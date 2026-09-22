package com.rustcraft.interop;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * M2.0 interop harness: Rust outbound packet compression component vs the
 * ACTUAL Minecraft/Netty reference decompressor.
 *
 * Typed binding: one exported JNI symbol per function
 * (Java_com_rustcraft_bridge_CompressionInterop_compressNative) — no
 * function-table offsets, and no GetPrimitiveArrayCritical anywhere: the
 * harness passes explicit direct-buffer addresses it owns; the native side
 * forms slices for the call duration only.
 *
 * Reference decoder: vanilla net.minecraft.network.NettyCompressionDecoder
 * (the exact class installed in the server pipeline) driven through a Netty
 * EmbeddedChannel, fed the VarInt-framed compressed frames the real pipeline
 * produces. Requires exact byte recovery AND successful stream completion.
 */
public final class CompressionInterop {

    private static boolean nativeLoaded = false;
    public static String backendNote = "unknown";

    static {
        try {
            System.loadLibrary("rustcraft_ffi");
            nativeLoaded = true;
        } catch (Throwable t) {
            System.err.println("[M2J] native lib load failed: " + t);
        }
    }

    /** JNI export from crates/ffi (typed per-symbol binding). */
    private static native int compressNative(long inAddr, int inLen, long outAddr, int outCap);

    public static boolean isNativeLoaded() { return nativeLoaded; }

    private static long address(ByteBuffer b) {
        try {
            Method m = b.getClass().getMethod("address");
            m.setAccessible(true);
            return (Long) m.invoke(b);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot get direct buffer address", t);
        }
    }

    /** Compress into a fresh direct output buffer sized to the safe bound. */
    public static byte[] rustCompress(byte[] input) {
        if (!nativeLoaded) throw new IllegalStateException("native not loaded");
        ByteBuffer in = ByteBuffer.allocateDirect(Math.max(1, input.length));
        if (input.length > 0) in.put(input).flip();
        long bound = input.length + (input.length / 8192 + 2) * 5L + 16;
        ByteBuffer out = ByteBuffer.allocateDirect((int) bound);
        int n = compressNative(address(in), input.length, address(out), (int) bound);
        if (n < 0) throw new IllegalStateException("compressNative error code " + n);
        byte[] res = new byte[n];
        for (int i = 0; i < n; i++) res[i] = out.get(i);
        return res;
    }

    /** Expose capacity-error path for tests: returns native code. */
    public static int rustCompressCode(byte[] input, int outCapOverride) {
        if (!nativeLoaded) throw new IllegalStateException("native not loaded");
        ByteBuffer in = ByteBuffer.allocateDirect(Math.max(1, input.length));
        if (input.length > 0) in.put(input).flip();
        ByteBuffer out = ByteBuffer.allocateDirect(Math.max(1, outCapOverride));
        return compressNative(address(in), input.length, address(out), outCapOverride);
    }

    /**
     * Reference decompression through the ACTUAL vanilla decoder class:
     * builds a VarInt-framed compressed frame exactly like the live
     * pipeline (varint(decompressedSize) + zlib stream) and decodes it
     * with net.minecraft.network.NettyCompressionDecoder via an
     * EmbeddedChannel. Returns the recovered plaintext or throws.
     */
    public static byte[] vanillaDecode(byte[] compressed, int originalLen, int threshold) throws Exception {
        Class<?> pb = Class.forName("net.minecraft.network.PacketBuffer");
        io.netty.buffer.ByteBuf framed = io.netty.buffer.Unpooled.buffer(compressed.length + 8);
        Object frameBuf = pb.getConstructor(io.netty.buffer.ByteBuf.class).newInstance(framed);
        // Frame EXACTLY like the reference encoder: below threshold the
        // encoder emits varint(0)+RAW (decoder rejects sub-threshold sizes).
        if (originalLen < threshold || originalLen == 0) {
            pb.getMethod("func_150787_b", int.class).invoke(frameBuf, 0);
            framed.writeBytes(compressed, 0, 0); // marker only; raw path tested by caller
            // real pipeline would append the raw packet; for interop we only
            // exercise the compressed path, so callers use >=threshold inputs
            // for decode and check sub-threshold framing separately.
        } else {
            pb.getMethod("func_150787_b", int.class).invoke(frameBuf, originalLen);
            framed.writeBytes(compressed);
        }
        Class<?> decClass = Class.forName("net.minecraft.network.NettyCompressionDecoder");
        Object decoder = decClass.getConstructor(int.class).newInstance(threshold);
        io.netty.channel.embedded.EmbeddedChannel ch =
                new io.netty.channel.embedded.EmbeddedChannel(
                        (io.netty.channel.ChannelHandler) decoder);
        ch.writeInbound(framed);
        io.netty.buffer.ByteBuf out = ch.readInbound();
        if (out == null) {
            out = ch.readInbound();
            ch.finishAndReleaseAll();
            throw new IllegalStateException("decoder produced no output");
        }
        ch.finishAndReleaseAll();
        byte[] plain = new byte[out.readableBytes()];
        out.readBytes(plain);
        out.release();
        return plain;
    }
}
