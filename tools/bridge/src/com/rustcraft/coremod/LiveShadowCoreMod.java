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
