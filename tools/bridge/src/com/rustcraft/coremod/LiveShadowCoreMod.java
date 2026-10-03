package com.rustcraft.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Issue #1 live-SHADOW campaign coremod (bounded diagnostic; default OFF).
 *
 * <p>Registers ONLY the three qualified Issue #1 transformers — deliberately
 * isolated from every legacy experimental transformer (M1/M4/MCK/probes), which
 * stay unregistered in this campaign. All transformer behavior remains gated by
 * -Drustcraft.liveWriterDiagnostic (default OFF: zero bytecode modification).
 * The coremod also starts the in-server diagnostic consumer
 * ({@link com.rustcraft.bridge.capture.LiveShadowCampaignConsumer}) when the
 * diagnostic is enabled.</p>
 *
 * <p>SHADOW ONLY: Java packets remain the only packets transmitted; the Rust
 * result is recorded and discarded; production authority stays fail-closed.</p>
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.rustcraft."})
@IFMLLoadingPlugin.SortingIndex(1005)
public class LiveShadowCoreMod implements IFMLLoadingPlugin {

    public static volatile boolean consumerStarted;

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{
                "com.rustcraft.coremod.SPacketChunkDataTransformer",
                "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
                "com.rustcraft.coremod.LiveChunkPublicationTransformer",
                // Single-copy outbound boundary (default OFF; no bytecode
                // modification unless -Drustcraft.singleCopy[Shadow] is set):
                "com.rustcraft.coremod.NetworkManagerSingleCopyTransformer",
                "com.rustcraft.coremod.NettyPacketEncoderCounterTransformer",
                // LIVE region write authority (default OFF; no bytecode
                // modification unless -Drustcraft.regionWriteExperiment=true):
                "com.rustcraft.coremod.RegionFileAuthorityTransformer"
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Fail-closed profile selection: the generated plan embeds the Forge
        // build it was qualified against. A runtime built by a different Forge
        // must never be instrumented with this plan, even before any per-class
        // pre-hook hash check can run.
        try {
            Object build = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData")
                    .getDeclaredField("build").get(null);
            String runtimeBuild = String.valueOf(build);
            String planBuild = LiveWriterPlan.FORGE_BUILD;
            // FMLInjectionData.build is the bare build number ("2860"); the plan
            // carries the full version ("14.23.5.2860"). The build number is the
            // discriminator (2846 vs 2860), so require an exact suffix match.
            if (runtimeBuild.length() < 4 || !planBuild.endsWith(runtimeBuild)) {
                throw new IllegalStateException(
                        "profile/runtime mismatch: plan qualified for Forge " + planBuild
                                + " but runtime reports build " + runtimeBuild);
            }
        } catch (IllegalStateException mismatch) {
            throw mismatch; // fail closed: refuse to instrument the wrong runtime
        } catch (Throwable unavailable) {
            // FMLInjectionData not reachable in this launch shape (offline
            // harness variants); per-class pre-hook identity still binds the
            // transformers to the exact qualified bytes.
        }
        // The live diagnostic is enabled for this campaign: bootstrap the live
        // session admission (shadow OFF when no DLL is configured), then start
        // the in-server consumer (drain → Rust replay → compare → record;
        // SHADOW only).
        if (Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) {
            bootstrapLiveSessionAdmission(data);
            try {
                com.rustcraft.bridge.capture.LiveShadowCampaignConsumer.start();
                consumerStarted = true;
            } catch (Throwable failure) {
                System.err.println("[live-capture] consumer failed to start: " + failure);
            }
        }
    }

