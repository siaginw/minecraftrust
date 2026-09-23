package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK4 §3 / M-CK4.2 — OFFLINE handler-shaped frame adapter (research only; NOT
 * registered in any production pipeline).
 *
 * Replaces the combined compression + outer-length-framing operation in a
 * test pipeline: one complete immutable/read-stable serialized packet body
 * (packet-ID VarInt included) in, one complete unencrypted frame out.
 *
 * NETTY OWNERSHIP CONTRACT (M-CK4.2, installed Netty 4.1.9.Final — verified by
 * disassembly of the classes bundled in minecraft_server.1.12.2.srg.jar):
 *  - Ownership is bound to the CHANNEL EXECUTOR captured in handlerAdded
 *    (ctx.executor()), NEVER to the first caller: a pre-attachment or
 *    unexpected first caller cannot become the owner because no
 *    caller-captured owner exists.
 *  - Post-attachment membership uses the REAL executor check
 *    EventExecutor.inEventLoop() (DefaultChannelPipeline.addLast(EventExecutorGroup,...)
 *    binds the context to group.next() and marshals handlerAdded through
 *    ctx.executor().execute(...); AbstractChannelHandlerContext.invokeWrite and
 *    the lifecycle callbacks use the same gate). EmbeddedChannel's own loop
 *    (EmbeddedEventLoop.inEventLoop() == true unconditionally) is permissive BY
 *    DESIGN in Netty — it is not used as evidence of ownership; the ownership
 *    gates exercise a real single-thread DefaultEventExecutorGroup instead.
 *  - Threshold configuration BEFORE attachment is plain configuration on any
 *    thread and binds nothing. AFTER attachment, synchronous setThreshold must
 *    run on the owning executor (membership check) and is REJECTED off-owner;
 *    off-owner callers use updateThresholdAsync, the explicit queued update
 *    with completion acknowledgement, which enqueues onto the owning executor.
 *  - ORDERING CLAIM (only what the executor contract proves): all work for one
 *    handler is submitted to ONE single-thread executor whose task queue is
 *    FIFO, so encodes, queued threshold updates, and the marshaled free run in
 *    submission order on that thread. NOTHING is implied between unrelated
 *    racing submissions (e.g., which thread calls updateThresholdAsync first).
 *  - Removal/close: handlerRemoved sets removed and frees the native context
 *    ON THE OWNING EXECUTOR when invoked off-owner (queued behind already-
 *    submitted encodes — a live context is never freed while queued work may
 *    still use it). If the executor rejects the task (shutdown), the free runs
 *    inline. The free itself is exactly-once (AtomicLong FREES + native CAS).
 *  - Creation failure is handled cleanly at attach: the handler degrades to a
 *    deterministic per-encode failure (no NPE, no leak — nothing was created),
 *    and removal still completes.
 *
 * Buffer-form/copy accounting and the FUTURE SHADOW BOUNDARY documentation are
 * unchanged from M-CK4.1: body staged ByteBuf -> heap -> direct input; frame
 * copied native -> heap -> Netty output (3 copies); a live SHADOW (separate
 * authorization, NOT this checkpoint, NOT enabled) must observe a read-stable
 * copy taken AFTER Java serialization while Java output stays authoritative.
 */
public class RustFrameHandler extends MessageToByteEncoder<ByteBuf> {

    private volatile int threshold = -1; // <0 = compression disabled

    // ---- attachment state (M-CK4.2) ----
    private volatile EventExecutor ownerExecutor; // captured in handlerAdded == ctx.executor()
    OutboundFrameCtx ctx;                          // created at attach on the owner executor; package-private: test observability
    private volatile boolean removed;
    private volatile boolean creationFailed;
    volatile Thread lastEncodeThread;              // test observability: real thread that ran encode
    volatile Thread lastUpdateThread;              // test observability: real thread that applied a threshold update
    final AtomicLong FREES = new AtomicLong();     // test observability: free ran exactly once
    private final java.util.concurrent.atomic.AtomicBoolean freed = new java.util.concurrent.atomic.AtomicBoolean();
    // ---- M-CK5 quiescence protocol (replaces the optimistic inline free after
    // a rejected executor submission): a free is legal ONLY when removed==true
    // AND inFlightNative==0; ops increment inFlightNative BEFORE re-checking
    // removed, so any op that could still use the native context is visible to
    // cleanup. If work is in flight at free time, the free is DEFERRED to the
    // completing op's finally-block (which runs on the owning thread). A CAS on
    // the pointer alone is NOT an in-flight lifetime guarantee.
    private final java.util.concurrent.atomic.AtomicInteger inFlightNative = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean freeDeferred;
    private final java.util.concurrent.atomic.AtomicBoolean freeRan = new java.util.concurrent.atomic.AtomicBoolean();
    /** M-CK5 offline seams: block INSIDE encode while a native op is in flight
     *  (testEncodeBlockOn), with testEncodeEntered counting down when the
     *  blocked point is reached — deterministic quiescence/rejection tests.
     *  Package-private, property-immune. */
    volatile CountDownLatch testEncodeBlockOn;
    volatile CountDownLatch testEncodeEntered;

