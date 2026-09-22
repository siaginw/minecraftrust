package com.rustcraft.worldgen;

import com.rustcraft.interop.WNoiseInterop;
import net.minecraft.world.gen.NoiseGeneratorOctaves;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * M3-WORLDGEN-NOISE kernel differential harness.
 *
 * ORACLE = the REAL installed NoiseGeneratorOctaves (actual server jar).
 * CANDIDATE = Rust port via one JNI call per field.
 * Comparison is BIT-EXACT (Double.doubleToLongBits).
 *
 * Corpus: seeds x shapes x offsets, including the exact 5x33x5 / 5x5 shapes
 * and the real scale values initNoiseField uses (from ChunkGeneratorSettings
 * defaults), plus negative/far coordinates and repeated-call state reuse.
 */
public final class KernelParity {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M3W-kernel-parity.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
        System.loadLibrary("rustcraft_ffi");

        long mismatches = 0, fields = 0, samples = 0;
        long[] firstDiffs = new long[5];
        java.util.ArrayList<String> diffDesc = new java.util.ArrayList<>();

        // deterministic corpus: 64 seeds x shapes
        long[] seeds = new long[64];
        for (int i = 0; i < seeds.length; i++) seeds[i] = 0x1000 + i * 7919L;
        int[][] shapes3d = {
                {5, 33, 5},      // initNoiseField main/min/max
                {5, 1, 5},       // y==1 takes the 3D method's 2D path via wrapper? no: 2D wrapper
                {10, 8, 10},
                {1, 4, 1},
                {4, 17, 4},
        };
        // real scale sets from ChunkGeneratorSettings defaults (vanilla):
        // coordinateScale=684.412, heightScale=684.412, mainNoiseScaleXYZ=80/160/80
        double[][] scales = {
                {684.412, 684.412, 684.412},
                {684.412 / 80.0, 684.412 / 160.0, 684.412 / 80.0},
                {684.412 / 80.0, 684.412 / 160.0, 684.412 / 80.0},
                {684.412, 684.412, 684.412},
        };
        int[][] offsets = {
                {0, 0, 0}, {100, 0, 100}, {-1000, 0, -1000},
                {1234567, 0, -7654321}, {16, 32, 16}, {-30_000_000, 0, 30_000_000},
        };
        int[] octaveCounts = {16, 8, 10};

        for (int octN : octaveCounts) {
            for (long seed : seeds) {
                // ORACLE: real classes, real java.util.Random
                NoiseGeneratorOctaves oracle = new NoiseGeneratorOctaves(new Random(seed), octN);
                long rust = WNoiseInterop.create(seed, octN);
                for (int s = 0; s < scales.length; s++) {
                    for (int[] shape : shapes3d) {
                        for (int oi = 0; oi < offsets.length; oi++) {
                            if ((oi + s) % 3 != 0) continue; // bound corpus size
                            int[] off = offsets[oi];
                            double[] a = oracle.func_76304_a(null,
                                    off[0], off[1], off[2],
                                    shape[0], shape[1], shape[2],
                                    scales[s][0], scales[s][1], scales[s][2]);
                            DoubleBuffer rb = direct(a.length);
                            WNoiseInterop.gen3d(rust, off[0], off[1], off[2],
                                    shape[0], shape[1], shape[2],
                                    scales[s][0], scales[s][1], scales[s][2],
                                    address(rb), a.length);
                            fields++;
                            samples += a.length;
                            for (int i = 0; i < a.length; i++) {
                                if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(rb.get(i))) {
                                    mismatches++;
                                    if (diffDesc.size() < 5) diffDesc.add(String.format(
                                            "oct=%d seed=%d shape=%dx%dx%d off=%d,%d,%d scaleIdx=%d idx=%d java=%s rust=%s",
                                            octN, seed, shape[0], shape[1], shape[2], off[0], off[1], off[2], s, i,
                                            Double.toHexString(a[i]), Double.toHexString(rb.get(i))));
                                    break;
                                }
                            }
                        }
                    }
                    // 2D wrapper fields (real 5x5 depth-noise shape + quirk arg)
                    if (octN == 16) {
                        double[] a2 = oracle.func_76305_a(null, 12345, -6789, 5, 5,
                                200.0, 999.0 /*zScale*/, 200.0 /*3rd double dropped by vanilla*/);
                        DoubleBuffer rb2 = direct(a2.length);
                        WNoiseInterop.gen2d(rust, 12345, -6789, 5, 5, 200.0, 999.0, 200.0,
                                address(rb2), a2.length);
                        fields++;
                        samples += a2.length;
                        for (int i = 0; i < a2.length; i++) {
                            if (Double.doubleToLongBits(a2[i]) != Double.doubleToLongBits(rb2.get(i))) {
                                mismatches++;
                                if (diffDesc.size() < 5) diffDesc.add("2D idx=" + i + " java=" + a2[i] + " rust=" + rb2.get(i));
                                break;
                            }
                        }
                    }
                }
                WNoiseInterop.freeRaw(rust);
            }
        }
        out.println("== M3W kernel differential parity ==");
        out.println("fields=" + fields + " samples=" + samples + " mismatches=" + mismatches);
        for (String d : diffDesc) out.println("DIFF " + d);
        out.println(mismatches == 0 ? "KERNEL PARITY: BIT-EXACT" : "KERNEL PARITY: FAILED");
        out.flush();
        if (mismatches != 0) System.exit(3);
    }

    static DoubleBuffer direct(int n) {
        return ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    }

    static long address(java.nio.Buffer b) {
        try {
            java.lang.reflect.Method m = b.getClass().getMethod("address");
            m.setAccessible(true);
            return (Long) m.invoke(b);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
