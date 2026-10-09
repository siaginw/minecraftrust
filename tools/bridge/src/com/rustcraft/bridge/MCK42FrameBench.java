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
 * M-CK4.2 §3 — equivalent-pipeline three-arm frame benchmark (immutable
 * completed packet bodies in; frames out; OFFLINE).
 *
 * A. INSTALLED-JAVA   : installed NettyCompressionEncoder + installed
 *                       NettyVarint21FrameEncoder (the vanilla 2-handler pipeline).
 * B. RUST-COMPRESS    : MCK42RustCompressJavaFrameHandler (CK2 native compressor,
 *                       inner framing) + installed NettyVarint21FrameEncoder —
 *                       the M-CK2 production shape (2 handlers). Below-threshold
 *                       packets take the pure-Java passthrough (no JNI).
 * C. RUST-FRAME       : RustFrameHandler (1 handler: threshold decision +
 *                       compression + both framings in Rust).
 *
 * DISABLED phase: the vanilla contract (NetworkManager.func_179289_a, negative
 * threshold -> compression handlers REMOVED) means arm A' is the installed
 * prepender ONLY and arm B' is by construction identical to A' (no Rust code
 * runs when compression is off — B's handler must not even be installed); so
 * disabled compares A' vs C' only.
 *
 * Equivalence rules (all arms):
 *  - same input form (complete immutable heap byte[] body) and same fixture
 *    schedule; warmed reusable channel per arm; encode runs inline on the
 *    calling thread (the EmbeddedChannel loop is permissive; in production the
 *    same encode runs on the channel's single event-loop thread);
 *  - timed span = writeOutbound -> readOutbound -> copy to caller byte[]
 *    (draining + result consumption included; decode/compare validation is
 *    OUTSIDE the timed span but runs EVERY iteration, first included);
 *  - no fixed reference output across different inputs: each output is
 *    validated against ITS scheduled input; within-arm determinism checked
 *    against that (arm,payload)'s first output; uncompressed framings
 *    (passthrough payloads, and every disabled-phase frame) must match ACROSS
 *    arms byte-exactly; compressed bytes are NOT compared across Java/Rust
 *    (different deflate implementations) — decode equality is the contract.
 *
 * Schedule is deterministic and LABELED (real fixture + synthetic classes).
 * It does NOT model live traffic distribution.
 *
 * Cold creation + first operation measured separately and symmetrically
 * BEFORE any warmup (all arms: fresh channel+handlers per rep, same first
 * payload, cleanup outside both spans).
 *
 * One JVM = one rotation (args[0], e.g. ABC/BCA/CAB); the launcher runs three
 * fresh JVMs. Per-iteration intra-JVM arm order also rotates.
 */
public class MCK42FrameBench {

    static final int WARM_PASSES = 15, TIMED_PASSES = 40, COLD_REPS = 30;

    static final class Payload {
        final String name, label;
        final byte[] bytes;
        Payload(String name, String label, byte[] bytes) { this.name = name; this.label = label; this.bytes = bytes; }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        String rotation = args.length > 0 ? args[0] : "ABC";
        int threshold = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        Payload[] payloads = buildPayloads();
        System.out.printf("MCK42 rotation=%s threshold=%d java=%s netty=bundled-4.1.9.Final%n",
                rotation, threshold, System.getProperty("java.version"));
        for (Payload p : payloads)
            System.out.printf("PAYLOAD name=%s len=%d label=%s%n", p.name, p.bytes.length, p.label);
        System.out.println("NOTE: deterministic labeled schedule; does NOT model live traffic distribution.");

        // ---- COLD (before any warmup), symmetric across arms, same first payload ----
        coldPhase("cold-enabled", rotation, threshold, payloads[0].bytes, true);
        coldPhase("cold-disabled", rotation, -1, payloads[0].bytes, false);

        // ---- steady-state enabled + disabled ----
        steadyPhase("enabled", rotation, threshold, payloads, true);
        steadyPhase("disabled", rotation, -1, payloads, false);

        System.out.println("IMMUTABLE-INPUT OFFLINE COMPONENT MEASUREMENT ONLY; no live-capture/refresh economics; no TPS claims.");
    }

    // ---- payloads ----
    static Payload[] buildPayloads() throws Exception {
        List<Payload> ps = new ArrayList<>();
        ps.add(new Payload("real-31k", "REAL (installed serializer + enum id)", M53FixtureBuilder.realChunkPacketBody(800, 80)));
        ps.add(new Payload("syn-64", "SYNTHETIC below-threshold constant", constBody(64)));
        ps.add(new Payload("syn-200", "SYNTHETIC below-threshold constant", constBody(200)));
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

    // ---- pipeline construction (per arm) ----
    static EmbeddedChannel newPipeline(char arm, int threshold) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        if (arm == 'A' || arm == 'B') {
            Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
            ch.pipeline().addLast("prep", (ChannelHandler) prep); // tail-most sees body FIRST after compress-layer handler
        }
        if (arm == 'A' && threshold >= 0) {
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
            ch.pipeline().addLast("compress", (ChannelHandler) comp);
        } else if (arm == 'B') {
            ch.pipeline().addLast("rustcompress", new MCK42RustCompressJavaFrameHandler(threshold));
        } else if (arm == 'C') {
            RustFrameHandler h = new RustFrameHandler();
            if (threshold >= 0) h.setThreshold(threshold); // pre-attachment configuration
            ch.pipeline().addLast("rustframe", h);
        } else if (arm == 'A') {
            // disabled A': prepender only (vanilla removes the compress slot)
        }
        return ch;
    }

    /** Timed operation unit for every arm: write -> drain -> copy (identical shape). */
    static byte[] frameOnce(EmbeddedChannel ch, byte[] body) {
        ch.writeOutbound(Unpooled.wrappedBuffer(body));
        ByteBuf out = ch.readOutbound();
        if (out == null) throw new IllegalStateException("no output");
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    static String armDesc(char arm, int threshold) {
        if (threshold < 0) return arm == 'A' ? "A' INSTALLED-JAVA prepender-only (vanilla disabled contract)"
                : "C' RUST-FRAME disabled";
        if (arm == 'A') return "A INSTALLED-JAVA (compress+prep)";
        if (arm == 'B') return "B RUST-COMPRESS (CK2 ctx+Java varint) + installed prep";
        return "C RUST-FRAME (threshold+compress+both framings)";
    }

    // ---- cold phase ----
    static void coldPhase(String name, String rotation, int threshold, byte[] firstBody, boolean enabled) throws Exception {
        String armSet = enabled ? "ABC" : "AC";
        for (char arm : armSet.toCharArray()) {
            long[] construct = new long[COLD_REPS], firstOp = new long[COLD_REPS];
            for (int r = 0; r < COLD_REPS; r++) {
                long t0 = System.nanoTime();
                EmbeddedChannel ch = newPipeline(arm, threshold);
                long t1 = System.nanoTime();
                byte[] f = frameOnce(ch, firstBody); // first operation (consumed; validated below, outside spans)
                long t2 = System.nanoTime();
                byte[] dec = MCK4Validation.decodeFrameSafe(f, threshold >= 0);
                if (dec == null || !Arrays.equals(dec, firstBody)) throw new IllegalStateException("cold output invalid arm=" + arm);
                ch.close();
                construct[r] = t1 - t0;
                firstOp[r] = t2 - t1;
            }
            System.out.printf("COLD phase=%s arm=%c construct_ns_mean=%.0f p50=%.0f | first_op_ns_mean=%.0f p50=%.0f%n",
                    name, arm, mean(construct), p(construct, 0.5), mean(firstOp), p(firstOp, 0.5));
        }
    }

    // ---- steady phase ----
    static void steadyPhase(String name, String rotation, int threshold, Payload[] payloads, boolean enabled) throws Exception {
        String armSet = enabled ? "ABC" : "AC";
        int nArms = armSet.length();
        EmbeddedChannel[] chs = new EmbeddedChannel[nArms];
        for (int i = 0; i < nArms; i++) chs[i] = newPipeline(armSet.charAt(i), threshold);

        // warmup (outputs consumed, not validated — validation cost belongs to the timed phase only by design note)
        for (int w = 0; w < WARM_PASSES; w++)
            for (Payload p : payloads)
                for (int i = 0; i < nArms; i++) frameOnce(chs[i], p.bytes);

        long[][][] t = new long[nArms][payloads.length][TIMED_PASSES];
        byte[][][] firstOut = new byte[nArms][payloads.length][];
        long validationErrors = 0;
        for (int i = 0; i < TIMED_PASSES; i++) {
            int off = i % nArms; // per-iteration rotation
            for (int pi = 0; pi < payloads.length; pi++) {
                byte[][] outs = new byte[nArms][];
                for (int k = 0; k < nArms; k++) {
                    // slot k of this iteration measures arm (k+i)%nArms: per-iteration rotation
                    // on top of the JVM-level rotation given by args[0]
                    int ai = (k + i) % nArms;
                    long t0 = System.nanoTime();
                    byte[] f = frameOnce(chs[ai], payloads[pi].bytes);
                    long t1 = System.nanoTime();
                    t[ai][pi][i] = t1 - t0;
                    outs[ai] = f;
                }
                // validation OUTSIDE timed spans: every iteration, every arm, first included
                boolean passthrough = enabled && payloads[pi].bytes.length < threshold;
                boolean deterministicFraming = !enabled || passthrough;
                for (int ai = 0; ai < nArms; ai++) {
                    byte[] dec = MCK4Validation.decodeFrameSafe(outs[ai], enabled);
                    if (dec == null || !Arrays.equals(dec, payloads[pi].bytes)) validationErrors++;
                    if (firstOut[ai][pi] == null) firstOut[ai][pi] = outs[ai];
                    else if (!Arrays.equals(firstOut[ai][pi], outs[ai])) validationErrors++; // within-arm determinism
                }
                if (deterministicFraming) { // cross-arm byte equality where framing is deterministic
                    for (int ai = 1; ai < nArms; ai++)
                        if (!Arrays.equals(outs[0], outs[ai])) validationErrors++;
                }
            }
        }
        if (validationErrors != 0) throw new IllegalStateException("validation errors=" + validationErrors);
        System.out.printf("PHASE %s rotation=%s validation=OK (every iteration decoded to its input; within-arm determinism; cross-arm exact bytes where deterministic)%n", name, rotation);

        double[] aggMean = new double[nArms];
        for (int ai = 0; ai < nArms; ai++) {
            long[] all = new long[payloads.length * TIMED_PASSES];
            int n = 0;
            for (int pi = 0; pi < payloads.length; pi++) {
                long[] s = t[ai][pi].clone();
                Arrays.sort(s);
                for (int i = 0; i < TIMED_PASSES; i++) all[n++] = t[ai][pi][i];
                System.out.printf("STAT phase=%s arm=%c payload=%s n=%d mean_us=%.2f p50_us=%.2f p95_us=%.2f p99_us=%.2f frame_bytes=%d%n",
                        name, armSet.charAt(ai), payloads[pi].name, TIMED_PASSES,
                        mean(t[ai][pi]) / 1000.0, s[s.length / 2] / 1000.0, s[(int) (s.length * 0.95)] / 1000.0,
                        s[(int) (s.length * 0.99)] / 1000.0, firstOut[ai][pi].length);
            }
            aggMean[ai] = mean(all);
            System.out.printf("AGG phase=%s arm=%c (%s) mean_us=%.2f p50_us=%.2f p95_us=%.2f p99_us=%.2f%n",
                    name, armSet.charAt(ai), armDesc(armSet.charAt(ai), threshold),
                    aggMean[ai] / 1000.0, p(all, 0.5) / 1000.0, p(all, 0.95) / 1000.0, p(all, 0.99) / 1000.0);
        }
        for (int pi = 0; pi < payloads.length; pi++) {
            StringBuilder sb = new StringBuilder("RATIO phase=" + name + " payload=" + payloads[pi].name);
            for (int ai = 1; ai < nArms; ai++)
                sb.append(String.format(" %c/A=%.3fx", armSet.charAt(ai), mean(t[ai][pi]) / mean(t[0][pi])));
            if (nArms == 3)
                sb.append(String.format(" C/B=%.3fx", mean(t[2][pi]) / mean(t[1][pi])));
            System.out.println(sb);
        }
        StringBuilder sb = new StringBuilder("RATIO phase=" + name + " overall");
        for (int ai = 1; ai < nArms; ai++) sb.append(String.format(" %c/A=%.3fx", armSet.charAt(ai), aggMean[ai] / aggMean[0]));
        if (nArms == 3) sb.append(String.format(" C/B=%.3fx", aggMean[2] / aggMean[1]));
        System.out.println(sb);

        // allocation pass (separate, untimed): one schedule pass per arm on this thread
        com.sun.management.ThreadMXBean tb = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int ai = 0; ai < nArms; ai++) {
            long tid = Thread.currentThread().getId();
            long a0 = tb.getThreadAllocatedBytes(tid);
            for (Payload p : payloads) frameOnce(chs[ai], p.bytes);
            long a1 = tb.getThreadAllocatedBytes(tid);
            System.out.printf("ALLOC phase=%s arm=%c bytes_per_pass=%d bytes_per_iter_approx=%d%n",
                    name, armSet.charAt(ai), a1 - a0, (a1 - a0) / payloads.length);
        }
        System.out.printf("SCRATCH phase=%s B_CompressionCtx_retained_approx=%d C_OutboundFrameCtx_retained_approx=%d A_installer_deflater=NOT_MEASURED(JDK-internals)%n",
                name, CompressionCtx.SCRATCH_RETAINED_BYTES_APPROX, OutboundFrameCtx.RETAINED_APPROX);
        System.out.printf("COUNTERS frameOps=%d retries=%d grows=%d (static, whole-JVM)%n",
                OutboundFrameCtx.FRAME_OPS.get(), OutboundFrameCtx.RETRY_EVENTS.get(), OutboundFrameCtx.GROW_EVENTS.get());
        for (int i = 0; i < nArms; i++) chs[i].close();
    }

    static double mean(long[] a) { long s = 0; for (long v : a) s += v; return s / (double) a.length; }

    static double p(long[] a, double q) {
        long[] s = a.clone();
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, (long) (s.length * q))];
    }
}
