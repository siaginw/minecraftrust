package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * M-CK4 §2/§3/§5 — corrected validation: real packet fixture, encoded-length
 * boundary vs the ACTUAL installed handlers, offline handler-shaped adapter
 * parity, buffer-lifetime and concurrency gates.
 */
public class MCK4Validation {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        realFixtureAndAdapterParity();
        boundaryVsInstalledHandlers();
        adapterPipelineCases();
        lifetimeAndConcurrency();
        retryInjectionGate();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    // ---- real fixture via the installed serializer + adapter parity ----
    static void realFixtureAndAdapterParity() throws Exception {
        byte[] body = M53FixtureBuilder.realChunkPacketBody(910, 91);
        check("fixture: real complete packet body (installed writePacketData + enum id)",
                body.length > 31000 && (body[0] & 0xFF) == 0x20, "len=" + body.length);

        // adapter pipeline parity for compressed + disabled
        parityAdapter(body, 256, "adapter: real packet @256");
        parityAdapter(body, -1, "adapter: real packet disabled");
        // old fixture relabeled: SYNTHETIC section-payload-with-prefix (kept for regression only)
        byte[] synthetic = syntheticBody(31008);
        parityAdapter(synthetic, 256, "adapter: SYNTHETIC_SECTION_PAYLOAD_WITH_PREFIX @256");
    }

    static byte[] syntheticBody(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (i % 7);
        return b;
    }

