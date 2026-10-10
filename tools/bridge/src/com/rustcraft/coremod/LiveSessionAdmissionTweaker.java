package com.rustcraft.coremod;

import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.launcher.FMLServerTweaker;

/**
 * Real-launch twin of the offline oracle's writer registration: a genuine
 * LaunchWrapper tweaker (the same mechanism Mixin itself uses) that
 * bootstraps live session admission inside a REAL Forge/FML server.
 *
 * <p>Deliberately NOT a coremod: FML's coremod inventory of the instrumented
 * runtime must stay exactly the qualified offline topology, so the admission
 * instrumentation loads through its own --tweakClass entry and never appears
 * as a mod or a coremod of the runtime it observes.</p>
 *
 * <p>What it does, all observation and registration of OUR OWN components:</p>
 * <ol>
 *   <li>delegates the real FML server bootstrap (FMLServerTweaker);</li>
 *   <li>excludes rustcraft packages from transformation (the support classes
 *       must not be a second in-loader copy of what the parent loads);</li>
 *   <li>binds the session environment from THIS launch's properties;</li>
 *   <li>installs the entry observer at the FRONT of the transformer chain;</li>
 *   <li>registers the three qualified writers (their first invocation places
 *       them at the chain tail — the qualified topology — via
 *       {@link LiveWriterOrdering});</li>
 *   <li>when rustcraft.qualificationResult is set, registers the shutdown
 *       hook that flushes this process's own evidence through the SAME
 *       shared producer the offline oracle uses.</li>
 * </ol>
 *
 * <p>It stamps nothing, decides nothing, and transmits nothing: the
 * qualification engine remains the decision point over evidence this JVM
 * observed about itself.</p>
 */
