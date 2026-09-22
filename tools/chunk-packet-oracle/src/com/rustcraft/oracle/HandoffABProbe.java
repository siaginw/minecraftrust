package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.nio.ByteBuffer;

/**
 * M1.4-R2: HANDOFF-A vs HANDOFF-0 probe. Per-phase timing + byte equality
 * between the two handoffs (both must produce identical packet payloads).
 */
public final class HandoffABProbe {

    static final int WARMUP = 500, TRIALS = 3000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        long sink = 0;

        for (int f = 0; f < 8; f++) {
            M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(f);
            Chunk ch = M14RParityHarness.createMockChunk(fx);
            ExtendedBlockStorage[] sections = ch.func_76587_i();
            int mask = fx.mask;
            boolean full = (mask == 0xFFFF);
            byte[] biomes = full ? ch.func_76605_m() : null;

            ByteBuffer staging = NativeChunkPacket.getStagingForProbe();
            staging.clear();
            int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, mask, full, true, biomes);
            long stgAddr = NativeChunkPacket.getDirectBufferAddress((ByteBuffer) staging);

            int predicted = NativeChunkPacket.predictOutputLen(stgAddr, stagedLen);

            // baseline handoff-0 result via manual path
            io.netty.buffer.ByteBuf db = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(262144);
            long outAddr = NativeChunkPacket.getNettyBufferAddress(db);
            int w0 = NativeChunkPacket.encodeSections(stgAddr, stagedLen, outAddr, db.capacity());
            byte[] payload0 = new byte[w0];
            db.getBytes(0, payload0);
            db.release();

            // handoff-A result
            byte[] payloadA = new byte[predicted];
            int wA = NativeChunkPacket.encodeSectionsIntoArray(stgAddr, stagedLen, payloadA);
            boolean equal = wA == w0 && java.util.Arrays.equals(payload0, payloadA);

            // timing: handoff A (predict + alloc + critical encode)
            long tA0 = System.nanoTime();
            for (int i = 0; i < WARMUP; i++) {
                int pr = NativeChunkPacket.predictOutputLen(stgAddr, stagedLen);
                byte[] pl = new byte[pr];
                sink += NativeChunkPacket.encodeSectionsIntoArray(stgAddr, stagedLen, pl);
            }
            long best = Long.MAX_VALUE;
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                int pr = NativeChunkPacket.predictOutputLen(stgAddr, stagedLen);
                byte[] pl = new byte[pr];
                sink += NativeChunkPacket.encodeSectionsIntoArray(stgAddr, stagedLen, pl);
                long t1 = System.nanoTime();
                if (t1 - t0 < best) best = t1 - t0;
            }

            // timing: handoff 0 (acquire + encode + heap copy)
            long best0 = Long.MAX_VALUE;
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                io.netty.buffer.ByteBuf d2 = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(262144);
                long a2 = NativeChunkPacket.getNettyBufferAddress(d2);
                int wr = NativeChunkPacket.encodeSections(stgAddr, stagedLen, a2, d2.capacity());
                byte[] pl = new byte[wr];
                d2.getBytes(0, pl);
                d2.release();
                long t1 = System.nanoTime();
                if (t1 - t0 < best0) best0 = t1 - t0;
                sink += wr;
            }

            System.err.println(String.format(
                    "HANDOFF_PROBE det%d mask=%04x staged=%d predicted=%d written0=%d writtenA=%d equal=%s bestA=%.2fus best0=%.2fus",
                    f, mask, stagedLen, predicted, w0, wA, equal, best / 1000.0, best0 / 1000.0));
        }
        System.err.println("sink=" + sink);
    }
}
