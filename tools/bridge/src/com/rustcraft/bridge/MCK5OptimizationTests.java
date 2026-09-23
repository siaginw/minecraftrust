package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * M-CK5 Checkpoint C — correctness gates for the direct-address frame paths
 * (Optimization 1+2) and the scratch-retention measurement (Optimization 3).
 *
 * Verifies FIRST (against the installed Netty 4.1.9) the buffer primitive the
 * optimization relies on: for a direct non-composite ByteBuf,
 * buf.nioBuffer(readerIndex, len) returns a DIRECT ByteBuffer sharing the
 * buffer's memory (no copy) — measured, not assumed. The adopted technique is
 * cross-checked against the pinned Velocity reference (ensureCompatible +
 * deflate-into-out; ledger in machine/MCK5-optimization-results.yaml).
 *
 * The legacy staging path (arm C) remains the default; the direct paths are
 * opt-in (useDirectPaths) and every correctness property of M-CK4.x must hold
 * identically: parity with the legacy path, decode-to-original, both-outcome
 * framing, retry discipline, no partial output on failure, writer-index
 * advancement only after success, freed-context rejection, concurrency
 * independence, retained-A-while-B, and zero Java-side scratch retention for
 * direct-path traffic (large-then-small pattern).
 */
public class MCK5OptimizationTests {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        nettyPrimitiveVerification();
        parityAndFraming();
        retryThroughNettyOutput();
        noPartialOutputOnFailure();
        nonzeroIndexAndFallbacks();
        freedContextRejection();
        concurrencyIndependence();
        retainedAWhileBDirect();
        largeThenSmallRetention();
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    // ---- verify the Netty primitive empirically (installed 4.1.9) ----
    static void nettyPrimitiveVerification() throws Exception {
        byte[] body = new byte[1000];
        new Random(3).nextBytes(body);
        ByteBuf db = Unpooled.directBuffer(1200).writeBytes(body);
        db.readerIndex(50);
        ByteBuffer nio = db.nioBuffer(db.readerIndex(), db.readableBytes());
        boolean shared = nio != null && nio.isDirect() && nio.remaining() == 950;
        if (shared) { // mutating the view must reflect in the ByteBuf (shared memory, no copy)
            nio.put(0, (byte) 0x5A);
            shared = db.getByte(db.readerIndex()) == 0x5A;
            nio.put(0, body[50]);
        }
        check("netty-primitive: direct ByteBuf.nioBuffer(readerIndex,len) is a DIRECT SHARED view (no copy) — measured on installed 4.1.9",
                shared, "isDirect=" + (nio != null && nio.isDirect()));
        db.release();
    }

    static EmbeddedChannel channel(boolean direct, int threshold) {
        EmbeddedChannel ch = new EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        h.useDirectPaths = direct;
        if (threshold >= 0) h.setThreshold(threshold); // pre-attach config
        ch.pipeline().addLast("rustframe", h);
        return ch;
    }

    static ByteBuf directWrap(byte[] b) {
        return Unpooled.directBuffer(b.length).writeBytes(b);
    }

    static byte[] frame(EmbeddedChannel ch, byte[] body) {
        ch.writeOutbound(directWrap(body));
        ByteBuf out = ch.readOutbound();
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    // ---- parity: direct path == legacy path == expected framing ----
    static void parityAndFraming() throws Exception {
        byte[][] bodies = {
                pattern(31_008), prng(5_000, 11), prng(257, 7), new byte[100], prng(256, 21), pattern(65_536)
        };
        int[] thresholds = { 256, 256, 256, 256, 256, -1 };
        boolean all = true;
        for (int i = 0; i < bodies.length; i++) {
            EmbeddedChannel cCh = channel(false, thresholds[i]);
            EmbeddedChannel dCh = channel(true, thresholds[i]);
            byte[] fc = frame(cCh, bodies[i]);
            byte[] fd = frame(dCh, bodies[i]);
            boolean ok = Arrays.equals(fc, fd) // both Rust: byte-identical per context
                    && Arrays.equals(MCK4Validation.decodeFrameSafe(fd, thresholds[i] >= 0), bodies[i]);
            if (!ok) all = false;
            cCh.close(); dCh.close();
        }
        check("parity: direct-path frames BYTE-IDENTICAL to legacy-path frames across compressible/incompressible/passthrough/at-threshold/disabled",
                all, null);
        // framing vs installed Java for a passthrough + a disabled case (deterministic exact bytes)
        EmbeddedChannel dCh = channel(true, 256);
        byte[] f = frame(dCh, new byte[100]);
        byte[] jf = M53FrameBench.javaFrame(new byte[100], 256);
        check("parity: direct passthrough == installed Java bytes", Arrays.equals(f, jf), null);
        dCh.close();
    }

    static byte[] pattern(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (i % 251); return b; }

    static byte[] prng(int n, int seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    // ---- capacity retry through the Netty-owned output region ----
    static void retryThroughNettyOutput() throws Exception {
        EmbeddedChannel ch = channel(true, 256);
        RustFrameHandler h = (RustFrameHandler) ch.pipeline().get("rustframe");
        byte[] body = prng(5_000, 31);
        h.ctx.testForceFirstCapacity = 64; // real ERR_CAPACITY through frameAddr on the FIRST call
        byte[] f = frame(ch, body);
        boolean ok = f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), body)
                && h.ctx.LAST_RETRY_NEEDED > 64;
        check("direct-retry: forced ERR_CAPACITY -> native needed bound -> ensureWritable growth -> address RE-ACQUIRED -> frame decodes",
                ok, "needed=" + h.ctx.LAST_RETRY_NEEDED);
        ch.close();
    }

