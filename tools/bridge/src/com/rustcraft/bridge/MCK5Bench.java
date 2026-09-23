package com.rustcraft.bridge;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * M-CK5 §6 — component benchmark: A (installed Java) / B (CK2 rust compressor +
 * installed prepender) / C (legacy full-Rust frame adapter) / D (M-CK5 optimized
 * direct-address adapter) — equivalent warmed handler/pipeline boundaries,
 * identical deterministic schedules, output ownership and validation outside
 * timing; PLUS a separate observer-overhead phase (observer ABSENT / OFF / ON
 * 1:1 / ON 1:4 on the SAME installed Java pipeline) so shadow-computation cost
 * is never mixed with the replacement adapter's speedup.
 *
 * All writes use DIRECT input buffers (the production shape: the packet
 * serializer emits an allocated buffer; D's direct path engages, C stages as
 * before, heap/composite fallback correctness is covered by gates not timed
 * here). C and D are both Rust and deterministic: their frames must be
 * BYTE-IDENTICAL every iteration (validated). Passthrough payloads additionally
 * require A==B==C==D exact bytes.
 */
public class MCK5Bench {

    static final int WARM_PASSES = 15, TIMED_PASSES = 40, COLD_REPS = 30;
    static final String ARMS = "ABCD";

    static final class Payload {
        final String name, label;
        final byte[] bytes;
        Payload(String n, String l, byte[] b) { name = n; label = l; bytes = b; }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        String rotation = args.length > 0 ? args[0] : "ABCD";
        int threshold = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        Payload[] payloads = buildPayloads();
        System.out.printf("MCK5 rotation=%s threshold=%d java=%s netty=bundled-4.1.9.Final inputs=DIRECT%n",
                rotation, threshold, System.getProperty("java.version"));
        for (Payload p : payloads)
            System.out.printf("PAYLOAD name=%s len=%d label=%s%n", p.name, p.bytes.length, p.label);
        System.out.println("NOTE: deterministic labeled schedule; does NOT model live traffic distribution.");

        coldPhase(rotation, threshold, payloads[0].bytes);
        steadyPhase(rotation, threshold, payloads);
        observerOverheadPhase(threshold, payloads);
        System.out.println("IMMUTABLE-INPUT OFFLINE COMPONENT MEASUREMENT ONLY; no TPS claims; issue #1 OPEN.");
    }

    static Payload[] buildPayloads() throws Exception {
        List<Payload> ps = new ArrayList<>();
        ps.add(new Payload("real-31k", "REAL (installed serializer + enum id)", M53FixtureBuilder.realChunkPacketBody(800, 80)));
        ps.add(new Payload("syn-64", "SYNTHETIC below-threshold constant", constBody(64)));
        ps.add(new Payload("syn-255", "SYNTHETIC threshold-adjacent (below)", constBody(255)));
        ps.add(new Payload("syn-257r", "SYNTHETIC threshold-adjacent (above), PRNG(7)", prng(257, 7)));
        ps.add(new Payload("syn-rep-64k", "SYNTHETIC repetitive pattern", pattern(65536)));
        ps.add(new Payload("syn-rnd-64k", "SYNTHETIC incompressible PRNG(42)", prng(65536, 42)));
        ps.add(new Payload("syn-rep-1m", "SYNTHETIC repetitive pattern", pattern(1048576)));
        ps.add(new Payload("syn-rnd-1m", "SYNTHETIC incompressible PRNG(43)", prng(1048576, 43)));
        return ps.toArray(new Payload[0]);
    }

    static byte[] constBody(int n) { byte[] b = new byte[n]; Arrays.fill(b, (byte) 7); return b; }

    static byte[] pattern(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (i % 251); return b; }

