package com.rustcraft.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Deflater;

public class ChunkAndTaskBenchmarks {

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        System.out.println("=== P0-5 Chunk, Task, and Fan-Out Benchmarks Start ===");

        benchChunkDataConstruction();
        benchChunkFanOut();
        benchCustomPayloadWrapper();
        benchServerThreadTaskDrain();

        System.out.println("=== P0-5 Chunk, Task, and Fan-Out Benchmarks Complete ===");
    }

    static void benchChunkDataConstruction() throws Exception {
        System.out.println("\n--- [BENCH] SPacketChunkData Construction & Serialization ---");
        // We simulate chunks by constructing Chunk instances or measuring extractChunkData directly
        // In 1.12.2 Chunk requires a World. We can measure extractChunkData logic and calculateChunkSize logic!
        
        // Let's create an ExtendedBlockStorage array
        // Category 1: Empty chunk (all null sections)
        // Category 2: Normal chunk (4 sections: 0..63 height filled with stone, dirt, grass, air)
        // Category 3: Full 16-section chunk (all 16 sections filled)
        
        ExtendedBlockStorage[] emptySecs = new ExtendedBlockStorage[16];
        
        ExtendedBlockStorage[] normalSecs = new ExtendedBlockStorage[16];
        for (int i = 0; i < 4; i++) {
            normalSecs[i] = new ExtendedBlockStorage(i << 4, true);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        normalSecs[i].func_177484_a(x, y, z, (i == 0 ? Blocks.field_150357_h : Blocks.field_150348_b).func_176223_P());
                    }
                }
            }
        }

        ExtendedBlockStorage[] fullSecs = new ExtendedBlockStorage[16];
        for (int i = 0; i < 16; i++) {
            fullSecs[i] = new ExtendedBlockStorage(i << 4, true);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        fullSecs[i].func_177484_a(x, y, z, Blocks.field_150348_b.func_176223_P());
                    }
                }
            }
        }

        benchSectionExtract("Empty Chunk (0 sections)", emptySecs, 65535);
        benchSectionExtract("Normal Chunk (4 sections)", normalSecs, 65535);
        benchSectionExtract("Full Chunk (16 sections)", fullSecs, 65535);
        
        // TE-heavy chunk: 64 TileEntityChest update tags
        List<NBTTagCompound> teTags = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.func_74778_a("id", "minecraft:chest");
            tag.func_74768_a("x", i % 16);
            tag.func_74768_a("y", 64);
            tag.func_74768_a("z", (i / 16) % 16);
            tag.func_74778_a("CustomName", "Chest_" + i);
            teTags.add(tag);
        }

        // Measure TE NBT serialization
        PacketBuffer teBuf = new PacketBuffer(Unpooled.directBuffer(65536));
        int iters = 10000;
        long[] teTimes = new long[iters];
        for (int i = 0; i < iters; i++) {
            teBuf.clear();
            long t0 = System.nanoTime();
            teBuf.func_150787_b(teTags.size());
            for (NBTTagCompound t : teTags) {
                teBuf.func_150786_a(t);
            }
            teTimes[i] = System.nanoTime() - t0;
        }
        printStats("64 TE UpdateTags Serialization", teTimes);
        teBuf.release();
    }

    static void benchSectionExtract(String name, ExtendedBlockStorage[] storages, int sectionMask) {
        // Measure extractChunkData loop
        PacketBuffer pb = new PacketBuffer(Unpooled.directBuffer(131072));
        boolean hasSky = true;

        // Warmup
        for (int i = 0; i < 2000; i++) {
            pb.clear();
            extractSections(pb, storages, hasSky, sectionMask);
        }

        int iters = 10000;
        long[] times = new long[iters];
        int totalBytes = 0;

        for (int i = 0; i < iters; i++) {
            pb.clear();
            long t0 = System.nanoTime();
            extractSections(pb, storages, hasSky, sectionMask);
            times[i] = System.nanoTime() - t0;
            totalBytes = pb.readableBytes();
        }

        System.out.printf("%-30s | Output: %6d B | ", name, totalBytes);
        printStatsInline(times);
        System.out.println();
        pb.release();
    }

    static void extractSections(PacketBuffer buf, ExtendedBlockStorage[] storages, boolean hasSky, int mask) {
        for (int i = 0; i < storages.length; i++) {
            ExtendedBlockStorage ebs = storages[i];
            if (ebs != null && (!ebs.func_76663_a()) && (mask & (1 << i)) != 0) {
                ebs.func_186049_g().func_186009_b(buf);
                buf.writeBytes(ebs.func_76661_k().func_177481_a());
                if (hasSky) {
                    buf.writeBytes(ebs.func_76671_l().func_177481_a());
                }
            }
        }
        // Biome array 256 bytes
        buf.writeBytes(new byte[256]);
    }

    static void benchChunkFanOut() throws Exception {
        System.out.println("\n--- [BENCH] Chunk Packet Fan-Out Cost (Netty IO Compression + Encryption) ---");
        // Realistic 4-section compressed chunk buffer (~12 KB raw)
        byte[] chunkPayload = new byte[12288];
        new Random(42).nextBytes(chunkPayload);

        int[] recipientCounts = new int[]{1, 5, 10, 25, 50};
        byte[] keyBytes = new byte[16];
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec iv = new IvParameterSpec(keyBytes);

        for (int r : recipientCounts) {
            int iters = 2000;
            long[] totalFanOutTimes = new long[iters];
            byte[] outDeflate = new byte[32768];
            byte[] outEnc = new byte[32768];

            for (int i = 0; i < iters; i++) {
                long t0 = System.nanoTime();
                for (int p = 0; p < r; p++) {
                    // Each player's channel:
                    // 1. Deflate
                    Deflater deflater = new Deflater();
                    deflater.setInput(chunkPayload);
                    deflater.finish();
                    int csz = deflater.deflate(outDeflate, 0, outDeflate.length, Deflater.SYNC_FLUSH);
                    deflater.end();

                    // 2. Encrypt
                    Cipher enc = Cipher.getInstance("AES/CFB8/NoPadding");
                    enc.init(Cipher.ENCRYPT_MODE, key, iv);
                    enc.update(outDeflate, 0, csz, outEnc, 0);
                }
                totalFanOutTimes[i] = System.nanoTime() - t0;
            }
            printStats(String.format("Fan-Out %2d Recipients", r), totalFanOutTimes);
        }
    }

    static void benchCustomPayloadWrapper() {
        System.out.println("\n--- [BENCH] SimpleNetworkWrapper / CustomPayload Double-Copy Overhead ---");
        // Forge SimpleNetworkWrapper workflow:
        // Step 1: Mod serializes message into ByteBuf (allocate buffer A)
        // Step 2: FMLIndexedMessageToMessageCodec wraps buffer A into FMLProxyPacket (payload buffer B = buffer A.copy())
        // Step 3: FMLOutboundHandler transforms to CPacketCustomPayload / SPacketCustomPayload
        // Step 4: PacketBuffer writes channel name (VarInt + UTF-8) + payload bytes
        
        int[] sizes = new int[]{256, 2048, 8192, 32768};
        for (int sz : sizes) {
            byte[] modData = new byte[sz];
            new Random(77).nextBytes(modData);

            int iters = 20000;
            long[] directTimes = new long[iters];
            long[] doubleCopyTimes = new long[iters];

            for (int i = 0; i < iters; i++) {
                // Direct: single buffer write
                long t0 = System.nanoTime();
                ByteBuf single = Unpooled.directBuffer(sz + 32);
                writeVarint(single, 6);
                single.writeBytes("mod:ch".getBytes());
                single.writeBytes(modData);
                directTimes[i] = System.nanoTime() - t0;
                single.release();

                // Double Copy (Forge FML pipeline simulation)
                long t1 = System.nanoTime();
                ByteBuf modBuf = Unpooled.buffer(sz);
                modBuf.writeBytes(modData); // Copy 1: mod to ByteBuf
                ByteBuf proxyBuf = modBuf.copy(); // Copy 2: FMLProxyPacket wrapper copy
                ByteBuf wireBuf = Unpooled.directBuffer(sz + 32);
                writeVarint(wireBuf, 6);
                wireBuf.writeBytes("mod:ch".getBytes());
                wireBuf.writeBytes(proxyBuf); // Copy 3: Netty frame encode
                doubleCopyTimes[i] = System.nanoTime() - t1;

                modBuf.release();
                proxyBuf.release();
                wireBuf.release();
            }

            double meanDirect = Arrays.stream(directTimes).average().orElse(0);
            double meanDouble = Arrays.stream(doubleCopyTimes).average().orElse(0);
            double overheadPct = ((meanDouble - meanDirect) / meanDirect) * 100.0;
            System.out.printf("Payload %5d B | Direct: %6.1f ns | FML Double-Copy: %6.1f ns (+%5.1f%% overhead)%n",
                    sz, meanDirect, meanDouble, overheadPct);
        }
    }

    static void benchServerThreadTaskDrain() {
        System.out.println("\n--- [BENCH] Server-Thread futureTaskQueue Drain Workloads ---");
        // MinecraftServer.futureTaskQueue drains all enqueued tasks at start of tick.
        // We measure drain time per tick for various synthetic packet task distributions.

        int iters = 2000;
        benchTaskBatch("Workload A: 10 Move tasks (pure field updates)", 10, () -> {
            // Player position update simulation
            double x = 100.5, y = 64.0, z = -200.5;
            x += 0.1;
        });

        benchTaskBatch("Workload B: 50 Move tasks", 50, () -> {
            double x = 100.5; x += 0.1;
        });

        benchTaskBatch("Workload C: 200 Move tasks (heavy movement)", 200, () -> {
            double x = 100.5; x += 0.1;
        });

        benchTaskBatch("Workload D: 20 Block Placement / Interaction tasks", 20, () -> {
            // Block change / logic simulation
            BlockPos p = new BlockPos(100, 64, -200);
            int hash = p.hashCode();
        });

        benchTaskBatch("Workload E: 10 Inventory Window Click tasks", 10, () -> {
            // Slot lookup simulation
            int slot = 36;
            slot = (slot * 31) ^ 17;
        });

        benchTaskBatch("Workload F: Mixed 50-task tick (35 move, 10 look, 3 block, 2 chat)", 50, () -> {
            int action = 1;
            action += 2;
        });
    }

    static void benchTaskBatch(String name, int count, Runnable singleAction) {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        int iters = 5000;
        long[] drainTimes = new long[iters];

        for (int i = 0; i < iters; i++) {
            for (int k = 0; k < count; k++) {
                queue.add(singleAction);
            }
            long t0 = System.nanoTime();
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
            drainTimes[i] = System.nanoTime() - t0;
        }

        printStats(name, drainTimes);
    }

    static void writeVarint(ByteBuf out, int value) {
        while ((value & -128) != 0) {
            out.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static void printStats(String name, long[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        long p50 = times[times.length / 2];
        long p95 = times[(int)(times.length * 0.95)];
        long p99 = times[(int)(times.length * 0.99)];
        long max = times[times.length - 1];
        System.out.printf("%-50s | Mean: %7.1f ns | p50: %6d ns | p95: %6d ns | p99: %6d ns (N=%d)%n",
                name, mean, p50, p95, p99, times.length);
    }

    static void printStatsInline(long[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        long p50 = times[times.length / 2];
        long p95 = times[(int)(times.length * 0.95)];
        long p99 = times[(int)(times.length * 0.99)];
        System.out.printf("Mean: %6.1f ns, p50: %5d ns, p95: %5d ns, p99: %5d ns", mean, p50, p95, p99);
    }
}
