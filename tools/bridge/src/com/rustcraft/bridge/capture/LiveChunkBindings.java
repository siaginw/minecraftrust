package com.rustcraft.bridge.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-side exact lifecycle/binding registry for the Issue #1 live-SHADOW
 * writer protocol: one binding per admitted chunk incarnation per session.
 *
 * <p>Identity is deliberately NOT coordinate-based: it is the conjunction of
 * the server session, the exact World object, the exact Chunk object, the
 * monotonic incarnation, and the minted owned-encode generation. Coordinates
 * and identity hashes are descriptive only and never authorize capture or
 * publication. The native identity for the stateless owned snapshot ABI is
 * {@code {session, worldId, chunkObjectId, incarnation, ownedEncodeGeneration,
 * eventId, retainedHandle=ABSENT}} — the eventId is bound per capture attempt
 * by the gate, and no {@code NativeChunk} retained handle is ever fabricated
 * here (the owned ABI is stateless and unregistered).</p>
 *
 * <p>State machine: {@code PRIVATE -> PUBLISHING -> READY}, or failure to
 * {@code INELIGIBLE}; both READY and INELIGIBLE can become {@code RETIRED}.
 * Failure never reaches READY; no binding is ever resurrected. Unload,
 * replacement, and dormancy retire the old binding and revoke its opaque
 * token before any future work can be authorized; same-coordinate
 * replacement and same-object reload always receive a fresh incarnation and
 * generation and never inherit old eligibility.</p>
 *
 * <p>Threading and memory ordering: the chunk-object identity map, the
 * per-World coordinate membership maps, and all binding state transitions are
 * owner-only under the session gate (see {@link LiveWriterGate} for the lock
 * contract and happens-before edges). The opaque
 * {@link RevocationToken} is volatile so gate-free queue consumers can check
 * revocation. Sticky source status is never cleared in-session. Retention is
 * strong for the bounded diagnostic session (maximum 4096 bindings; the
 * component registry's own bound lives in {@link PrivateBuildTickets});
 * exhaustion disqualifies and stops. Registries clear only at fully quiesced
 * session shutdown.</p>
 *
 * <p>Foundation stage: inert unless a diagnostic session explicitly enables
 * the gate; no production path references this class and production native
 * packet authority remains fail-closed.</p>
 */
public final class LiveChunkBindings {

    /** Strong chunk-binding retention bound for the bounded diagnostic session. */
    static final int MAX_BINDINGS = 4096;

    private static final int NO_COORD = Integer.MIN_VALUE;

    public enum BindingState { PRIVATE, PUBLISHING, READY, INELIGIBLE, RETIRED }

    /** Sticky source qualification recorded at admission; taint never clears. */
    public enum SourceQualification {
        OWNER_GENERATED, FRESH_DISK_CURRENT, SHARED_PENDING_NBT, OLD_DATA_VERSION, TAINTED
    }

    enum CaptureRejection {
        NO_BINDING, PROVIDER_IDENTITY_CHANGED, UNLOADED, NOT_READY, SOURCE_UNQUALIFIED
    }

    public enum FailureReason {
        INVALID_INPUT, INVALID_TICKET, RESOURCE_LIMIT, WORLD_MISMATCH,
        DUPLICATE_PUBLICATION, INELIGIBLE, RETIRED, NO_BINDING, NOT_PUBLISHING,
        COORDINATE_OCCUPIED, REMOVAL_IDENTITY_MISMATCH, PUBLICATION_FAILED,
        SESSION_MISMATCH, TICKET_NOT_SEALED, TICKET_FAILED, TICKET_NOT_ACQUIRED, TICKET_REPLAYED,
        INCARNATION_MISMATCH, CHUNK_IDENTITY_MISMATCH, GRAPH_CLOSURE_INVALID,
        SOURCE_UNQUALIFIED, UNKNOWN_VALUE, FOREIGN_ALIAS, ORPHAN_PENDING,
        SEALED_NOT_ADOPTED, RETIRED_COMPONENT_REUSE, SHARED_COMPONENT,
        NO_ACTIVE_TRANSACTION, REGISTRATION_REJECTED, HOOK_PROTOCOL_BROKEN
    }

    /** Opaque per-binding revocation token carried by queue jobs; volatile for gate-free consumers. */
    public static final class RevocationToken {
        private volatile boolean revoked;

        void revoke() { revoked = true; }

        public boolean isRevoked() { return revoked; }
    }

    /**
     * Immutable job-safe identity: token-plus-these-ids is everything a
     * comparison job carries. No live Chunk/World reference, no coordinate, no
     * identityHashCode. {@code retainedHandle} is ABSENT by definition for the
     * stateless owned snapshot ABI and is deliberately not a field here.
     */
    public static final class BindingIdentity {
        public final long sessionId;
        public final long worldId;
        public final long chunkId;
        public final long incarnation;
        public final long ownedEncodeGeneration;

        BindingIdentity(long sessionId, long worldId, long chunkId, long incarnation, long ownedEncodeGeneration) {
            this.sessionId = sessionId;
            this.worldId = worldId;
            this.chunkId = chunkId;
            this.incarnation = incarnation;
            this.ownedEncodeGeneration = ownedEncodeGeneration;
        }
    }

    /** One exact chunk-incarnation binding. */
    public static final class Binding {
        private final long sessionId;
        private final long worldId;
        private final long chunkId;
        private final long incarnation;
        private final long ownedEncodeGeneration;
        private final Object world;  // internal exact identity anchor; never exposed as a job reference
        private final Object chunk;  // internal exact identity anchor; never exposed as a job reference
        private final RevocationToken revocation = new RevocationToken();
        private final List<PrivateBuildTickets.ComponentRecord> ownedComponents =
                new ArrayList<PrivateBuildTickets.ComponentRecord>();

        private BindingState state;
        private final SourceQualification source;   // sticky
        private boolean publicationCompleted;        // owner-only
        private int recordedX = NO_COORD;            // descriptive only, recorded at the exact map put
        private int recordedZ = NO_COORD;

        Binding(long sessionId, long worldId, long chunkId, long incarnation, long ownedEncodeGeneration,
                Object world, Object chunk, BindingState state, SourceQualification source) {
            this.sessionId = sessionId;
            this.worldId = worldId;
            this.chunkId = chunkId;
            this.incarnation = incarnation;
            this.ownedEncodeGeneration = ownedEncodeGeneration;
            this.world = world;
            this.chunk = chunk;
            this.state = state;
            this.source = source;
        }

        public long sessionId() { return sessionId; }

        public long worldId() { return worldId; }

        public long chunkId() { return chunkId; }

        public long incarnation() { return incarnation; }

        /** Minted once per admitted incarnation; carried in the RCSNAP01 generation field later. */
        public long ownedEncodeGeneration() { return ownedEncodeGeneration; }

        public BindingState state() { return state; }

        public SourceQualification sourceQualification() { return source; }

        public boolean isRevoked() { return revocation.isRevoked(); }

        public RevocationToken revocationToken() { return revocation; }

        public BindingIdentity identityRecord() {
            return new BindingIdentity(sessionId, worldId, chunkId, incarnation, ownedEncodeGeneration);
        }

        boolean isSourceQualified() {
            return source == SourceQualification.OWNER_GENERATED
                    || source == SourceQualification.FRESH_DISK_CURRENT;
        }
    }

    /** One owner publication transaction handle (outer or nested). */
    public static final class Publication {
        private final long txnId;
        private final Binding binding;

        Publication(long txnId, Binding binding) {
            this.txnId = txnId;
            this.binding = binding;
        }

        public long txnId() { return txnId; }

        public Binding binding() { return binding; }
    }

    /** Uniform outcome for registry operations; failure carries exactly one reason. */
    public static final class Outcome {
        private final boolean ok;
        private final FailureReason reason;
        private final Binding binding;
        private final Publication publication;

        private Outcome(boolean ok, FailureReason reason, Binding binding, Publication publication) {
            this.ok = ok;
            this.reason = reason;
            this.binding = binding;
            this.publication = publication;
        }

        static Outcome ok(Binding binding, Publication publication) {
            return new Outcome(true, null, binding, publication);
        }

        static Outcome fail(FailureReason reason) {
            return new Outcome(false, reason, null, null);
        }

        public boolean ok() { return ok; }

        public FailureReason reason() { return reason; }

        public Binding binding() { return binding; }

        public Publication publication() { return publication; }
    }

    private final LiveWriterGate gate;
    private final PrivateBuildTickets tickets;

    // Owner-only, under the gate:
    private final Map<Object, Binding> byChunkObject = new IdentityHashMap<Object, Binding>();
    private final Map<Object, Map<Long, Binding>> coordsByWorld = new IdentityHashMap<Object, Map<Long, Binding>>();
    private final Map<Object, Long> worldIds = new IdentityHashMap<Object, Long>();
    private final ArrayDeque<Long> txnStack = new ArrayDeque<Long>();
    private final Map<Long, List<PrivateBuildTickets.ComponentRecord>> pendingByTxn =
            new HashMap<Long, List<PrivateBuildTickets.ComponentRecord>>();
    private final List<PrivateBuildTickets.ComponentRecord> writerScopePendings =
            new ArrayList<PrivateBuildTickets.ComponentRecord>();
    private final List<Binding> pendingFinalize = new ArrayList<Binding>();

    // Owner-minted positive nonwrapping counters (overflow disqualifies; no wrap/reset/recovery).
    private long nextWorldId;
    private long nextChunkId;
    private long nextIncarnation;
    private long nextOwnedEncodeGeneration;

    private volatile CaptureRejection lastCaptureRejection;

    public LiveChunkBindings(LiveWriterGate gate, PrivateBuildTickets tickets) {
        if (gate == null || tickets == null) throw new IllegalArgumentException("gate and tickets required");
        this.gate = gate;
        this.tickets = tickets;
    }

    // ------------------------------------------------------------------
    // Publication transactions (owner only, under the gate)
    // ------------------------------------------------------------------

    /**
     * Begins one whole owner publication transaction. A chunk object with no
     * binding gets a provisional PUBLISHING binding (owner-generation path);
     * an adopted PRIVATE binding moves to PUBLISHING; READY/INELIGIBLE/RETIRED
     * bindings are never re-published. Nested calls form one outer
     * transaction: no intermediate READY publication.
     */
    public Outcome beginPublication(Object world, Object chunk) {
        gate.assertOwnerUnderGate("beginPublication");
        if (world == null || chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Binding binding = byChunkObject.get(chunk);
        if (binding == null) {
            binding = createBinding(world, chunk, BindingState.PUBLISHING,
                    SourceQualification.OWNER_GENERATED);
            if (binding == null) return Outcome.fail(FailureReason.RESOURCE_LIMIT);
        } else {
            if (binding.world != world) return Outcome.fail(FailureReason.WORLD_MISMATCH);
            switch (binding.state) {
                case PRIVATE:
                    binding.state = BindingState.PUBLISHING;
                    break;
                case PUBLISHING:
                    break; // reentrant publication of the same chunk inside the outer transaction
                case READY:
                    return Outcome.fail(FailureReason.DUPLICATE_PUBLICATION);
                case INELIGIBLE:
                    return Outcome.fail(FailureReason.INELIGIBLE);
                case RETIRED:
                    return Outcome.fail(FailureReason.RETIRED);
                default:
                    return Outcome.fail(FailureReason.INELIGIBLE);
            }
        }
        long txnId = gate.mintPublicationTxnId();
        gate.enterPublication();
        txnStack.push(txnId);
        pendingByTxn.put(txnId, new ArrayList<PrivateBuildTickets.ComponentRecord>());
        return Outcome.ok(binding, new Publication(txnId, binding));
    }

    /**
     * Records the exact provider map insertion (owner only). Requires a
     * PUBLISHING binding for the exact chunk object and World; coordinates
     * still held by a live (non-retired) different binding reject the put —
     * same-coordinate replacement must retire the old binding first and never
     * inherits old eligibility.
     */
    public Outcome recordMapPut(Object world, int x, int z, Object chunk) {
        gate.assertOwnerUnderGate("recordMapPut");
        if (world == null || chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Binding binding = byChunkObject.get(chunk);
        if (binding == null) return Outcome.fail(FailureReason.NO_BINDING);
        if (binding.world != world) return Outcome.fail(FailureReason.WORLD_MISMATCH);
        if (binding.state != BindingState.PUBLISHING) return Outcome.fail(FailureReason.NOT_PUBLISHING);
        Map<Long, Binding> coords = coordsByWorld.get(world);
        if (coords == null) {
            coords = new HashMap<Long, Binding>();
            coordsByWorld.put(world, coords);
        }
        Long key = coordKey(x, z);
        Binding existing = coords.get(key);
        if (existing != null && existing != binding && existing.state != BindingState.RETIRED) {
            return Outcome.fail(FailureReason.COORDINATE_OCCUPIED);
        }
        coords.put(key, binding);
        if (binding.recordedX == NO_COORD) {
            binding.recordedX = x; // descriptive only; coordinates are never identity
            binding.recordedZ = z;
        }
        return Outcome.ok(binding, null);
    }

    /**
     * Records the exact provider map removal (owner only). The real lifecycle
     * order is retire-before-remove, so a removal for an already-retired
     * (unbound) object is an expected no-op. The coordinate entry is removed
     * only when it still maps to this exact binding — never a later
     * replacement's entry.
     */
    public Outcome recordMapRemove(Object world, int x, int z, Object chunk) {
        gate.assertOwnerUnderGate("recordMapRemove");
        if (world == null || chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Binding binding = byChunkObject.get(chunk);
        if (binding == null) return Outcome.ok(null, null); // retired before removal: nothing left to update
        if (binding.world != world) return Outcome.fail(FailureReason.WORLD_MISMATCH);
        Map<Long, Binding> coords = coordsByWorld.get(world);
        Long key = coordKey(x, z);
        Binding current = coords == null ? null : coords.get(key);
        if (current == binding) {
            coords.remove(key);
            return Outcome.ok(binding, null);
        }
        // A different occupant or an unrecorded coordinate: never touch another binding's entry.
        return Outcome.fail(FailureReason.REMOVAL_IDENTITY_MISMATCH);
    }

    /**
     * Ends one publication transaction. A Throwable fails that publication and
     * poisons every pending publication of the transaction tree (failure goes
     * INELIGIBLE, never READY). Normal completion marks the binding completed;
     * only the outermost normal completion finalizes all validly completed
     * bindings to READY. Unattached OWNER_PENDING records of the closing
     * transaction become non-private tombstones that can never be reused as
     * fresh allocations.
     */
    public Outcome finishPublication(Publication publication, Throwable throwable) {
        gate.assertOwnerUnderGate("finishPublication");
        if (publication == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Long top = txnStack.peek();
        if (top == null || top.longValue() != publication.txnId()) {
            gate.disqualify(LiveWriterGate.DisqualificationReason.HOOK_PROTOCOL_BROKEN);
            return Outcome.fail(FailureReason.HOOK_PROTOCOL_BROKEN);
        }
        txnStack.pop();
        List<PrivateBuildTickets.ComponentRecord> pendings = pendingByTxn.remove(publication.txnId());
        if (pendings != null) {
            for (PrivateBuildTickets.ComponentRecord record : pendings) {
                record.tombstoneAsSharedOrUnknown();
            }
        }
        gate.exitPublication();
        Binding binding = publication.binding();
        if (throwable != null) {
            revoke(binding); // INELIGIBLE + revoked token: failure is terminal for this incarnation
            invalidateAllPending(); // one failed transaction poisons the whole pending tree
            return Outcome.fail(FailureReason.PUBLICATION_FAILED);
        }
        binding.publicationCompleted = true;
        if (!pendingFinalize.contains(binding)) pendingFinalize.add(binding);
        if (txnStack.isEmpty()) finalizePendingPublications();
        return Outcome.ok(binding, null);
    }

    /**
     * Retires a binding before unload callback, removal, or replacement
     * (owner only). Irreversible: revokes the opaque token, marks RETIRED,
     * removes the exact object entry and the exact coordinate entry, and
     * tombstones its owned components as RETIRED_OWNER so new wrappers cannot
     * launder old aliases.
     */
    public Outcome retire(Object world, Object chunk) {
        gate.assertOwnerUnderGate("retire");
        if (world == null || chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Binding binding = byChunkObject.get(chunk);
        if (binding == null) return Outcome.fail(FailureReason.NO_BINDING);
        if (binding.world != world) return Outcome.fail(FailureReason.WORLD_MISMATCH);
        byChunkObject.remove(chunk);
        if (binding.recordedX != NO_COORD) {
            Map<Long, Binding> coords = coordsByWorld.get(world);
            if (coords != null) {
                Long key = coordKey(binding.recordedX, binding.recordedZ);
                if (coords.get(key) == binding) coords.remove(key);
            }
        }
        binding.state = BindingState.RETIRED;
        binding.revocation.revoke(); // volatile: visible to gate-free consumers
        pendingFinalize.remove(binding);
        for (PrivateBuildTickets.ComponentRecord record : binding.ownedComponents) {
            record.markRetiredOwner();
        }
        return Outcome.ok(binding, null);
    }

    // ------------------------------------------------------------------
    // Private I/O ticket adoption (owner only, under the gate)
    // ------------------------------------------------------------------

    /**
     * Admits a consumed SEALED_SUCCESS ticket: validates the session, the
     * sealed success outcome, the single-use acquire, the exact World
     * identity, the absence of any live binding for the exact chunk object
     * (same-object reload requires a retired predecessor and always mints a
     * fresh incarnation), the exact chunk identity registered under the
     * ticket, and the intact SEALED_IO closure of every registered component.
     * A qualified fresh-disk ticket creates the PRIVATE binding and transfers
     * all components to OWNER_BOUND. An unqualified (shared-pending, old data
     * version, or tainted) ticket records a sticky-INELIGIBLE binding and the
     * ordinary Java continuation is unaffected — that incarnation can never
     * become eligible.
     */
    public Outcome admitIoTicket(PrivateBuildTickets.Ticket ticket, Object world, Object chunk) {
        gate.assertOwnerUnderGate("admitIoTicket");
        if (ticket == null || world == null || chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        if (ticket.issuer() != tickets) return Outcome.fail(FailureReason.SESSION_MISMATCH);
        if (ticket.state() == PrivateBuildTickets.TicketState.SEALED_FAILURE) {
            return Outcome.fail(FailureReason.TICKET_FAILED);
        }
        if (!ticket.isConsumed()) {
            // The owner acquire must happen first; an unacquired sealed ticket admits nothing.
            return Outcome.fail(ticket.state() == PrivateBuildTickets.TicketState.SEALED_SUCCESS
                    ? FailureReason.TICKET_NOT_ACQUIRED
                    : FailureReason.TICKET_NOT_SEALED);
        }
        PrivateBuildTickets.Completion completion = ticket.completionRecord();
        if (completion == null || completion.outcome() != PrivateBuildTickets.Completion.Outcome.SUCCESS) {
            return Outcome.fail(FailureReason.TICKET_FAILED);
        }
        if (ticket.isAdmitted()) return Outcome.fail(FailureReason.TICKET_REPLAYED);
        if (ticket.world() != world) return Outcome.fail(FailureReason.WORLD_MISMATCH);
        Binding existing = byChunkObject.get(chunk);
        if (existing != null && existing.state != BindingState.RETIRED) {
            return Outcome.fail(FailureReason.INCARNATION_MISMATCH);
        }
        PrivateBuildTickets.ComponentRecord chunkRecord = tickets.componentRecordFor(chunk);
        if (chunkRecord == null || chunkRecord.ticket() != ticket
                || chunkRecord.state() != PrivateBuildTickets.ComponentRecord.State.PRIVATE_BUILDING) {
            return Outcome.fail(FailureReason.CHUNK_IDENTITY_MISMATCH);
        }
        List<PrivateBuildTickets.ComponentRecord> closure = tickets.componentsOfTicket(ticket);
        for (PrivateBuildTickets.ComponentRecord record : closure) {
            if (record.state() != PrivateBuildTickets.ComponentRecord.State.PRIVATE_BUILDING) {
                return Outcome.fail(FailureReason.GRAPH_CLOSURE_INVALID);
            }
        }
        boolean qualified = ticket.isSourceQualified();
        SourceQualification source = qualified ? SourceQualification.FRESH_DISK_CURRENT
                : sourceFromTicket(ticket);
        Binding binding = createBinding(world, chunk,
                qualified ? BindingState.PRIVATE : BindingState.INELIGIBLE, source);
        if (binding == null) return Outcome.fail(FailureReason.RESOURCE_LIMIT);
        if (!qualified) return Outcome.fail(FailureReason.SOURCE_UNQUALIFIED);
        for (PrivateBuildTickets.ComponentRecord record : closure) {
            record.bindToOwner(binding);
            binding.ownedComponents.add(record);
        }
        ticket.markAdmitted(); // single-use: a replayed admission is refused forever after
        return Outcome.ok(binding, null);
    }

    private SourceQualification sourceFromTicket(PrivateBuildTickets.Ticket ticket) {
        switch (ticket.sourceStatus()) {
            case SHARED_PENDING_NBT:
                return SourceQualification.SHARED_PENDING_NBT;
            case OLD_DATA_VERSION:
                return SourceQualification.OLD_DATA_VERSION;
            default:
                return SourceQualification.TAINTED;
        }
    }

    // ------------------------------------------------------------------
    // Owner allocation and attachment (owner only, under the gate)
    // ------------------------------------------------------------------

    /**
     * Registers a verified fresh owner allocation as OWNER_PENDING for the
     * current outer publication transaction, or for the enclosing whole writer
     * operation when no publication transaction is open (post-readiness plane
     * replacement and similar owner mutations). Existing identities are never
     * relabeled.
     */
    public Outcome registerOwnerPending(Object component, Object backing) {
        gate.assertOwnerUnderGate("registerOwnerPending");
        if (component == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        long txnId = txnStack.isEmpty() ? 0L : txnStack.peek();
        if (txnId == 0L && gate.writerDepth() == 0) {
            return Outcome.fail(FailureReason.NO_ACTIVE_TRANSACTION);
        }
        if (!tickets.registerOwnerPending(component, backing, txnId)) {
            return Outcome.fail(FailureReason.REGISTRATION_REJECTED);
        }
        PrivateBuildTickets.ComponentRecord record = tickets.componentRecordFor(component);
        if (txnId != 0L) {
            pendingByTxn.get(txnId).add(record);
        } else {
            writerScopePendings.add(record);
        }
        return Outcome.ok(null, null);
    }

    /**
     * Validates one reference-slot attachment store BEFORE the original Java
     * store. Two branches with different rules:
     *
     * <ul>
     * <li><b>Private-build branch (I/O worker, never takes the gate):</b>
     * requires an active BUILDING ticket and a PRIVATE_BUILDING receiver under
     * it. Same-ticket private closures attach freely; a shared/foreign
     * reference may be installed only while marking the ticket's source graph
     * sticky-INELIGIBLE (retaining the backing's original ownership). A worker
     * attach into a published (OWNER_BOUND) receiver is a fatal before-write
     * violation.</li>
     * <li><b>Owner branch (under the gate):</b> permits this transaction's
     * OWNER_PENDING, OWNER_BOUND to this exact binding, and (post-readiness)
     * same-binding moves. Foreign aliases revoke both known bindings;
     * unknown/shared/sealed-not-adopted values reject and revoke the receiving
     * binding; a still-BUILDING private component reaching the owner is a
     * terminal PRIVATE_GRAPH_ESCAPE thrown before the store. A failed
     * attachment marks the receiving binding permanently INELIGIBLE before the
     * original Java store; Java continues.</li>
     * </ul>
     */
    public Outcome beforeAttach(Object chunk, Object receiver, Object value) {
        if (tickets.hasActiveTicketOnCurrentThread() && !gate.isOwnerUnderGate()) {
            return privateAttach(receiver, value);
        }
        return ownerAttach(chunk, value);
    }

    private Outcome privateAttach(Object receiver, Object value) {
        PrivateBuildTickets.Ticket ticket = tickets.activeTicketForCurrentThread();
        PrivateBuildTickets.ComponentRecord receiverRecord = tickets.componentRecordFor(receiver);
        if (receiverRecord != null
                && receiverRecord.state() == PrivateBuildTickets.ComponentRecord.State.OWNER_BOUND) {
            // Off-owner attachment into a published receiver: fatal before-write violation.
            gate.disqualify(LiveWriterGate.DisqualificationReason.PRIVATE_ALIAS_WRITE);
            throw new LiveWriterGate.ProtocolViolationException(
                    LiveWriterGate.DisqualificationReason.PRIVATE_ALIAS_WRITE,
                    "private worker attach into published receiver");
        }
        if (receiverRecord == null || receiverRecord.ticket() != ticket
                || receiverRecord.state() != PrivateBuildTickets.ComponentRecord.State.PRIVATE_BUILDING) {
            tickets.taintTicket(ticket, PrivateBuildTickets.TaintReason.UNKNOWN_RECEIVER);
            if (value != null) tickets.tombstoneAsSharedOrUnknown(value);
            return Outcome.ok(null, null); // Java continues; the graph can never become eligible
        }
        if (value == null) return Outcome.ok(null, null);
        if (tickets.isPrivateComponent(ticket, value)) return Outcome.ok(null, null);
        // Shared/foreign reference install: permitted for Java, sticky taint,
        // backing ownership retained; wrapping the reference is not a byte write.
        tickets.taintTicket(ticket, PrivateBuildTickets.TaintReason.SHARED_ATTACH);
        tickets.tombstoneAsSharedOrUnknown(value);
        return Outcome.ok(null, null);
    }

    private Outcome ownerAttach(Object chunk, Object value) {
        gate.assertOwnerUnderGate("beforeAttach");
        if (chunk == null) return Outcome.fail(FailureReason.INVALID_INPUT);
        Binding receiving = byChunkObject.get(chunk);
        if (receiving == null) return Outcome.fail(FailureReason.NO_BINDING);
        if (receiving.state != BindingState.PUBLISHING && receiving.state != BindingState.READY) {
            return Outcome.fail(FailureReason.NOT_PUBLISHING);
        }
        if (value == null) return Outcome.ok(null, null);
        PrivateBuildTickets.ComponentRecord record = tickets.componentRecordFor(value);
        if (record == null) {
            // Unknown provenance is never relabeled owned; fail closed for this
            // binding before the original Java store; Java continues.
            revoke(receiving);
            return Outcome.fail(FailureReason.UNKNOWN_VALUE);
        }
        switch (record.state()) {
            case OWNER_PENDING: {
                long txnId = record.ownerTxnId();
                boolean inCurrentTxn = txnId != 0L && txnStack.contains(txnId);
                boolean inWriterScope = txnId == 0L && gate.writerDepth() > 0;
                if (!inCurrentTxn && !inWriterScope) {
                    // Pending from an exited transaction: non-private tombstone, never reusable.
                    revoke(receiving);
                    return Outcome.fail(FailureReason.ORPHAN_PENDING);
                }
                record.bindToOwner(receiving);
                receiving.ownedComponents.add(record);
                if (txnId != 0L) {
                    pendingByTxn.get(txnId).remove(record);
                } else {
                    writerScopePendings.remove(record);
                }
                return Outcome.ok(null, null);
            }
            case OWNER_BOUND:
                if (record.ownerBinding() == receiving) return Outcome.ok(null, null);
                // Foreign alias: taints/revokes both known bindings before the store.
                revoke(record.ownerBinding());
                revoke(receiving);
                return Outcome.fail(FailureReason.FOREIGN_ALIAS);
            case PRIVATE_BUILDING:
                if (record.isEffectivelySealedIo()) {
                    // SEALED_IO attaches only through explicit publication adoption.
                    revoke(receiving);
                    return Outcome.fail(FailureReason.SEALED_NOT_ADOPTED);
                }
                // Still-BUILDING private component reached the owner: escape before any store.
                gate.disqualify(LiveWriterGate.DisqualificationReason.PRIVATE_GRAPH_ESCAPE);
                throw new LiveWriterGate.ProtocolViolationException(
                        LiveWriterGate.DisqualificationReason.PRIVATE_GRAPH_ESCAPE,
                        "BUILDING private component attached by the owner");
            case RETIRED_OWNER: {
                Binding oldBinding = record.ownerBinding();
                boolean sameExactQualifiedGraph = oldBinding != null
                        && oldBinding.state == BindingState.RETIRED
                        && oldBinding.chunk == chunk      // the SAME exact Chunk graph only
                        && oldBinding.isSourceQualified(); // qualified retained origin only
                if (sameExactQualifiedGraph) {
                    // Qualified dormant rebind through explicit publication: fresh binding,
                    // fresh incarnation/generation; the old token is never revived.
                    record.bindToOwner(receiving);
                    receiving.ownedComponents.add(record);
                    return Outcome.ok(null, null);
                }
                revoke(receiving);
                return Outcome.fail(FailureReason.RETIRED_COMPONENT_REUSE);
            }
            case SHARED_OR_UNKNOWN:
            default:
                revoke(receiving);
                return Outcome.fail(FailureReason.SHARED_COMPONENT);
        }
    }

    private void revoke(Binding binding) {
        if (binding == null) return;
        binding.state = BindingState.INELIGIBLE;
        binding.revocation.revoke();
        pendingFinalize.remove(binding);
    }

    // ------------------------------------------------------------------
    // Capture-side resolution (called by the gate, owner under gate)
    // ------------------------------------------------------------------

    /** Resolves the exact binding by object identity; never creates a binding here. */
    LiveChunkBindings.Binding resolveForCapture(Object world, Object chunk) {
        Binding binding = byChunkObject.get(chunk);
        if (binding == null) {
            lastCaptureRejection = CaptureRejection.NO_BINDING;
            return null;
        }
        if (binding.world != world) {
            lastCaptureRejection = CaptureRejection.PROVIDER_IDENTITY_CHANGED;
            return null;
        }
        if (binding.state == BindingState.RETIRED) {
            lastCaptureRejection = CaptureRejection.UNLOADED;
            return null;
        }
        if (binding.state != BindingState.READY) {
            lastCaptureRejection = CaptureRejection.NOT_READY; // PRIVATE/PUBLISHING/INELIGIBLE
            return null;
        }
        if (binding.revocation.isRevoked()) {
            lastCaptureRejection = CaptureRejection.UNLOADED; // defensive: revoked while still mapped
            return null;
        }
        if (!binding.isSourceQualified()) {
            lastCaptureRejection = CaptureRejection.SOURCE_UNQUALIFIED;
            return null;
        }
        return binding;
    }

    CaptureRejection lastCaptureRejectionReason() {
        return lastCaptureRejection == null ? CaptureRejection.NO_BINDING : lastCaptureRejection;
    }

    /** Maps the capture rejection to the gate's fallback vocabulary (1:1 names). */
    LiveWriterGate.FallbackReason captureFallbackReason() {
        CaptureRejection rejection = lastCaptureRejectionReason();
        switch (rejection) {
            case PROVIDER_IDENTITY_CHANGED:
                return LiveWriterGate.FallbackReason.PROVIDER_IDENTITY_CHANGED;
            case UNLOADED:
                return LiveWriterGate.FallbackReason.UNLOADED;
            case NOT_READY:
                return LiveWriterGate.FallbackReason.NOT_READY;
            case SOURCE_UNQUALIFIED:
                return LiveWriterGate.FallbackReason.SOURCE_UNQUALIFIED;
            case NO_BINDING:
            default:
                return LiveWriterGate.FallbackReason.NO_BINDING;
        }
    }

    /** Final commit membership check: exact object still bound to the same READY unrevoked binding. */
    boolean stillValidForCapture(Object chunk, Binding binding) {
        Binding current = byChunkObject.get(chunk);
        return current == binding && binding.state == BindingState.READY
                && !binding.revocation.isRevoked();
    }

    // ------------------------------------------------------------------
    // Finalization (called by the gate / finishPublication, owner under gate)
    // ------------------------------------------------------------------

    void finalizePendingPublications() {
        for (Binding binding : pendingFinalize) {
            if (binding.state == BindingState.PUBLISHING && binding.publicationCompleted) {
                binding.state = BindingState.READY;
            }
        }
        pendingFinalize.clear();
    }

    void invalidatePendingPublications() {
        invalidateAllPending();
    }

    private void invalidateAllPending() {
        for (Binding binding : pendingFinalize) {
            if (binding.state == BindingState.PUBLISHING || binding.state == BindingState.PRIVATE) {
                binding.state = BindingState.INELIGIBLE;
                binding.revocation.revoke();
            }
        }
        pendingFinalize.clear();
    }

    /** Unattached writer-scope allocations become non-private tombstones at outermost scope exit. */
    void sweepWriterScopePendings() {
        for (PrivateBuildTickets.ComponentRecord record : writerScopePendings) {
            record.tombstoneAsSharedOrUnknown();
        }
        writerScopePendings.clear();
    }

    /** Session-end revocation and retention clear; only called on a fully quiesced shutdown. */
    void revokeAllForSessionEnd() {
        for (Binding binding : byChunkObject.values()) {
            binding.state = BindingState.RETIRED;
            binding.revocation.revoke();
        }
        byChunkObject.clear();
        coordsByWorld.clear();
        worldIds.clear();
        txnStack.clear();
        pendingByTxn.clear();
        writerScopePendings.clear();
        pendingFinalize.clear();
    }

    // ------------------------------------------------------------------
    // Introspection
    // ------------------------------------------------------------------

    /** Owner-visible lookup of the current binding for an exact chunk object (may be null). */
    public Binding bindingFor(Object chunk) {
        return chunk == null ? null : byChunkObject.get(chunk);
    }

    public int bindingCount() {
        return byChunkObject.size();
    }

    private Binding createBinding(Object world, Object chunk, BindingState state, SourceQualification source) {
        if (byChunkObject.size() >= MAX_BINDINGS) {
            gate.disqualify(LiveWriterGate.DisqualificationReason.RESOURCE_LIMIT);
            return null;
        }
        if (nextWorldId == Long.MAX_VALUE || nextChunkId == Long.MAX_VALUE
                || nextIncarnation == Long.MAX_VALUE || nextOwnedEncodeGeneration == Long.MAX_VALUE) {
            gate.disqualify(LiveWriterGate.DisqualificationReason.COUNTER_EXHAUSTED);
            return null;
        }
        Long worldId = worldIds.get(world);
        if (worldId == null) {
            worldId = ++nextWorldId;
            worldIds.put(world, worldId);
        }
        Binding binding = new Binding(gate.sessionId(), worldId, ++nextChunkId, ++nextIncarnation,
                ++nextOwnedEncodeGeneration, world, chunk, state, source);
        byChunkObject.put(chunk, binding);
        return binding;
    }

    private static Long coordKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
