package com.rustcraft.oracle;
import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.nio.ByteBuffer;

public class ProbeShadowBytes {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.mask = 0x0001; fx.fullChunk = false; fx.withTE = false;
        fx.sectionState = new int[16];
        fx.sectionState[0] = 2; // one populated section

        fx.seed = 42L;
        Chunk chunk = M14RParityHarness.createMockChunk(fx);

        boolean sky = true;
        boolean full = false;
        ExtendedBlockStorage[] sections = chunk.func_76587_i();
        byte[] biomes = full ? chunk.func_76605_m() : null;

        // A. encodeJavaReference
        ByteBuf javaRef = NativeChunkPacket.encodeJavaReference(sections, fx.mask, full, sky, biomes);
        System.out.println("javaRef len=" + javaRef.readableBytes());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, javaRef.readableBytes()); i++)
            sb.append(String.format("%02X ", javaRef.getByte(i)));
        System.out.println("javaRef[0..15]: " + sb);

        // B. native path
        ByteBuffer staging = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, fx.mask, full, sky, biomes);
        System.out.println("stagedLen=" + stagedLen);
        sun.misc.Unsafe U;
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true); U = (sun.misc.Unsafe) uf.get(null);
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < Math.min(16, stagedLen); i++)
            sb2.append(String.format("%02X ", U.getByte(sAddr + i)));
        System.out.println("staging[0..15]: " + sb2);

        ByteBuffer out = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        long oAddr = NativeChunkPacket.getDirectBufferAddress(out);
        int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, oAddr, NativeChunkPacket.MAX_OUTPUT_CAPACITY);
        System.out.println("written=" + written);
        StringBuilder sb3 = new StringBuilder();
        for (int i = 0; i < Math.min(16, written); i++)
            sb3.append(String.format("%02X ", U.getByte(oAddr + i)));
        System.out.println("native[0..15]: " + sb3);

        // C. real vanilla for ground truth
        NativeChunkPacket.setRuntimeMode("OFF");
        SPacketChunkData refPacket = new SPacketChunkData(chunk, fx.mask);
        byte[] refBytes = M14RParityHarness.serialize(refPacket);
        System.out.println("vanilla wire len=" + refBytes.length);
        StringBuilder sb4 = new StringBuilder();
        // wire: varint chunkX, chunkZ, varint mask(1 byte here), then section data
        for (int i = 0; i < Math.min(16, refBytes.length); i++)
            sb4.append(String.format("%02X ", refBytes[i]));
        System.out.println("vanilla wire[0..15]: " + sb4);

        // D. native ON_EXPERIMENTAL full path
        long n0 = NativeChunkPacket.M1_NATIVE_PACKETS_TRANSMITTED.get();
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        SPacketChunkData nat = new SPacketChunkData();
        boolean ok = NativeChunkPacket.populatePacket(nat, chunk, fx.mask);
        byte[] natBytes = M14RParityHarness.serialize(nat);
        System.out.println("populatePacket=" + ok + " nativePacketsDelta="
                + (NativeChunkPacket.M1_NATIVE_PACKETS_TRANSMITTED.get() - n0));
        StringBuilder sb5 = new StringBuilder();
        for (int i = 0; i < Math.min(16, natBytes.length); i++)
            sb5.append(String.format("%02X ", natBytes[i]));
        System.out.println("native wire[0..15]: " + sb5);
        System.out.println("wire equal: " + java.util.Arrays.equals(refBytes, natBytes));
    }
}
