package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * M-CK4.2 §3 arm B — the M-CK2 rust-backed compressor in its PRODUCTION shape:
 * a drop-in replacement for the vanilla 'compress' handler that emits the INNER
 * compression layer only ([VarInt dataLen][payload]); the INSTALLED
 * NettyVarint21FrameEncoder remains in the pipeline for the OUTER length
 * framing (the bench pipeline adds prepender BEFORE this handler, matching the
 * vanilla wiring where outbound tail->head reaches this handler first).
 *
 * Below-threshold packets take the PURE-JAVA passthrough (VarInt(0) + raw
 * bytes, no JNI call, no native-context creation) — preserving the actual
 * CK2/vanilla below-threshold behavior rather than paying a JNI round trip to
 * make Rust look better.
 *
 * Disabled contract (threshold < 0): the vanilla NetworkManager REMOVES the
 * compression handlers entirely (func_179289_a: iflt -> remove("compress")/
 * remove("decompress")) — so this handler must NOT be installed in disabled
 * mode; the disabled bench arm uses the prepender-only pipeline.
 */
public class MCK42RustCompressJavaFrameHandler extends MessageToByteEncoder<ByteBuf> {

    private final CompressionCtx cctx = new CompressionCtx();
    private final int threshold;
    public long passthroughOps, nativeOps; // test/bench observability

    public MCK42RustCompressJavaFrameHandler(int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("disabled mode must not install this handler (vanilla removes the compress slot)");
        this.threshold = threshold;
    }

    @Override
    protected void encode(ChannelHandlerContext chc, ByteBuf in, ByteBuf out) throws Exception {
        int len = in.readableBytes();
        if (len < threshold) {
            passthroughOps++;          // pure-Java passthrough: no JNI, no context creation
            writeVarInt(out, 0);
            out.writeBytes(in);
            return;
        }
        byte[] body = new byte[len];   // one staging copy (same buffer form contract as arm C)
        in.readBytes(body);
        if (!cctx.ensureCreated()) throw new EncoderException("native compression context creation failed");
        byte[] comp = cctx.compress(body);
        if (comp == null) throw new EncoderException("native compress failed");
        nativeOps++;
        writeVarInt(out, body.length);
        out.writeBytes(comp);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext chc) throws Exception {
        cctx.free();
        super.handlerRemoved(chc);
    }

    /** Vanilla PacketBuffer VarInt byte layout (same algorithm as the installed handlers). */
    static void writeVarInt(ByteBuf out, int v) {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }
}
