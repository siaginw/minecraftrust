package com.rustcraft.bridge.capture;

import java.util.Map;

/** Profile-bound vanilla 1.12 storage extractor for diagnostic SHADOW only. */
public final class LiveForgeCaptureSource implements LivePacketCapture.LiveCaptureSource {
    private static final String CHUNK = "net.minecraft.world.chunk.Chunk";
    private static final String SECTION = "net.minecraft.world.chunk.storage.ExtendedBlockStorage";
    private static final String NIBBLE = "net.minecraft.world.chunk.NibbleArray";
    private static final String PACKET = "net.minecraft.network.play.server.SPacketChunkData";
    private final Object chunk, world;
    private final LiveChunkBindings.Binding binding;
    private final LiveWriterGate gate;
    private final int filter;
    private final LiveCaptureScope.RuntimeBinding runtime;
    private final LiveCaptureScope scope;
    private final String provenance;

    private LiveForgeCaptureSource(Object chunk, Object world, LiveChunkBindings.Binding binding,
            LiveWriterGate gate, int filter, LiveCaptureScope.RuntimeBinding runtime) {
        this.chunk = chunk; this.world = world; this.binding = binding; this.gate = gate;
        this.filter = filter; this.runtime = runtime; this.scope = runtime.scope;
        this.provenance = "live-shadow-owned-v1\u0001session=" + binding.sessionId()
                + "\u0001worldId=" + binding.worldId() + "\u0001chunkId=" + binding.chunkId()
                + "\u0001incarnation=" + binding.incarnation()
                + "\u0001ownedEncodeGeneration=" + binding.ownedEncodeGeneration()
                + "\u0001profile=" + scope.profileId + "\u0001certificate=" + scope.certificateId
                + "\u0001extractorScope=" + LiveCaptureScope.SCHEMA + "\u0001dimension=" + scope.dimension
                + "\u0001storage=" + scope.storageFamily + "\u0001registryEpoch=" + scope.registryEpoch
                + "\u0001stateWidth=" + scope.stateWidthBits + "\u0001generator=" + scope.generatorFamily;
    }

    /** Missing explicit immutable policy fails closed; no legacy inference here. */
    public static LiveForgeCaptureSource forChunk(Object chunk, LiveChunkBindings.Binding binding,
            LiveWriterGate gate, int filter) { return null; }

    public static LiveForgeCaptureSource forChunk(Object chunk, LiveChunkBindings.Binding binding,
            LiveWriterGate gate, int filter, LiveCaptureScope.RuntimeBinding runtime) {
        try {
            if (chunk == null || binding == null || gate == null || runtime == null || (filter & ~0xffff) != 0) {
                System.err.println("[RustCraft] forChunk null: chunk=" + (chunk == null)
                        + " binding=" + (binding == null) + " gate=" + (gate == null)
                        + " runtime=" + (runtime == null) + " filter=0x" + Integer.toHexString(filter));
                return null;
            }
            Object world = runtime.field(chunk, CHUNK, "field_76637_e", "Lnet/minecraft/world/World;");
            runtime.checkWorld(chunk, world);
            return new LiveForgeCaptureSource(chunk, world, binding, gate, filter, runtime);
        } catch (LiveCaptureScope.ScopeFailure unsupported) {
            System.err.println("[RustCraft] capture scope rejected chunk: " + unsupported.reason);
            return null;
        } catch (Exception unsupported) { return null; }
    }

    @Override public Object world() { return world; }
    @Override public Object chunk() { return chunk; }
    @Override public boolean syntheticOfflineScope() { return false; }
    @Override public void atPhase(CaptureContract.Phase phase) { }
    @Override public CaptureContract.Scope captureScope() { return CaptureContract.Scope.LIVE_SHADOW_OWNED_V1; }

    @Override public LivePacketCapture.JavaPacketView javaPacket(Object packet) {
        try {
            runtime.checkPacket(packet);
            byte[] payload = ((byte[]) runtime.field(packet, PACKET, "field_186949_d", "[B")).clone();
            int mask = (Integer) runtime.field(packet, PACKET, "field_186948_c", "I");
            boolean full = (Boolean) runtime.field(packet, PACKET, "field_149279_g", "Z");
            int x = (Integer) runtime.field(packet, PACKET, "field_149284_a", "I");
            int z = (Integer) runtime.field(packet, PACKET, "field_149282_b", "I");
            return new LivePacketCapture.JavaPacketView() {
                @Override public byte[] payload() { return payload; }
                @Override public int mask() { return mask; }
                @Override public boolean fullChunk() { return full; }
                @Override public int packetX() { return x; }
                @Override public int packetZ() { return z; }
            };
        } catch (Exception failure) { throw new IllegalStateException("qualified packet extraction failed", failure); }
    }

