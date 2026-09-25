package com.rustcraft.bridge.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Pinned concrete-class extractor for the qualified live-SHADOW profile: reads one
 * transformed {@code net.minecraft.world.chunk.Chunk} and one SPacketChunkData
 * through fixed SRG names and exact runtime classes (verified against the
 * committed live-shadow profile) using reflection only — no virtual mod
 * callbacks, no width narrowing, no writer normalization, no source repair.
 *
 * <p>All reads happen while the caller holds the writer-protocol gate, so the
 * extracted arrays are stable. Every array is cloned before the read view
 * escapes; nothing live is retained. The world provider's concrete class must
 * be the qualified {@code WorldProviderSurface} (dimension 0); anything else is
 * an unsupported world and refuses capture.</p>
 */
public final class LiveForgeCaptureSource implements LivePacketCapture.LiveCaptureSource {

    private final Object chunk;
    private final Object world;
    private final LiveChunkBindings.Binding binding;
    private final LiveWriterGate gate;
    private final int filter;
    private final boolean skylight;
    private final int globalPaletteBits;
    private final String provenance;

    private LiveForgeCaptureSource(Object chunk, Object world, LiveChunkBindings.Binding binding,
                                   LiveWriterGate gate, int filter) {
        this.chunk = chunk;
        this.world = world;
        this.binding = binding;
        this.gate = gate;
        this.filter = filter;
        try {
            this.skylight = worldHasSkylight(world);
            this.globalPaletteBits = globalPaletteBits();
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new IllegalStateException(failure);
        }
        this.provenance = "live-shadow-owned-v1\u0001session=" + binding.sessionId()
                + "\u0001worldId=" + binding.worldId() + "\u0001chunkId=" + binding.chunkId()
                + "\u0001incarnation=" + binding.incarnation()
                + "\u0001ownedEncodeGeneration=" + binding.ownedEncodeGeneration()
                + "\u0001profile=FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1";
    }

    /** The pinned extractor for one transformed chunk event, or null when unresolvable. */
    public static LiveForgeCaptureSource forChunk(Object chunk, LiveChunkBindings.Binding binding,
                                                  LiveWriterGate gate, int filter) {
        try {
            Object world = field(chunk, "field_76637_e");
            if (world == null) return null;
            // The qualified profile is the surface provider (dimension 0). Subclasses
            // are accepted by superclass-chain name so detached diagnostic worlds and
            // the exact runtime class both admit; anything else is unsupported.
            Object provider = field(world, "field_73011_w");
            boolean surface = false;
            for (Class<?> c = provider.getClass(); c != null; c = c.getSuperclass()) {
                if ("net.minecraft.world.WorldProviderSurface".equals(c.getName())) { surface = true; break; }
            }
            if (!surface) return null; // unsupported world: only the qualified surface profile admits
            return new LiveForgeCaptureSource(chunk, world, binding, gate, filter);
        } catch (Throwable unresolvable) {
            return null;
        }
    }

    @Override public Object world() { return world; }

    /** The live shadow is its own scope; the offline-synthetic flag stays false. */
    @Override public boolean syntheticOfflineScope() { return false; }

    /** Phase hooks are diagnostic counters for the offline path; nothing to hook here. */
    @Override public void atPhase(CaptureContract.Phase phase) { }

    @Override public CaptureContract.Scope captureScope() {
        return CaptureContract.Scope.LIVE_SHADOW_OWNED_V1;
    }

    @Override public Object chunk() { return chunk; }

    /** SPacketChunkData readout via pinned field names; arrays cloned immediately. */
    @Override public LivePacketCapture.JavaPacketView javaPacket(Object packet) {
        try {
            byte[] payload = ((byte[]) field(packet, "field_186949_d")).clone();
            int mask = (Integer) field(packet, "field_186948_c");
            boolean full = (Boolean) field(packet, "field_149279_g");
            int x = (Integer) field(packet, "field_149284_a");
            int z = (Integer) field(packet, "field_149282_b");
            return new LivePacketCapture.JavaPacketView() {
            @Override public byte[] payload() { return payload; }
            @Override public int mask() { return mask; }
            @Override public boolean fullChunk() { return full; }
            @Override public int packetX() { return x; }
            @Override public int packetZ() { return z; }
        };
        } catch (Throwable unresolvable) {
            throw new IllegalStateException("live packet field read failed", unresolvable);
        }
    }

    /** The TE map only for an emptiness check: never retained, never iterated here. */
    public boolean tileEntitiesEmpty() {
        try {
            Map<?, ?> map = (Map<?, ?>) invoke(chunk, "func_177434_r");
            return map.isEmpty();
        } catch (Throwable unresolvable) {
            throw new IllegalStateException("live TE map read failed", unresolvable);
        }
    }

