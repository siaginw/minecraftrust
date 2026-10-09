package com.rustcraft.offline.oracle;

import com.google.gson.GsonBuilder;
import com.rustcraft.offline.agent.ObservationAgent;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.launchwrapper.*;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/** Offline entry point: official FML load/preinit/init, but no server main, world, tick or socket. */
public final class QualifyRuntime {
    public static final String PROFILE = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1";
    private static final String PREFIX = "net.minecraftforge.fml.common.asm.transformers.";
    private static final String[] TRANSFORMERS = {
        PREFIX + "PatchingTransformer", "$wrapper." + PREFIX + "SideTransformer",
        "$wrapper." + PREFIX + "EventSubscriptionTransformer",
        "$wrapper." + PREFIX + "EventSubscriberTransformer",
        "$wrapper." + PREFIX + "SoundEngineFixTransformer",
        PREFIX + "DeobfuscationTransformer", PREFIX + "AccessTransformer",
        PREFIX + "ModAccessTransformer", PREFIX + "ItemStackTransformer",
        PREFIX + "ItemBlockTransformer", PREFIX + "ItemBlockSpecialTransformer",
        PREFIX + "PotionEffectTransformer", PREFIX + "TerminalTransformer"
    };
    private static final String[] REQUIRED = {
        "net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
        "net.minecraft.world.chunk.BlockStateContainer", "net.minecraft.world.chunk.NibbleArray",
        "net.minecraft.network.play.server.SPacketChunkData", "net.minecraft.tileentity.TileEntity",
        "net.minecraft.network.PacketBuffer", "net.minecraft.world.World", "net.minecraft.world.WorldServer",
        "net.minecraft.world.gen.ChunkProviderServer", "net.minecraft.world.chunk.storage.AnvilChunkLoader",
        "net.minecraftforge.common.chunkio.ChunkIOProvider", "net.minecraftforge.common.chunkio.ChunkIOExecutor",
        "net.minecraftforge.common.chunkio.ChunkIOThreadPoolExecutor",
        "net.minecraftforge.fml.common.network.internal.FMLNetworkHandler",
        "net.minecraftforge.fml.common.network.NetworkRegistry", "net.minecraftforge.event.ForgeEventFactory",
        "net.minecraftforge.event.AttachCapabilitiesEvent", "net.minecraftforge.fml.common.eventhandler.EventBus",
        "net.minecraftforge.event.world.ChunkEvent$Load", "net.minecraftforge.event.world.ChunkEvent$Unload",
        "net.minecraftforge.fml.common.eventhandler.ListenerList", "net.minecraftforge.fml.common.eventhandler.IEventListener",
        "net.minecraftforge.fml.common.eventhandler.ASMEventHandler", "net.minecraft.server.MinecraftServer",
        "net.minecraftforge.fml.common.FMLCommonHandler", "net.minecraftforge.fml.server.FMLServerHandler",
        "net.minecraft.util.BitArray", "net.minecraft.world.chunk.BlockStatePaletteLinear",
        "net.minecraft.world.chunk.BlockStatePaletteHashMap", "net.minecraft.world.chunk.BlockStatePaletteRegistry",
        "net.minecraft.util.ObjectIntIdentityMap", "net.minecraft.world.WorldProvider",
        "net.minecraft.world.WorldProviderSurface", "net.minecraft.world.WorldProviderHell",
        "net.minecraft.world.storage.WorldInfo", "net.minecraft.world.WorldSettings", "net.minecraft.world.GameType",
        "net.minecraft.world.WorldType", "net.minecraft.profiler.Profiler", "net.minecraft.world.storage.ISaveHandler",
        "net.minecraft.world.chunk.IChunkProvider",
        "net.minecraft.util.math.BlockPos", "net.minecraft.init.Blocks", "net.minecraft.nbt.NBTTagCompound"
        , "net.minecraftforge.common.ForgeInternalHandler", "net.minecraftforge.common.FarmlandWaterManager",
        "net.minecraftforge.common.ticket.ChunkTicketManager", "net.minecraftforge.common.ticket.AABBTicket",
        "net.minecraftforge.common.ticket.MultiTicketManager", "net.minecraftforge.common.ticket.SimpleTicket",
        // Live-writer profile qualification: the flat generator is never loaded by the
        // offline FML-initialized profile (no world is created), so its final transformed
        // definition is forced here without initialization. No static initializer, world,
        // tick, or generator code executes; the passive observer records the transformed bytes.
        "net.minecraft.world.gen.ChunkGeneratorFlat",
        // Same treatment for the packet send-path context classes (no players exist offline).
        "net.minecraft.server.management.PlayerChunkMap",
        "net.minecraft.server.management.PlayerChunkMapEntry"
    };

