package com.rustcraft.offline.oracle;

import com.google.gson.GsonBuilder;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Revelation dedicated-server lifecycle inventory agent. PASSIVE at class-load
 * time (same observation behavior as ObservationAgent via its premain chain is
 * NOT used here — this agent only registers a shutdown hook that dumps the
 * real server's lifecycle truth: loaded mods, registry state ids, chunk
 * lifecycle listeners, capability listeners, provider inventory, FML coremod
 * plugins and the runtime Forge identity). Default-OFF for the writer
 * protocol: it never installs transformers and never touches gameplay bytes.
 */
public final class RevServerInventoryAgent {
  private static volatile boolean ran;
  private static ClassLoader CLASS_LOADER;

  public static void premain(String args, Instrumentation instrumentation) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { collect(); } catch (Throwable failure) {
        System.out.println("[rev-qual] inventory collection failed: " + failure);
      }
    }, "rustcraft-rev-inventory"));
  }

  private static void collect() throws Exception {
    if (ran) return;
    ran = true;
    // All mod/Forge/vanilla classes live in the LaunchClassLoader (transformed,
    // initialized). The application loader only sees raw jar bytes without the
    // FML-generated constructors, so every reflective lookup must go through
    // the launch loader.
    try {
      Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", true, RevServerInventoryAgent.class.getClassLoader());
      CLASS_LOADER = (ClassLoader) launch.getField("classLoader").get(null);
    } catch (Throwable ignored) {
      CLASS_LOADER = RevServerInventoryAgent.class.getClassLoader();
    }
    Map<String, Object> result = new TreeMap<>();
    result.put("phase", "DEDICATED_SERVER_QUALIFICATION");
    result.put("collectedAt", System.currentTimeMillis());
    try {
      Class<?> injectionData = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData", true, CLASS_LOADER);
      result.put("forge_build", field(injectionData, "build"));
      result.put("forge_major", field(injectionData, "major"));
      result.put("forge_mccversion", field(injectionData, "mccversion"));
    } catch (Throwable failure) {
      result.put("forge_identity_error", failure.toString());
    }
    try {
      Class<?> loader = Class.forName("net.minecraftforge.fml.common.Loader", true, CLASS_LOADER);
      Object instance = loader.getMethod("instance").invoke(null);
      Class<?> containerType = Class.forName("net.minecraftforge.fml.common.ModContainer", true, CLASS_LOADER);
      List<Map<String, String>> mods = new ArrayList<>();
      for (Object mod : (Iterable<?>) loader.getMethod("getActiveModList").invoke(instance)) {
        Map<String, String> item = new TreeMap<>();
        item.put("id", (String) containerType.getMethod("getModId").invoke(mod));
        item.put("version", (String) containerType.getMethod("getVersion").invoke(mod));
        mods.add(item);
      }
      result.put("active_mod_count", mods.size());
      result.put("active_mods", mods);
    } catch (Throwable failure) {
      result.put("mod_inventory_error", failure.toString());
    }
    try {
      Object registry = Class.forName("net.minecraft.block.Block", true, CLASS_LOADER).getField("field_176229_d").get(null);
      java.lang.reflect.Method stateId = Class.forName("net.minecraft.util.ObjectIntIdentityMap", true, CLASS_LOADER)
          .getMethod("func_148747_b", Object.class);
      TreeMap<Integer, String> states = new TreeMap<>();
      for (Object state : (Iterable<?>) registry) {
        states.put((Integer) stateId.invoke(registry, state), state.toString());
      }
      result.put("registry_state_count", states.size());
      result.put("registry_state_max_id", states.isEmpty() ? -1 : states.lastKey());
      result.put("registry_max_id_exceeds_u16", !states.isEmpty() && states.lastKey() > 0xFFFF);
      StringBuilder text = new StringBuilder();
      for (Map.Entry<Integer, String> entry : states.entrySet()) {
        text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
      }
      Path out = outPath();
      Files.write(out.resolveSibling("server-registry-state-ids.tsv"),
          text.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Throwable failure) {
      result.put("registry_error", failure.toString());
    }
    try {
      result.put("chunk_load_listeners", listenersOf("net.minecraftforge.event.world.ChunkEvent$Load"));
      result.put("chunk_unload_listeners", listenersOf("net.minecraftforge.event.world.ChunkEvent$Unload"));
      Object attach = Class.forName("net.minecraftforge.event.AttachCapabilitiesEvent", true, CLASS_LOADER)
          .getConstructor(Class.class, Object.class)
          .newInstance(Class.forName("net.minecraft.world.chunk.Chunk", true, CLASS_LOADER), null);
      result.put("chunk_attach_capabilities_listeners", listenersOf(attach.getClass().getName()));
    } catch (Throwable failure) {
      result.put("listener_error", failure.toString());
    }
    try {
      Field plugins = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager", true, CLASS_LOADER)
          .getDeclaredField("loadPlugins");
      plugins.setAccessible(true);
      List<String> coremods = new ArrayList<>();
      for (Object wrapper : (Iterable<?>) plugins.get(null)) {
        try {
          Field instanceField = wrapper.getClass().getDeclaredField("coreModInstance");
          instanceField.setAccessible(true);
          Object instance = instanceField.get(wrapper);
          coremods.add(instance == null ? "null" : instance.getClass().getName());
        } catch (NoSuchFieldException ignored) {
          coremods.add(wrapper.getClass().getName());
        }
      }
      result.put("registered_coremod_plugins", coremods);
    } catch (Throwable failure) {
      result.put("coremod_error", failure.toString());
    }
    result.put("production_authority", false);
    Path out = outPath();
    Files.createDirectories(out.getParent());
    Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result)
        .getBytes(StandardCharsets.UTF_8));
    System.out.println("[rev-qual] lifecycle inventory written: " + out);
  }

  private static Object listenersOf(String eventName) throws Exception {
    Object event = Class.forName(eventName, true, CLASS_LOADER).getConstructor().newInstance();
    Object bus = Class.forName("net.minecraftforge.common.MinecraftForge").getField("EVENT_BUS").get(null);
    Field busId = bus.getClass().getDeclaredField("busID");
    busId.setAccessible(true);
    Object list = event.getClass().getMethod("getListenerList").invoke(event);
    Object[] listeners = (Object[]) list.getClass().getMethod("getListeners", int.class)
        .invoke(list, busId.getInt(bus));
    List<String> identities = new ArrayList<>();
    for (Object listener : listeners) {
      identities.add(listener.getClass().getName() + ":"
          + listener.toString().replaceAll("@[0-9a-fA-F]+ ", "@IDENTITY "));
    }
    return identities;
  }

  private static Object field(Class<?> type, String name) throws Exception {
    Field declared = type.getDeclaredField(name);
    declared.setAccessible(true);
    return declared.get(null);
  }

  private static Path outPath() {
    return Paths.get(System.getProperty("rustcraft.revInventory", "rev-inventory.json"));
  }
}
