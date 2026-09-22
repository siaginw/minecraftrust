package com.rustcraft.worldgen;

import com.rustcraft.interop.WNoiseInterop;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorSettings;
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
 * M3W5: Base terrain placement & ChunkPrimer ownership differential oracle & benchmark.
 *
 * Compares:
 * 1. JAVA_REFERENCE: ChunkGeneratorOverworld.setBlocksInChunk (func_185976_a) decompiled byte-exact loop
 * 2. RUST_PARITY: worldgen_noise::terrain::set_blocks_in_chunk_parity via JNI
 * 3. RUST_OPTIMIZED: worldgen_noise::terrain::set_blocks_in_chunk_column_major via JNI
 * 4. RUST_FUSED: worldgen_noise::terrain::generate_terrain_fused via JNI
 */
public final class TerrainParity {

    private static final int CHUNK_PRIMER_SIZE = 65536;
    private static final int STONE_ID = 16;
    private static final int WATER_ID = 144;
    private static final int AIR_ID = 0;
    private static final int DEFAULT_SEA_LEVEL = 63;

    private static Field PRIMER_CHAR_ARRAY_FIELD;

    static {
        try {
            Field f = ChunkPrimer.class.getDeclaredField("field_177860_a");
            f.setAccessible(true);
            PRIMER_CHAR_ARRAY_FIELD = f;
        } catch (Exception e) {
            throw new RuntimeException("Cannot access ChunkPrimer.field_177860_a", e);
        }
    }

