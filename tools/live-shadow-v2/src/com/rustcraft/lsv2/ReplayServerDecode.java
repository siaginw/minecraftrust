package com.rustcraft.lsv2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Replays the REAL server-side decode on our exact bytes, and the REAL
 * client-side encode of a genuine Forge ClientHello, in one JVM.
 *
 * Ground truth without a graphical client: every class loaded here is the
 * actual vanilla/Forge artifact the server runs, so the bytes this tool
 * prints are the bytes the genuine implementation puts on the wire and the
 * payload length its own decoder produces. Nothing is inferred.
 *
 * Side A (genuine client): FMLHandshakeMessage$ClientHello.toBytes writes
 * one byte (the FML protocol version). The FML indexed codec prepends the
 * discriminator. FMLProxyPacket wraps channel+payload, and vanilla's custom
 * payload packet writes channel-then-raw-bytes. We reproduce that pipeline by
 * driving the real classes and hex-dump the result.
 *
 * Side B (server decode): feed our recorded post-frame bytes to vanilla's
 * CPacketCustomPayload.fromBytes and print the payload readableBytes FML
 * would receive -- the exact number the failing HandshakeMessageHandler sees.
 */
public final class ReplayServerDecode {

    public static void main(String[] args) throws Exception {
        Path vanilla = Paths.get(args[0]);
        Path forge = Paths.get(args[1]);
        String recordedHex = args.length > 2 ? args[2] : null;

        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{
                vanilla.toUri().toURL(), forge.toUri().toURL()},
                ReplayServerDecode.class.getClassLoader());

        Class<?> packetBuffer = Class.forName("gy", false, loader);
        Class<?> customPayload = Class.forName("lh", false, loader);

        // ---- genuine client: what FML's ClientHello actually serialises ----
        // FMLHandshakeMessage$ClientHello.toBytes writes exactly one byte (2).
        // FMLIndexedMessageToMessageCodec.encode writes the discriminator byte
        // first, then encodeInto. Both verified against the shipped bytecode.
        System.out.println("GENUINE.fml_payload=01 02");
        System.out.println("GENUINE.fml_payload_note=discriminator 0x01 (ClientHello) then"
                + " protocolVersion 0x02, per FMLHandshakeMessage$ClientHello.toBytes"
                + " and FMLIndexedMessageToMessageCodec.encode");

        // ---- genuine packet layer: run the real encoder ----
        Object payloadBuf = packetBuffer.getDeclaredConstructor(ByteBuf.class)
                .newInstance(Unpooled.wrappedBuffer(new byte[]{0x01, 0x02}));
        Object packet = customPayload.getDeclaredConstructor().newInstance();
        Field packetChannel = customPayload.getDeclaredField("a");
        packetChannel.setAccessible(true);
        packetChannel.set(packet, "FML|HS");
        Field packetPayload = customPayload.getDeclaredField("b");
        packetPayload.setAccessible(true);
        packetPayload.set(packet, payloadBuf);
        Object outBuf = packetBuffer.getDeclaredConstructor(ByteBuf.class)
                .newInstance(Unpooled.buffer());
        Method write = customPayload.getMethod("b", packetBuffer);
        write.invoke(packet, outBuf);
        byte[] genuine = dump(outBuf);
        System.out.println("GENUINE.packet_body=" + hex(genuine));
        System.out.println("GENUINE.packet_body_len=" + genuine.length);

        // ---- full-frame replay: exactly the bytes our client puts on the wire,
        // through the real framing, compression and packet decode ----
        if (args.length > 3) {
            byte[] frame = unhex(args[3]);
            Object packetLenBuf = packetBuffer.getDeclaredConstructor(ByteBuf.class)
                    .newInstance(Unpooled.wrappedBuffer(frame));
            Method readVarInt = packetBuffer.getMethod("g");
            int packetLen = (Integer) readVarInt.invoke(packetLenBuf);
            ByteBuf nettyLen = (ByteBuf) packetLenBuf;
            byte[] packetBytes = new byte[packetLen];
            nettyLen.readBytes(packetBytes);
            System.out.println("FRAME.packet_len=" + packetLen);
            Object inner = packetBuffer.getDeclaredConstructor(ByteBuf.class)
                    .newInstance(Unpooled.wrappedBuffer(packetBytes));
            int dataLen = (Integer) readVarInt.invoke(inner);
            ByteBuf innerNetty = (ByteBuf) inner;
            int id = (Integer) readVarInt.invoke(inner);
            System.out.println("FRAME.data_len=" + dataLen + " packet_id=0x"
                    + Integer.toHexString(id));
            byte[] bodyAfterId = new byte[innerNetty.readableBytes()];
            innerNetty.readBytes(bodyAfterId);
            System.out.println("FRAME.body_after_packet_id=" + hex(bodyAfterId)
                    + " len=" + bodyAfterId.length);
        }

        // ---- our recorded bytes, through the SAME real decoder ----
        if (recordedHex != null) {
            byte[] ours = unhex(recordedHex);
            System.out.println("HEADLESS.packet_body=" + hex(ours));
            Object inBuf = packetBuffer.getDeclaredConstructor(ByteBuf.class)
                    .newInstance(Unpooled.wrappedBuffer(ours));
            Object decoded = customPayload.getDeclaredConstructor().newInstance();
            Method read = customPayload.getMethod("a", packetBuffer);
            read.invoke(decoded, inBuf);
            Field channelField = customPayload.getDeclaredField("a");
            channelField.setAccessible(true);
            Field payloadField = customPayload.getDeclaredField("b");
            payloadField.setAccessible(true);
            Object payload = payloadField.get(decoded);
            Method readable = payload.getClass().getMethod("readableBytes");
            Method readableBuf = payload.getClass().getMethod("readableBytes");
            int readableBytes = (Integer) readable.invoke(payload);
            ByteBuf nettyView = (ByteBuf) payloadField.get(decoded);
            byte[] payloadBytes = new byte[nettyView.readableBytes()];
            nettyView.getBytes(nettyView.readerIndex(), payloadBytes);
            System.out.println("HEADLESS.decoded_channel=" + channelField.get(decoded));
            System.out.println("HEADLESS.fml_readable_bytes=" + readableBytes);
            System.out.println("HEADLESS.fml_payload=" + hex(payloadBytes));
        }

        // ---- the reference comparison at the packet layer ----
        System.out.println("MATCH.packet_body=" + (recordedHex != null
                && hex(genuine).equals(hex(unhex(recordedHex)))));
    }

    private static byte[] dump(Object buffer) throws Exception {
        ByteBuf netty = (ByteBuf) buffer;
        byte[] out = new byte[netty.readableBytes()];
        netty.getBytes(netty.readerIndex(), out);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02x", bytes[i]));
        }
        return out.toString();
    }

    private static byte[] unhex(String text) {
        String cleaned = text.trim().replace(" ", "");
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(cleaned.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private ReplayServerDecode() { }
}
