package com.rustcraft.bridge.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Immutable, explicitly selected extractor policy. A policy is not proof of
 * writer closure and does not authorize native packets. RuntimeBinding binds
 * class-loader identity and an actual immutable registry observation to it.
 */
public final class LiveCaptureScope {
    public static final String SCHEMA = "LIVE_CAPTURE_SCOPE_V1";
    public static final String OPERATION = "chunk_packet_shadow_capture";
    public static final String VANILLA_U16 = "VANILLA_U16";

    public final String profileId, certificateId, storageFamily, generatorFamily;

    /**
     * Whether a registry whose ids exceed the u16 transport is tolerated with
     * the decision deferred to each candidate chunk, rather than refusing the
     * runtime outright.
     *
     * <p>Default false, which is the historical behaviour and leaves every
     * existing caller byte-for-byte unchanged. The real Revelation runtime
     * carries registry max id 77,663 while 84.1% of its 35,711 states fit
     * u16, so a registry-level refusal would reject every capture of a runtime
     * whose chunks are mostly comparable. Under this policy the registry is
     * only bound for IDENTITY (it must not change mid-scope); the width
     * decision is made per chunk by {@link ShadowScopeGate}, which excludes a
     * chunk containing any state above 65535 and never widens the transport.
     * </p>
     */
    public final boolean perChunkStateIdLimit;
    public final String providerClass, worldClass, chunkClass, sectionClass, containerClass;
    public final String nibbleClass, packetClass, registryClass, generatorClass;
    public final int dimension, stateWidthBits;
    public final long registryEpoch;
    public final boolean skylight, detachedDiagnostic;

    public LiveCaptureScope(String profileId, String certificateId, int dimension,
            String providerClass, String worldClass, String chunkClass, String sectionClass,
            String containerClass, String nibbleClass, String packetClass, String registryClass,
            String storageFamily, long registryEpoch, int stateWidthBits,
            String generatorFamily, String generatorClass, boolean skylight, boolean detachedDiagnostic) {
        this(profileId, certificateId, dimension, providerClass, worldClass, chunkClass,
                sectionClass, containerClass, nibbleClass, packetClass, registryClass,
                storageFamily, registryEpoch, stateWidthBits, generatorFamily, generatorClass,
                skylight, detachedDiagnostic, false);
    }

    public LiveCaptureScope(String profileId, String certificateId, int dimension,
            String providerClass, String worldClass, String chunkClass, String sectionClass,
            String containerClass, String nibbleClass, String packetClass, String registryClass,
            String storageFamily, long registryEpoch, int stateWidthBits,
            String generatorFamily, String generatorClass, boolean skylight,
            boolean detachedDiagnostic, boolean perChunkStateIdLimit) {
        this.perChunkStateIdLimit = perChunkStateIdLimit;
        this.profileId = text(profileId); this.certificateId = text(certificateId);
        this.dimension = dimension;
        this.providerClass = text(providerClass); this.worldClass = text(worldClass);
        this.chunkClass = text(chunkClass); this.sectionClass = text(sectionClass);
        this.containerClass = text(containerClass); this.nibbleClass = text(nibbleClass);
        this.packetClass = text(packetClass); this.registryClass = text(registryClass);
        this.storageFamily = text(storageFamily); this.registryEpoch = registryEpoch;
        this.stateWidthBits = stateWidthBits; this.generatorFamily = text(generatorFamily);
        this.generatorClass = generatorClass; this.skylight = skylight;
        this.detachedDiagnostic = detachedDiagnostic;
        if (registryEpoch <= 0 || stateWidthBits < 9 || stateWidthBits > 16)
            throw new IllegalArgumentException("invalid registry epoch/state width");
        if (generatorClass == null && (!detachedDiagnostic || !"ABSENT_DETACHED_DIAGNOSTIC".equals(generatorFamily)))
            throw new IllegalArgumentException("absent generator requires explicit detached diagnostic policy");
        if (generatorClass != null) text(generatorClass);
        // Explicitly enumerated generator surfaces. Phase D admits the vanilla
        // Overworld generator in addition to the historical flat campaign
        // surface: join-probe worlds generate Overworld terrain. Each family
        // names exactly one accepted implementation class; anything else is
        // unimplemented, not inferred.
        if (generatorClass != null && !(
                ("MINECRAFT_1_12_FLAT".equals(generatorFamily)
                        && "net.minecraft.world.gen.ChunkGeneratorFlat".equals(generatorClass))
                || ("MINECRAFT_1_12_OVERWORLD".equals(generatorFamily)
                        && "net.minecraft.world.gen.ChunkGeneratorOverworld".equals(generatorClass))))
            throw new IllegalArgumentException("unimplemented generator family");
    }

