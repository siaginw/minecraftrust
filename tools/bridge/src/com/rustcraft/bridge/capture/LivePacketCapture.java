package com.rustcraft.bridge.capture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Live packet capture admission (Issue #1 live-SHADOW diagnostic; default OFF).
 *
 * <p>Converts one admitted SPacketChunkData construction into exactly one SEALED
 * immutable capture, following the accepted handoff ordering:</p>
 *
 * <pre>
 * constructor entry  → begin:    gate.tryBeginCapture (one tryLock, never waits)
 *                              → draft extraction under the held gate
 * constructor body   → Java-authoritative, unchanged
 * before return      → commit:   end view re-read + packet readout + final
 *                              validation → seal once → gate.endCapture(null)
 *                              → nonblocking queue offer
 * Throwable          → abort:    gate.endCapture(throwable), publish nothing,
 *                              original Throwable propagates unchanged
 * </pre>
 *
 * <p>DEFAULT OFF: with no diagnostic session every entry returns null after a
 * counter increment — no admission, no draft allocation, no queue activity, no
 * changed packet bytes. Admission never waits, never weakens UNKNOWN/async/raw
 *-array exclusions, and never identifies a chunk by coordinates alone: the gate
 * resolves the exact binding by Chunk/World object identity and requires READY,
 * unrevoked, source-qualified state.</p>
 *
 * <p>TE rule: a nonempty TileEntity map rejects capture at begin AND at commit
 * (TE_PRESENT). Java continues normally in every rejected path.</p>
 */
public final class LivePacketCapture {

    /** Bounded queue capacity and aggregate owned-byte budget (handoff constants). */
    public static final int QUEUE_CAPACITY = 8;
    public static final long QUEUE_MAX_BYTES = 8L * 1024 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 262144;

    /** One terminal rejection reason per attempt; never a best-effort success. */
    public enum RejectionReason {
        DISABLED, NO_PINNED_EXTRACTOR, SOURCE_UNQUALIFIED, INVALID_INPUT,
        CAPTURE_BUSY, WRITER_ACTIVE, PUBLICATION_ACTIVE, NESTED_CAPTURE,
        RUNTIME_DISQUALIFIED, OFF_THREAD, NO_BINDING, NOT_READY, UNLOADED,
        PROVIDER_IDENTITY_CHANGED, UNSUPPORTED_WORLD, UNSUPPORTED_REGISTRY,
        UNRESOLVED_STATE, NONCANONICAL_STORAGE, EXTENDED_ID, UNSUPPORTED_STORAGE,
        INVALID_FILTER, TE_PRESENT, EXTRACTION_FAILED, PACKET_MISMATCH,
        MASK_MISMATCH, EPOCH_CHANGED, VALIDATION_FAILED, SOURCE_EXCEPTION,
        QUEUE_FULL, QUEUE_BYTES_EXHAUSTED
    }

    /** Carries one rejection out of the extractor; Java continues normally. */
    public static final class Rejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final RejectionReason reason;

        Rejection(RejectionReason reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }
    }

    static Rejection reject(RejectionReason reason, String detail) {
        return new Rejection(reason, detail);
    }

    /** The Java packet readout seam (SPacket fields for the real runtime). */
    public interface JavaPacketView {
        byte[] payload();
        int mask();
        boolean fullChunk();
        int packetX();
        int packetZ();
    }

    /**
     * The pinned per-runtime capture source. Implemented by LiveForgeCaptureSource
     * for the qualified transformed runtime; registered explicitly so the
     * foundation test JVM never class-loads runtime-specific code.
     */
    public interface LiveCaptureSource extends CaptureSource {
        Object world();
        Object chunk();
        JavaPacketView javaPacket(Object packet);
        boolean tileEntitiesEmpty();
    }

    /** Installed by the diagnostic runtime; null means no pinned extractor exists. */
    public interface SourceFactory {
        LiveCaptureSource create(Object packet, Object chunk, int filter,
                                 LiveChunkBindings.Binding binding, LiveWriterGate gate);
    }

    private static volatile SourceFactory sourceFactory;
    private static volatile RejectionReason lastRejection;

    public static final java.util.concurrent.atomic.AtomicLong TE_PRESENT_REJECTIONS =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile String lastTeRejection;

    /** Evidence for the last TE_PRESENT rejection (coords + TE map size), or null. */
    public static String lastTeRejectionEvidence() { return lastTeRejection; }

    /** Last begin-time rejection (diagnostic evidence; not synchronization). */
    public static RejectionReason lastRejection() { return lastRejection; }

    private static final java.util.EnumMap<RejectionReason, AtomicLong> REASON_COUNTERS =
            new java.util.EnumMap<RejectionReason, AtomicLong>(RejectionReason.class);
    static {
        for (RejectionReason reason : RejectionReason.values()) {
            REASON_COUNTERS.put(reason, new AtomicLong());
        }
    }

    /** Per-reason rejection counts (diagnostic evidence; every reason kept separate). */
    public static java.util.Map<RejectionReason, Long> rejectionReasonCounts() {
        java.util.Map<RejectionReason, Long> snapshot =
                new java.util.EnumMap<RejectionReason, Long>(RejectionReason.class);
        for (java.util.Map.Entry<RejectionReason, AtomicLong> entry : REASON_COUNTERS.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    public static void installSourceFactory(SourceFactory factory) {
        sourceFactory = factory;
    }

    public static SourceFactory installedSourceFactory() { return sourceFactory; }

    /** Diagnostic counters (never used as synchronization; never authorizing). */
    public static final AtomicLong SEEN = new AtomicLong();
    public static final AtomicLong ADMITTED = new AtomicLong();
    public static final AtomicLong REJECTED = new AtomicLong();
    public static final AtomicLong SEALED = new AtomicLong();
    public static final AtomicLong ENQUEUED = new AtomicLong();
    public static final AtomicLong QUEUE_DROPPED = new AtomicLong();
    public static final AtomicLong REVOKED_DROPPED = new AtomicLong();
    public static final AtomicLong UNSUPPORTED = new AtomicLong();
    public static final AtomicLong CAPTURE_BUSY = new AtomicLong();
    public static final AtomicLong VALIDATION_FAILED = new AtomicLong();
    public static final AtomicLong ABORTED = new AtomicLong();

    private LivePacketCapture() { }

    /**
     * Constructor-entry admission. Returns the opaque attempt token, or null when
     * the event falls back to Java-only (default OFF, busy, rejected, unsupported).
     * On the admitted path the gate is held and the draft extracted; any extractor
     * failure discards the attempt and releases the gate exactly once.
     */
    public static Object begin(Object packet, Object chunk, int filter) {
        LiveWriterHooks.Session session = LiveWriterHooks.currentSessionInternal();
        if (session == null) {
            return null; // default OFF: inert, nothing allocated
        }
        SEEN.incrementAndGet();
        if (chunk == null || packet == null || (filter & ~0xFFFF) != 0) {
            REJECTED.incrementAndGet();
            return null;
        }
        SourceFactory factory = sourceFactory;
        if (factory == null) {
            UNSUPPORTED.incrementAndGet();
            return rejected(session, RejectionReason.NO_PINNED_EXTRACTOR, "no pinned extractor installed");
        }
        LiveChunkBindings.Binding binding = session.bindings.bindingFor(chunk);
        if (binding == null) {
            REJECTED.incrementAndGet();
            return rejected(session, RejectionReason.NO_BINDING, "no exact binding for the chunk object");
        }
        LiveCaptureSource source;
        try {
            source = factory.create(packet, chunk, filter, binding, session.gate);
        } catch (Throwable failure) {
            REJECTED.incrementAndGet();
            return rejected(session, RejectionReason.SOURCE_EXCEPTION, String.valueOf(failure));
        }
        if (source == null) {
            REJECTED.incrementAndGet();
            return rejected(session, RejectionReason.UNSUPPORTED_WORLD,
                    "world/provider is not the qualified surface profile");
        }
        try {
            if (!source.tileEntitiesEmpty()) {
                TE_PRESENT_REJECTIONS.incrementAndGet();
                lastTeRejection = "chunk=" + chunk + " coords=" + chunkCoords(chunk)
                        + " at " + System.currentTimeMillis();
                REJECTED.incrementAndGet();
                return rejected(session, RejectionReason.TE_PRESENT,
                        "TE-bearing chunks are Java-only in this profile");
            }
        } catch (Throwable failure) {
            REJECTED.incrementAndGet();
            return rejected(session, RejectionReason.SOURCE_EXCEPTION, String.valueOf(failure));
        }
        LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(source.world(), chunk);
        if (attempt == null || !attempt.admitted()) {
            if (attempt == null) {
                return rejected(session, RejectionReason.INVALID_INPUT, "null attempt");
            }
            return rejected(session, fallbackReason(attempt.fallbackReason()),
                    String.valueOf(attempt.fallbackReason()));
        }
        try {
            CaptureDraft draft = CaptureDraft.extract(source, attempt, binding, filter);
            ADMITTED.incrementAndGet();
            return new AttemptToken(session, attempt, draft);
        } catch (Rejection rejection) {
            // Discard + release exactly once; the constructor still runs Java-only.
            session.gate.endCapture(attempt, rejection);
            REJECTED.incrementAndGet();
            return rejected(session, rejection.reason, rejection.getMessage());
        } catch (Throwable failure) {
            session.gate.endCapture(attempt, failure);
            REJECTED.incrementAndGet();
            return rejected(session, RejectionReason.EXTRACTION_FAILED, String.valueOf(failure));
        }
    }

    /**
     * Constructor-success commit: end view + packet readout + final validation
     * under the still-held gate, seal once, commit the gate, then a bounded
     * nonblocking queue offer. Any failure discards everything; the packet's
     * Java state is never touched.
     */
    public static void commit(Object token, Object packet, Object chunk, int filter) {
        AttemptToken attemptToken = token(token);
        if (attemptToken == null || attemptToken.consumed) return;
        attemptToken.consumed = true; // seal-at-most-once: a replayed commit is a no-op
        LiveWriterHooks.Session session = attemptToken.session;
        try {
            CaptureDraft draft = attemptToken.draft;
            LiveWriterGate.CaptureAttempt outcome = attemptToken.attempt;
            // Final validation while the writer protocol still protects the event.
            draft.validateForSeal();
            SealedLiveCapture sealed = draft.seal(packet, outcome.eventId());
            // Capture commit: releases the gate exactly once after sealing.
            if (!session.gate.endCapture(outcome, null)) {
                VALIDATION_FAILED.incrementAndGet();
                REJECTED.incrementAndGet();
                return;
            }
            SEALED.incrementAndGet();
            LiveComparisonQueue.OfferResult offer = LiveComparisonQueue.offer(sealed);
            if (offer.accepted) ENQUEUED.incrementAndGet();
            else QUEUE_DROPPED.incrementAndGet();
        } catch (Rejection rejection) {
            releaseQuietly(attemptToken, rejection);
            VALIDATION_FAILED.incrementAndGet();
            REJECTED.incrementAndGet();
        } catch (Throwable failure) {
            releaseQuietly(attemptToken, failure);
            VALIDATION_FAILED.incrementAndGet();
            REJECTED.incrementAndGet();
        }
    }

    /**
     * Compensating release after a failed commit. When the failure itself came
     * from endCapture (gate no longer held), the release already happened; never
     * mask the original failure with a second throw.
     */
    private static void releaseQuietly(AttemptToken attemptToken, Throwable failure) {
        try {
            attemptToken.session.gate.endCapture(attemptToken.attempt, failure);
        } catch (Throwable alreadyReleased) {
            if (alreadyReleased != failure) failure.addSuppressed(alreadyReleased);
        }
    }

    /** Constructor-throw abort: discard everything; the caller rethrows unchanged. */
    public static void abort(Object token, Throwable throwable) {
        AttemptToken attemptToken = token(token);
        if (attemptToken == null || attemptToken.consumed) return;
        attemptToken.consumed = true;
        ABORTED.incrementAndGet();
        attemptToken.session.gate.endCapture(attemptToken.attempt, throwable);
    }

    // ------------------------------------------------------------------

    private static final class AttemptToken {
        final LiveWriterHooks.Session session;
        final LiveWriterGate.CaptureAttempt attempt;
        final CaptureDraft draft;
        boolean consumed; // one commit or one abort per admitted attempt; owner-thread only

        AttemptToken(LiveWriterHooks.Session session, LiveWriterGate.CaptureAttempt attempt, CaptureDraft draft) {
            this.session = session;
            this.attempt = attempt;
            this.draft = draft;
        }
    }

    /** Descriptive chunk coords via runtime reflection (never used as identity). */
    private static String chunkCoords(Object chunk) {
        try {
            Object world = null;
            java.lang.reflect.Field wf = chunk.getClass().getDeclaredField("field_76637_e");
            wf.setAccessible(true);
            world = wf.get(chunk);
            java.lang.reflect.Field xf = chunk.getClass().getDeclaredField("field_76635_g");
            xf.setAccessible(true);
            java.lang.reflect.Field zf = chunk.getClass().getDeclaredField("field_76647_h");
            zf.setAccessible(true);
            return "x=" + xf.getInt(chunk) + " z=" + zf.getInt(chunk)
                    + (world != null ? " world=" + world.getClass().getSimpleName() : "");
        } catch (Throwable failure) {
            return "unavailable";
        }
    }

    private static AttemptToken token(Object token) {
        return token instanceof AttemptToken ? (AttemptToken) token : null;
    }

    private static Object rejected(LiveWriterHooks.Session session, RejectionReason reason, String detail) {
        lastRejection = reason;
        classify(reason);
        return null;
    }

    private static Object rejectedAtCommit(LiveWriterHooks.Session session, String detail) {
        REJECTED.incrementAndGet();
        return null;
    }

    private static void classify(RejectionReason reason) {
        REASON_COUNTERS.get(reason).incrementAndGet();
        switch (reason) {
            case CAPTURE_BUSY: case WRITER_ACTIVE: case PUBLICATION_ACTIVE: case NESTED_CAPTURE:
                CAPTURE_BUSY.incrementAndGet();
                break;
            case RUNTIME_DISQUALIFIED: case OFF_THREAD:
                UNSUPPORTED.incrementAndGet();
                break;
            case UNLOADED: case NO_BINDING: case NOT_READY: case PROVIDER_IDENTITY_CHANGED:
                REVOKED_DROPPED.incrementAndGet();
                break;
            case UNSUPPORTED_WORLD: case UNSUPPORTED_REGISTRY: case UNRESOLVED_STATE:
            case NONCANONICAL_STORAGE: case EXTENDED_ID: case UNSUPPORTED_STORAGE:
            case INVALID_FILTER: case TE_PRESENT:
                UNSUPPORTED.incrementAndGet();
                break;
            default:
                REJECTED.incrementAndGet();
        }
    }

    private static RejectionReason fallbackReason(LiveWriterGate.FallbackReason reason) {
        switch (reason) {
            case WRITER_ACTIVE: return RejectionReason.WRITER_ACTIVE;
            case PUBLICATION_ACTIVE: return RejectionReason.PUBLICATION_ACTIVE;
            case NESTED_CAPTURE: return RejectionReason.NESTED_CAPTURE;
            case CAPTURE_BUSY: return RejectionReason.CAPTURE_BUSY;
            case RUNTIME_DISQUALIFIED: return RejectionReason.RUNTIME_DISQUALIFIED;
            case OFF_THREAD: return RejectionReason.OFF_THREAD;
            case NO_BINDING: return RejectionReason.NO_BINDING;
            case NOT_READY: return RejectionReason.NOT_READY;
            case UNLOADED: return RejectionReason.UNLOADED;
            case PROVIDER_IDENTITY_CHANGED: return RejectionReason.PROVIDER_IDENTITY_CHANGED;
            case DISABLED: return RejectionReason.DISABLED;
            case SOURCE_UNQUALIFIED: return RejectionReason.SOURCE_UNQUALIFIED;
            case INVALID_INPUT: return RejectionReason.INVALID_INPUT;
            default: return RejectionReason.INVALID_INPUT;
        }
    }
}
