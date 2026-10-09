package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live outbound Netty boundary for Rust-authoritative SPacketChunkData
 * bodies: one complete, immutable, pre-compression ByteBuf per packet,
 * written straight past vanilla {@code NettyPacketEncoder} into the existing
 * {@code NettyCompressionEncoder} / {@code NettyVarint21FrameEncoder} chain.
 *
 * <p>Handler placement (dedicated-server vanilla 1.12.2 pipeline, verified
 * from bytecode): the handler is inserted with
 * {@code pipeline.addAfter("encoder", "rustcraft_single_copy", ...)}, so the
 * outbound traversal is</p>
 *
 * <pre>
 *   tail -> packet_handler (NetworkManager)
 *        -> rustcraft_single_copy (THIS handler)          [bypass decision]
 *        -> encoder            (NettyPacketEncoder)       [bypassed for admitted packets:
 *                                                           a raw ByteBuf is not a Packet, and
 *                                                           MessageToByteEncoder forwards
 *                                                           non-matching messages untouched]
 *        -> rustcraft_body_capture (shadow only)          [compares encoder output vs body]
 *        -> compress           (NettyCompressionEncoder)  [untouched]
 *        -> prepender          (NettyVarint21FrameEncoder)[untouched]
 *        -> encrypt            (when enabled)             [untouched]
 *        -> head/socket
 * </pre>
 *
 * <p>Java packets are forwarded with {@code ctx.write(msg, promise)} and the
 * vanilla encoder runs untouched. Admission state is a one-time transition:
 * a packet is JAVA or RUST_SINGLE_COPY, never both.</p>
 *
 * <p>Ownership states (task contract): CREATED (buffer allocated) → COMPLETE
 * (header+payload+trailer written, writerIndex frozen) → QUEUED (registered
 * against the packet) → COMMITTED (consumed by at least one outbound write) →
 * RELEASED (body refCnt returned to 0 exactly once, CAS-guarded).</p>
 *
 * <p>Release lifecycle — the 30s sweep is the DEFENSIVE net, never the
 * primary mechanism:</p>
 * <ol>
 *   <li>Every per-connection write serializes a {@code retainedDuplicate()}
 *   view; the accepting downstream MessageToByteEncoder (compression /
 *   prepender / head) releases that view — pipeline-owned, promise-bound.</li>
 *   <li>A single-consumer body is invalidated by a quiescence check scheduled
 *   on the event loop 100ms after its write completes (or on channel close),
 *   so a dropped connection releases without waiting for any future packet
 *   sweep.</li>
 *   <li>A multi-consumer body (vanilla changed-chunk broadcast sends one
 *   packet object to every watcher) cannot know its last consumer, so its
 *   final release falls to the sweep; the bound is hard (128 tickets).</li>
 * </ol>
 *
 * <p>Governance: both modes are default-OFF. Shadow mode transmits only
 * vanilla-encoded bytes and byte-compares the encoder output against the
 * body. Direct mode writes the body itself. {@code PRODUCTION_AUTHORITY}
 * semantics of the enclosing experiment are unchanged.</p>
 */
public final class SingleCopyPipeline {

    public static final String HANDLER_NAME = "rustcraft_single_copy";
    public static final String CAPTURE_NAME = "rustcraft_body_capture";

    private static final String PROPERTY_DIRECT = "rustcraft.singleCopy";
    private static final String PROPERTY_SHADOW = "rustcraft.singleCopyShadow";

    // Volatile (not final) so the offline suite can toggle modes without a
    // second JVM; production always uses the property-derived values.
    private static volatile boolean DIRECT_MODE = Boolean.getBoolean(PROPERTY_DIRECT);
    private static volatile boolean SHADOW_MODE = Boolean.getBoolean(PROPERTY_SHADOW);

    /** Hard bound on outstanding single-copy bodies (count). */
    public static final int MAX_OUTSTANDING_BODIES = 128;
    /** Hard bound on outstanding single-copy body bytes. */
    public static final long MAX_OUTSTANDING_BYTES = 32L * 1024 * 1024;
    /** Quiescence window before a single-consumer body is invalidated. */
    public static final long QUIESCENCE_DELAY_NANOS = 100_000_000L;
    /** Defensive sweep age for tickets (matches the legacy 30s eviction). */
    public static final long SWEEP_EXPIRATION_NANOS = 30_000_000_000L;

    private static final AttributeKey<Boolean> INSTALL_TOUCHED =
            AttributeKey.valueOf("rustcraft_single_copy_touched");

