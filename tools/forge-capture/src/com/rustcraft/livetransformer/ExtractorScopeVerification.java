package com.rustcraft.livetransformer;

import com.rustcraft.bridge.capture.LegacyCaptureScopes;
import com.rustcraft.bridge.capture.LiveCaptureScope;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.profiler.Profiler;
import sun.misc.Unsafe;

/** Real transformed class scope controls, detached objects only; never a server. */
public final class ExtractorScopeVerification {
    private static class Provider extends WorldProviderSurface { }
    private static final class SubProvider extends Provider { }
    private static final class Detached extends World {
        Detached(WorldProvider provider) { super(null, null, provider, new Profiler(), false); }
        @Override protected net.minecraft.world.chunk.IChunkProvider func_72970_h() { throw new AssertionError("generator callback forbidden"); }
        @Override protected boolean func_175680_a(int x, int z, boolean allowEmpty) { return false; }
    }
    private interface Checked { void run() throws Exception; }
    private static int passed;
    private static final Map<String, String> outcomes = new LinkedHashMap<String, String>();
    private static class Layout { private int value = 3; private int read() { return value; } }
    private static final class HiddenLayout extends Layout { private int value; }
    private static class VirtualLayout { protected int read() { return 3; } }
    private static final class OverrideLayout extends VirtualLayout { @Override protected int read() { return 4; } }