    @Override public boolean tileEntitiesEmpty() {
        try {
            Map<?, ?> map = (Map<?, ?>) runtime.invoke(chunk, CHUNK, "func_177434_r", "()Ljava/util/Map;");
            if (map.getClass() != java.util.HashMap.class)
                throw new LiveCaptureScope.ScopeFailure("TILE_ENTITY_MAP_CLASS");
            return map.isEmpty();
        } catch (Exception failure) { throw new IllegalStateException("qualified TE extraction failed", failure); }
    }

    @Override public CaptureSource.View readView() {
        try {
            if (!gate.isOwnerUnderGate()) throw new LiveCaptureScope.ScopeFailure("READ_REQUIRES_OWNER_GATE");
            runtime.checkWorld(chunk, world);
            runtime.checkRegistry(scope.registryEpoch);
            Object storage = runtime.invoke(chunk, CHUNK, "func_76587_i",
                    "()[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;");
            Object[] sections = (Object[]) storage;
            if (sections.length != 16) throw new LiveCaptureScope.ScopeFailure("SECTION_ARRAY_LENGTH");
            CaptureSource.Section[] extracted = new CaptureSource.Section[16];
            for (int y = 0; y < 16; y++) if (sections[y] != null) extracted[y] = extractSection(sections[y], y);
            byte[] biomes = ((byte[]) runtime.invoke(chunk, CHUNK, "func_76605_m", "()[B")).clone();
            runtime.checkRegistry(scope.registryEpoch);
            runtime.checkWorld(chunk, world);
            return new CaptureSource.View(chunk, storage, scope.dimension,
                    (Integer) runtime.field(chunk, CHUNK, "field_76635_g", "I"),
                    (Integer) runtime.field(chunk, CHUNK, "field_76647_h", "I"),
                    binding.incarnation(), binding.ownedEncodeGeneration(), gate.epoch(), filter,
                    filter == 0xffff, scope.skylight, CaptureContract.StorageModel.VANILLA_U16,
                    scope.stateWidthBits, scope.registrySize, provenance, extracted, biomes);
        } catch (LivePacketCapture.Rejection failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("qualified chunk extraction failed", failure); }
    }

    private CaptureSource.Section extractSection(Object section, int y) throws Exception {
        runtime.checkSection(section);
        boolean empty = (Boolean) runtime.invoke(section, SECTION, "func_76663_a", "()Z");
        long[] states = new long[4096]; int nonAir = 0;
        // Bind once per section. Exact class and descriptor checks precede
        // invocation; no unqualified subclass dispatch is admitted.
        java.lang.reflect.Method read = LiveCaptureScope.exactMethod(section, section.getClass(),
                "func_177485_a", "(III)Lnet/minecraft/block/state/IBlockState;");
        for (int i = 0; i < 4096; i++) {
            Object state = read.invoke(section, i & 15, i >>> 8, (i >>> 4) & 15);
            long id = runtime.stateId(state);
            if (id != 0) nonAir++;
            states[i] = id;
        }
        if (empty != (nonAir == 0))
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.NONCANONICAL_STORAGE,
                    "isEmpty=" + empty + " differs from nonAir=" + nonAir + " in section " + y);
        Object block = runtime.field(section, SECTION, "field_76679_g", "Lnet/minecraft/world/chunk/NibbleArray;");
        Object sky = runtime.field(section, SECTION, "field_76685_h", "Lnet/minecraft/world/chunk/NibbleArray;");
        byte[] blockLight = ((byte[]) runtime.invoke(block, NIBBLE, "func_177481_a", "()[B")).clone();
        byte[] skyLight = scope.skylight ? ((byte[]) runtime.invoke(sky, NIBBLE, "func_177481_a", "()[B")).clone() : null;
        return new CaptureSource.Section(section, states, blockLight, skyLight, null, empty, nonAir);
    }
}