    // ------------------------------------------------------------------
    // Telemetry (task contract names + lifecycle accounting)
    // ------------------------------------------------------------------
    public static final class Telemetry {
        public final AtomicLong committed = new AtomicLong();            // single_copy_committed (bodies registered)
        public final AtomicLong vanillaEncoderBypassed = new AtomicLong();// writes that skipped NettyPacketEncoder
        public final AtomicLong packetEncoderInvocations = new AtomicLong(); // instrumented encode() entries
        public final AtomicLong packetbufferPayloadCopies = new AtomicLong();// PacketBuffer.writeBytes payload copies
        public final AtomicLong bodyBytes = new AtomicLong();
        public final AtomicLong buffersCreated = new AtomicLong();
        public final AtomicLong buffersReleased = new AtomicLong();
        public final AtomicLong pressureFallback = new AtomicLong();     // DIRECT_BUFFER_PRESSURE_FALLBACK (bounds)
        public final AtomicLong measureFailures = new AtomicLong();
        public final AtomicLong encodeFailures = new AtomicLong();
        public final AtomicLong measureEncodeDivergences = new AtomicLong();
        public final AtomicLong bodyBuildErrors = new AtomicLong();
        public final AtomicLong handlerErrors = new AtomicLong();
        public final AtomicLong shadowMatches = new AtomicLong();
        public final AtomicLong shadowMismatches = new AtomicLong();
        public final AtomicLong shadowExpectedDivergences = new AtomicLong();
        public final AtomicLong shadowEncodingDivergences = new AtomicLong();
        public final AtomicLong multiConsumerBodies = new AtomicLong();
        public final AtomicLong releasedViaQuiescence = new AtomicLong();
        public final AtomicLong releasedViaChannelClose = new AtomicLong();
        public final AtomicLong releasedViaSweep = new AtomicLong();
        public final AtomicLong releasedViaShutdown = new AtomicLong();
        public final AtomicLong staleTicketRefusals = new AtomicLong();   // writePacketData guard refusals
        public final AtomicLong fallbackTwoCopyServes = new AtomicLong(); // writePacketData two-copy fallback serves
        public final AtomicLong installs = new AtomicLong();
        public final AtomicLong installFailures = new AtomicLong();
        public final AtomicInteger outstandingBodies = new AtomicInteger();
        public final AtomicLong outstandingBytes = new AtomicLong();
        public volatile long highWaterBodies = 0;
        public volatile long highWaterBytes = 0;
        public final AtomicLong shadowSetDebug = new AtomicLong();
    }

    private static final Telemetry T = new Telemetry();

    public static Telemetry telemetry() {
        return T;
    }

    public static boolean directMode() {
        return DIRECT_MODE;
    }

    public static boolean shadowMode() {
        return SHADOW_MODE;
    }

    public static boolean enabled() {
        return DIRECT_MODE || SHADOW_MODE;
    }

    private SingleCopyPipeline() { }

    // Test hooks: production code never calls these.
    static void setDirectModeForTest(boolean on) {
        DIRECT_MODE = on;
    }

    static void setShadowModeForTest(boolean on) {
        SHADOW_MODE = on;
    }

    // ------------------------------------------------------------------
    // Ticket registry
    // ------------------------------------------------------------------

    /** packet (identity) -> ticket. The packet semantic freeze: putting the
     *  ticket here is the one-time JAVA -> RUST_SINGLE_COPY transition. */
    private static final ConcurrentMap<Object, SingleCopyTicket> TICKETS =
            new ConcurrentHashMap<>();

    private static final AtomicInteger SLOT_RESERVATIONS = new AtomicInteger();

    /**
     * One immutable body + its lifecycle. The body ByteBuf refCnt is 1 while
     * registered; each consumer write duplicates (+1) and the pipeline
     * releases the duplicate. release() drops the registry's own reference
     * exactly once.
     */
    public static final class SingleCopyTicket {
        public final ByteBuf body;
        public final Object packet;
        /** Frozen packet fields mirrored from the encode (audit + fallback serve). */
        public final int packetId;
        public final int chunkX;
        public final int chunkZ;
        public final boolean fullChunk;
        public final int mask;
        public final int payloadBytes;
        /** Offset of the payload inside the body (the header size). */
        public final int payloadOffset;
        /** Shadow provenance: ChunkMutationTracker version when the body froze
         *  and a weak handle to the chunk. If the version moved by capture
         *  time, the live Java packet serializes a NEWER state than the frozen
         *  body by real concurrency - classified as an expected divergence,
         *  never an encoder mismatch. Shadow mode only. */
        public volatile int shadowMutationVersion;
        public volatile java.lang.ref.WeakReference<Object> shadowChunkRef;
        public final long createdAtNanos = System.nanoTime();
        public final AtomicInteger totalWrites = new AtomicInteger();
        public final AtomicInteger outstandingWrites = new AtomicInteger();
        final AtomicBoolean released = new AtomicBoolean(false);
        volatile long lastWriteEndNanos = 0;

