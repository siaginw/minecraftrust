package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK5 Checkpoint A — TRUE offline SHADOW observer around the INSTALLED Java
 * outbound framing pipeline (research only; default OFF; NOT registered in any
 * production path).
 *
 * What this is: Java remains the sole authoritative producer. The observer
 * COPIES data as it flows and independently verifies it, then discards its
 * copies. It must NOT be confused with RustFrameHandler, which REPLACES the
 * framing stages — this observer replaces nothing.
 *
 * Observer positions (from the ACTUAL installed execution order, verified by
 * disassembly of minecraft_server.1.12.2.srg.jar): production outbound
 * traversal is packet_handler -> encoder (serialize) -> compress ->
 * prepender (outer VarInt) -> encrypt -> head, because NetworkSystem$4 adds
 * [timeout, legacy_query, splitter, decoder, prepender, encoder,
 * packet_handler] and NetworkManager adds compress addBefore("encoder") and
 * encrypt addBefore("prepender"). Therefore:
 *  - CAPTURE handler sits immediately TAIL-ward of the compression stage
 *    (the "encoder"-adjacent slot): sees the complete serialized packet body
 *    (packet-ID VarInt included) AFTER Java serialization, BEFORE
 *    compression/framing.
 *  - FRAME-OBSERVE handler sits at the ENCRYPT position (head-ward of the
 *    prepender): sees the actual Java frame AFTER outer framing, BEFORE
 *    encryption. Offline there is no encryption handler; the position is
 *    identical.
 *
 * Per write, for a SELECTED sample (deterministic sampling, explicit bounds):
 *  1. capture: owned byte[] snapshot via msg.getBytes(readerIndex, ...) — a
 *     genuinely owned copy (NOT a retained slice/duplicate, which only protect
 *     lifetime and still share content); bounded re-read of evenly spaced
 *     offsets verifies read stability during the copy window (a torn-write
 *     detector, not a proof of source quiescence — the source is read under
 *     the pipeline's single-thread ownership interval of this write call);
 *     original reader/writer indices untouched; the ORIGINAL message and its
 *     promise are forwarded exactly once, unmodified.
 *  2. frame observation: owned copy of the framed output at the encrypt
 *     position; completes the observation BEFORE forwarding so the capture's
 *     promise listener (which fires nested inside the forwarded write on
 *     EmbeddedChannel) never sees a falsely-unpaired sample.
 *  3. comparison (synchronous, single-owner — no worker pool):
 *     independently decode the JAVA frame (outer VarInt, dataLen VarInt,
 *     inflate if needed) and compare the recovered body — packet ID included —
 *     against the captured original; then have the RUST frame engine frame its
 *     OWN copy of the original, independently decode THAT, and compare again.
 *     Compressed-byte equality is NOT required (JDK deflate vs zlib-rs produce
 *     different valid streams — proven M-CK4.2); for passthrough/disabled
 *     framings the exact frame bytes may additionally be compared.
 *  4. discard: every observer-owned copy is dropped; native output is NEVER
 *     sent, substituted, or retained past the call.
 *
 * PAIRING: per-observer (per-channel) sequence numbers; the installed boundary
 * is one-frame-per-message (NettyCompressionEncoder and NettyVarint21FrameEncoder
 * are MessageToByteEncoder 1-in-1-out and no aggregator sits between the two
 * positions) — this contract is VALIDATED per observation: the structural
 * decode must consume exactly the observed buffer. Anything unexpected
 * (trailing bytes, decode failure, seq mismatch, composite at the frame
 * boundary) is counted and SKIPPED — never manufactured into a MATCH. At most
 * ONE observation is outstanding (single-owner synchronous design), bounded by
 * construction; a capture whose write completes without a frame observation
 * (Java encode failure, observer removed mid-flight) is retired as unpaired by
 * a promise listener that releases observer-owned data.
 *
 * OFF MODE: forwarding only — no native context is ever created and no
 * per-packet sample buffers are allocated (counter-verified).
 *
 * FAILURE CONTAINMENT: any recoverable observer problem (native error, decode
 * anomaly, limit reached) disables or skips observation with a precise reason
 * and preserves the normal Java path untouched. Unrelated Java codec failures
 * still propagate unchanged. A native process crash is NOT recoverable by this
 * or any Java handler — no such claim is made.
 *
 * LIFECYCLE (quiescence contract, same protocol as RustFrameHandler M-CK5):
 * the native frame context is created lazily on the first COMPLETED sample
 * comparison and freed only when removed==true && inFlightNative==0; ops
 * increment inFlightNative before re-checking removed; a rejected executor
 * submission defers the free to the completing op rather than freeing beneath
 * in-flight work.
 */
public final class FrameShadowObserver {

    // ---- configuration (explicit bounds; deterministic sampling) ----
    public static final class Config {
        public volatile boolean enabled = false;   // default OFF
        public int sampleEvery = 1;                // deterministic: seq % sampleEvery == 0 selects
        public int maxSampleBytes = 4 << 20;       // oversized -> skipped, counted
        public long maxComparisons = Long.MAX_VALUE;
        public boolean exactBytesForUncompressed = true; // passthrough/disabled: Java==Rust frame bytes
    }

    // ---- counters (reported separately; injected expected failures are not successes) ----
    public final AtomicLong OBSERVED = new AtomicLong();      // messages seen at capture position
    public final AtomicLong SELECTED = new AtomicLong();      // sampled for comparison
    public final AtomicLong COMPLETED = new AtomicLong();     // full comparisons performed
    public final AtomicLong MATCHED = new AtomicLong();       // BOTH java and rust recovered the original
    public final AtomicLong MISMATCHED = new AtomicLong();
    public final AtomicLong UNPAIRED_FRAME = new AtomicLong();   // frame seen with no selected capture
    public final AtomicLong UNPAIRED_CAPTURE = new AtomicLong(); // capture completed without its frame
    public final AtomicLong OBSERVER_ERRORS = new AtomicLong();  // recoverable observer failures
    public final AtomicLong CTX_CREATED = new AtomicLong();
    public final AtomicLong CTX_FREED = new AtomicLong();
    public final AtomicLong RETAINED_OBSERVATIONS = new AtomicLong(); // current outstanding (0 or 1)
    public final AtomicLong RETAINED_BYTES = new AtomicLong();
    public final AtomicLong SKIPPED_OVERSIZE = new AtomicLong();
    public final AtomicLong SKIPPED_EMPTY = new AtomicLong();
    public final AtomicLong SKIPPED_LIMIT = new AtomicLong();
    public final AtomicLong SKIPPED_UNREADABLE_THRESHOLD = new AtomicLong();
    public final AtomicLong SKIPPED_FRAME_ANOMALY = new AtomicLong();  // 1:1 contract violation detected
    public final AtomicLong SKIPPED_STABILITY = new AtomicLong();     // torn-read detector tripped
    public final AtomicLong SAMPLED_BYTES = new AtomicLong();          // total bytes copied into owned samples
    public final AtomicLong COMPLETED_COMPRESSED = new AtomicLong();   // comparisons where dataLen>0 (inflate path)
    public final AtomicLong COMPLETED_PASSTHROUGH = new AtomicLong();  // comparisons where framing was uncompressed
    /** Bounded root-cause diagnostics: first N mismatches per observer —
     *  lengths + packet-id byte + hex heads of captured vs java-decoded body. */
    final java.util.concurrent.atomic.AtomicInteger diagLeft = new java.util.concurrent.atomic.AtomicInteger(3);
    public final java.util.List<String> MISMATCH_DIAGNOSTICS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Bounded ordered write-trace (first N events per observer): 'C seq len'
     *  at capture, 'F len <pendingSeq|- >' at frame-observe — reveals bypassed,
     *  dropped, or reordered writes in modded pipelines. Property-gated OFF. */
    public static final boolean TRACE = "true".equalsIgnoreCase(System.getProperty("minecraftrust.frame_shadow.trace", "false"));
    final java.util.concurrent.atomic.AtomicInteger traceLeft = new java.util.concurrent.atomic.AtomicInteger(40);
    public final java.util.List<String> WRITE_TRACE = new java.util.concurrent.CopyOnWriteArrayList<>();
    public volatile String disabledReason;                     // set when observer disables itself

    // ---- per-channel pairing state ----
    private static final class Pending {
        final long seq;
        final byte[] original;      // observer-OWNED copy
        final int thresholdAtWrite; // effective compression state at this boundary (-1 = disabled)
        volatile boolean completed;
        Pending(long seq, byte[] original, int t) { this.seq = seq; this.original = original; this.thresholdAtWrite = t; }
    }

    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicLong seqGen = new AtomicLong();
    private final Config cfg;
    private final ChannelPipeline pipeline;

    // ---- native context: lazy on first completed comparison; quiescence-freed ----
    private OutboundFrameCtx frameCtx;
    private final java.util.concurrent.atomic.AtomicInteger inFlightNative = new AtomicInteger();
    private volatile boolean removed, freeDeferred;
    private final java.util.concurrent.atomic.AtomicBoolean freedOnce = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile EventExecutor ownerExecutor;

    // ---- offline fault injection (package-private, property-immune) ----
    boolean testCorruptSample;    // flip a byte in the sample fed to comparison (same length)
    boolean testCorruptRustFrame; // flip a byte in the rust frame before decoding
    boolean testPairingCorrupt;   // complete against the WRONG (stale) pending slot
    boolean testSimulateCtxCreateFail; // first ctx creation reports failure
    CountDownLatch testNativeEntryLatch; // block inside the native op (quiescence tests)
    volatile CountDownLatch testNativeEntered; // counts down when the block point is reached
    private byte[] lastOriginal; // previous completed sample — used ONLY by the pairing-corruption seam
    // debug observability
    volatile int dbgJavaFrameLen = -1, dbgOriginalLen = -1, dbgBodyLen = -1, dbgThresholdAtWrite = -999;
    volatile boolean dbgJavaOk, dbgRustOk;

    // installed-compressor threshold reflection (actual state at the boundary)
    private static volatile Field COMPRESS_THRESHOLD_FIELD;
    private volatile boolean thresholdReflectionFailed;

    private FrameShadowObserver(ChannelPipeline pipeline, Config cfg) {
        this.pipeline = pipeline;
        this.cfg = cfg;
    }

    void setEnabled(boolean v) { cfg.enabled = v; }
    void setSampleEvery(int n) { cfg.sampleEvery = n; }

    // ---- M-CK5-LIVE install counters (fail-closed reasons, reported separately) ----
    public static final AtomicLong INSTALL_OK = new AtomicLong();
    public static final AtomicLong INSTALL_SKIPPED = new AtomicLong();
    public static volatile String LAST_SKIP_REASON;
    /** Live lifecycle verification: incremented in the capture handler's
     *  handlerRemoved AFTER freeNative() — by then teardown runs on the
     *  channel's own event loop with no in-flight op, so the free has happened
     *  inline and the counters are final. (A closeFuture listener would fire
     *  BEFORE teardown frees — wrong lifecycle point.) */
    public static final AtomicLong CHANNELS_CLEAN_CLOSE = new AtomicLong();
    /** M-CK5-LIVE evidence: the verified pipeline layout (head->tail handler
     *  names) of the FIRST successfully verified channel, and the layout at
     *  the most recent fail-closed skip (with its reason) — recorded so
     *  modded pipelines are documented, never guessed. */
    public static volatile String PIPELINE_LAYOUT_VERIFIED;
    public static volatile String PIPELINE_LAYOUT_SKIPPED;

    private static String layout(ChannelPipeline p) {
        try {
            String n = String.valueOf(p.names());
            return n.length() > 400 ? n.substring(0, 400) + "..." : n;
        } catch (Throwable t) {
            return "layout-unreadable:" + t;
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    /**
     * M-CK5-LIVE installation for the REAL production pipeline. Positions are
     * derived from handler NAMES with layout VERIFICATION before any mutation
     * (never guessed): the installed live pipeline is, head->tail,
     * [timeout, legacy_query, splitter, (decrypt), decoder, (encrypt),
     * prepender, compress, encoder, packet_handler] (NetworkSystem$4 addLast
     * order + NetworkManager addBefore("encoder","compress") /
     * addBefore("prepender","encrypt") — verified by disassembly).
     *  - CAPTURE: addBefore("encoder") -> between compress and encoder in list
     *    order -> outbound traversal (tail->head) sees it AFTER the serializer
     *    (complete serialized body, packet ID included) and BEFORE compression.
     *    Robust to extra handlers: strict reverse-list traversal guarantees
     *    encoder->capture->compress regardless of what sits elsewhere.
     *  - FRAME-OBSERVE: addBefore("prepender") -> immediately head-ward of the
     *    prepender -> traversal sees the ACTUAL Java frame AFTER outer framing
     *    and BEFORE everything head-ward of prepender (including encryption).
     * FAIL-CLOSED: if the expected handlers are missing or the verified
     * order (prepender head-ward of compress head-ward of encoder) does not
     * hold, NOTHING is installed, the skip is counted with a reason, and Java
     * is untouched. Never double-installs (existing mck5-* names -> skip).
     */
    public static FrameShadowObserver liveInstall(ChannelPipeline pipeline, Config cfg) {
        try {
            if (pipeline.get("mck5-capture") != null || pipeline.get("mck5-frame-observe") != null) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "already-installed";
                return null;
            }
            if (pipeline.get("encoder") == null || pipeline.get("compress") == null || pipeline.get("prepender") == null) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "missing-handler:"
                        + (pipeline.get("encoder") == null ? "encoder " : "")
                        + (pipeline.get("compress") == null ? "compress " : "")
                        + (pipeline.get("prepender") == null ? "prepender" : "");
                PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
                return null;
            }
            java.util.List<String> names = pipeline.names(); // head -> tail
            int iPrep = names.indexOf("prepender"), iComp = names.indexOf("compress"), iEnc = names.indexOf("encoder");
            if (!(iPrep >= 0 && iComp > iPrep && iEnc > iComp)) {
                INSTALL_SKIPPED.incrementAndGet();
                LAST_SKIP_REASON = "order-verification-failed prep=" + iPrep + " comp=" + iComp + " enc=" + iEnc;
                PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
                return null;
            }
            FrameShadowObserver obs = new FrameShadowObserver(pipeline, cfg);
            // ORDER: frame-observe FIRST, then capture. If a write slips between
            // the two installs it is an extra FRAME with no pending (benign,
            // counted UNPAIRED_FRAME) — the reverse order could orphan a CAPTURE
            // whose frame is then mis-paired (MCK5LR live-defect class).
            pipeline.addBefore("prepender", "mck5-frame-observe", obs.new FrameObserveHandler());
            pipeline.addBefore("encoder", "mck5-capture", obs.new CaptureHandler());
            INSTALL_OK.incrementAndGet();
            if (PIPELINE_LAYOUT_VERIFIED == null) PIPELINE_LAYOUT_VERIFIED = layout(pipeline);
            return obs;
        } catch (Throwable t) {
            INSTALL_SKIPPED.incrementAndGet();
            LAST_SKIP_REASON = "install-exception:" + t;
            PIPELINE_LAYOUT_SKIPPED = layout(pipeline);
            return null;
        }
    }

    /** Per-observer state for the live aggregation dump. */
    public String dumpState() {
        return "observer[enabled=" + cfg.enabled + " observed=" + OBSERVED.get() + " selected=" + SELECTED.get()
                + " completed=" + COMPLETED.get() + " completedCompressed=" + COMPLETED_COMPRESSED.get()
                + " completedUncompressed=" + COMPLETED_PASSTHROUGH.get()
                + " matched=" + MATCHED.get() + " mismatched=" + MISMATCHED.get()
                + " unpairedFrame=" + UNPAIRED_FRAME.get() + " unpairedCapture=" + UNPAIRED_CAPTURE.get()
                + " errors=" + OBSERVER_ERRORS.get() + " skippedOver=" + SKIPPED_OVERSIZE.get()
                + " skippedEmpty=" + SKIPPED_EMPTY.get() + " skippedLimit=" + SKIPPED_LIMIT.get()
                + " skippedThreshold=" + SKIPPED_UNREADABLE_THRESHOLD.get() + " frameAnomaly=" + SKIPPED_FRAME_ANOMALY.get()
                + " stability=" + SKIPPED_STABILITY.get() + " ctxCreated=" + CTX_CREATED.get() + " ctxFreed=" + CTX_FREED.get()
                + " retainedObs=" + RETAINED_OBSERVATIONS.get() + " retainedBytes=" + RETAINED_BYTES.get()
                + " sampledBytes=" + SAMPLED_BYTES.get()
                + " disabledReason=" + disabledReason + "]";
    }

    /** Aggregate live counters across per-channel observers (hook-side registry). */
    public long completed() { return COMPLETED.get(); }
    public long mismatched() { return MISMATCHED.get(); }
    public long matched() { return MATCHED.get(); }

    /**
     * Installs the two observer handlers around the OFFLINE framing pipeline.
     * Final head->tail order: [mck5-frame-observe, ...prepender..., ...compress...,
     * mck5-capture] — outbound traversal reaches CAPTURE first (raw serialized
     * body, pre-compression) and FRAME-OBSERVE last (actual frame, post-prepender,
     * at the production encrypt position). The prepender/compress stages must
     * already exist.
     */
    public static FrameShadowObserver install(ChannelPipeline pipeline, Config cfg) {
        FrameShadowObserver obs = new FrameShadowObserver(pipeline, cfg);
        pipeline.addFirst("mck5-frame-observe", obs.new FrameObserveHandler()); // head-most: after framing
        pipeline.addLast("mck5-capture", obs.new CaptureHandler());             // tail-most: before compression
        return obs;
    }

    /** Offline removal API: detaches both handlers, releases owned data, frees native ctx. */
    public void uninstall() {
        removed = true;
        retirePending("uninstalled");
        ChannelPipeline p = pipeline;
        removeQuietly(p, "mck5-capture");
        removeQuietly(p, "mck5-frame-observe");
        freeNative();
    }

    private static void removeQuietly(ChannelPipeline p, String name) {
        try { p.remove(name); } catch (Throwable ignore) { }
    }

    // ---------------- capture position (tail-ward of compress) ----------------
    final class CaptureHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!cfg.enabled || removed || !(msg instanceof ByteBuf)) {
                ctx.write(msg, promise); // OFF/removed/non-ByteBuf: untouched forwarding, no allocations
                return;
            }
            ByteBuf in = (ByteBuf) msg;
            OBSERVED.incrementAndGet();
            long seq = seqGen.incrementAndGet();
            // PAIRING HARDENING v2 (MCK5LR live defect, both holes): in a
            // strictly in-order single-owner pipeline, frame(X) is always
            // observed BEFORE the next message reaches this capture boundary —
            // for SELECTED and SKIPPED messages alike. Therefore ANY pending
            // still incomplete at ANY capture event (selected or sampling-
            // skipped) is provably stale: its frame was dropped/bypassed/
            // rerouted by a modded pipeline (observed on Forge: the FML|MP
            // multipart HEADER write vanishing between capture and the
            // prepender). Retire it as UNPAIRED so no later frame can ever be
            // absorbed into the wrong sample. (v1 retired only on selected
            // captures — the sampled-out next message left the stale pending
            // absorbable, reproduced live at every=2.)
            Pending stale = pending.getAndSet(null);
            if (stale != null && !stale.completed) {
                stale.completed = true;
                UNPAIRED_CAPTURE.incrementAndGet();
            }
            boolean selected = cfg.sampleEvery > 0 && seq % cfg.sampleEvery == 0
                    && COMPLETED.get() + countSkipsForBudget() < Long.MAX_VALUE;
            int len = in.readableBytes();
            if (!selected) { ctx.write(msg, promise); return; }
            if (len == 0) { SKIPPED_EMPTY.incrementAndGet(); ctx.write(msg, promise); return; }
            if (len > cfg.maxSampleBytes) { SKIPPED_OVERSIZE.incrementAndGet(); ctx.write(msg, promise); return; }
            if (COMPLETED.get() >= cfg.maxComparisons) { SKIPPED_LIMIT.incrementAndGet(); ctx.write(msg, promise); return; }
            Integer threshold = effectiveThreshold(ctx);
            if (threshold == null) {
                SKIPPED_UNREADABLE_THRESHOLD.incrementAndGet();
                disable("threshold unreadable at capture boundary");
                ctx.write(msg, promise);
                return;
            }
            byte[] snapshot = new byte[len];
            in.getBytes(in.readerIndex(), snapshot); // owned copy; indices untouched
            if (!readStableSample(in, snapshot)) {   // torn-read detector over the copy window
                SKIPPED_STABILITY.incrementAndGet();
                OBSERVER_ERRORS.incrementAndGet();
                ctx.write(msg, promise);
                return;
            }
            Pending p = new Pending(seq, snapshot, threshold);
            if (TRACE && traceLeft.decrementAndGet() >= 0) {
                WRITE_TRACE.add("C#" + seq + ":" + len);
            }
            pending.set(p); // stale predecessor already retired above
            SELECTED.incrementAndGet();
            SAMPLED_BYTES.addAndGet(snapshot.length);
            RETAINED_OBSERVATIONS.set(1);
            RETAINED_BYTES.set(snapshot.length);
            // Retire the sample if its write completes without a frame observation
            // (Java encode failure upstream of the frame position, or observer
            // removal mid-flight). Runs nested inside the forwarded write chain on
            // EmbeddedChannel, AFTER FrameObserveHandler completes the pairing.
            promise.addListener(f -> {
                Pending cur = pending.get();
                if (cur == p && !cur.completed) {
                    UNPAIRED_CAPTURE.incrementAndGet();
                    pending.compareAndSet(p, null);
                    RETAINED_OBSERVATIONS.set(0);
                    RETAINED_BYTES.set(0);
                }
            });
            ctx.write(msg, promise); // ORIGINAL message + promise, exactly once, indices untouched
        }

        /** Installed-pipeline encode failures surface via fireExceptionCaught
         *  (MessageToByteEncoder does NOT fail the write promise): retire an
         *  incomplete sample as unpaired, then FORWARD the exception unchanged —
         *  the observer must never alter Java failure behavior. */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) throws Exception {
            retireIncompletePending();
            ctx.fireExceptionCaught(t);
        }

        /** LIVE lifecycle: channels close without an explicit uninstall —
         *  retire owned data and quiescence-free the native context exactly
         *  once when the handler leaves the pipeline (idempotent with
         *  uninstall()). Teardown runs on the channel's own event loop, so the
         *  inline free completes before this returns; verify the balance HERE
         *  (the final lifecycle point), not on closeFuture. */
        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            removed = true;
            retireIncompletePending();
            freeNative();
            if (CTX_CREATED.get() == CTX_FREED.get() && RETAINED_BYTES.get() == 0) {
                CHANNELS_CLEAN_CLOSE.incrementAndGet();
            }
        }
    }

    private void retireIncompletePending() {
        Pending p = pending.get();
        if (p != null && !p.completed) {
            UNPAIRED_CAPTURE.incrementAndGet();
            pending.compareAndSet(p, null);
            RETAINED_OBSERVATIONS.set(0);
            RETAINED_BYTES.set(0);
        }
    }

    private long countSkipsForBudget() { return 0; } // budget enforced explicitly above; placeholder for clarity

    /**
     * Bounded torn-read verification: re-read evenly spaced offsets (plus first
     * and last) and compare to the snapshot. This detects a concurrent source
     * write DURING the copy window; it does not claim to repair one — a trip
     * skips the sample (counted) rather than comparing unstable data.
     */
    private boolean readStableSample(ByteBuf in, byte[] snapshot) {
        int n = snapshot.length;
        int checks = Math.min(9, n);
        for (int i = 0; i < checks; i++) {
            int idx = i == 0 ? 0 : (int) ((long) (n - 1) * i / (checks - 1));
            if (in.getByte(in.readerIndex() + idx) != snapshot[idx]) return false;
        }
        return true;
    }

    /** Effective compression state at THIS message boundary, read from the
     *  INSTALLED compressor's own threshold field (ground truth). Null = could
     *  not determine -> unknown state is never guessed. */
    private Integer effectiveThreshold(ChannelHandlerContext captureCtx) {
        ChannelPipeline p = captureCtx.pipeline();
        io.netty.channel.ChannelHandler compress = p.get("compress");
        if (compress == null) return Integer.valueOf(-1); // disabled contract: handler absent
        try {
            Field f = COMPRESS_THRESHOLD_FIELD;
            if (f == null) {
                // verified by javap: NettyCompressionEncoder's int threshold is field_179301_c
                // (field_179300_a is its byte[] deflate scratch — NOT the threshold)
                f = compress.getClass().getDeclaredField("field_179301_c");
                f.setAccessible(true);
                COMPRESS_THRESHOLD_FIELD = f;
            }
            if (!f.getDeclaringClass().isInstance(compress)) { thresholdReflectionFailed = true; return null; }
            return Integer.valueOf(f.getInt(compress));
        } catch (Throwable t) {
            thresholdReflectionFailed = true;
            return null;
        }
    }

    // ---------------- frame-observe position (encrypt slot, head-ward of prepender) ----------------
    final class FrameObserveHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Pending p = pending.get();
            if (TRACE && msg instanceof ByteBuf && traceLeft.decrementAndGet() >= 0) {
                WRITE_TRACE.add("F:" + ((ByteBuf) msg).readableBytes() + "<-" + (p == null ? "-" : ("#" + p.seq + (p.completed ? "c" : ""))));
            }
            if (cfg.enabled && !removed && p != null && !p.completed && msg instanceof ByteBuf) {
                ByteBuf frameBuf = (ByteBuf) msg;
                int flen = frameBuf.readableBytes();
                byte[] javaFrame = new byte[flen];
                frameBuf.getBytes(frameBuf.readerIndex(), javaFrame); // owned copy; indices untouched
                // Complete BEFORE forwarding: the capture promise listener fires
                // nested inside the forwarded write on EmbeddedChannel.
                Pending target = p;
                if (testPairingCorrupt) {
                    // REAL stale-sample pairing: associate this Java frame with the
                    // PREVIOUS message's captured original (different bytes) — the
                    // comparison must detect it as a mismatch, never a MATCH.
                    testPairingCorrupt = false;
                    byte[] stale = lastOriginal != null ? lastOriginal : p.original;
                    target = new Pending(p.seq, stale, p.thresholdAtWrite);
                }
                completeObservation(target, javaFrame);
            } else if (cfg.enabled && !removed && p == null && msg instanceof ByteBuf) {
                UNPAIRED_FRAME.incrementAndGet(); // frame with no selected capture (sampling skip)
            }
            ctx.write(msg, promise); // actual Java frame forwarded UNTOUCHED
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            removed = true; // mirror of the capture handler's LIVE lifecycle cleanup
        }
    }

    private void completeObservation(Pending p, byte[] javaFrame) {
        try {
            p.completed = true;
            pending.compareAndSet(p, null);
            RETAINED_OBSERVATIONS.set(0);
            RETAINED_BYTES.set(0);
            byte[] original = p.original;
            if (testCorruptSample) {
                testCorruptSample = false;
                original = original.clone();
                original[original.length / 2] ^= 0x5A; // same-length deliberate change
            }
            boolean compressedFraming = p.thresholdAtWrite >= 0; // disabled contract: NO dataLen byte
            // (1) independent JAVA-frame decode: structural decode validates the
            // one-frame-per-message contract (must consume exactly the buffer)
            Decoded jd = structurallyDecode(javaFrame, compressedFraming);
            if (jd == null) {
                SKIPPED_FRAME_ANOMALY.incrementAndGet();
                OBSERVER_ERRORS.incrementAndGet();
                disable("java frame failed structural decode (1:1 contract unproven)");
                return;
            }
            boolean javaOk = jd.consumedExactly && java.util.Arrays.equals(jd.body, original);
            // (2) independent RUST frame of the observer-owned copy
            if (frameCtx == null) {
                if (testSimulateCtxCreateFail) { testSimulateCtxCreateFail = false; frameCtx = null; }
                else {
                    frameCtx = OutboundFrameCtx.create();
                    if (!(frameCtx != null && frameCtx.isLive())) frameCtx = null;
                    else CTX_CREATED.incrementAndGet();
                }
                if (frameCtx == null) { OBSERVER_ERRORS.incrementAndGet(); disable("native frame context unavailable"); return; }
            }
            inFlightNative.incrementAndGet();
            byte[] rustFrame = null;
            try {
                if (removed) throw new IllegalStateException("removed during native op");
                CountDownLatch enteredSig = testNativeEntered;
                if (enteredSig != null) { testNativeEntered = null; enteredSig.countDown(); }
                CountDownLatch latch = testNativeEntryLatch;
                if (latch != null) { testNativeEntryLatch = null; latch.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                rustFrame = frameCtx.frame(original, p.thresholdAtWrite);
            } catch (Throwable t) {
                rustFrame = null;
            } finally {
                if (inFlightNative.decrementAndGet() == 0 && freeDeferred) freeNow(); // deferred free runs at quiescence
            }
            if (rustFrame == null) {
                OBSERVER_ERRORS.incrementAndGet();
                disable("native frame encode failed");
                return;
            }
            if (testCorruptRustFrame) {
                testCorruptRustFrame = false;
                rustFrame = rustFrame.clone();
                rustFrame[rustFrame.length / 2] ^= 0x5A;
            }
            Decoded rd = structurallyDecode(rustFrame, compressedFraming);
            boolean rustOk = rd != null && rd.consumedExactly && java.util.Arrays.equals(rd.body, original);
            boolean passthrough = compressedFraming && jd.dataLen == 0;
            boolean deterministicFraming = !compressedFraming || passthrough;
            if (cfg.exactBytesForUncompressed && deterministicFraming && !java.util.Arrays.equals(javaFrame, rustFrame)) {
                rustOk = false; // deterministic framing must be byte-exact
            }
            COMPLETED.incrementAndGet();
            boolean uncompressed = !compressedFraming || jd.dataLen == 0; // passthrough or disabled framing
            if (uncompressed) COMPLETED_PASSTHROUGH.incrementAndGet(); else COMPLETED_COMPRESSED.incrementAndGet();
            if (javaOk && rustOk) MATCHED.incrementAndGet();
            else {
                MISMATCHED.incrementAndGet();
                if (!(javaOk && rustOk) && diagLeft.decrementAndGet() >= 0) {
                    StringBuilder d = new StringBuilder("seq=").append(p.seq)
                            .append(" thr=").append(p.thresholdAtWrite)
                            .append(" capLen=").append(original.length)
                            .append(" frameLen=").append(javaFrame.length)
                            .append(" jdNull=").append(jd == null)
                            .append(" jdConsumed=").append(jd != null && jd.consumedExactly)
                            .append(" jdDataLen=").append(jd == null ? -1 : jd.dataLen)
                            .append(" jdBodyLen=").append(jd == null ? -1 : jd.body.length);
                    if (jd != null && jd.body.length > 0 && original.length > 0) {
                        d.append(" capId=0x").append(Integer.toHexString(original[0] & 0xFF))
                                .append(" jdId=0x").append(Integer.toHexString(jd.body[0] & 0xFF))
                                .append(" capHead=").append(hex(original, 16))
                                .append(" jdHead=").append(hex(jd.body, 16));
                    }
                    MISMATCH_DIAGNOSTICS.add(d.toString());
                }
                disable(javaOk ? "rust comparison mismatch" : "java frame did not recover the captured body");
            }
            // debug observability (package-private, offline)
            dbgJavaFrameLen = javaFrame.length; dbgOriginalLen = original.length; dbgBodyLen = jd.body.length;
            dbgJavaOk = javaOk; dbgRustOk = rustOk; dbgThresholdAtWrite = p.thresholdAtWrite;
            if (javaOk && rustOk) lastOriginal = original; // pristine original of the last completed sample
            // (3) discard: javaFrame/original/rustFrame go out of scope; native output never forwarded
        } catch (Throwable t) {
            OBSERVER_ERRORS.incrementAndGet();
            disable("observer comparison failure: " + t);
        }
    }

    private void disable(String reason) {
        if (disabledReason == null) disabledReason = reason;
        cfg.enabled = false;
    }

    private void retirePending(String why) {
        Pending p = pending.getAndSet(null);
        if (p != null && !p.completed) {
            UNPAIRED_CAPTURE.incrementAndGet();
            RETAINED_OBSERVATIONS.set(0);
            RETAINED_BYTES.set(0);
        }
    }

    // ---------------- native lifecycle (quiescence contract) ----------------
    void captureOwner(EventExecutor ex) { ownerExecutor = ex; }

    private final java.util.concurrent.atomic.AtomicBoolean freeRan = new java.util.concurrent.atomic.AtomicBoolean();

    private void freeNow() {
        if (freeRan.compareAndSet(false, true)) {
            OutboundFrameCtx c = frameCtx;
            frameCtx = null;
            if (c != null) { c.free(); CTX_FREED.incrementAndGet(); }
        }
    }

    /** Request-once free (uninstall/removal). The actual free RUNS only at
     *  quiescence: immediately if no op is in flight, else deferred to the
     *  completing op (or the marshaled task), which calls freeNow() directly. */
    private void freeNative() {
        if (!freedOnce.compareAndSet(false, true)) return; // request only once
        Runnable attempt = () -> {
            if (inFlightNative.get() > 0) freeDeferred = true; // completing op frees
            else freeNow();
        };
        EventExecutor owner = ownerExecutor;
        if (owner == null || owner.inEventLoop()) {
            attempt.run();
            return;
        }
        try {
            owner.execute(attempt);
        } catch (Throwable rejected) {
            attempt.run(); // rejected: defer if in flight (removed blocks future ops pre-native), else free now
        }
    }

    // ---------------- independent structural decode (no rust involvement) ----------------
    static final class Decoded {
        final byte[] body;
        final int dataLen;
        final boolean consumedExactly;
        Decoded(byte[] body, int dataLen, boolean consumedExactly) { this.body = body; this.dataLen = dataLen; this.consumedExactly = consumedExactly; }
    }

    /** Decodes [VarInt outer][payload] when !compressedFraming (disabled
     *  contract: no dataLen byte), or [VarInt outer][VarInt dataLen][payload]
     *  when compressedFraming; dataLen==0 -> passthrough; dataLen>0 -> zlib
     *  inflate must recover exactly dataLen bytes and finish. consumedExactly
     *  proves the buffer held EXACTLY one frame. */
    static Decoded structurallyDecode(byte[] f, boolean compressedFraming) {
        try {
            int i = 0;
            long outer = 0; int shift = 0, used = 0;
            while (true) {
                if (i >= f.length || used > 5) return null;
                byte x = f[i++];
                outer |= (long) (x & 0x7F) << shift;
                used++;
                if ((x & 0x80) == 0) break;
                shift += 7;
            }
            int afterOuter = i;
            if (afterOuter + outer != f.length) return new Decoded(new byte[0], 0, false); // not exactly one frame
            if (outer == 0) return null;
            if (!compressedFraming) {
                byte[] payload = new byte[(int) outer];
                System.arraycopy(f, afterOuter, payload, 0, payload.length);
                return new Decoded(payload, 0, true);
            }
            long dataLen = 0; shift = 0; int j = afterOuter;
            while (true) {
                if (j >= f.length || (j - afterOuter) > 5) return null;
                byte x = f[j++];
                dataLen |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
            }
            byte[] payload = new byte[(int) (f.length - j)];
            System.arraycopy(f, j, payload, 0, payload.length);
            if (dataLen == 0) return new Decoded(payload, 0, true);
            if (dataLen > 64 << 20) return null;
            Inflater inf = new Inflater();
            inf.setInput(payload);
            byte[] out = new byte[(int) dataLen];
            int n = inf.inflate(out);
            boolean ok = n == dataLen && inf.finished() && inf.getRemaining() == 0;
            inf.end();
            return ok ? new Decoded(out, (int) dataLen, true) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
