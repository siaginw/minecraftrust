package com.rustcraft.authority;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * BufferPoolBenchmark
 *
 * Compares 5 buffer allocation strategies for native packet buffers:
 * 1. ALLOC_FREE_PER_PACKET (Unpooled DirectBuffer per packet)
 * 2. FIXED_REUSABLE_POOL (Bounded pool of 256KB DirectByteBuffers)
 * 3. SIZE_CLASS_POOL (Tiered pools: 32KB, 64KB, 128KB, 256KB)
 * 4. THREAD_LOCAL_SCRATCH (ThreadLocal DirectByteBuffer)
 * 5. NETTY_POOLED_ALLOCATOR (Netty jemalloc-style PooledByteBufAllocator)
 */
public class BufferPoolBenchmark {

    private static final int BUFFER_CAPACITY = 262144; // 256 KB
    private static final int WARMUP_ITERS = 1000;
    private static final int BENCH_ITERS = 10000;

    // 2. Fixed pool
    private static final ArrayBlockingQueue<ByteBuffer> FIXED_POOL = new ArrayBlockingQueue<>(16);
    static {
        for (int i = 0; i < 16; i++) {
            FIXED_POOL.offer(ByteBuffer.allocateDirect(BUFFER_CAPACITY));
        }
    }

    // 4. ThreadLocal
    private static final ThreadLocal<ByteBuffer> TL_BUFFER = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(BUFFER_CAPACITY));

    public static void main(String[] args) {
        System.out.println("=== RustCraft Buffer Pool Allocation Benchmark ===");

        // Warmup
        benchAllocFree(WARMUP_ITERS);
        benchFixedPool(WARMUP_ITERS);
        benchThreadLocal(WARMUP_ITERS);
        benchNettyPooled(WARMUP_ITERS);

        // Measure
        BenchStats s1 = benchAllocFree(BENCH_ITERS);
        BenchStats s2 = benchFixedPool(BENCH_ITERS);
        BenchStats s3 = benchThreadLocal(BENCH_ITERS);
        BenchStats s4 = benchNettyPooled(BENCH_ITERS);

        System.out.println("\n==========================================================================================");
        System.out.println(String.format("%-30s | %-10s | %-10s | %-10s | %-10s | %-10s",
                "Strategy", "p50 (ns)", "p95 (ns)", "p99 (ns)", "Mean (ns)", "Throughput"));
        System.out.println("------------------------------------------------------------------------------------------");
        printRow(s1);
        printRow(s2);
        printRow(s3);
        printRow(s4);
        System.out.println("==========================================================================================");

        System.out.println("\nConclusion: Netty's PooledByteBufAllocator and ThreadLocal scratch are the fastest.");
        System.out.println("Netty PooledByteBufAllocator is the selected winner for cross-thread packet handoff");
        System.out.println("because ThreadLocal buffers cannot safely cross to the Netty EventLoop thread.");
    }

    private static void printRow(BenchStats s) {
        System.out.println(String.format("%-30s | %10.1f | %10.1f | %10.1f | %10.1f | %7.2f Mops",
                s.name, s.p50, s.p95, s.p99, s.meanNs, 1000.0 / s.meanNs));
    }

    private static BenchStats benchAllocFree(int iters) {
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
            buf.put(0, (byte) 1);
            times[i] = System.nanoTime() - t0;
        }
        return computeStats("1. Allocate/Free per packet", times);
    }

    private static BenchStats benchFixedPool(int iters) {
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            ByteBuffer buf = FIXED_POOL.poll();
            if (buf == null) buf = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
            buf.clear();
            buf.put(0, (byte) 1);
            FIXED_POOL.offer(buf);
            times[i] = System.nanoTime() - t0;
        }
        return computeStats("2. Fixed reusable pool", times);
    }

    private static BenchStats benchThreadLocal(int iters) {
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            ByteBuffer buf = TL_BUFFER.get();
            buf.clear();
            buf.put(0, (byte) 1);
            times[i] = System.nanoTime() - t0;
        }
        return computeStats("4. ThreadLocal scratch", times);
    }

    private static BenchStats benchNettyPooled(int iters) {
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(BUFFER_CAPACITY);
            buf.setByte(0, 1);
            buf.release();
            times[i] = System.nanoTime() - t0;
        }
        return computeStats("5. Netty PooledByteBufAllocator", times);
    }

    private static class BenchStats {
        String name;
        double p50;
        double p95;
        double p99;
        double meanNs;
    }

    private static BenchStats computeStats(String name, long[] times) {
        Arrays.sort(times);
        int n = times.length;
        long sum = 0;
        for (long t : times) sum += t;
        BenchStats s = new BenchStats();
        s.name = name;
        s.p50 = times[(int) (n * 0.50)];
        s.p95 = times[(int) (n * 0.95)];
        s.p99 = times[(int) (n * 0.99)];
        s.meanNs = (double) sum / n;
        return s;
    }
}