public final class LiveSessionAdmissionTweaker implements ITweaker {
    private final FMLServerTweaker delegate = new FMLServerTweaker();

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        delegate.acceptOptions(args, gameDir, assetsDir, profile);
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader cl) {
        System.err.println("[RustCraft] admission tweaker inject: target="
                + delegate.getLaunchTarget() + " diagnostic="
                + Boolean.getBoolean("rustcraft.liveWriterDiagnostic"));
        // Before ANY class can flow through the loader during the delegate's
        // own processing: the writers defer every bootstrap class until this
        // target -- the first class launchwrapper loads after the chain is
        // complete -- arrives.
        LiveWriterOrdering.armOn(delegate.getLaunchTarget());
        delegate.injectIntoClassLoader(cl);
        // Scope-correct: transformer-support packages load from the parent
        // classpath (the campaign jar is on -cp). Left in the launch loader
        // they would be a SECOND copy of the classes the writers use, so the
        // writers' records would land in one SameProcessAcquisition and the
        // chain would read the other's -- which is not a missing chain, it is
        // a chain that can never see a definition.
        cl.addClassLoaderExclusion("com.rustcraft.");
        cl.addTransformerExclusion("com.rustcraft.");
        cl.addClassLoaderExclusion("io.netty.");
        if (!Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) {
            return; // default OFF: a real launch with no diagnostic changes nothing
        }
        try {
            String processId = System.getProperty(
                    LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY);
            String sessionId = System.getProperty(
                    LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY);
            boolean sessionBound = processId != null && processId.length() > 0
                    && sessionId != null && sessionId.length() > 0;
            if (sessionBound) {
                LiveHookSupport.bindSessionEnvironment(
                        new LiveHookSupport.SystemPropertySessionEnvironment());
            }
            System.out.println("[RustCraft] live session admission tweaker: environment bound="
                    + sessionBound);
            // §20/§21 MSPT telemetry: the tweaker launch shape never runs the
            // FML coremod phase (LiveShadowCoreMod.injectData), so delegate
            // here — RustCraftCoreMod.injectData starts the vanilla tick-time
            // sampler (m1-metrics.txt). Measurement-only.
            try {
                new com.rustcraft.coremod.RustCraftCoreMod()
                        .injectData(new java.util.HashMap<String, Object>());
            } catch (Throwable t) {
                System.out.println("[RustCraft] MSPT telemetry start failed: " + t);
            }
            // The observer goes in at the FRONT so the chain's first edge is
            // the true entry buffer of this launch.
            System.out.println("[RustCraft] live session admission tweaker: entry observer "
                    + com.rustcraft.qualification.LoaderTransformChain.installAtFront(cl));
            // M1-COMPOSE: the WRITERS register FIRST among our own
            // transformers, and the ordering guard maintains
            // [foreign][writers][authorities]. The writers' qualified
            // pre-hook pins bind to FOREIGN-stage bytes (the discovery
            // probe registers no RustCraft transformers), so they must
            // transform before any owned authority pass injects into the
            // same classes. The authority passes are method-gated (no
            // class-digest admission) with disjoint hook sites; where a
            // method is shared (W05/WorldLight on func_180500_c,
            // W18/ChunkMutation on func_76631_c) both injections survive
            // and only in-method ordering flips. The guard's initial
            // state must already be tiered by this registration order —
            // its repair only ever moves late-appended foreign entries
            // back into the foreign prefix (writers/authorities shift
            // LATER, never before the live iterator).
            for (String name : new String[] {
                    "com.rustcraft.coremod.SPacketChunkDataTransformer",
                    "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
                    "com.rustcraft.coremod.LiveChunkPublicationTransformer"}) {
                cl.registerTransformer(name);
            }
            // Single-copy outbound boundary (self-gating: with the boundary
            // disabled the transformers return every class unmodified).
            // Registered INSIDE the writer block and counted in the ordering
            // guard's writer set, so the tier topology is stable from birth
            // and the qualified writers see byte-identical inputs.
            if (com.rustcraft.bridge.SingleCopyPipeline.enabled()) {
                cl.registerTransformer("com.rustcraft.coremod.NetworkManagerSingleCopyTransformer");
                cl.registerTransformer("com.rustcraft.coremod.NettyPacketEncoderCounterTransformer");
            }
            // Compression authority (M2C, default OFF) + the passive corpus
            // tap (rustcraft.compressionCorpus): the transformer rewrites only
            // the NettyCompressionEncoder construction site inside
            // NetworkManager, and self-gates on its own properties. Registered
            // BEFORE the counted writers - it is topologically a foreign
            // transformer, and the tail must contain exactly the counted set.
            // Gate on RAW SYSTEM PROPERTIES: touching NativeCompressionEncoder
            // here would class-load its NettyCompressionEncoder superclass at
            // tweak time (NoClassDefFoundError observed live) and kill the
            // whole registration section.
            if (!"OFF".equalsIgnoreCase(System.getProperty("minecraftrust.native_compress", "OFF"))
                    || System.getProperty("rustcraft.compressionCorpus") != null
                    || Boolean.getBoolean("rustcraft.rustCompressionExperiment")
                    || Boolean.getBoolean("rustcraft.rustCompressionShadow")) {
                cl.registerTransformer("com.rustcraft.coremod.NetworkManagerCompressionTransformer");
            }
            // LIVE region write authority (RUST_REGION_WRITE_AUTHORITY,
            // default OFF): rewrites only the RegionFile write/close seams and
            // self-gates on rustcraft.regionWriteExperiment. Registered with
            // the foreign transformers, before the counted writers.
            if (Boolean.getBoolean("rustcraft.regionWriteExperiment")) {
                cl.registerTransformer(
                        "com.rustcraft.coremod.RegionFileAuthorityTransformer");
            }
            // LIVE block-light authority: hooks Phosphor's LightingEngine
            // BLOCK drain; self-gates on rustcraft.lightExperiment.
            if (Boolean.getBoolean("rustcraft.lightExperiment")) {
                cl.registerTransformer(
                        "com.rustcraft.coremod.PhosphorLightTransformer");
                // per-cell vanilla checkLightFor shadow (works in launch
                // shapes where Phosphor mixins are inert)
                cl.registerTransformer(
                        "com.rustcraft.coremod.WorldLightTransformer");
                // COARSE BLOCK-light AUTHORITY (goal
                // RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY): World.checkLight
                // seam — Rust owns the BLOCK half of admitted jobs; self-
                // gates on rustcraft.lightMode=ON_EXPERIMENTAL.
                cl.registerTransformer(
                        "com.rustcraft.coremod.CheckLightAuthorityTransformer");
                System.out.println("[RustCraft-Light] authority registered:"
                        + " experiment="
                        + Boolean.getBoolean("rustcraft.lightExperiment")
                        + " mode=" + System.getProperty("rustcraft.lightMode",
                                "SHADOW"));
                // §1 diagnostics: PROVE why LightingEngine does/doesn't reach
                // the transformer. Print exclusion lists + chain membership.
                try {
                    java.lang.reflect.Method gt = cl.getClass().getMethod(
                            "getTransformers");
                    Object chain = gt.invoke(cl);
                    boolean inChain = String.valueOf(chain)
                            .contains("PhosphorLightTransformer");
                    System.out.println("[RustCraft-Light] in transformer chain="
                            + inChain);
                    for (String fieldName : new String[]{
                            "transformerExceptions", "classLoaderExceptions"}) {
                        try {
                            java.lang.reflect.Field ff = cl.getClass()
                                    .getDeclaredField(fieldName);
                            ff.setAccessible(true);
                            Object setObj = ff.get(cl);
                            for (Object e : (java.util.Set<?>) setObj) {
                                String es = String.valueOf(e);
                                if (es.contains("phosphor")
                                        || es.contains("jellysquid")
                                        || es.contains("me.jellysquid")) {
                                    System.out.println("[RustCraft-Light] "
                                            + fieldName + ": " + es);
                                }
                            }
                        } catch (NoSuchFieldException nsf) {
                            System.out.println("[RustCraft-Light] field "
                                    + fieldName + " absent");
                        }
                    }
                } catch (Throwable diag) {
                    System.out.println("[RustCraft-Light] diagnostics failed: "
                            + diag);
                }
                // §2 decisive experiment: force-load LightingEngine through
                // THIS classloader. If our transform() fires (transformCount
                // increments), the class was merely loaded later in live
                // runs; if transformCount stays 0, the class was already
                // DEFINED before tweakers ran (cached untransformed).
                try {
                    // RUNSCOPE-JUSTIFIED: the loader choice IS the diagnostic —
                    // cl is the LaunchClassLoader captured at injection time and
                    // the probe intentionally force-loads the mod class through
                    // it (the mod jar lives only in Launch space).
                    int before = com.rustcraft.coremod.PhosphorLightTransformer.transformCount;
                    Class<?> le = Class.forName(
                            "me.jellysquid.mods.phosphor.mod.world.lighting."
                                    + "LightingEngine", false, cl);
                    int after = com.rustcraft.coremod.PhosphorLightTransformer.transformCount;
                    System.out.println("[RustCraft-Light] forced load: before="
                            + before + " after=" + after
                            + " codeSource=" + le.getProtectionDomain()
                                    .getCodeSource());
                } catch (Throwable forceErr) {
                    System.out.println("[RustCraft-Light] forced load failed: "
                            + forceErr);
                }
                // §1: at shutdown, is LightingEngine defined AT ALL, and by
                // which loader? (runs in the shutdown hook registered below)
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        // RUNSCOPE-JUSTIFIED: probes through Launch.classLoader
                        // EXPLICITLY (the mod jar lives only in Launch space) —
                        // the loader choice is the point of this diagnostic.
                        Class<?> le = Class.forName(
                                "me.jellysquid.mods.phosphor.mod.world.lighting."
                                        + "LightingEngine", false,
                                net.minecraft.launchwrapper.Launch.classLoader);
                        System.out.println("[RustCraft-Light] LightingEngine"
                                + " DEFINED loader="
                                + le.getClassLoader().getClass().getName()
                                + " codeSource=" + le.getProtectionDomain()
                                        .getCodeSource());
                    } catch (Throwable t) {
                        System.out.println("[RustCraft-Light] LightingEngine"
                                + " NEVER DEFINED: " + t);
                    }
                }, "rustcraft-light-defined-check"));
            }
            // LIVE region READ authority (RUST_REGION_READ_DECOMPRESSION_
            // AUTHORITY, default OFF): rewrites only the RegionFile read
            // seam (and close, for handle lifecycle) and self-gates on
            // rustcraft.regionReadExperiment.
            if (Boolean.getBoolean("rustcraft.regionReadExperiment")) {
                cl.registerTransformer(
                        "com.rustcraft.coremod.RegionFileReadTransformer");
            }
            // NATIVECHUNK_WORLD_REGISTRY: the Chunk lifecycle/mutation
            // transformer (onLoad/onChunkUnload/onBlockSet/onLightSet hooks
            // feeding ChunkMutationTracker). It was previously listed ONLY
            // in RustCraftCoreMod.getASMTransformerClass — which never runs
            // in this tweaker launch shape — so no chunk hooks ever fired
            // (m4_registered_chunks=0 with the registry armed, no-client
            // proof run). Registered here when the world registry is armed;
            // the hooks self-gate (onChunkLoaded only feeds the registry,
            // which is ENABLED-gated).
            if (Boolean.getBoolean("rustcraft.worldRegistry")) {
                cl.registerTransformer(
                        "com.rustcraft.coremod.ChunkMutationTransformer");
                System.out.println("[RustCraft-WorldRegistry] lifecycle "
                        + "transformer registered; status will print at "
                        + "first Chunk load");
                // mutation→native state push: ChunkStateAuthorityTransformer
                // rewrites Chunk.setBlockState to write NativeChunk states
                // directly (same dead-coremod trap as ChunkMutationTransformer
                // — its only other registration never ran in this shape;
                // zero-stage reads registry states, so without it mutations
                // are invisible to Rust, committedCells=0 in dev4)
                if (Boolean.getBoolean(
                        "rustcraft.chunkStateAuthorityExperiment")) {
                    cl.registerTransformer(
                            "com.rustcraft.coremod.ChunkStateAuthority"
                                    + "Transformer");
                    System.out.println("[RustCraft-WorldRegistry] chunk-"
                            + "state push transformer registered");
                }
            }
            // M1-COMPOSE: the writers now register at the TOP of our own
            // block (see the tiered-topology comment there); nothing to
            // do here — this slot formerly held their registration.

            registerEvidenceFlushHook(sessionBound);
            // Cross-language fixture harvest (diagnostic only, real chunk).
            com.rustcraft.bridge.capture.RevelationFixtureHarvest.maybeSchedule();
            // Test-only campaign teleport controller (default OFF).
            com.rustcraft.bridge.capture.CampaignTeleportController.maybeSchedule();
            // The shadow consumer starts here in the tweaker launch shape
            // (the coremod shape starts it in injectData). It requires the
            // DLL property; without it this is an admission-only launch.
            if (System.getProperty("rustcraft.liveShadowDll") != null) {
                try {
                    com.rustcraft.bridge.capture.LiveShadowCampaignConsumer.start();
                } catch (Throwable consumerFailure) {
                    System.err.println("[live-capture] consumer failed to start: "
                            + consumerFailure);
                }
            }
            // Ensure PacketAuthorityExperiment class is initialized so its shutdown hook registers
            com.rustcraft.bridge.capture.PacketAuthorityExperiment.enabled();
            // Compression metrics at shutdown (the coremod dumper is not
            // registered in this launch shape).
            if (!"OFF".equalsIgnoreCase(System.getProperty("minecraftrust.native_compress", "OFF"))
                    || Boolean.getBoolean("rustcraft.rustCompressionExperiment")
                    || Boolean.getBoolean("rustcraft.rustCompressionShadow")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("[RustCraft-Compression] shutdown metrics:");
                    System.out.println(com.rustcraft.bridge.RustCompressionEngine.dumpMetrics());
                }, "rustcraft-compression-metrics"));
            }
            // LIVE region counters: periodic snapshot file for campaign
            // runners (event-driven completion — goal §15 of the tooling
            // refactor). Every 2s, key=value lines; runners poll the file
            // instead of regexing logs. Default OFF with the experiments.
            final String metricsFile =
                    System.getProperty("rustcraft.regionMetricsFile");
            if (metricsFile != null && metricsFile.length() > 0
                    && (Boolean.getBoolean("rustcraft.regionReadExperiment")
                        || Boolean.getBoolean("rustcraft.regionWriteExperiment")
                        || Boolean.getBoolean("rustcraft.lightExperiment"))) {
                Thread dumper = new Thread(() -> {
                    while (true) {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append(com.rustcraft.bridge.RustRegionReadHook.snapshotLines());
                            sb.append(com.rustcraft.bridge.RustRegionWriteHook.snapshotLines());
                            sb.append("worldLightCells=")
                                    .append(com.rustcraft.bridge.WorldLightHook.CELLS.get())
                                    .append("\nworldLightMis=")
                                    .append(com.rustcraft.bridge.WorldLightHook.MISMATCHES.get())
                                    .append("\nworldLightErr=")
                                    .append(com.rustcraft.bridge.WorldLightHook.ERRORS.get())
                                    .append("\nworldLightSettledLate=")
                                    .append(com.rustcraft.bridge.WorldLightHook.SETTLED_LATE.get())
                                    .append("\nphosphorJobs=")
                                    .append(com.rustcraft.bridge.PhosphorLightHook.JOBS.get())
                                    .append("\nphosphorCompared=")
                                    .append(com.rustcraft.bridge.PhosphorLightHook.COMPARED.get())
                                    .append("\nphosphorMis=")
                                    .append(com.rustcraft.bridge.PhosphorLightHook.MISMATCHES.get())
                                    .append("\nphosphorErr=")
                                    .append(com.rustcraft.bridge.PhosphorLightHook.ERRORS.get())
                                    .append("\nlightAuthJobs=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.JOBS.get())
                                    .append("\nlightAuthAdmitted=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.ADMITTED.get())
                                    .append("\nlightAuthFallbacks=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.FALLBACKS.get())
                                    .append("\nlightAuthCommitted=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.COMMITTED_CELLS.get())
                                    .append("\nlightAuthSky=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.SKY_CALLS.get())
                                    .append("\nlightAuthErr=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.ERRORS.get())
                                    .append("\nzsRepairs=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.ZS_REPAIRS.get())
                                    .append("\nmirrorStateSets=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_STATE_SETS.get())
                                    .append("\nmirrorStateErrors=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_STATE_ERRORS.get())
                                    .append("\nwidthRejects=")
                                    .append(com.rustcraft.bridge.M4Coherency.HIGH_ID_REJECTED.get())
                                    .append("\nmirrorSkipStateNull=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_SKIP_STATE_NULL.get())
                                    .append("\nmirrorSkipResultNull=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_SKIP_RESULT_NULL.get())
                                    .append("\nmirrorSidUnresolved=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_SID_UNRESOLVED.get())
                                    .append("\nmirrorNoopOrUnregistered=")
                                    .append(com.rustcraft.bridge.ChunkMutationTracker.MIRROR_NOOP_OR_UNREGISTERED.get())
                                    .append("\nworldRegistryRegisters=")
                                    .append(com.rustcraft.bridge.NativeChunkRegistryHook.REGISTERS.get())
                                    .append("\nworldRegistryUnregisters=")
                                    .append(com.rustcraft.bridge.NativeChunkRegistryHook.UNREGISTERS.get())
                                    .append("\nworldRegistryBootstrap=")
                                    .append(com.rustcraft.bridge.NativeChunkRegistryHook.BOOTSTRAP_REGISTERS.get())
                                    .append("\nworldRegistryMissing=")
                                    .append(com.rustcraft.bridge.NativeChunkRegistryHook.MISSING_LOOKUP.get())
                                    .append("\nzeroStageJobs=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.ZERO_STAGE_JOBS.get())
                                    .append("\nzeroStageStaleGen=")
                                    .append(com.rustcraft.bridge.LightAuthorityHook.STALE_GEN.get())
                                    .append("\nworldRegistryChunks=")
                                    .append(com.rustcraft.bridge.NativeChunkBridge.REGISTERED_CHUNKS.get()
                                            - com.rustcraft.bridge.NativeChunkBridge.UNLOADED_CHUNKS.get())
                                    .append("\n");
                            // Reflection-driven counter dump: EVERY static
                            // AtomicLong of the hook classes lands here
                            // automatically (field names, zero wiring). The
                            // legacy aliases above stay stable for existing
                            // runners; new counters appear the day they are
                            // written — no second source of truth to forget
                            // (zsa2's unwired skip counters cost 2 cycles).
                            appendAllAtomicCounters(sb,
                                    com.rustcraft.bridge.ChunkMutationTracker.class,
                                    com.rustcraft.bridge.LightAuthorityHook.class,
                                    com.rustcraft.bridge.NativeChunkRegistryHook.class,
                                    com.rustcraft.bridge.M4Coherency.class);
                            appendAuditTelemetry(sb);
                            java.nio.file.Path out = java.nio.file.Paths.get(metricsFile);
                            if (out.getParent() != null) {
                                java.nio.file.Files.createDirectories(out.getParent());
                            }
                            java.nio.file.Files.write(out,
                                    sb.toString().getBytes("UTF-8"));
                        } catch (Throwable ignore) { }
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                    }
                }, "rustcraft-region-metrics-dumper");
                dumper.setDaemon(true);
                dumper.start();
            }
            // Light metrics at shutdown.
            if (Boolean.getBoolean("rustcraft.lightExperiment")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("[RustCraft-Light] shutdown metrics:");
                    System.out.println(com.rustcraft.bridge.PhosphorLightHook.dumpMetrics());
                    System.out.println(com.rustcraft.bridge.WorldLightHook.dumpMetrics());
                    System.out.println("[RustCraft-Light] transformer status="
                            + com.rustcraft.coremod.PhosphorLightTransformer.transformCount
                            + " " + com.rustcraft.coremod.PhosphorLightTransformer.lastTransformStatus
                            + " phosphorClassesSeen="
                            + com.rustcraft.coremod.PhosphorLightTransformer.phosphorClassesSeen);
                    System.out.println("[RustCraft-Light] worldLightTransformer "
                            + com.rustcraft.coremod.WorldLightTransformer.dumpDiagnostics());
                    System.out.println("[RustCraft-WorldRegistry] chunkLifecycle "
                            + "transformCount=" + com.rustcraft.coremod.ChunkMutationTransformer.transformCount
                            + " lastStatus=" + com.rustcraft.coremod.ChunkMutationTransformer.lastStatus
                            + " hookLoads=" + com.rustcraft.bridge.ChunkMutationTracker.HOOK_CHUNK_LOADED.get()
                            + " " + com.rustcraft.bridge.NativeChunkRegistryHook.dumpMetrics());
                    System.out.println("[RustCraft-Light] "
                            + com.rustcraft.bridge.LightAuthorityHook.dumpMetrics());
                }, "rustcraft-light-metrics"));
                // Periodic change-driven metrics: shutdown hooks race process
                // exit; this guarantees the parity counters reach the log.
                final String compareFlag =
                        System.getProperty("rustcraft.lightCompareFlag");
                Thread periodic = new Thread(() -> {
                    long lastCells = -1, lastErrs = -1, lastMis = -1;
                    boolean windowDone = false;
                    // §20/§21 MSPT sampling state: [0]=FML handler,
                    // [1]=getMinecraftServerInstance Method, [2]=tickTimes Field
                    final Object[] msptState = new Object[3];
                    long msptSum = 0, msptMax = 0, msptCount = 0;
                    while (true) {
                        try { Thread.sleep(1000); }
                        catch (InterruptedException ie) { return; }
                        if (!windowDone && compareFlag != null) {
                            windowDone = true; // check once per second until open
                        } else if (windowDone) {
                            // window already opened; keep the light counters
                            // dumping on change below
                        }
                        if (compareFlag != null && !com.rustcraft.bridge
                                .PhosphorLightHook.COMPARE_WINDOW
                                && new java.io.File(compareFlag).isFile()) {
                            com.rustcraft.bridge.PhosphorLightHook
                                    .COMPARE_WINDOW = true;
                            try {
                                String t = new String(
                                        java.nio.file.Files.readAllBytes(
                                                java.nio.file.Paths.get(
                                                        compareFlag)),
                                        "UTF-8").trim();
                                String[] parts = t.split("[,; ]+");
                                if (parts.length == 3) {
                                    com.rustcraft.bridge.PhosphorLightHook
                                            .COMPARE_ANCHOR = new int[]{
                                            Integer.parseInt(parts[0].trim()),
                                            Integer.parseInt(parts[1].trim()),
                                            Integer.parseInt(parts[2].trim())};
                                }
                            } catch (Throwable ignore) {
                                // window opens without an anchor: near-anchor
                                // admission degrades to admit-all-in-window
                            }
                            System.out.println("[RustCraft-Light] compare "
                                    + "window OPEN anchor="
                                    + java.util.Arrays.toString(
                                            com.rustcraft.bridge.PhosphorLightHook
                                                    .COMPARE_ANCHOR));
                        }
                        long c = com.rustcraft.bridge.WorldLightHook.CELLS.get();
                        long e = com.rustcraft.bridge.WorldLightHook.ERRORS.get();
                        long m = com.rustcraft.bridge.WorldLightHook.MISMATCHES.get();
                        try {
                            if (msptState[0] == null) {
                                Class<?> fml = Class.forName(
                                        "net.minecraftforge.fml.common.FMLCommonHandler");
                                msptState[0] = fml.getMethod("instance").invoke(null);
                                msptState[1] = fml.getMethod(
                                        "getMinecraftServerInstance");
                            }
                            Object server = msptState[0] == null ? null
                                    : ((java.lang.reflect.Method) msptState[1])
                                            .invoke(msptState[0]);
                            if (server != null && msptState[2] == null) {
                                // tickTimes ring = field_71311_j at runtime
                                // (SRG; the working RustCraftCoreMod sampler
                                // attaches via exactly this name — the old
                                // generic long[] scan never found it)
                                for (Class<?> sc = server.getClass();
                                        sc != null && msptState[2] == null;
                                        sc = sc.getSuperclass()) {
                                    try {
                                        java.lang.reflect.Field f =
                                                sc.getDeclaredField("field_71311_j");
                                        f.setAccessible(true);
                                        msptState[2] = f;
                                    } catch (NoSuchFieldException ignore) {
                                        // walk up
                                    }
                                }
                                if (msptState[2] == null) {
                                    System.out.println("[RustCraft-Light] MSPT"
                                            + " sampler: field_71311_j not found");
                                }
                            }
                            if (server != null && msptState[2] != null) {
                                long[] arr = (long[]) ((java.lang.reflect.Field)
                                        msptState[2]).get(server);
                                if (arr != null) {
                                    for (long ns : arr) {
                                        if (ns <= 0) continue;
                                        msptSum += ns;
                                        msptCount++;
                                        if (ns > msptMax) msptMax = ns;
                                        AUDIT_MSPT_NS.add(ns); // audit reservoir
                                    }
                                }
                            }
                        } catch (Throwable ignore) {
                            // MSPT is best-effort telemetry; never fatal
                        }
                        if (c != lastCells || e != lastErrs || m != lastMis) {
                            lastCells = c; lastErrs = e; lastMis = m;
                            System.out.println("[RustCraft-Light] periodic "
                                    + com.rustcraft.bridge.WorldLightHook.dumpMetrics()
                                    + " transformer=" + com.rustcraft.coremod
                                            .WorldLightTransformer.lastTransformStatus);
                            System.out.println("[RustCraft-Light] periodic "
                                    + com.rustcraft.bridge.PhosphorLightHook.dumpMetrics());
                            System.out.println("[RustCraft-Light] periodic "
                                    + com.rustcraft.bridge.LightAuthorityHook.dumpMetrics());
                            if (msptCount > 0) {
                                double meanMs = (msptSum / msptCount) / 1_000_000.0;
                                double maxMs = msptMax / 1_000_000.0;
                                System.out.println("[RustCraft-Light] periodic MSPT"
                                        + " mean=" + String.format("%.2f", meanMs)
                                        + "ms max=" + String.format("%.2f", maxMs)
                                        + "ms ticks=" + msptCount);
                                msptSum = 0;
                                msptMax = 0;
                                msptCount = 0;
                            }
                        } else {
                            // counters frozen: still flush MSPT if sampled
                            if (msptCount > 0) {
                                double meanMs = (msptSum / msptCount) / 1_000_000.0;
                                double maxMs = msptMax / 1_000_000.0;
                                System.out.println("[RustCraft-Light] periodic MSPT"
                                        + " mean=" + String.format("%.2f", meanMs)
                                        + "ms max=" + String.format("%.2f", maxMs)
                                        + "ms ticks=" + msptCount);
                                msptSum = 0;
                                msptMax = 0;
                                msptCount = 0;
                            }
                        }
                    }
                }, "rustcraft-light-periodic");
                periodic.setDaemon(true);
                periodic.start();
            }
            // Region-read metrics at shutdown.
            if (Boolean.getBoolean("rustcraft.regionReadExperiment")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("[RustCraft-RegionRead] shutdown metrics:");
                    System.out.println(com.rustcraft.bridge.RustRegionReadHook.dumpMetrics());
                    System.out.println("[RustCraft-RegionRead] transformer status="
                            + com.rustcraft.coremod.RegionFileReadTransformer.transformCount
                            + " " + com.rustcraft.coremod.RegionFileReadTransformer.lastTransformStatus);
                }, "rustcraft-region-read-metrics"));
            }
            // Region-write metrics at shutdown (RUST_REGION_WRITE_AUTHORITY
            // campaign; default OFF with the experiment property).
            if (Boolean.getBoolean("rustcraft.regionWriteExperiment")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("[RustCraft-RegionWrite] shutdown metrics:");
                    System.out.println(com.rustcraft.bridge.RustRegionWriteHook.dumpMetrics());
                    System.out.println(com.rustcraft.bridge.RustRegionWriteHook.dumpProvenance());
                    System.out.println("[RustCraft-RegionWrite] transformer status="
                            + com.rustcraft.coremod.RegionFileAuthorityTransformer.transformCount
                            + " " + com.rustcraft.coremod.RegionFileAuthorityTransformer.lastTransformStatus);
                }, "rustcraft-region-write-metrics"));
            }
        } catch (Throwable failure) {
            System.err.println("[RustCraft] live session admission tweaker failed: " + failure);
        }
    }

    private static void registerEvidenceFlushHook(final boolean sessionBound) {
        String resultProperty = System.getProperty("rustcraft.qualificationResult");
        if (resultProperty == null || resultProperty.length() == 0) return;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                java.util.Map<String, Object> facts =
                        new java.util.LinkedHashMap<String, Object>();
                facts.put("schema_version", Integer.valueOf(1));
                facts.put("profile", LiveWriterPlan.QUALIFICATION_PROFILE);
                facts.put("capture_kind", "REAL_FML_TRANSFORM_CAPTURE");
                facts.put("scope", "REAL_FML_SERVER_SESSION_ADMISSION_FULL_SERVER_LIFECYCLE");
                facts.put("launch_shape", "REAL_FML_SERVER");
                facts.put("java_version", System.getProperty("java.version"));
                facts.put("java_runtime_version", System.getProperty("java.runtime.version"));
                facts.put("server_main_called", Boolean.TRUE);
                facts.put("mod_lifecycle_executed", Boolean.TRUE);
                facts.put("session_environment_bound", Boolean.valueOf(sessionBound));
                facts.put("transformers",
                        com.rustcraft.qualification.SessionEvidenceFlush.transformers());
                try {
                    facts.put("registered_coremod_plugins",
                            com.rustcraft.qualification.SessionEvidenceFlush.coremodPlugins());
                } catch (Throwable failure) {
                    facts.put("coremod_inventory_error", String.valueOf(failure));
                }
                String[] forgeFacts = {"major", "minor", "rev", "build", "mccversion", "mcpversion"};
                for (String fact : forgeFacts) facts.put("forge_" + fact,
                        com.rustcraft.qualification.SessionEvidenceFlush.safeStatic(
                                "net.minecraftforge.fml.relauncher.FMLInjectionData", fact));
                facts.put("transformed_classes",
                        com.rustcraft.qualification.SessionEvidenceFlush.agentHashes());
                facts.put("transformed_class_loaders",
                        com.rustcraft.qualification.SessionEvidenceFlush.agentLoaders());
                facts.put("production_authority", Boolean.FALSE);
                com.rustcraft.qualification.SessionEvidenceFlush.emit(facts);
            }
        }, "rustcraft-live-session-evidence-flush"));
    }

    @Override
    public String getLaunchTarget() {
        return delegate.getLaunchTarget();
    }

    @Override
    public String[] getLaunchArguments() {
        return delegate.getLaunchArguments();
    }

    /** Metrics dump v2: every static AtomicLong field of the given hook
     *  classes as FIELD_NAME=value lines. New counters flow into
     *  region-metrics.txt with zero wiring; the liveness gate reads these
     *  field names. */
    private static void appendAllAtomicCounters(StringBuilder sb,
            Class<?>... classes) {
        for (Class<?> c : classes) {
            try {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != java.util.concurrent.atomic.AtomicLong.class
                            || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) {
                        sb.append(f.getName()).append('=')
                                .append(((java.util.concurrent.atomic.AtomicLong) v).get())
                                .append((char) 10);
                    }
                }
            } catch (Throwable ignore) {
                // one class failing must not kill the whole dump
            }
        }
    }

    // ==== Audit telemetry (measurement-only; RUST_ENGINE_OWNERSHIP_
    // PERFORMANCE_AUDIT §6): per-tick MSPT reservoir + MXBean GC/heap/CPU.
    // The metrics dumper renders these every pass; percentiles are
    // computed over the whole run so far.
    static final java.util.List<Long> AUDIT_MSPT_NS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static volatile long AUDIT_GC0_COUNT = -1, AUDIT_GC0_MS;
    private static volatile double AUDIT_CPU_MAX;
    private static volatile long AUDIT_CPU_N; private static volatile double AUDIT_CPU_SUM;

    private static void appendAuditTelemetry(StringBuilder sb) {
        try {
            java.util.List<Long> copy;
            synchronized (AUDIT_MSPT_NS) { copy = new java.util.ArrayList<>(AUDIT_MSPT_NS); }
            if (!copy.isEmpty()) {
                java.util.Collections.sort(copy);
                int n = copy.size();
                long sum = 0; int over50 = 0, over100 = 0;
                for (long v : copy) { sum += v; if (v > 50_000_000L) over50++; if (v > 100_000_000L) over100++; }
                sb.append("msptTicks=").append(n)
                  .append("\nmsptMeanMs=").append(String.format("%.3f", sum / 1e6 / n))
                  .append("\nmsptP50Ms=").append(String.format("%.3f", copy.get(n / 2) / 1e6))
                  .append("\nmsptP95Ms=").append(String.format("%.3f", copy.get((int) (n * 0.95)) / 1e6))
                  .append("\nmsptP99Ms=").append(String.format("%.3f", copy.get((int) (n * 0.99)) / 1e6))
                  .append("\nmsptMaxMs=").append(String.format("%.3f", copy.get(n - 1) / 1e6))
                  .append("\nmsptOver50=").append(over50)
                  .append("\nmsptOver100=").append(over100)
                  .append("\n");
            }
            java.util.List<java.lang.management.GarbageCollectorMXBean> gcs =
                    java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
            long gcCount = 0, gcMs = 0;
            for (java.lang.management.GarbageCollectorMXBean g : gcs) {
                gcCount += Math.max(0, g.getCollectionCount());
                gcMs += Math.max(0, g.getCollectionTime());
            }
            if (AUDIT_GC0_COUNT < 0) { AUDIT_GC0_COUNT = gcCount; AUDIT_GC0_MS = gcMs; }
            sb.append("gcCount=").append(gcCount - AUDIT_GC0_COUNT)
              .append("\ngcTimeMs=").append(gcMs - AUDIT_GC0_MS).append("\n");
            java.lang.management.MemoryMXBean mem =
                    java.lang.management.ManagementFactory.getMemoryMXBean();
            sb.append("heapUsedMb=").append(mem.getHeapMemoryUsage().getUsed() >> 20)
              .append("\nheapMaxMb=").append(mem.getHeapMemoryUsage().getMax() >> 20).append("\n");
            try {
                java.lang.management.OperatingSystemMXBean os =
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                java.lang.reflect.Method m = os.getClass().getMethod("getProcessCpuLoad");
                m.setAccessible(true);
                double load = (Double) m.invoke(os);
                if (load >= 0) {
                    AUDIT_CPU_SUM += load; AUDIT_CPU_N++;
                    if (load > AUDIT_CPU_MAX) AUDIT_CPU_MAX = load;
                }
            } catch (Throwable ignore) { /* com.sun.management absent */ }
            if (AUDIT_CPU_N > 0) {
                sb.append("procCpuAvg=").append(String.format("%.3f", AUDIT_CPU_SUM / AUDIT_CPU_N))
                  .append("\nprocCpuMax=").append(String.format("%.3f", AUDIT_CPU_MAX)).append("\n");
            }
        } catch (Throwable ignore) {
            // telemetry must never be fatal
        }
    }
}