    public static final AtomicLong FRAMES = new AtomicLong();
    public static final AtomicLong BYTES_IN = new AtomicLong();
    public static final AtomicLong BYTES_OUT = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static final AtomicLong THRESHOLD_UPDATES = new AtomicLong();
    public static final AtomicLong DIRECT_FRAMES = new AtomicLong();   // M-CK5: direct-address path taken
    public static final AtomicLong FALLBACK_FRAMES = new AtomicLong(); // M-CK5: bounded-copy fallback taken
    final ArrayDeque<Integer> ORDERED_UPDATE_TOKENS = new ArrayDeque<>(); // sequence tokens: tests verify ORDER

    /**
     * M-CK5 Optimization 1+2 (arm D; default FALSE = unchanged legacy behavior):
     * when enabled, encode uses the direct-address paths — a DIRECT input buffer
     * is read natively in place under the pipeline's synchronous single-owner
     * window (no heap staging, no context input scratch; heap/composite/unsupported
     * forms keep the bounded-copy fallback), and the completed frame is written
     * by native code DIRECTLY into the Netty-owned output buffer's writable
     * region, with the writer index advanced ONLY after success and the output
     * address RE-ACQUIRED after any growth (ensureWritable may reallocate).
     * Technique validated against the pinned Velocity reference (native/src
     * CompressorUtils + proxy MinecraftCompressorAndLengthEncoder: ensureCompatible
     * direct-input + deflate into `out`) and Netty 4.1.9 nioBuffer semantics
     * (verified empirically by MCK5OptimizationTests).
     */
    boolean useDirectPaths;

    /** M-CK4.2 offline injection seam: simulate native-context creation failure
     *  at attach. Package-private, property-immune — production code paths can
     *  never activate it via configuration. */
    boolean testSimulateCreateFailure;

    EventExecutor ownerExecutor() { return ownerExecutor; } // package-private: gates assert the bound executor

    /**
     * Effective-compression state supplied by the (Java) connection.
     * PRE-ATTACHMENT: configuration on any thread; binds no ownership.
     * POST-ATTACHMENT: must run on the owning executor (real membership check);
     * off-owner synchronous calls are REJECTED (use updateThresholdAsync).
     * After removal: always rejected.
     */
    public void setThreshold(int t) {
        if (removed) throw new IllegalStateException("setThreshold after handler removal");
        EventExecutor owner = ownerExecutor;
        if (owner != null && !owner.inEventLoop()) {
            throw new IllegalStateException(
                    "setThreshold off-owner: synchronous updates must run on the owning event executor (inEventLoop() == false); use updateThresholdAsync for the queued form");
        }
        threshold = t;
        THRESHOLD_UPDATES.incrementAndGet();
        ORDERED_UPDATE_TOKENS.addLast(t);
    }

