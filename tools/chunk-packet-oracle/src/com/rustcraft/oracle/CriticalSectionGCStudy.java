package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1.4-R2 §4: GC/safepoint impact of GetPrimitiveArrayCritical bursts.
 * Drives high-rate critical-section encodes at 1/4/8/16 sections and reports
 * young-GC counts, total GC time, and critical-section duration percentiles.
 */
public final class CriticalSectionGCStudy {

    static final int BURST = 200_000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        for (int f : new int[]{0, 1, 4, 6, 7}) {
            M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(f);
            Chunk ch = M14RParityHarness.createMockChunk(fx);
            ExtendedBlockStorage[] sections = ch.func_76587_i();
            int mask = fx.mask;
            boolean full = (mask == 0xFFFF);
            byte[] biomes = full ? ch.func_76605_m() : null;

            ByteBuffer staging = NativeChunkPacket.getStagingForProbe();
            staging.clear();
            int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, mask, full, true, biomes);
            long stgAddr = NativeChunkPacket.getDirectBufferAddress(staging);
            int predicted = NativeChunkPacket.predictOutputLen(stgAddr, stagedLen);
            // warmup
            for (int i = 0; i < 20_000; i++) {
                byte[] pl = new byte[predicted];
                NativeChunkPacket.encodeSectionsIntoArray(stgAddr, stagedLen, pl);
            }
            java.lang.management.GarbageCollectorMXBean ygc = null;
            for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (b.getName().contains("ParNew") || b.getName().contains("Scavenge") || b.getName().contains("PS Scavenge")) ygc = b;
            }
            long gcBefore = ygc != null ? ygc.getCollectionCount() : -1;
            long tBefore = ygc != null ? ygc.getCollectionTime() : -1;

            long[] durs = new long[BURST];
            long t0 = System.nanoTime();
            long sink = 0;
            for (int i = 0; i < BURST; i++) {
                byte[] pl = new byte[predicted];
                long c0 = System.nanoTime();
                sink += NativeChunkPacket.encodeSectionsIntoArray(stgAddr, stagedLen, pl);
                durs[i] = System.nanoTime() - c0;
            }
            long wall = System.nanoTime() - t0;
            long gcAfter = ygc != null ? ygc.getCollectionCount() : -1;
            long tAfter = ygc != null ? ygc.getCollectionTime() : -1;

            java.util.Arrays.sort(durs);
            System.err.println(String.format(
                "GC_STUDY fixture=det%d sections=%d payload=%dB burst=%d wall=%.0fms p50=%.2fus p99=%.2fus p999=%.2fus youngGC=%d gcTimeMs=%d sink=%d",
                f, Integer.bitCount(mask), predicted, BURST, wall / 1e6,
                durs[BURST / 2] / 1e3, durs[(int) (BURST * 0.99)] / 1e3, durs[(int) (BURST * 0.999)] / 1e3,
                (gcAfter - gcBefore), (tAfter - tBefore), sink));
        }
        System.err.println("done");
    }
}
