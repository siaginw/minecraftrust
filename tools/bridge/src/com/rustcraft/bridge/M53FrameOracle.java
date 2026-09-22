package com.rustcraft.bridge;

import java.util.Arrays;

/**
 * M-CK3 §6 — independent offline oracle: the ACTUAL installed Java outbound
 * handlers (NettyCompressionEncoder + NettyVarint21FrameEncoder through an
 * EmbeddedChannel) vs the Rust frame engine, with an independent Java
 * frame-decoder/inflater validating both.
 */
public class M53FrameOracle {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        contractBasics();
        realPacketFixture();
        sizeSweepAndTransitions();
        consecutiveAndConcurrent();
        capacityLifetimeNegative();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    // ---- actual installed Java pipeline (the reference arm) ----
    static byte[] javaFrame(byte[] body, Integer threshold) throws Exception {
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        // Outbound traverses tail->head (last-added runs first). Wire order
        // prep-then-compress so the COMPRESSOR sees the raw body and the
        // prepender adds the outer length last (the production layout).
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder")
                .getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prep", (io.netty.channel.ChannelHandler) prep);
        if (threshold != null) {
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder")
                    .getConstructor(int.class).newInstance(threshold.intValue());
            ch.pipeline().addLast("compress", (io.netty.channel.ChannelHandler) comp);
        }
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
        Object out = ch.readOutbound();
        if (out == null) return null;
        try {
            io.netty.buffer.ByteBuf b = (io.netty.buffer.ByteBuf) out;
            byte[] arr = new byte[b.readableBytes()];
            b.readBytes(arr);
            return arr;
        } finally {
            ((io.netty.buffer.ByteBuf) out).release();
        }
    }

    // ---- independent frame decoder (not the Rust reader) ----
    static byte[] decodeFrame(byte[] f) throws Exception { return decodeFrame(f, true); }

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
        inf.end();
        return (n == dataLen && inf.finished()) ? Arrays.copyOf(out, n) : null;
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

    static byte[] payload(int len, int mode) {
        byte[] b = new byte[len];
        if (mode == 0) { for (int i = 0; i < len; i++) b[i] = (byte) (i % 11); }
        else if (mode == 1) { new java.util.Random(7).nextBytes(b); }
        else Arrays.fill(b, (byte) 9);
        return b;
    }

