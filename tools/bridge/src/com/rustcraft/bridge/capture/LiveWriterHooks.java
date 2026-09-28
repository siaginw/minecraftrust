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

    // -- single-ticket IO trace (diagnostic JSONL; path from rustcraft.ioTrace) --
    private static final java.io.PrintWriter IO_TRACE = openIoTrace();
    private static final AtomicLong IO_TRACE_REGISTERS = new AtomicLong();

    private static java.io.PrintWriter openIoTrace() {
        String path = System.getProperty("rustcraft.ioTrace");
        if (path == null) return null;
        try {
            java.io.PrintWriter out = new java.io.PrintWriter(
                    new java.io.FileWriter(path, true), true);
            out.println("{\"ev\":\"trace_open\",\"buildId\":\""
                    + jsonEscape(System.getProperty("rustcraft.buildId", "unset")) + "\"}");
            return out;
        } catch (Throwable failure) {
            System.err.println("[live-capture] io trace disabled: " + failure);
            return null;
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** One compact JSON object per line; null sink = tracing off. */
    private static synchronized void ioTrace(String event, String detail) {
        java.io.PrintWriter out = IO_TRACE;
        if (out == null) return;
        out.println("{\"ts\":" + System.currentTimeMillis() + ",\"thread\":\""
                + jsonEscape(Thread.currentThread().getName()) + "\",\"ev\":\"" + jsonEscape(event)
                + "\",\"d\":\"" + jsonEscape(detail) + "\"}");
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        String n = o.getClass().getSimpleName();
        return n.isEmpty() ? o.getClass().getName() : n;
    }

    static {
        // Campaign build identity: lets the runner prove the loaded jar is the
        // one it just built (stale-jar guard) without trusting file mtimes.
        String buildId = System.getProperty("rustcraft.buildId");
        if (buildId != null) {
            System.err.println("[live-capture] BUILD_ID " + buildId);
        }
    }

    /**
     * One traced registerNew call site shared by the constructor facades.
     *
     * <p>Provenance derivation for NBT primitive arrays: byte[] has no
     * constructor, so the ownership hooks can never mint its identity at
     * allocation. On the fresh-disk path the array's provable origin is the
     * ticket-qualified disk root itself ({@code ioDiskRoot} -> FRESH_DISK_CURRENT
     * under this same ticket, the accepted positive source event), whose members
     * are exactly what the loader feeds to NibbleArray([B). The derivation fires
     * ONLY for an as-yet-unregistered byte[] backing while the active ticket is
     * source-qualified and untainted; every other case reaches the foundation
     * unchanged (cross-ticket alias -> ALIASED_BACKING, unknown -> UNKNOWN_BACKING,
     * unqualified ticket -> taint, fail-closed as before).</p>
     */
    private static void tracedRegister(Session s, PrivateBuildTickets.Ticket ticket,
                                       Object component, Object backing) {
        if (backing instanceof byte[]
                && s.tickets.componentRecordFor(backing) == null
                && ticket.isSourceQualified()) {
            boolean derived = s.tickets.registerNew(ticket, backing, null);
            ioTrace("backing_derived", "ticket=" + ticket.ticketId() + " ok=" + derived
                    + " source=" + ticket.sourceStatus());
        }
        Object taintBefore = ticket.taintReason();
        boolean ok = s.tickets.registerNew(ticket, component, backing);
        Object taintAfter = ticket.taintReason();
        // Bounded evidence: every rejected registration and every taint
        // transition names its component/backing classes (first taint wins,
        // so this terminates); plus the first 40 clean registrations per JVM
        // to reconstruct the build order.
        if (!ok || taintAfter != null) {
            ioTrace("register", "ticket=" + ticket.ticketId() + " ok=" + ok
                    + " comp=" + brief(component) + " backing=" + brief(backing)
                    + " taint=" + taintAfter + " taintChanged=" + (taintBefore != taintAfter));
        } else if (IO_TRACE_REGISTERS.getAndIncrement() < 40) {
            ioTrace("register_ok", "ticket=" + ticket.ticketId()
                    + " comp=" + brief(component) + " backing=" + brief(backing));
        }
    }

    /** Diagnostic counters (never used as synchronization; never authorizing). */
    public static final AtomicLong WRITER_SCOPES = new AtomicLong();
    public static final AtomicLong CONSTRUCTED_CHUNKS = new AtomicLong();
    public static final AtomicLong REGISTERED_COMPONENTS = new AtomicLong();
    public static final AtomicLong RETIRES = new AtomicLong();
    public static final AtomicLong IO_TASKS = new AtomicLong();
    public static final AtomicLong IO_TICKET_CREATED = new AtomicLong();
    public static final AtomicLong IO_SEALED_SUCCESS = new AtomicLong();
    public static final AtomicLong IO_SEALED_FAILURE = new AtomicLong();
    public static final AtomicLong IO_STALE = new AtomicLong();
    public static final AtomicLong IO_ACQUIRED = new AtomicLong();
    public static final AtomicLong IO_ADMITTED = new AtomicLong();
    public static final java.util.Map<Long, Boolean> IO_ADOPTED_CHUNK_IDS =
            new java.util.concurrent.ConcurrentHashMap<Long, Boolean>();

    /** True when the chunk identity was adopted through the live IO-ticket path. */
    public static boolean isIoAdoptedChunkId(long chunkId) {
        return IO_ADOPTED_CHUNK_IDS.containsKey(chunkId);
    }

    /** The binding's chunkId for an adopted chunk object (0 when unbound). */
    public static long adoptedChunkId(Object chunk) {
        Session s = session;
        if (s == null || chunk == null) return 0L;
        LiveChunkBindings.Binding b = s.bindings.bindingFor(chunk);
        return b == null ? 0L : b.chunkId();
    }

    private static long adoptedChunkIdentity(Object chunk) {
        return adoptedChunkId(chunk);
    }
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
        ClassLoader bridgeLoader = LiveWriterHooks.class.getClassLoader();
        ClassLoader runtimeLoader = Thread.currentThread().getContextClassLoader();
        // Explicit historical Clean policy only, UNLESS Phase D explicitly
        // requests the Overworld per-chunk scope under an admitted plan
        // profile. A Revelation/unknown generated plan cannot inherit Clean
        // provenance or scope; the Phase-D path carries its own profile-bound
        // scope and never weakens the historical selection.
        LiveCaptureScope scope = LegacyCaptureScopes.selectedLegacyPlan(bridgeLoader);
        if (PhaseDScopePolicy.requested()) {
            String planProfile = PhaseDScopePolicy.installedPlanProfile(bridgeLoader);
            if (!PhaseDScopePolicy.admittedPlanProfile(planProfile)) {
                System.err.println("[RustCraft] phase-d scope refused: plan profile '"
                        + planProfile + "' is not admitted; keeping historical selection");
            } else {
                LiveCaptureScope overworld = PhaseDScopePolicy.overworldPerChunk(runtimeLoader, planProfile);
                if (overworld == null) {
                    System.err.println("[RustCraft] phase-d scope unavailable (registry unreadable"
                            + " or wider than the u16 transport); keeping historical selection");
                } else {
                    scope = overworld;
                }
            }
        }
        LivePacketCapture.installSourceFactory(scope == null ? null
                : scope.sourceFactory(runtimeLoader));
        LiveWriterGate gate = new LiveWriterGate(Thread.currentThread());
        PrivateBuildTickets tickets = new PrivateBuildTickets(gate);
        LiveChunkBindings bindings = new LiveChunkBindings(gate, tickets);
        gate.attach(tickets, bindings);
        gate.enable();
        session = new Session(gate, tickets, bindings);
        establishPhaseDJournal(scope, runtimeLoader);
    }

    /**
     * Phase-D session establishment: the SessionCompatibilityContract is
     * created ONCE here (registry facts read reflectively, one pass) and the
     * journal binds to it. No per-packet work happens in this method; when
     * the facts are unavailable the journal stays unbound and the consumer
     * keeps its historical behavior. Never throws: the shadow path fails
     * toward evidence, not toward behavior change.
     */
    private static void establishPhaseDJournal(LiveCaptureScope scope, ClassLoader runtimeLoader) {
        try {
            String processId = System.getProperty("rustcraft.session.processId");
            String sessionId = System.getProperty("rustcraft.session.transformationSessionId");
            if (processId == null || sessionId == null || scope == null) return;
            PhaseDScopePolicy.RegistryFacts facts = PhaseDScopePolicy.registryFacts(runtimeLoader);
            if (facts == null) return;
            SessionCompatibilityContract contract = SessionCompatibilityContract.establish(
                    processId, sessionId, scope.profileId,
                    facts.size, facts.widthBits, facts.digestSha256);
            String out = System.getProperty("rustcraft.liveShadowOut", "live-shadow-events.jsonl");
            String journalPath = System.getProperty("rustcraft.liveShadowJournal",
                    java.nio.file.Paths.get(out).toAbsolutePath().resolveSibling(
                            "shadow-journal.jsonl").toString());
            ShadowEventJournal bound = ShadowEventJournal.bind(contract, journalPath);
            if (bound == null)
                System.err.println("[RustCraft] phase-d journal could not bind; "
                        + "shadow outcomes will keep the historical counters only");
        } catch (Throwable failure) {
            System.err.println("[RustCraft] phase-d session establishment failed: " + failure);
        }
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
            tracedRegister(s, ticket, chunk, null);
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
            tracedRegister(s, ticket, component, backing);
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
        if (ticket != null) {
            IO_TICKETS.put(task, ticket);
            IO_TICKET_CREATED.incrementAndGet();
            ioTrace("ticket_created", "ticket=" + ticket.ticketId() + " loadId=" + ticket.loadId()
                    + " chunk=" + ticket.chunkX() + "," + ticket.chunkZ()
                    + " creator=" + ticket.creator().getName()
                    + " world=" + brief(ticket.world()));
        }
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
            if (success) {
                IO_RELEASES_SUCCESS.incrementAndGet();
                IO_SEALED_SUCCESS.incrementAndGet();
            } else {
                IO_RELEASES_FAILURE.incrementAndGet();
                IO_SEALED_FAILURE.incrementAndGet();
            }
        } else {
            IO_STALE.incrementAndGet(); // seal rejected: stale/replayed/already-sealed
        }
        ioTrace("io_sealed", "ticket=" + ticket.ticketId() + " requested=" + (success ? "SUCCESS" : "FAILURE")
                + " sealed=" + sealed + " state=" + ticket.state() + " consumed=" + ticket.isConsumed()
                + " source=" + ticket.sourceStatus() + " taint=" + ticket.taintReason());
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

    /**
     * Injected after the pending-map read (profile BCI 17). Diagnostic
     * observation only: does NOT set the ticket source. The authoritative
     * source comes from ioDiskRoot on the actual disk branch. The shared-
     * pending NBT semantics are enforced by the accepted Ultra contract at
     * the admission layer, not by a speculative pre-read hook.
     */
    public static void ioPendingNbt(Object pendingResult) {
        // Diagnostic observation; the shared-pending NBT rejection is handled
        // by the accepted admission model (ticket source must be FRESH_DISK_CURRENT).
        ioTrace("pending_observed", "nonNull=" + (pendingResult != null));
    }

    /** Injected after the fresh-disk compressed read (profile BCI 59). */
    public static void ioDiskRoot(Object nbtRoot) {
        Session s = session;
        if (s == null) return;
        PrivateBuildTickets.Ticket ticket = s.tickets.activeTicketForCurrentThread();
        if (ticket != null) {
            int dv = dataVersionOf(nbtRoot);
            Object prev = ticket.sourceStatus();
            boolean ok = s.tickets.recordDiskRoot(ticket, nbtRoot, dv);
            ioTrace("disk_root", "ticket=" + ticket.ticketId() + " dv=" + dv
                    + " prev=" + prev + " new=" + ticket.sourceStatus() + " ok=" + ok
                    + " taint=" + ticket.taintReason());
            if (!ok) {
                System.err.println("[live-capture] recordDiskRoot FAILED: dv=" + dv
                        + " source=" + ticket.sourceStatus() + " taint=" + ticket.taintReason());
            }
        }
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
            ioTrace("admit_failed", "ticket=null taskOnly=true chunk=" + brief(chunk));
            return new IoPublicationScope(writer, null, false);
        }
        String ticketId = "ticket=" + ticket.ticketId();
        PrivateBuildTickets.AcquireOutcome acquire = s.tickets.acquireCompletion(ticket);
        if (acquire != PrivateBuildTickets.AcquireOutcome.SUCCESS) {
            IO_JAVA_ONLY.incrementAndGet();
            if (acquire == PrivateBuildTickets.AcquireOutcome.REPLAYED) IO_STALE.incrementAndGet();
            ioTrace("acquire", ticketId + " outcome=" + acquire + " state=" + ticket.state()
                    + " consumed=" + ticket.isConsumed() + " source=" + ticket.sourceStatus()
                    + " taint=" + ticket.taintReason());
            return new IoPublicationScope(writer, null, false);
        }
        IO_ACQUIRED.incrementAndGet();
        ioTrace("acquire", ticketId + " outcome=SUCCESS state=" + ticket.state()
                + " consumed=" + ticket.isConsumed() + " source=" + ticket.sourceStatus()
                + " taint=" + ticket.taintReason());
        LiveChunkBindings.Outcome admitted = s.bindings.admitIoTicket(ticket, world, chunk);
        if (!admitted.ok()) {
            System.err.println("[live-capture] IO ADMISSION FAILED: " + admitted.reason()
                    + " ticketState=" + ticket.state() + " consumed=" + ticket.isConsumed()
                    + " source=" + ticket.sourceStatus() + " taint=" + ticket.taintReason()
                    + " world=" + (world != null ? world.getClass().getSimpleName() : "null")
                    + " chunkClass=" + (chunk != null ? chunk.getClass().getSimpleName() : "null"));
            IO_JAVA_ONLY.incrementAndGet();
            ioTrace("admit_failed", ticketId + " reason=" + admitted.reason()
                    + " state=" + ticket.state() + " consumed=" + ticket.isConsumed()
                    + " source=" + ticket.sourceStatus() + " taint=" + ticket.taintReason()
                    + " chunkClass=" + brief(chunk));
            return new IoPublicationScope(writer, null, false);
        }
        IO_ADMITTED.incrementAndGet();
        long adoptedId = adoptedChunkId(chunk);
        if (adoptedId != 0L) {
            IO_ADOPTED_CHUNK_IDS.put(adoptedId, Boolean.TRUE);
        }
        LiveChunkBindings.Outcome publication = s.bindings.beginPublication(world, chunk);
        if (!publication.ok()) {
            IO_JAVA_ONLY.incrementAndGet();
            ioTrace("publication_failed", ticketId + " reason=" + publication.reason()
                    + " chunkClass=" + brief(chunk));
            return new IoPublicationScope(writer, null, false);
        }
        IO_ADOPTIONS.incrementAndGet();
        ioTrace("admitted", ticketId + " chunkClass=" + brief(chunk)
                + " publicationOk=true adoptedId=" + adoptedId);
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
    // ---- qualified one-shot observation wrappers ---------------------------
    //
    // A one-shot observational hook is a notification, not a lifecycle: there is
    // no begin/end pair, so there is nothing to close on the way out. The only
    // exception contract such a hook can honour is containment, and -- this is
    // the part that matters -- it is honoured HERE, in the callee, not at the
    // Minecraft callsite.
    //
    // The alternative was a catch-all wrapped around the injected call in the
    // game method. That is not safe to do generically: the writer cannot know
    // the operand stack at an arbitrary anchor, and a handler is entered with an
    // empty stack, so resuming mid-expression means reconstructing the caller's
    // frame -- which is verifier-precise, was measured at depths 1 and 3 for the
    // S03 sites, and widened to java/lang/Object when spilled. Here the callsite
    // stays a single INVOKESTATIC with the same arguments and the same stack, and
    // the try/catch lives in a method that has no live stack to protect.
    //
    // The contract, precisely: a failure of the OBSERVATION is contained and the
    // caller continues normally. An exception from the caller's own code is not
    // swallowed, because it is raised outside this method and the range covers
    // only the observation call.
    /** Observations that failed and were contained, for qualification evidence. */
    public static final AtomicLong OBSERVATION_FAILURES_CONTAINED = new AtomicLong();

    // Each wrapper states its own containment rather than sharing one generic
    // helper. A shared helper that took a lambda or an interface would put the
    // real observation call inside a synthetic inner class, one frame away from
    // the catch-all -- and a contract that cannot be seen in the method it
    // claims to protect is a contract nobody can prove. Written out, the try
    // range visibly contains exactly the one call it is about.

    public static void safeIoPrivateLoadScope(Object world, int x, int z) {
        try {
            ioPrivateLoadScope(world, x, z);
        } catch (Throwable contained) {
            OBSERVATION_FAILURES_CONTAINED.incrementAndGet();
        }
    }

    public static void safeIoPendingNbt(Object pendingResult) {
        try {
            ioPendingNbt(pendingResult);
        } catch (Throwable contained) {
            OBSERVATION_FAILURES_CONTAINED.incrementAndGet();
        }
    }

    public static void safeIoDiskRoot(Object nbtRoot) {
        try {
            ioDiskRoot(nbtRoot);
        } catch (Throwable contained) {
            OBSERVATION_FAILURES_CONTAINED.incrementAndGet();
        }
    }

    public static void safeIoPrivateConstructionSite(Object anchor) {
        try {
            ioPrivateConstructionSite(anchor);
        } catch (Throwable contained) {
            OBSERVATION_FAILURES_CONTAINED.incrementAndGet();
        }
    }

    public static void safeGeneratorScopeBegin(Object generator) {
        try {
            generatorScopeBegin(generator);
        } catch (Throwable contained) {
            OBSERVATION_FAILURES_CONTAINED.incrementAndGet();
        }
    }

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

    /**
     * Reads the chunk NBT's DataVersion through the runtime class. Absent or
     * unreadable versions yield 0, which the ticket records as OLD_DATA_VERSION
     * (fail-closed: the incarnation stays Java-only).
     */
    private static int dataVersionOf(Object nbtRoot) {
        try {
            java.lang.reflect.Method getInteger = nbtRoot.getClass().getMethod("func_74762_e", String.class);
            getInteger.setAccessible(true);
            Integer value = (Integer) getInteger.invoke(nbtRoot, "DataVersion");
            return value == null ? 0 : value;
        } catch (Throwable failure) {
            return 0;
        }
    }
}
