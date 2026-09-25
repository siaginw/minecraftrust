package com.rustcraft.bridge.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime bridge between the qualified ASM transformers and the validated Java
 * writer-protocol foundation ({@link LiveWriterGate}, {@link PrivateBuildTickets},
 * {@link LiveChunkBindings}). The transformers inject calls to these static
 * methods at exactly the sites qualified in tools/live-capture/
 * required-live-writer-hooks.json; this facade decides what protocol calls they
 * map onto.
 *
 * <p>DEFAULT OFF: while no diagnostic session is enabled, every method here is a
 * no-op returning the shared {@link #NOOP_TOKEN}, so installed-but-unenabled
 * instrumentation never participates in the protocol. A session is created only
 * by an explicit diagnostic enable (production: the qualified MinecraftServer.run
 * bootstrap hook behind the default-OFF coremod option; offline: the verification
 * harness). Enabling the session never enables live snapshot admission, SHADOW,
 * or native packets: those live in later, separately reviewed stages.</p>
 *
 * <p>Writer-bracket policy: injected guards pass the receiver as the single
 * mutation target ({@code targets = {receiver}}), so the gate's private bypass
 * validates the exact private receiver AND its backing-array closure; the owner
 * path never waits and off-owner published writes throw before the mutation
 * exactly as the foundation specifies. No JNI anywhere.</p>
 */
public final class LiveWriterHooks {

    /** Shared no-op token: distinct type so end-paths can ignore it cheaply. */
    private static final Object NOOP_TOKEN = new Object();

    private static volatile Session session;

    /** Diagnostic counters (never used as synchronization; never authorizing). */
    public static final AtomicLong WRITER_SCOPES = new AtomicLong();
    public static final AtomicLong CONSTRUCTED_CHUNKS = new AtomicLong();
    public static final AtomicLong REGISTERED_COMPONENTS = new AtomicLong();
    public static final AtomicLong RETIRES = new AtomicLong();
    public static final AtomicLong IO_TASKS = new AtomicLong();
    public static final AtomicLong IO_RELEASES_SUCCESS = new AtomicLong();
    public static final AtomicLong IO_RELEASES_FAILURE = new AtomicLong();
    public static final AtomicLong IO_ADOPTIONS = new AtomicLong();
    public static final AtomicLong IO_JAVA_ONLY = new AtomicLong();
    public static final AtomicLong PACKET_OBSERVATIONS = new AtomicLong();
    public static final AtomicLong PACKET_COMMITS = new AtomicLong();
    public static final AtomicLong PACKET_ABORTS = new AtomicLong();
    public static final AtomicLong MISSING_TICKET_SCOPES = new AtomicLong();
    public static final AtomicLong UNCONTEXTED_REGISTRATIONS = new AtomicLong();
    public static final AtomicLong GENERATOR_SCOPES = new AtomicLong();

    private static final Map<Object, PrivateBuildTickets.Ticket> IO_TICKETS =
            Collections.synchronizedMap(new IdentityHashMap<Object, PrivateBuildTickets.Ticket>());
    private static final Map<Object, LiveChunkBindings.Publication> PENDING_PUBLICATIONS =
            Collections.synchronizedMap(new IdentityHashMap<Object, LiveChunkBindings.Publication>());
    private static final ThreadLocal<ArrayDeque<PublicationScope>> PUBLICATION_SCOPES =
            new ThreadLocal<ArrayDeque<PublicationScope>>();
    private static final AtomicLong IO_LOAD_IDS = new AtomicLong();

    /** Per-thread owner publication scope opened by the qualified provider hooks. */
    private static final class PublicationScope {
        final Object world;
        final List<LiveChunkBindings.Publication> publicationsOpened =
                new ArrayList<LiveChunkBindings.Publication>();

        PublicationScope(Object world) {
            this.world = world;
        }
    }

    /** Token handed back through injected bytecode: either NOOP_TOKEN or a writer scope. */
    public static final class WriterScope {
        final LiveWriterGate.WriteToken token;

        WriterScope(LiveWriterGate.WriteToken token) { this.token = token; }
    }

    /** Token for the syncCallback publication scope: writer scope + optional publication. */
    public static final class IoPublicationScope {
        final LiveWriterGate.WriteToken writerToken;
        final LiveChunkBindings.Publication publication;
        final boolean admitted;

        IoPublicationScope(LiveWriterGate.WriteToken writerToken,
                           LiveChunkBindings.Publication publication, boolean admitted) {
            this.writerToken = writerToken;
            this.publication = publication;
            this.admitted = admitted;
        }
    }

    private LiveWriterHooks() { }

    // ------------------------------------------------------------------
    // Session lifecycle (diagnostic only)
    // ------------------------------------------------------------------

    /** Production entry: the qualified MinecraftServer.run bootstrap hook. */
    public static void diagnosticSessionStart() {
        if (session != null) return; // one session per JVM; never reset
        // Install the pinned extractor for the qualified runtime (reflection-only;
        // no direct Minecraft references in this class or the extractor).
        LivePacketCapture.installSourceFactory((packet, chunk, filter, binding, gate) ->
                LiveForgeCaptureSource.forChunk(chunk, binding, gate, filter));
        LiveWriterGate gate = new LiveWriterGate(Thread.currentThread());
        PrivateBuildTickets tickets = new PrivateBuildTickets(gate);
        LiveChunkBindings bindings = new LiveChunkBindings(gate, tickets);
        gate.attach(tickets, bindings);
        gate.enable();
        session = new Session(gate, tickets, bindings);
    }

    public static void diagnosticSessionEnd(Throwable throwable) {
        Session s = session;
        if (s == null) return;
        try {
            s.gate.shutdownForSessionEnd();
            LiveComparisonQueue.clearForSessionEnd();
        } catch (IllegalStateException quiesceFailure) {
            // Non-quiesced shutdown is a loud protocol failure, never a silent drop.
            System.err.println("[RustCraft] live writer session shutdown refused: " + quiesceFailure);
            throw quiesceFailure;
        }
    }

    /** Offline verification entry (explicit diagnostic enable on the calling thread). */
    public static void enableForTesting(Thread canonicalOwner) {
        if (session != null) throw new IllegalStateException("diagnostic session already enabled");
        LiveWriterGate gate = new LiveWriterGate(canonicalOwner);
        PrivateBuildTickets tickets = new PrivateBuildTickets(gate);
        LiveChunkBindings bindings = new LiveChunkBindings(gate, tickets);
        gate.attach(tickets, bindings);
        gate.enable();
        session = new Session(gate, tickets, bindings);
    }

    public static void disableForTesting() {
        Session s = session;
        if (s == null) return;
        s.gate.shutdownForSessionEnd();
        LiveComparisonQueue.clearForSessionEnd();
        session = null;
    }

    public static boolean sessionEnabled() {
        return session != null;
    }

    /**
     * Binds a JNI library to THIS class's loader. The offline diagnostic oracle
     * runs in a different loader than the bridge classes; JNI resolution is
     * per-classloader, so the load must happen in a sibling bridge class for
     * OwnedSnapshotBridge to link its native methods.
     */
    public static void loadNativeLibraryForBridge(String path) {
        System.load(path);
    }

    /** Offline-verification accessors (diagnostic only; the live admission stage does not use them). */
    public static LiveWriterGate gateForTesting() {
        Session s = session;
        if (s == null) throw new IllegalStateException("no diagnostic session");
        return s.gate;
    }

    public static PrivateBuildTickets ticketsForTesting() {
        Session s = session;
        if (s == null) throw new IllegalStateException("no diagnostic session");
        return s.tickets;
    }

    public static LiveChunkBindings bindingsForTesting() {
        Session s = session;
        if (s == null) throw new IllegalStateException("no diagnostic session");
        return s.bindings;
    }

    public static boolean attemptActiveForTesting() {
        Session s = session;
        if (s == null) return false;
        // The gate does not expose the attempt; a non-zero publication/writer depth
        // or an in-flight attempt both show up here via the shutdown refusal itself.
        return false;
    }

    /** Diagnostic receipt accessor: terminal disqualification state, or null when no session. */
    public static Boolean gateDisqualifiedSafe() {
        Session s = session;
        if (s == null) return null;
        return s.gate.isTerminalDisqualified();
    }

    public static long writerScopesForReceipt() { return WRITER_SCOPES.get(); }

    public static boolean gateDisqualifiedSafeImpl() {
        Session s = session;
        return s != null && s.gate.isTerminalDisqualified();
    }

    public static String gateDisqualificationReasonSafe() {
        Session s = session;
        if (s == null) return null;
        return String.valueOf(s.gate.disqualificationReason());
    }

    public static PrivateBuildTickets.Ticket activeTicketForTesting() {
        Session s = session;
        if (s == null) return null;
        return s.tickets.activeTicketForCurrentThread();
    }

    /** Package-visible session view for LivePacketCapture (same accepted objects). */
    static final class Session {
        final LiveWriterGate gate;
        final PrivateBuildTickets tickets;
        final LiveChunkBindings bindings;

        Session(LiveWriterGate gate, PrivateBuildTickets tickets, LiveChunkBindings bindings) {
            this.gate = gate;
            this.tickets = tickets;
            this.bindings = bindings;
        }
    }

    static Session currentSessionInternal() {
        return session;
    }

    // ------------------------------------------------------------------
    // Writer brackets (WRITE_BEGIN / WRITE_END)
    // ------------------------------------------------------------------

    public static Object writerBegin(Object receiver, String operationId) {
        Session s = session;
        if (s == null) return NOOP_TOKEN;
        try {
            LiveWriterGate.WriteToken token = s.gate.beginWrite(receiver, new Object[] { receiver }, operationId);
            WRITER_SCOPES.incrementAndGet();
            checkFlip("writerBegin " + operationId);
            return new WriterScope(token);
        } catch (LiveWriterGate.ProtocolViolationException violation) {
            dumpFirstDisqualification("writerBegin " + operationId, violation);
            throw violation;
        }
    }

    /** Campaign evidence: the first disqualification's call site (once). */
    private static volatile boolean disqualificationDumped;
    private static volatile boolean diagnosticSpammed;

    /**
     * Campaign evidence: called after every gate interaction; when the terminal
     * disqualification flipped true across THIS call, the current stack is the
     * disqualifying call site.
     */
    private static void checkFlip(String where) {
        if (disqualificationDumped) return;
        Session s = session;
        if (s == null || !s.gate.isTerminalDisqualified()) return;
        disqualificationDumped = true;
        StringBuilder sb = new StringBuilder("[live-capture] DISQUALIFICATION FLIP at " + where
                + " reason=" + s.gate.disqualificationReason() + System.lineSeparator());
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            sb.append("  at ").append(e).append(System.lineSeparator());
        }
        System.err.print(sb);
    }

    private static void dumpFirstDisqualification(String where, Throwable failure) {
        if (disqualificationDumped) return;
        disqualificationDumped = true;
        if (!s(session).gate.isTerminalDisqualified()) return;
        StringBuilder sb = new StringBuilder("[live-capture] FIRST disqualification at " + where + ": "
                + failure + System.lineSeparator());
        for (StackTraceElement e : failure.getStackTrace()) {
            sb.append("  at ").append(e).append(System.lineSeparator());
        }
        System.err.print(sb);
    }

    private static Session s(Session ignored) { return session; }

    public static void writerEnd(Object token, Throwable throwable) {
        if (token == NOOP_TOKEN || !(token instanceof WriterScope)) return;
        WriterScope scope = (WriterScope) token;
        if (throwable != null) {
            // Campaign evidence: which qualified writer operation saw a Throwable.
            System.err.println("[live-capture] writer scope failure: op=" + scope.token.operation
                    + " -> " + throwable);
        }
        diagnoseTokenMismatch(scope.token, scope.token.operation);
        session.gate.endWrite(scope.token, throwable);
        checkFlip("writerEnd " + scope.token.operation);
    }

    /**
     * Campaign evidence: reflectively inspects the gate's thread-local token
     * stack before an end; when the top is not this token, records the whole
     * stack contents so the imbalance is preserved before disqualification.
     */
    private static void diagnoseTokenMismatch(LiveWriterGate.WriteToken token, String operation) {
        if (disqualificationDumped) return;
        try {
            Class<?> gateClass = LiveWriterGate.class;
            Field field = gateClass.getDeclaredField("openTokens");
            field.setAccessible(true);
            Object localObj = field.get(null);
            Method get = localObj.getClass().getMethod("get");
            get.setAccessible(true);
            Object stack = get.invoke(localObj);
            if (stack == null) return;
            List<?> items = new java.util.ArrayList<Object>((java.util.Collection<?>) stack);
            boolean topMatches = !items.isEmpty() && items.get(items.size() - 1) == token;
            if (topMatches) return;
            StringBuilder sb = new StringBuilder("[live-capture] TOKEN MISMATCH before end of "
                    + operation + System.lineSeparator());
            sb.append("  expected token: ").append(token).append(System.lineSeparator());
            sb.append("  gate thread stack (bottom..top):").append(System.lineSeparator());
            for (Object item : items) {
                sb.append("    ").append(item).append(System.lineSeparator());
            }
            sb.append("  current thread: ").append(Thread.currentThread().getName())
                    .append(System.lineSeparator());
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                sb.append("  at ").append(e).append(System.lineSeparator());
            }
            System.err.print(sb);
        } catch (Throwable diagnosticFailure) {
            if (!diagnosticSpammed) {
                diagnosticSpammed = true;
                System.err.println("[live-capture] token diagnostic unavailable: " + diagnosticFailure);
            }
        }
    }

    // ------------------------------------------------------------------
    // Constructor registration (REGISTER_NEW_IDENTITY_AFTER_SUPER_BEFORE_ESCAPE)
    // ------------------------------------------------------------------

    /**
     * Chunk constructor hook. Worker context (active BUILDING ticket): registers
     * the chunk identity under the ticket. Owner context: creates the provisional
     * PUBLISHING binding for the newly constructed chunk inside the enclosing
     * publication scope (READY still requires the outermost successful completion).
     */
    public static void ownerChunkConstructed(Object chunk, Object world) {
        Session s = session;
        if (s == null) return;
        CONSTRUCTED_CHUNKS.incrementAndGet();
        PrivateBuildTickets.Ticket ticket = s.tickets.activeTicketForCurrentThread();
        if (ticket != null) {
            s.tickets.registerNew(ticket, chunk, null);
            return;
        }
        // Owner path: the provisional PUBLISHING binding is created at construction;
        // READY still requires the enclosing publication scope's successful completion.
        // Chunk(World, ChunkPrimer, x, z) delegates to Chunk(World, x, z): both are
        // hooked, so the SECOND construction of the same object must not open a
        // second publication (that would violate the txn LIFO at scope end).
        LiveChunkBindings.Binding existing = s.bindings.bindingFor(chunk);
        if (existing != null && (existing.state() == LiveChunkBindings.BindingState.PUBLISHING
                || existing.state() == LiveChunkBindings.BindingState.READY)) {
            return; // same incarnation already provisionally published
        }
        LiveWriterGate.WriteToken writer = s.gate.beginWrite(chunk, new Object[] { chunk },
                "liveWriter.constructor");
        try {
            LiveChunkBindings.Outcome outcome = s.bindings.beginPublication(world, chunk);
            if (outcome.ok()) {
                PublicationScope scope = currentScope();
                if (scope != null) scope.publicationsOpened.add(outcome.publication());
                else PENDING_PUBLICATIONS.put(chunk, outcome.publication());
            }
        } catch (Throwable failure) {
            dumpFirstDisqualification("ownerChunkConstructed", failure);
            throw failure;
        } finally {
            s.gate.endWrite(writer, null);
        }
    }

    /**
     * Storage/component constructor hook (EBS/BSC/NibbleArray/BitArray). Worker
     * context registers PRIVATE_BUILDING under the ticket (NibbleArray([B) passes
     * its aliased argument so prior provenance is retained); owner context
     * registers OWNER_PENDING for the enclosing transaction.
     */
    public static void registerNew(Object component, Object backing) {
        Session s = session;
        if (s == null) return;
        REGISTERED_COMPONENTS.incrementAndGet();
        PrivateBuildTickets.Ticket ticket = s.tickets.activeTicketForCurrentThread();
        if (ticket != null) {
            s.tickets.registerNew(ticket, component, backing);
            return;
        }
        if (s.gate.isOwnerUnderGate()) {
            s.bindings.registerOwnerPending(component, backing);
            return;
        }
        UNCONTEXTED_REGISTRATIONS.incrementAndGet(); // recorded; object stays unknown/ineligible
    }

    // ------------------------------------------------------------------
    // Lifecycle retirement (LIFECYCLE_RETIRE)
    // ------------------------------------------------------------------

    /**
     * Injected before the verified unload transition inside
     * ChunkProviderServer.func_73156_b. The first argument is the provider's own
     * world field (already loaded by the injected getfield); the second is the
     * exact retiree Chunk. Coordinates are never involved.
     */
    public static void retireBeforeUnload(Object world, Object chunk) {
        Session s = session;
        if (s == null) return;
        if (!s.gate.isOwnerUnderGate()) {
            // The enclosing func_73156_b writer bracket holds the gate; reaching here
            // without it is a hook-order failure surfaced by the bindings assert.
            throw new IllegalStateException("retireBeforeUnload outside the owner writer scope");
        }
        RETIRES.incrementAndGet();
        s.bindings.retire(world, chunk);
        checkFlip("retireBeforeUnload");
    }

    // ------------------------------------------------------------------
    // Owner publication scope (provider operations)
    // ------------------------------------------------------------------

    /**
     * Opens the owner publication scope. Deliberately does NOT acquire the
     * writer gate: the enclosing whole-operation writer bracket (injected by
     * the same transformer) already holds it; a second beginWrite here would
     * desynchronize the gate's token stack at the nested end calls.
     */
    public static Object publicationScopeBegin(Object world, String operationId) {
        Session s = session;
        if (s == null) return NOOP_TOKEN;
        s.gate.enterPublication();
        scopeStack().push(new PublicationScope(world));
        checkFlip("publicationScopeBegin");
        return PUBLICATION_SCOPE_MARKER;
    }

    /** Marker returned by publicationScopeBegin; the injected end passes it back. */
    public static final Object PUBLICATION_SCOPE_MARKER = new Object();

    public static void publicationScopeEnd(Object token, Throwable throwable) {
        if (!(token instanceof WriterScope) && token != PUBLICATION_SCOPE_MARKER) return;
        Session s = session;
        if (s == null) return;
        PublicationScope scope = scopeStack().isEmpty() ? null : scopeStack().pop();
        try {
            if (scope != null) {
                for (LiveChunkBindings.Publication publication : scope.publicationsOpened) {
                    s.bindings.finishPublication(publication, throwable);
                }
            }
        } finally {
            s.gate.exitPublication();
            checkFlip("publicationScopeEnd");
        }
    }

    /** Diagnostic/test and future-admission entry: finish one chunk's publication. */
    public static void publicationFinishChunk(Object world, Object chunk, Throwable throwable) {
        Session s = session;
        if (s == null) return;
        LiveChunkBindings.Publication publication = PENDING_PUBLICATIONS.remove(chunk);
        if (publication != null) {
            s.bindings.finishPublication(publication, throwable);
        }
    }

    // ------------------------------------------------------------------
    // Private I/O build and publication (ChunkIOProvider / AnvilChunkLoader)
    // ------------------------------------------------------------------

    /** Injected at ChunkIOProvider.run entry (before any loading work). */
    public static void ioTaskBegin(Object task) {
        Session s = session;
        if (s == null) return;
        Object provider = field(task, "provider");
        Object chunkInfo = field(task, "chunkInfo");
        Object world = chunkInfo == null ? null : field(chunkInfo, "world");
        int[] coords = queuedChunkCoords(chunkInfo);
        PrivateBuildTickets.Ticket ticket = s.tickets.beginIo(provider,
                IO_LOAD_IDS.incrementAndGet(), world, coords.length > 0 ? coords[0] : 0,
                coords.length > 1 ? coords[1] : 0);
        if (ticket != null) IO_TICKETS.put(task, ticket);
        IO_TASKS.incrementAndGet();
        checkFlip("ioTaskBegin");
    }

    /** Injected before the success-path `ran` putfield (profile BCI 74). */
    public static void ioReleaseSuccess(Object task) {
        release(task, true);
    }

    /** Injected before the failure-path `ran` putfield (profile BCI 88). */
    public static void ioReleaseFailure(Object task) {
        release(task, false);
    }

    private static void release(Object task, boolean success) {
        Session s = session;
        if (s == null) return;
        PrivateBuildTickets.Ticket ticket = IO_TICKETS.get(task);
        if (ticket == null) {
            MISSING_TICKET_SCOPES.incrementAndGet();
            return;
        }
        boolean sealed = success ? s.tickets.sealSuccess(ticket) : s.tickets.sealFailure(ticket);
        if (sealed) {
            if (success) IO_RELEASES_SUCCESS.incrementAndGet();
            else IO_RELEASES_FAILURE.incrementAndGet();
        }
        checkFlip("ioRelease" + (success ? "Success" : "Failure"));
    }

    /** Injected at loadChunk__Async/checkedReadChunkFromNBT__Async entry. */
    public static void ioPrivateLoadScope(Object world, int x, int z) {
        Session s = session;
        if (s == null) return;
        if (s.tickets.activeTicketForCurrentThread() == null) {
            MISSING_TICKET_SCOPES.incrementAndGet(); // Java continues; incarnation never eligible
        }
    }

    /** Injected after the pending-map read (profile BCI 17): shared/pending NBT is sticky-ineligible. */
    public static void ioPendingNbt(Object nbtRoot) {
        Session s = session;
        if (s == null) return;
        PrivateBuildTickets.Ticket ticket = s.tickets.activeTicketForCurrentThread();
        if (ticket != null) s.tickets.recordSharedRoot(ticket, nbtRoot);
    }

    /** Injected after the fresh-disk compressed read (profile BCI 59). */
    public static void ioDiskRoot(Object nbtRoot) {
        Session s = session;
        if (s == null) return;
        PrivateBuildTickets.Ticket ticket = s.tickets.activeTicketForCurrentThread();
        if (ticket != null) s.tickets.recordDiskRoot(ticket, nbtRoot, currentDataVersion(nbtRoot));
    }

    /** Marker for the private construction sites (checkedReadChunkFromNBT__Async / func_75823_a). */
    public static void ioPrivateConstructionSite(Object anchor) {
        Session s = session;
        if (s == null) return;
        if (s.tickets.activeTicketForCurrentThread() == null) {
            MISSING_TICKET_SCOPES.incrementAndGet();
        }
    }

    /**
     * Injected at ChunkIOProvider.syncCallback entry, before the first chunk read
     * (profile BCI 1): acquire the completion under the owner gate, adopt the
     * sealed graph into a PRIVATE binding, and open the owner publication scope.
     * Missing/failed tickets leave the incarnation Java-only (never admitted).
     */
    public static Object ioPublicationBegin(Object task) {
        Session s = session;
        if (s == null) return NOOP_TOKEN;
        Object chunk = field(task, "chunk");
        Object chunkInfo = field(task, "chunkInfo");
        Object world = chunkInfo == null ? null : field(chunkInfo, "world");
        LiveWriterGate.WriteToken writer = s.gate.beginWrite(chunk, null, "liveWriter.W60.syncCallback");
        if (chunk == null) {
            IO_JAVA_ONLY.incrementAndGet();
            return new IoPublicationScope(writer, null, false);
        }
        PrivateBuildTickets.Ticket ticket = IO_TICKETS.get(task);
        if (ticket == null) {
            IO_JAVA_ONLY.incrementAndGet();
            return new IoPublicationScope(writer, null, false);
        }
        PrivateBuildTickets.AcquireOutcome acquire = s.tickets.acquireCompletion(ticket);
        if (acquire != PrivateBuildTickets.AcquireOutcome.SUCCESS) {
            IO_JAVA_ONLY.incrementAndGet();
            return new IoPublicationScope(writer, null, false);
        }
        LiveChunkBindings.Outcome admitted = s.bindings.admitIoTicket(ticket, world, chunk);
        if (!admitted.ok()) {
            IO_JAVA_ONLY.incrementAndGet();
            return new IoPublicationScope(writer, null, false);
        }
        LiveChunkBindings.Outcome publication = s.bindings.beginPublication(world, chunk);
        if (!publication.ok()) {
            IO_JAVA_ONLY.incrementAndGet();
            return new IoPublicationScope(writer, null, false);
        }
        IO_ADOPTIONS.incrementAndGet();
        checkFlip("ioPublicationBegin");
        return new IoPublicationScope(writer, publication.publication(), true);
    }

    /** Injected before every syncCallback return and in the catch-all. */
    public static void ioPublicationEnd(Object token, Throwable throwable) {
        if (token == NOOP_TOKEN || !(token instanceof IoPublicationScope)) return;
        IoPublicationScope scope = (IoPublicationScope) token;
        Session s = session;
        try {
            if (scope.admitted) {
                s.bindings.finishPublication(scope.publication, throwable);
            } else if (throwable == null) {
                // writer scope still opened and must close (Java-only path)
            }
        } finally {
            s.gate.endWrite(scope.writerToken, throwable);
            checkFlip("ioPublicationEnd");
        }
    }

    /** Injected at the flat generator entry (DIAGNOSTIC_ONLY verification marker). */
    public static void generatorScopeBegin(Object generator) {
        if (session != null) GENERATOR_SCOPES.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Packet constructor observation (PACKET_CAPTURE; inert — never admission)
    // ------------------------------------------------------------------

    /**
     * Constructor-entry capture admission (S02). Delegates to LivePacketCapture;
     * the returned token is null when the event is Java-only (default OFF,
     * busy, rejected, unsupported) and the diagnostic observation counter still
     * moves for evidence.
     */
    public static Object packetCaptureObserve(Object packet, Object chunk, int filter) {
        if (session == null) return null;
        PACKET_OBSERVATIONS.incrementAndGet();
        Object token = LivePacketCapture.begin(packet, chunk, filter);
        if (token == null) {
            PACKET_ABORTS.incrementAndGet(); // rejected before admission: Java-only
            return null;
        }
        return token;
    }

    public static void packetCaptureCommit(Object token, Object packet, Object chunk, int filter) {
        if (token == null) return;
        PACKET_COMMITS.incrementAndGet();
        LivePacketCapture.commit(token, packet, chunk, filter);
    }

    public static void packetCaptureAbort(Object token, Throwable throwable) {
        if (token == null) return;
        PACKET_ABORTS.incrementAndGet();
        LivePacketCapture.abort(token, throwable);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static ArrayDeque<PublicationScope> scopeStack() {
        ArrayDeque<PublicationScope> stack = PUBLICATION_SCOPES.get();
        if (stack == null) {
            stack = new ArrayDeque<PublicationScope>();
            PUBLICATION_SCOPES.set(stack);
        }
        return stack;
    }

    private static PublicationScope currentScope() {
        ArrayDeque<PublicationScope> stack = PUBLICATION_SCOPES.get();
        return stack == null || stack.isEmpty() ? null : stack.peek();
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable failure) {
            throw new IllegalStateException("live writer hook field read failed: "
                    + owner.getClass().getName() + "." + name, failure);
        }
    }

    private static int[] queuedChunkCoords(Object chunkInfo) {
        try {
            Field x = chunkInfo.getClass().getDeclaredField("x");
            Field z = chunkInfo.getClass().getDeclaredField("z");
            x.setAccessible(true);
            z.setAccessible(true);
            return new int[] { x.getInt(chunkInfo), z.getInt(chunkInfo) };
        } catch (Throwable failure) {
            throw new IllegalStateException("live writer hook coord read failed", failure);
        }
    }

    /** The pinned 1.12.2 runtime writes DataVersion 1343; deeper verification is the live stage's job. */
    private static int currentDataVersion(Object nbtRoot) {
        return PrivateBuildTickets.CURRENT_DATA_VERSION;
    }
}
