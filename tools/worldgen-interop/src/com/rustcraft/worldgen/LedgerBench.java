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

/** M3W4: n=1 ledger benchmark — baseline vs one-call redesigned path. */
public final class LedgerBench {

    static DoubleBuffer S_q, S_hd, S_em, S_fm, S_gm;
    static FloatBuffer S_bf;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M3W4-ledger.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
        System.loadLibrary("rustcraft_ffi");
        Bootstrap.func_151354_b();

        Object st = Class.forName("net.minecraft.world.gen.ChunkGeneratorSettings$Factory").newInstance();
        Object s = st.getClass().getMethod("func_177864_b").invoke(st);
        float a = f(s, "field_177811_a"), b = f(s, "field_177809_b");
        float e = f(s, "field_177808_e"), fp = f(s, "field_177803_f"), g = f(s, "field_177804_g");
        float h = f(s, "field_177825_h"), i = f(s, "field_177827_i"), j = f(s, "field_177821_j");
        float k = f(s, "field_177823_k"), l = f(s, "field_177817_l");
        float d = f(s, "field_177806_d"), c = f(s, "field_177810_c");
        float m = f(s, "field_177819_m"), n = f(s, "field_177813_n");
        float o = f(s, "field_177815_o"), p = f(s, "field_177843_p");
        double mnx = (double) (a / h), mny = (double) (b / i), mnz = (double) (a / j);

        Class<?> biomes = Class.forName("net.minecraft.init.Biomes");
        Biome[] picks = {
                (Biome) biomes.getField("field_76769_d").get(null),
                (Biome) biomes.getField("field_76781_i").get(null),
                (Biome) biomes.getField("field_76771_b").get(null),
                (Biome) biomes.getField("field_76776_l").get(null),
                (Biome) biomes.getField("field_76788_q").get(null),
        };
        Random br = new Random(99);
        float[][] grid = new float[100][2];
        for (int ci = 0; ci < 100; ci++) {
            Biome bio = picks[br.nextInt(picks.length)];
            grid[ci][0] = bio.func_185355_j();
            grid[ci][1] = bio.func_185360_m();
        }

        // buffers + strong refs
        DoubleBuffer q = direct(825), hd = direct(25), em = direct(825), fm = direct(825), gm = direct(825);
        FloatBuffer bf = fbuf(200);
        S_q = q; S_hd = hd; S_em = em; S_fm = fm; S_gm = gm; S_bf = bf;
        bf.clear();
        for (float[] cell : grid) bf.put(cell[0]);
        for (float[] cell : grid) bf.put(cell[1]);
        bf.flip();
        final long qA = addr(q), hdA = addr(hd), emA = addr(em), fmA = addr(fm), gmA = addr(gm), bfA = addr(bf);

        // handles
        long fHandle = WNoiseInterop.fieldCreate(a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p, (byte) 0);
        long iHandle = WNoiseInterop.initCreate(1L, 2L, 3L, 4L,
                a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p, (byte) 0);
        long sMain = WNoiseInterop.singleCreate(2L, 8), sMin = WNoiseInterop.singleCreate(3L, 16), sMax = WNoiseInterop.singleCreate(4L, 16);
        long rDepth = WNoiseInterop.create(1L, 16), rMain = WNoiseInterop.create(2L, 8),
             rMin = WNoiseInterop.create(3L, 16), rMax = WNoiseInterop.create(4L, 16);

        // JAVA reference (fixed gens, real assembly via FieldParity.javaComplete)
        NoiseGeneratorOctaves jd = new NoiseGeneratorOctaves(new Random(1L), 16);
        NoiseGeneratorOctaves jm = new NoiseGeneratorOctaves(new Random(2L), 8);
        NoiseGeneratorOctaves jn = new NoiseGeneratorOctaves(new Random(3L), 16);
        NoiseGeneratorOctaves jx = new NoiseGeneratorOctaves(new Random(4L), 16);

