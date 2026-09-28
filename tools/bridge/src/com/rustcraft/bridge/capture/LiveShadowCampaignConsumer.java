package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.PacketEncodeResultV2;
import sun.misc.Unsafe;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-server diagnostic consumer for the FIRST bounded Clean Forge live SHADOW
 * campaign. Started only when -Drustcraft.liveWriterDiagnostic=true (never in
 * production). Drains the {@link LiveComparisonQueue}, replays each sealed
 * scope-3 transport through the existing owned Rust ABI, compares against the
 * SAME Java packet event (mask/length/bytes), and appends one machine-readable
 * JSONL record per compared event plus a shutdown receipt.
 *
 * <p>SHADOW ONLY: the Rust result is recorded and discarded. It never reaches
 * any wire path and never replaces Java packet content. A mismatch writes the
 * full event artifact, raises the stop flag, and stops further replay so the
 * campaign runner can shut the server down with evidence preserved.</p>
 */
public final class LiveShadowCampaignConsumer {

    private static final Unsafe MEMORY = unsafe();
    private static final int NATIVE_CAPACITY = 262144;

    public static final AtomicHolder COMPARED = new AtomicHolder();
    public static final AtomicHolder MASK_MISMATCH = new AtomicHolder();
    public static final AtomicHolder LENGTH_MISMATCH = new AtomicHolder();
    public static final AtomicHolder BYTE_MISMATCH = new AtomicHolder();
    public static final AtomicHolder REPLAY_FAILURE = new AtomicHolder();
    public static final AtomicHolder ARTIFACTS = new AtomicHolder();
    public static final AtomicHolder IO_ADOPTED_COMPARED = new AtomicHolder();

    /** Simple mutable holder (atomic semantics live in the drain loop thread). */
    public static final class AtomicHolder {
        private long value;
        public long get() { return value; }
        public void set(long v) { value = v; }
        public void increment() { value++; }
    }

    // ---- Phase D: bounded smoke cap + component timings --------------------
    // Once the cap is reached further sealed events are recorded DROPPED_CAPPED
    // (identity preserved, never parity). The smoke proves the pipeline; it is
    // not a campaign and does not chase counts.
    private static final long MAX_EVENTS =
            Long.getLong("rustcraft.liveShadowMaxEvents", 64).longValue();
    public static final AtomicHolder RUST_ENCODE_NANOS_TOTAL = new AtomicHolder();
    public static final AtomicHolder COMPARE_NANOS_TOTAL = new AtomicHolder();
    public static final AtomicHolder QUEUE_WAIT_NANOS_TOTAL = new AtomicHolder();
    public static final AtomicHolder TIMED_EVENTS = new AtomicHolder();

    private static volatile boolean started;
    private static volatile boolean mismatchSeen;
    private static PrintWriter jsonl;
    private static Path artifactDir;
    private static long nativeInput, nativeOutput;
    private static long startedAtMillis;

    private LiveShadowCampaignConsumer() { }

