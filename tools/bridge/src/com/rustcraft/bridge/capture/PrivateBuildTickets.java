package com.rustcraft.bridge.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Private build/publication ticket system for the Issue #1 live-SHADOW writer
 * protocol. A ticket is the exactly identified private construction context of
 * one chunk incarnation built off the canonical owner (the qualified Forge
 * chunk-I/O worker path): it binds the server session, the provider object,
 * one unique loadId, the exact World object and coordinates, the creator
 * thread, the exact constructed chunk identity (through the component
 * registry), the backing-storage ownership closure, the source provenance, and
 * the single terminal outcome. The ticket is single-use and consumed once,
 * under the owner gate.
 *
 * <p>States: {@code BUILDING -> SEALED_SUCCESS | SEALED_FAILURE -> CONSUMED}.
 * Only BUILDING, the exact creator identity, and the matching active TLS scope
 * permit private writes. Sealing is one-way and permanently revokes that
 * permission BEFORE the volatile completion record is published. A success
 * ticket conveys visibility and provenance only — never permission to keep
 * writing. Failure cannot be converted to success: a BUILDING ticket's
 * monotonic {@code writeFailed} flag forces {@code sealSuccess} to publish
 * {@code SEALED_FAILURE}.</p>
 *
 * <p>The component registry is a concurrent identity-keyed map with
 * reference-equality keys (never hash-only). Existing entries are never
 * overwritten as PRIVATE. Alias/backing ownership takes precedence over a
 * wrapper's own tag: {@code NibbleArray(byte[])}-style wrappers retain the
 * argument's prior provenance, and shared/pending provenance is sticky for the
 * whole session so new wrappers cannot launder old aliases.</p>
 *
 * <p>Memory ordering: the worker publishes the private graph through the
 * volatile {@code Ticket.completion} write (release) after sealing revokes
 * private-write permission; the owner's volatile read in
 * {@code acquireCompletion} (acquire) happens-before every owner-side read of
 * the ticket's graph. Ticket identity fields, the completion record, and the
 * component records' final fields are safely published through that edge.
 * Owner-side record transitions occur under the session gate (see
 * {@link LiveWriterGate} for the lock contract). No edge here orders a
 * nonparticipating raw-array writer.</p>
 *
 * <p>Foundation stage: inert unless a diagnostic session explicitly enables
 * the gate; no production path references this class and production native
 * packet authority remains fail-closed.</p>
 */
public final class PrivateBuildTickets {

    /** Only the current Minecraft 1.12.2 DataVersion may mark fresh-disk provenance qualified. */
    public static final int CURRENT_DATA_VERSION = 1343;

    /** Strong component-identity retention bound for the bounded diagnostic session. */
    static final int MAX_COMPONENT_IDENTITIES = 1_000_000;

    public enum TicketState { BUILDING, SEALED_SUCCESS, SEALED_FAILURE, CONSUMED }

    /** Sticky source provenance; once tainted, never cleared for the session. */
    public enum SourceStatus { NONE, FRESH_DISK_CURRENT, SHARED_PENDING_NBT, OLD_DATA_VERSION }

    /** Why a ticket's graph lost eligibility permanently (sticky). */
    public enum TaintReason {
        CONTRADICTORY_SOURCE, EXISTING_IDENTITY_RELABELED, UNKNOWN_BACKING_PROVENANCE,
        ALIASED_BACKING, SHARED_ATTACH, UNKNOWN_RECEIVER
    }

    public enum TicketFailure {
        DISABLED, SESSION_DISQUALIFIED, THREAD_BUSY, DUPLICATE_LOAD_ID, RESOURCE_LIMIT
    }

    /** Immutable terminal completion record; final fields, published by a volatile write. */
    public static final class Completion {
        public enum Outcome { SUCCESS, FAILURE }

        private final Outcome outcome;
        private final long ticketId;
        private final long loadId;

