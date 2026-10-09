package com.rustcraft.worldgen;

import com.rustcraft.interop.WNoiseInterop;
import net.minecraft.world.gen.NoiseGeneratorOctaves;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

/** M3W benchmark: one CHUNK's complete noise-field generation (the exact
 *  initNoiseField shape set: main/min/max 5x33x5 + depth 5x5), Java reference
 *  vs Rust through the one-call JNI boundary (transfer included). */
public final class KernelBench {
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M3W-kernel-bench.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
        System.loadLibrary("rustcraft_ffi");

        int warm = 3000, n = 20000;
        // three 3D fields (5x33x5) + one 2D (5x5): one chunk of initNoiseField
        NoiseGeneratorOctaves mainO = new NoiseGeneratorOctaves(new Random(1L), 8);
        NoiseGeneratorOctaves minO = new NoiseGeneratorOctaves(new Random(2L), 16);
        NoiseGeneratorOctaves maxO = new NoiseGeneratorOctaves(new Random(3L), 16);
        NoiseGeneratorOctaves depthO = new NoiseGeneratorOctaves(new Random(4L), 16);
        long rMain = WNoiseInterop.create(1L, 8);
        long rMin = WNoiseInterop.create(2L, 16);
        long rMax = WNoiseInterop.create(3L, 16);
        long rDepth = WNoiseInterop.create(4L, 16);
        DoubleBuffer b3d = direct(825), b2d = direct(25);
        long a3d = address(b3d), a2d = address(b2d);
        double cx = 684.412, cy = 684.412;
        double mnx = (float) cx / 80.0f, mny = (float) cy / 160.0f, mnz = (float) cx / 80.0f;

        Runnable javaPath = () -> {
            double[][] keep = new double[4][];
            for (int i = 0; i < 1; i++) {
                int x = i * 4, z = i * 4;
                keep[0] = depthO.func_76305_a(null, x, z, 5, 5, 200.0, 200.0, 200.0);
                keep[1] = mainO.func_76304_a(null, x, 0, z, 5, 33, 5, mnx, mny, mnz);
                keep[2] = minO.func_76304_a(null, x, 0, z, 5, 33, 5, cx, cy, cx);
                keep[3] = maxO.func_76304_a(null, x, 0, z, 5, 33, 5, cx, cy, cx);
            }
        };
        Runnable rustPath = () -> {
            for (int i = 0; i < 1; i++) {
                int x = i * 4, z = i * 4;
                WNoiseInterop.gen2d(rDepth, x, z, 5, 5, 200.0, 200.0, 200.0, a2d, 25);
                WNoiseInterop.gen3d(rMain, x, 0, z, 5, 33, 5, mnx, mny, mnz, a3d, 825);
                WNoiseInterop.gen3d(rMin, x, 0, z, 5, 33, 5, cx, cy, cx, a3d, 825);
                WNoiseInterop.gen3d(rMax, x, 0, z, 5, 33, 5, cx, cy, cx, a3d, 825);
            }
        };
        bench(out, "java_chunk", javaPath, warm, n);
        bench(out, "rust_chunk_jni", rustPath, warm, n);
        // single-field variants for granularity
        bench(out, "java_main_8oct", () -> { mainO.func_76304_a(null, 100, 0, 100, 5, 33, 5, mnx, mny, mnz); }, warm, n);
        bench(out, "rust_main_8oct", () -> WNoiseInterop.gen3d(rMain, 100, 0, 100, 5, 33, 5, mnx, mny, mnz, a3d, 825), warm, n);
        bench(out, "java_min_16oct", () -> { minO.func_76304_a(null, 100, 0, 100, 5, 33, 5, cx, cy, cx); }, warm, n);
        bench(out, "rust_min_16oct", () -> WNoiseInterop.gen3d(rMin, 100, 0, 100, 5, 33, 5, cx, cy, cx, a3d, 825), warm, n);
        out.flush();
    }

    static void bench(PrintStream out, String name, Runnable r, int warm, int n) {
        for (int i = 0; i < warm; i++) r.run();
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - s;
        }
        long[] sorted = t.clone();
        Arrays.sort(sorted);
        out.printf("%s median=%.0fns p95=%.0fns p99=%.0fns qps=%.0f%n", name,
                (double) sorted[n / 2], (double) sorted[(int) (n * 0.95)], (double) sorted[(int) (n * 0.99)],
                1e9 / sorted[n / 2]);
    }

    static DoubleBuffer direct(int n) {
        return ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    static long address(java.nio.Buffer b) throws Exception {
        java.lang.reflect.Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }
}
