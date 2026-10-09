package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.init.Bootstrap;

import java.nio.ByteBuffer;

/** sky=false path: manual staging + Rust encode, compare vs encodeJavaReference. */
public class ProbeSkyFalse {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.mask = 0x0001; fx.fullChunk = false; fx.withTE = false;
        fx.sectionState = new int[16];
        fx.sectionState[0] = 2;
        fx.seed = 42L;
        net.minecraft.world.chunk.Chunk chunk = M14RParityHarness.createMockChunk(fx);
        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.func_76587_i();

        // what runShadowOnPacket derives:
        boolean skyDerived = false;
        try {
            skyDerived = chunk.func_177412_p() != null
                    && chunk.func_177412_p().field_73011_w != null
                    && chunk.func_177412_p().field_73011_w.func_191066_m();
        } catch (Throwable ignored) {}
        System.out.println("skyDerived=" + skyDerived);

        boolean sky = false;
        ByteBuf javaRef = NativeChunkPacket.encodeJavaReference(sections, fx.mask, false, sky, null);
        System.out.println("javaRef(sky=false) len=" + javaRef.readableBytes());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, javaRef.readableBytes()); i++)
            sb.append(String.format("%02X ", javaRef.getByte(i)));
        System.out.println("javaRef[0..7]: " + sb);

        ByteBuffer staging = NativeChunkPacket.getStagingForProbe();
        staging.clear();
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, fx.mask, false, sky, null);
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe U = (sun.misc.Unsafe) uf.get(null);
        StringBuilder sbS = new StringBuilder();
        for (int i = 0; i < Math.min(12, stagedLen); i++)
            sbS.append(String.format("%02X ", U.getByte(sAddr + i)));
        System.out.println("staging[0..11]: " + sbS + " len=" + stagedLen);

        ByteBuf db = PooledByteBufAllocator.DEFAULT.directBuffer(65536);
        long oAddr = NativeChunkPacket.getDirectBufferAddress(db.internalNioBuffer(0, db.capacity()));
        int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, oAddr, db.capacity());
        db.getBytes(0, new byte[0]); // no-op
        byte[] payload = new byte[Math.max(0, written)];
        if (written > 0) db.getBytes(0, payload);
        StringBuilder sbN = new StringBuilder();
        for (int i = 0; i < Math.min(8, payload.length); i++)
            sbN.append(String.format("%02X ", payload[i]));
        System.out.println("native(sky=false) written=" + written + " [0..7]: " + sbN);

        boolean eq = written == javaRef.readableBytes();
        if (eq) {
            for (int i = 0; i < written; i++)
                if (payload[i] != javaRef.getByte(i)) { eq = false;
                    System.out.println("first diff at " + i + ": exp=" +
                        String.format("%02X", javaRef.getByte(i)) + " act=" + String.format("%02X", payload[i]));
                    break; }
        } else System.out.println("length mismatch");
        System.out.println("equal: " + eq);
        db.release();
        javaRef.release();
    }
}