    private static String text(String value) {
        if (value == null || value.length() == 0 || value.indexOf('\u0001') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("invalid scope text");
        return value;
    }

    /** No authority grant; each factory owns one lifetime-bound registry snapshot. */
    public LivePacketCapture.SourceFactory sourceFactory(final ClassLoader trustedRuntimeLoader) {
        if (trustedRuntimeLoader == null) throw new IllegalArgumentException("explicit runtime loader required");
        return new LivePacketCapture.SourceFactory() {
            private RuntimeBinding runtime;
            @Override public synchronized LivePacketCapture.LiveCaptureSource create(Object packet, Object chunk,
                    int filter, LiveChunkBindings.Binding binding, LiveWriterGate gate) {
                if (chunk == null) return null;
                try {
                    if (runtime == null) runtime = bindRuntime(trustedRuntimeLoader);
                    return LiveForgeCaptureSource.forChunk(chunk, binding, gate, filter, runtime);
                } catch (Exception unsupported) {
                    System.err.println("[RustCraft] capture source factory rejected: " + unsupported);
                    return null;
                }
            }
        };
    }

    public RuntimeBinding bindRuntime(ClassLoader loader) throws Exception {
        return new RuntimeBinding(this, loader);
    }

    public static final class ScopeFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final String reason;
        ScopeFailure(String reason) { super("EXTRACTOR_SCOPE_REJECTED: " + reason); this.reason = reason; }
        ScopeFailure(String reason, String detail) { super("EXTRACTOR_SCOPE_REJECTED: " + reason + ": " + detail); this.reason = reason; }
    }

    /**
     * Exact runtime classes and registry snapshot for one policy/session. The
     * immutable snapshot is checked at capture begin/end. This catches observed
     * replacement/content drift; it does NOT prove absence of transient or
     * asynchronous registry writers. Such closure remains certificate evidence.
     */
    public static final class RuntimeBinding {
        public final LiveCaptureScope scope;
        private final ClassLoader loader;
        private final Class<?> providerType, worldType, chunkType, sectionType, containerType;
        private final Class<?> nibbleType, packetType, registryType, generatorType;
        private final Object registry;
        private final IdentityHashMap<Object, Integer> aliases;
        private final Object[] decoded;
        private boolean revoked;
        private long registryChecks, registryEntriesCompared, registryCheckNanos;

