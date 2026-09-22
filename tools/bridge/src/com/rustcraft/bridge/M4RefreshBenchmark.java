package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * M4.1 Task 8 — Refresh model measurements.
 *
 * Measures the section-level lazy-pull refresh path end to end:
 *   1. markSectionMutation  — hook cost paid per Java-side mutation (must be trivial)
 *   2. refreshSection       — 12 KB pull (8 KB u16 states + 2 KB block light + 2 KB sky light)
 *   3. 5-section chunk refresh — population/post-populate burst case
 *   4. registerPrimer       — full-chunk re-register (128 KB) as the coarse alternative
 *   5. encodePacket after refresh — palette rebuild amortization check
 */
public class M4RefreshBenchmark {

    private static final int WARMUP = 2000;
    private static final int ITERS = 10000;

    private static final int CX = 77, CZ = -33;

    private static java.lang.reflect.Field addressField;
    static {
        try {
            addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
        } catch (Throwable t) { addressField = null; }
    }
    private static long addr(ByteBuffer bb) {
        try { return addressField.getLong(bb); } catch (Throwable t) { return 0; }
    }

    public static void main(String[] args) {
        System.out.println("=== M4.1 REFRESH MODEL BENCHMARK ===");
        if (!NativeChunkBridge.isAvailable()) {
            System.err.println("NativeChunkBridge not available");
            return;
        }

        // Synthetic terrain: 5 active sections (y=0..68 overworld-like)
        ByteBuffer primerBB = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer pc = primerBB.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 0, (char) 7);
                for (int y = 1; y <= 63; y++) pc.put(col | y, (char) 1);
                for (int y = 64; y <= 67; y++) pc.put(col | y, (char) 3);
                pc.put(col | 68, (char) 2);
            }
        }

        // Section refresh source: layer-major u16[4096] (stone+dirt mix, 60 unique ids)
        ByteBuffer statesBB = ByteBuffer.allocateDirect(4096 * 2).order(ByteOrder.nativeOrder());
        CharBuffer sc = statesBB.asCharBuffer();
        for (int i = 0; i < 4096; i++) sc.put(i, (char) (1 + (i % 60)));
        ByteBuffer blockLightBB = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder());
        blockLightBB.put(new byte[2048]).rewind();
        for (int i = 0; i < 2048; i++) blockLightBB.put(i, (byte) 7);
        ByteBuffer skyLightBB = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 2048; i++) skyLightBB.put(i, (byte) 15);

        ByteBuffer outBB = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder());
        long statesAddr = addr(statesBB), blAddr = addr(blockLightBB), slAddr = addr(skyLightBB);
        long outAddr = addr(outBB), primerAddr = addr(primerBB);

        long genId = NativeChunkBridge.register(0, CX, CZ, primerAddr, 0);
        System.out.println("registered genId=" + genId);

        long[] t = new long[ITERS];

        // --- 1. markSectionMutation hook cost ---
        for (int i = 0; i < WARMUP; i++) NativeChunkBridge.markSectionMutation(0, CX, CZ, (byte) 3);
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.markSectionMutation(0, CX, CZ, (byte) 3);
            t[i] = System.nanoTime() - t0;
        }
        stats("markSectionMutation (hook, per Java mutation)", t, 0);

        // --- 2. refreshSection full 12KB ---
        for (int i = 0; i < WARMUP; i++) NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, blAddr, slAddr);
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, blAddr, slAddr);
            t[i] = System.nanoTime() - t0;
        }
        stats("refreshSection (12KB: 8KB states + 4KB light)", t, 12288);

        // --- 3. refreshSection states-only 8KB ---
        for (int i = 0; i < WARMUP; i++) NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, 0, 0);
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, 0, 0);
            t[i] = System.nanoTime() - t0;
        }
        stats("refreshSection (8KB states only)", t, 8192);

        // --- 4. 5-section chunk refresh burst (population case) ---
        for (int i = 0; i < WARMUP; i++) {
            for (byte y = 0; y < 5; y++) NativeChunkBridge.refreshSection(0, CX, CZ, y, statesAddr, blAddr, slAddr);
        }
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            for (byte y = 0; y < 5; y++) NativeChunkBridge.refreshSection(0, CX, CZ, y, statesAddr, blAddr, slAddr);
            t[i] = System.nanoTime() - t0;
        }
        stats("5-section chunk refresh (population burst)", t, 5 * 12288);

        // --- 5. Full re-register via primer (coarse alternative) ---
        for (int i = 0; i < WARMUP; i++) NativeChunkBridge.register(0, CX, CZ, primerAddr, 0);
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.register(0, CX, CZ, primerAddr, 0);
            t[i] = System.nanoTime() - t0;
        }
        stats("registerPrimer (full-chunk 128KB re-register)", t, 131072);
        genId = NativeChunkBridge.register(0, CX, CZ, primerAddr, 0); // final stable handle

        // --- 6. encodePacket after refresh (palette rebuild amortization) ---
        for (int i = 0; i < WARMUP; i++) NativeChunkBridge.encodePacket(0, CX, CZ, genId, true, true, outAddr, 65536);
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.encodePacket(0, CX, CZ, genId, true, true, outAddr, 65536);
            t[i] = System.nanoTime() - t0;
        }
        stats("encodePacket post-refresh (cached palette)", t, 0);

        // Refresh-then-encode sequence cost
        for (int i = 0; i < WARMUP; i++) {
            NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, blAddr, slAddr);
            NativeChunkBridge.encodePacket(0, CX, CZ, genId, true, true, outAddr, 65536);
        }
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            NativeChunkBridge.refreshSection(0, CX, CZ, (byte) 3, statesAddr, blAddr, slAddr);
            NativeChunkBridge.encodePacket(0, CX, CZ, genId, true, true, outAddr, 65536);
            t[i] = System.nanoTime() - t0;
        }
        stats("refresh 1 section + encode packet (total path)", t, 12288);

        NativeChunkBridge.unload(0, CX, CZ);
        System.out.println("\n=== COMPLETE ===");
    }

    private static void stats(String label, long[] t, long bytes) {
        long[] s = t.clone();
        Arrays.sort(s);
        long sum = 0;
        for (long v : s) sum += v;
        double meanUs = (sum / (double) s.length) / 1000.0;
        double p50 = s[(int) (s.length * 0.50)] / 1000.0;
        double p95 = s[(int) (s.length * 0.95)] / 1000.0;
        double p99 = s[(int) (s.length * 0.99)] / 1000.0;
        System.out.printf("%-52s mean=%8.2fus p50=%8.2f p95=%8.2f p99=%8.2f", label, meanUs, p50, p95, p99);
        if (bytes > 0) {
            System.out.printf("  [%d B -> %.2f GB/s]", bytes, bytes / (meanUs * 1000.0));
        }
        System.out.println();
    }
}