    /** Starts the consumer; idempotent. Called from the coremod when the diagnostic is on. */
    public static synchronized void start() {
        if (started) return;
        try {
            String out = System.getProperty("rustcraft.liveShadowOut", "live-shadow-events.jsonl");
            Path outPath = Paths.get(out).toAbsolutePath();
            if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
            jsonl = new PrintWriter(Files.newBufferedWriter(outPath, StandardCharsets.UTF_8), true);
            artifactDir = outPath.resolveSibling("mismatch-artifacts");
            Files.createDirectories(artifactDir);
            String dll = System.getProperty("rustcraft.liveShadowDll");
            if (dll == null) throw new IllegalStateException("rustcraft.liveShadowDll required");
            System.load(Paths.get(dll).toAbsolutePath().normalize().toString());
            nativeInput = MEMORY.allocateMemory(1024);
            nativeOutput = MEMORY.allocateMemory(NATIVE_CAPACITY);
            startedAtMillis = System.currentTimeMillis();
            started = true;
            if (Boolean.getBoolean("rustcraft.liveShadowDimTest")) {
                Thread dimTask = new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(30_000); } catch (InterruptedException i) { return; }
                        runDimensionExclusionTask();
                    }
                }, "rustcraft-live-shadow-dimtest");
                dimTask.setDaemon(true);
                dimTask.start();
            }
            Thread watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    // Campaign evidence: when the gate first disqualifies, capture
                    // every live thread's stack so the disqualifying call site is
                    // preserved even if Forge swallows the protocol exception.
                    while (!LiveWriterHooks.gateDisqualifiedSafeImpl()) {
                        try { Thread.sleep(25); } catch (InterruptedException i) { return; }
                    }
                    StringBuilder sb = new StringBuilder("[live-capture] DISQUALIFICATION WATCHDOG"
                            + System.lineSeparator());
                    for (Map.Entry<Thread, StackTraceElement[]> entry :
                            Thread.getAllStackTraces().entrySet()) {
                        sb.append("THREAD ").append(entry.getKey().getName())
                                .append(System.lineSeparator());
                        for (StackTraceElement e : entry.getValue()) {
                            sb.append("  at ").append(e).append(System.lineSeparator());
                        }
                    }
                    try {
                        Files.write(Paths.get(System.getProperty("rustcraft.liveShadowOut",
                                "live-shadow-events.jsonl")).toAbsolutePath()
                                .resolveSibling("disqualification-threads.txt"),
                                sb.toString().getBytes(StandardCharsets.UTF_8));
                    } catch (Throwable failure) {
                        System.err.print(sb);
                    }
                }
            }, "rustcraft-live-shadow-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            Thread thread = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "rustcraft-live-shadow-consumer");
            thread.setDaemon(true);
            thread.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override public void run() { writeReceipt(); }
            }, "rustcraft-live-shadow-receipt"));
            System.out.println("[live-capture] consumer started; out=" + outPath);
        } catch (Throwable failure) {
            System.err.println("[live-capture] consumer FAILED to start: " + failure);
            started = false;
        }
    }

    public static boolean isStarted() { return started; }

    public static boolean mismatchSeen() { return mismatchSeen; }

    /**
     * Multi-dimension exclusion evidence: obtains the REAL Nether WorldServer from
     * the live runtime, loads an ACTUAL chunk through its normal provider, and
     * presents it to the live capture eligibility layer. The qualified surface
     * profile must fail closed with UNSUPPORTED_WORLD — no sealed capture, no
     * queue entry, no gate disqualification.
     */
    private static void runDimensionExclusionTask() {
        try {
            Object fmlHandler = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler")
                    .getMethod("instance").invoke(null);
            Object server = fmlHandler.getClass().getMethod("getMinecraftServerInstance")
                    .invoke(fmlHandler);
            if (server == null) throw new IllegalStateException("no server instance");
            Class<?> mcsClass = server.getClass();
            while (mcsClass != null && !mcsClass.getName().equals("net.minecraft.server.MinecraftServer")) {
                mcsClass = mcsClass.getSuperclass();
            }
            if (mcsClass == null) throw new IllegalStateException("MinecraftServer class not found");
            // The Nether unloads seconds after boot when no player occupies it,
            // and dimension (re)creation performs owner-gated writes — running it
            // on this diagnostic thread would trip OFF_OWNER_PUBLISHED_WRITE and
            // terminally disqualify the gate. ALL Minecraft work therefore runs
            // on the canonical owner thread via addScheduledTask; this thread
            // only waits and then records the evidence.
            final Object serverRef = server;
            final String[] worldMeta = new String[2];
            java.util.concurrent.Callable<Object> ownerWork = new java.util.concurrent.Callable<Object>() {
                @Override public Object call() throws Exception {
                    Class<?> dimManager = Class.forName("net.minecraftforge.common.DimensionManager");
                    Object netherWorld = dimManager.getMethod("getWorld", int.class).invoke(null, -1);
                    if (netherWorld == null) {
                        dimManager.getMethod("initDimension", int.class).invoke(null, -1);
                        netherWorld = dimManager.getMethod("getWorld", int.class).invoke(null, -1);
                    }
                    if (netherWorld == null) throw new IllegalStateException("Nether WorldServer is null");
                    Object provider = null;
                    for (Field f : netherWorld.getClass().getSuperclass().getDeclaredFields()) {
                        if (f.getType().getName().endsWith("WorldProvider")) {
                            f.setAccessible(true);
                            provider = f.get(netherWorld);
                            break;
                        }
                    }
                    // Load a real Nether chunk through the normal provider path.
                    java.lang.reflect.Method getChunk = null;
                    for (java.lang.reflect.Method m : netherWorld.getClass().getMethods()) {
                        if (m.getName().equals("func_72964_e") && m.getParameterCount() == 2
                                && m.getParameterTypes()[0] == int.class && m.getParameterTypes()[1] == int.class) {
                            getChunk = m;
                            break;
                        }
                    }
                    if (getChunk == null) throw new IllegalStateException("getChunk accessor not found");
                    Object netherChunk = getChunk.invoke(netherWorld, 0, 0);
                    worldMeta[0] = provider == null ? "null" : provider.getClass().getName();
                    worldMeta[1] = netherChunk == null ? "null" : netherChunk.getClass().getName();
                    return netherChunk;
                }
            };
            Object netherChunk;
            try {
                // ListenableFuture via the JDK Future interface: this class must
                // stay compilable without the Guava jar on the classpath.
                java.util.concurrent.Future<Object> future =
                        (java.util.concurrent.Future<Object>)
                                mcsClass.getMethod("func_175586_a", java.util.concurrent.Callable.class)
                                        .invoke(server, ownerWork);
                netherChunk = future.get();
            } catch (java.util.concurrent.ExecutionException failure) {
                throw (Exception) failure.getCause();
            }
            String providerClass = worldMeta[0];
            String chunkClass = worldMeta[1];

            // Present the genuine non-Overworld chunk to the eligibility extractor.
            com.rustcraft.bridge.capture.LivePacketCapture.SourceFactory factory =
                    com.rustcraft.bridge.capture.LivePacketCapture.installedSourceFactory();
            String extraction = "no factory";
            if (factory != null) {
                // Exercise the installed explicit scope, never the legacy no-policy
                // overload. A missing binding cannot prove dimension exclusion.
                LiveChunkBindings.Binding binding = LiveWriterHooks.bindingsForTesting() == null
                        ? null : LiveWriterHooks.bindingsForTesting().bindingFor(netherChunk);
                if (binding == null) {
                    extraction = "UNCLASSIFIED_NO_BINDING (not dimension exclusion evidence)";
                } else {
                    LivePacketCapture.LiveCaptureSource source = factory.create(null, netherChunk, 0xFFFF,
                            binding, LiveWriterHooks.gateForTesting());
                    extraction = source == null ? "EXPLICIT_SCOPE_REJECTED (reason not independently classified)"
                            : "OUT_OF_SCOPE_ACCEPTED (unexpected)";
                }
            }
            Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("dimension", -1);
            rec.put("providerClass", providerClass);
            rec.put("chunkClass", chunkClass);
            rec.put("extraction", extraction);
            rec.put("timestamp", System.currentTimeMillis());
            Path out = Paths.get(System.getProperty("rustcraft.liveShadowOut",
                    "live-shadow-events.jsonl")).toAbsolutePath().resolveSibling("dimension-exclusion.json");
            Files.write(out, json(rec).getBytes(StandardCharsets.UTF_8));
            System.out.println("[live-capture] dimension exclusion evidence: " + json(rec));
        } catch (Throwable failure) {
            System.err.println("[live-capture] dimension exclusion task failed: " + failure);
        }
    }

    private static void loop() {
        while (!mismatchSeen) {
            List<SealedLiveCapture> drained = LiveComparisonQueue.drain();
            for (SealedLiveCapture sealed : drained) {
                compareAndRecord(sealed);
                if (mismatchSeen) return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void compareAndRecord(SealedLiveCapture sealed) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        try {
            ShadowEventJournal journal = ShadowEventJournal.instance();
            // Phase-D bounded smoke cap: once reached, further events are
            // recorded DROPPED_CAPPED with full identity; never compared,
            // never parity, never silent.
            if (journal != null && COMPARED.get() >= MAX_EVENTS) {
                journal.recordOutcome(sealed.gateEventId(), sealed.identity().sessionId,
                        ShadowEventJournal.Outcome.DROPPED, "CAPPED",
                        "event cap " + MAX_EVENTS + " reached; smoke is bounded by design");
                return;
            }
            byte[] transport = sealed.toTransportBytes();
            record.put("gateEventId", sealed.gateEventId());
            record.put("sessionId", sealed.identity().sessionId);
            record.put("worldId", sealed.identity().worldId);
            record.put("chunkId", sealed.identity().chunkId);
            record.put("chunkX", sealed.chunkX());
            record.put("chunkZ", sealed.chunkZ());
            record.put("incarnation", sealed.identity().incarnation);
            record.put("ownedEncodeGeneration", sealed.identity().ownedEncodeGeneration);
            record.put("requestedFilter", sealed.requestedFilter());
            record.put("javaMask", sealed.javaMask());
            record.put("sealedMask", sealed.sealedMask());
            record.put("fullChunk", sealed.javaFullChunk());
            record.put("epochStart", sealed.captureEpochStart());
            record.put("epochEnd", sealed.captureEpochEnd());
            record.put("bindingRevoked", sealed.identity().ownedEncodeGeneration < 0);
            record.put("ioAdopted", sealed.ioAdopted());

            // Phase-D completion registry: mint + contract check. A refusal
            // disqualifies the event -- it is never compared.
            if (journal != null) {
                ShadowEventJournal.RefusedCompletion refusal =
                        journal.mint(sealed.gateEventId(), sealed.identity().sessionId);
                if (refusal != null) {
                    journal.recordOutcome(sealed.gateEventId(), sealed.identity().sessionId,
                            ShadowEventJournal.Outcome.DISQUALIFIED, "COMPLETION_REGISTRY",
                            "refusal=" + refusal.refusal);
                    REPLAY_FAILURE.increment();
                    fail("completion registry refused event " + sealed.gateEventId()
                            + ": " + refusal.refusal, sealed, record, transport, null, -1);
                    return;
                }
            }

            // Independent Rust replay of the exact scope-3 transport (timed:
            // component measurement of the shadow encode only).
            if (transport.length > NATIVE_CAPACITY) throw new IllegalStateException("transport too large");
            if (nativeInput != 0) MEMORY.freeMemory(nativeInput);
            nativeInput = MEMORY.allocateMemory(transport.length);
            for (int i = 0; i < transport.length; i++) MEMORY.putByte(nativeInput + i, transport[i]);
            MEMORY.setMemory(nativeOutput, NATIVE_CAPACITY, (byte) 0xCC);
            long encodeStart = System.nanoTime();
            long packed = com.rustcraft.bridge.capture.OwnedSnapshotBridge.encodeOwnedV1(
                    nativeInput, transport.length, nativeOutput, NATIVE_CAPACITY);
            long encodeNanos = System.nanoTime() - encodeStart;
            RUST_ENCODE_NANOS_TOTAL.set(RUST_ENCODE_NANOS_TOTAL.get() + encodeNanos);
            QUEUE_WAIT_NANOS_TOTAL.set(QUEUE_WAIT_NANOS_TOTAL.get()
                    + (encodeStart - sealed.sealedAtNanos()));
            PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
            if (!result.isSuccess()) {
                REPLAY_FAILURE.increment();
                if (journal != null) journal.recordOutcome(sealed.gateEventId(),
                        sealed.identity().sessionId,
                        ShadowEventJournal.Outcome.INFRA_FAILURE, "RUST_ENCODE",
                        String.valueOf(result.failure()));
                record.put("rustFailure", String.valueOf(result.failure()));
                fail("rust replay failed", sealed, record, transport, null, -1);
                return;
            }
            int rustMask = result.emittedMask();
            int rustLen = result.bytesWritten();
            byte[] rustBody = new byte[rustLen];
            for (int i = 0; i < rustLen; i++) rustBody[i] = MEMORY.getByte(nativeOutput + i);
            byte[] javaBody = sealed.javaPayload();

            // Phase-D comparator: typed sides, exact equality, first divergence.
            ShadowEventComparator.JavaSide javaSide =
                    new ShadowEventComparator.JavaSide(javaBody, sealed.javaMask());
            ShadowEventComparator.RustSide rustSide =
                    new ShadowEventComparator.RustSide(rustBody, rustMask);
            ShadowEventComparator.Comparison comparison =
                    ShadowEventComparator.compareStatic(javaSide, rustSide);
            COMPARE_NANOS_TOTAL.set(COMPARE_NANOS_TOTAL.get() + comparison.compareNanos);
            TIMED_EVENTS.increment();

            boolean maskEqual = rustMask == sealed.javaMask() && rustMask == sealed.sealedMask()
                    && comparison.maskEqual;
            boolean lenEqual = rustLen == javaBody.length && comparison.lengthEqual;
            boolean byteEqual = Arrays.equals(rustBody, javaBody) && comparison.byteEqual;
            if (sealed.ioAdopted() && maskEqual && lenEqual && byteEqual) {
                IO_ADOPTED_COMPARED.increment();
            }
            record.put("rustMask", rustMask);
            record.put("rustLen", rustLen);
            record.put("javaLen", javaBody.length);
            record.put("rustEncodeNanos", encodeNanos);
            record.put("compareNanos", comparison.compareNanos);
            record.put("maskEqual", maskEqual);
            record.put("lenEqual", lenEqual);
            record.put("byteEqual", byteEqual);

            if (!maskEqual) MASK_MISMATCH.increment();
            if (!lenEqual) LENGTH_MISMATCH.increment();
            if (!byteEqual) BYTE_MISMATCH.increment();
            COMPARED.increment();

            if (journal != null) {
                if (maskEqual && lenEqual && byteEqual) {
                    journal.recordOutcome(sealed.gateEventId(), sealed.identity().sessionId,
                            ShadowEventJournal.Outcome.COMPARE_PASS, null,
                            "javaLen=" + javaBody.length + " mask=" + rustMask);
                } else {
                    ShadowEventComparator.FirstDivergence d = comparison.firstDivergence;
                    journal.recordOutcome(sealed.gateEventId(), sealed.identity().sessionId,
                            ShadowEventJournal.Outcome.COMPARE_MISMATCH, "FIRST_DIVERGENCE",
                            d == null ? "mask/length divergence" :
                                    "offset=" + d.offset + " javaLen=" + d.javaLength
                                    + " rustLen=" + d.rustLength + " region=" + d.region
                                    + " javaSha256=" + d.javaSha256
                                    + " rustSha256=" + d.rustSha256
                                    + " context=" + d.contextHex);
                }
            }

            synchronized (LiveShadowCampaignConsumer.class) {
                jsonl.println(json(record));
                jsonl.flush();
            }
            if (!maskEqual || !lenEqual || !byteEqual) {
                fail("comparison mismatch", sealed, record, transport, rustBody, javaBody.length);
            }
        } catch (Throwable failure) {
            REPLAY_FAILURE.increment();
            ShadowEventJournal journal = ShadowEventJournal.instance();
            if (journal != null) journal.recordOutcome(sealed.gateEventId(),
                    sealed.identity().sessionId,
                    ShadowEventJournal.Outcome.INFRA_FAILURE, "REPLAY_EXCEPTION",
                    String.valueOf(failure));
            try {
                fail("replay exception: " + failure, sealed, record, null, null, -1);
            } catch (Throwable ignored) { }
        }
    }

    /** Writes the full mismatch artifact, raises the stop flag, stops replay. */
    private static void fail(String why, SealedLiveCapture sealed, Map<String, Object> record,
                             byte[] transport, byte[] rustBody, int javaLen) {
        mismatchSeen = true;
        try {
            ARTIFACTS.increment();
            Path dir = artifactDir.resolve("event-" + ARTIFACTS.get() + "-" + sealed.gateEventId());
            Files.createDirectories(dir);
            if (transport != null) Files.write(dir.resolve("sealed-transport.bin"), transport);
            byte[] javaBody = sealed.javaPayload();
            Files.write(dir.resolve("java-body.bin"), javaBody);
            if (rustBody != null) Files.write(dir.resolve("rust-body.bin"), rustBody);
            record.put("why", why);
            record.put("javaLen", javaLen);
            record.put("javaMask", sealed.javaMask());
            record.put("chunkX", sealed.chunkX());
            record.put("chunkZ", sealed.chunkZ());
            record.put("filter", sealed.requestedFilter());
            record.put("timestamp", System.currentTimeMillis());
            Files.write(dir.resolve("meta.json"), json(record).getBytes(StandardCharsets.UTF_8));
            Files.write(artifactDir.resolveSibling("live-shadow-STOP"), String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            synchronized (LiveShadowCampaignConsumer.class) {
                jsonl.println(json(record));
                jsonl.flush();
            }
            System.err.println("[live-capture] MISMATCH — campaign stop requested: " + why
                    + " artifact=" + dir);
        } catch (Throwable failure) {
            System.err.println("[live-capture] artifact write failed: " + failure);
        }
    }

    /** Shutdown receipt: counters + classification input; final call is the runner's. */
    private static void writeReceipt() {
        try {
            Map<String, Object> receipt = new LinkedHashMap<String, Object>();
            receipt.put("compared", COMPARED.get());
            receipt.put("maskMismatch", MASK_MISMATCH.get());
            receipt.put("lengthMismatch", LENGTH_MISMATCH.get());
            receipt.put("byteMismatch", BYTE_MISMATCH.get());
            receipt.put("replayFailures", REPLAY_FAILURE.get());
            receipt.put("artifacts", ARTIFACTS.get());
            receipt.put("mismatchSeen", mismatchSeen);
            receipt.put("captured", LivePacketCapture.SEALED.get());
            receipt.put("admitted", LivePacketCapture.ADMITTED.get());
            receipt.put("seen", LivePacketCapture.SEEN.get());
            receipt.put("rejected", LivePacketCapture.REJECTED.get());
            receipt.put("aborted", LivePacketCapture.ABORTED.get());
            receipt.put("validationFailed", LivePacketCapture.VALIDATION_FAILED.get());
            receipt.put("captureBusy", LivePacketCapture.CAPTURE_BUSY.get());
            receipt.put("unsupported", LivePacketCapture.UNSUPPORTED.get());
            receipt.put("revokedDropped", LivePacketCapture.REVOKED_DROPPED.get());
            receipt.put("queueEnqueued", LivePacketCapture.ENQUEUED.get());
            receipt.put("queueDropped", LivePacketCapture.QUEUE_DROPPED.get());
            receipt.put("queueHighWater", LiveComparisonQueue.HIGH_WATER.get());
            receipt.put("queueHighWaterBytes", LiveComparisonQueue.HIGH_WATER_BYTES.get());
            receipt.put("rejectionReasons", LivePacketCapture.rejectionReasonCounts());
            receipt.put("gateDisqualified", LiveWriterHooks.gateDisqualifiedSafe());
            receipt.put("gateDisqualificationReason", LiveWriterHooks.gateDisqualificationReasonSafe());
            receipt.put("writerScopes", LiveWriterHooks.writerScopesForReceipt());
            receipt.put("constructedChunks", LiveWriterHooks.CONSTRUCTED_CHUNKS.get());
            receipt.put("retires", LiveWriterHooks.RETIRES.get());
            receipt.put("ioTasks", LiveWriterHooks.IO_TASKS.get());
            receipt.put("ioAdoptions", LiveWriterHooks.IO_ADOPTIONS.get());
            receipt.put("ioJavaOnly", LiveWriterHooks.IO_JAVA_ONLY.get());
            receipt.put("packetObservations", LivePacketCapture.SEEN.get());
            receipt.put("ioTicketCreated", LiveWriterHooks.IO_TICKET_CREATED.get());
            receipt.put("ioSealedSuccess", LiveWriterHooks.IO_SEALED_SUCCESS.get());
            receipt.put("ioSealedFailure", LiveWriterHooks.IO_SEALED_FAILURE.get());
            receipt.put("ioAcquired", LiveWriterHooks.IO_ACQUIRED.get());
            receipt.put("ioAdmitted", LiveWriterHooks.IO_ADMITTED.get());
            receipt.put("ioStale", LiveWriterHooks.IO_STALE.get());
            receipt.put("ioAdoptedCompared", IO_ADOPTED_COMPARED.get());
            receipt.put("teRejections", LivePacketCapture.TE_PRESENT_REJECTIONS.get());
            receipt.put("lastTeRejection", LivePacketCapture.lastTeRejectionEvidence());
            receipt.put("ioAdoptedCompared", IO_ADOPTED_COMPARED.get());
            receipt.put("teRejections", LivePacketCapture.TE_PRESENT_REJECTIONS.get());
            receipt.put("lastTeRejection", LivePacketCapture.lastTeRejectionEvidence());
            // ---- Phase-D: taxonomy, denominator, component timings, scope ----
            ShadowEventJournal journal = ShadowEventJournal.instance();
            if (journal != null) {
                journal.validate();
                receipt.put("phaseDTaxonomy", journal.taxonomyJson());
                receipt.put("phaseDJournalRecords", journal.recordsSize());
                receipt.put("phaseDProtocolViolations", journal.protocolViolations());
                SessionCompatibilityContract contract = journal.contract();
                if (contract != null) receipt.put("phaseDContract", contract.toJson());
            } else {
                receipt.put("phaseDTaxonomy", "journal-unbound");
            }
            receipt.put("phaseDMaxEvents", MAX_EVENTS);
            receipt.put("phaseDShadowScopeTelemetry", ShadowScopeGate.telemetryJson());
            long timed = TIMED_EVENTS.get();
            receipt.put("phaseDTimings", "{\"capture_begin_nanos_total\":"
                    + LivePacketCapture.BEGIN_NANOS_TOTAL.get()
                    + ",\"capture_begin_count\":" + LivePacketCapture.BEGIN_COUNT.get()
                    + ",\"capture_commit_nanos_total\":" + LivePacketCapture.COMMIT_NANOS_TOTAL.get()
                    + ",\"capture_commit_count\":" + LivePacketCapture.COMMIT_COUNT.get()
                    + ",\"enqueue_nanos_total\":" + LivePacketCapture.ENQUEUE_NANOS_TOTAL.get()
                    + ",\"rust_encode_nanos_total\":" + RUST_ENCODE_NANOS_TOTAL.get()
                    + ",\"compare_nanos_total\":" + COMPARE_NANOS_TOTAL.get()
                    + ",\"queue_wait_nanos_total\":" + QUEUE_WAIT_NANOS_TOTAL.get()
                    + ",\"timed_events\":" + timed
                    + ",\"claim_limit\":\"component measurements only; no end-to-end"
                    + " performance claim is made or implied\"}");
            receipt.put("queueCapacity", LiveComparisonQueue.capacityForReceipt());
            receipt.put("uptimeMillis", System.currentTimeMillis() - startedAtMillis);
            Path out = Paths.get(System.getProperty("rustcraft.liveShadowOut", "live-shadow-events.jsonl"))
                    .toAbsolutePath().resolveSibling("live-shadow-receipt.json");
            Files.write(out, json(receipt).getBytes(StandardCharsets.UTF_8));
            System.out.println("[live-capture] receipt written: " + out);
        } catch (Throwable failure) {
            System.err.println("[live-capture] receipt write failed: " + failure);
        }
    }

    private static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Object v = entry.getValue();
            sb.append('"').append(entry.getKey()).append("\":");
            if (v instanceof Map) {
                sb.append(jsonOfMap((Map<?, ?>) v));
            } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String jsonOfMap(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(String.valueOf(entry.getKey())).append("\":").append(entry.getValue());
        }
        return sb.append('}').toString();
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
}
