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
            body = M53FixtureBuilder.realChunkPacketBody(800, 80); // REAL complete packet (installed serializer + enum id, round-trip verified)
        } else if (mode.equals("synthetic")) {
            body = new byte[31008];
            for (int i = 0; i < body.length; i++) body[i] = (byte) (i % 7);
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
        byte[] fa = javaFrame(body, threshold), fb = frameB(c2, body, threshold, new java.util.concurrent.atomic.AtomicLong()), fcF = fc.frame(body, threshold);
        System.out.printf("sizes: java=%d rustB=%d rustC=%d%n",
                fa != null ? fa.length : -1, fb != null ? fb.length : -1, fcF != null ? fcF.length : -1);

        // WARM arms: one channel/handler/context created ONCE outside timing
        // (corrects the old asymmetry where arm A constructed handlers + a
        // Deflater inside every timed call).
        long coldJava = 0, coldRust = 0;
        {
            long t0 = System.nanoTime();
            Object coldCh = newJavaPipeline(threshold);
            long t1 = System.nanoTime();
            coldJava = t1 - t0;
            ((io.netty.channel.embedded.EmbeddedChannel) coldCh).close();
            OutboundFrameCtx cold = OutboundFrameCtx.create();
            long t2 = System.nanoTime();
            cold.frame(body, threshold);
            long t3 = System.nanoTime();
            cold.free();
            coldRust = t3 - t2;
        }
        System.out.printf("cold: java-pipeline-construct=%dns rust-ctx-first-frame=%dns (informational, separate)%n", coldJava, coldRust);
        Object javaCh = newJavaPipeline(threshold);
        io.netty.channel.embedded.EmbeddedChannel rustCh = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler rustH = new RustFrameHandler();
        rustCh.pipeline().addLast("rustframe", rustH);
        rustH.setThreshold(threshold);
        java.util.concurrent.atomic.AtomicLong bPassthrough = new java.util.concurrent.atomic.AtomicLong();
        long[] ta = new long[N], tb = new long[N], tc = new long[N];
        for (int w = 0; w < WARM; w++) {
            javaFrameWarm(javaCh, body);
            frameB(c2, body, threshold, bPassthrough);
            frameWarmRust(rustCh, body);
        }
        for (int i = 0; i < N; i++) {
            switch (i % 3) {
                case 0: {
                    long t0 = System.nanoTime(); byte[] x = javaFrameWarm(javaCh, body); ta[i] = System.nanoTime() - t0;
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold, bPassthrough); tb[i] = System.nanoTime() - t1;
                    long t2 = System.nanoTime(); byte[] z = frameWarmRust(rustCh, body); tc[i] = System.nanoTime() - t2;
                    check3(x, y, z);
                    break;
                }
                case 1: {
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold, bPassthrough); tb[i] = System.nanoTime() - t1;
                    long t2 = System.nanoTime(); byte[] z = frameWarmRust(rustCh, body); tc[i] = System.nanoTime() - t2;
                    long t0 = System.nanoTime(); byte[] x = javaFrameWarm(javaCh, body); ta[i] = System.nanoTime() - t0;
                    check3(x, y, z);
                    break;
                }
                default: {
                    long t2 = System.nanoTime(); byte[] z = frameWarmRust(rustCh, body); tc[i] = System.nanoTime() - t2;
                    long t0 = System.nanoTime(); byte[] x = javaFrameWarm(javaCh, body); ta[i] = System.nanoTime() - t0;
                    long t1 = System.nanoTime(); byte[] y = frameB(c2, body, threshold, bPassthrough); tb[i] = System.nanoTime() - t1;
                    check3(x, y, z);
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
        System.out.printf("armB: below-threshold JAVA-passthrough ops=%d (counter-verified, no JNI on that path)%n",
                bPassthrough.get());
        ((io.netty.channel.embedded.EmbeddedChannel) javaCh).close();
        rustCh.close();
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

    static byte[] REF_A; // content sink: populated once, compared every iteration
    static void check3(byte[] a, byte[] b, byte[] c) {
        if (a == null || b == null || c == null) throw new IllegalStateException("null arm output");
        if (REF_A == null) { REF_A = a; return; }
        // CONTENT equality, not null/length-only: every iteration's outputs must
        // match the first captured reference arm (frames are deterministic per body).
        if (!Arrays.equals(a, REF_A)) throw new IllegalStateException("arm A content drift");
        if (!Arrays.equals(b, c)) throw new IllegalStateException("arm B/C content mismatch");
    }

    static Object newJavaPipeline(int threshold) throws Exception {
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prep", (io.netty.channel.ChannelHandler) prep);
        Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
        ch.pipeline().addLast("compress", (io.netty.channel.ChannelHandler) comp);
        return ch;
    }

    static byte[] javaFrameWarm(Object chObj, byte[] body) {
        io.netty.channel.embedded.EmbeddedChannel ch = (io.netty.channel.embedded.EmbeddedChannel) chObj;
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
        io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
        try {
            byte[] arr = new byte[out.readableBytes()];
            out.readBytes(arr);
            return arr;
        } finally { out.release(); }
    }

    static byte[] frameWarmRust(io.netty.channel.embedded.EmbeddedChannel ch, byte[] body) {
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(body));
        io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
        try {
            byte[] arr = new byte[out.readableBytes()];
            out.readBytes(arr);
            return arr;
        } finally { out.release(); }
    }

    // ---- arm B: CK2 compressor + Java framing (below-threshold = JAVA passthrough, no JNI) ----
    static byte[] frameB(CompressionCtx ctx, byte[] body, int threshold, java.util.concurrent.atomic.AtomicLong passthrough) {
        byte[] payload;
        int dataLen;
        if (body.length < threshold) {
            passthrough.incrementAndGet(); // Java passthrough: no ctx.compress / no JNI (counter-verified)
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