    private static char[] getPrimerChars(ChunkPrimer primer) {
        try {
            return (char[]) PRIMER_CHAR_ARRAY_FIELD.get(primer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void javaSetBlocksInChunk(double[] heightMap, int seaLevel, int stoneId, int waterId, char[] primerChars) {
        for (int ix = 0; ix < 4; ++ix) {
            int px = ix * 5;
            int pxx = (ix + 1) * 5;
            for (int iz = 0; iz < 4; ++iz) {
                int pxxxx = (px + iz) * 33;
                int pxxxxx = (px + iz + 1) * 33;
                int pxxxxxx = (pxx + iz) * 33;
                int pxxxxxxx = (pxx + iz + 1) * 33;

                for (int iy = 0; iy < 32; ++iy) {
                    double h00 = heightMap[pxxxx + iy];
                    double h01 = heightMap[pxxxxx + iy];
                    double h10 = heightMap[pxxxxxx + iy];
                    double h11 = heightMap[pxxxxxxx + iy];
                    double dh00 = (heightMap[pxxxx + iy + 1] - h00) * 0.125;
                    double dh01 = (heightMap[pxxxxx + iy + 1] - h01) * 0.125;
                    double dh10 = (heightMap[pxxxxxx + iy + 1] - h10) * 0.125;
                    double dh11 = (heightMap[pxxxxxxx + iy + 1] - h11) * 0.125;

                    for (int sub_y = 0; sub_y < 8; ++sub_y) {
                        double var_y0 = h00;
                        double var_y1 = h01;
                        double dx0 = (h10 - h00) * 0.25;
                        double dx1 = (h11 - h01) * 0.25;

                        for (int sub_x = 0; sub_x < 4; ++sub_x) {
                            double dz = (var_y1 - var_y0) * 0.25;
                            double density = var_y0 - dz;

                            for (int sub_z = 0; sub_z < 4; ++sub_z) {
                                if ((density += dz) > 0.0) {
                                    int x = ix * 4 + sub_x;
                                    int y = iy * 8 + sub_y;
                                    int z = iz * 4 + sub_z;
                                    int idx = (x << 12) | (z << 8) | y;
                                    primerChars[idx] = (char) stoneId;
                                } else if (iy * 8 + sub_y < seaLevel) {
                                    int x = ix * 4 + sub_x;
                                    int y = iy * 8 + sub_y;
                                    int z = iz * 4 + sub_z;
                                    int idx = (x << 12) | (z << 8) | y;
                                    primerChars[idx] = (char) waterId;
                                }
                            }

                            var_y0 += dx0;
                            var_y1 += dx1;
                        }

                        h00 += dh00;
                        h01 += dh01;
                        h10 += dh10;
                        h11 += dh11;
                    }
                }
            }
        }
    }

    private static long addr(ByteBuffer bb) {
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(bb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long addr(DoubleBuffer db) {
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(db);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long addr(FloatBuffer fb) {
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(fb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "machine/raw/M3W5-terrain-parity.txt";
        PrintStream out = new PrintStream(Files.newOutputStream(Paths.get(outPath)), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));

        System.loadLibrary("rustcraft_ffi");
        Bootstrap.func_151354_b();

        out.println("================================================================================");
        out.println(" M3W5 BASE TERRAIN / CHUNKPRIMER DIFFERENTIAL ORACLE & BENCHMARK");
        out.println("================================================================================");

        // Verify block state IDs match expected constants
        int verifiedStone = Block.field_176229_d.func_148747_b(Blocks.field_150348_b.func_176223_P()); // Blocks.STONE
        int verifiedWater = Block.field_176229_d.func_148747_b(Blocks.field_150355_j.func_176223_P()); // Blocks.WATER
        int verifiedAir = Block.field_176229_d.func_148747_b(Blocks.field_150350_a.func_176223_P());   // Blocks.AIR

        out.println("Verified block IDs: STONE=" + verifiedStone + ", WATER=" + verifiedWater + ", AIR=" + verifiedAir);
        if (verifiedStone != STONE_ID || verifiedWater != WATER_ID || verifiedAir != AIR_ID) {
            out.println("WARNING: Block IDs differ from defaults! Expected STONE=16, WATER=144, AIR=0");
        }

        // Vanilla ChunkGeneratorSettings
        Object st = Class.forName("net.minecraft.world.gen.ChunkGeneratorSettings$Factory").newInstance();
        java.lang.reflect.Method build = st.getClass().getMethod("func_177864_b");
        Object s = build.invoke(st);
        float a = FieldParity.f(s, "field_177811_a"), b = FieldParity.f(s, "field_177809_b");
        float e = FieldParity.f(s, "field_177808_e"), fp = FieldParity.f(s, "field_177803_f"), g = FieldParity.f(s, "field_177804_g");
        float h = FieldParity.f(s, "field_177825_h"), i = FieldParity.f(s, "field_177827_i"), j = FieldParity.f(s, "field_177821_j");
        float k = FieldParity.f(s, "field_177823_k"), l = FieldParity.f(s, "field_177817_l");
        float d = FieldParity.f(s, "field_177806_d"), c = FieldParity.f(s, "field_177810_c");
        float m = FieldParity.f(s, "field_177819_m"), n = FieldParity.f(s, "field_177813_n");
        float o = FieldParity.f(s, "field_177815_o"), p = FieldParity.f(s, "field_177843_p");

        Class<?> biomesClass = Class.forName("net.minecraft.init.Biomes");
        Biome[] picks = {
                (Biome) biomesClass.getField("field_76769_d").get(null), // plains
                (Biome) biomesClass.getField("field_76781_i").get(null), // desert
                (Biome) biomesClass.getField("field_76771_b").get(null), // ocean
                (Biome) biomesClass.getField("field_76776_l").get(null), // extreme hills
                (Biome) biomesClass.getField("field_76788_q").get(null), // swampland
        };

        // Direct buffers for FFI
        DoubleBuffer densityBuf = ByteBuffer.allocateDirect(825 * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
        FloatBuffer biomeBuf = ByteBuffer.allocateDirect(200 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ByteBuffer primerParityBB = ByteBuffer.allocateDirect(CHUNK_PRIMER_SIZE * 2).order(ByteOrder.nativeOrder());
        ByteBuffer primerOptBB = ByteBuffer.allocateDirect(CHUNK_PRIMER_SIZE * 2).order(ByteOrder.nativeOrder());
        ByteBuffer primerFusedBB = ByteBuffer.allocateDirect(CHUNK_PRIMER_SIZE * 2).order(ByteOrder.nativeOrder());

        long densityAddr = addr(densityBuf);
        long biomeAddr = addr(biomeBuf);
        long primerParityAddr = addr(primerParityBB);
        long primerOptAddr = addr(primerOptBB);
        long primerFusedAddr = addr(primerFusedBB);

        long fHandle = WNoiseInterop.initCreate(1L, 2L, 3L, 4L,
                a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p, (byte) 0);

        char[] javaPrimerChars = new char[CHUNK_PRIMER_SIZE];

        // ---------------------------------------------------------------------
        // PART 1: Bit-exact parity test across 100 random chunks
        // ---------------------------------------------------------------------
        out.println("\n--- PART 1: Bit-Exact Differential Testing (100 Chunks) ---");
        long totalBlocks = 0;
        long mismatchesParity = 0;
        long mismatchesOpt = 0;
        long mismatchesFused = 0;
        long totalStone = 0;
        long totalWater = 0;
        long totalAir = 0;

        Random rng = new Random(133742);

        for (int trial = 0; trial < 100; trial++) {
            int cx = rng.nextInt(20000) - 10000;
            int cz = rng.nextInt(20000) - 10000;
            int x4 = cx * 4;
            int z4 = cz * 4;

            float[][] grid = new float[100][2];
            biomeBuf.clear();
            for (int ci = 0; ci < 100; ci++) {
                Biome bio = picks[rng.nextInt(picks.length)];
                grid[ci][0] = bio.func_185355_j();
                grid[ci][1] = bio.func_185360_m();
                biomeBuf.put(grid[ci][0]);
            }
            for (int ci = 0; ci < 100; ci++) {
                biomeBuf.put(grid[ci][1]);
            }
            biomeBuf.flip();

            // Step A: Generate density using Rust initFieldComplete into densityBuf
            WNoiseInterop.initFieldComplete(fHandle, x4, z4, biomeAddr, densityAddr);

            double[] heightMap = new double[825];
            densityBuf.rewind();
            densityBuf.get(heightMap);

            // Step B: Java reference ChunkPrimer
            java.util.Arrays.fill(javaPrimerChars, (char) AIR_ID);
            javaSetBlocksInChunk(heightMap, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, javaPrimerChars);

            // Also test against actual vanilla ChunkPrimer to confirm javaSetBlocksInChunk matches ChunkPrimer.setBlockState exactly
            ChunkPrimer vanillaPrimer = new ChunkPrimer();
            for (int ix = 0; ix < 4; ++ix) {
                int px = ix * 5;
                int pxx = (ix + 1) * 5;
                for (int iz = 0; iz < 4; ++iz) {
                    int pxxxx = (px + iz) * 33;
                    int pxxxxx = (px + iz + 1) * 33;
                    int pxxxxxx = (pxx + iz) * 33;
                    int pxxxxxxx = (pxx + iz + 1) * 33;
                    for (int iy = 0; iy < 32; ++iy) {
                        double h00 = heightMap[pxxxx + iy];
                        double h01 = heightMap[pxxxxx + iy];
                        double h10 = heightMap[pxxxxxx + iy];
                        double h11 = heightMap[pxxxxxxx + iy];
                        double dh00 = (heightMap[pxxxx + iy + 1] - h00) * 0.125;
                        double dh01 = (heightMap[pxxxxx + iy + 1] - h01) * 0.125;
                        double dh10 = (heightMap[pxxxxxx + iy + 1] - h10) * 0.125;
                        double dh11 = (heightMap[pxxxxxxx + iy + 1] - h11) * 0.125;
                        for (int sub_y = 0; sub_y < 8; ++sub_y) {
                            double var_y0 = h00;
                            double var_y1 = h01;
                            double dx0 = (h10 - h00) * 0.25;
                            double dx1 = (h11 - h01) * 0.25;
                            for (int sub_x = 0; sub_x < 4; ++sub_x) {
                                double dz = (var_y1 - var_y0) * 0.25;
                                double density = var_y0 - dz;
                                for (int sub_z = 0; sub_z < 4; ++sub_z) {
                                    if ((density += dz) > 0.0) {
                                        vanillaPrimer.func_177855_a(ix * 4 + sub_x, iy * 8 + sub_y, iz * 4 + sub_z, Blocks.field_150348_b.func_176223_P());
                                    } else if (iy * 8 + sub_y < DEFAULT_SEA_LEVEL) {
                                        vanillaPrimer.func_177855_a(ix * 4 + sub_x, iy * 8 + sub_y, iz * 4 + sub_z, Blocks.field_150355_j.func_176223_P());
                                    }
                                }
                                var_y0 += dx0;
                                var_y1 += dx1;
                            }
                            h00 += dh00;
                            h01 += dh01;
                            h10 += dh10;
                            h11 += dh11;
                        }
                    }
                }
            }
            char[] vanillaChars = getPrimerChars(vanillaPrimer);
            for (int i2 = 0; i2 < CHUNK_PRIMER_SIZE; i2++) {
                if (javaPrimerChars[i2] != vanillaChars[i2]) {
                    out.println("FATAL: javaSetBlocksInChunk does not match vanilla ChunkPrimer at index " + i2);
                    System.exit(1);
                }
            }

            // Step C: Rust parity
            for (int i2 = 0; i2 < CHUNK_PRIMER_SIZE * 2; i2++) primerParityBB.put(i2, (byte) 0);
            WNoiseInterop.terrainSetBlocks(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerParityAddr);

            // Step D: Rust optimized (column major)
            for (int i2 = 0; i2 < CHUNK_PRIMER_SIZE * 2; i2++) primerOptBB.put(i2, (byte) 0);
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);

            // Step E: Rust fused (density + terrain)
            for (int i2 = 0; i2 < CHUNK_PRIMER_SIZE * 2; i2++) primerFusedBB.put(i2, (byte) 0);
            WNoiseInterop.terrainComplete(fHandle, x4, z4, biomeAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerFusedAddr);

            // Verify bit-exact match across all 65,536 positions
            java.nio.CharBuffer cbParity = primerParityBB.asCharBuffer();
            java.nio.CharBuffer cbOpt = primerOptBB.asCharBuffer();
            java.nio.CharBuffer cbFused = primerFusedBB.asCharBuffer();

            for (int idx = 0; idx < CHUNK_PRIMER_SIZE; idx++) {
                char jc = javaPrimerChars[idx];
                char pc = cbParity.get(idx);
                char oc = cbOpt.get(idx);
                char fc = cbFused.get(idx);

                totalBlocks++;
                if (jc == (char) verifiedStone) totalStone++;
                else if (jc == (char) verifiedWater) totalWater++;
                else totalAir++;

                if (jc != pc) mismatchesParity++;
                if (jc != oc) mismatchesOpt++;
                if (jc != fc) mismatchesFused++;
            }

            if (trial < 5 || trial == 99) {
                out.printf("Chunk %3d (cx=%6d, cz=%6d): parity_mism=%d, opt_mism=%d, fused_mism=%d\n",
                        trial, cx, cz, mismatchesParity, mismatchesOpt, mismatchesFused);
            }
        }

        out.println("\nParity verification results over " + totalBlocks + " blocks (100 chunks):");
        out.println("  Block composition: Stone=" + totalStone + " (" + String.format("%.2f", (totalStone * 100.0 / totalBlocks)) + "%), "
                + "Water=" + totalWater + " (" + String.format("%.2f", (totalWater * 100.0 / totalBlocks)) + "%), "
                + "Air=" + totalAir + " (" + String.format("%.2f", (totalAir * 100.0 / totalBlocks)) + "%)");
        out.println("  Rust Parity mismatches:    " + mismatchesParity + " / " + totalBlocks + " (" + (mismatchesParity == 0 ? "BIT-EXACT 100%" : "FAIL") + ")");
        out.println("  Rust Column-Major mism:    " + mismatchesOpt + " / " + totalBlocks + " (" + (mismatchesOpt == 0 ? "BIT-EXACT 100%" : "FAIL") + ")");
        out.println("  Rust Fused mismatches:     " + mismatchesFused + " / " + totalBlocks + " (" + (mismatchesFused == 0 ? "BIT-EXACT 100%" : "FAIL") + ")");

        if (mismatchesParity != 0 || mismatchesOpt != 0 || mismatchesFused != 0) {
            out.println("PARITY FAILED — stopping before benchmark.");
            out.flush();
            System.exit(2);
        }

        // ---------------------------------------------------------------------
        // PART 2: Three-tier benchmark
        // ---------------------------------------------------------------------
        out.println("\n--- PART 2: Three-Way Micro-Benchmark (n=1 production shape) ---");
        int warm = 500;
        int reps = 3000;

        // Warmup
        for (int rep = 0; rep < warm; rep++) {
            javaSetBlocksInChunk(new double[825], DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, javaPrimerChars);
            WNoiseInterop.terrainSetBlocks(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerParityAddr);
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);
            WNoiseInterop.terrainComplete(fHandle, 0, 0, biomeAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerFusedAddr);
        }

        long[] tJava = new long[reps];
        long[] tRustParity = new long[reps];
        long[] tRustOpt = new long[reps];
        long[] tRustFused = new long[reps];
        long[] tJavaTotal = new long[reps];

        double[] hmap = new double[825];
        densityBuf.rewind();
        densityBuf.get(hmap);

        // 1. JAVA reference (setBlocksInChunk only)
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            javaSetBlocksInChunk(hmap, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, javaPrimerChars);
            tJava[rep] = System.nanoTime() - t0;
        }

        // 2. RUST parity (set_blocks_in_chunk_parity via JNI)
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainSetBlocks(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerParityAddr);
            tRustParity[rep] = System.nanoTime() - t0;
        }

        // 3. RUST optimized (set_blocks_in_chunk_column_major via JNI)
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);
            tRustOpt[rep] = System.nanoTime() - t0;
        }

        // 4. RUST fused (initNoiseField + set_blocks in ONE JNI call)
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainComplete(fHandle, 100, 100, biomeAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerFusedAddr);
            tRustFused[rep] = System.nanoTime() - t0;
        }

        // 5. JAVA total (Java initNoiseField + Java setBlocksInChunk)
        NoiseGeneratorOctaves jd = new NoiseGeneratorOctaves(new Random(1L), 16);
        NoiseGeneratorOctaves jm = new NoiseGeneratorOctaves(new Random(2L), 8);
        NoiseGeneratorOctaves jn = new NoiseGeneratorOctaves(new Random(3L), 16);
        NoiseGeneratorOctaves jx = new NoiseGeneratorOctaves(new Random(4L), 16);
        float[][] grid = new float[100][2];
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            double[] q = FieldParity.javaComplete(jd, jm, jn, jx, 100, 100, grid,
                    a, b, e, fp, g, h, i, j, k, l, d, c, m, n, o, p);
            javaSetBlocksInChunk(q, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, javaPrimerChars);
            tJavaTotal[rep] = System.nanoTime() - t0;
        }

        java.util.Arrays.sort(tJava);
        java.util.Arrays.sort(tRustParity);
        java.util.Arrays.sort(tRustOpt);
        java.util.Arrays.sort(tRustFused);
        java.util.Arrays.sort(tJavaTotal);

        printStats(out, "JAVA_SET_BLOCKS", tJava);
        printStats(out, "RUST_PARITY_SET_BLOCKS", tRustParity);
        printStats(out, "RUST_OPT_COLUMN_MAJOR", tRustOpt);
        printStats(out, "RUST_FUSED_COMPLETE (density + terrain)", tRustFused);
        printStats(out, "JAVA_TOTAL (density + terrain)", tJavaTotal);

        double speedupTerrainOpt = (double) tJava[reps / 2] / tRustOpt[reps / 2];
        double speedupTotalFused = (double) tJavaTotal[reps / 2] / tRustFused[reps / 2];

        out.printf("\nMedian Speedups (n=1 production shape):\n");
        out.printf("  Terrain placement alone (Rust Opt vs Java): %.2fx\n", speedupTerrainOpt);
        out.printf("  Total pipeline (Rust Fused vs Java Total):  %.2fx\n", speedupTotalFused);

        // ---------------------------------------------------------------------
        // PART 3: Native Chunk State Transfer & Materialization Study (Task 8)
        // ---------------------------------------------------------------------
        out.println("\n--- PART 3: ChunkPrimer Transfer & Java Materialization Study ---");
        out.println("Payload: 65,536 chars = 131,072 bytes (128 KB) per chunk");

        long[] tDirectKernelOnly = new long[reps];
        long[] tDirectWithHeapCopy = new long[reps];
        long[] tCriticalArray = new long[reps];
        char[] targetChars = new char[CHUNK_PRIMER_SIZE];

        // Warmup Part 3
        for (int rep = 0; rep < warm; rep++) {
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);
            primerOptBB.rewind();
            primerOptBB.asCharBuffer().get(targetChars);
            WNoiseInterop.terrainSetBlocksCritical(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, targetChars);
        }