    @Override public CaptureSource.View readView() {
        try {
            Object chunkIdentity = chunk;
            Object storageIdentity = invoke(chunk, "func_76587_i"); // ExtendedBlockStorage[16]
            Object[] sections = (Object[]) storageIdentity;
            CaptureSource.Section[] extracted = new CaptureSource.Section[16];
            Map<Object, Integer> stateIds = new IdentityHashMap<Object, Integer>();
            for (int y = 0; y < 16; y++) {
                Object ebs = sections[y];
                if (ebs == null) continue;
                extracted[y] = extractSection(ebs, y, stateIds);
            }
            byte[] biomes = ((byte[]) invoke(chunk, "func_76605_m")).clone();
            return new CaptureSource.View(chunkIdentity, storageIdentity, 0,
                    chunkX(chunk), chunkZ(chunk), binding.incarnation(),
                    binding.ownedEncodeGeneration(), gate.epoch(), filter,
                    filter == 0xFFFF, skylight, CaptureContract.StorageModel.VANILLA_U16,
                    globalPaletteBits, provenance, extracted, biomes);
        } catch (LivePacketCapture.Rejection failure) {
            throw failure;
        } catch (Throwable unresolvable) {
            throw new IllegalStateException("live chunk extraction failed", unresolvable);
        }
    }

    private CaptureSource.Section extractSection(Object ebs, int y, Map<Object, Integer> stateIds)
            throws Exception {
        boolean empty = (Boolean) invoke(ebs, "func_76663_a");
        long[] states = new long[4096];
        int nonAir = 0;
        int index = 0;
        for (int py = 0; py < 16; py++) {
            for (int pz = 0; pz < 16; pz++) {
                for (int px = 0; px < 16; px++) {
                    Object state = invoke(ebs, "func_177485_a", px, py, pz);
                    long id = globalStateId(state, stateIds);
                    if (id != 0) nonAir++;
                    states[index++] = id;
                }
            }
        }
        // Cross-check the extracted cells against the runtime's own count bookkeeping:
        // a divergence means noncanonical storage and refuses the capture.
        if (empty != (nonAir == 0)) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.NONCANONICAL_STORAGE,
                    "isEmpty=" + empty + " but counted nonAir=" + nonAir + " in section " + y);
        }
        byte[] blockLight = ((byte[]) invoke(invoke(ebs, "func_76661_k"), "func_177481_a")).clone();
        Object skyNibble = invoke(ebs, "func_76671_l");
        byte[] skyLight = skylight ? ((byte[]) invoke(skyNibble, "func_177481_a")).clone() : null;
        return new CaptureSource.Section(ebs, states, blockLight, skyLight, null, empty, nonAir);
    }

    private long globalStateId(Object state, Map<Object, Integer> stateIds) throws Exception {
        if (state == null) return 0L;
        Integer memo = stateIds.get(state);
        if (memo != null) return memo;
        Object registry = staticField(minecraftClass("net.minecraft.block.Block"), "field_176229_d");
        Integer id = (Integer) invoke(registry, "func_148747_b", state);
        if (id == null || id < 0) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.UNRESOLVED_STATE,
                    "registry has no id for " + state);
        }
        Object roundTrip = invoke(registry, "func_148745_a", id);
        if (roundTrip != state) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.UNRESOLVED_STATE,
                    "registry round-trip mismatch for id " + id);
        }
        stateIds.put(state, id);
        return id;
    }

    private static int chunkX(Object chunk) throws Exception {
        return (Integer) field(chunk, "field_76635_g");
    }

    private static int chunkZ(Object chunk) throws Exception {
        return (Integer) field(chunk, "field_76647_h");
    }

    private static boolean worldHasSkylight(Object world) throws Exception {
        Object provider = field(world, "field_73011_w");
        return (Boolean) invoke(provider, "func_191066_m");
    }

    /** Vanilla width derivation: ceil log2 of the global registry size, clamped 9..16. */
    private int globalPaletteBits() throws Exception {
        Object registry = staticField(minecraftClass("net.minecraft.block.Block"), "field_176229_d");
        int size = ((Number) invoke(registry, "func_186804_a")).intValue();
        if (size < 2) throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.UNSUPPORTED_REGISTRY,
                "global registry too small: " + size);
        int bits = 32 - Integer.numberOfLeadingZeros(size - 1);
        return Math.max(9, Math.min(16, bits));
    }

    private static Object field(Object owner, String name) throws Exception {
        // Detached diagnostic worlds are subclasses; walk the chain to the declaring class.
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException absent) { continue; }
        }
        throw new NoSuchFieldException(name + " on " + owner.getClass().getName());
    }

    /**
     * Minecraft classes resolve through the caller's context classloader (the
     * launch chain offline/live), never this class's own loader.
     */
    private Class<?> minecraftClass(String name) throws Exception {
        ClassLoader loader = chunk.getClass().getClassLoader();
        if (loader == null) loader = LiveForgeCaptureSource.class.getClassLoader();
        return Class.forName(name, false, loader);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object invoke(Object owner, String name) throws Exception {
        return invoke(owner, name, new Object[0]);
    }

    private static Object invoke(Object owner, String name, Object... args) throws Exception {
        Method m = findMethod(owner.getClass(), name, args.length);
        m.setAccessible(true);
        return m.invoke(owner, args);
    }

    /**
     * Pinned SRG names are unique per runtime class here, so lookup is by name and
     * arity; reflection boxes/unboxes primitives across get/invoke transparently.
     * An arity collision would be a transformed-runtime change and refuses above.
     */
    private static Method findMethod(Class<?> type, String name, int arity)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
            }
        }
        throw new NoSuchMethodException(name + "/" + arity + " on " + type.getName());
    }
}
