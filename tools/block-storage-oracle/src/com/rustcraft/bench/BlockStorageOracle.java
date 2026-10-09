package com.rustcraft.bench;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.BlockStateContainer;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.*;

public class BlockStorageOracle {

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b(); // Initialize block registry
        System.out.println("=== P0-6 Block Storage & Coordinate Oracle Start ===");

        File outDir = new File("benchmarks/world/p0-6");
        outDir.mkdirs();

        generateCoordinateCorpus(new File(outDir, "golden_coordinates.txt"));
        generateBlockStateCorpus(new File(outDir, "golden_block_states.txt"));

        System.out.println("=== P0-6 Block Storage & Coordinate Oracle Complete ===");
    }

    static void generateCoordinateCorpus(File outFile) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(outFile));
        pw.println("# Golden Coordinate Test Corpus (Minecraft 1.12.2 / Protocol 340)");
        pw.println("# format: TYPE | input_args | packed_hex | expected_unpacked_or_result");

        int[][] testCoords = new int[][]{
                {0, 0, 0},
                {1, 1, 1},
                {-1, -1, -1},
                {15, 64, 15},
                {16, 64, 16},
                {-15, 64, -15},
                {-16, 64, -16},
                {-17, 64, -17},
                {-31, 64, -31},
                {-32, 64, -32},
                {-33, 64, -33},
                {30000000, 255, 30000000},
                {-30000000, 0, -30000000},
                {33554431, 2047, 33554431},    // Max positive 26-bit / 12-bit
                {-33554432, -2048, -33554432}, // Min negative 26-bit / 12-bit
                {100, 256, 200},               // Y at build limit
                {100, -1, 200},                // Y below world
                {512, 128, 512}                // Region boundary
        };

        for (int[] c : testCoords) {
            int x = c[0], y = c[1], z = c[2];
            BlockPos bp = new BlockPos(x, y, z);
            long packed = bp.func_177986_g(); // toLong()
            BlockPos unpacked = BlockPos.func_177969_a(packed); // fromLong()

            int cx = bp.func_177958_n() >> 4;
            int cz = bp.func_177952_p() >> 4;
            int lx = bp.func_177958_n() & 15;
            int ly = bp.func_177956_o() & 15;
            int lz = bp.func_177952_p() & 15;
            int secIdx = bp.func_177956_o() >> 4;

            long chunkKey = ChunkPos.func_77272_a(cx, cz);
            int hash = new ChunkPos(cx, cz).hashCode();

            pw.printf("BLOCKPOS | %d,%d,%d | 0x%016x | unpack=%d,%d,%d | chunk=%d,%d | local=%d,%d,%d | sec=%d | ckey=0x%016x | chash=%d%n",
                    x, y, z, packed, unpacked.func_177958_n(), unpacked.func_177956_o(), unpacked.func_177952_p(), cx, cz, lx, ly, lz, secIdx, chunkKey, hash);
        }

        pw.close();
        System.out.println("Generated " + outFile.getPath());
    }

    static void generateBlockStateCorpus(File outFile) throws Exception {
        PrintWriter pw = new PrintWriter(new FileWriter(outFile));
        pw.println("# Golden Block State Corpus (Minecraft 1.12.2)");
        pw.println("# format: STATE_NAME | GLOBAL_ID | BLOCK_ID | META");

        // Collect representative states
        IBlockState[] states = new IBlockState[]{
                Blocks.field_150350_a.func_176223_P(), // Air
                Blocks.field_150348_b.func_176223_P(), // Stone (meta 0)
                Blocks.field_150346_d.func_176223_P(), // Dirt
                Blocks.field_150349_c.func_176223_P(), // Grass
                Blocks.field_150344_f.func_176223_P(), // Wood Planks
                Blocks.field_150357_h.func_176223_P(), // Bedrock
                Blocks.field_150355_j.func_176223_P(), // Flowing Water
                Blocks.field_150353_l.func_176223_P(), // Flowing Lava
                Blocks.field_150354_m.func_176223_P(), // Sand
                Blocks.field_150322_A.func_176223_P(), // Sandstone
                Blocks.field_150341_Y.func_176223_P(), // Chest
                Blocks.field_150486_ae.func_176223_P(), // Trapped Chest
                Blocks.field_150482_ag.func_176223_P(), // Daylight detector
                Blocks.field_150478_aa.func_176223_P(), // Redstone torch
                Blocks.field_150429_aA.func_176223_P(), // Quartz
        };

        for (IBlockState state : states) {
            int globalId = Block.field_176229_d.func_148747_b(state); // BLOCK_STATE_IDS.get()
            Block b = state.func_177230_c();
            int bId = Block.func_149682_b(b); // getIdFromBlock
            int meta = b.func_176201_c(state); // getMetaFromState
            pw.printf("STATE | %-25s | global_id=%5d | block_id=%4d | meta=%2d%n",
                    b.getClass().getSimpleName(), globalId, bId, meta);
        }

        // Section Palette Transitions test
        pw.println("\n# PALETTE TRANSITIONS IN BlockStateContainer");
        BlockStateContainer container = new BlockStateContainer();
        Field bitsField = BlockStateContainer.class.getDeclaredField("field_186024_e");
        bitsField.setAccessible(true);
        Field paletteField = BlockStateContainer.class.getDeclaredField("field_186022_c");
        paletteField.setAccessible(true);

        pw.printf("INITIAL: bits=%d, palette=%s%n",
                bitsField.getInt(container), paletteField.get(container).getClass().getSimpleName());

        // Collect distinct states from registry
        List<IBlockState> allStates = new ArrayList<>();
        for (Block b : Block.field_149771_c) {
            for (IBlockState bs : b.func_176194_O().func_177619_a()) {
                allStates.add(bs);
                if (allStates.size() >= 300) break;
            }
            if (allStates.size() >= 300) break;
        }

        for (int i = 0; i < 16; i++) {
            container.func_186013_a(i % 16, i / 16, 0, allStates.get(i));
        }
        pw.printf("AFTER 16 STATES: bits=%d, palette=%s%n",
                bitsField.getInt(container), paletteField.get(container).getClass().getSimpleName());

        // 17th state -> triggers Linear (4-bit) -> HashMap (5-bit)
        container.func_186013_a(0, 1, 0, allStates.get(16));
        pw.printf("AFTER 17 STATES (Linear->HashMap): bits=%d, palette=%s%n",
                bitsField.getInt(container), paletteField.get(container).getClass().getSimpleName());

        // Grow to 256 states
        for (int i = 17; i < 256; i++) {
            int x = i % 16;
            int z = (i / 16) % 16;
            int y = i / 256;
            container.func_186013_a(x, y, z, allStates.get(i));
        }
        pw.printf("AFTER 256 STATES: bits=%d, palette=%s%n",
                bitsField.getInt(container), paletteField.get(container).getClass().getSimpleName());

        // 257th state -> triggers HashMap (8-bit) -> Global Palette (> 8 bit)
        container.func_186013_a(1, 1, 0, allStates.get(256));
        pw.printf("AFTER 257 STATES (HashMap->Global): bits=%d, palette=%s%n",
                bitsField.getInt(container), paletteField.get(container).getClass().getSimpleName());

        pw.close();
        System.out.println("Generated " + outFile.getPath());
    }
}