    static void parityCase(String name, byte[] body, Integer threshold) throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] jf = javaFrame(body, threshold);
        byte[] rf = ctx.frame(body, threshold == null ? -1 : threshold.intValue());
        boolean compressed = threshold != null;
        byte[] jDec = jf == null ? null : decodeFrame(jf, compressed);
        byte[] rDec = rf == null ? null : decodeFrame(rf, compressed);
        boolean ok = jf != null && rf != null && jDec != null && rDec != null
                && Arrays.equals(jDec, body) && Arrays.equals(rDec, body)
                && Arrays.equals(jDec, rDec);
        String extra = "jf=" + (jf == null ? -1 : jf.length) + " rf=" + (rf == null ? -1 : rf.length)
                + " jDec=" + (jDec == null ? "null" : jDec.length) + " rDec=" + (rDec == null ? "null" : rDec.length)
                + " bodyLen=" + body.length;
        if (threshold == null) {
            ok = ok && Arrays.equals(jf, rf); // disabled path: deterministic -> exact bytes
        }
        check(name, ok, extra);
        ctx.free();
    }

    static void contractBasics() throws Exception {
        parityCase("disabled: exact bytes", payload(300, 0), null);
        parityCase("below-threshold passthrough", payload(100, 0), 256);
        parityCase("threshold-adjacent (len=threshold)", payload(256, 0), 256);
        parityCase("threshold-adjacent (len=threshold-1)", payload(255, 0), 256);
        parityCase("threshold-adjacent (len=threshold+1)", payload(257, 0), 256);
        parityCase("threshold 0 compresses everything", payload(50, 0), 0);
        parityCase("incompressible at threshold", payload(4096, 1), 256);
        // VarInt boundaries on the outer prefix
        parityCase("outer VarInt boundary 127", payload(127, 0), null);
        parityCase("outer VarInt boundary 128", payload(128, 0), null);
        parityCase("outer VarInt boundary 16383", payload(16383, 0), null);
        parityCase("outer VarInt boundary 16384", payload(16384, 0), null);
    }

    static void realPacketFixture() throws Exception {
        // real packet body: use an actual SPacketChunkData payload (its serialized
        // form begins with the packet-ID VarInt + full fields — we wrap with the ID)
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(700, 70);
        Object chunk = r[0];
        NativeChunkBridge.register(0, 700, 70, addr((java.nio.ByteBuffer) r[1]), 0L);
        M4Coherency.refreshChunkNow(chunk);
        Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
        byte[] sectionPayload = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
        // packet ID 0x20 (SPacketChunkData) VarInt + payload = complete body
        byte[] body = new byte[1 + sectionPayload.length];
        body[0] = 0x20;
        System.arraycopy(sectionPayload, 0, body, 1, sectionPayload.length);
        parityCase("real chunk packet (31KB, compressed)", body, 256);
        parityCase("real chunk packet (disabled)", body, null);
        NativeChunkBridge.unload(0, 700, 70);
    }

    static void sizeSweepAndTransitions() throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        // increasing/decreasing on ONE reused context incl. threshold transitions
        int[] sizes = {64, 300, 5000, 40000, 900, 60};
        int[] thresholds = {256, 256, 1024, 100, -1, -1}; // includes disable mid-stream
        boolean all = true;
        for (int i = 0; i < sizes.length; i++) {
            boolean compressed = thresholds[i] >= 0;
            byte[] body = payload(sizes[i], i % 2);
            byte[] rf = ctx.frame(body, thresholds[i]);
            byte[] dec = rf == null ? null : decodeFrameSafe(rf, compressed);
            if (dec == null || !Arrays.equals(dec, body)) { all = false; break; }
            byte[] jf = javaFrame(body, compressed ? Integer.valueOf(thresholds[i]) : null);
            byte[] jDec = jf == null ? null : decodeFrameSafe(jf, compressed);
            if (jDec == null || !Arrays.equals(jDec, dec)) { all = false; break; }
        }
        check("transitions: size+threshold sweep on one ctx (incl. disable)", all, null);
        ctx.free();
    }

    static void consecutiveAndConcurrent() throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] a = payload(3000, 0);
        byte[] fa = ctx.frame(a, 256);
        byte[] faCopy = fa.clone();
        byte[] b = payload(4000, 2);
        byte[] fb = ctx.frame(b, 256);
        check("lifetime: frame A unchanged while B produced",
                Arrays.equals(fa, faCopy) && Arrays.equals(decodeFrame(fa), a), null);
        check("lifetime: frame B independently correct", Arrays.equals(decodeFrame(fb), b), null);
        ctx.free();
        // independent concurrent contexts
        final OutboundFrameCtx c1 = OutboundFrameCtx.create();
        final OutboundFrameCtx c2 = OutboundFrameCtx.create();
        final boolean[] ok = {true, true};
        Thread t1 = new Thread(() -> { for (int i = 0; i < 300; i++) { byte[] f = c1.frame(payload(2000, i % 2), 256); if (f == null || !Arrays.equals(decodeFrameSafe(f), payload(2000, i % 2))) { ok[0] = false; return; } } });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 300; i++) { byte[] f = c2.frame(payload(3500, (i + 1) % 2), 256); if (f == null || !Arrays.equals(decodeFrameSafe(f), payload(3500, (i + 1) % 2))) { ok[1] = false; return; } } });
        t1.start(); t2.start(); t1.join(); t2.join();
        check("concurrent: independent contexts clean", ok[0] && ok[1], null);
        c1.free(); c2.free();
    }

    static byte[] decodeFrameSafe(byte[] f) { return decodeFrameSafe(f, true); }
    static byte[] decodeFrameSafe(byte[] f, boolean compressed) { try { return decodeFrame(f, compressed); } catch (Throwable t) { return null; } }

    static void capacityLifetimeNegative() throws Exception {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] body = payload(8000, 0);
        byte[] f = ctx.frame(body, 256);
        check("capacity: first large frame succeeds", f != null && Arrays.equals(decodeFrameSafe(f), body), null);
        byte[] big = payload(70000, 1);
        byte[] f2 = ctx.frame(big, 256);
        check("capacity: growth handles 70KB", f2 != null && Arrays.equals(decodeFrameSafe(f2), big), null);
        // use-after-close
        ctx.free();
        check("close: frame after free returns null", ctx.frame(body, 256) == null, null);
        ctx.free();
        check("close: double free safe", true, null);
        // empty body rejected
        OutboundFrameCtx c2 = OutboundFrameCtx.create();
        check("invalid: empty body rejected", c2.frame(new byte[0], 256) == null, null);
        check("invalid: null body rejected", c2.frame(null, 256) == null, null);
        // corruption negative controls: damaged length / payload / stream must be DETECTED
        byte[] good = c2.frame(payload(2000, 0), 256);
        byte[] badLen = good.clone(); badLen[0] ^= 0x01;
        check("negative: damaged outer length detected", decodeFrameSafe(badLen) == null, null);
        byte[] badPayload = good.clone(); badPayload[badPayload.length / 2] ^= 0x55;
        check("negative: damaged payload detected (inflate or length fails)",
                decodeFrameSafe(badPayload) == null || !Arrays.equals(decodeFrameSafe(badPayload), payload(2000, 0)), null);
        c2.free();
    }

    static long addr(java.nio.ByteBuffer b) throws Exception {
        java.lang.reflect.Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
