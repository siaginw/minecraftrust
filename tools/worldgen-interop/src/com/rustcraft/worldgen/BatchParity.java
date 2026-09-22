package com.rustcraft.worldgen;

import com.rustcraft.interop.WNoiseInterop;
import net.minecraft.world.gen.NoiseGeneratorOctaves;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * M3W2 batched differential parity + 3-tier benchmark.
 *
 * Oracle = REAL NoiseGeneratorOctaves per chunk. Candidate = batched SIMD
 * Rust through ONE JNI call per batch. Bit-exact comparison.
 * Benchmark tiers: JAVA_REFERENCE / RUST_PARITY (per-chunk gen3d) /
 * RUST_OPTIMIZED (batched) at n = 1/4/8/16 chunks.
 */
public final class BatchParity {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M3W2-batch.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
        System.loadLibrary("rustcraft_ffi");

        // ---------- PARITY ----------
        long mismatches = 0, fields = 0, samples = 0;
        int[] octaveCounts = {16, 8};
        for (int octN : octaveCounts) {
            for (int s = 0; s < 16; s++) {
                long seed = 0x3000 + s * 104729L;
                NoiseGeneratorOctaves oracle = new NoiseGeneratorOctaves(new Random(seed), octN);
                long bh = WNoiseInterop.batchCreate(seed, octN);
                for (int n : new int[]{1, 3, 4, 7, 8, 16}) {
                    int[] xo = new int[16], zo = new int[16];
                    java.util.Random r = new java.util.Random(seed ^ n);
                    for (int c = 0; c < 16; c++) {
                        xo[c] = r.nextInt(800_000) - 400_000;
                        zo[c] = r.nextInt(800_000) - 400_000;
                    }
                    IntBuffer xib = intBuf(xo), zib = intBuf(zo);
                    int len = 5 * 33 * 5;
                    DoubleBuffer ob = direct(n * len);
                    WNoiseInterop.batchGen3d(bh, n, address(xib), address(zib),
                            0, 5, 33, 5, 684.412, 684.412, 684.412, address(ob));
                    for (int c = 0; c < n; c++) {
                        double[] ref = oracle.func_76304_a(null, xo[c], 0, zo[c], 5, 33, 5,
                                684.412, 684.412, 684.412);
                        fields++;
                        samples += len;
                        for (int i = 0; i < len; i++) {
                            if (Double.doubleToLongBits(ref[i]) != Double.doubleToLongBits(ob.get(c * len + i))) {
                                mismatches++;
                                if (mismatches <= 3) out.printf(
                                        "MISMATCH oct=%d seed=%d n=%d c=%d i=%d java=%s rust=%s%n",
                                        octN, seed, n, c, i, Double.toHexString(ref[i]), Double.toHexString(ob.get(c * len + i)));
                                break;
                            }
                        }
                    }
                }
                WNoiseInterop.batchFreeRaw(bh);
            }
        }
        out.println("== M3W2 batched differential (SIMD vs REAL classes) ==");
        out.println("fields=" + fields + " samples=" + samples + " mismatches=" + mismatches);
        out.println(mismatches == 0 ? "BATCH PARITY: BIT-EXACT" : "BATCH PARITY: FAILED");

        // ---------- BENCHMARK (only if parity clean) ----------
        if (mismatches == 0) {
            int warm = 2000, reps = 8000;
            long rSeed = 777L;
            NoiseGeneratorOctaves mainO = new NoiseGeneratorOctaves(new Random(rSeed), 8);
            NoiseGeneratorOctaves minO = new NoiseGeneratorOctaves(new Random(rSeed + 1), 16);
            NoiseGeneratorOctaves maxO = new NoiseGeneratorOctaves(new Random(rSeed + 2), 16);
            long pMain = WNoiseInterop.create(rSeed, 8);
            long pMin = WNoiseInterop.create(rSeed + 1, 16);
            long pMax = WNoiseInterop.create(rSeed + 2, 16);
            long bMain = WNoiseInterop.batchCreate(rSeed, 8);
            long bMin = WNoiseInterop.batchCreate(rSeed + 1, 16);
            long bMax = WNoiseInterop.batchCreate(rSeed + 2, 16);
            double cx = 684.412, cy = 684.412;
            int[] bx = new int[16], bz = new int[16];
            for (int i = 0; i < 16; i++) { bx[i] = i * 4096; bz[i] = -i * 4096; }
            IntBuffer xib = intBuf(bx), zib = intBuf(bz);
            final long xibA, zibA;
            try { xibA = address(xib); zibA = address(zib); } catch (Exception e) { throw new RuntimeException(e); }
            int len = 5 * 33 * 5;
            DoubleBuffer slab8 = direct(16 * len);
            DoubleBuffer one = direct(len);
            long slabA = address(slab8), oneA = address(one);

            for (int n : new int[]{1, 4, 8, 16}) {
                bench(out, "java_ref_n" + n, warm, reps / Math.max(1, n / 2), () -> {
                    for (int c = 0; c < n; c++) {
                        mainO.func_76304_a(null, bx[c], 0, bz[c], 5, 33, 5, cx / 80, cy / 160, cx / 80);
                        minO.func_76304_a(null, bx[c], 0, bz[c], 5, 33, 5, cx, cy, cx);
                        maxO.func_76304_a(null, bx[c], 0, bz[c], 5, 33, 5, cx, cy, cx);
                    }
                });
                bench(out, "rust_parity_n" + n, warm, reps / Math.max(1, n / 2), () -> {
                    for (int c = 0; c < n; c++) {
                        WNoiseInterop.gen3d(pMain, bx[c], 0, bz[c], 5, 33, 5, cx / 80, cy / 160, cx / 80, oneA, len);
                        WNoiseInterop.gen3d(pMin, bx[c], 0, bz[c], 5, 33, 5, cx, cy, cx, oneA, len);
                        WNoiseInterop.gen3d(pMax, bx[c], 0, bz[c], 5, 33, 5, cx, cy, cx, oneA, len);
                    }
                });
                bench(out, "rust_batched_n" + n, warm, reps / Math.max(1, n / 2), () -> {
                    WNoiseInterop.batchGen3d(bMain, n, xibA, zibA, 0, 5, 33, 5, cx / 80, cy / 160, cx / 80, slabA);
                    WNoiseInterop.batchGen3d(bMin, n, xibA, zibA, 0, 5, 33, 5, cx, cy, cx, slabA);
                    WNoiseInterop.batchGen3d(bMax, n, xibA, zibA, 0, 5, 33, 5, cx, cy, cx, slabA);
                });
                out.println("-- per-chunk at n=" + n + " (divide medians above by " + n + ")");
            }
        }
        out.flush();
        if (mismatches != 0) System.exit(3);
    }

    static void bench(PrintStream out, String name, int warm, int reps, Runnable r) {
        for (int i = 0; i < warm; i++) r.run();
        long[] t = new long[reps];
        for (int i = 0; i < reps; i++) {
            long s0 = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - s0;
        }
        java.util.Arrays.sort(t);
        out.printf("%s median=%.0fns p95=%.0fns p99=%.0fns%n", name,
                (double) t[t.length / 2], (double) t[(int) (t.length * 0.95)], (double) t[(int) (t.length * 0.99)]);
    }

    static DoubleBuffer direct(int n) {
        return ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    static IntBuffer intBuf(int[] a) {
        IntBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        b.put(a);
        b.flip();
        return b;
    }

    static long address(java.nio.Buffer b) throws Exception {
        java.lang.reflect.Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }
}