    public static Map<String, Object> run() throws Exception {
        passed = 0;
        outcomes.clear();
        LiveCaptureScope policy = LegacyCaptureScopes.detachedTransformerDiagnostic(Detached.class.getName(), Provider.class.getName());
        LiveCaptureScope.RuntimeBinding binding = policy.bindRuntime(Launch.classLoader);
        Detached world = new Detached(new Provider()); Chunk chunk = new Chunk(world, 0, 0);
        binding.checkWorld(chunk, world); passed++;
        Detached subclassProvider = new Detached(new SubProvider());
        reject(() -> binding.checkWorld(new Chunk(subclassProvider, 0, 0), subclassProvider), "provider subclass", "PROVIDER_CLASS");
        reject(() -> binding.checkWorld(chunk, new Detached(new Provider())), "different world identity", "CHUNK_WORLD_IDENTITY");
        WorldProvider loaderTwin = (WorldProvider) memory().allocateInstance(twinClass(Provider.class));
        if (loaderTwin.getClass() == Provider.class || !loaderTwin.getClass().getName().equals(Provider.class.getName()))
            throw new AssertionError("invalid loader-twin control");
        Detached twinWorld = new Detached(loaderTwin);
        reject(() -> binding.checkWorld(new Chunk(twinWorld, 0, 0), twinWorld), "same provider name different loader", "PROVIDER_CLASS");
        Detached wrongProvider = new Detached(new WorldProviderSurface());
        reject(() -> binding.checkWorld(new Chunk(wrongProvider, 0, 0), wrongProvider), "wrong exact provider", "PROVIDER_CLASS");
        Field dimension = WorldProvider.class.getDeclaredField("dimensionId"); dimension.setAccessible(true);
        dimension.setInt(world.field_73011_w, 7);
        reject(() -> binding.checkWorld(chunk, world), "wrong dimension", "DIMENSION_MISMATCH");
        dimension.setInt(world.field_73011_w, 0); binding.checkWorld(chunk, world); passed++;
        set(world.field_73011_w, WorldProvider.class, "field_191067_f", true);
        reject(() -> binding.checkWorld(chunk, world), "wrong skylight", "SKYLIGHT_MISMATCH");
        set(world.field_73011_w, WorldProvider.class, "field_191067_f", false);
        reject(() -> binding.checkWorld(new Chunk(world, 0, 0) { }, world), "chunk subclass", "CHUNK_CLASS");
        ExtendedBlockStorage section = new ExtendedBlockStorage(0, false);
        binding.checkSection(section); passed++;
        reject(() -> binding.checkSection(new ExtendedBlockStorage(0, false) { }), "section subclass", "SECTION_CLASS");
        Field container = ExtendedBlockStorage.class.getDeclaredField("field_177488_d"); container.setAccessible(true);
        Object originalContainer = container.get(section); container.set(section, new BlockStateContainer() { });
        reject(() -> binding.checkSection(section), "container subclass", "CONTAINER_CLASS"); container.set(section, originalContainer);
        reject(() -> copy(policy, "OTHER_STORAGE", 13, policy.registryClass, policy.generatorFamily, null).bindRuntime(Launch.classLoader), "storage family", "UNSUPPORTED_STORAGE_FAMILY");
        reject(() -> copy(policy, policy.storageFamily, 12, policy.registryClass, policy.generatorFamily, null).bindRuntime(Launch.classLoader), "width mismatch", "STATE_WIDTH_MISMATCH");
        reject(() -> copy(policy, policy.storageFamily, 13, "net.minecraft.util.ObjectIntIdentityMap", policy.generatorFamily, null).bindRuntime(Launch.classLoader), "registry exact class", "REGISTRY_CLASS");
        reject(() -> copy(policy, policy.storageFamily, 13, policy.registryClass, "WRONG_GENERATOR_FAMILY", null), "generator family", "INVALID_SCOPE_POLICY");
        reject(() -> binding.stateId(new Object()), "unregistered state", "UNRESOLVED_STATE");
        if (!Integer.valueOf(3).equals(binding.field(new Layout(), Layout.class.getName(), "value", "I")))
            throw new AssertionError("exact field layout");
        passed++;
        reject(() -> binding.field(new Layout(), Layout.class.getName(), "value", "J"), "field descriptor", "FIELD_DESCRIPTOR");
        reject(() -> binding.field(new HiddenLayout(), Layout.class.getName(), "value", "I"), "hidden field ambiguity", "HIDDEN_FIELD");
        if (!Integer.valueOf(3).equals(binding.invoke(new Layout(), Layout.class.getName(), "read", "()I")))
            throw new AssertionError("exact method layout");
        passed++;
        reject(() -> binding.invoke(new Layout(), Layout.class.getName(), "read", "()J"), "method descriptor", "METHOD_DESCRIPTOR");
        reject(() -> binding.invoke(new OverrideLayout(), VirtualLayout.class.getName(), "read", "()I"), "virtual override ambiguity", "UNQUALIFIED_METHOD_OVERRIDE");
        LiveCaptureScope live = LegacyCaptureScopes.cleanSurfaceFlat(LegacyCaptureScopes.CLEAN_PROFILE);
        if (live.dimension != 0 || !live.providerClass.equals("net.minecraft.world.WorldProviderSurface")
                || !live.generatorClass.equals("net.minecraft.world.gen.ChunkGeneratorFlat") || !live.skylight)
            throw new AssertionError("legacy live policy broadened");
        passed++;
        // Allocate exact runtime layout shells without invoking any world/generator
        // constructor. This proves scope checks only, not a live world or generator.
        LiveCaptureScope.RuntimeBinding liveBinding = live.bindRuntime(Launch.classLoader);
        Object liveWorld = memory().allocateInstance(net.minecraft.world.WorldServer.class);
        Object liveChunk = memory().allocateInstance(Chunk.class);
        Object liveProvider = memory().allocateInstance(WorldProviderSurface.class);
        Object chunkProvider = memory().allocateInstance(net.minecraft.world.gen.ChunkProviderServer.class);
        Object flat = memory().allocateInstance(net.minecraft.world.gen.ChunkGeneratorFlat.class);
        set(liveChunk, Chunk.class, "field_76637_e", liveWorld);
        set(liveWorld, World.class, "field_73011_w", liveProvider);
        set(liveWorld, World.class, "field_73020_y", chunkProvider);
        set(liveProvider, WorldProvider.class, "field_191067_f", true);
        set(chunkProvider, net.minecraft.world.gen.ChunkProviderServer.class, "field_186029_c", flat);
        liveBinding.checkWorld(liveChunk, liveWorld); passed++;
        set(chunkProvider, net.minecraft.world.gen.ChunkProviderServer.class, "field_186029_c",
                memory().allocateInstance(net.minecraft.world.gen.ChunkGeneratorHell.class));
        reject(() -> liveBinding.checkWorld(liveChunk, liveWorld), "wrong generator exact class", "GENERATOR_CLASS");
        set(chunkProvider, net.minecraft.world.gen.ChunkProviderServer.class, "field_186029_c", flat);
        set(liveWorld, World.class, "field_73020_y", null);
        reject(() -> liveBinding.checkWorld(liveChunk, liveWorld), "missing live generator provider", "CHUNK_PROVIDER_CLASS");
        set(world, World.class, "field_73020_y", chunkProvider);
        reject(() -> binding.checkWorld(chunk, world), "detached generator must be absent", "DETACHED_GENERATOR_MUST_BE_ABSENT");
        set(world, World.class, "field_73020_y", null);
        if (LegacyCaptureScopes.cleanSurfaceFlat("REVELATION") != null) throw new AssertionError("unknown plan inherited Clean policy");
        passed++;
        reject(() -> binding.checkRegistry(policy.registryEpoch + 1), "registry epoch", "REGISTRY_EPOCH_MISMATCH");
        reject(() -> binding.checkRegistry(policy.registryEpoch), "registry revocation sticky", "REGISTRY_BINDING_REVOKED");
        LiveCaptureScope.RuntimeBinding drift = policy.bindRuntime(Launch.classLoader);
        Field aliases = Class.forName("net.minecraft.util.ObjectIntIdentityMap", false, Launch.classLoader).getDeclaredField("field_148749_a");
        aliases.setAccessible(true);
        Object registry = Class.forName("net.minecraft.block.Block", false, Launch.classLoader).getDeclaredField("field_176229_d").get(null);
        @SuppressWarnings("unchecked") Map<Object, Integer> aliasMap = (Map<Object, Integer>) aliases.get(registry);
        Object state = aliasMap.keySet().iterator().next(); Integer id = aliasMap.get(state);
        try { aliasMap.put(state, id + 1); reject(() -> drift.checkRegistry(policy.registryEpoch), "same-size registry content drift", "REGISTRY_ALIAS_CHANGED"); }
        finally { aliasMap.put(state, id); }
        reject(() -> drift.checkRegistry(policy.registryEpoch), "registry drift remains revoked after restoration", "REGISTRY_BINDING_REVOKED");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scope", "DETACHED_REAL_TRANSFORMED_CLASSES_NOT_LIVE_QUALIFICATION");
        if (passed != 33 || outcomes.size() != 25) throw new AssertionError("scope control count drift");
        result.put("passed", passed); result.put("positive_controls", passed - outcomes.size());
        result.put("negative_controls", outcomes.size()); result.put("registryChecks", binding.registryChecks());
        result.put("rejection_controls", new LinkedHashMap<String, String>(outcomes));
        result.put("registryEntriesCompared", binding.registryEntriesCompared());
        result.put("registryCheckNanos", binding.registryCheckNanos());
        result.put("productionAuthority", false);
        return result;
    }

