package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK6 — AUTHORITATIVE outbound frame/compression handler (clean Forge
 * Target A; research runtime; default OFF via minecraftrust.frame_authority).
 *
 * AUTHORITY BOUNDARY (operator-authorized): Java remains authoritative for
 * packet SERIALIZATION — the canonical Rust input is the complete immutable
 * Java-serialized packet body (packet-ID VarInt included), exactly the input
 * the observer ladder validated across 53,140 live comparisons. This handler
 * takes authority ONLY for compression + length framing:
 *
 *   success:   encoder(serialized body) -> THIS handler builds the complete
 *              Rust frame off-pipeline -> writes it via the PREPENDER'S OWN
 *              context, which routes outbound starting head-ward of the
 *              prepender — BYPASSING the Java compressor and prepender —
 *              into the normal downstream path (encryption/socket).
 *   failure:   FAIL CLOSED TO JAVA — ctx.write(originalMsg, promise) from
 *              THIS context continues through the normal Java compressor and
 *              prepender, byte-for-byte the vanilla path. The original
 *              message's bytes, indices, and promise are untouched at the
 *              moment of fallback: the Rust frame is built fully BEFORE any
 *              production state is mutated (complete-frame-or-nothing; no
 *              partial writer-index advancement, no partial bytes, no
 *              double-compression, no double-prepending, no duplicated or
 *              lost packet — proven by injection regressions).
 *
 * The bypass is only exercised AFTER name-and-order pipeline verification
 * (liveInstall: prepender/compress/encoder present with prep<compress<enc in
 * the verified list order); any other layout leaves the channel 100% Java
 * with a counted fallback reason.
 *
 * Compression semantics: exact 1.12.2 threshold contract via the INSTALLED
 * compressor's own live threshold field (field_179301_c; below threshold =
 * VarInt(0) passthrough framing; at/above = [VarInt outer][VarInt
 * uncompressed-size][zlib stream]); live threshold updates honored per write.
 * Compressed byte identity with Java is NOT required (zlib-rs vs JDK); the
 * sampled cross-check independently decodes transmitted Rust frames and
 * compares the recovered body to the captured original.
 *
 * Lifecycle: one OutboundFrameCtx per channel created lazily on first
 * authoritative frame; freed under the proven quiescence protocol (free legal
 * only when removed && inFlightNative==0; ops increment before re-checking
 * removed; executor rejection with an op in flight defers the free to the
 * completing op) — identical to RustFrameHandler/observer.
 */
public final class FrameAuthorityHandler extends ChannelOutboundHandlerAdapter {

    public static final String MODE = System.getProperty("minecraftrust.frame_authority", "OFF").toUpperCase();
    public static final boolean ENABLED = "ON_EXPERIMENTAL".equals(MODE);

    // cross-check sampling (§10): every Nth authoritative frame is independently
    // structural-decoded and compared to the captured original — low-overhead,
    // separated from the production handoff, never re-runs Java framing.
    static final int CROSSCHECK_EVERY = Integer.getInteger("minecraftrust.frame_authority.crosscheck", 16);

    // ---- counters (fallbacks tracked BY REASON; authoritative split compressed/uncompressed) ----
    public final AtomicLong AUTH_PACKETS = new AtomicLong();
    public final AtomicLong AUTH_COMPRESSED = new AtomicLong();
    public final AtomicLong AUTH_UNCOMPRESSED = new AtomicLong();
    public final AtomicLong AUTH_BYTES_BODY = new AtomicLong();
    public final AtomicLong AUTH_BYTES_WIRE = new AtomicLong();
    public final AtomicLong CROSSCHECKS = new AtomicLong();
    public final AtomicLong CROSSCHECK_MISMATCHES = new AtomicLong();
    public final AtomicLong FALLBACK_PIPELINE = new AtomicLong();
    public final AtomicLong FALLBACK_NO_CTX = new AtomicLong();
    public final AtomicLong FALLBACK_NATIVE = new AtomicLong();
    public final AtomicLong FALLBACK_THRESHOLD = new AtomicLong();
    public final AtomicLong FALLBACK_UNSUPPORTED_INPUT = new AtomicLong();
    public final AtomicLong FALLBACK_CLOSING = new AtomicLong();
    public final AtomicLong FALLBACK_DISABLED = new AtomicLong();
    public final AtomicLong CTX_CREATED = new AtomicLong();
    public final AtomicLong CTX_FREED = new AtomicLong();
    public final AtomicLong AUTH_NATIVE_NS = new AtomicLong();
    public volatile String lastFallbackReason;

