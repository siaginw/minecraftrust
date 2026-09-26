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
                "com.rustcraft.coremod.LiveChunkPublicationTransformer"
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
        // The live diagnostic is enabled for this campaign: start the in-server
        // consumer (drain → Rust replay → compare → record; SHADOW only).
        if (Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) {
            try {
                com.rustcraft.bridge.capture.LiveShadowCampaignConsumer.start();
                consumerStarted = true;
            } catch (Throwable failure) {
                System.err.println("[live-capture] consumer failed to start: " + failure);
            }
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