        SingleCopyTicket(Object packet, ByteBuf body, int packetId, int chunkX, int chunkZ,
                         boolean fullChunk, int mask, int payloadBytes, int payloadOffset) {
            this.packet = packet;
            this.body = body;
            this.packetId = packetId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.fullChunk = fullChunk;
            this.mask = mask;
            this.payloadBytes = payloadBytes;
            this.payloadOffset = payloadOffset;
        }

        /** Consume this ticket for one outbound write. Returns false only if
         *  the ticket was already released (the caller must not use the body). */
        public boolean tryBeginWrite() {
            if (released.get()) return false;
            if (outstandingWrites.incrementAndGet() < 0) {
                outstandingWrites.decrementAndGet();
                return false;
            }
            if (totalWrites.incrementAndGet() == 2) {
                T.multiConsumerBodies.incrementAndGet();
            }
            return true;
        }

        /** Called when this write's promise completes (success or failure). */
        public void writeCompleted() {
            lastWriteEndNanos = System.nanoTime();
            outstandingWrites.decrementAndGet();
        }

        /** CAS-guarded final release: registry reference dropped exactly once.
         *  Also releases this ticket's slot in the hard outstanding-bounds. */
        public boolean invalidate() {
            if (!released.compareAndSet(false, true)) {
                return false;
            }
            TICKETS.remove(packet, this);
            T.outstandingBytes.addAndGet(-body.readableBytes());
            T.buffersReleased.incrementAndGet();
            SLOT_RESERVATIONS.decrementAndGet();
            int bodies = T.outstandingBodies.decrementAndGet();
            if (bodies < 0) {
                T.outstandingBodies.set(0);
            }
            try {
                body.release();
            } catch (Throwable ignore) {
                // Netty's own double-release guard; the CAS above already
                // prevents our side from releasing twice.
            }
            return true;
        }
    }

    /** Register a completed body. The builder has already reserved a slot. */
    static SingleCopyTicket registerTicket(Object packet, ByteBuf body, int packetId,
                                           int chunkX, int chunkZ, boolean fullChunk,
                                           int mask, int payloadBytes, int payloadOffset) {
        SingleCopyTicket ticket = new SingleCopyTicket(packet, body, packetId, chunkX,
                chunkZ, fullChunk, mask, payloadBytes, payloadOffset);
        SingleCopyTicket previous = TICKETS.putIfAbsent(packet, ticket);
        if (previous != null) {
            // Cannot happen for fresh packet objects; fail closed regardless.
            body.release();
            return null;
        }
        return ticket;
    }

    /** Current ticket for an admitted packet, or null (never admitted, or
     *  already released — callers must treat null with the admitted-set guard). */
    public static SingleCopyTicket ticketFor(Object packet) {
        return TICKETS.get(packet);
    }

    /** Slot accounting for the hard outstanding-bounds (count + bytes). */
    static boolean tryReserveSlot() {
        for (;;) {
            int now = SLOT_RESERVATIONS.get();
            if (now >= MAX_OUTSTANDING_BODIES
                    || T.outstandingBytes.get() >= MAX_OUTSTANDING_BYTES) {
                return false;
            }
            if (SLOT_RESERVATIONS.compareAndSet(now, now + 1)) {
                int bodies = T.outstandingBodies.incrementAndGet();
                if (bodies > T.highWaterBodies) {
                    T.highWaterBodies = bodies;
                }
                return true;
            }
        }
    }

    /** Release a reservation that did not become a registered ticket. */
    static void releaseSlot() {
        SLOT_RESERVATIONS.decrementAndGet();
        int bodies = T.outstandingBodies.decrementAndGet();
        if (bodies < 0) {
            T.outstandingBodies.set(0);
        }
    }

    // ------------------------------------------------------------------
    // Install (called from the NetworkManager.channelActive hook)
    // ------------------------------------------------------------------

    private static final AtomicBoolean PLACEMENT_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOKED = new AtomicBoolean(false);

