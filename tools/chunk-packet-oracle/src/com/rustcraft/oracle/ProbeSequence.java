package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;

import java.nio.ByteBuffer;

/** Reproduce probe-D failure: SHADOW first, then ON_EXPERIMENTAL. */
public class ProbeSequence {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.mask = 0x0001; fx.fullChunk = false; fx.withTE = false;
        fx.sectionState = new int[16];
        fx.sectionState[0] = 2;
        fx.seed = 42L;
        net.minecraft.world.chunk.Chunk chunk = M14RParityHarness.createMockChunk(fx);
        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.func_76587_i();

        // Step 1: vanilla reference
        NativeChunkPacket.setRuntimeMode("OFF");
        SPacketChunkData refP = new SPacketChunkData(chunk, fx.mask);
        byte[] refBytes = M14RParityHarness.serialize(refP);
        System.out.println("vanilla wire len=" + refBytes.length);

        // Step 2: SHADOW on same chunk (exactly what harness Phase 3 does)
        NativeChunkPacket.setRuntimeMode("SHADOW");
        SPacketChunkData shadowPkt = new SPacketChunkData();
        boolean r = NativeChunkPacket.populatePacket(shadowPkt, chunk, fx.mask);
        System.out.println("shadow returned " + r
                + " matches=" + NativeChunkPacket.M1_SHADOW_CONSECUTIVE_MATCHES.get()
                + " mismatches=" + NativeChunkPacket.M1_SHADOW_MISMATCHES.get());

        // Step 3: ON_EXPERIMENTAL immediately after
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        SPacketChunkData nat = new SPacketChunkData();
        boolean ok = NativeChunkPacket.populatePacket(nat, chunk, fx.mask);
        byte[] natBytes = M14RParityHarness.serialize(nat);
        System.out.println("on_experimental ok=" + ok + " wire len=" + natBytes.length);
        System.out.println("wire equal: " + java.util.Arrays.equals(refBytes, natBytes));

        // Step 4: manual staging path AFTER the shadow run, thread-local staging
        ByteBuffer staging = NativeChunkPacket.getStagingForProbe();
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        staging.clear();
        int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, fx.mask, false, true, null);
        ByteBuf db = PooledByteBufAllocator.DEFAULT.directBuffer(65536);
        long oAddr = NativeChunkPacket.getDirectBufferAddress(db.internalNioBuffer(0, db.capacity()));
        int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, oAddr, db.capacity());
        byte[] payload = new byte[written];
        db.getBytes(0, payload);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, payload.length); i++) sb.append(String.format("%02X ", payload[i]));
        System.out.println("step4 manual after shadow: written=" + written + " [0..7]: " + sb);
        db.release();

        // Step 5: compare step4 payload against vanilla section payload slice
        // vanilla wire: 4B x, 4B z, varint mask... find section bytes start by stripping header
        // (javaRef comparison suffices: identical encoding shape)
    }
}