    static byte[] prng(int n, int seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    static EmbeddedChannel newPipeline(char arm, int threshold) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        if (arm == 'A' || arm == 'B') {
            Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
            ch.pipeline().addLast("prep", (ChannelHandler) prep);
        }
        if (arm == 'A' && threshold >= 0) {
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
            ch.pipeline().addLast("compress", (ChannelHandler) comp);
        } else if (arm == 'B') {
            ch.pipeline().addLast("rustcompress", new MCK42RustCompressJavaFrameHandler(threshold));
        } else if (arm == 'C' || arm == 'D') {
            RustFrameHandler h = new RustFrameHandler();
            h.useDirectPaths = arm == 'D';
            if (threshold >= 0) h.setThreshold(threshold);
            ch.pipeline().addLast("rustframe", h);
        }
        return ch;
    }

    static ByteBuf directIn(byte[] body) { return Unpooled.directBuffer(body.length).writeBytes(body); }

    static byte[] frameOnce(EmbeddedChannel ch, byte[] body) {
        ch.writeOutbound(directIn(body));
        ByteBuf out = ch.readOutbound();
        if (out == null) throw new IllegalStateException("no output");
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    static String armDesc(char arm) {
        if (arm == 'A') return "A INSTALLED-JAVA (compress+prep)";
        if (arm == 'B') return "B RUST-COMPRESS (CK2) + installed prep";
        if (arm == 'C') return "C RUST-FRAME legacy staging";
        return "D RUST-FRAME direct-address (M-CK5 opt)";
    }

    static void coldPhase(String rotation, int threshold, byte[] firstBody) throws Exception {
        for (char arm : ARMS.toCharArray()) {
            long[] construct = new long[COLD_REPS], firstOp = new long[COLD_REPS];
            for (int r = 0; r < COLD_REPS; r++) {
                long t0 = System.nanoTime();
                EmbeddedChannel ch = newPipeline(arm, threshold);
                long t1 = System.nanoTime();
                byte[] f = frameOnce(ch, firstBody);
                long t2 = System.nanoTime();
                byte[] dec = MCK4Validation.decodeFrameSafe(f, threshold >= 0);
                if (dec == null || !Arrays.equals(dec, firstBody)) throw new IllegalStateException("cold output invalid arm=" + arm);
                ch.close();
                construct[r] = t1 - t0;
                firstOp[r] = t2 - t1;
            }
            System.out.printf("COLD arm=%c construct_ns_mean=%.0f p50=%.0f | first_op_ns_mean=%.0f p50=%.0f%n",
                    arm, mean(construct), p(construct, 0.5), mean(firstOp), p(firstOp, 0.5));
        }
    }

    static void steadyPhase(String rotation, int threshold, Payload[] payloads) throws Exception {
        int nArms = ARMS.length();
        EmbeddedChannel[] chs = new EmbeddedChannel[nArms];
        for (int i = 0; i < nArms; i++) chs[i] = newPipeline(ARMS.charAt(i), threshold);

        for (int w = 0; w < WARM_PASSES; w++)
            for (Payload p : payloads)
                for (int i = 0; i < nArms; i++) frameOnce(chs[i], p.bytes);

        long[][][] t = new long[nArms][payloads.length][TIMED_PASSES];
        byte[][][] firstOut = new byte[nArms][payloads.length][];
        long validationErrors = 0;
        for (int i = 0; i < TIMED_PASSES; i++) {
            for (int pi = 0; pi < payloads.length; pi++) {
                byte[][] outs = new byte[nArms][];
                for (int k = 0; k < nArms; k++) {
                    int ai = (k + i) % nArms;
                    long t0 = System.nanoTime();
                    byte[] f = frameOnce(chs[ai], payloads[pi].bytes);
                    long t1 = System.nanoTime();
                    t[ai][pi][i] = t1 - t0;
                    outs[ai] = f;
                }
                boolean passthrough = payloads[pi].bytes.length < threshold;
                for (int ai = 0; ai < nArms; ai++) {
                    byte[] dec = MCK4Validation.decodeFrameSafe(outs[ai], threshold >= 0);
                    if (dec == null || !Arrays.equals(dec, payloads[pi].bytes)) validationErrors++;
                    if (firstOut[ai][pi] == null) firstOut[ai][pi] = outs[ai];
                    else if (!Arrays.equals(firstOut[ai][pi], outs[ai])) validationErrors++;
                }
                if (passthrough) { // deterministic framing: all four arms byte-identical
                    for (int ai = 1; ai < nArms; ai++) if (!Arrays.equals(outs[0], outs[ai])) validationErrors++;
                }
                // C and D are both Rust + deterministic: byte-identical ALWAYS
                if (!Arrays.equals(outs[2], outs[3])) validationErrors++;
            }
        }
        if (validationErrors != 0) throw new IllegalStateException("validation errors=" + validationErrors);
        System.out.printf("PHASE steady rotation=%s validation=OK (decode-to-input every iteration; within-arm determinism; passthrough cross-arm exact bytes; C==D byte-identical always)%n", rotation);

        double[] agg = new double[nArms];
        for (int ai = 0; ai < nArms; ai++) {
            long[] all = new long[payloads.length * TIMED_PASSES];
            int n = 0;
            for (int pi = 0; pi < payloads.length; pi++) {
                long[] s = t[ai][pi].clone();
                Arrays.sort(s);
                for (int i = 0; i < TIMED_PASSES; i++) all[n++] = t[ai][pi][i];
                System.out.printf("STAT arm=%c payload=%s n=%d mean_us=%.2f p50_us=%.2f p95_us=%.2f p99_us=%.2f frame_bytes=%d%n",
                        ARMS.charAt(ai), payloads[pi].name, TIMED_PASSES,
                        mean(t[ai][pi]) / 1000.0, s[s.length / 2] / 1000.0, s[(int) (s.length * 0.95)] / 1000.0,
                        s[(int) (s.length * 0.99)] / 1000.0, firstOut[ai][pi].length);
            }
            agg[ai] = mean(all);
            System.out.printf("AGG arm=%c (%s) mean_us=%.2f p50_us=%.2f p95_us=%.2f p99_us=%.2f%n",
                    ARMS.charAt(ai), armDesc(ARMS.charAt(ai)), agg[ai] / 1000.0, p(all, 0.5) / 1000.0, p(all, 0.95) / 1000.0, p(all, 0.99) / 1000.0);
        }
        for (int pi = 0; pi < payloads.length; pi++) {
            StringBuilder sb = new StringBuilder("RATIO payload=" + payloads[pi].name);
            for (int ai = 1; ai < nArms; ai++)
                sb.append(String.format(" %c/A=%.3fx", ARMS.charAt(ai), mean(t[ai][pi]) / mean(t[0][pi])));
            sb.append(String.format(" D/C=%.3fx", mean(t[3][pi]) / mean(t[2][pi])));
            System.out.println(sb);
        }
        StringBuilder sb = new StringBuilder("RATIO overall");
        for (int ai = 1; ai < nArms; ai++) sb.append(String.format(" %c/A=%.3fx", ARMS.charAt(ai), agg[ai] / agg[0]));
        sb.append(String.format(" D/C=%.3fx", agg[3] / agg[2]));
        System.out.println(sb);

        com.sun.management.ThreadMXBean tb = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        for (int ai = 0; ai < nArms; ai++) {
            long a0 = tb.getThreadAllocatedBytes(tid);
            for (Payload p : payloads) frameOnce(chs[ai], p.bytes);
            long a1 = tb.getThreadAllocatedBytes(tid);
            System.out.printf("ALLOC arm=%c bytes_per_pass=%d bytes_per_iter_approx=%d%n",
                    ARMS.charAt(ai), a1 - a0, (a1 - a0) / payloads.length);
        }
        // retained scratch: instance-level for C and D
        for (int ai = 2; ai <= 3; ai++) {
            RustFrameHandler h = (RustFrameHandler) chs[ai].pipeline().get("rustframe");
            System.out.printf("RETAINED arm=%c ctx_java_direct_scratch_bytes=%d%n", ARMS.charAt(ai), h.ctx.retainedBytes());
        }
        System.out.printf("RETAINED arm=B CompressionCtx_static_approx=%d A=NOT_MEASURED(JDK-internals)%n", CompressionCtx.SCRATCH_RETAINED_BYTES_APPROX);
        System.out.printf("COUNTERS directFrames=%d fallbackFrames=%d frameOps=%d retries=%d grows=%d%n",
                RustFrameHandler.DIRECT_FRAMES.get(), RustFrameHandler.FALLBACK_FRAMES.get(),
                OutboundFrameCtx.FRAME_OPS.get(), OutboundFrameCtx.RETRY_EVENTS.get(), OutboundFrameCtx.GROW_EVENTS.get());
        for (int i = 0; i < nArms; i++) chs[i].close();
    }

    /** Observer overhead on the SAME installed Java pipeline: ABSENT / OFF / ON(1:1) / ON(1:4). */
    static void observerOverheadPhase(int threshold, Payload[] payloads) throws Exception {
        String[] states = { "ABSENT", "OFF", "ON-1of1", "ON-1of4" };
        long[][] t = new long[states.length][TIMED_PASSES * payloads.length];
        java.util.concurrent.atomic.AtomicLong matched = new java.util.concurrent.atomic.AtomicLong();
        for (int st = 0; st < states.length; st++) {
            EmbeddedChannel ch = new EmbeddedChannel();
            Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
            ch.pipeline().addLast("prep", (ChannelHandler) prep);
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
            ch.pipeline().addLast("compress", (ChannelHandler) comp);
            FrameShadowObserver obs = FrameShadowObserver.install(ch.pipeline(), new FrameShadowObserver.Config());
            if (st >= 2) {
                obs.setEnabled(true);
                if (st == 3) obs.setSampleEvery(4);
            }
            int idx = 0;
            byte[][] first = new byte[payloads.length][];
            for (int w = 0; w < WARM_PASSES; w++)
                for (Payload p : payloads) frameOnce(ch, p.bytes);
            for (int i = 0; i < TIMED_PASSES; i++) {
                for (int pi = 0; pi < payloads.length; pi++) {
                    long t0 = System.nanoTime();
                    byte[] f = frameOnce(ch, payloads[pi].bytes);
                    long t1 = System.nanoTime();
                    t[st][idx++] = t1 - t0;
                    byte[] dec = MCK4Validation.decodeFrameSafe(f, true);
                    if (dec == null || !Arrays.equals(dec, payloads[pi].bytes)) throw new IllegalStateException("observer-state output invalid: " + states[st]);
                    if (first[pi] == null) first[pi] = f;
                    else if (!Arrays.equals(first[pi], f)) throw new IllegalStateException("observer changed Java output: " + states[st]);
                }
            }
            if (st >= 2) matched.set(obs.MATCHED.get());
            if (st >= 2) obs.uninstall();
            ch.close();
            System.out.printf("OBSERVER state=%s mean_us=%.2f p50_us=%.2f p95_us=%.2f p99_us=%.2f matched=%d%n",
                    states[st], mean(t[st]) / 1000.0, p(t[st], 0.5) / 1000.0, p(t[st], 0.95) / 1000.0, p(t[st], 0.99) / 1000.0, matched.get());
        }
        System.out.printf("OBSERVER overhead ON(1:1) vs ABSENT: %.3fx of Java-pipeline time; ON(1:4): %.3fx%n",
                mean(t[2]) / mean(t[0]), mean(t[3]) / mean(t[0]));
    }

    static double mean(long[] a) { long s = 0; for (long v : a) s += v; return s / (double) a.length; }

    static double p(long[] a, double q) {
        long[] s = a.clone();
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, (long) (s.length * q))];
    }
}