        Completion(Outcome outcome, long ticketId, long loadId) {
            this.outcome = outcome;
            this.ticketId = ticketId;
            this.loadId = loadId;
        }

        public Outcome outcome() { return outcome; }

        public long ticketId() { return ticketId; }

        public long loadId() { return loadId; }
    }

    /** Outcome of the owner's single-use acquire of a ticket completion. */
    public enum AcquireOutcome {
        SUCCESS, FAILURE, NOT_YET_SEALED, REPLAYED, SESSION_MISMATCH, INVALID_TICKET
    }

    /**
     * One private build ticket. Identity fields are final; the ticket is bound
     * to one creator thread and one session. Never a transferable permission:
     * only the creator's BUILDING TLS scope admits private writes.
     */
    public static final class Ticket {
        private final long ticketId;      // private-build identity minted by the session
        private final long loadId;        // caller-supplied unique task identity
        private final Object provider;    // exact provider object
        private final Object world;       // exact World object
        private final int x;
        private final int z;
        private final Thread creator;
        private final PrivateBuildTickets issuer;
        private final List<ComponentRecord> components = new ArrayList<ComponentRecord>(); // closure for adoption

        private volatile TicketState state = TicketState.BUILDING;
        private volatile boolean writeFailed;      // monotonic; set by any private writer Throwable
        private volatile SourceStatus source = SourceStatus.NONE;
        private volatile TaintReason taint;        // sticky; null until tainted
        private volatile Completion completion;    // published only after sealing revokes permission
        private int openPrivateScopes;             // creator thread only (balanced by gate tokens)
        private boolean consumed;                  // owner only, under the gate
        private boolean admitted;                  // owner only, under the gate: single-use admission

        Ticket(long ticketId, long loadId, Object provider, Object world, int x, int z,
               Thread creator, PrivateBuildTickets issuer) {
            this.ticketId = ticketId;
            this.loadId = loadId;
            this.provider = provider;
            this.world = world;
            this.x = x;
            this.z = z;
            this.creator = creator;
            this.issuer = issuer;
        }

        public long ticketId() { return ticketId; }

        public long loadId() { return loadId; }

        public Object provider() { return provider; }

        public Object world() { return world; }

        public int chunkX() { return x; }

        public int chunkZ() { return z; }

        public Thread creator() { return creator; }

        PrivateBuildTickets issuer() { return issuer; }

        public TicketState state() { return state; }

        public boolean isWriteFailed() { return writeFailed; }

        public SourceStatus sourceStatus() { return source; }

        public TaintReason taintReason() { return taint; }

        /** Sticky source qualification: only an untainted FRESH_DISK_CURRENT build is admitted later. */
        public boolean isSourceQualified() { return source == SourceStatus.FRESH_DISK_CURRENT && taint == null; }

        void openPrivateScope() { openPrivateScopes++; }

        void closePrivateScope() {
            if (openPrivateScopes > 0) openPrivateScopes--;
        }

        void markWriteFailed() { writeFailed = true; } // irreversible

        boolean isConsumed() { return consumed; }

        boolean isAdmitted() { return admitted; }

        void markAdmitted() { admitted = true; } // owner only, under the gate

        /** Package-private completion view for owner-side admission validation. */
        Completion completionRecord() { return completion; }

        int openPrivateScopes() { return openPrivateScopes; }
    }

    /**
     * One component/backing ownership record. Logical states are distinct from
     * Binding states. SEALED_IO is derived from the immutable ticket
     * completion state rather than rewriting each row; it never permits
     * private writes.
     */
    static final class ComponentRecord {
        enum State { PRIVATE_BUILDING, OWNER_PENDING, OWNER_BOUND, RETIRED_OWNER, SHARED_OR_UNKNOWN }

        final Object component;
        final Object backing;      // null when the component is its own storage
        final Ticket ticket;       // PRIVATE_BUILDING rows only
        private volatile State state;
        private volatile long ownerTxnId;          // OWNER_PENDING: publication txn id, 0 = enclosing writer scope
        private volatile LiveChunkBindings.Binding binding; // OWNER_BOUND / RETIRED_OWNER