    /**
     * Install the outbound handler on one live channel. Runs on the Netty
     * event loop from NetworkManager.channelActive. Fail-open: any problem
     * leaves the pipeline vanilla and admitted packets use the two-copy
     * writePacketData fallback (never malformed bytes, never a crash).
     */
    public static void install(Object channelObj) {
        if (!enabled()) {
            return;
        }
        try {
            Channel channel = (Channel) channelObj;
            if (channel == null || channel.attr(INSTALL_TOUCHED).get() != null) {
                return;
            }
            channel.attr(INSTALL_TOUCHED).set(Boolean.TRUE);

            ChannelPipeline pipeline = channel.pipeline();
            boolean logPlacement = PLACEMENT_LOGGED.compareAndSet(false, true);
            if (logPlacement) {
                List<String> before = pipeline.names();
                System.out.println("[RustCraft-SingleCopy] live pipeline.names() before install: " + before);
                if (SHADOW_MODE) {
                    System.out.println("[RustCraft-SingleCopy] mode=SHADOW (vanilla transmits; encoder output compared)");
                } else {
                    System.out.println("[RustCraft-SingleCopy] mode=DIRECT (admitted packets bypass NettyPacketEncoder)");
                }
            }

            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (pipeline.get("encoder") == null) {
                // Unknown pipeline shape: fail open, vanilla path untouched.
                T.installFailures.incrementAndGet();
                if (logPlacement) {
                    System.err.println("[RustCraft-SingleCopy] no 'encoder' handler in pipeline; not installing: "
                            + pipeline.names());
                }
                return;
            }

            pipeline.addAfter("encoder", HANDLER_NAME, new RustCraftSingleCopyChunkHandler());
            T.installs.incrementAndGet();

            if (logPlacement) {
                System.out.println("[RustCraft-SingleCopy] handler placement proof, pipeline.names() after install: "
                        + pipeline.names());
                hookShutdownOnce();
            }
        } catch (Throwable t) {
            T.installFailures.incrementAndGet();
            System.err.println("[RustCraft-SingleCopy] install failed (vanilla path preserved): " + t);
        }
    }

