package com.rustcraft.bridge.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Phase-D live-scope policy: the Overworld per-chunk scope both live smokes
 * run under (join-probe worlds generate Overworld terrain, unlike the
 * historical flat campaign).
 *
 * <p>Activation is fail-closed and property-gated:
 * {@code -Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK} plus an installed
 * writer plan whose qualification profile is one of the two Phase-D admitted
 * profiles (Clean Forge 2860, FTB Revelation 3.4.0 on Forge 2846). With the
 * property absent the historical Clean-flat policy (or none) is selected and
 * behavior is byte-for-byte unchanged.</p>
 *
 * <p>The registry width is DERIVED from the live registry at session
 * establishment using the same formula {@link LiveCaptureScope.RuntimeBinding}
 * enforces (max(9, ceil-log2(size))), so the binding cannot disagree with the
 * scope. Under the per-chunk policy the registry may carry ids above the u16
 * transport (Revelation: max id 77,663); each CHUNK is then gated per state by
 * {@link ShadowScopeGate}, and the registry itself is bound for identity
 * only. No transport widening occurs.</p>
 */
public final class PhaseDScopePolicy {

    public static final String PROPERTY = "rustcraft.liveShadowScope";
    public static final String OVERWORLD_PER_CHUNK = "OVERWORLD_PER_CHUNK";
    public static final String GENERATOR_FAMILY = "MINECRAFT_1_12_OVERWORLD";

    private static final String CLEAN_PROFILE =
            "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1";
    private static final String REVELATION_PROFILE =
            "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1";

    private PhaseDScopePolicy() { }

    public static boolean requested() {
        return OVERWORLD_PER_CHUNK.equals(System.getProperty(PROPERTY));
    }