        ComponentRecord(Object component, Object backing, Ticket ticket, State state) {
            this.component = component;
            this.backing = backing;
            this.ticket = ticket;
            this.state = state;
        }

        State state() { return state; }

        Ticket ticket() { return ticket; }

        Object backing() { return backing; }

        long ownerTxnId() { return ownerTxnId; }

        LiveChunkBindings.Binding ownerBinding() { return binding; }

        /** SEALED_IO derivation: a PRIVATE_BUILDING row of a sealed (successfully built) ticket. */
        boolean isEffectivelySealedIo() {
            return state == State.PRIVATE_BUILDING && ticket != null
                    && ticket.state != TicketState.BUILDING;
        }

        void markOwnerPending(long txnId) {
            this.state = State.OWNER_PENDING;
            this.ownerTxnId = txnId;
        }

        void bindToOwner(LiveChunkBindings.Binding binding) {
            this.state = State.OWNER_BOUND;
            this.ownerTxnId = 0L;
            this.binding = binding;
        }

        void markRetiredOwner() {
            this.state = State.RETIRED_OWNER;
        }

        void tombstoneAsSharedOrUnknown() {
            // Never from PRIVATE_BUILDING/OWNER_BOUND/RETIRED_OWNER: those carry
            // provenance that must not be silently rewritten into "shared".
            if (state == State.OWNER_PENDING) state = State.SHARED_OR_UNKNOWN;
        }
    }

    private final LiveWriterGate gate;

    /**
     * Concurrent identity-keyed registry: reference-equality keys, monitor for
     * cross-thread safety; worker registrations are published to the owner
     * through the ticket completion volatile edge and re-protected here for
     * later owner-side mutation.
     */
    private final Map<Object, ComponentRecord> components =
            Collections.synchronizedMap(new IdentityHashMap<Object, ComponentRecord>());

    private final ThreadLocal<Ticket> activeTicket = new ThreadLocal<Ticket>();
    private final Set<Long> issuedLoadIds = new HashSet<Long>(); // guarded by itself

    private volatile TicketFailure lastAdmissionFailure; // best-effort diagnostic (single worker per task)

    public PrivateBuildTickets(LiveWriterGate gate) {
        if (gate == null) throw new IllegalArgumentException("gate required");
        this.gate = gate;
    }

    public TicketFailure lastAdmissionFailure() { return lastAdmissionFailure; }

    // ------------------------------------------------------------------
    // Ticket lifecycle (worker side)
    // ------------------------------------------------------------------

    /**
     * Creates the private build context before any loading work, binding the
     * exact creator thread through TLS. One active ticket per thread; loadIds
     * are unique per session. Fails closed (returns null) without throwing:
     * the ordinary Java loader continues, the incarnation just never becomes
     * eligible.
     */
    public Ticket beginIo(Object provider, long loadId, Object world, int x, int z) {
        if (!gate.isEnabled() || gate.isShutDown()) {
            lastAdmissionFailure = TicketFailure.DISABLED;
            return null;
        }
        if (gate.isTerminalDisqualified()) {
            lastAdmissionFailure = TicketFailure.SESSION_DISQUALIFIED;
            return null;
        }
        if (world == null || provider == null) {
            lastAdmissionFailure = TicketFailure.DISABLED;
            return null;
        }
        if (activeTicket.get() != null) {
            lastAdmissionFailure = TicketFailure.THREAD_BUSY;
            return null;
        }
        synchronized (issuedLoadIds) {
            if (!issuedLoadIds.add(loadId)) {
                lastAdmissionFailure = TicketFailure.DUPLICATE_LOAD_ID;
                return null;
            }
        }
        Ticket ticket = new Ticket(newTicketId(), loadId, provider, world, x, z,
                Thread.currentThread(), this);
        activeTicket.set(ticket);
        return ticket;
    }

