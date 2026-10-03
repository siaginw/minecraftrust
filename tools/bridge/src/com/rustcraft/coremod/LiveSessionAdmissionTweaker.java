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
            // The observer goes in at the FRONT so the chain's first edge is
            // the true entry buffer of this launch.
            System.out.println("[RustCraft] live session admission tweaker: entry observer "
                    + com.rustcraft.qualification.LoaderTransformChain.installAtFront(cl));
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
            // The writers register now; their first invocation places them at
            // the chain tail after every foreign transformer has registered.
            for (String name : new String[] {
                    "com.rustcraft.coremod.SPacketChunkDataTransformer",
                    "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
                    "com.rustcraft.coremod.LiveChunkPublicationTransformer"}) {
                cl.registerTransformer(name);
            }
            // Single-copy outbound boundary (self-gating: with the boundary
            // disabled the transformers return every class unmodified).
            // Registered INSIDE the writer block and counted in the ordering
            // guard's writer set, so the tail topology is stable from birth
            // and the qualified writers see byte-identical inputs.
            if (com.rustcraft.bridge.SingleCopyPipeline.enabled()) {
                cl.registerTransformer("com.rustcraft.coremod.NetworkManagerSingleCopyTransformer");
                cl.registerTransformer("com.rustcraft.coremod.NettyPacketEncoderCounterTransformer");
            }

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
            // Region-write metrics at shutdown (RUST_REGION_WRITE_AUTHORITY
            // campaign; default OFF with the experiment property).
            if (Boolean.getBoolean("rustcraft.regionWriteExperiment")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("[RustCraft-RegionWrite] shutdown metrics:");
                    System.out.println(com.rustcraft.bridge.RustRegionWriteHook.dumpMetrics());
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
}
