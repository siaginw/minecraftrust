package com.rustcraft.bridge.capture;

/** Historical diagnostic policies, never V2 qualification or native authority. */
public final class LegacyCaptureScopes {
    public static final String CLEAN_PROFILE = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1";
    private LegacyCaptureScopes() { }

    public static LiveCaptureScope selectedLegacyPlan(ClassLoader bridgeLoader) {
        try {
            Class<?> plan = Class.forName("com.rustcraft.coremod.LiveWriterPlan", false, bridgeLoader);
            java.lang.reflect.Field id = plan.getDeclaredField("QUALIFICATION_PROFILE");
            if (id.getType() != String.class || !java.lang.reflect.Modifier.isStatic(id.getModifiers())
                    || !java.lang.reflect.Modifier.isFinal(id.getModifiers())) return null;
            return cleanSurfaceFlat((String) id.get(null));
        } catch (Exception unavailable) { return null; }
    }

    /** Accepted Clean campaign uses dimension-zero FLAT; unknown plans get no scope. */
    public static LiveCaptureScope cleanSurfaceFlat(String selectedPlanProfile) {
        if (!CLEAN_PROFILE.equals(selectedPlanProfile)) return null;
        return policy(selectedPlanProfile, "net.minecraft.world.WorldServer",
                "net.minecraft.world.WorldProviderSurface", "MINECRAFT_1_12_FLAT",
                "net.minecraft.world.gen.ChunkGeneratorFlat", true, false);
    }

    /** Exact detached test classes; not inherited into the live Clean policy. */
    public static LiveCaptureScope detachedTransformerDiagnostic(String worldClass, String providerClass) {
        return policy("DETACHED_TRANSFORMER_DIAGNOSTIC_V1", worldClass, providerClass,
                "ABSENT_DETACHED_DIAGNOSTIC", null, false, true);
    }

    private static LiveCaptureScope policy(String id, String world, String provider, String generatorFamily,
            String generator, boolean sky, boolean detached) {
        return new LiveCaptureScope(id, "HISTORICAL_DIAGNOSTIC_NOT_V2_NOT_AUTHORIZING", 0,
                provider, world, "net.minecraft.world.chunk.Chunk",
                "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
                "net.minecraft.world.chunk.BlockStateContainer", "net.minecraft.world.chunk.NibbleArray",
                "net.minecraft.network.play.server.SPacketChunkData",
                "net.minecraftforge.registries.GameData$BlockCallbacks$1",
                LiveCaptureScope.VANILLA_U16, 1L, 13, generatorFamily, generator, sky, detached);
    }
}