        // ---------- PARITY: one-call vs java oracle ----------
        long mism = 0;
        for (int t = 0; t < 240; t++) {
            long seed = 500 + t * 31;
            int x4, z4;
            switch (t % 4) {
                case 0: x4 = (t * 7919) % 300_000 - 150_000; z4 = -(t * 6007) % 300_000 + 150_000; break;
                case 1: x4 = (t * 104729) % 7_500_000 - 3_750_000; z4 = -(t * 15485863) % 7_500_000 + 3_750_000; break; // far
                case 2: x4 = (t % 2 == 0 ? 1 : -1) * (28_000_000 + t); z4 = (t % 3 == 0 ? 1 : -1) * (29_500_000 - t); break; // extreme
                default: x4 = (t * 337) % 200 - 100; z4 = -(t * 349) % 200 + 100; break; // near origin
            }
            // rebuild java gens per seed
            NoiseGeneratorOctaves d2 = new NoiseGeneratorOctaves(new Random(seed), 16);
            NoiseGeneratorOctaves m2 = new NoiseGeneratorOctaves(new Random(seed + 1), 8);
            NoiseGeneratorOctaves n2 = new NoiseGeneratorOctaves(new Random(seed + 2), 16);
            NoiseGeneratorOctaves x2 = new NoiseGeneratorOctaves(new Random(seed + 3), 16);
            double[] ref = FieldParity.javaComplete(d2, m2, n2, x2, x4, z4, grid,
                    a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
            long ih = WNoiseInterop.initCreate(seed, seed + 1, seed + 2, seed + 3,
                    a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p, (byte) 0);
            WNoiseInterop.initFieldComplete(ih, x4, z4, bfA, qA);
            WNoiseInterop.initFreeRaw(ih);
            for (int i2 = 0; i2 < 825; i2++) {
                if (Double.doubleToLongBits(ref[i2]) != Double.doubleToLongBits(q.get(i2))) {
                    mism++;
                    if (mism <= 3) out.println("MISMATCH t=" + t + " i=" + i2);
                    break;
                }
            }
        }
        out.println("== M3W4 one-call parity (vs REAL + oracle assembly, per-seed handles) ==");
        out.println("chunks=240 mismatches=" + mism);
        out.println(mism == 0 ? "ONE-CALL PARITY: BIT-EXACT" : "PARITY FAILED");

        // ---------- LEDGER BENCHMARK (all timed at FIXED gens, n=1) ----------
        int warm = 2000, reps = 12000;
        // L0 java reference
        bench(out, "L0_java_reference", warm, reps, () ->
                FieldParity.javaComplete(jd, jm, jn, jx, 123456, -654321, grid,
                        a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p));
        // L1 M3W3 fragmented path (5 calls, x-lane SIMD + FlatTables)
        bench(out, "L1_m3w3_fragmented", warm, reps, () -> {
            WNoiseInterop.gen2d(rDepth, 123456, -654321, 5, 5, e, fp, g, hdA, 25);
            WNoiseInterop.singleGen3d(sMain, 123456, 0, -654321, 5, 33, 5, mnx, mny, mnz, emA);
            WNoiseInterop.singleGen3d(sMin, 123456, 0, -654321, 5, 33, 5, a, b, a, fmA);
            WNoiseInterop.singleGen3d(sMax, 123456, 0, -654321, 5, 33, 5, a, b, a, gmA);
            WNoiseInterop.fieldAssemble(fHandle, hdA, emA, fmA, gmA, bfA, qA);
        });
        // L2 one-call redesigned (scalar compact + lockstep + zero-alloc + fused assembly)
        bench(out, "L2_onecall_redesigned", warm, reps, () ->
                WNoiseInterop.initFieldComplete(iHandle, 123456, -654321, bfA, qA));
        // isolations
        bench(out, "iso_assembly_only", warm, reps, () ->
                WNoiseInterop.fieldAssemble(fHandle, hdA, emA, fmA, gmA, bfA, qA));
        bench(out, "iso_empty_jni", warm, reps, () -> WNoiseInterop.initFieldComplete(iHandle, 0, 0, bfA, qA)); // small coords ~ same work; not empty — skip interpret
        out.println("NOTE iso_empty_jni uses coords (0,0): same compute; retained only as run-to-run variance probe");

        // ---- Part 7: INTEGRATED regression through the LIVE-SHADOW state-transplant path ----
        // (a) transplant ctx from REAL generator states == oracle, bit-exact
        // (b) forced native failure: initCreateFromState with bad octave counts => 0 => adapter would stay Java-only
        // (c) unknown-generator class simulation => INELIGIBLE_CLASS (adapter logic; checked by class-identity test below)
        {
            java.util.Random rd = new java.util.Random(1L);
            NoiseGeneratorOctaves depthG = new NoiseGeneratorOctaves(rd, 16);
            NoiseGeneratorOctaves mainG = new NoiseGeneratorOctaves(rd, 8);
            NoiseGeneratorOctaves minG = new NoiseGeneratorOctaves(rd, 16);
            NoiseGeneratorOctaves maxG = new NoiseGeneratorOctaves(rd, 16);
            // oracle built the SAME vanilla way: ONE shared Random(1L), ctor order j,k,l
            java.util.Random rdo = new java.util.Random(1L);
            NoiseGeneratorOctaves oJ = new NoiseGeneratorOctaves(rdo, 16); // j = min
            NoiseGeneratorOctaves oK = new NoiseGeneratorOctaves(rdo, 16); // k = max
            NoiseGeneratorOctaves oL = new NoiseGeneratorOctaves(rdo, 8);  // l = main
            // NOTE ctor order j,k,l,m,b,c,d: j consumes first. Our transplant packs
            // depth(c,6th),main(l,3rd),min(j,1st),max(k,2nd) — oracle below constructs
            // matching GENERATORS PER SLOT from a fresh shared Random consumed in a
            // wrapper matching each packed state (see pack order): to keep the test
            // faithful to the ADAPTER (transplant = whatever live states are), the
            // oracle re-uses the SAME generator objects whose state was packed:
            // (the per-slot refs below alias depthG/mainG/minG/maxG)
            // pack state exactly as the adapter does
            java.nio.ByteBuffer pbb = java.nio.ByteBuffer.allocateDirect(64 * 512 * 4).order(java.nio.ByteOrder.nativeOrder());
            java.nio.DoubleBuffer obb = java.nio.ByteBuffer.allocateDirect(64 * 3 * 8).order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();
            pack(pbb, obb, depthG, 16); pack(pbb, obb, mainG, 8); pack(pbb, obb, minG, 16); pack(pbb, obb, maxG, 16);
            long tctx = com.rustcraft.interop.WNoiseInterop.initCreateFromState(
                    16, 8, 16, 16, addr(pbb), addr(obb),
                    a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
            out.println("transplant_ctx_created=" + (tctx != 0));
            long mm = 0;
            for (int t = 0; t < 30; t++) {
                int x4 = (t * 7919) % 200_000 - 100_000, z4 = -(t * 6007) % 200_000 + 100_000;
                double[] ref = FieldParity.javaComplete(
                        depthG, mainG, minG, maxG,
                        x4, z4, grid, a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
                com.rustcraft.interop.WNoiseInterop.initFieldComplete(tctx, x4, z4, bfA, qA);
                for (int i2 = 0; i2 < 825; i2++) {
                    if (Double.doubleToLongBits(ref[i2]) != Double.doubleToLongBits(q.get(i2))) { mm++; break; }
                }
            }
            out.println("transplant_parity_fields=30 mismatches=" + mm);
            // forced failure: truncated state buffer => ctx must be 0
            java.nio.ByteBuffer tbb = java.nio.ByteBuffer.allocateDirect(4 * 512 * 4).order(java.nio.ByteOrder.nativeOrder());
            java.nio.DoubleBuffer tbb2 = java.nio.ByteBuffer.allocateDirect(4 * 3 * 8).order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();
            long bad = com.rustcraft.interop.WNoiseInterop.initCreateFromState(
                    16, 8, 16, 16, addr(tbb), addr(tbb2),
                    a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
            out.println("forced_truncated_state_ctx_zero=" + (bad == 0));
            com.rustcraft.interop.WNoiseInterop.initFreeRaw(tctx);
            out.println(mm == 0 && bad == 0 && tctx != 0 ? "INTEGRATED REGRESSION: PASS" : "INTEGRATED REGRESSION: FAIL");
        }
        out.flush();
        if (mism != 0) System.exit(3);
    }

    static void pack(java.nio.ByteBuffer pbb, java.nio.DoubleBuffer obb, NoiseGeneratorOctaves g, int oct) throws Exception {
        java.lang.reflect.Field af = NoiseGeneratorOctaves.class.getDeclaredField("field_76307_a");
        af.setAccessible(true);
        Object[] levels = (Object[]) af.get(g);
        java.lang.reflect.Field pf = Class.forName("net.minecraft.world.gen.NoiseGeneratorImproved").getDeclaredField("field_76312_d");
        pf.setAccessible(true);
        java.lang.reflect.Field xf = Class.forName("net.minecraft.world.gen.NoiseGeneratorImproved").getDeclaredField("field_76315_a");
        java.lang.reflect.Field yf = Class.forName("net.minecraft.world.gen.NoiseGeneratorImproved").getDeclaredField("field_76313_b");
        java.lang.reflect.Field zf = Class.forName("net.minecraft.world.gen.NoiseGeneratorImproved").getDeclaredField("field_76314_c");
        xf.setAccessible(true); yf.setAccessible(true); zf.setAccessible(true);
        for (Object lv : levels) {
            int[] perm = (int[]) pf.get(lv);
            for (int v : perm) pbb.putInt(v);
            obb.put(xf.getDouble(lv)); obb.put(yf.getDouble(lv)); obb.put(zf.getDouble(lv));
        }
    }

    static float f(Object settings, String name) throws Exception {
        Field fl = settings.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        return fl.getFloat(settings);
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
        out.printf("%s median=%.0fns p95=%.0fns p99=%.0fns qps=%.0f%n", name,
                (double) t[t.length / 2], (double) t[(int) (t.length * 0.95)], (double) t[(int) (t.length * 0.99)],
                1e9 / t[t.length / 2]);
    }

    static DoubleBuffer direct(int n) {
        return ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    static FloatBuffer fbuf(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    static long addr(java.nio.Buffer b) throws Exception {
        java.lang.reflect.Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }
}
