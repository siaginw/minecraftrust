package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/**
 * M1.4-R LiveShadowHarness — repaired (no fabricated evidence).
 *
 * Phase 1: real error injection — native panic path + malformed staging header
 *          both must fall back without throwing.
 * Phase 2a: JNI transition lower bound, measured in BATCHES (recovery §4):
 *          time N jniNoop calls, divide by N. Reported as
 *          JNI_TRANSITION_LOWER_BOUND (jniNoop does less work than the
 *          production encodeSections entry).
 * Phase 2b: decomposed timing on real Chunk objects — every column is a direct
 *          measurement; no hardcoded overhead constants, no derived split of
 *          JNI vs Rust inside one call.
 * Phase 3: N consecutive SHADOW-mode live comparisons through the production
 *          entry point populatePacket(packet, chunk, filter) — the same call
 *          the CoreMod-injected hook makes. Workloads are synthetic shapes on
 *          real Chunk objects; no pack is installed or claimed.
 */
public class LiveShadowHarness {

    public static void main(String[] args) throws Exception {
        int campaignCount = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        String rawDir = args.length > 1 ? args[1] : "machine/raw";
        new java.io.File(rawDir).mkdirs();

        Bootstrap.func_151354_b();

        System.out.println("==================================================");
        System.out.println("  M1.4-R LIVE SHADOW HARNESS (repaired)");
        System.out.println("==================================================");

        if (!NativeChunkPacket.isNativeLoaded()) {
            System.err.println("FATAL: Native library rustcraft_ffi is not loaded!");
            System.exit(1);
        }

        runErrorInjectionSuite();
        runJniTransitionLowerBound(rawDir);
        runDecomposedTimingBenchmark(rawDir);
        runShadowCampaign(campaignCount, rawDir);

        System.out.println("\nAll phases complete.");
    }

    // ------------------------------------------------------------------
    // Phase 1: real error injection
    // ------------------------------------------------------------------
    private static void runErrorInjectionSuite() {
        System.out.println("\n--- [Phase 1] Live Error Injection Suite ---");
        long panicsBefore = NativeChunkPacket.M1_NATIVE_PANICS.get();

        // 1a. Forced native panic must return error code, not unwind into JVM.
        int rc = NativeChunkPacket.testForcedPanic();
        if (rc >= 0) throw new AssertionError("testForcedPanic returned " + rc + ", expected negative");
        System.out.println("  [PASS] forced native panic contained: rc=" + rc
                + ", no JVM unwind (panics delta="
                + (NativeChunkPacket.M1_NATIVE_PANICS.get() - panicsBefore) + ")");

        // 1b. Malformed staging header (garbage bytes) must be rejected by JNI.
        ByteBuffer staging = ByteBuffer.allocateDirect(64);
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(64);
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        long oAddr = NativeChunkPacket.getDirectBufferAddress(out);
        int w = NativeChunkPacket.encodeSections(sAddr, 8, oAddr, 64); // garbage header
        if (w >= 0) throw new AssertionError("malformed staging accepted: rc=" + w);
        System.out.println("  [PASS] malformed staging rejected: rc=" + w);

        // 1c. Unknown packet class must be refused by populatePacket identity guard.
        boolean touched = NativeChunkPacket.populatePacket(new Object(), new Object(), 0xFFFF);
        if (touched) throw new AssertionError("identity guard failed");
        System.out.println("  [PASS] identity guard: non-SPacketChunkData refused");
    }