    /**
     * Marks fresh-disk provenance from the qualified loader branch. Only the
     * current DataVersion with the exact root identity preserved by the pinned
     * fixer qualifies; anything else makes the incarnation permanently
     * ineligible while ordinary Java loading continues.
     */
    public boolean recordDiskRoot(Ticket ticket, Object nbtRoot, int dataVersion) {
        if (!isBuildingByCurrentThread(ticket)) return false;
        if (ticket.source != SourceStatus.NONE) {
            taint(ticket, TaintReason.CONTRADICTORY_SOURCE);
            return false;
        }
        if (dataVersion != CURRENT_DATA_VERSION) {
            ticket.source = SourceStatus.OLD_DATA_VERSION; // sticky
            return false;
        }
        ticket.source = SourceStatus.FRESH_DISK_CURRENT;
        return true;
    }

    /**
     * Marks the pending-save NBT alias hazard: shared/pending roots are
     * sticky-INELIGIBLE for this incarnation even if values later match.
     */
    public boolean recordSharedRoot(Ticket ticket, Object nbtRoot) {
        if (!isBuildingByCurrentThread(ticket)) return false;
        if (ticket.source != SourceStatus.NONE) {
            taint(ticket, TaintReason.CONTRADICTORY_SOURCE);
            return false;
        }
        ticket.source = SourceStatus.SHARED_PENDING_NBT; // sticky
        return true;
    }

    /**
     * Registers one verified new component (or primitive array) under the
     * BUILDING ticket with its backing storage. The backing's prior provenance
     * takes precedence over the wrapper's own tag: an unknown or foreign
     * backing taints the ticket's whole graph permanently. Existing registry
     * identities are never overwritten as PRIVATE.
     */
    public boolean registerNew(Ticket ticket, Object component, Object backing) {
        if (!isBuildingByCurrentThread(ticket)) return false;
        if (component == null) return false;
        synchronized (components) {
            if (components.size() >= MAX_COMPONENT_IDENTITIES) {
                gate.disqualify(LiveWriterGate.DisqualificationReason.RESOURCE_LIMIT);
                return false;
            }
            ComponentRecord existing = components.get(component);
            if (existing != null) {
                // Chunk(World, ChunkPrimer, int, int) delegates to Chunk(World, int, int)
                // and BOTH constructors carry the registration hook: the delegation
                // double-fires for the same object under the SAME ticket. That is a
                // compile artifact, not a writer event — idempotent success (nothing
                // is overwritten; the entry stays PRIVATE_BUILDING under this ticket).
                // A DIFFERENT ticket or a different component's identity still taints.
                if (existing.ticket == ticket
                        && existing.state() == ComponentRecord.State.PRIVATE_BUILDING) {
                    return true;
                }
                taint(ticket, TaintReason.EXISTING_IDENTITY_RELABELED);
                return false;
            }
            ComponentRecord record = new ComponentRecord(component, backing, ticket,
                    ComponentRecord.State.PRIVATE_BUILDING);
            components.put(component, record);
            ticket.components.add(record);
        }
        if (backing != null) {
            ComponentRecord backingRecord = components.get(backing);
            if (backingRecord == null) {
                taint(ticket, TaintReason.UNKNOWN_BACKING_PROVENANCE);
            } else if (backingRecord.ticket != ticket) {
                // Passed arrays retain prior provenance; the alias is sticky.
                taint(ticket, TaintReason.ALIASED_BACKING);
            }
        }
        return true;
    }

    /**
     * One-way sealing of a successful build. Requires the exact creator TLS
     * scope, BUILDING state, and all nested private write scopes closed. A
     * BUILDING ticket whose {@code writeFailed} flag was set by any private
     * writer Throwable publishes SEALED_FAILURE — a later normal return cannot
     * manufacture a successful publication. The state transition that revokes
     * private-write permission strictly precedes the volatile completion
     * publication.
     */
    public boolean sealSuccess(Ticket ticket) {
        return seal(ticket, true);
    }