    /** The installed plan's qualification profile, or null when unreadable. */
    public static String installedPlanProfile(ClassLoader bridgeLoader) {
        try {
            Class<?> plan = Class.forName("com.rustcraft.coremod.LiveWriterPlan", false, bridgeLoader);
            Field id = plan.getDeclaredField("QUALIFICATION_PROFILE");
            if (id.getType() != String.class || !Modifier.isStatic(id.getModifiers())
                    || !Modifier.isFinal(id.getModifiers())) return null;
            id.setAccessible(true);
            return (String) id.get(null);
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** True when the installed plan's profile is one Phase D admits. */
    public static boolean admittedPlanProfile(String profile) {
        return CLEAN_PROFILE.equals(profile) || REVELATION_PROFILE.equals(profile);
    }

    /**
     * Builds the Overworld per-chunk scope for this runtime, or null when the
     * registry cannot be read or its width exceeds the u16 transport cap (the
     * runtime then keeps the historical behavior: no live scope).
     */
    public static LiveCaptureScope overworldPerChunk(ClassLoader runtimeLoader, String planProfile) {
        RegistryFacts facts = registryFacts(runtimeLoader, System.err);
        // Width up to 20 = the scope binding's own per-chunk registry cap.
        // The width is TELEMETRY: a chunk is admitted by its ACTUAL state ids.
        if (facts == null || facts.widthBits > 20) return null;
        return new LiveCaptureScope(
                planProfile + "/PHASE_D_OVERWORLD_PER_CHUNK",
                "PHASE_D_LIVE_SHADOW_SMOKE_NOT_V2_NOT_AUTHORIZING",
                0,
                "net.minecraft.world.WorldProviderSurface",
                "net.minecraft.world.WorldServer",
                "net.minecraft.world.chunk.Chunk",
                "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
                "net.minecraft.world.chunk.BlockStateContainer",
                "net.minecraft.world.chunk.NibbleArray",
                "net.minecraft.network.play.server.SPacketChunkData",
                "net.minecraftforge.registries.GameData$BlockCallbacks$1",
                LiveCaptureScope.VANILLA_U16, 1L, facts.widthBits, facts.size,
                GENERATOR_FAMILY, "net.minecraft.world.gen.ChunkGeneratorOverworld",
                true, false, true);
    }

    /** Immutable registry facts captured once at session establishment. */
    public static final class RegistryFacts {
        public final int size;
        public final int widthBits;
        public final String digestSha256;
        RegistryFacts(int size, int widthBits, String digestSha256) {
            this.size = size; this.widthBits = widthBits; this.digestSha256 = digestSha256;
        }
    }

    /**
     * Reads the block-state registry (the same ObjectIntIdentityMap the scope
     * binding pins) reflectively: size, derived width, and a content digest
     * over the dense id ordering (index, state toString). One call, at
     * session establishment only.
     */
    public static RegistryFacts registryFacts(ClassLoader runtimeLoader) {
        return registryFacts(runtimeLoader, null);
    }

    /** Same facts, with the failure reason reported to the given log sink. */
    public static RegistryFacts registryFacts(ClassLoader runtimeLoader,
                                              java.io.PrintStream diagnostics) {
        try {
            Class<?> block = Class.forName("net.minecraft.block.Block", false, runtimeLoader);
            // The registry the SCOPE BINDING will read is Block.field_176229_d
            // by NAME (LiveCaptureScope.currentRegistry); the facts MUST come
            // from the very same field or the derived width disagrees with the
            // binding's own computation (measured: STATE_WIDTH_MISMATCH when
            // a by-type scan happened to pick a different ObjectIntIdentityMap
            // static). The name lookup is validated by reading the backing
            // map through the ObjectIntIdentityMap class -- a wrong-typed
            // value cannot pass it.
            Class<?> mapClassProbe = Class.forName("net.minecraft.util.ObjectIntIdentityMap",
                    false, block.getClassLoader());
            Object registry = null;
            try {
                Field named = block.getDeclaredField("field_176229_d");
                named.setAccessible(true);
                Object value = named.get(null);
                if (value != null && mapClassProbe.isInstance(value)) registry = value;
            } catch (Throwable nameLookupFailed) {
                // fall through to the type-matched scan
            }
            if (registry == null) {
                for (Field candidate : block.getDeclaredFields()) {
                    if (!Modifier.isStatic(candidate.getModifiers())) continue;
                    if (!candidate.getType().getName().equals("net.minecraft.util.ObjectIntIdentityMap"))
                        continue;
                    candidate.setAccessible(true);
                    Object value = candidate.get(null);
                    if (value != null && mapClassProbe.isInstance(value)) { registry = value; break; }
                }
            }
            if (registry == null) {
                if (diagnostics != null) diagnostics.println(
                        "[RustCraft] registry facts: no static ObjectIntIdentityMap field on Block");
                return null;
            }
            if (diagnostics != null) diagnostics.println(
                    "[RustCraft] registry facts: registry class = "
                            + registry.getClass().getName());
            Class<?> mapClass = Class.forName("net.minecraft.util.ObjectIntIdentityMap",
                    false, registry.getClass().getClassLoader());
            Field mapField = null;
            Field listField = null;
            for (Field candidate : mapClass.getDeclaredFields()) {
                candidate.setAccessible(true);
                Class<?> type = candidate.getType();
                if (type.getName().equals("java.util.IdentityHashMap") && mapField == null)
                    mapField = candidate;
                // The list's DECLARED type is java.util.List on this runtime
                // (measured); matching ArrayList alone silently missed it.
                if ((type.getName().equals("java.util.List")
                        || type.getName().equals("java.util.ArrayList")) && listField == null)
                    listField = candidate;
            }
            if (mapField == null || listField == null) {
                if (diagnostics != null) {
                    diagnostics.println(
                            "[RustCraft] registry facts: backing map/list fields not found by type;"
                            + " registry object class=" + registry.getClass().getName()
                            + " loader=" + registry.getClass().getClassLoader());
                    for (Field candidate : mapClass.getDeclaredFields())
                        diagnostics.println("[RustCraft] registry facts: field "
                                + candidate.getName() + " : " + candidate.getType().getName());
                    for (Field candidate : block.getDeclaredFields())
                        if (Modifier.isStatic(candidate.getModifiers()))
                            diagnostics.println("[RustCraft] registry facts: Block static "
                                    + candidate.getName() + " : " + candidate.getType().getName());
                }
                return null;
            }
            Map<?, ?> map = (Map<?, ?>) mapField.get(registry);
            ArrayList<?> list = (ArrayList<?>) listField.get(registry);
            if (diagnostics != null) diagnostics.println(
                    "[RustCraft] registry facts: map.size=" + map.size()
                    + " list.size=" + list.size() + " at=" + System.currentTimeMillis());
            if (map.getClass() != IdentityHashMap.class || list.getClass() != ArrayList.class) {
                if (diagnostics != null) diagnostics.println(
                        "[RustCraft] registry facts: unexpected backing "
                                + map.getClass().getName());
                return null;
            }
            int size = map.size();
            int bits = Math.max(9, 32 - Integer.numberOfLeadingZeros(size - 1));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.valueOf(size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < list.size(); i++) {
                md.update((byte) '\u0001');
                md.update(String.valueOf(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) '\u0001');
                Object state = list.get(i);
                md.update(String.valueOf(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return new RegistryFacts(size, bits, hex.toString());
        } catch (Exception unreadable) {
            if (diagnostics != null) diagnostics.println(
                    "[RustCraft] registry facts unreadable: " + unreadable);
            return null;
        }
    }
}
