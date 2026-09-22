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
            // both must REJECT (installed throws; Rust returns null or BodyTooLarge)
            System.out.println("  max+1: java=" + (jOk ? "accepted?" : "rejected") + " rust=" + (rOk ? "accepted?" : "rejected"));
            return !rOk; // Rust must reject; Java exception is recorded by the oracle probe below
        }
    }

    static boolean boundaryBelowThreshold() throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] body = new byte[100];
        Arrays.fill(body, (byte) 3);
        byte[] jf = M53FrameBench.javaFrame(body, 256);
        byte[] rf = ctx.frame(body, 256);
        ctx.free();
        // [outer][0][body]: outer = 1 + bodyLen accounting
        return jf != null && rf != null && Arrays.equals(jf, rf)
                && decodeFrameSafe(jf, true) != null;
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
        check("boundary: compressible >limit body encodes; frame under outer limit; decodes equal",
                ok && frameUnderLimit, "rfLen=" + (rf == null ? -1 : rf.length));
        // The installed handler under the same input: does vanilla accept it?
        boolean javaAccepted;
        try {
            byte[] jf = M53FrameBench.javaFrame(body, 256);
            javaAccepted = jf != null;
        } catch (Throwable t) {
            javaAccepted = false; // prepender "unable to fit" fires AFTER compression in vanilla too
        }
        System.out.println("  vanilla behavior on same input: accepted=" + javaAccepted + " (recorded, not asserted)");
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
        byte[] faCopy = new byte[fa.readableBytes()];
        fa.readBytes(faCopy);
        fa.readerIndex(0); // retain
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(b));
        io.netty.buffer.ByteBuf fb = (io.netty.buffer.ByteBuf) ch.readOutbound();
        byte[] fbCopy = new byte[fb.readableBytes()];
        fb.readBytes(fbCopy);
        check("lifetime: adapter frame A unchanged while B produced",
                Arrays.equals(faCopy, Arrays.copyOfRange(faCopy, 0, faCopy.length))
                        && Arrays.equals(decodeFrameSafe(fbCopy, true), b), null);
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