    /** One-way sealing of a failed build; failure is never converted to success. */
    public boolean sealFailure(Ticket ticket) {
        return seal(ticket, false);
    }

    /**
     * Owner-side single-use consume of the ticket completion, under the gate.
     * The volatile read of {@code completion} is the acquire edge that makes
     * the worker's whole private graph visible. Never waits: a ticket that has
     * not sealed returns NOT_YET_SEALED and the ordinary Java callback
     * continues without admission.
     */
    public AcquireOutcome acquireCompletion(Ticket ticket) {
        if (ticket == null) return AcquireOutcome.INVALID_TICKET;
        if (ticket.issuer != this) return AcquireOutcome.SESSION_MISMATCH;
        gate.assertOwnerUnderGate("acquireCompletion");
        if (ticket.consumed) return AcquireOutcome.REPLAYED;
        Completion completion = ticket.completion; // volatile read (acquire)
        if (completion == null) return AcquireOutcome.NOT_YET_SEALED;
        ticket.consumed = true;
        ticket.state = TicketState.CONSUMED;
        return completion.outcome() == Completion.Outcome.SUCCESS
                ? AcquireOutcome.SUCCESS
                : AcquireOutcome.FAILURE;
    }

    private boolean seal(Ticket ticket, boolean requestedSuccess) {
        if (ticket == null || ticket.issuer != this) return false;
        if (activeTicket.get() != ticket) return false;          // exact creator/TLS scope
        if (Thread.currentThread() != ticket.creator) return false;
        if (ticket.state != TicketState.BUILDING) return false;  // one-way
        if (ticket.openPrivateScopes != 0) return false;         // all nested private scopes closed
        boolean success = requestedSuccess && !ticket.writeFailed;
        ticket.state = success ? TicketState.SEALED_SUCCESS : TicketState.SEALED_FAILURE;
        activeTicket.remove();
        // Release: the revoking state transition and every private write happen
        // before this volatile write; the owner acquires it before graph reads.
        ticket.completion = new Completion(
                success ? Completion.Outcome.SUCCESS : Completion.Outcome.FAILURE,
                ticket.ticketId, ticket.loadId);
        return true;
    }

    private boolean isBuildingByCurrentThread(Ticket ticket) {
        return ticket != null && ticket.issuer == this
                && ticket.state == TicketState.BUILDING
                && activeTicket.get() == ticket
                && Thread.currentThread() == ticket.creator;
    }

    private void taint(Ticket ticket, TaintReason reason) {
        // Sticky: any taint permanently vetoes eligibility, overriding an
        // earlier FRESH_DISK_CURRENT determination. Never cleared in-session.
        if (ticket.taint == null) ticket.taint = reason;
    }

    /** Package-private taint entry point for the attachment rules in LiveChunkBindings. */
    void taintTicket(Ticket ticket, TaintReason reason) {
        taint(ticket, reason);
    }

    /** Package-private private-closure check for the attachment rules in LiveChunkBindings. */
    boolean isPrivateComponent(Ticket ticket, Object component) {
        return isPrivateUnder(ticket, component);
    }

    private long newTicketId() {
        // Monotonic positive nonwrapping private-build identity for this session.
        // Every counter exhaustion disqualifies; no wrap/reset/recovery.
        long seq = TICKET_SEQ.incrementAndGet();
        if (seq == Long.MAX_VALUE) {
            gate.disqualify(LiveWriterGate.DisqualificationReason.COUNTER_EXHAUSTED);
            return -1L;
        }
        return seq;
    }

    private static final java.util.concurrent.atomic.AtomicLong TICKET_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    // ------------------------------------------------------------------
    // Private-write admission (called by LiveWriterGate)
    // ------------------------------------------------------------------