    /**
     * Explicit QUEUED threshold update with completion acknowledgement
     * (post-attachment only). Ordered with respect to encodes and other work
     * submitted to the SAME owning executor (single-thread FIFO task queue —
     * the only ordering claimed; nothing is implied between unrelated racing
     * submissions). The future completes on the owning executor after the
     * update is applied (or exceptionally if the handler was removed first).
     */
    public CompletableFuture<Void> updateThresholdAsync(int t) {
        EventExecutor owner = ownerExecutor;
        if (owner == null) {
            throw new IllegalStateException("updateThresholdAsync before attachment: use setThreshold (pre-attachment configuration)");
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (removed) {
            done.completeExceptionally(new IllegalStateException("handler removed before queued update ran"));
            return done;
        }
        owner.execute(() -> {
            try {
                if (!removed) {
                    threshold = t;
                    THRESHOLD_UPDATES.incrementAndGet();
                    ORDERED_UPDATE_TOKENS.addLast(t);
                    lastUpdateThread = Thread.currentThread();
                } else {
                    done.completeExceptionally(new IllegalStateException("handler removed before queued update ran"));
                    return;
                }
                done.complete(null);
            } catch (Throwable ex) {
                done.completeExceptionally(ex);
            }
        });
        return done;
    }

    public int threshold() { return threshold; }

    @Override
    public void handlerAdded(ChannelHandlerContext chc) throws Exception {
        ownerExecutor = chc.executor(); // the REAL channel executor, not any caller
        if (testSimulateCreateFailure) {
            creationFailed = true;      // nothing created: no leak possible; encode fails deterministically
            return;
        }
        OutboundFrameCtx c = OutboundFrameCtx.create();
        ctx = c;
        if (c == null || !c.isLive()) creationFailed = true; // real frameCreate failure handled cleanly
        super.handlerAdded(chc);
    }

    @Override
    protected void encode(ChannelHandlerContext chc, ByteBuf in, ByteBuf out) throws Exception {
        if (removed) {
            ERRORS.incrementAndGet();
            throw new EncoderException("rust frame encode after handler removal");
        }
        EventExecutor owner = ownerExecutor;
        if (owner != null && !owner.inEventLoop()) {
            ERRORS.incrementAndGet();
            throw new EncoderException("rust frame encode off owning executor (Netty contract violation)");
        }
        OutboundFrameCtx c = ctx;
        if (c == null || creationFailed) {
            ERRORS.incrementAndGet();
            throw new EncoderException("rust frame context unavailable (creation failed at attach)");
        }
        if (!c.isLive()) {
            ERRORS.incrementAndGet();
            throw new EncoderException("rust frame context closed");
        }
        lastEncodeThread = Thread.currentThread();
        int len = in.readableBytes();
        if (len == 0) {
            ERRORS.incrementAndGet();
            throw new IllegalStateException("empty packet body");
        }
        // quiescence protocol: counted BEFORE the removed re-check so cleanup can
        // never free beneath a dispatched op; native use happens only after both
        // guards pass inside this counted region.
        inFlightNative.incrementAndGet();
        try {
            if (removed) throw new EncoderException("rust frame encode after handler removal (dispatch raced cleanup)");
            // M-CK5 arm D: try the direct-address paths FIRST (no heap staging);
            // heap/composite/unsupported forms return false and take the bounded-copy
            // fallback below — input indices are never touched by the direct attempt.
            if (useDirectPaths) {
                if (encodeDirect(c, in, out, len, threshold)) return;
                FALLBACK_FRAMES.incrementAndGet();
            }
            byte[] body = new byte[len];
            in.readBytes(body); // one staging copy from ANY ByteBuf form (fallback path)
            CountDownLatch entered = testEncodeEntered;
            if (entered != null) { testEncodeEntered = null; entered.countDown(); }
            CountDownLatch latch = testEncodeBlockOn;
            if (latch != null) { testEncodeBlockOn = null; try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { } }
            byte[] frame = c.frame(body, threshold);

            if (frame == null) {
                ERRORS.incrementAndGet();
                throw new EncoderException("native frame encode failed (last native code " + OutboundFrameCtx.LAST_ERR + ")");
            }
            FRAMES.incrementAndGet();
            BYTES_IN.addAndGet(len);
            BYTES_OUT.addAndGet(frame.length);
            out.writeBytes(frame); // one output copy into Netty-owned buffer
        } finally {
            if (inFlightNative.decrementAndGet() == 0 && freeDeferred) freeNow();
        }
    }

    /**
     * M-CK5 Optimization 1+2 (must be called INSIDE the in-flight native region):
     * direct input (a direct, non-composite ByteBuf read natively in place at its
     * readerIndex — verified shareable via nioBuffer) and native frame output
     * written directly into the Netty-owned out buffer's writable region. Writer
     * index advances ONLY after success; after a capacity-driven ensureWritable
     * the output address is RE-ACQUIRED (growth may reallocate). Returns true if
     * the frame was produced; false = caller must use the bounded-copy fallback.
     * Capacity discipline mirrors frame(): exactly one growth retry, needed bound
     * read from the native-written i32, policyLimit enforced, no partial output.
     */
    private boolean encodeDirect(OutboundFrameCtx c, ByteBuf in, ByteBuf out, int len, int threshold) throws Exception {
        long inAddr;
        if (in.isDirect() && !(in instanceof io.netty.buffer.CompositeByteBuf)) {
            ByteBuffer inNio = null;
            try { inNio = in.nioBuffer(in.readerIndex(), len); } catch (Throwable t) { inNio = null; }
            if (inNio != null && inNio.isDirect()) inAddr = addressOf(inNio);
            else return false;
        } else {
            return false; // heap/composite/unsupported: bounded-copy fallback
        }
        out.ensureWritable(OutboundFrameCtx.maxOutputLen(len) + 16);
        int attempts = 0;
        while (true) {
            ByteBuffer outNio;
            try { outNio = out.nioBuffer(out.writerIndex(), out.writableBytes()); } catch (Throwable t) { return false; }
            if (outNio == null || !outNio.isDirect()) return false; // allocator config without direct output: fallback
            int cap = out.writableBytes();
            int n = c.frameAddr(inAddr, len, threshold, addressOf(outNio), cap);
            if (n > 0) {
                out.writerIndex(out.writerIndex() + n); // advance ONLY after successful completion
                FRAMES.incrementAndGet();
                DIRECT_FRAMES.incrementAndGet();
                BYTES_IN.addAndGet(len);
                BYTES_OUT.addAndGet(n);
                return true;
            }
            if (n == OutboundFrameCtx.ERR_CAPACITY) {
                int needed = c.LAST_RETRY_NEEDED;
                if (needed <= 0 || needed <= c.LAST_EFF_CAP || needed > c.policyLimit) {
                    ERRORS.incrementAndGet();
                    throw new EncoderException("direct-path capacity bound rejected: needed=" + needed + " failedCap=" + c.LAST_EFF_CAP);
                }
                if (++attempts > 1) { // one-retry ceiling, same discipline as frame()
                    ERRORS.incrementAndGet();
                    throw new EncoderException("direct-path capacity retry exhausted");
                }
                out.ensureWritable(needed); // may REALLOCATE: address re-acquired at loop top
                continue;
            }
            ERRORS.incrementAndGet();
            throw new EncoderException("native frame encode failed (direct path, code " + n + ")");
        }
    }

    private static volatile java.lang.reflect.Method ADDRESS_OF;
    private static long addressOf(ByteBuffer direct) throws Exception {
        java.lang.reflect.Method m = ADDRESS_OF;
        if (m == null) {
            m = direct.getClass().getMethod("address");
            m.setAccessible(true);
            ADDRESS_OF = m;
        }
        return (Long) m.invoke(direct);
    }

    private void freeNow() {
        if (freeRan.compareAndSet(false, true)) {
            OutboundFrameCtx c = ctx;
            if (c != null) c.free(); // native free is itself idempotent (CAS)
            FREES.incrementAndGet();
        }
    }

    /**
     * Cleanup is triggered from BOTH handlerRemoved AND the outbound close()
     * hook (Netty 4.1.9-verified: an explicit pipeline.remove marshals
     * handlerRemoved onto the owning executor, but a channel CLOSE alone never
     * invokes handlerRemoved for a foreign-executor-bound handler).
     *
     * QUIESCENCE CONTRACT (M-CK5): the native context is freed only when
     * removed==true AND inFlightNative==0. Ops increment inFlightNative BEFORE
     * re-checking removed, so a dispatched-then-preempted op is visible to
     * cleanup (which then defers the free to the op's completing finally-block
     * on the owning thread). If the executor REJECTS the marshaled free while
     * an op is in flight, the free is deferred the same way — the earlier
     * inline-free-after-rejection is replaced by this provable protocol; a
     * rejected executor with inFlight==0 can safely free because removed==true
     * forces any not-yet-started op to abort before any native use.
     */
    private void cleanupNative(String via) {
        if (!freed.compareAndSet(false, true)) return;
        removed = true;
        EventExecutor owner = ownerExecutor;
        if (owner == null || owner.inEventLoop()) {
            if (inFlightNative.get() > 0) freeDeferred = true; // completing op frees
            else freeNow();
            return;
        }
        try {
            owner.execute(() -> {
                if (inFlightNative.get() > 0) freeDeferred = true; // single owner thread: its completing op frees
                else freeNow();
            });
        } catch (Throwable rejected) {
            if (inFlightNative.get() > 0) freeDeferred = true; // in-flight op frees at completion
            else freeNow(); // no op can start: removed==true aborts entries pre-native
        }
    }

    /**
     * Outbound-side close hook: channel ops (close included) propagate
     * tail->head through the pipeline, marshaling per-context onto the owning
     * executor — so this fires for foreign-executor-bound handlers where
     * 4.1.9's channel close NEVER invokes handlerRemoved (probe-verified).
     * Marshaled FIFO ordering guarantees already-queued encodes run before
     * the close task, then cleanup runs on the same executor thread.
     */
    @Override
    public void close(ChannelHandlerContext chc, ChannelPromise promise) throws Exception {
        cleanupNative("close");
        super.close(chc, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext chc) throws Exception {
        cleanupNative("handlerRemoved"); // fires on explicit pipeline removal/replacement
        super.handlerRemoved(chc);
    }
}