        // Method 1: Off-heap Direct Buffer (Kernel only, no Java materialization)
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);
            tDirectKernelOnly[rep] = System.nanoTime() - t0;
        }

        // Method 2: Off-heap Direct Buffer + Java Heap Materialization (copy to char[])
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainSetBlocksOpt(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, primerOptAddr);
            primerOptBB.rewind();
            primerOptBB.asCharBuffer().get(targetChars);
            tDirectWithHeapCopy[rep] = System.nanoTime() - t0;
        }

        // Method 3: GetPrimitiveArrayCritical direct into Java char[]
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            WNoiseInterop.terrainSetBlocksCritical(densityAddr, DEFAULT_SEA_LEVEL, verifiedStone, verifiedWater, targetChars);
            tCriticalArray[rep] = System.nanoTime() - t0;
        }

        java.util.Arrays.sort(tDirectKernelOnly);
        java.util.Arrays.sort(tDirectWithHeapCopy);
        java.util.Arrays.sort(tCriticalArray);

        printStats(out, "1. NATIVE_DIRECT_NO_COPY (Zero-copy)", tDirectKernelOnly);
        printStats(out, "2. DIRECT_BUFFER + HEAP_COPY (asCharBuf)", tDirectWithHeapCopy);
        printStats(out, "3. GET_PRIMITIVE_ARRAY_CRITICAL", tCriticalArray);

        double p50DirectKernel = tDirectKernelOnly[reps / 2] / 1000.0;
        double p50DirectCopy = tDirectWithHeapCopy[reps / 2] / 1000.0;
        double p50Critical = tCriticalArray[reps / 2] / 1000.0;
        double copyOnlyCost = p50DirectCopy - p50DirectKernel;

        out.println("\nTransfer Cost Breakdown:");
        out.printf("  Pure Kernel Compute (Native):                  %6.2f us (100.0%%)\n", p50DirectKernel);
        out.printf("  Java Heap Copy Cost (128 KB memcpy):           %6.2f us (+%.1f%% overhead)\n",
                copyOnlyCost, (copyOnlyCost / p50DirectKernel) * 100.0);
        out.printf("  Total with Heap Materialization:               %6.2f us\n", p50DirectCopy);
        out.printf("  GetPrimitiveArrayCritical total:               %6.2f us\n", p50Critical);
        out.printf("  Native State Retention (retained in Rust):     %6.2f us (ELIMINATES %6.2f us / chunk)\n",
                p50DirectKernel, copyOnlyCost);

        out.flush();
        out.close();
        System.out.println("TerrainParity completed successfully. Output in " + outPath);
    }

    private static void printStats(PrintStream out, String name, long[] sortedNs) {
        int n = sortedNs.length;
        double p50 = sortedNs[(int) (n * 0.50)] / 1000.0;
        double p90 = sortedNs[(int) (n * 0.90)] / 1000.0;
        double p99 = sortedNs[(int) (n * 0.99)] / 1000.0;
        double min = sortedNs[0] / 1000.0;
        double max = sortedNs[n - 1] / 1000.0;
        out.printf("%-40s | p50: %8.2f us | p90: %8.2f us | p99: %8.2f us | min: %7.2f us | max: %8.2f us\n",
                name, p50, p90, p99, min, max);
    }
}