    /**
     * True only when the receiver AND every actual mutated backing target are
     * registered PRIVATE_BUILDING under the caller's active BUILDING ticket on
     * the creator thread, with the backing closure private as well. TLS alone
     * never suffices; a fresh wrapper over LIVE backing fails here, which
     * sends the write to the off-owner violation path before any byte changes.
     */
    boolean canBypassPrivateWrite(Object receiver, Object[] mutationTargets) {
        Ticket ticket = activeTicket.get();
        if (ticket == null || ticket.state != TicketState.BUILDING) return false;
        if (ticket.creator != Thread.currentThread()) return false;
        if (!isPrivateUnder(ticket, receiver)) return false;
        if (mutationTargets != null) {
            for (Object target : mutationTargets) {
                if (!isPrivateUnder(ticket, target)) return false;
            }
        }
        return true;
    }

    private boolean isPrivateUnder(Ticket ticket, Object component) {
        if (component == null) return false;
        ComponentRecord record = components.get(component);
        if (record == null || record.ticket != ticket || record.state != ComponentRecord.State.PRIVATE_BUILDING) {
            return false;
        }
        if (record.backing != null) { // alias/backing ownership takes precedence over the wrapper's tag
            ComponentRecord backingRecord = components.get(record.backing);
            if (backingRecord == null || backingRecord.ticket != ticket
                    || backingRecord.state != ComponentRecord.State.PRIVATE_BUILDING) {
                return false;
            }
        }
        return true;
    }

    /** Active BUILDING ticket on the current thread, or null. */
    Ticket activeTicketForCurrentThread() {
        return activeTicket.get();
    }

    boolean hasActiveTicketOnCurrentThread() {
        return activeTicket.get() != null;
    }

    // ------------------------------------------------------------------
    // Component registry collaboration (owner side, under the gate)
    // ------------------------------------------------------------------

    ComponentRecord componentRecordFor(Object component) {
        return component == null ? null : components.get(component);
    }

    /**
     * Owner-side registration of a fresh allocation as OWNER_PENDING for the
     * current outer transaction ({@code txnId}, or 0 for the enclosing writer
     * scope). Existing identities are never relabeled; unregistered fresh
     * objects only.
     */
    boolean registerOwnerPending(Object component, Object backing, long txnId) {
        if (component == null) return false;
        synchronized (components) {
            if (components.size() >= MAX_COMPONENT_IDENTITIES) {
                gate.disqualify(LiveWriterGate.DisqualificationReason.RESOURCE_LIMIT);
                return false;
            }
            if (components.containsKey(component)) return false;
            ComponentRecord record = new ComponentRecord(component, backing, null,
                    ComponentRecord.State.OWNER_PENDING);
            record.markOwnerPending(txnId);
            components.put(component, record);
            return true;
        }
    }

    /**
     * Tombstones an unknown/shared object so it can never be laundered into a
     * private or pending allocation later. Never rewrites provenance rows that
     * already carry ownership.
     */
    boolean tombstoneAsSharedOrUnknown(Object component) {
        if (component == null) return false;
        synchronized (components) {
            ComponentRecord existing = components.get(component);
            if (existing != null) {
                existing.tombstoneAsSharedOrUnknown();
                return true;
            }
            if (components.size() >= MAX_COMPONENT_IDENTITIES) {
                gate.disqualify(LiveWriterGate.DisqualificationReason.RESOURCE_LIMIT);
                return false;
            }
            components.put(component, new ComponentRecord(component, null, null,
                    ComponentRecord.State.SHARED_OR_UNKNOWN));
            return true;
        }
    }

    /** All component records registered under one ticket, in registration order. */
    List<ComponentRecord> componentsOfTicket(Ticket ticket) {
        return ticket == null ? Collections.<ComponentRecord>emptyList() : ticket.components;
    }

    long componentIdentityCount() {
        synchronized (components) {
            return components.size();
        }
    }

    /** Package-private registry view for owner-side closure validation. */
    Map<Object, ComponentRecord> registryView() {
        return components;
    }
}
