package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.util.IntIdentityHashBiMap;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class ChunkPacketOracle {

    private static Field fieldBits;
    private static Field fieldPalette;
    private static Field fieldRegPalette;
    private static Field fieldStorage;
    private static Field fieldLinearArraySize;
    private static Field fieldLinearStates;
    private static Field fieldHashMapMap;

    static {
        try {
            fieldBits = BlockStateContainer.class.getDeclaredField("field_186024_e");
            fieldPalette = BlockStateContainer.class.getDeclaredField("field_186022_c");
            fieldRegPalette = BlockStateContainer.class.getDeclaredField("field_186023_d");
            fieldStorage = BlockStateContainer.class.getDeclaredField("field_186021_b");
            fieldBits.setAccessible(true);
            fieldPalette.setAccessible(true);
            fieldRegPalette.setAccessible(true);
            fieldStorage.setAccessible(true);

            for (Field f : BlockStatePaletteLinear.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == int.class) fieldLinearArraySize = f;
                else if (f.getType() == IBlockState[].class) fieldLinearStates = f;
            }
            for (Field f : BlockStatePaletteHashMap.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (IntIdentityHashBiMap.class.isAssignableFrom(f.getType())) fieldHashMapMap = f;
            }
        } catch (Throwable t) {
            throw new RuntimeException("Reflection init failed", t);
        }
    }

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        System.out.println("==================================================");
        System.out.println("  M1.2 STANDALONE DIFFERENTIAL ORACLE HARNESS");
        System.out.println("==================================================");

        if (!NativeChunkPacket.isNativeLoaded()) {
            System.err.println("FATAL: Native library rustcraft_ffi is not loaded!");
            System.exit(1);
        }

        // Phase 1: JNI Bridge Safety & Fallback Suite
        testJniSafetySuite();

        // Phase 2: Deterministic Golden Fixture Suite (1,000 fixtures)
        testDeterministicFixtures(1000);

        // Phase 3: High-Entropy Differential Fuzzer (10,000 cases)
        testDifferentialFuzzer(10000);

        // Phase 4: Micro-Benchmarking Rust-only Encodings
        benchmarkRustEncodings();

        System.out.println("\nAll M1.2 Differential Oracle Suites Passed with 100% Parity.");
    }

    private static void testJniSafetySuite() {
        System.out.println("\n--- [Phase 1] JNI Bridge Safety & Fallback Suite ---");

        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        ByteBuffer output = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        long stagingAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        long outputAddr = NativeChunkPacket.getDirectBufferAddress(output);

        // Populate valid empty chunk in staging
        staging.clear();
        staging.putShort(NativeChunkPacket.SCHEMA_VERSION);
        staging.putShort((short) 0); // mask 0
        staging.put((byte) 1); // fullChunk
        staging.put((byte) 0); // count 0
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 4);
        staging.put(biomes);
        int validLen = staging.position();

        // Test 1: Valid execution
        int res1 = NativeChunkPacket.encodeSections(stagingAddr, validLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res1 != 256) throw new RuntimeException("Valid execution failed: " + res1);
        System.out.println("  [PASS] Case 1: Valid direct staging and output -> written=" + res1);

        // Test 2: Null staging pointer
        int res2 = NativeChunkPacket.encodeSections(0, validLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res2 != -1) throw new RuntimeException("Null staging pointer failed to return -1: " + res2);
        System.out.println("  [PASS] Case 2: Null staging pointer -> code=" + res2);

        // Test 3: Null output pointer
        int res3 = NativeChunkPacket.encodeSections(stagingAddr, validLen, 0, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res3 != -1) throw new RuntimeException("Null output pointer failed to return -1: " + res3);
        System.out.println("  [PASS] Case 3: Null output pointer -> code=" + res3);

        // Test 4: Zero staging length
        int res4 = NativeChunkPacket.encodeSections(stagingAddr, 0, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res4 != -6) throw new RuntimeException("Zero staging length failed to return -6: " + res4);
        System.out.println("  [PASS] Case 4: Zero staging length -> code=" + res4);

        // Test 5: Negative staging length
        int res5 = NativeChunkPacket.encodeSections(stagingAddr, -10, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res5 != -6) throw new RuntimeException("Negative staging length failed to return -6: " + res5);
        System.out.println("  [PASS] Case 5: Negative staging length -> code=" + res5);

        // Test 6: Undersized output buffer
        int res6 = NativeChunkPacket.encodeSections(stagingAddr, validLen, outputAddr, 100);
        if (res6 != -2) throw new RuntimeException("Undersized output failed to return -2: " + res6);
        System.out.println("  [PASS] Case 6: Undersized output capacity -> code=" + res6);

        // Test 7: Malformed staging (invalid schema version 99)
        staging.putShort(0, (short) 99);
        int res7 = NativeChunkPacket.encodeSections(stagingAddr, validLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res7 != -7) throw new RuntimeException("Invalid schema version failed to return -7: " + res7);
        staging.putShort(0, NativeChunkPacket.SCHEMA_VERSION);
        System.out.println("  [PASS] Case 7: Invalid ABI schema version -> code=" + res7);

        // Test 8: Malformed staging (mask vs count mismatch)
        staging.putShort(2, (short) 0x03); // mask claims 2 bits set, but count is 0
        int res8 = NativeChunkPacket.encodeSections(stagingAddr, validLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res8 != -3) throw new RuntimeException("Mask mismatch failed to return -3: " + res8);
        staging.putShort(2, (short) 0);
        System.out.println("  [PASS] Case 8: Section mask mismatch -> code=" + res8);

        // Test 9: Forced test panic
        int res9 = NativeChunkPacket.testForcedPanic();
        if (res9 != -5) throw new RuntimeException("Forced test panic failed to catch: " + res9);
        System.out.println("  [PASS] Case 9: Forced Rust test panic via catch_unwind -> code=" + res9);

        // Test 10: Forced panic inside encodeSections
        int res10 = NativeChunkPacket.encodeSections(-999, validLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        if (res10 != -5) throw new RuntimeException("Controlled panic inside encodeSections failed: " + res10);
        System.out.println("  [PASS] Case 10: Controlled panic inside encodeSections -> code=" + res10);

        System.out.println("  JNI Bridge Safety Suite: 10/10 checks verified, zero crashes.");
    }

    private static void testDeterministicFixtures(int count) throws Exception {
        System.out.println("\n--- [Phase 2] Deterministic Golden Fixture Suite (" + count + " fixtures) ---");

        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        ByteBuffer output = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        long stagingAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        long outputAddr = NativeChunkPacket.getDirectBufferAddress(output);

        Random rng = new Random(42);
        int goldenSaved = 0;

        for (int i = 0; i < count; i++) {
            boolean fullChunk = (i % 3) != 0;
            boolean skyLight = (i % 2) == 0;

            int sectionType = i % 8;
            ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
            int mask = 0;

            switch (sectionType) {
                case 0: // Empty chunk
                    mask = 0;
                    break;
                case 1: // Single 4-bit section
                    mask = 1;
                    sections[0] = createMockSection(0, 4, 2, 0, skyLight, rng);
                    break;
                case 2: // 4-section surface chunk (bits=4)
                    mask = 0x000F;
                    for (int s = 0; s < 4; s++) sections[s] = createMockSection(s, 4, 3 + s, 0, skyLight, rng);
                    break;
                case 3: // 8-section chunk (mixed bits 4..8)
                    mask = 0x00FF;
                    for (int s = 0; s < 8; s++) sections[s] = createMockSection(s, 4 + (s % 5), 2 + s * 2, 0, skyLight, rng);
                    break;
                case 4: // 16-section full column (bits 4..8)
                    mask = 0xFFFF;
                    for (int s = 0; s < 16; s++) sections[s] = createMockSection(s, 4 + (s % 5), 4 + s, 0, skyLight, rng);
                    break;
                case 5: // Sparse masks (e.g. 0, 3, 7, 12, 15)
                    mask = (1 << 0) | (1 << 3) | (1 << 7) | (1 << 12) | (1 << 15);
                    sections[0] = createMockSection(0, 4, 2, 0, skyLight, rng);
                    sections[3] = createMockSection(3, 5, 8, 0, skyLight, rng);
                    sections[7] = createMockSection(7, 6, 16, 0, skyLight, rng);
                    sections[12] = createMockSection(12, 7, 32, 0, skyLight, rng);
                    sections[15] = createMockSection(15, 8, 64, 0, skyLight, rng);
                    break;
                case 6: // Global/registry palette (bits=13)
                    mask = 0x0003;
                    sections[0] = createMockSection(0, 13, 0, 0, skyLight, rng);
                    sections[1] = createMockSection(1, 13, 0, 0, skyLight, rng);
                    break;
                case 7: // Modded high state IDs up to 65535
                    mask = 0x0007;
                    sections[0] = createMockSection(0, 5, 10, 60000, skyLight, rng);
                    sections[1] = createMockSection(1, 6, 20, 62000, skyLight, rng);
                    sections[2] = createMockSection(2, 8, 50, 65000, skyLight, rng);
                    break;
            }

            byte[] biomes = new byte[256];
            for (int b = 0; b < 256; b++) biomes[b] = (byte) ((i + b) & 0xFF);

            // 1. Authoritative Java Reference Payload
            ByteBuf refBuf = NativeChunkPacket.encodeJavaReference(sections, mask, fullChunk, skyLight, biomes);
            byte[] expectedBytes = new byte[refBuf.readableBytes()];
            refBuf.readBytes(expectedBytes);
            refBuf.release();

            // 2. Native Staging & Encoding
            staging.clear();
            int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, mask, fullChunk, skyLight, biomes);
            int written = NativeChunkPacket.encodeSections(stagingAddr, stagedLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);

            if (written != expectedBytes.length) {
                throw new RuntimeException("Fixture #" + i + " length mismatch! expected=" + expectedBytes.length + ", got=" + written);
            }

            byte[] actualBytes = new byte[written];
            output.position(0);
            output.get(actualBytes, 0, written);

            for (int b = 0; b < written; b++) {
                if (actualBytes[b] != expectedBytes[b]) {
                    throw new RuntimeException(String.format("Fixture #%d mismatch at byte %d! expected=0x%02X, actual=0x%02X (type=%d, mask=0x%04X)",
                            i, b, expectedBytes[b], actualBytes[b], sectionType, mask));
                }
            }

            // Save first 10 fixtures to disk as golden reference files
            if (goldenSaved < 10) {
                File goldenFile = new File(String.format("benchmarks/m1/chunk-packet/golden/fixture_%03d_type%d.bin", i, sectionType));
                try (FileOutputStream fos = new FileOutputStream(goldenFile)) {
                    fos.write(expectedBytes);
                }
                goldenSaved++;
            }
        }

        System.out.println("  [PASS] 1000/1000 Deterministic Fixtures Matched Bit-for-Bit with Java Reference!");
    }

    private static void testDifferentialFuzzer(int cases) throws Exception {
        System.out.println("\n--- [Phase 3] High-Entropy Differential Fuzzer (" + cases + " cases) ---");

        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        ByteBuffer output = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        long stagingAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        long outputAddr = NativeChunkPacket.getDirectBufferAddress(output);

        Random rng = new Random(133742);
        int fuzzSaved = 0;

        for (int c = 0; c < cases; c++) {
            int mask = rng.nextInt(65536);
            boolean fullChunk = rng.nextBoolean();
            boolean skyLight = rng.nextBoolean();

            ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
            for (int s = 0; s < 16; s++) {
                if ((mask & (1 << s)) != 0) {
                    int bitsChoice = rng.nextInt(10);
                    int bits;
                    int paletteCount;
                    int baseId = rng.nextInt(60000);
                    if (bitsChoice < 5) {
                        bits = 4;
                        paletteCount = 1 + rng.nextInt(15);
                    } else if (bitsChoice < 7) {
                        bits = 5;
                        paletteCount = 16 + rng.nextInt(15);
                    } else if (bitsChoice < 9) {
                        bits = 8;
                        paletteCount = 32 + rng.nextInt(64);
                    } else {
                        bits = 13; // global
                        paletteCount = 0;
                    }
                    sections[s] = createMockSection(s, bits, paletteCount, baseId, skyLight, rng);
                }
            }

            byte[] biomes = new byte[256];
            rng.nextBytes(biomes);

            ByteBuf refBuf = NativeChunkPacket.encodeJavaReference(sections, mask, fullChunk, skyLight, biomes);
            byte[] expected = new byte[refBuf.readableBytes()];
            refBuf.readBytes(expected);
            refBuf.release();

            staging.clear();
            int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, mask, fullChunk, skyLight, biomes);
            int written = NativeChunkPacket.encodeSections(stagingAddr, stagedLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);

            if (written != expected.length) {
                throw new RuntimeException("Fuzz #" + c + " length mismatch! expected=" + expected.length + ", got=" + written);
            }

            output.position(0);
            byte[] actual = new byte[written];
            output.get(actual, 0, written);

            for (int b = 0; b < written; b++) {
                if (actual[b] != expected[b]) {
                    throw new RuntimeException(String.format("Fuzz case #%d bit mismatch at byte %d: expected=0x%02X, actual=0x%02X", c, b, expected[b], actual[b]));
                }
            }

            if (fuzzSaved < 5) {
                File fuzzFile = new File(String.format("benchmarks/m1/chunk-packet/fuzz/case_%04d.bin", c));
                try (FileOutputStream fos = new FileOutputStream(fuzzFile)) {
                    fos.write(expected);
                }
                fuzzSaved++;
            }
        }

        System.out.println("  [PASS] " + cases + "/" + cases + " High-Entropy Fuzz Cases Passed with 0 Divergence!");
    }

    private static void benchmarkRustEncodings() throws Exception {
        System.out.println("\n--- [Phase 4] Rust-only Micro-Latency Benchmarking ---");

        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        ByteBuffer output = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        long stagingAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        long outputAddr = NativeChunkPacket.getDirectBufferAddress(output);
        Random rng = new Random(999);

        // 4-section surface chunk
        ExtendedBlockStorage[] s4 = new ExtendedBlockStorage[16];
        for (int i = 0; i < 4; i++) s4[i] = createMockSection(i, 4, 4, 0, true, rng);
        staging.clear();
        int len4 = NativeChunkPacket.populateStagingBuffer(staging, s4, 0x000F, true, true, new byte[256]);

        // 8-section underground chunk
        ExtendedBlockStorage[] s8 = new ExtendedBlockStorage[16];
        for (int i = 0; i < 8; i++) s8[i] = createMockSection(i, 4, 4, 0, true, rng);
        staging.clear();
        int len8 = NativeChunkPacket.populateStagingBuffer(staging, s8, 0x00FF, true, true, new byte[256]);

        // 16-section full column
        ExtendedBlockStorage[] s16 = new ExtendedBlockStorage[16];
        for (int i = 0; i < 16; i++) s16[i] = createMockSection(i, 4, 4, 0, true, rng);
        staging.clear();
        int len16 = NativeChunkPacket.populateStagingBuffer(staging, s16, 0xFFFF, true, true, new byte[256]);

        // Warmup JIT / branch predictor
        for (int i = 0; i < 5000; i++) {
            NativeChunkPacket.encodeSections(stagingAddr, len4, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
            NativeChunkPacket.encodeSections(stagingAddr, len8, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
            NativeChunkPacket.encodeSections(stagingAddr, len16, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        }

        int samples = 2000;
        long[] times4 = measureSamples(stagingAddr, len4, outputAddr, samples);
        long[] times8 = measureSamples(stagingAddr, len8, outputAddr, samples);
        long[] times16 = measureSamples(stagingAddr, len16, outputAddr, samples);

        Arrays.sort(times4);
        Arrays.sort(times8);
        Arrays.sort(times16);

        printStats("4-Section Surface Chunk", times4);
        printStats("8-Section Subsurface Chunk", times8);
        printStats("16-Section Full Column Chunk", times16);
    }

    private static long[] measureSamples(long stagingAddr, int stagingLen, long outputAddr, int samples) {
        long[] times = new long[samples];
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            NativeChunkPacket.encodeSections(stagingAddr, stagingLen, outputAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
            long t1 = System.nanoTime();
            times[i] = t1 - t0;
        }
        return times;
    }

    private static void printStats(String label, long[] sorted) {
        int n = sorted.length;
        double p50 = sorted[(int) (n * 0.50)] / 1000.0;
        double p90 = sorted[(int) (n * 0.90)] / 1000.0;
        double p99 = sorted[(int) (n * 0.99)] / 1000.0;
        long sum = 0;
        for (long t : sorted) sum += t;
        double mean = (sum / (double) n) / 1000.0;

        System.out.println(String.format("  %-30s : mean=%.2f us | p50=%.2f us | p90=%.2f us | p99=%.2f us",
                label, mean, p50, p90, p99));
    }

    private static ExtendedBlockStorage createMockSection(int sectionY, int bits, int paletteCount, int baseId, boolean skyLight, Random rng) throws Exception {
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(sectionY << 4, skyLight);
        BlockStateContainer bsc = ebs.func_186049_g();

        if (bits <= 4) {
            paletteCount = Math.min(16, Math.max(1, paletteCount));
        } else if (bits <= 8) {
            paletteCount = Math.min(1 << bits, Math.max(1, paletteCount));
        } else {
            paletteCount = 0;
        }

        fieldBits.setInt(bsc, bits);

        if (bits < 9) {
            if (bits <= 4) {
                BlockStatePaletteLinear pal = new BlockStatePaletteLinear(bits, bsc);
                fieldLinearArraySize.setInt(pal, paletteCount);
                IBlockState[] states = (IBlockState[]) fieldLinearStates.get(pal);
                for (int p = 0; p < paletteCount; p++) {
                    states[p] = Block.field_176229_d.func_148745_a(baseId + p);
                }
                fieldPalette.set(bsc, pal);
            } else {
                BlockStatePaletteHashMap pal = new BlockStatePaletteHashMap(bits, bsc);
                IntIdentityHashBiMap<IBlockState> map = (IntIdentityHashBiMap<IBlockState>) fieldHashMapMap.get(pal);
                for (int p = 0; p < paletteCount; p++) {
                    map.func_186814_a(Block.field_176229_d.func_148745_a(baseId + p), p);
                }
                fieldPalette.set(bsc, pal);
            }
        } else {
            IBlockStatePalette regPal = (IBlockStatePalette) fieldRegPalette.get(null);
            fieldPalette.set(bsc, regPal);
        }

        BitArray storage = new BitArray(bits, 4096);
        int maxVal = (1 << bits) - 1;
        for (int i = 0; i < 4096; i++) {
            int stateIdx = paletteCount > 0 ? (i % paletteCount) : ((baseId + i) & maxVal);
            storage.func_188141_a(i, stateIdx);
        }
        fieldStorage.set(bsc, storage);

        // Lights
        byte[] blockLight = ebs.func_76661_k().func_177481_a();
        rng.nextBytes(blockLight);
        if (skyLight && ebs.func_76671_l() != null) {
            byte[] sl = ebs.func_76671_l().func_177481_a();
            rng.nextBytes(sl);
        }

        // Set refCount so isEmpty() is false
        Field refCountField = ExtendedBlockStorage.class.getDeclaredField("field_76682_b");
        refCountField.setAccessible(true);
        refCountField.setInt(ebs, 4096);

        return ebs;
    }
}
