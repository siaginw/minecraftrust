package com.rustcraft.bridge.capture;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-session Java writer/capture gate for the Issue #1 live-SHADOW writer
 * protocol (handoff family F: canonical-owner capture + one server-session
 * Java gate + explicit private-I/O release/acquire transfer + exact lifecycle
 * generations).
 *
 * <p>This is the foundation stage only: the class is inert unless a diagnostic
 * session explicitly enables it, and no transformer, capability flag, or
 * production path references it. Production native packet authority remains
 * fail-closed in {@code M4NativeStatePayload.tryEncode}, which this class never
 * touches. There is no JNI anywhere in the gate; ordinary Java writes never
 * cross into Rust for synchronization.</p>
 *
 * <p>Scope: one gate per server session, never one lock per chunk or
 * dimension. The gate is a non-fair {@link ReentrantLock} owned by the exact
 * canonical server Thread. Only the canonical owner ever acquires the gate;
 * off-owner published writes never wait on it — they are a terminal
 * before-write protocol violation. Capture admission uses exactly one
 * {@code tryLock} and falls back immediately when busy. Private I/O workers
 * holding a {@link PrivateBuildTickets.Ticket} bypass the gate only for their
 * exact privately-owned receiver/backing closure.</p>
 *
 * <h2>MEMORY-ORDER CONTRACT (JLS 17.4.5 / java.util.concurrent.locks.Lock)</h2>
 *
 * <p>All synchronization below is expressed with Java language and
 * {@code java.util.concurrent} primitives only. No timing, scheduling, or
 * thread-naming assumption is ever used as synchronization.</p>
 * <ol>
 * <li><b>Happens-before lock.</b> The single session {@code ReentrantLock}
 * (field {@code gate}) establishes happens-before: a successful {@code lock()}
 * or {@code tryLock()} has the same memory synchronization effects as a
 * successful monitor acquire, and every {@code unlock()} the same effects as a
 * monitor release. All gate-protected state ({@code epoch}, {@code
 * writerDepth}, {@code publicationDepth}, {@code activeAttempt}, binding and
 * component owner-side transitions) is read or written only while the current
 * thread holds that lock (or, for the private-worker paths, only by the single
 * worker thread that owns the ticket scope).</li>
 * <li><b>Writer entry.</b> {@link #beginWrite} acquires the gate before the
 * caller performs its first protected mutation. Entry therefore happens-after
 * the previous holder's exit: the writer observes every earlier binding
 * transition, publication finalization, and capture commit. The outermost
 * entry advances the positive nonwrapping {@code epoch} under the lock, so any
 * later capture commit comparing epochs detects the interleaving.</li>
 * <li><b>Writer exit.</b> {@link #endWrite} releases the gate in a
 * {@code finally} on every return and Throwable path, exactly once. Everything
 * the writer did happens-before the next {@code lock()}/{@code tryLock()} — in
 * particular before the next capture admission and before the capture commit
 * check.</li>
 * <li><b>Capture admission.</b> {@link #tryBeginCapture} performs exactly one
 * {@code tryLock()}; success gives monitor-acquire semantics, so the attempt
 * observes the READY binding published by the publication transaction's
 * earlier unlock. Failure never waits: the caller falls back to Java. The
 * entry {@code epoch0} is read under the same lock.</li>
 * <li><b>Capture invalidation.</b> A reentrant owner {@code beginWrite} while
 * an attempt is active invalidates the attempt (volatile
 * {@code CaptureAttempt.invalidated}) and advances the epoch under the lock
 * BEFORE returning, i.e. before the caller mutates. The commit check in
 * {@link #endCapture} re-reads invalidation and epoch under the same still-held
 * lock, so the invalidation is always visible at commit time. Off-owner
 * writers need no ordering at all: they throw before performing any write.</li>
 * <li><b>I/O ticket publication.</b> The worker publishes the private graph
 * through the volatile {@code Ticket.completion} record: sealing first
 * transitions the ticket state (revoking private-write permission) and then
 * writes {@code completion}. The owner's {@code acquireCompletion} performs
 * the volatile read (acquire), which happens-after the worker's volatile write
 * (release); all of the worker's registry writes and final fields of the
 * ticket, completion, and component records are therefore visible to the
 * owner's adoption. This is the release/acquire transfer of the private graph.</li>
 * <li><b>Publication/sealing visibility.</b> Binding state transitions,
 * revocation-token writes, and publication finalization occur under the gate,
 * so capture admission and the commit check observe them via the lock. The
 * opaque {@code RevocationToken} is additionally volatile so queue consumers
 * without the gate can re-check revocation before recording a result.</li>
 * <li><b>Exception/finally behavior.</b> Every acquired gate (writer scope or
 * capture attempt) is released exactly once in {@code finally} on all
 * Throwable paths. A Throwable escaping a participating writer scope
 * disqualifies the session terminally (WRITE_EXCEPTION) before unlocking; it
 * never clears a disqualification and never leaves the lock held. NOOP and
 * PRIVATE scopes never acquire the lock, so they cannot leak it. ThreadLocal
 * token stacks enforce per-thread balance; an unbalanced exit is itself a
 * terminal HOOK_PROTOCOL_BROKEN violation.</li>
 * <li><b>Coverage limits.</b> None of these edges orders a writer that does
 * not participate. Arbitrary raw-array writers are excluded by policy (they
 * make the profile unqualified); they are not synchronized by this gate.</li>
 * </ol>
 */
public final class LiveWriterGate {

    /** Terminal before-write protocol violations; each disqualifies the session. */
    public enum DisqualificationReason {
        OFF_OWNER_PUBLISHED_WRITE, PRIVATE_ALIAS_WRITE, WRITE_EXCEPTION,
        HOOK_PROTOCOL_BROKEN, PRIVATE_GRAPH_ESCAPE, RESOURCE_LIMIT, COUNTER_EXHAUSTED
    }

    /** One terminal fallback reason per capture attempt (foundation subset of the full handoff vocabulary). */
    public enum FallbackReason {
        DISABLED, OFF_THREAD, WRITER_ACTIVE, PUBLICATION_ACTIVE, NESTED_CAPTURE,
        CAPTURE_BUSY, RUNTIME_DISQUALIFIED, NO_BINDING, NOT_READY, UNLOADED,
        PROVIDER_IDENTITY_CHANGED, SOURCE_UNQUALIFIED, INVALID_INPUT, ID_EXHAUSTED
    }

    /** Why an admitted capture attempt was invalidated before its commit point. */
    public enum InvalidationReason { REENTRANT_WRITE }

    /** One terminal outcome per admitted attempt. */
    public enum CaptureOutcome {
        IN_FLIGHT, SEALED_COMMITTED, JAVA_CONSTRUCTOR_EXCEPTION,
        REENTRANT_WRITE, EPOCH_CHANGED, DEPTH_CHANGED, BINDING_STALE, RUNTIME_DISQUALIFIED
    }

    /** Thrown before the protected mutation on terminal before-write violations. */
    public static final class ProtocolViolationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final DisqualificationReason reason;

        ProtocolViolationException(DisqualificationReason reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }

        public DisqualificationReason reason() { return reason; }
    }

    /**
     * Balanced writer-scope token. The kind records which path admitted the
     * writer: HELD holds the gate, PRIVATE bypasses it for an exact private
     * ticket closure, NOOP is the disabled/quiesced no-enforcement path.
     */
    public static final class WriteToken {
        enum Kind { HELD, PRIVATE, NOOP }

        final Kind kind;
        final String operation;
        final PrivateBuildTickets.Ticket ticket; // PRIVATE only

        WriteToken(Kind kind, String operation, PrivateBuildTickets.Ticket ticket) {
            this.kind = kind;
            this.operation = operation;
            this.ticket = ticket;
        }

        public Kind kind() { return kind; }
        public String operation() { return operation; }
    }

    /**
     * One private, unencodable capture attempt bound to the exact world/chunk
     * objects and a minted event id. The attempt carries no snapshot, draft,
     * or transport: the foundation records the protocol outcome only; payload
     * extraction/sealing is a later, separately reviewed stage. Rejected
     * attempts (never admitted) are terminal fallbacks with exactly one
     * reason.
     */
    public static final class CaptureAttempt {
        private final long eventId;
        private final long epoch0;
        private final Object world;   // exact object identity; never exposed as a job reference
        private final Object chunk;   // exact object identity; never exposed as a job reference
        private final LiveChunkBindings.Binding binding;
        private volatile boolean invalidated;
        private volatile InvalidationReason invalidationReason;
        private volatile CaptureOutcome outcome = CaptureOutcome.IN_FLIGHT;
        private final boolean admitted;

        CaptureAttempt(long eventId, long epoch0, Object world, Object chunk,
                       LiveChunkBindings.Binding binding, boolean admitted, FallbackReason fallback) {
            this.eventId = eventId;
            this.epoch0 = epoch0;
            this.world = world;
            this.chunk = chunk;
            this.binding = binding;
            this.admitted = admitted;
            this.fallbackReason = fallback;
        }

        private final FallbackReason fallbackReason;

        /** True when the gate was acquired and the attempt is private and unencodable. */
        public boolean admitted() { return admitted; }

        /** Terminal fallback reason for a rejected attempt; undefined when admitted. */
        public FallbackReason fallbackReason() { return fallbackReason; }

        public long eventId() { return eventId; }

        /** Epoch observed at admission under the gate; the commit requires it unchanged. */
        public long entryEpoch() { return epoch0; }

        public boolean invalidated() { return invalidated; }

        public InvalidationReason invalidationReason() { return invalidationReason; }

        public CaptureOutcome outcome() { return outcome; }

        void invalidate(InvalidationReason reason) {
            if (!invalidated) { // first invalidation wins; one terminal outcome per attempt
                invalidated = true;
                invalidationReason = reason;
                outcome = CaptureOutcome.REENTRANT_WRITE;
            }
        }

        void terminal(CaptureOutcome result) {
            // One terminal outcome per attempt. Invalidation already recorded
            // REENTRANT_WRITE as terminal; nothing may overwrite it afterwards.
            if (outcome == CaptureOutcome.IN_FLIGHT) outcome = result;
        }
    }

    private static final AtomicLong SESSION_IDS = new AtomicLong();

    private final long sessionId = SESSION_IDS.incrementAndGet();
    private final Thread canonicalOwner;
    private final ReentrantLock gate = new ReentrantLock(false);
    private final ThreadLocal<ArrayDeque<WriteToken>> openTokens = new ThreadLocal<ArrayDeque<WriteToken>>();

    // Gate-protected state (owner thread, lock held unless noted).
    private long epoch = 1L;                 // positive, nonwrapping
    private int writerDepth;
    private int publicationDepth;
    private CaptureAttempt activeAttempt;
    private long nextEventId;                // positive, nonwrapping capture event identity
    private long nextPublicationTxnId;       // positive, nonwrapping owner transaction ids

    // Session lifecycle. enable()/shutdown are diagnostic-controller actions;
    // no production path ever calls them.
    private boolean enabled;
    private boolean shutDown;

    // Monotonic terminal disqualification (AtomicBoolean + volatile reason), never reset in-session.
    private final AtomicBoolean disqualified = new AtomicBoolean(false);
    private volatile DisqualificationReason disqualificationReason;

    // Collaborators attached once at session assembly.
    private PrivateBuildTickets tickets;
    private LiveChunkBindings bindings;

    // Diagnostic counters (volatile; never used as synchronization).
    private volatile long captureSeen, captureAdmitted, captureSealed, captureDiscarded, protocolViolations;

    public LiveWriterGate(Thread canonicalOwner) {
        if (canonicalOwner == null) throw new IllegalArgumentException("canonicalOwner required");
        this.canonicalOwner = canonicalOwner;
    }

    /** Session assembly: attach the ticket and binding registries (once, before enabling). */
    void attach(PrivateBuildTickets tickets, LiveChunkBindings bindings) {
        if (this.tickets != null || this.bindings != null) throw new IllegalStateException("already attached");
        if (enabled) throw new IllegalStateException("attach before enable");
        this.tickets = tickets;
        this.bindings = bindings;
    }

    /**
     * Explicitly enables the diagnostic session. Never called by any
     * production path in this stage; until called, every writer is NOOP and
     * every capture attempt falls back DISABLED.
     */
    void enable() {
        gate.lock();
        try {
            if (shutDown) throw new IllegalStateException("session already shut down");
            enabled = true;
        } finally {
            gate.unlock();
        }
    }

    /**
     * Ends the diagnostic session: requires full quiesce (no writer scope, no
     * publication transaction, no active attempt), revokes every binding, and
     * clears the identity registries. Afterwards all writers are NOOP and
     * capture is DISABLED. This is the only point that clears retained
     * identities.
     */
    void shutdownForSessionEnd() {
        if (!enabled) throw new IllegalStateException("gate never enabled");
        if (Thread.currentThread() != canonicalOwner) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("shutdown off owner");
        }
        gate.lock();
        try {
            if (writerDepth != 0 || publicationDepth != 0 || activeAttempt != null) {
                disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
                throw new IllegalStateException("shutdown requires fully quiesced attempts and workers");
            }
            shutDown = true;
            if (bindings != null) bindings.revokeAllForSessionEnd();
        } finally {
            gate.unlock();
        }
    }

    /** Terminal, monotonic disqualification. The first reason wins; never reset in-session. */
    void disqualify(DisqualificationReason reason) {
        if (disqualified.compareAndSet(false, true)) {
            disqualificationReason = reason;
            protocolViolations++;
        }
    }

    public long sessionId() { return sessionId; }

    public boolean isEnabled() { return enabled; }

    boolean isShutDown() { return shutDown; }

    public boolean isTerminalDisqualified() { return disqualified.get(); }

    public DisqualificationReason disqualificationReason() { return disqualificationReason; }

    public long epoch() { return epoch; }

    public int writerDepth() { return writerDepth; }

    public int publicationDepth() { return publicationDepth; }

    public long captureAttemptsSeen() { return captureSeen; }

    public long captureAttemptsAdmitted() { return captureAdmitted; }

    public long captureAttemptsSealed() { return captureSealed; }

    public long captureAttemptsDiscarded() { return captureDiscarded; }

    public long protocolViolations() { return protocolViolations; }

    // ------------------------------------------------------------------
    // Writer protocol
    // ------------------------------------------------------------------

    /**
     * Admits one whole protected write operation. Must be called before the
     * operation's first mutation of protected state, and balanced by exactly
     * one {@link #endWrite} on every return/Throwable path.
     *
     * <p>Order of admission (handoff pseudocode): disabled/quiesced NOOP;
     * exact private-ticket bypass for the receiver and every mutated backing
     * target; off-owner published writes are a terminal violation thrown
     * BEFORE the mutation without ever waiting on the gate; otherwise the
     * owner takes the gate, invalidates any active capture attempt before the
     * mutation, advances the epoch at the outermost depth, and increments the
     * writer depth.</p>
     */
    public WriteToken beginWrite(Object receiver, Object[] mutationTargets, String operation) {
        if (!enabled || shutDown) { // NOOP only when never enabled or fully quiesced shutdown
            WriteToken token = new WriteToken(WriteToken.Kind.NOOP, operation, null);
            openTokenStack().push(token);
            return token;
        }
        PrivateBuildTickets.Ticket activeTicket = tickets == null ? null : tickets.activeTicketForCurrentThread();
        if (tickets != null && tickets.canBypassPrivateWrite(receiver, mutationTargets)) {
            // Exact PRIVATE receiver and actual mutated backing-array ownership on
            // the current BUILDING ticket/creator; TLS alone never suffices.
            activeTicket.openPrivateScope();
            WriteToken token = new WriteToken(WriteToken.Kind.PRIVATE, operation, activeTicket);
            openTokenStack().push(token);
            return token;
        }
        if (Thread.currentThread() != canonicalOwner) {
            // Fatal diagnostic protocol violation BEFORE any write; never wait for
            // the gate (an I/O worker holding its provider monitor must not be made
            // to wait behind an owner holding the gate).
            DisqualificationReason reason = activeTicket != null
                    ? DisqualificationReason.PRIVATE_ALIAS_WRITE
                    : DisqualificationReason.OFF_OWNER_PUBLISHED_WRITE;
            disqualify(reason);
            throw new ProtocolViolationException(reason,
                    "off-owner published write; receiver=" + safeClass(receiver) + " op=" + operation);
        }
        gate.lock(); // owner, reentrant, always balanced by endWrite
        if (activeAttempt != null) {
            // Same-owner reentrant mutation invalidates the attempt and advances
            // the epoch BEFORE the caller writes, even if bytes are restored later.
            activeAttempt.invalidate(InvalidationReason.REENTRANT_WRITE);
        }
        if (writerDepth == 0) advanceEpochOrDisqualify();
        writerDepth++;
        WriteToken token = new WriteToken(WriteToken.Kind.HELD, operation, null);
        openTokenStack().push(token);
        return token;
    }

    /**
     * Closes one writer scope opened by {@link #beginWrite}. Must be called on
     * the same thread with the exact token returned, as the innermost open
     * scope. Releases the gate exactly once in finally on all paths.
     */
    public void endWrite(WriteToken token, Throwable throwable) {
        ArrayDeque<WriteToken> stack = openTokens.get();
        if (token == null || stack == null || stack.isEmpty() || stack.peek() != token) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("unbalanced endWrite (wrong thread, stale, or missing token)");
        }
        stack.pop();
        switch (token.kind) {
            case NOOP:
                return;
            case PRIVATE:
                if (throwable != null) token.ticket.markWriteFailed(); // irreversible, before scope close
                token.ticket.closePrivateScope();
                return;
            case HELD:
                try {
                    if (throwable != null) {
                        disqualify(DisqualificationReason.WRITE_EXCEPTION);
                        if (bindings != null) bindings.invalidatePendingPublications();
                    }
                    writerDepth--;
                    if (throwable == null && writerDepth == 0 && bindings != null) {
                        bindings.sweepWriterScopePendings();   // unattached allocations become tombstones
                        bindings.finalizePendingPublications(); // outermost normal success finalizes valid pendings
                    }
                } finally {
                    gate.unlock(); // exactly once, on every path; never clears disqualification
                }
                return;
            default:
                throw new IllegalStateException("unknown token kind");
        }
    }

    private void advanceEpochOrDisqualify() {
        if (epoch == Long.MAX_VALUE) disqualify(DisqualificationReason.COUNTER_EXHAUSTED);
        else epoch++;
    }

    private ArrayDeque<WriteToken> openTokenStack() {
        ArrayDeque<WriteToken> stack = openTokens.get();
        if (stack == null) {
            stack = new ArrayDeque<WriteToken>();
            openTokens.set(stack);
        }
        return stack;
    }

    // ------------------------------------------------------------------
    // Capture protocol
    // ------------------------------------------------------------------

    /**
     * Attempts nonblocking capture admission at the packet-constructor entry
     * point. Exactly one {@code tryLock}, never awaited. Requires the enabled
     * and unqualified-free session, the canonical owner, zero writer and
     * publication depth, no active attempt, and an exact READY unrevoked
     * source-qualified binding resolved by object identity (bindings are never
     * created here). On success the caller holds the gate for the whole
     * private attempt and must pair it with exactly one {@link #endCapture}.
     */
    public CaptureAttempt tryBeginCapture(Object world, Object chunk) {
        if (enabled && !shutDown) captureSeen++;
        if (!enabled || shutDown) return rejected(FallbackReason.DISABLED);
        if (disqualified.get()) return rejected(FallbackReason.RUNTIME_DISQUALIFIED);
        if (world == null || chunk == null) return rejected(FallbackReason.INVALID_INPUT);
        if (Thread.currentThread() != canonicalOwner) return rejected(FallbackReason.OFF_THREAD);
        if (writerDepth > 0) return rejected(FallbackReason.WRITER_ACTIVE);
        if (publicationDepth > 0) return rejected(FallbackReason.PUBLICATION_ACTIVE);
        if (activeAttempt != null) return rejected(FallbackReason.NESTED_CAPTURE);
        if (!gate.tryLock()) return rejected(FallbackReason.CAPTURE_BUSY);
        boolean admitted = false;
        try {
            if (disqualified.get()) return rejectedUnderLock(FallbackReason.RUNTIME_DISQUALIFIED); // recheck under lock
            LiveChunkBindings.Binding binding = bindings == null ? null : bindings.resolveForCapture(world, chunk);
            if (binding == null) {
                return rejectedUnderLock(bindings == null
                        ? FallbackReason.NO_BINDING
                        : bindings.captureFallbackReason());
            }
            if (nextEventId == Long.MAX_VALUE) {
                disqualify(DisqualificationReason.COUNTER_EXHAUSTED);
                return rejectedUnderLock(FallbackReason.ID_EXHAUSTED);
            }
            long eventId = ++nextEventId; // exact packet/capture event identity, minted under the gate
            CaptureAttempt attempt = new CaptureAttempt(eventId, epoch, world, chunk, binding, true, null);
            activeAttempt = attempt;
            captureAdmitted++;
            admitted = true;
            return attempt;
        } finally {
            if (!admitted) gate.unlock(); // rejected attempts release immediately; admitted attempts hold
        }
    }

    /**
     * Closes one admitted capture attempt. With a Throwable: discards the
     * candidate and counts JAVA_CONSTRUCTOR_EXCEPTION; the caller rethrows the
     * original throwable unchanged. Without a Throwable: runs the final commit
     * checks under the still-held gate (attempt identity, no invalidation,
     * unchanged epoch, zero writer/publication depth, binding still READY and
     * unrevoked for the exact object, session still qualified) and seals the
     * one terminal outcome. Either way the attempt is cleared and the gate is
     * released exactly once in finally. No live read, helper callback, or
     * transport exists in the foundation stage after the commit check.
     *
     * @return true only when the attempt sealed as SEALED_COMMITTED
     */
    public boolean endCapture(CaptureAttempt attempt, Throwable throwable) {
        if (attempt == null || !attempt.admitted) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("endCapture requires the admitted attempt");
        }
        if (!gate.isHeldByCurrentThread()) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("endCapture without the held gate");
        }
        try {
            if (throwable != null) {
                attempt.terminal(CaptureOutcome.JAVA_CONSTRUCTOR_EXCEPTION);
                captureDiscarded++;
                return false; // never turn failed Java construction into success
            }
            if (activeAttempt != attempt) {
                attempt.terminal(CaptureOutcome.BINDING_STALE);
            } else if (attempt.invalidated) {
                attempt.terminal(CaptureOutcome.REENTRANT_WRITE); // one terminal outcome, already recorded
            } else if (epoch != attempt.epoch0) {
                attempt.terminal(CaptureOutcome.EPOCH_CHANGED);
            } else if (writerDepth != 0 || publicationDepth != 0) {
                attempt.terminal(CaptureOutcome.DEPTH_CHANGED);
            } else if (bindings == null || !bindings.stillValidForCapture(attempt.chunk, attempt.binding)) {
                attempt.terminal(CaptureOutcome.BINDING_STALE);
            } else if (shutDown || disqualified.get()) {
                attempt.terminal(CaptureOutcome.RUNTIME_DISQUALIFIED);
            } else {
                attempt.terminal(CaptureOutcome.SEALED_COMMITTED);
                captureSealed++;
                return true;
            }
            captureDiscarded++;
            return false;
        } finally {
            if (activeAttempt == attempt) activeAttempt = null;
            gate.unlock(); // exactly once
        }
    }

    private CaptureAttempt rejected(FallbackReason reason) {
        return new CaptureAttempt(0L, 0L, null, null, null, false, reason);
    }

    private CaptureAttempt rejectedUnderLock(FallbackReason reason) {
        // The caller's finally releases the lock because admitted stays false.
        return new CaptureAttempt(0L, 0L, null, null, null, false, reason);
    }

    // ------------------------------------------------------------------
    // Publication-depth coordination (owner only, called by LiveChunkBindings)
    // ------------------------------------------------------------------

    void enterPublication() {
        publicationDepth++;
    }

    void exitPublication() {
        publicationDepth--;
    }

    long mintPublicationTxnId() {
        if (nextPublicationTxnId == Long.MAX_VALUE) disqualify(DisqualificationReason.COUNTER_EXHAUSTED);
        return ++nextPublicationTxnId;
    }

    /** True when the current thread is the canonical owner holding the gate. */
    boolean isOwnerUnderGate() {
        return Thread.currentThread() == canonicalOwner && gate.isHeldByCurrentThread();
    }

    /**
     * Enforces the owner-under-gate discipline for owner-side registry
     * operations. Reaching one of these outside a writer scope (or during an
     * un-invalidated capture) is a hook-protocol break: terminal
     * disqualification plus a loud failure.
     */
    void assertOwnerUnderGate(String operation) {
        if (Thread.currentThread() != canonicalOwner || !gate.isHeldByCurrentThread()) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("owner-under-gate violation in " + operation);
        }
        if (activeAttempt != null && !activeAttempt.invalidated) {
            disqualify(DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            throw new IllegalStateException("registry operation inside an un-invalidated capture: " + operation);
        }
    }

    private static String safeClass(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