    // ------------------------------------------------------------------
    // Phase 2a: JNI transition lower bound (batched measurement)
    // ------------------------------------------------------------------
    private static void runJniTransitionLowerBound(String rawDir) throws Exception {
        System.out.println("\n--- [Phase 2a] JNI Transition Lower Bound (batched jniNoop) ---");
        final int warmupBatches = 50;
        final int batches = 200;
        int[] callsPerBatch = {1000, 10000, 100000};

        StringBuilder csv = new StringBuilder("calls_per_batch,batches,per_call_median_ns,per_call_p90_ns,per_call_p99_ns\n");
        long sink = 0;
        for (int n : callsPerBatch) {
            for (int b = 0; b < warmupBatches; b++)
                for (int i = 0; i < n; i++) sink += NativeChunkPacket.jniNoop();

            double[] perBatch = new double[batches];
            for (int b = 0; b < batches; b++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) sink += NativeChunkPacket.jniNoop();
                long t1 = System.nanoTime();
                perBatch[b] = (t1 - t0) / (double) n;
            }
            Arrays.sort(perBatch);
            double median = perBatch[batches / 2];
            double p90 = perBatch[(int) (batches * 0.90)];
            double p99 = perBatch[(int) (batches * 0.99)];
            System.out.printf("  n=%7d : per-call %.2f ns (p90 %.2f, p99 %.2f)%n",
                    n, median, p90, p99);
            csv.append(n).append(',').append(batches).append(',')
               .append(String.format("%.4f", median)).append(',')
               .append(String.format("%.4f", p90)).append(',')
               .append(String.format("%.4f", p99)).append('\n');
        }
        if (sink == Long.MIN_VALUE) System.out.println("  (impossible sink)"); // consume return value
        try (PrintWriter pw = new PrintWriter(new FileWriter(rawDir + "/M14R-jni-lower-bound.csv"))) {
            pw.print(csv);
        }
        System.out.println("  raw -> " + rawDir + "/M14R-jni-lower-bound.csv");
        System.out.println("  NOTE: jniNoop does LESS work than encodeSections; this is a");
        System.out.println("        JNI_TRANSITION_LOWER_BOUND, not the production JNI cost.");
    }

    // ------------------------------------------------------------------
    // Phase 2b: decomposed timing on real chunks (measured columns only)
    // ------------------------------------------------------------------
    private static void runDecomposedTimingBenchmark(String rawDir) throws Exception {
        System.out.println("\n--- [Phase 2b] Decomposed Timing (measured columns only) ---");

        int warmup = 2000, samples = 5000;

        String[] profiles = {"0001", "0004", "0008", "FFFF"};
        try (PrintWriter pw = new PrintWriter(new FileWriter(rawDir + "/M14R-shadow-decomposed.csv"))) {
            pw.println("mask,java_ref_us,t_acquire_us,t_stage_us,t_jni_rust_us,t_handoff_us,total_native_us,delta_pct");

            for (String maskHex : profiles) {
                int mask = Integer.parseInt(maskHex, 16);
                boolean fullChunk = mask == 0xFFFF;
                Random rng = new Random(0xC0FFEE);

                long[] javaT = new long[samples], acqT = new long[samples],
                       stageT = new long[samples], jniT = new long[samples],
                       handT = new long[samples], totT = new long[samples];

                ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
                long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);

                Chunk[] chunks = new Chunk[8]; // rotate chunks to avoid pure-cache behavior
                for (int c = 0; c < chunks.length; c++) {
                    M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
                    fx.mask = mask;
                    fx.fullChunk = fullChunk;
                    fx.sectionState = new int[16];
                    for (int s = 0; s < 16; s++) {
                        if ((mask & (1 << s)) != 0) fx.sectionState[s] = 2;
                        else fx.sectionState[s] = 0;
                    }
                    fx.seed = rng.nextLong();
                    chunks[c] = M14RParityHarness.createMockChunk(fx);
                }

                // warmup both paths
                for (int w = 0; w < warmup; w++) {
                    Chunk ch = chunks[w % chunks.length];
                    NativeChunkPacket.setRuntimeMode("OFF");
                    SPacketChunkData ref = new SPacketChunkData(ch, mask);
                    M14RParityHarness.serialize(ref);
                    NativeChunkPacket.setRuntimeMode("SHADOW");
                    NativeChunkPacket.populatePacket(new SPacketChunkData(), ch, mask);
                }

                for (int s = 0; s < samples; s++) {
                    Chunk ch = chunks[s % chunks.length];
                    boolean full = fullChunk;
                    boolean sky = true;

                    // Java reference: real vanilla constructor + real serialization
                    NativeChunkPacket.setRuntimeMode("OFF");
                    long j0 = System.nanoTime();
                    SPacketChunkData refP = new SPacketChunkData(ch, mask);
                    byte[] refBytes = M14RParityHarness.serialize(refP);
                    long j1 = System.nanoTime();
                    javaT[s] = j1 - j0;

                    // native decomposition on the same chunk
                    ExtendedBlockStorage[] sections = ch.func_76587_i();
                    byte[] biomes = full ? ch.func_76605_m() : null;

                    long a0 = System.nanoTime();
                    ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(
                            NativeChunkPacket.MAX_OUTPUT_CAPACITY);
                    long a1 = System.nanoTime();
                    acqT[s] = a1 - a0;

                    staging.clear();
                    long s0 = System.nanoTime();
                    int stagedLen = NativeChunkPacket.populateStagingBuffer(
                            staging, sections, mask, full, sky, biomes);
                    long s1 = System.nanoTime();
                    stageT[s] = s1 - s0;

                    long outAddr = NativeChunkPacket.getNettyBufferAddress(directBuf);

                    long n0 = System.nanoTime();
                    int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, outAddr,
                            directBuf.capacity());
                    long n1 = System.nanoTime();
                    jniT[s] = n1 - n0;

                    long h0 = System.nanoTime();
                    directBuf.writerIndex(written);
                    long h1 = System.nanoTime();
                    handT[s] = h1 - h0;
                    directBuf.release();

                    if (written < 0) throw new AssertionError("encodeSections rc=" + written
                            + " mask=" + maskHex);

                    totT[s] = (a1 - a0) + (s1 - s0) + (n1 - n0) + (h1 - h0);
                }

                double mj = meanUs(javaT), ma = meanUs(acqT), ms = meanUs(stageT),
                       mn = meanUs(jniT), mh = meanUs(handT), mt = meanUs(totT);
                double delta = (mj - mt) / mj * 100.0;
                System.out.printf("  mask 0x%04X : Java %6.2f µs | acquire %5.2f | stage %6.2f | jni+rust %6.2f | handoff %5.2f | TOTAL %6.2f µs | delta %+6.1f%%%n",
                        mask, mj, ma, ms, mn, mh, mt, delta);
                pw.printf("0x%04X,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f%n",
                        mask, mj, ma, ms, mn, mh, mt, delta);
            }
        }
        System.out.println("  raw -> " + rawDir + "/M14R-shadow-decomposed.csv");
    }

    // ------------------------------------------------------------------
    // Phase 3: N consecutive SHADOW-mode live comparisons (production entry)
    // ------------------------------------------------------------------
    private static void runShadowCampaign(int target, String rawDir) throws Exception {
        System.out.println("\n--- [Phase 3] " + target + " Consecutive SHADOW-mode Live Campaign ---");
        System.out.println("  (synthetic workload shapes on real Chunk objects; no pack installed/claimed)");

        NativeChunkPacket.M1_SHADOW_PACKETS.set(0);
        NativeChunkPacket.M1_SHADOW_CONSECUTIVE_MATCHES.set(0);
        NativeChunkPacket.M1_SHADOW_MISMATCHES.set(0);
        NativeChunkPacket.M1_FALLBACKS.set(0);

        Random rng = new Random(777);
        long start = System.currentTimeMillis();

        for (int p = 0; p < target; p++) {
            M14RParityHarness.Fixture fx = (p % 2 == 0)
                    ? M14RParityHarness.buildDeterministic(p % 8)
                    : M14RParityHarness.buildFuzz(rng, p);
            Chunk chunk = M14RParityHarness.createMockChunk(fx);

            NativeChunkPacket.setRuntimeMode("SHADOW");
            SPacketChunkData packet = new SPacketChunkData(); // no-arg; hook not injected here
            boolean javaPath = !NativeChunkPacket.populatePacket(packet, chunk, fx.mask);
            if (!javaPath) throw new AssertionError("SHADOW mode must always return false (Java path)");
            if (packet.func_149274_i()) throw new AssertionError("SHADOW must not mark packet full-chunk");

            if (p > 0 && p % 20000 == 0) {
                System.out.printf("  progress: %d / %d (consecutive matches: %d, mismatches: %d)%n",
                        p, target,
                        NativeChunkPacket.M1_SHADOW_CONSECUTIVE_MATCHES.get(),
                        NativeChunkPacket.M1_SHADOW_MISMATCHES.get());
            }
        }

        long dur = System.currentTimeMillis() - start;
        long matches = NativeChunkPacket.M1_SHADOW_CONSECUTIVE_MATCHES.get();
        long mismatches = NativeChunkPacket.M1_SHADOW_MISMATCHES.get();
        long shadowPkts = NativeChunkPacket.M1_SHADOW_PACKETS.get();
        long fallbacks = NativeChunkPacket.M1_FALLBACKS.get();

        System.out.printf("%n  [CAMPAIGN COMPLETE] %d packets in %d ms (%.0f pkt/s)%n",
                shadowPkts, dur, shadowPkts * 1000.0 / Math.max(1, dur));
        System.out.println("  Consecutive matches: " + matches + " / " + target);
        System.out.println("  Mismatches: " + mismatches);
        System.out.println("  Shadow fallbacks (errors swallowed): " + fallbacks);

        try (PrintWriter pw = new PrintWriter(new FileWriter(rawDir + "/M14R-shadow-campaign.txt"))) {
            pw.println("M1.4-R Live Shadow Campaign (repaired harness)");
            pw.println("target_packets: " + target);
            pw.println("mode: SHADOW via populatePacket(packet, chunk, filter)");
            pw.println("reference: encodeJavaReference (independent vanilla semantics)");
            pw.println("consecutive_matches: " + matches);
            pw.println("mismatches: " + mismatches);
            pw.println("shadow_packets: " + shadowPkts);
            pw.println("fallbacks: " + fallbacks);
            pw.println("duration_ms: " + dur);
            pw.println("workloads: synthetic shapes (det families 0-7 + seeded fuzz); no pack installed");
        }

        if (matches != target || mismatches != 0) {
            throw new RuntimeException("FAILED consecutive shadow gate: matches=" + matches
                    + " mismatches=" + mismatches);
        }
        System.out.println("  [GATE PASSED] " + target + " consecutive live shadow comparisons, zero divergence.");
    }

    private static double meanUs(long[] ns) {
        long sum = 0;
        for (long t : ns) sum += t;
        return (sum / (double) ns.length) / 1000.0;
    }
}