    private static void hookShutdownOnce() {
        if (!SHUTDOWN_HOOKED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            int released = releaseAllOutstanding();
            T.releasedViaShutdown.addAndGet(released);
            System.out.println("[RustCraft-SingleCopy] shutdown release: " + released
                    + " outstanding bodies; telemetry=" + telemetrySummary());
        }, "rustcraft-single-copy-shutdown"));
    }

    /** Used by the injected NettyPacketEncoder.encode entry hook. */
    public static void onPacketEncoderInvoke() {
        T.packetEncoderInvocations.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // The outbound handler
    // ------------------------------------------------------------------

    public static final class RustCraftSingleCopyChunkHandler extends ChannelDuplexHandler {

        /** Tickets this connection has written; closed connections use it to
         *  drop their share of the lifecycle early (deterministic disconnect
         *  cleanup for single-consumer bodies). */
        private final ConcurrentMap<Object, SingleCopyTicket> writtenHere =
                new ConcurrentHashMap<>();

        private volatile boolean captureAfterCompress = false;

        /**
         * The capture must see NettyPacketEncoder's output BEFORE the
         * compressor touches it: directly between "encoder" and "compress".
         * At channelActive the compressor does not exist yet (it registers
         * with addBefore("encoder") at the login transition, which would bury
         * an early-placed capture on the head side of compression), so the
         * capture is placed - and if needed moved - lazily from the event
         * loop on the first shadow writes.
         */
        private void ensureCapturePlacement(ChannelHandlerContext ctx) {
            ChannelPipeline pipeline = ctx.pipeline();
            if (pipeline.get(CAPTURE_NAME) == null) {
                if (pipeline.get("compress") != null) {
                    pipeline.addAfter("compress", CAPTURE_NAME, new BodyCaptureHandler());
                    captureAfterCompress = true;
                } else {
                    pipeline.addBefore("encoder", CAPTURE_NAME, new BodyCaptureHandler());
                }
                return;
            }
            if (!captureAfterCompress && pipeline.get("compress") != null) {
                ChannelHandler capture = pipeline.remove(CAPTURE_NAME);
                pipeline.addAfter("compress", CAPTURE_NAME, capture);
                captureAfterCompress = true;
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf) {
                // Never re-handle our own views (or any other ByteBuf).
                ctx.write(msg, promise);
                return;
            }
            SingleCopyTicket ticket = TICKETS.get(msg);
            if (ticket != null) {
                if (SHADOW_MODE) {
                    ensureCapturePlacement(ctx);
                    if (T.shadowSetDebug.get() < 5) {
                        T.shadowSetDebug.incrementAndGet();
                        System.out.println("[SingleCopy-ShadowDbg] EXPECTED set: packet=" + msg.getClass().getName()
                                + " bodyBytes=" + ticket.body.readableBytes()
                                + " pipeline=" + ctx.pipeline().names());
                    }
                    // Shadow: vanilla transmits; the capture handler between
                    // the encoder and compression compares the encoder output
                    // against this packet's frozen body. The body's only
                    // purpose is the comparison, so the ticket is invalidated
                    // when this write's promise settles.
                    if (ticket.tryBeginWrite()) {
                        EXPECTED_TICKET.set(ticket);
                        EXPECTED.set(ticket.body);
                        try {
                            ctx.write(msg, promise);
                        } finally {
                            EXPECTED.remove();
                            EXPECTED_TICKET.remove();
                        }
                        promise.addListener(future -> {
                            ticket.writeCompleted();
                            if (ticket.invalidate()) {
                                T.releasedViaQuiescence.incrementAndGet();
                            }
                        });
                    } else {
                        ctx.write(msg, promise);
                    }
                    return;
                }
                // DIRECT: bypass NettyPacketEncoder for this write.
                if (ticket.tryBeginWrite()) {
                    ByteBuf view = null;
                    try {
                        view = ticket.body.retainedDuplicate();
                    } catch (Throwable t) {
                        ticket.writeCompleted();
                        throwBodyUnavailable(ctx, msg, promise, t);
                        return;
                    }
                    writtenHere.put(msg, ticket);
                    try {
                        ctx.write(view, promise);
                        view = null; // pipeline owns the view now
                    } catch (Throwable t) {
                        // ctx.write threw synchronously; the view (and the
                        // promise if still unfinished) are ours to settle.
                        writtenHere.remove(msg, ticket);
                        if (view != null) {
                            try { view.release(); } catch (Throwable ignore) { }
                        }
                        ticket.writeCompleted();
                        if (!promise.isDone()) {
                            promise.setFailure(t);
                        }
                        T.handlerErrors.incrementAndGet();
                        return;
                    }
                    T.vanillaEncoderBypassed.incrementAndGet();
                    final SingleCopyTicket finalTicket = ticket;
                    final Object writtenPacket = msg;
                    final EventExecutor executor = ctx.executor();
                    promise.addListener(future -> {
                        finalTicket.writeCompleted();
                        // The write is settled: this connection no longer
                        // holds a lifecycle stake in the packet.
                        writtenHere.remove(writtenPacket, finalTicket);
                        scheduleQuiescenceRelease(executor, finalTicket);
                    });
                    return;
                }
                // Ticket released mid-flight: refuse loudly rather than let
                // the vanilla encoder serialize the empty-payload shell.
                throwBodyUnavailable(ctx, msg, promise, null);
                return;
            }
            // Ordinary Java packet: vanilla encoder runs untouched.
            ctx.write(msg, promise);
        }

        private void throwBodyUnavailable(ChannelHandlerContext ctx, Object msg,
                                          ChannelPromise promise, Throwable cause) {
            T.staleTicketRefusals.incrementAndGet();
            IllegalStateException failure = new IllegalStateException(
                    "RustCraft single-copy body unavailable for an admitted packet; "
                            + "refusing malformed empty-payload serialization", cause);
            if (!promise.isDone()) {
                promise.setFailure(failure);
            }
            System.err.println("[RustCraft-SingleCopy] " + failure);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            releaseForChannel(false);
            super.channelInactive(ctx);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            releaseForChannel(false);
            super.handlerRemoved(ctx);
        }

        /** Drop single-consumer tickets written by this connection. Shared
         *  bodies (broadcast) are left to their own quiescence checks and the
         *  sweep — another watcher's write may still be pending. */
        private void releaseForChannel(boolean forced) {
            for (ConcurrentHashMap.Entry<Object, SingleCopyTicket> e : writtenHere.entrySet()) {
                SingleCopyTicket ticket = e.getValue();
                if (writtenHere.remove(e.getKey(), ticket)) {
                    if (!forced && ticket.totalWrites.get() > 1) {
                        continue; // shared body: not solely this connection's
                    }
                    if (ticket.outstandingWrites.get() == 0 && ticket.invalidate()) {
                        T.releasedViaChannelClose.incrementAndGet();
                    }
                }
            }
        }

        // Test hook: force-release everything this channel wrote.
        void releaseForChannelForTest() {
            releaseForChannel(true);
        }
    }

    // ------------------------------------------------------------------
    // Quiescence release (single-consumer bodies)
    // ------------------------------------------------------------------

    static void scheduleQuiescenceRelease(EventExecutor executor, SingleCopyTicket ticket) {
        try {
            executor.schedule(() -> {
                runQuiescenceCheck(ticket);
            }, QUIESCENCE_DELAY_NANOS, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (Throwable ignore) {
            // Executor rejecting (shutdown): the sweep still covers the body.
        }
    }

    /** The quiescence check body, shared by the scheduled task and tests. */
    static void runQuiescenceCheck(SingleCopyTicket ticket) {
        if (!ticket.released.get()
                && ticket.totalWrites.get() == 1
                && ticket.outstandingWrites.get() == 0
                && ticket.invalidate()) {
            T.releasedViaQuiescence.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // Shadow capture (installed only in shadow mode)
    // ------------------------------------------------------------------

    private static final ThreadLocal<ByteBuf> EXPECTED = new ThreadLocal<>();
    private static final ThreadLocal<SingleCopyTicket> EXPECTED_TICKET = new ThreadLocal<>();

    public static final class BodyCaptureHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ByteBuf expected = EXPECTED.get();
            if (expected != null && msg instanceof ByteBuf) {
                ByteBuf actual = (ByteBuf) msg;
                boolean match = actual.readableBytes() == expected.readableBytes()
                        && bytesEqual(actual, expected);
                if (match) {
                    T.shadowMatches.incrementAndGet();
                } else if (chunkChangedSinceFreeze(EXPECTED_TICKET.get())) {
                    // The chunk mutated between the body freeze and this live
                    // serialization: the Java packet legitimately carries a
                    // newer state. Concurrency, not an encoder divergence.
                    T.shadowExpectedDivergences.incrementAndGet();
                } else {
                    // Byte-different but possibly the same world state: a
                    // vanilla hashmap palette never shrinks, so a chunk with
                    // block churn carries stale palette entries the native
                    // minimal palette omits. Both are valid Protocol-340
                    // encodings; only a real state difference is a mismatch.
                    SingleCopyBodyCodec.ParsedBody expectedBody = SingleCopyBodyCodec.parse(expected);
                    SingleCopyBodyCodec.ParsedBody actualBody = SingleCopyBodyCodec.parse(actual);
                    if (SingleCopyBodyCodec.decodeEquivalent(expectedBody, actualBody)) {
                        T.shadowEncodingDivergences.incrementAndGet();
                    } else if (bodyIsStale(EXPECTED_TICKET.get(), actualBody)) {
                        // Arbitration: the chunk's CURRENT faithful state equals
                        // what Java transmitted, so the frozen body encoded an
                        // earlier state (concurrent mutation, including
                        // off-tracker in-place writes). Expected divergence.
                        T.shadowExpectedDivergences.incrementAndGet();
                    } else {
                        T.shadowMismatches.incrementAndGet();
                        logShadowMismatch(expected, actual);
                        logDecodeDifference(expected, actual, expectedBody, actualBody);
                    }
                }
                EXPECTED.remove();
                EXPECTED_TICKET.remove();
            }
            ctx.write(msg, promise);
        }

        private static void logDecodeDifference(ByteBuf expected, ByteBuf actual,
                                                SingleCopyBodyCodec.ParsedBody e,
                                                SingleCopyBodyCodec.ParsedBody a) {
            if (e == null || a == null) {
                System.err.println(String.format(
                        "[RustCraft-SingleCopy] decode-diff: parse failed exp=[%s] act=[%s]",
                        e == null ? SingleCopyBodyCodec.parseDebug(expected)
                                  : "ok",
                        a == null ? SingleCopyBodyCodec.parseDebug(actual)
                                  : "ok"));
                return;
            }
            System.err.println(String.format(
                    "[RustCraft-SingleCopy] decode-diff: id %d/%d xyz %d,%d/%d,%d full %b/%b mask %04x/%04x",
                    e.packetId, a.packetId, e.chunkX, e.chunkZ, a.chunkX, a.chunkZ,
                    e.fullChunk, a.fullChunk, e.mask, a.mask));
            for (int sec = 0; sec < 16; sec++) {
                SingleCopyBodyCodec.ParsedSection es = e.sections[sec];
                SingleCopyBodyCodec.ParsedSection as = a.sections[sec];
                if (es == null && as == null) continue;
                if (es == null || as == null) {
                    System.err.println("  section " + sec + " present exp=" + (es != null)
                            + " act=" + (as != null));
                    continue;
                }
                int cellDiff = -1;
                for (int i = 0; i < 4096; i++) {
                    if (es.cells[i] != as.cells[i]) { cellDiff = i; break; }
                }
                int lightDiff = -1;
                for (int i = 0; i < 2048; i++) {
                    if (es.blockLight[i] != as.blockLight[i]) { lightDiff = i; break; }
                }
                int skyDiff = -1;
                if (es.skyLight != null && as.skyLight != null) {
                    for (int i = 0; i < 2048; i++) {
                        if (es.skyLight[i] != as.skyLight[i]) { skyDiff = i; break; }
                    }
                }
                System.err.println(String.format(
                        "  section %d: firstCellDiff=%d (exp=%d act=%d) blockLightDiff=%d skyLightDiff=%d",
                        sec, cellDiff,
                        cellDiff >= 0 ? es.cells[cellDiff] : -1,
                        cellDiff >= 0 ? as.cells[cellDiff] : -1,
                        lightDiff, skyDiff));
            }
        }

        /**
         * True when the chunk's CURRENT faithful state equals the
         * Java-transmitted payload semantics - proving the frozen body is a
         * stale (concurrently superseded) snapshot, not a wrong encoding.
         */
        private static boolean bodyIsStale(SingleCopyTicket ticket,
                                           SingleCopyBodyCodec.ParsedBody actualBody) {
            if (ticket == null || ticket.shadowChunkRef == null || actualBody == null) {
                return false;
            }
            Object chunk = ticket.shadowChunkRef.get();
            if (chunk == null) {
                return false;
            }
            Object[] faithful = com.rustcraft.bridge.M4Coherency
                    .extractFaithfulForArbitration(chunk);
            if (faithful == null) {
                return false; // unarbitratable: stays a mismatch (conservative)
            }
            for (int sec = 0; sec < 16; sec++) {
                SingleCopyBodyCodec.ParsedSection actual = actualBody.sections[sec];
                Object[] faithfulSection = (Object[]) faithful[sec];
                if (actual == null && faithfulSection == null) {
                    continue;
                }
                if (actual == null || faithfulSection == null) {
                    // One side has the section and the other does not. Java
                    // omits empty sections from the mask; the frozen body's
                    // mask came from the same rule, so a disagreement at
                    // arbitration time is a state change - stale body.
                    return faithfulSection == null;
                }
                int[] gids = (int[]) faithfulSection[0];
                for (int i = 0; i < 4096; i++) {
                    if (gids[i] != actual.cells[i]) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean chunkChangedSinceFreeze(SingleCopyTicket ticket) {
            if (ticket == null || ticket.shadowChunkRef == null) {
                return false;
            }
            Object chunk = ticket.shadowChunkRef.get();
            if (chunk == null) {
                return false; // unreachable: unclassifiable, counts as mismatch
            }
            return ChunkMutationTracker.versionOf(chunk) != ticket.shadowMutationVersion;
        }

        private static boolean bytesEqual(ByteBuf a, ByteBuf b) {
            int len = a.readableBytes();
            int ai = a.readerIndex();
            int bi = b.readerIndex();
            for (int i = 0; i < len; i++) {
                if (a.getByte(ai + i) != b.getByte(bi + i)) {
                    return false;
                }
            }
            return true;
        }

        private ChannelHandlerContext lastCtx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            this.lastCtx = ctx;
            super.handlerAdded(ctx);
        }

        private static String head16(ByteBuf buf) {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(16, buf.readableBytes());
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02x", buf.getByte(buf.readerIndex() + i) & 0xFF));
            }
            return sb.toString();
        }

        private static void logShadowMismatch(ByteBuf expected, ByteBuf actual) {
            int expLen = expected.readableBytes();
            int actLen = actual.readableBytes();
            int first = -1;
            int n = Math.min(expLen, actLen);
            for (int i = 0; i < n; i++) {
                if (expected.getByte(expected.readerIndex() + i)
                        != actual.getByte(actual.readerIndex() + i)) {
                    first = i;
                    break;
                }
            }
            String context = "";
            if (first >= 0) {
                StringBuilder e = new StringBuilder();
                StringBuilder a = new StringBuilder();
                int from = Math.max(0, first - 4);
                int to = Math.min(Math.min(expLen, actLen), first + 4);
                for (int i = from; i < to; i++) {
                    e.append(String.format("%02x", expected.getByte(expected.readerIndex() + i) & 0xFF));
                    a.append(String.format("%02x", actual.getByte(actual.readerIndex() + i) & 0xFF));
                }
                context = String.format(" exp[%d..%d]=%s act=%s",
                        from, to - 1, e, a);
            }
            System.err.println(String.format(
                    "[RustCraft-SingleCopy] SHADOW MISMATCH len expected=%d actual=%d firstDivergence=%s"
                            + " expHead=%s actHead=%s%s",
                    expLen, actLen, first < 0 ? "none(len only)" : Integer.toString(first),
                    head16(expected), head16(actual), context));
        }
    }

    // ------------------------------------------------------------------
    // Defensive sweep + shutdown
    // ------------------------------------------------------------------

    /** Defensive safety net (NOT the primary lifecycle): releases tickets
     *  older than the sweep age. Called from the authority admission path. */
    public static int sweepExpired() {
        if (TICKETS.isEmpty()) {
            return 0;
        }
        long now = System.nanoTime();
        int evicted = 0;
        for (SingleCopyTicket ticket : TICKETS.values()) {
            if (now - ticket.createdAtNanos > SWEEP_EXPIRATION_NANOS
                    && ticket.invalidate()) {
                T.releasedViaSweep.incrementAndGet();
                evicted++;
            }
        }
        return evicted;
    }

    /** Release every outstanding body (shutdown hook + tests). */
    public static int releaseAllOutstanding() {
        int released = 0;
        for (SingleCopyTicket ticket : TICKETS.values()) {
            if (ticket.invalidate()) {
                released++;
            }
        }
        return released;
    }

    /** Test/startup hook: clear any residual state. */
    public static void resetForTesting() {
        releaseAllOutstanding();
        T.outstandingBodies.set(0);
        T.outstandingBytes.set(0);
        SLOT_RESERVATIONS.set(0);
        T.highWaterBodies = 0;
        T.highWaterBytes = 0;
    }

    /** Note outstanding body bytes for the byte bound + high-water marks. */
    static void noteOutstandingBytes(int bodyBytes) {
        long now = T.outstandingBytes.addAndGet(bodyBytes);
        if (now > T.highWaterBytes) {
            T.highWaterBytes = now;
        }
    }

    public static String telemetrySummary() {
        return "single_copy_committed=" + T.committed.get()
                + " vanilla_encoder_bypassed=" + T.vanillaEncoderBypassed.get()
                + " packet_encoder_invocations=" + T.packetEncoderInvocations.get()
                + " packetbuffer_payload_copy_count=" + T.packetbufferPayloadCopies.get()
                + " buffers_created=" + T.buffersCreated.get()
                + " buffers_released=" + T.buffersReleased.get()
                + " outstanding=" + T.outstandingBodies.get()
                + " high_water_bodies=" + T.highWaterBodies
                + " high_water_bytes=" + T.highWaterBytes
                + " multi_consumer=" + T.multiConsumerBodies.get()
                + " released{quiescence=" + T.releasedViaQuiescence.get()
                + ",channel=" + T.releasedViaChannelClose.get()
                + ",sweep=" + T.releasedViaSweep.get()
                + ",shutdown=" + T.releasedViaShutdown.get()
                + "} stale_refusals=" + T.staleTicketRefusals.get()
                + " two_copy_serves=" + T.fallbackTwoCopyServes.get()
                + " shadow_matches=" + T.shadowMatches.get()
                + " shadow_mismatches=" + T.shadowMismatches.get()
                + " shadow_expected_divergences=" + T.shadowExpectedDivergences.get()
                + " shadow_encoding_divergences=" + T.shadowEncodingDivergences.get()
                + " measure_failures=" + T.measureFailures.get()
                + " encode_failures=" + T.encodeFailures.get()
                + " measure_encode_divergences=" + T.measureEncodeDivergences.get()
                + " pressure_fallbacks=" + T.pressureFallback.get()
                + " handler_errors=" + T.handlerErrors.get()
                + " installs=" + T.installs.get()
                + " install_failures=" + T.installFailures.get();
    }

    // Convenience accessors used by tests/receipts.
    public static long outstandingBodyCount() {
        return T.outstandingBodies.get();
    }
}
