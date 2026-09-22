package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.init.Bootstrap;

import java.nio.ByteBuffer;

/** Bisect the zero-output bug: staging source x output type x javaRef held. */
public class ProbeBisect {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.mask = 0x0001; fx.fullChunk = false; fx.withTE = false;
        fx.sectionState = new int[16];
        fx.sectionState[0] = 2;
        fx.seed = 42L;
        net.minecraft.world.chunk.Chunk chunk = M14RParityHarness.createMockChunk(fx);
        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.func_76587_i();

        // V1: fresh staging, fresh NIO out, no javaRef
        run("V1 fresh-stag fresh-out no-ref", false, false, false, sections, fx.mask);
        // V2: TL staging, pooled out, no javaRef
        run("V2 tl-stag   pooled  no-ref", true, false, false, sections, fx.mask);
        // V3: TL staging, pooled out, javaRef held (shadow repro)
        run("V3 tl-stag   pooled  ref-held", true, false, true, sections, fx.mask);
        // V4: TL staging, fresh NIO out, javaRef held
        run("V4 tl-stag   fresh   ref-held", true, true, true, sections, fx.mask);
        // V5: fresh staging, pooled out, javaRef held
        run("V5 fresh-stag pooled  ref-held", false, false, true, sections, fx.mask);
    }

    static void run(String label, boolean tlStaging, boolean freshOut, boolean holdRef,
                    net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections, int mask) throws Exception {
        ByteBuf javaRef = holdRef
                ? NativeChunkPacket.encodeJavaReference(sections, mask, false, false, null)
                : null;

        ByteBuffer staging = tlStaging
                ? NativeChunkPacket.getStagingForProbe()
                : ByteBuffer.allocateDirect(NativeChunkPacket.MAX_STAGING_CAPACITY);
        staging.clear();
        long sAddr = NativeChunkPacket.getDirectBufferAddress(staging);
        int stagedLen = NativeChunkPacket.populateStagingBuffer(staging, sections, mask, false, false, null);

        ByteBuffer outNio = null;
        ByteBuf db = null;
        long oAddr;
        int cap;
        if (freshOut) {
            outNio = ByteBuffer.allocateDirect(NativeChunkPacket.MAX_OUTPUT_CAPACITY);
            oAddr = NativeChunkPacket.getDirectBufferAddress(outNio);
            cap = outNio.capacity();
        } else {
            db = PooledByteBufAllocator.DEFAULT.directBuffer(65536);
            oAddr = NativeChunkPacket.getDirectBufferAddress(db.internalNioBuffer(0, db.capacity()));
            cap = db.capacity();
            System.out.println("  [addr] db=" + memAddr(db) + " internalNioBuffer=" + oAddr);
        }
        if (holdRef) System.out.println("  [addr] javaRef buf=" + memAddr(javaRef));

        int written = NativeChunkPacket.encodeSections(sAddr, stagedLen, oAddr, cap);

        byte[] first8 = new byte[8];
        if (db != null) db.getBytes(0, first8);
        else { java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true); sun.misc.Unsafe U = (sun.misc.Unsafe) uf.get(null);
            for (int i = 0; i < 8; i++) first8[i] = U.getByte(oAddr + i); }
        StringBuilder sb = new StringBuilder();
        for (byte b : first8) sb.append(String.format("%02X ", b));
        System.out.printf("%-32s written=%d first8=[%s]%n", label, written, sb);

        if (db != null) db.release();
        if (javaRef != null) javaRef.release();
    }

    static long memAddr(ByteBuf buf) {
        try {
            Class<?> c = buf.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("memoryAddress");
                    f.setAccessible(true);
                    return f.getLong(buf);
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            // fallback: internalNioBuffer address
            return NativeChunkPacket.getDirectBufferAddress(buf.internalNioBuffer(0, buf.capacity()));
        } catch (Throwable t) { return -1; }
    }
}