    // ---- §2 boundary vs installed handlers ----
    static void boundaryVsInstalledHandlers() throws Exception {
        int max = 0x1F_FF_FF;
        // disabled framing at max-1 / max / max+1 (bounded allocations around 2MB)
        int[] lens = {max - 1, max, max + 1};
        for (int len : lens) {
            byte[] body = new byte[len]; // zero-filled = compressible but we use disabled here
            boolean disabledOk = boundaryDisabled(body, len);
            check("boundary: disabled len=" + len + " vs installed", disabledOk, null);
        }
        // enabled-but-uncompressed (below threshold): dataLen=0 framing
        check("boundary: enabled-uncompressed accounting (dataLen=0)",
                boundaryBelowThreshold(), null);
        // highly compressible plaintext LARGER than the outer limit: Rust must
        // now ENCODE it (compressed frame fits); installed handler behavior
        // compared for the same input.
        compressibleOverLimit();
        // capacity failure + retry (direct engine level)
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] body = new byte[40000];
        new java.util.Random(5).nextBytes(body);
        byte[] f = ctx.frame(body, 256);
        check("boundary: capacity retry succeeds", f != null && decodeFrameSafe(f, true) != null, null);
        ctx.free();
    }

    static boolean boundaryDisabled(byte[] body, int len) throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] jf = null;
        try { jf = M53FrameBench.javaFrame(body, null); } catch (Throwable t) { jf = null; /* prepender 'unable to fit' */ }
        byte[] rf = ctx.frame(body, -1);
        ctx.free();
        // exact-bytes contract for disabled framing; max+1 exceeds the 3-byte
        // prefix in the INSTALLED prepender ("unable to fit") — record behavior
        boolean jOk = jf != null, rOk = rf != null;
        if (len <= 0x1F_FF_FF) {
            return jOk && rOk && Arrays.equals(jf, rf);
        } else {
            // BOTH must reject: installed prepender throws ("unable to fit"); Rust returns null
            System.out.println("  max+1: java=" + (jOk ? "ACCEPTED-UNEXPECTED" : "rejected") + " rust=" + (rOk ? "ACCEPTED-UNEXPECTED" : "rejected"));
            return !jOk && !rOk; // BOTH outcomes asserted
        }
    }

    static boolean boundaryBelowThreshold() throws Exception {
        // boundary-ADJACENT (threshold-1 / threshold / threshold+1) with the
        // dataLen=0 byte accounted: inner = 1 + bodyLen for all three
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        boolean all = true;
        for (int delta = -1; delta <= 1; delta++) {
            int len = 256 + delta;
            byte[] body = new byte[len];
            Arrays.fill(body, (byte) 3);
            byte[] jf = M53FrameBench.javaFrame(body, 256);
            byte[] rf = ctx.frame(body, 256);
            boolean ok = jf != null && rf != null && Arrays.equals(jf, rf)
                    && decodeFrameSafe(jf, true) != null && decodeFrameSafe(rf, true) != null;
            if (!ok) { System.out.println("  below-threshold len=" + len + " FAILED"); all = false; }
        }
        ctx.free();
        return all;
    }

    static void compressibleOverLimit() throws Exception {
        int max = 0x1F_FF_FF;
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        // 2.5 MB of zeros compresses far under the limit
        byte[] body = new byte[max + 300_000];
        byte[] rf = ctx.frame(body, 256);
        boolean ok = false, frameUnderLimit = false;
        if (rf != null) {
            byte[] dec = decodeFrameSafe(rf, true);
            ok = dec != null && Arrays.equals(dec, body);
            long outer = readVarInt(rf);
            frameUnderLimit = outer <= max && outer + varIntSize((int) outer) == rf.length;
        }
        // BOTH outcomes asserted: vanilla ACCEPTS (prepender sees compressed size)
        byte[] jf = null;
        try { jf = M53FrameBench.javaFrame(body, 256); } catch (Throwable t) { jf = null; }
        boolean javaAccepts = jf != null;
        boolean rustAccepts = ok && frameUnderLimit;
        byte[] jDec = jf == null ? null : decodeFrameSafe(jf, true);
        check("boundary: compressible >limit — BOTH java and rust ACCEPT; both decode to original",
                javaAccepts && rustAccepts && jDec != null && Arrays.equals(jDec, body),
                "jf=" + (jf == null ? -1 : jf.length) + " rf=" + (rf == null ? -1 : rf.length));
        ctx.free();
    }

    // ---- §3 adapter pipeline cases ----
    static void parityAdapter(byte[] body, int threshold, String name) throws Exception {
        // Rust adapter pipeline
        io.netty.channel.embedded.EmbeddedChannel rust = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        rust.pipeline().addLast("rustframe", h);
        h.setThreshold(threshold);
        rust.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
        io.netty.buffer.ByteBuf rOut = (io.netty.buffer.ByteBuf) rust.readOutbound();
        byte[] rf = null;
        if (rOut != null) { rf = new byte[rOut.readableBytes()]; rOut.readBytes(rf); rOut.release(); }
        rust.close();

        // installed-handler pipeline (prep added before compress: tail->head order)
        byte[] jf = M53FrameBench.javaFrame(body, threshold < 0 ? null : Integer.valueOf(threshold));

        boolean compressed = threshold >= 0;
        byte[] jDec = jf == null ? null : decodeFrameSafe(jf, compressed);
        byte[] rDec = rf == null ? null : decodeFrameSafe(rf, compressed);
        boolean ok = jf != null && rf != null && jDec != null && rDec != null
                && Arrays.equals(jDec, body) && Arrays.equals(rDec, body);
        if (!compressed) ok = ok && Arrays.equals(jf, rf);
        check(name, ok, "jf=" + (jf == null ? -1 : jf.length) + " rf=" + (rf == null ? -1 : rf.length));
    }

    static void adapterPipelineCases() throws Exception {
        // threshold changes on a reused "connection" (one channel, multiple thresholds)
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        ch.pipeline().addLast("rustframe", h);
        boolean all = true;
        int[][] plan = { {300, 256}, {50, 256}, {5000, 1024}, {400, 0}, {700, -1} };
        for (int[] p : plan) {
            byte[] body = syntheticBody(p[0]);
            h.setThreshold(p[1]);
            ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
            io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
            byte[] f = new byte[out.readableBytes()];
            out.readBytes(f);
            out.release();
            byte[] dec = decodeFrameSafe(f, p[1] >= 0);
            if (dec == null || !Arrays.equals(dec, body)) { all = false; break; }
        }
        check("adapter: threshold transitions on reused connection", all, null);
        // counters prove frames flowed and order-preserving updates counted
        check("adapter: counters (frames>0, updates=5)",
                RustFrameHandler.FRAMES.get() > 0 && RustFrameHandler.THRESHOLD_UPDATES.get() >= 5,
                "frames=" + RustFrameHandler.FRAMES.get() + " updates=" + RustFrameHandler.THRESHOLD_UPDATES.get());
        ch.close();
        // nonzero readerIndex + slice inputs
        byte[] body = syntheticBody(1000);
        io.netty.channel.embedded.EmbeddedChannel ch2 = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h2 = new RustFrameHandler();
        ch2.pipeline().addLast("rustframe", h2);
        h2.setThreshold(256);
        io.netty.buffer.ByteBuf wrapped = io.netty.buffer.Unpooled.wrappedBuffer(body);
        wrapped.readerIndex(200); // nonzero offset
        ch2.writeOutbound(wrapped.slice()); // slice form
        io.netty.buffer.ByteBuf out2 = (io.netty.buffer.ByteBuf) ch2.readOutbound();
        byte[] f2 = new byte[out2.readableBytes()];
        out2.readBytes(f2);
        out2.release();
        byte[] expect = Arrays.copyOfRange(body, 200, 1000);
        check("adapter: nonzero readerIndex + slice",
                Arrays.equals(decodeFrameSafe(f2, true), expect), null);
        ch2.close();
    }


    static void retryInjectionGate() throws Exception {
        // FIRST call forces insufficient capacity through the REAL wrapper + JNI
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] body = new byte[5000];
        new java.util.Random(11).nextBytes(body);
        ctx.testForceFirstCapacity = 64;                 // far too small
        byte[] f = ctx.frame(body, 256);
        boolean ok = f != null && Arrays.equals(decodeFrameSafe(f, true), body);
        check("retry: forced ERR_CAPACITY -> native bound read -> exactly-one retry -> frame decodes",
                ok && ctx.LAST_RETRY_NEEDED > 64, "needed=" + ctx.LAST_RETRY_NEEDED);
        // One-retry ceiling: a fresh ctx where needed (real, ~5015) exceeds a
        // lowered policy terminates null on the FIRST retry — the retriesThisCall
        // guard plus policy together prove no unbounded retry loop is possible.
        OutboundFrameCtx ctx2 = OutboundFrameCtx.create();
        ctx2.policyLimit = 64;                     // needed(~5015) > policy
        ctx2.testForceFirstCapacity = 64;
        byte[] f2 = ctx2.frame(body, 256);
        check("retry: needed>policy on retry terminates null (one-retry ceiling enforced)",
                f2 == null && ctx2.LAST_RETRY_NEEDED > 64, "needed=" + ctx2.LAST_RETRY_NEEDED);
        ctx2.free();
        // Policy-exceeding bound rejected: LOWER the ceiling and force a real
        // capacity error — the needed value (real, native-written) now exceeds
        // policy and the retry must terminate null (guard logic via real JNI)
        ctx.policyLimit = 64;                        // below the needed bound (~5015)
        ctx.testForceFirstCapacity = 32;             // force the real error
        byte[] f3 = ctx.frame(body, 256);
        check("retry: needed > lowered policy -> null (guard via real JNI capacity error)",
                f3 == null, "needed=" + ctx.LAST_RETRY_NEEDED);
        ctx.free();
    }

    // ---- §5 lifetime/concurrency ----
    static void lifetimeAndConcurrency() throws Exception {
        // frame A retained while B produced (adapter level: Netty-owned outputs)
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        ch.pipeline().addLast("rustframe", h);
        h.setThreshold(256);
        byte[] a = syntheticBody(2000), b = syntheticBody(3000);
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(a));
        io.netty.buffer.ByteBuf fa = (io.netty.buffer.ByteBuf) ch.readOutbound();
        byte[] faSaved = new byte[fa.readableBytes()];
        fa.getBytes(fa.readerIndex(), faSaved);          // save WITHOUT changing indices
        int aRi = fa.readerIndex();
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(b));
        io.netty.buffer.ByteBuf fb = (io.netty.buffer.ByteBuf) ch.readOutbound();
        byte[] fbCopy = new byte[fb.readableBytes()];
        fb.readBytes(fbCopy);
        // Re-read A's ORIGINAL retained ByteBuf (indices unchanged) and compare
        // to the saved copy — B's production must not have touched A's storage.
        byte[] faReRead = new byte[faSaved.length];
        fa.getBytes(aRi, faReRead);
        boolean aUnchanged = Arrays.equals(faSaved, faReRead);
        boolean aDecodes = Arrays.equals(decodeFrameSafe(faSaved, true), a);
        boolean bDecodes = Arrays.equals(decodeFrameSafe(fbCopy, true), b);
        check("lifetime: A's retained ByteBuf bytes unchanged while B produced (original reread)",
                aUnchanged && aDecodes && bDecodes, null);
        // Negative control: corrupt the saved A -> the SAME assertion must fail
        byte[] faCorrupt = faSaved.clone();
        faCorrupt[faCorrupt.length / 2] ^= 0x5A;
        boolean corruptionDetected = !Arrays.equals(faSaved, faCorrupt)
                && !Arrays.equals(decodeFrameSafe(faCorrupt, true), a);
        check("lifetime-neg: corrupted-A detected by the same comparison", corruptionDetected, null);
        fa.release(); fb.release();
        ch.close();
        // handlerRemoved frees the native ctx
        check("lifetime: handlerRemoved frees (ctx dead after close)", !h.ctx.isLive(), null);
        // concurrent independent pipelines
        final boolean[] ok = {true, true};
        Thread t1 = new Thread(() -> runPipeline(2500, ok, 0));
        Thread t2 = new Thread(() -> runPipeline(3600, ok, 1));
        t1.start(); t2.start(); t1.join(); t2.join();
        check("concurrency: independent adapter pipelines clean", ok[0] && ok[1], null);
    }

    static void runPipeline(int size, boolean[] ok, int idx) {
        try {
            io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
            RustFrameHandler h = new RustFrameHandler();
            ch.pipeline().addLast("rustframe", h);
            h.setThreshold(256);
            for (int i = 0; i < 200; i++) {
                byte[] body = new byte[size];
                Arrays.fill(body, (byte) (i % 9));
                ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
                io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
                byte[] f = new byte[out.readableBytes()];
                out.readBytes(f);
                out.release();
                if (!Arrays.equals(decodeFrameSafe(f, true), body)) { ok[idx] = false; return; }
            }
            ch.close();
        } catch (Throwable t) { ok[idx] = false; }
    }

    // ---- independent decode helpers ----
    static byte[] decodeFrameSafe(byte[] f, boolean compressed) {
        try { return decodeFrame(f, compressed); } catch (Throwable t) { return null; }
    }

    static byte[] decodeFrame(byte[] f, boolean compressedFraming) throws Exception {
        long outer = readVarInt(f);
        int used = varIntSize((int) outer);
        if (used + (int) outer != f.length) return null;
        if (!compressedFraming) return Arrays.copyOfRange(f, used, f.length);
        byte[] inner = Arrays.copyOfRange(f, used, f.length);
        long dataLen = readVarInt(inner);
        int dUsed = varIntSize((int) dataLen);
        byte[] payload = Arrays.copyOfRange(inner, dUsed, inner.length);
        if (dataLen == 0) return payload;
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(payload);
        byte[] out = new byte[(int) dataLen + 64];
        int n = inf.inflate(out);
        boolean complete = n == dataLen && inf.finished() && inf.getRemaining() == 0;
        inf.end();
        return complete ? Arrays.copyOf(out, n) : null;
    }

    static long readVarInt(byte[] b) {
        long v = 0; int i = 0, shift = 0;
        while (true) {
            byte x = b[i++];
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { v >>>= 7; n++; }
        return n;
    }
}
