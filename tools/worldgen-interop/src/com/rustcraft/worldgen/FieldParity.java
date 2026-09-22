package com.rustcraft.worldgen;

import com.rustcraft.interop.WNoiseInterop;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.NoiseGeneratorOctaves;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * M3W3: COMPLETE initNoiseField differential + 3-tier benchmark.
 *
 * JAVA_ORACLE = REAL NoiseGeneratorOctaves + an exact transcription of the
 * decompiled initNoiseField assembly (committed noise-src) with REAL biome
 * float values from the REAL Biome instances. RUST = same pipeline through
 * the batched/x-lane kernels + native assembly. Bit-exact required.
 */
public final class FieldParity {
    // strong references keeping direct-buffer native memory alive across GC
    // (the benchmark lambdas capture only hoisted long addresses)
    static DoubleBuffer S_hd, S_em, S_fm, S_gm, S_qb, S_scratch, S_slab;
    static FloatBuffer S_bf;

    public static double[] javaComplete(NoiseGeneratorOctaves depth, NoiseGeneratorOctaves main,
                                 NoiseGeneratorOctaves min, NoiseGeneratorOctaves max,
                                 int x4, int z4, float[][] biomeGrid /*[100][2]*/,
                                 float a, float b, float e, float f_, float g,
                                 float h, float i, float j, float k, float l,
                                 float d, float c, float m, float n, float o, float p) {
        double[] hd = depth.func_76305_a(null, x4, z4, 5, 5, e, f_, g);
        double[] em = main.func_76304_a(null, x4, 0, z4, 5, 33, 5,
                (double) (a / h), (double) (b / i), (double) (a / j));
        double[] fm = min.func_76304_a(null, x4, 0, z4, 5, 33, 5, (double) a, (double) b, (double) a);
        double[] gm = max.func_76304_a(null, x4, 0, z4, 5, 33, 5, (double) a, (double) b, (double) a);
        // biome weights
        float[] w = new float[25];
        for (int ii = -2; ii <= 2; ii++)
            for (int jj = -2; jj <= 2; jj++)
                w[ii + 2 + (jj + 2) * 5] = 10.0F / net.minecraft.util.math.MathHelper.func_76129_c(ii * ii + jj * jj + 0.2F);
        double[] q = new double[825];
        int depthIdx = 0, idx = 0;
        for (int ix = 0; ix < 5; ix++) {
            for (int iz = 0; iz < 5; iz++) {
                float variation = 0F, depthS = 0F, wsum = 0F;
                int center = ix + 2 + (iz + 2) * 10;
                for (int di = -2; di <= 2; di++) {
                    for (int dj = -2; dj <= 2; dj++) {
                        int cell = ix + di + 2 + (iz + dj + 2) * 10;
                        float f7 = n + biomeGrid[cell][0] * m;
                        float f8 = p + biomeGrid[cell][1] * o;
                        float f9 = w[di + 2 + (dj + 2) * 5] / (f7 + 2.0F);
                        if (biomeGrid[cell][0] > biomeGrid[center][0]) f9 /= 2.0F;
                        variation += f8 * f9;
                        depthS += f7 * f9;
                        wsum += f9;
                    }
                }
                variation /= wsum;
                depthS /= wsum;
                variation = variation * 0.9F + 0.1F;
                depthS = (depthS * 4.0F - 1.0F) / 8.0F;
                double d0 = hd[depthIdx] / 8000.0;
                if (d0 < 0.0) d0 = -d0 * 0.3;
                d0 = d0 * 3.0 - 2.0;
                if (d0 < 0.0) {
                    d0 /= 2.0;
                    if (d0 < -1.0) d0 = -1.0;
                    d0 /= 1.4;
                    d0 /= 2.0;
                } else {
                    if (d0 > 1.0) d0 = 1.0;
                    d0 /= 8.0;
                }
                depthIdx++;
                double d1 = depthS + d0 * 0.2;
                d1 = d1 * (double) k / 8.0;
                double d3 = (double) k + d1 * 4.0;
                double d2 = variation;
                for (int y = 0; y < 33; y++) {
                    double d4 = ((double) y - d3) * (double) l * 128.0 / 256.0 / d2;
                    if (d4 < 0.0) d4 *= 4.0;
                    double d5 = fm[idx] / (double) d;
                    double d6 = gm[idx] / (double) c;
                    double d7 = (em[idx] / 10.0 + 1.0) / 2.0;
                    double dens = net.minecraft.util.math.MathHelper.func_151238_b(d5, d6, d7) - d4;
                    if (y > 29) {
                        double d8 = (double) ((float) (y - 29) / 3.0F);
                        dens = dens * (1.0 - d8) + -10.0 * d8;
                    }
                    q[idx++] = dens;
                }
            }
        }
        return q;
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M3W3-field.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
        System.loadLibrary("rustcraft_ffi");
        Bootstrap.func_151354_b();

        // REAL settings from the REAL ChunkGeneratorSettings defaults
        Object st = Class.forName("net.minecraft.world.gen.ChunkGeneratorSettings$Factory")
                .newInstance();
        java.lang.reflect.Method build = st.getClass().getMethod("func_177864_b");
        Object s = build.invoke(st);
        float a = f(s, "field_177811_a"), b = f(s, "field_177809_b");
        float e = f(s, "field_177808_e"), fp = f(s, "field_177803_f"), g = f(s, "field_177804_g");
        float h = f(s, "field_177825_h"), i = f(s, "field_177827_i"), j = f(s, "field_177821_j");
        float k = f(s, "field_177823_k"), l = f(s, "field_177817_l");
        float d = f(s, "field_177806_d"), c = f(s, "field_177810_c");
        float m = f(s, "field_177819_m"), n = f(s, "field_177813_n");
        float o = f(s, "field_177815_o"), p = f(s, "field_177843_p");

        // REAL biome floats: distinct vanilla biomes incl. extremes
        Class<?> biomes = Class.forName("net.minecraft.init.Biomes");
        Biome[] picks = {
                (Biome) biomes.getField("field_76769_d").get(null), // plains
                (Biome) biomes.getField("field_76781_i").get(null), // desert?
                (Biome) biomes.getField("field_76771_b").get(null), // ocean
                (Biome) biomes.getField("field_76776_l").get(null), // extreme hills?
                (Biome) biomes.getField("field_76788_q").get(null), // swampland?
        };
        out.println("settings a=" + a + " h=" + h + " k=" + k + " l=" + l + " d=" + d + " c=" + c);

        // ---- PARITY ----
        long mism = 0, chunks = 0;
        DoubleBuffer hd = direct(25), em = direct(825), fm = direct(825), gm = direct(825);
        DoubleBuffer qb = direct(825), scratch = direct(825);
        FloatBuffer bf = fbuf(200);
        long fHandle = WNoiseInterop.fieldCreate(a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p, (byte) 0);
        long sMain = WNoiseInterop.singleCreate(1L, 8), sMin = WNoiseInterop.singleCreate(2L, 16), sMax = WNoiseInterop.singleCreate(3L, 16);
        long rMain = WNoiseInterop.create(1L, 8), rMin = WNoiseInterop.create(2L, 16), rMax = WNoiseInterop.create(3L, 16), rDepth = WNoiseInterop.create(4L, 16);
        long bMain = WNoiseInterop.batchCreate(1L, 8), bMin = WNoiseInterop.batchCreate(2L, 16), bMax = WNoiseInterop.batchCreate(3L, 16);
        double mnx = (double) (a / h), mny = (double) (b / i), mnz = (double) (a / j);

        // hoisted buffer addresses (buffers are allocateDirect and stable)
        final long hdA, emA, fmA, gmA, qA, bfA, scA, slabA;
        DoubleBuffer slab16 = direct(16 * 825);
        try {
            hdA = addr(hd); emA = addr(em); fmA = addr(fm); gmA = addr(gm);
            qA = addr(qb); bfA = addr(bf); scA = addr(scratch); slabA = addr(slab16);
        } catch (Exception ex) { throw new RuntimeException(ex); }
        S_hd = hd; S_em = em; S_fm = fm; S_gm = gm; S_qb = qb; S_scratch = scratch; S_slab = slab16; S_bf = bf;
        Random rr = new Random(4242);
        for (int trial = 0; trial < 60; trial++) {
            long seed = 100 + trial * 7;
            NoiseGeneratorOctaves jd = new NoiseGeneratorOctaves(new Random(seed), 16);
            NoiseGeneratorOctaves jm = new NoiseGeneratorOctaves(new Random(seed + 1), 8);
            NoiseGeneratorOctaves jn = new NoiseGeneratorOctaves(new Random(seed + 2), 16);
            NoiseGeneratorOctaves jx = new NoiseGeneratorOctaves(new Random(seed + 3), 16);
            int x4 = rr.nextInt(200_000) - 100_000, z4 = rr.nextInt(200_000) - 100_000;
            float[][] grid = new float[100][2];
            for (int cI = 0; cI < 100; cI++) {
                Biome bio = picks[rr.nextInt(picks.length)];
                grid[cI][0] = bio.func_185355_j();
                grid[cI][1] = bio.func_185360_m();
            }
            double[] ref = javaComplete(jd, jm, jn, jx, x4, z4, grid, a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
            chunks++;
            // rust: parity tier (scalar fields + native assembly)
            long pD = WNoiseInterop.create(seed, 16), pM = WNoiseInterop.create(seed + 1, 8),
                 pN = WNoiseInterop.create(seed + 2, 16), pX = WNoiseInterop.create(seed + 3, 16);
            WNoiseInterop.gen2d(pD, x4, z4, 5, 5, e, fp, g, hdA, 25); // zScale=f_ (vanilla 2D-drop quirk: D3 dropped)
            WNoiseInterop.gen3d(pM, x4, 0, z4, 5, 33, 5, mnx, mny, mnz, emA, 825);
            WNoiseInterop.gen3d(pN, x4, 0, z4, 5, 33, 5, a, b, a, fmA, 825);
            WNoiseInterop.gen3d(pX, x4, 0, z4, 5, 33, 5, a, b, a, gmA, 825);
            fillBiomeBuf(bf, grid);
            WNoiseInterop.fieldAssemble(fHandle, hdA, emA, fmA, gmA, bfA, qA);
            WNoiseInterop.freeRaw(pD); WNoiseInterop.freeRaw(pM); WNoiseInterop.freeRaw(pN); WNoiseInterop.freeRaw(pX);
            for (int i2 = 0; i2 < 825; i2++) {
                if (Double.doubleToLongBits(ref[i2]) != Double.doubleToLongBits(qb.get(i2))) {
                    mism++;
                    if (mism <= 3) out.println("MISMATCH trial=" + trial + " i=" + i2
                            + " java=" + Double.toHexString(ref[i2]) + " rust=" + Double.toHexString(qb.get(i2)));
                    break;
                }
            }
        }
        out.println("== M3W3 complete-field differential (scalar tier) ==");
        out.println("chunks=" + chunks + " mismatches=" + mism);
        out.println(mism == 0 ? "COMPLETE-FIELD PARITY (scalar): BIT-EXACT" : "PARITY FAILED");

        // x-lane tier parity (n=1 optimized): reuse fixed generators vs fresh java
        if (mism == 0) {
            long m2 = 0, n2 = 0;
            for (int trial = 0; trial < 40; trial++) {
                long seed = 900 + trial * 13;
                NoiseGeneratorOctaves jm = new NoiseGeneratorOctaves(new Random(seed), 8);
                int x4 = (int) (rr.nextLong() % 100_000), z4 = (int) (rr.nextLong() % -100_000);
                double[] ref = jm.func_76304_a(null, x4, 0, z4, 5, 33, 5, mnx, mny, mnz);
                long sg = WNoiseInterop.singleCreate(seed, 8);
                WNoiseInterop.singleGen3d(sg, x4, 0, z4, 5, 33, 5, mnx, mny, mnz, scA);
                WNoiseInterop.singleFreeRaw(sg);
                n2++;
                for (int i2 = 0; i2 < 825; i2++) {
                    if (Double.doubleToLongBits(ref[i2]) != Double.doubleToLongBits(scratch.get(i2))) {
                        m2++;
                        if (m2 <= 3) out.println("XLANE MISMATCH trial=" + trial + " i=" + i2);
                        break;
                    }
                }
            }
            out.println("x-lane tier: fields=" + n2 + " mismatches=" + m2);
            if (m2 != 0) mism += m2;
        }

        // ---- BENCHMARK: complete initNoiseField per chunk, 3 tiers x n ----
        if (mism == 0) {
            int warm = 300, reps = 1500;
            float[][] grid = new float[100][2];
            for (int cI = 0; cI < 100; cI++) {
                Biome bio = picks[cI % picks.length];
                grid[cI][0] = bio.func_185355_j();
                grid[cI][1] = bio.func_185360_m();
            }
            fillBiomeBuf(bf, grid);
            NoiseGeneratorOctaves jd = new NoiseGeneratorOctaves(new Random(1L), 16);
            NoiseGeneratorOctaves jm = new NoiseGeneratorOctaves(new Random(2L), 8);
            NoiseGeneratorOctaves jn = new NoiseGeneratorOctaves(new Random(3L), 16);
            NoiseGeneratorOctaves jx = new NoiseGeneratorOctaves(new Random(4L), 16);
            IntHolder xi = new IntHolder();
            for (int nb : new int[]{1, 2, 4, 8, 16}) {
                bench(out, "java_complete_n" + nb, warm, reps, () -> {
                    for (int cc = 0; cc < nb; cc++) {
                        javaComplete(jd, jm, jn, jx, 4096 + cc * 4, -4096 - cc * 4, grid,
                                a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
                    }
                });
                bench(out, "rust_parity_n" + nb, warm, reps, () -> {
                    for (int cc = 0; cc < nb; cc++) {
                        int x4 = 4096 + cc * 4, z4 = -4096 - cc * 4;
                        WNoiseInterop.gen2d(rDepth, x4, z4, 5, 5, e, fp, g, hdA, 25);
                        WNoiseInterop.gen3d(rMain, x4, 0, z4, 5, 33, 5, mnx, mny, mnz, emA, 825);
                        WNoiseInterop.gen3d(rMin, x4, 0, z4, 5, 33, 5, a, b, a, fmA, 825);
                        WNoiseInterop.gen3d(rMax, x4, 0, z4, 5, 33, 5, a, b, a, gmA, 825);
                        WNoiseInterop.fieldAssemble(fHandle, hdA, emA, fmA, gmA, bfA, qA);
                    }
                });
                bench(out, "rust_optimized_n" + nb, warm, reps, () -> {
                    if (nb == 1) {
                        int x4 = 4096, z4 = -4096;
                        WNoiseInterop.gen2d(rDepth, x4, z4, 5, 5, e, fp, g, hdA, 25);
                        WNoiseInterop.singleGen3d(sMain, x4, 0, z4, 5, 33, 5, mnx, mny, mnz, emA);
                        WNoiseInterop.singleGen3d(sMin, x4, 0, z4, 5, 33, 5, a, b, a, fmA);
                        WNoiseInterop.singleGen3d(sMax, x4, 0, z4, 5, 33, 5, a, b, a, gmA);
                    } else {
                        int[] xs = new int[nb], zs = new int[nb];
                        for (int cc = 0; cc < nb; cc++) { xs[cc] = 4096 + cc * 4; zs[cc] = -4096 - cc * 4; }
                        java.nio.IntBuffer xib = ibuf(xs), zib = ibuf(zs);
                        try {
                            WNoiseInterop.batchGen3d(bMain, nb, addr(xib), addr(zib), 0, 5, 33, 5, mnx, mny, mnz, slabA);
                            WNoiseInterop.batchGen3d(bMin, nb, addr(xib), addr(zib), 0, 5, 33, 5, a, b, a, slabA);
                            WNoiseInterop.batchGen3d(bMax, nb, addr(xib), addr(zib), 0, 5, 33, 5, a, b, a, slabA);
                            for (int cc = 0; cc < nb; cc++) {
                                WNoiseInterop.gen2d(rDepth, xs[cc], zs[cc], 5, 5, e, fp, g, hdA, 25);
                            }
                        } catch (Exception ex) { throw new RuntimeException(ex); }
                    }
                    WNoiseInterop.fieldAssemble(fHandle, hdA, emA, fmA, gmA, bfA, qA);
                });
                out.println("-- complete initNoiseField per batch; divide by " + nb + " for per-chunk");
            }
        }
        out.flush();
        if (mism != 0) System.exit(3);
    }

    static float f(Object settings, String name) throws Exception {
        Field fl = settings.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        return fl.getFloat(settings);
    }

    static void fillBiomeBuf(FloatBuffer fb, float[][] grid) {
        fb.clear();
        for (float[] cell : grid) fb.put(cell[0]);
        for (float[] cell : grid) fb.put(cell[1]);
        fb.flip();
    }

    static class IntHolder { int v = 0; }

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

    static FloatBuffer fbuf(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    static java.nio.IntBuffer ibuf(int[] a) {
        java.nio.IntBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        b.put(a);
        b.flip();
        return b;
    }

    static long addr(java.nio.Buffer b) throws Exception {
        java.lang.reflect.Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }

    static long address(java.nio.Buffer b) throws Exception {
        return addr(b);
    }
}