    /**
     * Issue #1 live-writer diagnostic: appends the three qualified transformers
     * AFTER the complete FML chain (they must consume exactly the qualified
     * post-FML definitions) and before any hooked class is loaded.
     */
    private static void registerLiveWriterTransformers() {
        if (!Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) return;
        for (String name : new String[] {
                "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
                "com.rustcraft.coremod.LiveChunkPublicationTransformer",
                "com.rustcraft.coremod.SPacketChunkDataTransformer"}) {
            Launch.classLoader.registerTransformer(name);
        }
    }

    private static List<String> transformers() {
        List<String> names = new ArrayList<>();
        for (IClassTransformer transformer : Launch.classLoader.getTransformers()) {
            names.add(transformer.getClass().getName());
        }
        return names;
    }

    /** Checks actual inherited listeners, including generic listeners, without posting an event. */
    public static void assertNoAttachCapabilitiesListeners() throws Exception {
        Class<?> eventType = Class.forName("net.minecraftforge.event.AttachCapabilitiesEvent");
        Object event = eventType.getConstructor(Class.class, Object.class)
                .newInstance(Class.forName("net.minecraft.world.chunk.Chunk"), null);
        assertNoListeners(event);
    }

    public static void assertNoChunkLifecycleListeners() throws Exception {
        for (String suffix : new String[] {"Load", "Unload"}) {
            Object event = Class.forName("net.minecraftforge.event.world.ChunkEvent$" + suffix)
                    .getConstructor().newInstance();
            assertNoListeners(event);
        }
    }

    /** Exact clean-Forge registration; its effect is separately audited in the writer inventory. */
    public static void assertQualifiedChunkLifecycleListeners() throws Exception {
        List<String> load = listenerIdentities(Class.forName("net.minecraftforge.event.world.ChunkEvent$Load")
                .getConstructor().newInstance());
        Class<?> unloadType = Class.forName("net.minecraftforge.event.world.ChunkEvent$Unload");
        Object unloadEvent = unloadType.getConstructor().newInstance();
        Object[] actual = listeners(unloadEvent);
        List<String> unload = listenerIdentities(unloadEvent);
        List<String> expected = Arrays.asList("net.minecraftforge.fml.common.eventhandler.EventPriority:NORMAL",
                "net.minecraftforge.fml.common.eventhandler.ASMEventHandler:ASM: net.minecraftforge.common.ForgeInternalHandler@IDENTITY onChunkUnload(Lnet/minecraftforge/event/world/ChunkEvent$Unload;)V");
        if (!load.isEmpty() || !unload.equals(expected)) {
            throw new IllegalStateException("UNQUALIFIED_CHUNK_LIFECYCLE_LISTENERS:" + load + ":" + unload);
        }
        Class<?> asm = Class.forName("net.minecraftforge.fml.common.eventhandler.ASMEventHandler");
        if (actual[1].getClass() != asm) throw new IllegalStateException("Unexpected callback wrapper type");
        Field wrapperField = asm.getDeclaredField("handler");
        wrapperField.setAccessible(true);
        Object wrapper = wrapperField.get(actual[1]);
        Field cacheField = asm.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Method method = Class.forName("net.minecraftforge.common.ForgeInternalHandler")
                .getMethod("onChunkUnload", unloadType);
        if (((Map<?, ?>) cacheField.get(null)).get(method) != wrapper.getClass()) {
            throw new IllegalStateException("Chunk unload method identity is not qualified");
        }
        Field singleton = Class.forName("net.minecraftforge.common.MinecraftForge")
                .getDeclaredField("INTERNAL_HANDLER");
        singleton.setAccessible(true);
        if (wrapper.getClass().getField("instance").get(wrapper) != singleton.get(null)) {
            throw new IllegalStateException("Chunk unload callback target is not Forge's singleton");
        }
        assertNoFarmlandWaterTickets();
    }

    public static void assertNoFarmlandWaterTickets() throws Exception {
        Field field = Class.forName("net.minecraftforge.common.FarmlandWaterManager")
                .getDeclaredField("customWaterHandler");
        field.setAccessible(true);
        if (!((Map<?, ?>) field.get(null)).isEmpty()) {
            throw new IllegalStateException("UNQUALIFIED_FARMLAND_WATER_TICKETS");
        }
    }

    private static List<String> listenerIdentities(Object event) throws Exception {
        List<String> result = new ArrayList<>();
        for (Object listener : listeners(event)) {
            result.add(listener.getClass().getName() + ":" + listener.toString().replaceAll("@[0-9a-fA-F]+ ", "@IDENTITY "));
        }
        return result;
    }