    private static LiveCaptureScope copy(LiveCaptureScope p, String storage, int width, String registry,
            String family, String generator) {
        return new LiveCaptureScope(p.profileId, p.certificateId, p.dimension, p.providerClass, p.worldClass,
                p.chunkClass, p.sectionClass, p.containerClass, p.nibbleClass, p.packetClass, registry,
                storage, p.registryEpoch, width, family, generator, p.skylight, p.detachedDiagnostic);
    }
    private static void reject(Checked action, String label, String expectedReason) throws Exception {
        try { action.run(); throw new AssertionError("unexpected scope admission: " + label); }
        catch (LiveCaptureScope.ScopeFailure expected) {
            if (!expectedReason.equals(expected.reason)) throw new AssertionError(label + ": " + expected.reason, expected);
            passed++; outcomes.put(label, expected.reason);
        }
        catch (IllegalArgumentException expected) {
            if (!expectedReason.equals("INVALID_SCOPE_POLICY")) throw new AssertionError(label, expected);
            passed++; outcomes.put(label, "INVALID_SCOPE_POLICY");
        }
    }

    private static void set(Object receiver, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(receiver, value);
    }

    private static Unsafe memory() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (Unsafe) field.get(null);
    }

    private static Class<?> twinClass(Class<?> source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = source.getResourceAsStream("/" + source.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("missing loader control bytes");
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        final byte[] definition = bytes.toByteArray();
        return new ClassLoader(source.getClassLoader()) {
            Class<?> defineTwin() { return defineClass(source.getName(), definition, 0, definition.length); }
        }.defineTwin();
    }
}
