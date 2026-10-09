package com.rustcraft.bench;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BitArray;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.*;

public class BlockStorageBenchmarks {

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        System.out.println("=== P0-6 Block Storage Performance Suite Start ===");

        File outDir = new File("benchmarks/world/p0-6");
        outDir.mkdirs();
        PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir, "block_storage_benchmarks.txt")));

        benchMemoryFootprint(pw);
        benchBlockReads(pw);
        benchBlockWrites(pw);
        benchBulkScans(pw);
        benchPaletteResizing(pw);
        benchStateIdLookup(pw);
        benchSetBlockStateDecomposition(pw);
        benchTileEntityCreation(pw);
        benchFfiGranularity(pw);

        pw.close();
        System.out.println("=== P0-6 Block Storage Performance Suite Complete ===");
    }

    static void benchMemoryFootprint(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Memory Footprint Analysis (64-bit HotSpot, Compressed OOPs) ---");
        // JVM Object layout rules with Compressed Oops:
        // Object header: 12 bytes (8 mark + 4 klass) + 4 byte padding -> 16 bytes min.
        // Array header: 16 bytes (12 + 4 length).
        
        // 1. BitArray
        // 4-bit: 256 longs = 16 (header) + 256*8 = 2064 bytes + BitArray object (16 + 4 fields = 32 bytes) = 2096 bytes
        // 8-bit: 512 longs = 16 + 512*8 = 4112 bytes + 32 = 4144 bytes
        // 13-bit: 832 longs = 16 + 832*8 = 6672 bytes + 32 = 6704 bytes
        
        // 2. NibbleArray:
        // byte[2048] = 16 + 2048 = 2064 bytes + NibbleArray object (16 + 4 ref = 24 bytes) = 2088 bytes
        
        // 3. BlockStateContainer:
        // Object: 16 + 4 (bits) + 4 (storage ref) + 4 (palette ref) + 4 (air ref) = 32 bytes
        // Linear palette: 16 + 16*4 (refs) = 80 bytes
        // Total 4-bit section container: 32 + 80 + 2096 = 2208 bytes
        
        // 4. ExtendedBlockStorage:
        // Object: 16 + 4 (yBase) + 4 (blockRefCount) + 4 (tickRefCount) + 4 (data ref) + 4 (blockLight ref) + 4 (skyLight ref) = 40 bytes
        // BlockLight: 2088 bytes
        // SkyLight: 2088 bytes (if Overworld)
        // Total EBS (Overworld, 4-bit): 40 + 2208 + 2088 + 2088 = 6424 bytes (~6.3 KB)
        // Total EBS (Nether, no sky, 4-bit): 40 + 2208 + 2088 = 4336 bytes (~4.2 KB)
        // Total EBS (Global palette 13-bit): 40 + (32 + 6704) + 2088 + 2088 = 10952 bytes (~10.7 KB)
        
        // 5. Chunk object overhead:
        // Chunk object: ~160 bytes
        // storageArrays: ExtendedBlockStorage[16] = 16 + 16*4 = 80 bytes
        // blockBiomeArray: byte[256] = 16 + 256 = 272 bytes
        // heightMap: int[256] = 16 + 256*4 = 1040 bytes
        // precipitationHeightMap: int[256] = 1040 bytes
        // updateSkylightColumns: boolean[256] = 16 + 256 = 272 bytes
        // tileEntities: HashMap overhead = ~64 bytes (empty)
        // entityLists: ClassInheritanceMultiMap[16] = ~1280 bytes
        // Empty Chunk base footprint: ~4150 bytes (~4.1 KB)
        
        // Total Chunk Footprints:
        // Empty chunk (0 sections): 4.1 KB
        // 1 section (4-bit): 4.1 + 6.4 = 10.5 KB
        // 4 sections (typical surface): 4.1 + 4*6.4 = 29.7 KB
        // 8 sections: 4.1 + 8*6.4 = 55.3 KB
        // 16 sections (full 4-bit): 4.1 + 16*6.4 = 106.5 KB
        // 16 sections (full global palette): 4.1 + 16*11.0 = 180.1 KB
        
        log(pw, "Chunk Overhead (Empty):        %8d B (~4.1 KB)", 4150);
        log(pw, "Section Overhead (4-bit, Sky): %8d B (~6.3 KB) [Data: 2208 B, BlockLight: 2088 B, SkyLight: 2088 B]", 6424);
        log(pw, "Section Overhead (13-bit Global): %5d B (~10.7 KB)", 10952);
        log(pw, "Normal Chunk (4 sections):     %8d B (~29.7 KB)", 29700);
        log(pw, "Full Chunk (16 sections):      %8d B (~106.5 KB)", 106500);
    }

    static void benchBlockReads(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Block State Read Performance (Chunk-Local vs Section) ---");
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(0, true);
        IBlockState stone = Blocks.field_150348_b.func_176223_P();
        IBlockState dirt = Blocks.field_150346_d.func_176223_P();

        // Fill section with alternating stone/dirt
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    ebs.func_177484_a(x, y, z, (x + y + z) % 2 == 0 ? stone : dirt);
                }
            }
        }

        // Test A: Direct BlockStateContainer.get(x, y, z)
        BlockStateContainer bsc = ebs.func_186049_g();
        int batches = 5000;
        int batchSize = 1000;
        double[] bscTimes = new double[batches];

        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                int x = k & 15;
                int y = (k >> 4) & 15;
                int z = (k >> 8) & 15;
                IBlockState s = bsc.func_186016_a(x, y, z);
            }
            bscTimes[b] = (double)(System.nanoTime() - t0) / batchSize;
        }
        printStats(pw, "BlockStateContainer.get()", bscTimes);

        // Test B: ExtendedBlockStorage.get(x, y, z)
        double[] ebsTimes = new double[batches];
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                int x = k & 15;
                int y = (k >> 4) & 15;
                int z = (k >> 8) & 15;
                IBlockState s = ebs.func_177485_a(x, y, z);
            }
            ebsTimes[b] = (double)(System.nanoTime() - t0) / batchSize;
        }
        printStats(pw, "ExtendedBlockStorage.get()", ebsTimes);
    }

    static void benchBlockWrites(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Block State Write Performance (Container & Section) ---");
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(0, true);
        IBlockState stone = Blocks.field_150348_b.func_176223_P();
        IBlockState dirt = Blocks.field_150346_d.func_176223_P();
        IBlockState air = Blocks.field_150350_a.func_176223_P();

        int batches = 5000;
        int batchSize = 500;
        double[] sameStateTimes = new double[batches];
        double[] stateChangeTimes = new double[batches];
        double[] airToSolidTimes = new double[batches];

        // Scenario 1: Same state -> Same state
        ebs.func_177484_a(0, 0, 0, stone);
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                ebs.func_177484_a(0, 0, 0, stone);
            }
            sameStateTimes[b] = (double)(System.nanoTime() - t0) / batchSize;
        }
        printStats(pw, "EBS.set (Same State)", sameStateTimes);

        // Scenario 2: Solid -> Different Solid
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                IBlockState next = (k % 2 == 0) ? dirt : stone;
                ebs.func_177484_a(0, 0, 0, next);
            }
            stateChangeTimes[b] = (double)(System.nanoTime() - t0) / batchSize;
        }
        printStats(pw, "EBS.set (Solid -> Different Solid)", stateChangeTimes);

        // Scenario 3: Air -> Solid
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                ebs.func_177484_a(1, 1, 1, air);
                ebs.func_177484_a(1, 1, 1, stone);
            }
            airToSolidTimes[b] = (double)(System.nanoTime() - t0) / (batchSize * 2);
        }
        printStats(pw, "EBS.set (Air -> Solid)", airToSolidTimes);
    }

    static void benchBulkScans(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Bulk Section & Chunk Block Scanning ---");
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(0, true);
        IBlockState stone = Blocks.field_150348_b.func_176223_P();
        IBlockState air = Blocks.field_150350_a.func_176223_P();

        for (int i = 0; i < 2048; i++) {
            ebs.func_177484_a(i & 15, (i >> 4) & 15, (i >> 8) & 15, stone);
        }

        int iters = 5000;
        long[] scanTimes = new long[iters];

        for (int i = 0; i < iters; i++) {
            int matching = 0;
            long t0 = System.nanoTime();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (ebs.func_177485_a(x, y, z) == stone) {
                            matching++;
                        }
                    }
                }
            }
            scanTimes[i] = System.nanoTime() - t0;
        }
        printLongStats(pw, "Scan 4096 Blocks (Predicate Match)", scanTimes);
    }

    static void benchPaletteResizing(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Palette Resizing Spikes in BlockStateContainer ---");
        List<IBlockState> allStates = new ArrayList<>();
        for (Block b : Block.field_149771_c) {
            for (IBlockState bs : b.func_176194_O().func_177619_a()) {
                allStates.add(bs);
                if (allStates.size() >= 300) break;
            }
            if (allStates.size() >= 300) break;
        }

        int iters = 1000;
        long[] resize17Times = new long[iters];
        long[] resize257Times = new long[iters];

        // Benchmark Linear (4-bit) -> HashMap (5-bit) resize
        for (int k = 0; k < iters; k++) {
            BlockStateContainer bsc = new BlockStateContainer();
            for (int i = 0; i < 16; i++) {
                bsc.func_186013_a(i % 16, i / 16, 0, allStates.get(i));
            }
            long t0 = System.nanoTime();
            bsc.func_186013_a(0, 1, 0, allStates.get(16)); // Triggers resize
            resize17Times[k] = System.nanoTime() - t0;
        }
        printLongStats(pw, "Palette Resize (Linear 4b -> HashMap 5b)", resize17Times);

        // Benchmark HashMap (8-bit) -> Global Palette (13-bit) resize
        for (int k = 0; k < iters; k++) {
            BlockStateContainer bsc = new BlockStateContainer();
            for (int i = 0; i < 256; i++) {
                bsc.func_186013_a(i & 15, (i >> 4) & 15, i >> 8, allStates.get(i));
            }
            long t0 = System.nanoTime();
            // 257th distinct state triggers resize from HashMap to Global
            bsc.func_186013_a(0, 0, 1, allStates.get(256));
            resize257Times[k] = System.nanoTime() - t0;
        }
        printLongStats(pw, "Palette Resize (HashMap 8b -> Global 13b)", resize257Times);
    }

    static void benchStateIdLookup(PrintWriter pw) {
        log(pw, "\n--- [BENCH] Global Block State ID Registry Lookup ---");
        IBlockState stone = Blocks.field_150348_b.func_176223_P();
        int stoneId = Block.field_176229_d.func_148747_b(stone);

        int batches = 5000;
        int batchSize = 5000;
        double[] stateToIdTimes = new double[batches];
        double[] idToStateTimes = new double[batches];

        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                int id = Block.field_176229_d.func_148747_b(stone);
            }
            stateToIdTimes[b] = (double)(System.nanoTime() - t0) / batchSize;

            long t1 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                IBlockState s = Block.field_176229_d.func_148745_a(stoneId);
            }
            idToStateTimes[b] = (double)(System.nanoTime() - t1) / batchSize;
        }

        printStats(pw, "BLOCK_STATE_IDS.get(State -> ID)", stateToIdTimes);
        printStats(pw, "BLOCK_STATE_IDS.getByValue(ID -> State)", idToStateTimes);
    }

    static void benchSetBlockStateDecomposition(PrintWriter pw) {
        log(pw, "\n--- [BENCH] setBlockState Component Cost Decomposition ---");
        Map<Long, ExtendedBlockStorage> chunkMap = new HashMap<>();
        chunkMap.put(0L, new ExtendedBlockStorage(0, true));
        IBlockState stone = Blocks.field_150348_b.func_176223_P();

        int batches = 5000;
        int batchSize = 500;
        double[] mapLookupTimes = new double[batches];
        double[] rawSetTimes = new double[batches];

        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                ExtendedBlockStorage ebs = chunkMap.get(0L);
            }
            mapLookupTimes[b] = (double)(System.nanoTime() - t0) / batchSize;

            long t1 = System.nanoTime();
            ExtendedBlockStorage ebs = chunkMap.get(0L);
            for (int k = 0; k < batchSize; k++) {
                ebs.func_177484_a(k & 15, 0, 0, stone);
            }
            rawSetTimes[b] = (double)(System.nanoTime() - t1) / batchSize;
        }

        printStats(pw, "Component 1: Chunk Map Lookup", mapLookupTimes);
        printStats(pw, "Component 2: Section Storage Set", rawSetTimes);
    }

    static void benchTileEntityCreation(PrintWriter pw) {
        log(pw, "\n--- [BENCH] TileEntity Creation & Binding Overhead ---");
        // Measure cost of plain block set vs TileEntity creation & map insertion
        Map<BlockPos, TileEntityChest> teMap = new HashMap<>();
        BlockPos pos = new BlockPos(100, 64, 200);

        int iters = 20000;
        long[] teCreateTimes = new long[iters];

        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            TileEntityChest chest = new TileEntityChest();
            chest.func_174878_a(pos); // setPos
            teMap.put(pos, chest);
            teCreateTimes[i] = System.nanoTime() - t0;
            teMap.clear();
        }

        printLongStats(pw, "TileEntity Creation + Map Insertion", teCreateTimes);
    }

    static void benchFfiGranularity(PrintWriter pw) {
        log(pw, "\n--- [ANALYTIC MODEL, NOT MEASUREMENT] FFI GRANULARITY (§40) ---");
        // Pure arithmetic on ASSUMED constants; nothing is measured here.
        // Constants NOT calibrated since M1.4-R; ratios illustrative only.
        // Measured JNI transition data: machine/raw/ (LiveShadowHarness phase 2).
        final double JNI_BASE_NS = 15.2;   // ASSUMED
        final double LOOKUP_NS = 4.8;      // ASSUMED
        final double BULK_STAGE_NS = 50.0; // ASSUMED
        final double BULK_LOCAL_NS = 0.8;  // ASSUMED

        int[] operationCounts = new int[]{1, 100, 4096, 65536, 1000000};
        log(pw, "%-10s | %-30s | %-30s | %-15s", "N Ops", "Approach A (Per-Block JNI, model)", "Approach B (Bulk Section, model)", "Ratio (A / B)");

        for (int n : operationCounts) {
            // Model A: N FFI crossings, each fetching 1 block
            double timeA_ns = n * (JNI_BASE_NS + LOOKUP_NS);

            // Model B: ceil(N / 4096) FFI crossings, each staging 4096 blocks
            int sections = (int)Math.ceil((double)n / 4096.0);
            double timeB_ns = (sections * (JNI_BASE_NS + BULK_STAGE_NS)) + (n * BULK_LOCAL_NS);

            double ratio = timeA_ns / Math.max(1.0, timeB_ns);
            log(pw, "%-10d | %15.2f us (%5.1f ns/op) | %15.2f us (%5.2f ns/op) | %10.1fx (model)",
                    n, timeA_ns / 1000.0, timeA_ns / n, timeB_ns / 1000.0, timeB_ns / n, ratio);
        }
        log(pw, "\nConclusion (analytic only): per-block FFI crossings scale as O(N) crossings vs O(N/4096); bulk snapshots avoid ~20x-25x the crossing count. Not a runtime measurement.");
    }

    static void log(PrintWriter pw, String fmt, Object... args) {
        String s = String.format(fmt, args);
        System.out.println(s);
        pw.println(s);
    }

    static void printStats(PrintWriter pw, String name, double[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        double p50 = times[times.length / 2];
        double p95 = times[(int)(times.length * 0.95)];
        double p99 = times[(int)(times.length * 0.99)];
        double max = times[times.length - 1];
        String s = String.format("%-32s | Mean: %6.2f ns | p50: %6.2f ns | p95: %6.2f ns | p99: %6.2f ns | Max: %7.2f ns (N=%d)",
                name, mean, p50, p95, p99, max, times.length);
        System.out.println(s);
        pw.println(s);
    }

    static void printLongStats(PrintWriter pw, String name, long[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        long p50 = times[times.length / 2];
        long p95 = times[(int)(times.length * 0.95)];
        long p99 = times[(int)(times.length * 0.99)];
        long max = times[times.length - 1];
        String s = String.format("%-32s | Mean: %6.1f ns | p50: %5d ns | p95: %5d ns | p99: %5d ns | Max: %7d ns (N=%d)",
                name, mean, p50, p95, p99, max, times.length);
        System.out.println(s);
        pw.println(s);
    }
}
