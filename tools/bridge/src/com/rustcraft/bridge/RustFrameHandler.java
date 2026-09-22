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
 *
 * FUTURE SHADOW BOUNDARY (documented, NOT enabled, no authorization implied):
 * a live SHADOW of this adapter would observe a READ-STABLE COPY taken AFTER
 * Java packet serialization; Java compression, framing, encryption, and socket
 * writes remain authoritative; native output is compared then discarded —
 * never published, and the original input's indices/ownership/ordering/write
 * promise are never disturbed. Observation work is bounded and disabled on
 * any recoverable failure. This handler must NOT be installed alongside the
 * production compressor/prepender and called SHADOW — that would be a
 * replacement, not a shadow.
 */
public class RustFrameHandler extends MessageToByteEncoder<ByteBuf> {

    private volatile int threshold = -1; // <0 = compression disabled
    final OutboundFrameCtx ctx = OutboundFrameCtx.create(); // package-private: test observability
    public static final AtomicLong FRAMES = new AtomicLong();
    public static final AtomicLong BYTES_IN = new AtomicLong();
    public static final AtomicLong BYTES_OUT = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static final AtomicLong THRESHOLD_UPDATES = new AtomicLong();

    /**
     * Effective-compression state supplied by the (Java) connection.
     *
     * OWNER-EXECUTOR CONTRACT (M-CK4.1): after the handler is attached to a
     * channel, ALL state changes (setThreshold), encodes, and cleanup must run
     * on the owning event loop thread (Netty's own execution model). This
     * method ENFORCES it: off-owner calls are rejected unless a test executor
     * was installed pre-attachment via setOwnerExecutorForTests — an offline
     * seam that MARSHALS the update through the documented order-preserving
     * path instead of silently racing. Pre-attachment setup (constructing the
     * handler and setting an initial threshold before addLast) needs no owner.
     */
    public void setThreshold(int t) {
        if (ownerThread == null) {
            ownerThread = resolveOwner(); // captured lazily at FIRST post-attach use
        }
        Thread cur = Thread.currentThread();
        if (ownerThread != null && cur != ownerThread) {
            if (testExecutor != null) {
                testExecutor.execute(() -> setThresholdOnOwner(t)); // documented marshal path
                return;
            }
            throw new IllegalStateException("setThreshold off-owner: updates must run on the owning event loop (or use the documented test executor)");
        }
        setThresholdOnOwner(t);
    }

    private void setThresholdOnOwner(int t) {
        this.threshold = t;
        THRESHOLD_UPDATES.incrementAndGet();
        ORDERED_UPDATE_TOKENS.addLast(t); // sequence token: tests verify ORDER, not just counts
    }

    private volatile Thread ownerThread;
    private java.util.concurrent.Executor testExecutor;
    final java.util.ArrayDeque<Integer> ORDERED_UPDATE_TOKENS = new java.util.ArrayDeque<>();

    /** Pre-attachment ONLY: installs the offline order-preserving test executor. */
    public void setOwnerExecutorForTests(java.util.concurrent.Executor ex) {
        if (ownerThread != null) throw new IllegalStateException("must be called BEFORE attachment");
        this.testExecutor = ex;
    }

    /** Post-attachment test seam (documented marshal path; offline gates only). */
    void setOwnerExecutorForTestsPost(java.util.concurrent.Executor ex) {
        this.testExecutor = ex;
    }

    private static Thread resolveOwner() { return Thread.currentThread(); }

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