    public static final CopyOnWriteArrayList<FrameAuthorityHandler> LIVE = new CopyOnWriteArrayList<>();
    public static volatile String PIPELINE_LAYOUT_VERIFIED;
    public static volatile String PIPELINE_LAYOUT_SKIPPED;
    public static final AtomicLong INSTALL_OK = new AtomicLong();
    public static final AtomicLong INSTALL_SKIPPED = new AtomicLong();
    public static volatile String LAST_SKIP_REASON;

    private OutboundFrameCtx frameCtx;
    volatile boolean removed; // package-private: offline gates simulate closing
    private volatile boolean installed;
    private volatile ChannelHandlerContext prependerCtx; // bypass target, verified by name
    private final java.util.concurrent.atomic.AtomicInteger inFlightNative = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean freeDeferred;
    private final java.util.concurrent.atomic.AtomicBoolean freedOnce = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean freeRan = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile EventExecutor ownerExecutor;

    // offline injection seams (package-private, property-immune)
    boolean testForceNativeFail;       // every frame() call returns null (forced native error)
    boolean testForceCrosscheckFail;   // corrupt the sampled cross-check comparison

    private static volatile Field COMPRESS_THRESHOLD_FIELD;

    /** Live installation with name-and-order verification; fail closed. */
    public static FrameAuthorityHandler liveInstall(ChannelPipeline pipeline) {
        try {
            if (pipeline.get("mck5a-authority") != null) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "already-installed";
                return null;
            }
            if (pipeline.get("encoder") == null || pipeline.get("compress") == null || pipeline.get("prepender") == null) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "missing-handler";
                PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
                return null;
            }
            java.util.List<String> names = pipeline.names();
            int iPrep = names.indexOf("prepender"), iComp = names.indexOf("compress"), iEnc = names.indexOf("encoder");
            if (!(iPrep >= 0 && iComp > iPrep && iEnc > iComp)) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "order-verification-failed prep=" + iPrep + " comp=" + iComp + " enc=" + iEnc;
                PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
                return null;
            }
            FrameAuthorityHandler h = new FrameAuthorityHandler();
            // position: between compress and encoder in list order -> traversal
            // receives the Java-serialized body AFTER the encoder, BEFORE the
            // Java compressor. The bypass target (prepender's own context) is
            // captured AFTER installation and re-resolved defensively per write.
            pipeline.addBefore("encoder", "mck5a-authority", h);
            h.installed = true;
            LIVE.add(h);
            INSTALL_OK.incrementAndGet();
            if (PIPELINE_LAYOUT_VERIFIED == null) PIPELINE_LAYOUT_VERIFIED = layout(pipeline);
            return h;
        } catch (Throwable t) {
            INSTALL_SKIPPED.incrementAndGet();
            LAST_SKIP_REASON = "install-exception:" + t;
            PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
            return null;
        }
    }

    private static String layout(ChannelPipeline p) {
        try {
            String n = String.valueOf(p.names());
            return n.length() > 400 ? n.substring(0, 400) + "..." : n;
        } catch (Throwable t) {
            return "layout-unreadable:" + t;
        }
    }

    /** Live threshold read from the INSTALLED compressor's own field (ground truth). */
    private Integer effectiveThreshold(ChannelHandlerContext ctx) {
        try {
            Object compress = ctx.pipeline().get("compress");
            if (compress == null) return null;
            Field f = COMPRESS_THRESHOLD_FIELD;
            if (f == null) {
                f = compress.getClass().getDeclaredField("field_179301_c");
                f.setAccessible(true);
                COMPRESS_THRESHOLD_FIELD = f;
            }
            if (!f.getDeclaringClass().isInstance(compress)) return null;
            return Integer.valueOf(f.getInt(compress));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!ENABLED || removed || !installed || !(msg instanceof ByteBuf)) {
            if (!ENABLED || !installed) FALLBACK_DISABLED.incrementAndGet();
            else FALLBACK_CLOSING.incrementAndGet();
            ctx.write(msg, promise); // untouched Java path (authority off/closing/non-ByteBuf)
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        int len = in.readableBytes();

        Integer threshold = effectiveThreshold(ctx);
        if (threshold == null || threshold < -1) {
            FALLBACK_THRESHOLD.incrementAndGet();
            lastFallbackReason = "threshold-unreadable";
            ctx.write(msg, promise);
            return;
        }
        if (frameCtx == null) {
            OutboundFrameCtx c = OutboundFrameCtx.create();
            if (c == null || !c.isLive()) {
                FALLBACK_NO_CTX.incrementAndGet();
                lastFallbackReason = "native-ctx-unavailable";
                ctx.write(msg, promise);
                return;
            }
            frameCtx = c;
            CTX_CREATED.incrementAndGet();
        }
        // complete-frame-or-nothing: build the ENTIRE Rust frame off-pipeline
        // before touching any production state
        byte[] frame;
        byte[] snapshot = null; // cross-check sample (owned, bounded)
        inFlightNative.incrementAndGet();
        try {
            if (removed) throw new IllegalStateException("removed during native op");
            byte[] body = new byte[len];
            in.getBytes(in.readerIndex(), body); // does NOT change indices
            long t0 = System.nanoTime();
            boolean forceFail = testForceNativeFail;
            if (forceFail) testForceNativeFail = false; // one-shot seam (offline only)
            frame = forceFail ? null : frameCtx.frame(body, threshold.intValue());
            AUTH_NATIVE_NS.addAndGet(System.nanoTime() - t0);
            if (frame == null) throw new IllegalStateException("native frame failure");
            if (len > 0 && ((AUTH_PACKETS.get() % CROSSCHECK_EVERY) == 0)) snapshot = body;
        } catch (Throwable t) {
            FALLBACK_NATIVE.incrementAndGet();
            lastFallbackReason = "native:" + t;
            ctx.write(msg, promise); // FAIL CLOSED TO JAVA — original untouched
            return;
        } finally {
            if (inFlightNative.decrementAndGet() == 0 && freeDeferred) freeNow();
        }
        if (frame == null) { // defensive: unreachable, kept for clarity
            FALLBACK_NATIVE.incrementAndGet();
            ctx.write(msg, promise);
            return;
        }

        // sampled post-production cross-check (§10): independent structural
        // decode of the frame we are about to transmit vs the captured original
        if (snapshot != null) {
            CROSSCHECKS.incrementAndGet();
            FrameShadowObserver.Decoded d = FrameShadowObserver.structurallyDecode(frame, threshold.intValue() >= 0);
            boolean ok = d != null && d.consumedExactly && java.util.Arrays.equals(d.body, snapshot);
            if (testForceCrosscheckFail) { testForceCrosscheckFail = false; ok = false; }
            if (!ok) CROSSCHECK_MISMATCHES.incrementAndGet();
        }

        // ATOMIC HANDOFF: exactly one complete frame, written via the
        // prepender's OWN context — outbound traversal resumes head-ward of
        // the prepender, bypassing the Java compressor and prepender into the
        // normal downstream (encryption/socket) path.
        ChannelHandlerContext bypass = prependerCtx != null ? prependerCtx : ctx.pipeline().context("prepender");
        if (bypass == null) {
            FALLBACK_PIPELINE.incrementAndGet();
            lastFallbackReason = "prepender-context-lost";
            // frame built but not emitted — nothing partial was forwarded; fall back
            ctx.write(msg, promise);
            return;
        }
        prependerCtx = bypass;
        ByteBuf out = Unpooled.buffer(frame.length).writeBytes(frame);
        AUTH_PACKETS.incrementAndGet();
        if (threshold.intValue() >= 0 && len >= threshold.intValue()) AUTH_COMPRESSED.incrementAndGet();
        else AUTH_UNCOMPRESSED.incrementAndGet();
        AUTH_BYTES_BODY.addAndGet(len);
        AUTH_BYTES_WIRE.addAndGet(frame.length);
        bypass.write(out, promise); // single complete frame; original msg consumed by us
    }

    // ---------------- lifecycle (quiescence protocol, proven pattern) ----------------
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        removed = true;
        freeNative();
        // NOTE: handlers STAY in LIVE after close — the final shutdown dump
        // aggregates the list (removal zeroed the MCK5LR-221021 aggregate);
        // the list is bounded by connection count for the campaign lifetime.
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        removed = true;
        freeNative();
        super.close(ctx, promise);
    }

    private void freeNow() {
        if (freeRan.compareAndSet(false, true)) {
            OutboundFrameCtx c = frameCtx;
            frameCtx = null;
            if (c != null) { c.free(); CTX_FREED.incrementAndGet(); }
        }
    }

    private void freeNative() {
        if (!freedOnce.compareAndSet(false, true)) return;
        Runnable attempt = () -> {
            if (inFlightNative.get() > 0) freeDeferred = true;
            else freeNow();
        };
        EventExecutor owner = ownerExecutor;
        if (owner == null || owner.inEventLoop()) attempt.run();
        else {
            try {
                owner.execute(attempt);
            } catch (Throwable rejected) {
                attempt.run();
            }
        }
    }

    void captureOwner(EventExecutor ex) { ownerExecutor = ex; }

    public static String dumpMetrics() {
        long auth = 0, comp = 0, uncomp = 0, bin = 0, wire = 0, cc = 0, ccm = 0;
        long fbPipe = 0, fbCtx = 0, fbNat = 0, fbThr = 0, fbUnsup = 0, fbClos = 0, fbDis = 0, created = 0, freed = 0, ns = 0;
        for (FrameAuthorityHandler h : LIVE) {
            auth += h.AUTH_PACKETS.get(); comp += h.AUTH_COMPRESSED.get(); uncomp += h.AUTH_UNCOMPRESSED.get();
            bin += h.AUTH_BYTES_BODY.get(); wire += h.AUTH_BYTES_WIRE.get();
            cc += h.CROSSCHECKS.get(); ccm += h.CROSSCHECK_MISMATCHES.get();
            fbPipe += h.FALLBACK_PIPELINE.get(); fbCtx += h.FALLBACK_NO_CTX.get(); fbNat += h.FALLBACK_NATIVE.get();
            fbThr += h.FALLBACK_THRESHOLD.get(); fbUnsup += h.FALLBACK_UNSUPPORTED_INPUT.get();
            fbClos += h.FALLBACK_CLOSING.get(); fbDis += h.FALLBACK_DISABLED.get();
            created += h.CTX_CREATED.get(); freed += h.CTX_FREED.get(); ns += h.AUTH_NATIVE_NS.get();
        }
        return "mck6auth.mode=" + MODE + " installOk=" + INSTALL_OK.get() + " installSkipped=" + INSTALL_SKIPPED.get()
                + " lastSkipReason=" + LAST_SKIP_REASON
                + "\nmck6auth.channels=" + LIVE.size()
                + "\nmck6auth.packets=" + auth + " compressed=" + comp + " uncompressed=" + uncomp
                + " bodyBytes=" + bin + " wireBytes=" + wire
                + "\nmck6auth.crosschecks=" + cc + " crosscheckMismatches=" + ccm
                + "\nmck6auth.fallbacks pipeline=" + fbPipe + " noCtx=" + fbCtx + " native=" + fbNat
                + " threshold=" + fbThr + " unsupportedInput=" + fbUnsup + " closing=" + fbClos + " disabled=" + fbDis
                + "\nmck6auth.ctxCreated=" + created + " ctxFreed=" + freed
                + " nativeNs=" + ns
                + "\nmck6auth.pipelineVerified=" + PIPELINE_LAYOUT_VERIFIED
                + "\nmck6auth.pipelineSkipped=" + PIPELINE_LAYOUT_SKIPPED;
    }
}
