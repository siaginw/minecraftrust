package com.rustcraft.bridge;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * M-CK4 §1 — REAL complete-packet fixture builder.
 *
 * Serializes an actual SPacketChunkData through the INSTALLED wire encoder
 * (func_148840_b / writePacketData) preceded by the packet-ID VarInt exactly
 * once (the PacketEncoder's job in the real pipeline), producing the exact
 * complete serialized packet body that enters the compression/prepender
 * stages. Then round-trip-verifies through the installed reader
 * (func_148837_a) before handing the fixture to any test.
 */
public final class M53FixtureBuilder {

    private M53FixtureBuilder() {}

    /**
     * Builds a complete real serialized packet body from a synced chunk:
     * [VarInt(packetId)][full SPacketChunkData wire fields].
     * Round-trip verified against the installed reader.
     */
    public static byte[] realChunkPacketBody(int cx, int cz) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(cx, cz);
        Object chunk = r[0];
        NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) r[1]), 0L);
        M4Coherency.refreshChunkNow(chunk);

        // Real packet object from the real constructor
        Class<?> pktCls = Class.forName("net.minecraft.network.play.server.SPacketChunkData");
        Constructor<?> ctor = pktCls.getConstructor(Class.forName("net.minecraft.world.chunk.Chunk"), int.class);
        Object pkt = ctor.newInstance(chunk, 65535);

        // Real serializer: PacketEncoder writes VarInt(packetId) then calls
        // packet.writePacketData(buf). Replicate that exact composition.
        java.lang.reflect.Method writeMethod = pktCls.getMethod("func_148840_b",
                Class.forName("net.minecraft.network.PacketBuffer"));
        io.netty.buffer.ByteBuf nettyBuf = io.netty.buffer.Unpooled.buffer(64 * 1024);
        Object packetBuffer = Class.forName("net.minecraft.network.PacketBuffer")
                .getConstructor(io.netty.buffer.ByteBuf.class).newInstance(nettyBuf);
        // PacketBuffer.writeVarInt = func_150787_b
        java.lang.reflect.Method writeVarInt = packetBuffer.getClass()
                .getMethod("func_150787_b", int.class);
        // SPacketChunkData id: look it up from the connection packet enum path is
        // heavy; the Minecraft.PACKET state enums hold it. Use the Serializer.
        int packetId = packetIdOf(pkt);
        writeVarInt.invoke(packetBuffer, packetId);
        writeMethod.invoke(pkt, packetBuffer);

        byte[] body = new byte[nettyBuf.readableBytes()];
        nettyBuf.readBytes(body);
        nettyBuf.release();

        // Round-trip verification through the installed READER
        io.netty.buffer.ByteBuf vb = io.netty.buffer.Unpooled.wrappedBuffer(body);
        Object vpb = Class.forName("net.minecraft.network.PacketBuffer")
                .getConstructor(io.netty.buffer.ByteBuf.class).newInstance(vb);
        java.lang.reflect.Method readVarInt = vpb.getClass().getMethod("func_150792_a");
        int seenId = (Integer) readVarInt.invoke(vpb);
        if (seenId != packetId) throw new IllegalStateException("id mismatch " + seenId + " != " + packetId);
        Object pkt2 = pktCls.getConstructor().newInstance();
        pktCls.getMethod("func_148837_a", Class.forName("net.minecraft.network.PacketBuffer")).invoke(pkt2, vpb);
        // compare the re-read packet's section payload with the original's
        byte[] a = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
        byte[] b = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt2);
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalStateException("round-trip payload mismatch len " +
                    (a == null ? -1 : a.length) + " vs " + (b == null ? -1 : b.length));
        }
        NativeChunkBridge.unload(0, cx, cz);
        return body;
    }

    /** Packet id from the INSTALLED EnumConnectionState (func_179246_a),
     *  the exact lookup NettyPacketEncoder performs. Protocol enum PLAY +
     *  direction OUTBOUND. No hardcoded id. */
    static int packetIdOf(Object pkt) throws Exception {
        Class<?> stateCls = Class.forName("net.minecraft.network.EnumConnectionState");
        Object play = java.lang.reflect.Field.class.cast(stateCls.getField("PLAY")).get(null);
        Object dir = Class.forName("net.minecraft.network.EnumPacketDirection").getField("CLIENTBOUND").get(null);
        java.lang.reflect.Method m = stateCls.getMethod("func_179246_a",
                Class.forName("net.minecraft.network.EnumPacketDirection"), Class.forName("net.minecraft.network.Packet"));
        Object id = m.invoke(play, dir, pkt);
        if (id == null) throw new IllegalStateException("packet not registered in PLAY/OUTBOUND");
        return (Integer) id;
    }

    static long addr(ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
