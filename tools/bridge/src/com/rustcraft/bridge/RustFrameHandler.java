package com.rustcraft.bridge;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * M-CK4 §3 — OFFLINE handler-shaped frame adapter (research only; NOT
 * registered in any production pipeline).
 *
 * Replaces the combined compression + outer-length-framing operation in a
 * test pipeline: one complete immutable/read-stable serialized packet body
 * (packet-ID VarInt included) in, one complete unencrypted frame out.
 *
 * Wiring note (verified M-CK3): Netty outbound traverses tail→head, so this
 * handler is added LAST (tail-most) to see the raw body first, exactly where
 * the installed compression handler sits; no prepender follows (this adapter
 * emits the final framed bytes — double framing impossible by construction).
 *
 * Compression state: Java (the connection) supplies the EFFECTIVE threshold
 * via setThreshold(); the handler stores it in a plain volatile read between
 * messages — safe because Netty executes handler methods on one event loop,
 * which is exactly the enforced single-owner contract of the native
 * OutboundFrameCtx. updateCompressionOrder counter preserves message-order
 * observability for tests.
 *
 * Buffer forms: heap/direct, arbitrary readerIndex, slices and retained
 * slices, and composites are accepted — the body is staged into the
 * adapter's own direct input scratch via readBytes (one copy; documented).
 * Output: the native frame is copied once into the outbound ByteBuf
 * (caller-owned; a later frame cannot overwrite an earlier retained buffer
 * because each encode writes a fresh region of the output buffer Netty
 * provides).
 *
 * Unsupported forms: none in the offline pipeline (fallback = exception up to
 * the EmbeddedChannel, mirroring codec failure semantics).
 */
public class RustFrameHandler extends MessageToByteEncoder<ByteBuf> {

    private volatile int threshold = -1; // <0 = compression disabled
    final OutboundFrameCtx ctx = OutboundFrameCtx.create(); // package-private: test observability
    public static final AtomicLong FRAMES = new AtomicLong();
    public static final AtomicLong BYTES_IN = new AtomicLong();
    public static final AtomicLong BYTES_OUT = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static final AtomicLong THRESHOLD_UPDATES = new AtomicLong();

    /** Effective-compression state supplied by the (Java) connection.
     *  Order-preserving under the single-event-loop contract. */
    public void setThreshold(int t) {
        this.threshold = t;
        THRESHOLD_UPDATES.incrementAndGet();
    }

    public int threshold() { return threshold; }

    @Override
    protected void encode(ChannelHandlerContext chc, ByteBuf in, ByteBuf out) throws Exception {
        int len = in.readableBytes();
        if (len == 0) {
            ERRORS.incrementAndGet();
            throw new IllegalStateException("empty packet body");
        }
        byte[] body = new byte[len];
        in.readBytes(body); // one staging copy from ANY ByteBuf form
        byte[] frame = ctx.frame(body, threshold);
        if (frame == null) {
            ERRORS.incrementAndGet();
            throw new IllegalStateException("native frame encode failed");
        }
        FRAMES.incrementAndGet();
        BYTES_IN.addAndGet(len);
        BYTES_OUT.addAndGet(frame.length);
        out.writeBytes(frame); // one output copy into Netty-owned buffer
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext chc) throws Exception {
        ctx.free(); // native context lifetime == handler/connection lifetime
        super.handlerRemoved(chc);
    }
}
