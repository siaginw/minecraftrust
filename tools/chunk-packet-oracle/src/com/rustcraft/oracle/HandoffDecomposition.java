package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.nio.ByteBuffer;

/** M1.4-R2 §8: where does SCOPE-C-N-HA time go? Per-phase decomposition of
 *  populatePacket(HANDOFF-A) vs the Java constructor on the same fixtures. */
public final class HandoffDecomposition {

    static final int WARM = 3000, TRIALS = 12000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");
        long sink = 0;

        for (int f : new int[]{1, 4, 6, 21}) { // det1 16sec, det4, det6 sparse, sweep4_1
            M14RParityHarness.Fixture fx;
            Chunk ch;
            try {
                fx = M14RParityHarness.buildDeterministic(f % 8);
                ch = M14RParityHarness.createMockChunk(fx);
                if (f == 21) { fx = M14RParityHarness.buildDeterministic(1); ch = M14RParityHarness.createMockChunk(fx); }
            } catch (Exception e) { continue; }
            int mask = fx.mask;
            ExtendedBlockStorage[] sections = ch.func_76587_i();
            boolean full = (mask == 0xFFFF);
            byte[] biomes = full ? ch.func_76605_m() : null;

            // warmup both
            for (int i = 0; i < WARM; i++) {
                SPacketChunkData pJ = new SPacketChunkData(ch, mask); sink += pJ.func_149274_i() ? 1 : 0;
                SPacketChunkData pN = new SPacketChunkData(ch, 0);
                sink += NativeChunkPacket.populatePacket(pN, ch, mask) ? 1 : 0;
            }

            // whole-path timings
            long[] js = new long[TRIALS], ns_ = new long[TRIALS];
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                SPacketChunkData pJ = new SPacketChunkData(ch, mask);
                long t1 = System.nanoTime();
                sink += pJ.func_149274_i() ? 1 : 0;
                js[i] = t1 - t0;

                long u0 = System.nanoTime();
                SPacketChunkData pN = new SPacketChunkData(ch, 0);
                boolean ok = NativeChunkPacket.populatePacket(pN, ch, mask);
                long u1 = System.nanoTime();
                sink += ok ? 1 : 0;
                ns_[i] = u1 - u0;
            }
            java.util.Arrays.sort(js); java.util.Arrays.sort(ns_);
            long bestJ = js[0], bestN = ns_[0];
            long p50J = js[TRIALS / 2], p50N = ns_[TRIALS / 2];
            long p99J = js[(int) (TRIALS * 0.99)], p99N = ns_[(int) (TRIALS * 0.99)];

            // phases: staging, predict, encode-into-array, finish (fields+TE)
            ByteBuffer stg = NativeChunkPacket.getStagingForProbe();
            long bestStg = Long.MAX_VALUE, bestPred = Long.MAX_VALUE, bestEnc = Long.MAX_VALUE, bestFin = Long.MAX_VALUE;
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                stg.clear();
                int stagedLen = NativeChunkPacket.populateStagingBuffer(stg, sections, mask, full, true, biomes);
                long t1 = System.nanoTime();
                long addr = NativeChunkPacket.getDirectBufferAddress(stg);
                long u0 = System.nanoTime();
                int pred = NativeChunkPacket.predictOutputLen(addr, stagedLen);
                long u1 = System.nanoTime();
                byte[] pl = new byte[pred];
                long v0 = System.nanoTime();
                sink += NativeChunkPacket.encodeSectionsIntoArray(addr, stagedLen, pl);
                long v1 = System.nanoTime();
                SPacketChunkData pN2 = new SPacketChunkData(ch, 0);
                long w0 = System.nanoTime();
                sink += NativeChunkPacket.finishForProbe(pN2, ch, mask, sections, pl) ? 1 : 0;
                long w1 = System.nanoTime();
                if (t1 - t0 < bestStg) bestStg = t1 - t0;
                if (u1 - u0 < bestPred) bestPred = u1 - u0;
                if (v1 - v0 < bestEnc) bestEnc = v1 - v0;
                if (w1 - w0 < bestFin) bestFin = w1 - w0;
            }

            System.err.println(String.format(
                "DECOMP f=%d mask=%04x java p50=%.2f p99=%.2f best=%.2f | NATIVE-HA p50=%.2f p99=%.2f best=%.2f | stage=%.2f predict=%.2f encode=%.2f finish=%.2f",
                f, mask, p50J / 1e3, p99J / 1e3, bestJ / 1e3,
                p50N / 1e3, p99N / 1e3, bestN / 1e3,
                bestStg / 1e3, bestPred / 1e3, bestEnc / 1e3, bestFin / 1e3));
        }
        System.err.println("sink=" + sink);
    }
}
