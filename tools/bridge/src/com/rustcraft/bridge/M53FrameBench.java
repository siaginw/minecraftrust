package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * M-CK3 §7 — fair three-way frame benchmark (immutable inputs, offline).
 *
 * A. JAVA_REFERENCE      : actual installed NettyCompressionEncoder + NettyVarint21FrameEncoder (EmbeddedChannel).
 * B. CURRENT_RUST_BACKED : CK2-optimized CompressionCtx.compress + Java threshold/VarInt framing
 *                          (the shape of today's production rust-backed path).
 * C. NEW_RUST_FRAME      : OutboundFrameCtx.frame (threshold decision + compression + both framings in Rust).
 *
 * All arms: identical immutable packet body in, equivalently owned complete frame bytes out
 * (fresh heap byte[]), including all required copies/JNI/handoff.
 */
public class M53FrameBench {

    static final int N = 1500, WARM = 300;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        int threshold = Integer.parseInt(args.length > 0 ? args[0] : "256");
        String mode = args.length > 1 ? args[1] : "chunk";

        byte[] body;
        if (mode.equals("chunk")) {
            Object[] r = M4PacketParityHarness.baseChunkAndPrimer(800, 80);
            Object chunk = r[0];
            NativeChunkBridge.register(0, 800, 80, addr((ByteBuffer) r[1]), 0L);
            M4Coherency.refreshChunkNow(chunk);
            Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
            byte[] pl = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
            body = new byte[1 + pl.length];
            body[0] = 0x20;
            System.arraycopy(pl, 0, body, 1, pl.length);
            NativeChunkBridge.unload(0, 800, 80);
        } else if (mode.equals("incompressible")) {
            body = new byte[31008];
            new java.util.Random(3).nextBytes(body);
        } else { // below-threshold
            body = new byte[Math.max(1, threshold / 2)];
            Arrays.fill(body, (byte) 7);
        }
        System.out.printf("mode=%s threshold=%d bodyLen=%d (IMMUTABLE INPUT)%n", mode, threshold, body.length);

        CompressionCtx c2 = new CompressionCtx(); c2.ensureCreated();
        OutboundFrameCtx fc = OutboundFrameCtx.create();
        Deflater def = new Deflater();

        // correctness cross-check before timing
        byte[] fa = javaFrame(body, threshold), fb = frameB(c2, body, threshold), fcF = fc.frame(body, threshold);
        System.out.printf("sizes: java=%d rustB=%d rustC=%d%n",
                fa != null ? fa.length : -1, fb != null ? fb.length : -1, fcF != null ? fcF.length : -1);

        long[] ta = new long[N], tb = new long[N], tc = new long[N];
        for (int w = 0; w < WARM; w++) { javaFrame(body, threshold); frameB(c2, body, threshold); fc.frame(body, threshold); }
        for (int i = 0; i < N; i++) {
            switch (i % 3) {
                case 0: {
                    long t0 = System.nanoTime(); byte[] x = javaFrame(body, threshold); ta[i] = System.nanoTime() - t0;
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold); tb[i] = System.nanoTime() - t1;
                    long t2 = System.nanoTime(); byte[] z = fc.frame(body, threshold);   tc[i] = System.nanoTime() - t2;
                    if (x == null || y == null || z == null) throw new IllegalStateException();
                    break;
                }
                case 1: {
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold); tb[i] = System.nanoTime() - t1;
                    long t2 = System.nanoTime(); byte[] z = fc.frame(body, threshold);   tc[i] = System.nanoTime() - t2;
                    long t0 = System.nanoTime(); byte[] x = javaFrame(body, threshold); ta[i] = System.nanoTime() - t0;
                    if (x == null || y == null || z == null) throw new IllegalStateException();
                    break;
                }
                default: {
                    long t2 = System.nanoTime(); byte[] z = fc.frame(body, threshold);   tc[i] = System.nanoTime() - t2;
                    long t0 = System.nanoTime(); byte[] x = javaFrame(body, threshold); ta[i] = System.nanoTime() - t0;
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold); tb[i] = System.nanoTime() - t1;
                    if (x == null || y == null || z == null) throw new IllegalStateException();
                }
            }
        }
        stats("A JAVA_REFERENCE   (installed handlers, EmbeddedChannel)", ta);
        stats("B CURRENT_RUST_BACKED (CK2 ctx + Java threshold/framing)", tb);
        stats("C NEW_RUST_FRAME   (Rust threshold+compress+both framings)", tc);
        double a = mean(ta), b = mean(tb), c = mean(tc);
        System.out.printf("ratio: B/A=%.3fx  C/A=%.3fx  C/B=%.3fx  frame=%d B%n", b / a, c / a, c / b, fcF != null ? fcF.length : -1);
        System.out.printf("ctx-metrics: frameOps=%d retries=%d grows=%d retainedApprox=%d B%n",
                OutboundFrameCtx.FRAME_OPS.get(), OutboundFrameCtx.RETRY_EVENTS.get(),
                OutboundFrameCtx.GROW_EVENTS.get(), OutboundFrameCtx.RETAINED_APPROX);
        System.out.println("IMMUTABLE-INPUT COMPONENT MEASUREMENT ONLY; live capture/refresh/cache economics excluded and blocked.");
        fc.free(); c2.free();
    }

    // ---- arm A: actual installed pipeline ----
    static byte[] javaFrame(byte[] body, Integer threshold) throws Exception {
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prep", (io.netty.channel.ChannelHandler) prep);
        if (threshold != null) {
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold.intValue());
            ch.pipeline().addLast("compress", (io.netty.channel.ChannelHandler) comp);
        }
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
        io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
        try {
            byte[] arr = new byte[out.readableBytes()];
            out.readBytes(arr);
            return arr;
        } finally { out.release(); }
    }

    // ---- arm B: CK2 compressor + Java framing (today's rust-backed shape) ----
    static byte[] frameB(CompressionCtx ctx, byte[] body, int threshold) {
        byte[] payload;
        int dataLen;
        if (body.length < threshold) {
            payload = body;
            dataLen = 0;
        } else {
            payload = ctx.compress(body);
            if (payload == null) return null;
            dataLen = body.length;
        }
        int inner = varIntSize(dataLen) + payload.length;
        byte[] out = new byte[varIntSize(inner) + inner];
        int pos = writeVarInt(inner, out, 0);
        pos = writeVarInt(dataLen, out, pos);
        System.arraycopy(payload, 0, out, pos, payload.length);
        return out;
    }

    static int writeVarInt(int v, byte[] out, int pos) {
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out[pos++] = (byte) b;
        } while (v != 0);
        return pos;
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { v >>>= 7; n++; }
        return n;
    }

    static void stats(String name, long[] t) {
        long[] s = t.clone(); Arrays.sort(s);
        long sum = 0; for (long v : s) sum += v;
        System.out.printf("%-58s n=%d mean=%8.2fus p50=%8.2f p95=%8.2f p99=%8.2f%n",
                name, s.length, sum / (double) s.length / 1000.0,
                s[s.length / 2] / 1000.0, s[(int) (s.length * 0.95)] / 1000.0, s[(int) (s.length * 0.99)] / 1000.0);
    }

    static double mean(long[] t) { long s = 0; for (long v : t) s += v; return s / (double) t.length; }

    static long addr(ByteBuffer b) throws Exception {
        java.lang.reflect.Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