        private RuntimeBinding(LiveCaptureScope scope, ClassLoader loader) throws Exception {
            if (loader == null) throw new ScopeFailure("MISSING_RUNTIME_LOADER");
            this.scope = scope; this.loader = loader;
            providerType = type(scope.providerClass); worldType = type(scope.worldClass);
            chunkType = type(scope.chunkClass); sectionType = type(scope.sectionClass);
            containerType = type(scope.containerClass); nibbleType = type(scope.nibbleClass);
            packetType = type(scope.packetClass); registryType = type(scope.registryClass);
            generatorType = scope.generatorClass == null ? null : type(scope.generatorClass);
            if (!VANILLA_U16.equals(scope.storageFamily)) throw new ScopeFailure("UNSUPPORTED_STORAGE_FAMILY");
            registry = currentRegistry(); exact(registry, registryType, "REGISTRY_CLASS");
            Object map = field(registry, "net.minecraft.util.ObjectIntIdentityMap", "field_148749_a", "Ljava/util/IdentityHashMap;");
            Object list = field(registry, "net.minecraft.util.ObjectIntIdentityMap", "field_148748_b", "Ljava/util/List;");
            if (map.getClass() != IdentityHashMap.class || list.getClass() != ArrayList.class)
                throw new ScopeFailure("UNSUPPORTED_REGISTRY_BACKING");
            aliases = new IdentityHashMap<Object, Integer>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().getClass() != Integer.class)
                    throw new ScopeFailure("INVALID_REGISTRY_ENTRY");
                aliases.put(entry.getKey(), (Integer) entry.getValue());
            }
            decoded = ((ArrayList<?>) list).toArray();
            int size = aliases.size();
            if (size < 2) throw new ScopeFailure("REGISTRY_EXCEEDS_U16");
            if (scope.perChunkStateIdLimit) {
                // Registry-level WIDTH is no longer the gate: a modded registry
                // may legitimately assign ids above the u16 transport (the real
                // Revelation runtime's max id is 77,663). The registry is still
                // bound for identity below -- it must not change mid-scope --
                // and each candidate CHUNK is gated per state by
                // ShadowScopeGate, which excludes any chunk containing a state
                // above 65535. The transport width does not change.
                if (decoded.length > 1 << 20)
                    throw new ScopeFailure("REGISTRY_EXCEEDS_U16");
            } else if (size > 65536 || decoded.length > 65536) {
                throw new ScopeFailure("REGISTRY_EXCEEDS_U16");
            }
            int bits = Math.max(9, 32 - Integer.numberOfLeadingZeros(size - 1));
            if (bits != scope.stateWidthBits) throw new ScopeFailure("STATE_WIDTH_MISMATCH"
                    + ": binding sees " + size + " entries -> " + bits
                    + " bits, scope pinned " + scope.stateWidthBits + " bits");
            if (!scope.perChunkStateIdLimit)
                for (Integer id : aliases.values())
                    if (id < 0 || id >= (1 << scope.stateWidthBits))
                        throw new ScopeFailure("REGISTRY_ID_OUT_OF_WIDTH");
            else
                // Under the per-chunk policy ids may exceed the width in the
                // registry; only their sign is still bound here, because a
                // negative id is a broken registry rather than a wide one.
                for (Integer assigned : aliases.values())
                    if (assigned < 0) throw new ScopeFailure("REGISTRY_ID_OUT_OF_WIDTH");
            checkRegistry(scope.registryEpoch);
        }

        private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

        public void checkWorld(Object chunk, Object world) throws Exception {
            if (revoked) throw new ScopeFailure("REGISTRY_BINDING_REVOKED");
            exact(chunk, chunkType, "CHUNK_CLASS"); exact(world, worldType, "WORLD_CLASS");
            if (field(chunk, "net.minecraft.world.chunk.Chunk", "field_76637_e", "Lnet/minecraft/world/World;") != world)
                throw new ScopeFailure("CHUNK_WORLD_IDENTITY");
            Object provider = field(world, "net.minecraft.world.World", "field_73011_w", "Lnet/minecraft/world/WorldProvider;");
            exact(provider, providerType, "PROVIDER_CLASS");
            int dimension = (Integer) field(provider, "net.minecraft.world.WorldProvider", "dimensionId", "I");
            if (dimension != scope.dimension) throw new ScopeFailure("DIMENSION_MISMATCH");
            boolean skylight = (Boolean) field(provider, "net.minecraft.world.WorldProvider", "field_191067_f", "Z");
            if (skylight != scope.skylight) throw new ScopeFailure("SKYLIGHT_MISMATCH");
            Object providerServer = field(world, "net.minecraft.world.World", "field_73020_y", "Lnet/minecraft/world/chunk/IChunkProvider;");
            if (generatorType == null) {
                if (providerServer != null) throw new ScopeFailure("DETACHED_GENERATOR_MUST_BE_ABSENT");
            } else {
                exact(providerServer, type("net.minecraft.world.gen.ChunkProviderServer"), "CHUNK_PROVIDER_CLASS");
                Object generator = field(providerServer, "net.minecraft.world.gen.ChunkProviderServer",
                        "field_186029_c", "Lnet/minecraft/world/gen/IChunkGenerator;");
                exact(generator, generatorType, "GENERATOR_CLASS");
            }
        }

        public void checkSection(Object section) throws Exception {
            exact(section, sectionType, "SECTION_CLASS");
            Object container = field(section, "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
                    "field_177488_d", "Lnet/minecraft/world/chunk/BlockStateContainer;");
            exact(container, containerType, "CONTAINER_CLASS");
            exact(field(container, "net.minecraft.world.chunk.BlockStateContainer", "field_186021_b",
                    "Lnet/minecraft/util/BitArray;"), type("net.minecraft.util.BitArray"), "PACKED_STORAGE_CLASS");
            Object palette = field(container, "net.minecraft.world.chunk.BlockStateContainer", "field_186022_c",
                    "Lnet/minecraft/world/chunk/IBlockStatePalette;");
            if (palette == null || palette.getClass() != type("net.minecraft.world.chunk.BlockStatePaletteLinear")
                    && palette.getClass() != type("net.minecraft.world.chunk.BlockStatePaletteHashMap")
                    && palette.getClass() != type("net.minecraft.world.chunk.BlockStatePaletteRegistry"))
                throw new ScopeFailure("PALETTE_CLASS");
            // Readable logical state APIs are allowed only on this exact class
            // family. A mod subclass cannot introduce a virtual callback here.
            exact(field(section, "net.minecraft.world.chunk.storage.ExtendedBlockStorage", "field_76679_g",
                    "Lnet/minecraft/world/chunk/NibbleArray;"), nibbleType, "BLOCK_LIGHT_CLASS");
            Object sky = field(section, "net.minecraft.world.chunk.storage.ExtendedBlockStorage", "field_76685_h",
                    "Lnet/minecraft/world/chunk/NibbleArray;");
            if (scope.skylight) exact(sky, nibbleType, "SKY_LIGHT_CLASS");
            else if (sky != null) exact(sky, nibbleType, "UNUSED_SKY_LIGHT_CLASS");
        }

        public void checkPacket(Object packet) { exact(packet, packetType, "PACKET_CLASS"); }

        public void checkRegistry(long observedEpoch) throws Exception {
            long start = System.nanoTime();
            try {
                if (revoked) throw new ScopeFailure("REGISTRY_BINDING_REVOKED");
                if (observedEpoch != scope.registryEpoch) throw new ScopeFailure("REGISTRY_EPOCH_MISMATCH");
                if (currentRegistry() != registry) throw new ScopeFailure("REGISTRY_IDENTITY_CHANGED");
                Object map = field(registry, "net.minecraft.util.ObjectIntIdentityMap", "field_148749_a", "Ljava/util/IdentityHashMap;");
                Object list = field(registry, "net.minecraft.util.ObjectIntIdentityMap", "field_148748_b", "Ljava/util/List;");
                if (map.getClass() != IdentityHashMap.class || list.getClass() != ArrayList.class
                        || ((Map<?, ?>) map).size() != aliases.size() || ((ArrayList<?>) list).size() != decoded.length)
                    throw new ScopeFailure("REGISTRY_CONTENT_CHANGED");
                for (Map.Entry<Object, Integer> entry : aliases.entrySet()) {
                    registryEntriesCompared++;
                    if (!entry.getValue().equals(((IdentityHashMap<?, ?>) map).get(entry.getKey())))
                        throw new ScopeFailure("REGISTRY_ALIAS_CHANGED");
                }
                for (int i = 0; i < decoded.length; i++) {
                    registryEntriesCompared++;
                    if (decoded[i] != ((ArrayList<?>) list).get(i)) throw new ScopeFailure("REGISTRY_DECODE_CHANGED");
                }
            } catch (Exception failure) {
                revoked = true; throw failure;
            } finally {
                registryChecks++; registryCheckNanos += System.nanoTime() - start;
            }
        }

        public long stateId(Object state) {
            Integer id = aliases.get(state);
            if (id == null || id < 0 || id >= decoded.length || decoded[id] != state)
                throw new ScopeFailure("UNRESOLVED_STATE");
            return id.longValue();
        }

        public long registryChecks() { return registryChecks; }
        public long registryEntriesCompared() { return registryEntriesCompared; }
        public long registryCheckNanos() { return registryCheckNanos; }

        private Object currentRegistry() throws Exception {
            Class<?> block = type("net.minecraft.block.Block");
            Field field = block.getDeclaredField("field_176229_d");
            if (!Modifier.isStatic(field.getModifiers()) || !descriptor(field.getType()).equals("Lnet/minecraft/util/ObjectIntIdentityMap;"))
                throw new ScopeFailure("REGISTRY_FIELD_DESCRIPTOR");
            field.setAccessible(true); return field.get(null);
        }

        public Object field(Object receiver, String declaringClass, String name, String descriptor) throws Exception {
            return exactField(receiver, type(declaringClass), name, descriptor);
        }

        public Object invoke(Object receiver, String declaringClass, String name, String descriptor, Object... args) throws Exception {
            return exactMethod(receiver, type(declaringClass), name, descriptor).invoke(receiver, args);
        }

        private static void exact(Object value, Class<?> expected, String reason) {
            if (value == null || value.getClass() != expected)
                throw new ScopeFailure(reason, "expected=" + expected.getName()
                        + ", observed=" + (value == null ? "null" : value.getClass().getName()));
        }
    }

    /** Exact declaring owner/type; hidden fields are an unsupported layout. */
    static Object exactField(Object receiver, Class<?> declaring, String name, String desc) throws Exception {
        if (receiver == null || !declaring.isInstance(receiver)) throw new ScopeFailure("FIELD_RECEIVER_CLASS");
        for (Class<?> c = receiver.getClass(); c != declaring; c = c.getSuperclass()) {
            if (c == null) throw new ScopeFailure("FIELD_OWNER_NOT_FOUND");
            try { c.getDeclaredField(name); throw new ScopeFailure("HIDDEN_FIELD"); }
            catch (NoSuchFieldException absent) { /* continue */ }
        }
        Field f = declaring.getDeclaredField(name);
        if (Modifier.isStatic(f.getModifiers()) || !descriptor(f.getType()).equals(desc))
            throw new ScopeFailure("FIELD_DESCRIPTOR");
        f.setAccessible(true); return f.get(receiver);
    }

    /** Descriptor-exact lookup, refusing dispatch to an unqualified override. */
    static Method exactMethod(Object receiver, Class<?> declaring, String name, String desc) throws Exception {
        if (receiver == null || !declaring.isInstance(receiver)) throw new ScopeFailure("METHOD_RECEIVER_CLASS");
        Method selected = null;
        for (Method m : declaring.getDeclaredMethods())
            if (m.getName().equals(name) && methodDescriptor(m).equals(desc)) {
                if (selected != null) throw new ScopeFailure("AMBIGUOUS_METHOD"); selected = m;
            }
        if (selected == null || Modifier.isStatic(selected.getModifiers())) throw new ScopeFailure("METHOD_DESCRIPTOR");
        for (Class<?> c = receiver.getClass(); c != declaring; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && java.util.Arrays.equals(m.getParameterTypes(), selected.getParameterTypes()))
                    throw new ScopeFailure("UNQUALIFIED_METHOD_OVERRIDE");
        selected.setAccessible(true); return selected;
    }

    static String methodDescriptor(Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> type : method.getParameterTypes()) result.append(descriptor(type));
        return result.append(')').append(descriptor(method.getReturnType())).toString();
    }

    static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V"; if (type == boolean.class) return "Z";
        if (type == byte.class) return "B"; if (type == char.class) return "C";
        if (type == short.class) return "S"; if (type == int.class) return "I";
        if (type == long.class) return "J"; if (type == float.class) return "F"; return "D";
    }
}
