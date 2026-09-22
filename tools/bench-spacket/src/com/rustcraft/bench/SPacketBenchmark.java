package com.rustcraft.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SPacketBenchmark {

    public static void main(String[] args) throws Exception {
        net.minecraft.init.Bootstrap.func_151354_b();
        System.out.println("=== SPacketChunkData Reference Decomposed Benchmark ===");
        System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // Setup test sections
        ExtendedBlockStorage[] sections16 = createTestSections(16);
        ExtendedBlockStorage[] sections4 = createTestSections(4);
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 1); // Plains

        // Synthetic TileEntity update tags (64 TEs)
        List<NBTTagCompound> teTags64 = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.func_74768_a("x", i % 16);
            tag.func_74768_a("y", 64);
            tag.func_74768_a("z", i / 16);
            tag.func_74778_a("id", "minecraft:furnace");
            tag.func_74777_a("BurnTime", (short) 100);
            tag.func_74777_a("CookTime", (short) 50);
            teTags64.add(tag);
        }

        // Warmup JIT (2,000 iterations)
        System.out.println("\nWarming up JIT compiler (2,000 iterations)...");
        for (int i = 0; i < 2000; i++) {
            runFullJavaPipeline(sections16, biomes, teTags64, true);
            runFullJavaPipeline(sections4, biomes, teTags64, false);
            benchDecomposedPhases(sections16, biomes, teTags64, false);
            benchInputStrategyA(sections16, biomes, false);
        }

        System.out.println("Warmup complete. Commencing precision benchmarking (2,000 samples)...\n");

        // 1. Decomposed Breakdown of 16-Section Full Chunk
        benchDecomposedPhases(sections16, biomes, teTags64, true);

        // 2. Multi-scenario evaluation (Empty, 4-sec, 8-sec, 16-sec, 64-TE)
        benchChunkScenarios(sections4, sections16, biomes, teTags64);

        // 3. Input Strategy A: Direct Staging Buffer in Java
        benchInputStrategyA(sections16, biomes, true);

        // 4. Input Strategy B: JNI Primitive Access Preparation
        benchInputStrategyB(sections16, biomes, true);
    }

    private static ExtendedBlockStorage[] createTestSections(int count) throws Exception {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        Field storageField = BlockStateContainer.class.getDeclaredField("field_186021_b");
        storageField.setAccessible(true);

        for (int s = 0; s < count; s++) {
            ExtendedBlockStorage ebs = new ExtendedBlockStorage(s << 4, true);
            BlockStateContainer bsc = ebs.func_186049_g();
            BitArray bitArray = new BitArray(4, 4096);
            for (int i = 0; i < 4096; i++) {
                bitArray.func_188141_a(i, i % 5);
            }
            storageField.set(bsc, bitArray);

            // Populate lights
            byte[] blockLight = ebs.func_76661_k().func_177481_a();
            byte[] skyLight = ebs.func_76671_l().func_177481_a();
            Arrays.fill(blockLight, (byte) 0xEE);
            Arrays.fill(skyLight, (byte) 0xFF);

            // Reflection to set blockRefCount so func_76663_a() (isEmpty) returns false
            Field refCountField = ExtendedBlockStorage.class.getDeclaredField("field_76682_b");
            refCountField.setAccessible(true);
            refCountField.setInt(ebs, 4096);

            sections[s] = ebs;
        }
        return sections;
    }

    private static void runFullJavaPipeline(ExtendedBlockStorage[] sections, byte[] biomes, List<NBTTagCompound> tes, boolean fullChunk) {
        int size = 0;
        int mask = 0;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].func_76663_a()) {
                mask |= (1 << i);
                size += sections[i].func_186049_g().func_186018_a();
                size += sections[i].func_76661_k().func_177481_a().length;
                size += sections[i].func_76671_l().func_177481_a().length;
            }
        }
        if (fullChunk) size += biomes.length;

        byte[] rawBuf = new byte[size];
        ByteBuf nettyBuf = Unpooled.wrappedBuffer(rawBuf);
        nettyBuf.writerIndex(0);
        PacketBuffer pb = new PacketBuffer(nettyBuf);

        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].func_76663_a()) {
                sections[i].func_186049_g().func_186009_b(pb);
                pb.writeBytes(sections[i].func_76661_k().func_177481_a());
                pb.writeBytes(sections[i].func_76671_l().func_177481_a());
            }
        }
        if (fullChunk) {
            pb.writeBytes(biomes);
        }

        List<NBTTagCompound> teList = new ArrayList<>(tes.size());
        for (NBTTagCompound tag : tes) {
            teList.add(tag.func_74737_b());
        }
    }

    private static void benchDecomposedPhases(ExtendedBlockStorage[] sections, byte[] biomes, List<NBTTagCompound> tes, boolean report) {
        final int ITERS = 2000;
        long[] tSize = new long[ITERS];
        long[] tAlloc = new long[ITERS];
        long[] tBlockState = new long[ITERS];
        long[] tLight = new long[ITERS];
        long[] tBiome = new long[ITERS];
        long[] tTE = new long[ITERS];
        long[] tTotal = new long[ITERS];

        for (int it = 0; it < ITERS; it++) {
            long t0 = System.nanoTime();

            // 1. Size calculation
            long s0 = System.nanoTime();
            int size = 0;
            int mask = 0;
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] != null && !sections[i].func_76663_a()) {
                    mask |= (1 << i);
                    size += sections[i].func_186049_g().func_186018_a();
                    size += 2048; // block light
                    size += 2048; // sky light
                }
            }
            size += biomes.length;
            long s1 = System.nanoTime();

            // 2. Buffer alloc
            long a0 = System.nanoTime();
            byte[] buf = new byte[size];
            ByteBuf byteBuf = Unpooled.wrappedBuffer(buf);
            byteBuf.writerIndex(0);
            PacketBuffer pbuf = new PacketBuffer(byteBuf);
            long a1 = System.nanoTime();

            // 3. BlockStateContainer write (palette + bitarray)
            long b0 = System.nanoTime();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] != null && !sections[i].func_76663_a()) {
                    sections[i].func_186049_g().func_186009_b(pbuf);
                }
            }
            long b1 = System.nanoTime();

            // 4. Light arrays
            long l0 = System.nanoTime();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] != null && !sections[i].func_76663_a()) {
                    pbuf.writeBytes(sections[i].func_76661_k().func_177481_a());
                    pbuf.writeBytes(sections[i].func_76671_l().func_177481_a());
                }
            }
            long l1 = System.nanoTime();

            // 5. Biome array
            long m0 = System.nanoTime();
            pbuf.writeBytes(biomes);
            long m1 = System.nanoTime();

            // 6. TE getUpdateTag work
            long te0 = System.nanoTime();
            List<NBTTagCompound> teList = new ArrayList<>(tes.size());
            for (NBTTagCompound tag : tes) {
                teList.add(tag.func_74737_b());
            }
            long te1 = System.nanoTime();

            long tEnd = System.nanoTime();

            tSize[it] = s1 - s0;
            tAlloc[it] = a1 - a0;
            tBlockState[it] = b1 - b0;
            tLight[it] = l1 - l0;
            tBiome[it] = m1 - m0;
            tTE[it] = te1 - te0;
            tTotal[it] = tEnd - t0;
        }

        if (report) {
            Arrays.sort(tSize);
            Arrays.sort(tAlloc);
            Arrays.sort(tBlockState);
            Arrays.sort(tLight);
            Arrays.sort(tBiome);
            Arrays.sort(tTE);
            Arrays.sort(tTotal);

            System.out.println("--- DECOMPOSED JAVA SPacketChunkData PHASES (16-Section Full Chunk + 64 TEs) ---");
            printStats("1. Size Calculation", tSize);
            printStats("2. Buffer & Packet Allocation", tAlloc);
            printStats("3. BlockStateContainer (Palette + BitArray)", tBlockState);
            printStats("4. Lighting Arrays (Block + Sky)", tLight);
            printStats("5. Biome Array Copy", tBiome);
            printStats("6. TileEntity getUpdateTag Copy (64 TEs)", tTE);
            printStats("TOTAL CONSTRUCTOR DURATION", tTotal);
        }
    }

    private static void benchChunkScenarios(ExtendedBlockStorage[] s4, ExtendedBlockStorage[] s16, byte[] biomes, List<NBTTagCompound> tes) throws Exception {
        System.out.println("\n--- SCENARIO BREAKDOWN (Mean & p95 µs) ---");
        ExtendedBlockStorage[] s0 = new ExtendedBlockStorage[16];
        ExtendedBlockStorage[] s8 = createTestSections(8);

        benchSingleScenario("Empty Chunk (0 Sections, No TE)", s0, biomes, new ArrayList<>(), true);
        benchSingleScenario("Normal 4-Section Chunk (Surface, 0 TE)", s4, biomes, new ArrayList<>(), true);
        benchSingleScenario("Dense 8-Section Chunk (Surface + Underground, 16 TEs)", s8, biomes, tes.subList(0, 16), true);
        benchSingleScenario("Full 16-Section Chunk (No TE)", s16, biomes, new ArrayList<>(), true);
        benchSingleScenario("Full 16-Section Chunk (64 TEs)", s16, biomes, tes, true);
    }

    private static void benchSingleScenario(String name, ExtendedBlockStorage[] sections, byte[] biomes, List<NBTTagCompound> tes, boolean fullChunk) {
        final int ITERS = 2000;
        long[] times = new long[ITERS];
        for (int it = 0; it < ITERS; it++) {
            long t0 = System.nanoTime();
            runFullJavaPipeline(sections, biomes, tes, fullChunk);
            long t1 = System.nanoTime();
            times[it] = t1 - t0;
        }
        Arrays.sort(times);
        double meanUs = Arrays.stream(times).average().orElse(0) / 1000.0;
        double p50Us = times[(int) (ITERS * 0.50)] / 1000.0;
        double p95Us = times[(int) (ITERS * 0.95)] / 1000.0;
        double p99Us = times[(int) (ITERS * 0.99)] / 1000.0;
        System.out.printf("%-45s : Mean: %6.2f µs | p50: %6.2f µs | p95: %6.2f µs | p99: %6.2f µs\n",
                name, meanUs, p50Us, p95Us, p99Us);
    }

    private static void benchInputStrategyA(ExtendedBlockStorage[] sections, byte[] biomes, boolean report) {
        final int ITERS = 2000;
        long[] times = new long[ITERS];
        ByteBuffer directStaging = ByteBuffer.allocateDirect(131072);

        for (int it = 0; it < ITERS; it++) {
            long t0 = System.nanoTime();
            directStaging.clear();
            directStaging.putShort((short) 0xFFFF);
            directStaging.put((byte) 1);
            directStaging.put((byte) 1);

            for (int s = 0; s < 16; s++) {
                ExtendedBlockStorage ebs = sections[s];
                directStaging.put((byte) 4);
                directStaging.putShort((short) 4);
                directStaging.putInt(1);
                directStaging.putInt(2);
                directStaging.putInt(3);
                directStaging.putInt(4);
                long[] longs = ebs.func_186049_g().func_186018_a() > 0 ? new long[256] : new long[0];
                directStaging.putShort((short) longs.length);
                for (long val : longs) {
                    directStaging.putLong(val);
                }
                directStaging.put(ebs.func_76661_k().func_177481_a());
                directStaging.put(ebs.func_76671_l().func_177481_a());
            }
            directStaging.put(biomes);
            long t1 = System.nanoTime();
            times[it] = t1 - t0;
        }

        if (report) {
            Arrays.sort(times);
            System.out.println("\n--- INPUT STRATEGY A (Java Direct Staging Buffer Creation) ---");
            printStats("Java Direct Staging Buffer Population", times);
        }
    }

    private static void benchInputStrategyB(ExtendedBlockStorage[] sections, byte[] biomes, boolean report) {
        final int ITERS = 2000;
        long[] times = new long[ITERS];

        for (int it = 0; it < ITERS; it++) {
            long t0 = System.nanoTime();
            long[][] longArrays = new long[16][];
            byte[][] blockLights = new byte[16][];
            byte[][] skyLights = new byte[16][];
            int[] paletteSizes = new int[16];
            int[][] paletteStates = new int[16][];

            for (int s = 0; s < 16; s++) {
                ExtendedBlockStorage ebs = sections[s];
                longArrays[s] = new long[256];
                blockLights[s] = ebs.func_76661_k().func_177481_a();
                skyLights[s] = ebs.func_76671_l().func_177481_a();
                paletteSizes[s] = 4;
                paletteStates[s] = new int[]{1, 2, 3, 4};
            }
            long t1 = System.nanoTime();
            times[it] = t1 - t0;
        }

        if (report) {
            Arrays.sort(times);
            System.out.println("\n--- INPUT STRATEGY B (JNI Primitive Array References Preparation) ---");
            printStats("Java Object Array Collection for JNI", times);
        }
    }

    private static void printStats(String label, long[] sortedNanos) {
        int n = sortedNanos.length;
        double meanUs = Arrays.stream(sortedNanos).average().orElse(0) / 1000.0;
        double p50Us = sortedNanos[(int) (n * 0.50)] / 1000.0;
        double p95Us = sortedNanos[(int) (n * 0.95)] / 1000.0;
        double p99Us = sortedNanos[(int) (n * 0.99)] / 1000.0;
        double maxUs = sortedNanos[n - 1] / 1000.0;
        System.out.printf("%-42s : Mean: %6.2f µs | p50: %6.2f µs | p95: %6.2f µs | p99: %6.2f µs | Max: %6.2f µs\n",
                label, meanUs, p50Us, p95Us, p99Us, maxUs);
    }
}