    private static void assertNoListeners(Object event) throws Exception {
        Object[] listeners = listeners(event);
        if (listeners.length != 0) {
            throw new IllegalStateException("UNQUALIFIED_EVENT_LISTENERS:" + event.getClass().getName()
                    + ":" + listeners.length);
        }
    }

    private static Object[] listeners(Object event) throws Exception {
        Object bus = Class.forName("net.minecraftforge.common.MinecraftForge")
                .getField("EVENT_BUS").get(null);
        Field busId = bus.getClass().getDeclaredField("busID");
        busId.setAccessible(true);
        Object list = event.getClass().getMethod("getListenerList").invoke(event);
        return (Object[]) list.getClass().getMethod("getListeners", int.class)
                .invoke(list, busId.getInt(bus));
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("RustCraftOfflineOwner");
        if (!"SERVER".equals(FMLLaunchHandler.side().name())
                || FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            throw new IllegalStateException("Wrong Forge side or deobfuscated-development environment");
        }
        if (!transformers().equals(Arrays.asList(TRANSFORMERS))) {
            throw new IllegalStateException("UNQUALIFIED_TRANSFORMER_CHAIN:" + transformers());
        }
        registerLiveWriterTransformers(); // BEFORE any hooked class can load
        Class.forName("net.minecraft.init.Bootstrap", true, Launch.classLoader)
                .getMethod("func_151354_b").invoke(null);
        byte[] offlineConfig = Files.readAllBytes(Launch.minecraftHome.toPath().resolve("config/forge.cfg"));
        String requiredConfig = "general {\n B:disableVersionCheck=true\n}\n";
        if (!Arrays.equals(offlineConfig, requiredConfig.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Offline-only Forge configuration must precede FML initialization");
        }
        Class<?> handlerType = Class.forName("net.minecraftforge.fml.server.FMLServerHandler");
        Object handler = handlerType.getMethod("instance").invoke(null);
        handlerType.getMethod("beginServerLoading", Class.forName("net.minecraft.server.MinecraftServer"))
                .invoke(handler, new Object[] {null});
        handlerType.getMethod("finishServerLoading").invoke(handler);
        if (!(Boolean) Class.forName("net.minecraftforge.common.ForgeModContainer")
                .getField("disableVersionCheck").get(null)) {
            throw new IllegalStateException("Offline Forge version-check configuration was not applied");
        }
        assertNoAttachCapabilitiesListeners();
        assertQualifiedChunkLifecycleListeners();
        Map<String, String> locations = new TreeMap<>();
        for (String name : REQUIRED) {
            Class<?> clazz = Class.forName(name, false, Launch.classLoader);
            if (clazz.getClassLoader() != Launch.classLoader || !ObservationAgent.hashes().containsKey(name)) {
                throw new IllegalStateException("No final transformed-byte observation: " + name);
            }
            locations.put(name, clazz.getProtectionDomain().getCodeSource().getLocation().toString());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("profile", PROFILE);
        result.put("scope", "OFFLINE_DETACHED_OBJECTS_ONLY_NO_SERVER_OR_WORLD");
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_runtime_version", System.getProperty("java.runtime.version"));
        result.put("java_vm", System.getProperty("java.vm.name"));
        result.put("jvm_arguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("transformers", transformers());
        result.put("observer", "PASSIVE_JVM_CLASS_DEFINITION_OBSERVER_NO_RETRANSFORMATION");
        result.put("required_class_locations", locations);
        result.put("attach_capabilities_listener_count", 0);
        result.put("chunk_load_listener_count", 0);
        result.put("chunk_unload_listeners", listenerIdentities(Class.forName("net.minecraftforge.event.world.ChunkEvent$Unload")
                .getConstructor().newInstance()));
        result.put("server_main_called", false);
        result.put("mod_lifecycle_executed", true);
        result.put("forge_version_check_disabled_for_offline_harness", true);
        result.put("offline_forge_config_input_sha256", ObservationAgent.sha256(offlineConfig));
        result.put("farmland_water_ticket_map_empty", true);
        Map<String, String> mods = new TreeMap<>();
        Class<?> loader = Class.forName("net.minecraftforge.fml.common.Loader");
        Object loaderInstance = loader.getMethod("instance").invoke(null);
        Class<?> containerType = Class.forName("net.minecraftforge.fml.common.ModContainer");
        for (Object mod : (Iterable<?>) loader.getMethod("getModList").invoke(loaderInstance)) {
            mods.put((String) containerType.getMethod("getModId").invoke(mod),
                    (String) containerType.getMethod("getVersion").invoke(mod));
        }
        if (!mods.keySet().equals(new TreeSet<>(Arrays.asList("minecraft", "mcp", "FML", "forge")))) {
            throw new IllegalStateException("Unexpected mods " + mods);
        }
        result.put("loaded_mods", mods);
        List<Map<String, String>> orderedMods = new ArrayList<>();
        for (Object mod : (Iterable<?>) loader.getMethod("getActiveModList").invoke(loaderInstance)) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", (String) containerType.getMethod("getModId").invoke(mod));
            item.put("version", (String) containerType.getMethod("getVersion").invoke(mod));
            orderedMods.add(item);
        }
        result.put("ordered_loaded_mods", orderedMods);
        Field plugins = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager")
                .getDeclaredField("loadPlugins");
        plugins.setAccessible(true);
        List<Map<String, String>> coremods = new ArrayList<>();
        for (Object wrapper : (Iterable<?>) plugins.get(null)) {
            Field instanceField = wrapper.getClass().getDeclaredField("coreModInstance");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(wrapper);
            Map<String, String> plugin = new LinkedHashMap<>();
            plugin.put("class", instance.getClass().getName());
            plugin.put("location", instance.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
            coremods.add(plugin);
        }
        result.put("registered_coremod_plugins", coremods);
        result.put("production_authority", false);
        Object registry = Class.forName("net.minecraft.block.Block")
                .getField("field_176229_d").get(null);
        Method stateId = Class.forName("net.minecraft.util.ObjectIntIdentityMap")
                .getMethod("func_148747_b", Object.class);
        SortedMap<Integer, String> states = new TreeMap<>();
        for (Object state : (Iterable<?>) registry) {
            states.put((Integer) stateId.invoke(registry, state), state.toString());
        }
        StringBuilder registryText = new StringBuilder();
        for (Map.Entry<Integer, String> state : states.entrySet()) {
            registryText.append(state.getKey()).append('\t').append(state.getValue()).append('\n');
        }
        byte[] registryBytes = registryText.toString().getBytes(StandardCharsets.UTF_8);
        Field aliasesField = Class.forName("net.minecraft.util.ObjectIntIdentityMap")
                .getDeclaredField("field_148749_a");
        aliasesField.setAccessible(true);
        List<String> aliases = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) aliasesField.get(registry)).entrySet()) {
            aliases.add(entry.getKey().toString() + "\t" + entry.getValue() + "\n");
        }
        Collections.sort(aliases);
        StringBuilder aliasText = new StringBuilder();
        for (String alias : aliases) aliasText.append(alias);
        byte[] aliasBytes = aliasText.toString().getBytes(StandardCharsets.UTF_8);
        result.put("registry_state_count", states.size());
        result.put("registry_identity_map_size", Class.forName("net.minecraft.util.ObjectIntIdentityMap")
                .getMethod("func_186804_a").invoke(registry));
        result.put("registry_state_max_id", states.lastKey());
        String decodedHash = ObservationAgent.sha256(registryBytes);
        String logicalHash = ObservationAgent.sha256(aliasBytes);
        result.put("registry_decoded_ids_sha256", decodedHash);
        result.put("registry_logical_states_sha256", logicalHash);
        result.put("registry_identity_sha256", ObservationAgent.sha256(("RUSTCRAFT_REGISTRY_V1\n"
                + decodedHash + "\n" + logicalHash + "\n").getBytes(StandardCharsets.UTF_8)));
        Path registryOut = Paths.get(System.getProperty("rustcraft.qualificationResult"))
                .resolveSibling("registry-state-ids.tsv");
        Files.write(registryOut, registryBytes, StandardOpenOption.CREATE_NEW);
        Files.write(registryOut.resolveSibling("registry-logical-states.tsv"), aliasBytes,
                StandardOpenOption.CREATE_NEW);
        String oracle = System.getProperty("rustcraft.oracleMain");
        if (oracle != null) {
            Class.forName(oracle, true, Launch.classLoader).getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        }
        assertNoAttachCapabilitiesListeners();
        assertQualifiedChunkLifecycleListeners();
        if (!transformers().equals(result.get("transformers"))) throw new IllegalStateException("Transformer chain changed after qualification");
        result.put("transformed_classes", ObservationAgent.hashes());
        result.put("transformed_class_loaders", ObservationAgent.loaders());
        Path out = Paths.get(System.getProperty("rustcraft.qualificationResult"));
        Files.createDirectories(out.getParent());
        Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result)
                .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        System.out.println("RUSTCRAFT_OFFLINE_QUALIFICATION_COMPLETE " + PROFILE);
    }
}