    /**
     * Live session admission bootstrap: the real-launch counterpart of what
     * the offline oracle does before its writers register. Three real
     * observations, no fabrication:
     *
     * <ol>
     *   <li>bind the session environment from THIS launch's own properties --
     *       the same SystemPropertySessionEnvironment the offline oracle
     *       binds, so the writers can issue fresh process-bound certificates
     *       for THIS JVM;</li>
     *   <li>install the entry observer at the FRONT of the loader's
     *       transformer chain, so the chain's first edge is the true entry
     *       buffer of this launch;</li>
     *   <li>when rustcraft.qualificationResult is set, register a shutdown
     *       hook that flushes THIS process's own records -- the acquisition,
     *       chain, frame/final witness -- through the SAME shared producer
     *       the offline oracle uses.</li>
     * </ol>
     *
     * <p>Nothing here stamps provenance, mutates class bytes, or decides
     * admission: the qualification engine remains the decision point, fed
     * evidence this JVM observed about itself.</p>
     */
    private static void bootstrapLiveSessionAdmission(Map<String, Object> data) {
        boolean sessionBound = false;
        try {
            String processId = System.getProperty(
                    LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY);
            String sessionId = System.getProperty(
                    LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY);
            sessionBound = processId != null && processId.length() > 0
                    && sessionId != null && sessionId.length() > 0;
            if (sessionBound) {
                LiveHookSupport.bindSessionEnvironment(
                        new LiveHookSupport.SystemPropertySessionEnvironment());
            }
            System.out.println("[RustCraft] live session admission: environment bound="
                    + sessionBound);
            Object loader = data.get("classLoader");
            if (loader != null) {
                String installed = com.rustcraft.qualification.LoaderTransformChain
                        .installAtFront(loader);
                System.out.println("[RustCraft] live session admission: entry observer "
                        + installed);
            }
            final boolean bound = sessionBound;
            String resultProperty = System.getProperty("rustcraft.qualificationResult");
            if (resultProperty != null && resultProperty.length() > 0) {
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override public void run() {
                        java.util.Map<String, Object> facts =
                                new java.util.LinkedHashMap<String, Object>();
                        facts.put("schema_version", Integer.valueOf(1));
                        facts.put("profile", LiveWriterPlan.QUALIFICATION_PROFILE);
                        facts.put("capture_kind", "REAL_FML_TRANSFORM_CAPTURE");
                        facts.put("scope",
                                "REAL_FML_SERVER_SESSION_ADMISSION_FULL_SERVER_LIFECYCLE");
                        facts.put("launch_shape", "REAL_FML_SERVER");
                        facts.put("java_version", System.getProperty("java.version"));
                        facts.put("java_runtime_version",
                                System.getProperty("java.runtime.version"));
                        facts.put("server_main_called", Boolean.TRUE);
                        facts.put("mod_lifecycle_executed", Boolean.TRUE);
                        facts.put("session_environment_bound", Boolean.valueOf(bound));
                        facts.put("transformers",
                                com.rustcraft.qualification.SessionEvidenceFlush.transformers());
                        try {
                            facts.put("registered_coremod_plugins",
                                    com.rustcraft.qualification.SessionEvidenceFlush
                                            .coremodPlugins());
                        } catch (Throwable failure) {
                            facts.put("coremod_inventory_error", String.valueOf(failure));
                        }
                        facts.put("forge_major", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "major"));
                        facts.put("forge_minor", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "minor"));
                        facts.put("forge_rev", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "rev"));
                        facts.put("forge_build", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "build"));
                        facts.put("forge_mccversion", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "mccversion"));
                        facts.put("forge_mcpversion", com.rustcraft.qualification.SessionEvidenceFlush
                                .safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "mcpversion"));
                        facts.put("transformed_classes",
                                com.rustcraft.qualification.SessionEvidenceFlush.agentHashes());
                        facts.put("transformed_class_loaders",
                                com.rustcraft.qualification.SessionEvidenceFlush.agentLoaders());
                        facts.put("production_authority", Boolean.FALSE);
                        com.rustcraft.qualification.SessionEvidenceFlush.emit(facts);
                    }
                }, "rustcraft-live-session-evidence-flush"));
            }
        } catch (Throwable failure) {
            System.err.println("[RustCraft] live session admission bootstrap failed: "
                    + failure);
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
