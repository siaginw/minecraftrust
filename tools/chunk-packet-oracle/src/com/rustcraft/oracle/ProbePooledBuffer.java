package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.init.Bootstrap;

import java.nio.ByteBuffer;

/** Does Rust-written data at internalNioBuffer address read back via getBytes? */
public class ProbePooledBuffer {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        // small staging payload: schema(1)+mask(2)+... just reuse real path quickly
        // Build staging via fresh NIO buffer (known good from ProbeShadowBytes)
        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.mask = 0x0001; fx.fullChunk = false; fx.withTE = false;
        fx.sectionState = new int[16];
        fx.sectionState[0] = 2;
        fx.seed = 42L;
        net.minecraft.world.chunk.Chunk chunk = M14RParityHarness.createMockChunk(fx);
        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.func_76587_i();

        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, fx.mask, false, true, null);

        // pooled output buffer, mirroring executeNativePopulation exactly
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(Math.max(65536, stagedLen + 1024));
        long outAddr = NativeChunkPacket.getDirectBufferAddress(directBuf.internalNioBuffer(0, directBuf.capacity()));
        System.out.println("pooled buffer class: " + directBuf.getClass().getName());
        System.out.println("outAddr=" + outAddr);

        int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, outAddr, directBuf.capacity());
        System.out.println("written=" + written);

        byte[] payload = new byte[written];
        directBuf.getBytes(0, payload);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, payload.length); i++) sb.append(String.format("%02X ", payload[i]));
        System.out.println("pooled getBytes[0..7]: " + sb);

        // also raw read at outAddr
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe U = (sun.misc.Unsafe) uf.get(null);
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < Math.min(8, written); i++) sb2.append(String.format("%02X ", U.getByte(outAddr + i)));
        System.out.println("raw@outAddr[0..7]:    " + sb2);

        // and Netty's own memoryAddress view
        try {
            java.lang.reflect.Field ma = directBuf.getClass().getSuperclass().getDeclaredField("memoryAddress");
            ma.setAccessible(true);
            long memAddr = ma.getLong(directBuf);
            StringBuilder sb3 = new StringBuilder();
            for (int i = 0; i < Math.min(8, written); i++) sb3.append(String.format("%02X ", U.getByte(memAddr + i)));
            System.out.println("raw@memoryAddress:    " + sb3 + " (memAddr=" + memAddr + ")");
        } catch (Throwable t) {
            System.out.println("memoryAddress read failed: " + t);
        }
        directBuf.release();
    }
}
