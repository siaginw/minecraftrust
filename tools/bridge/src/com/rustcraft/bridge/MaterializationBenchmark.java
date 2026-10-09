package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Arrays;
import sun.misc.Unsafe;

/**
 * Benchmark for Java ChunkPrimer materialization paths:
 * Path 1: Heap CharBuffer loop / bulk get
 * Path 2: Unsafe.copyMemory off-heap to heap char[]
 * Path 3: Direct native JNI reverse materialization
 */
public class MaterializationBenchmark {

    private static final int WARMUP_ITERS = 5000;
    private static final int BENCH_ITERS = 10000;
    private static final int PRIMER_CHARS = 65536;
    private static final int PRIMER_BYTES = PRIMER_CHARS * 2;

    private static Unsafe unsafe;
    private static long charArrayOffset;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
            charArrayOffset = unsafe.arrayBaseOffset(char[].class);
        } catch (Throwable t) {
            unsafe = null;
        }
    }

    private static long address(ByteBuffer bb) {
        if (unsafe == null) return 0;
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(bb);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== M4 JAVA CHUNKPRIMER MATERIALIZATION BENCHMARK ===");

        // Setup synthetic test terrain in direct buffer
        ByteBuffer directSrc = ByteBuffer.allocateDirect(PRIMER_BYTES).order(ByteOrder.nativeOrder());
        CharBuffer cb = directSrc.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                cb.put(col | 0, (char) 7); // bedrock
                for (int y = 1; y <= 63; y++) cb.put(col | y, (char) 1); // stone
                for (int y = 64; y <= 67; y++) cb.put(col | y, (char) 3); // dirt
                cb.put(col | 68, (char) 2); // grass
            }
        }

        long srcAddr = address(directSrc);
        char[] targetArray = new char[PRIMER_CHARS];

        // Register in native state for Path 3
        long genId = 0;
        if (NativeChunkBridge.isAvailable()) {
            genId = NativeChunkBridge.register(10, 20, srcAddr, 0);
            System.out.println("NativeChunk registered, genId=" + genId);
        }

        // --- Warmup ---
        for (int i = 0; i < WARMUP_ITERS; i++) {
            // Path 1
            directSrc.rewind();
            directSrc.asCharBuffer().get(targetArray);

            // Path 2
            if (unsafe != null) {
                unsafe.copyMemory(null, srcAddr, targetArray, charArrayOffset, PRIMER_BYTES);
            }

            // Path 3
            if (genId > 0) {
                NativeChunkBridge.materializePrimer(10, 20, genId, srcAddr);
            }
        }

        // --- Benchmark Path 1: CharBuffer.get() ---
        long[] p1Times = new long[BENCH_ITERS];
        for (int i = 0; i < BENCH_ITERS; i++) {
            long t0 = System.nanoTime();
            directSrc.rewind();
            directSrc.asCharBuffer().get(targetArray);
            p1Times[i] = System.nanoTime() - t0;
        }

        // --- Benchmark Path 2: Unsafe.copyMemory ---
        long[] p2Times = new long[BENCH_ITERS];
        if (unsafe != null) {
            for (int i = 0; i < BENCH_ITERS; i++) {
                long t0 = System.nanoTime();
                unsafe.copyMemory(null, srcAddr, targetArray, charArrayOffset, PRIMER_BYTES);
                p2Times[i] = System.nanoTime() - t0;
            }
        }

        // --- Benchmark Path 3: Native Reverse Materialization ---
        long[] p3Times = new long[BENCH_ITERS];
        if (genId > 0) {
            for (int i = 0; i < BENCH_ITERS; i++) {
                long t0 = System.nanoTime();
                NativeChunkBridge.materializePrimer(10, 20, genId, srcAddr);
                p3Times[i] = System.nanoTime() - t0;
            }
        }

        // Compute stats
        printStats("Path 1 (CharBuffer.get heap copy)", p1Times);
        if (unsafe != null) {
            printStats("Path 2 (Unsafe.copyMemory off-heap to heap)", p2Times);
        }
        if (genId > 0) {
            printStats("Path 3 (NativeChunk JNI reverse materialization)", p3Times);
        }

        // Invalidation & Unload test
        if (genId > 0) {
            int invRes = NativeChunkBridge.invalidate(10, 20);
            System.out.println("Invalidation test: " + (invRes == 1 ? "SUCCESS" : "FAILED"));
            int unlRes = NativeChunkBridge.unload(10, 20);
            System.out.println("Unload test: " + (unlRes == 1 ? "SUCCESS" : "FAILED"));
        }
    }

    private static void printStats(String name, long[] times) {
        Arrays.sort(times);
        long sum = 0;
        for (long t : times) sum += t;
        double meanUs = (sum / (double) times.length) / 1000.0;
        double p50Us = times[(int) (times.length * 0.50)] / 1000.0;
        double p95Us = times[(int) (times.length * 0.95)] / 1000.0;
        double p99Us = times[(int) (times.length * 0.99)] / 1000.0;
        System.out.println("--- " + name + " ---");
        System.out.printf("  Mean: %.2f us | P50: %.2f us | P95: %.2f us | P99: %.2f us\n",
                meanUs, p50Us, p95Us, p99Us);
    }
}