    // ---- no partial output on failure; writer index never advances on error ----
    static void noPartialOutputOnFailure() throws Exception {
        // ctx level: capacity error must leave the output region untouched (sentinel intact)
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        ByteBuffer out = ByteBuffer.allocateDirect(4096);
        for (int i = 0; i < 4096; i++) out.put(i, (byte) 0x5A);
        byte[] body = prng(3_000, 41);
        ByteBuffer in = ByteBuffer.allocateDirect(body.length);
        in.put(body);
        in.flip();
        long inAddr, outAddr;
        java.lang.reflect.Field af = java.nio.Buffer.class.getDeclaredField("address");
        af.setAccessible(true);
        inAddr = af.getLong(in);
        outAddr = af.getLong(out);
        ctx.testForceFirstCapacity = 64;
        int n = ctx.frameAddr(inAddr, body.length, 256, outAddr, 4096);
        boolean sentinelIntact = true;
        for (int i = 0; i < 4096; i++) if (out.get(i) != 0x5A) { sentinelIntact = false; break; }
        check("no-partial: frameAddr capacity error leaves the output region UNTOUCHED (sentinel intact)",
                n == OutboundFrameCtx.ERR_CAPACITY && sentinelIntact, "n=" + n);
        // handler level: failed direct encode produces NO channel output (no partial frame)
        EmbeddedChannel ch = channel(true, 256);
        RustFrameHandler h = (RustFrameHandler) ch.pipeline().get("rustframe");
        h.ctx.testForceCapacityTimes = 2; // first call AND retry both fail -> EncoderException
        boolean threw = false;
        try { ch.writeOutbound(directWrap(prng(4_000, 43))); ch.checkException(); }
        catch (Throwable t) { threw = true; }
        boolean noOutput = ch.readOutbound() == null;
        check("no-partial: exhausted direct retry -> exception, NO partial channel output",
                threw && noOutput, null);
        // after the failure the handler still works (small valid frame on the same ctx)
        byte[] okBody = prng(500, 44);
        byte[] f = frame(ch, okBody);
        check("no-partial: same context still frames a small valid body after the failure",
                f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), okBody), null);
        ch.close();
        ctx.free();
    }

    // ---- nonzero readerIndex direct input; heap/composite fallback ----
    static void nonzeroIndexAndFallbacks() throws Exception {
        byte[] body = prng(3_000, 51);
        long d0 = RustFrameHandler.DIRECT_FRAMES.get(), fb0 = RustFrameHandler.FALLBACK_FRAMES.get();
        ByteBuf db = directWrap(body);
        db.readerIndex(200);
        EmbeddedChannel ch = channel(true, 256);
        ch.writeOutbound(db.slice());
        ByteBuf out = ch.readOutbound();
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        byte[] expect = Arrays.copyOfRange(body, 200, body.length);
        check("direct-nonzero-index: slice at readerIndex 200 frames exactly the readable region",
                Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), expect), null);
        // heap input -> bounded-copy fallback, still correct
        ch.writeOutbound(Unpooled.wrappedBuffer(body));
        ByteBuf out2 = ch.readOutbound();
        byte[] f2 = new byte[out2.readableBytes()];
        out2.readBytes(f2);
        out2.release();
        boolean heapOk = Arrays.equals(MCK4Validation.decodeFrameSafe(f2, true), body)
                && RustFrameHandler.FALLBACK_FRAMES.get() == fb0 + 1; // fallback COUNTED (no silent path change)
        // composite direct -> fallback too
        ByteBuf comp = Unpooled.compositeBuffer()
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 0, 1000)))
                .addComponent(true, directWrap(Arrays.copyOfRange(body, 1000, body.length)));
        long fb1 = RustFrameHandler.FALLBACK_FRAMES.get();
        ch.writeOutbound(comp);
        ByteBuf out3 = ch.readOutbound();
        byte[] f3 = new byte[out3.readableBytes()];
        out3.readBytes(f3);
        out3.release();
        boolean compOk = Arrays.equals(MCK4Validation.decodeFrameSafe(f3, true), body)
                && RustFrameHandler.FALLBACK_FRAMES.get() == fb1 + 1;
        check("fallbacks: heap and composite inputs take the COUNTED bounded-copy fallback and still decode to original",
                heapOk && compOk && RustFrameHandler.DIRECT_FRAMES.get() > d0,
                "heapOk=" + heapOk + " compOk=" + compOk + " directDelta=" + (RustFrameHandler.DIRECT_FRAMES.get() - d0)
                        + " fallbackDelta=" + (RustFrameHandler.FALLBACK_FRAMES.get() - fb0));
        ch.close();
    }

    // ---- freed context rejection through frameAddr ----
    static void freedContextRejection() {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        ctx.free();
        int n = ctx.frameAddr(12345L, 100, 256, 54321L, 4096);
        check("freed-ctx: frameAddr after free returns ERR_CLOSED", n == OutboundFrameCtx.ERR_CLOSED, "n=" + n);
    }

    // ---- concurrency: independent direct-path channels ----
    static void concurrencyIndependence() throws Exception {
        final boolean[] ok = { true, true };
        Thread t1 = new Thread(() -> runDirect(2500, 61, ok, 0));
        Thread t2 = new Thread(() -> runDirect(3600, 62, ok, 1));
        t1.start(); t2.start(); t1.join(); t2.join();
        check("concurrency: independent direct-path channels, 200 frames each, all decode",
                ok[0] && ok[1], null);
    }

    static void runDirect(int size, int seed, boolean[] ok, int idx) {
        try {
            EmbeddedChannel ch = channel(true, 256);
            for (int i = 0; i < 200; i++) {
                byte[] body = new byte[size];
                new Random(seed + i).nextBytes(body);
                byte[] f = frame(ch, body);
                if (!Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), body)) { ok[idx] = false; return; }
            }
            ch.close();
        } catch (Throwable t) { ok[idx] = false; }
    }

    // ---- retained-A-while-B on the direct path ----
    static void retainedAWhileBDirect() throws Exception {
        EmbeddedChannel ch = channel(true, 256);
        byte[] a = prng(2_000, 71), b = prng(3_000, 72);
        ch.writeOutbound(directWrap(a));
        ByteBuf fa = ch.readOutbound();
        byte[] faSaved = new byte[fa.readableBytes()];
        fa.getBytes(fa.readerIndex(), faSaved);
        int ri = fa.readerIndex();
        ch.writeOutbound(directWrap(b));
        byte[] faRe = new byte[faSaved.length];
        fa.getBytes(ri, faRe);
        check("retained-A(direct): B's direct-path frame leaves A's retained bytes unchanged; A decodes",
                Arrays.equals(faSaved, faRe) && Arrays.equals(MCK4Validation.decodeFrameSafe(faSaved, true), a), null);
        fa.release();
        ch.close();
    }

    // ---- Optimization 3: large-then-small retention (instance-level measurement) ----
    static void largeThenSmallRetention() throws Exception {
        byte[] big = pattern(1_048_576), small = prng(500, 81);
        // direct path: 1 MiB frame then small frame — ZERO Java-side context scratch retained
        EmbeddedChannel dCh = channel(true, 256);
        RustFrameHandler dh = (RustFrameHandler) dCh.pipeline().get("rustframe");
        frame(dCh, big);
        frame(dCh, small);
        long directRetained = dh.ctx.retainedBytes();
        dCh.close();
        // legacy path: same traffic retains ~2 MiB+ of direct scratch (inBuf+outBuf sized by the 1 MiB body)
        EmbeddedChannel cCh = channel(false, 256);
        RustFrameHandler ch2 = (RustFrameHandler) cCh.pipeline().get("rustframe");
        frame(cCh, big);
        frame(cCh, small);
        long legacyRetained = ch2.ctx.retainedBytes();
        cCh.close();
        check("retention: direct path retains ZERO Java-side scratch after large-then-small; legacy retains ~2x body size",
                directRetained == 0 && legacyRetained >= 1_048_576,
                "direct=" + directRetained + "B legacy=" + legacyRetained + "B");
    }
}
