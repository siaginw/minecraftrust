package com.rustcraft.offline.oracle;

import com.google.gson.GsonBuilder;
import com.rustcraft.offline.agent.ObservationAgent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Revelation TRANSFORMATION qualification (offline, lifecycle-free).
 *
 * Runs with the COMPLETE original mod/CoreMod/Mixin/Tweaker inventory: the
 * launch targets the exact pinned server directory, so FML's coremod scan and
 * cascading-tweaker injection register every transformer the pack ships. No
 * mod lifecycle is initialized (no Loader.loadMods/construct/preinit/init, no
 * Bootstrap, no registry population): final transformed bytes of the writer
 * classes are fully determined once the transformer chain is registered, and
 * a mod failing a synthetic lifecycle initialization is a probe limitation,
 * never a profile exclusion.
 *
 * Registry/listener lifecycle evidence is deliberately deferred to a separate
 * real dedicated-server qualification phase.
 */
public final class RevQualifyRuntime {
  public static final String PROFILE = "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1";

  /** Force-loaded (NOT initialized) writer-matrix target classes. */
  private static final String[] REQUIRED = {
      "net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
      "net.minecraft.world.chunk.BlockStateContainer", "net.minecraft.world.chunk.NibbleArray",
      "net.minecraft.util.BitArray", "net.minecraft.world.chunk.BlockStatePaletteLinear",
      "net.minecraft.world.chunk.BlockStatePaletteHashMap", "net.minecraft.world.chunk.BlockStatePaletteRegistry",
      "net.minecraft.util.ObjectIntIdentityMap",
      "net.minecraft.network.play.server.SPacketChunkData", "net.minecraft.tileentity.TileEntity",
      "net.minecraft.network.PacketBuffer", "net.minecraft.world.World", "net.minecraft.world.WorldServer",
      "net.minecraft.world.gen.ChunkProviderServer", "net.minecraft.world.chunk.storage.AnvilChunkLoader",
      "net.minecraftforge.common.chunkio.ChunkIOProvider", "net.minecraftforge.common.chunkio.ChunkIOExecutor",
      "net.minecraft.server.MinecraftServer", "net.minecraft.util.math.BlockPos",
      "net.minecraft.nbt.NBTTagCompound", "net.minecraft.nbt.NBTTagByteArray",
      "net.minecraft.world.WorldProvider", "net.minecraft.world.WorldProviderSurface",
      "net.minecraft.world.gen.ChunkGeneratorFlat",
      "net.minecraft.server.management.PlayerChunkMap", "net.minecraft.server.management.PlayerChunkMapEntry",
      "net.minecraft.util.datafix.DataFixer", "net.minecraft.nbt.CompressedStreamTools"
  };

  private static List<String> transformers() {
    List<String> names = new ArrayList<>();
    for (Object transformer : Launch.classLoader.getTransformers()) {
      names.add(transformer.getClass().getName());
    }
    return names;
  }

  private static List<Map<String, String>> coremodPlugins() throws Exception {
    List<Map<String, String>> coremods = new ArrayList<>();
    Field plugins = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager")
        .getDeclaredField("loadPlugins");
    plugins.setAccessible(true);
    for (Object wrapper : (Iterable<?>) plugins.get(null)) {
      Map<String, String> plugin = new LinkedHashMap<>();
      try {
        Field instanceField = wrapper.getClass().getDeclaredField("coreModInstance");
        instanceField.setAccessible(true);
        Object instance = instanceField.get(wrapper);
        plugin.put("class", instance == null ? "null" : instance.getClass().getName());
        plugin.put("location", instance == null ? "null"
            : instance.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
      } catch (NoSuchFieldException noInstance) {
        plugin.put("class", wrapper.getClass().getName() + " (no instance field)");
      }
      coremods.add(plugin);
    }
    return coremods;
  }

  private static Object safeStatic(String className, String field) {
    try {
      Class<?> type = Class.forName(className);
      Field declared = type.getDeclaredField(field);
      declared.setAccessible(true);
      return declared.get(null);
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static void main(String[] args) throws Exception {
    Thread.currentThread().setName("RustCraftRevOfflineOwner");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema_version", 1);
    result.put("profile", PROFILE);
    result.put("scope", "OFFLINE_TRANSFORMATION_QUALIFICATION_ONLY_COMPLETE_TRANSFORMER_INVENTORY_NO_MOD_LIFECYCLE");
    result.put("java_version", System.getProperty("java.version"));
    result.put("java_runtime_version", System.getProperty("java.runtime.version"));
    if (!"SERVER".equals(FMLLaunchHandler.side().name()) || FMLLaunchHandler.isDeobfuscatedEnvironment()) {
      throw new IllegalStateException("Wrong Forge side or deobfuscated-development environment");
    }
    // Offline hook-insertion validation: register the three qualified
    // transformers AFTER the complete FML chain (they must consume exactly the
    // profile-pinned post-FML pre-hook definitions). Inert unless the
    // diagnostic property is explicitly set; default-OFF runs never transform.
    if (Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) {
      for (String name : new String[] {
          "com.rustcraft.coremod.SPacketChunkDataTransformer",
          "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
          "com.rustcraft.coremod.LiveChunkPublicationTransformer"}) {
        Launch.classLoader.registerTransformer(name);
      }
    }
    result.put("forge_major", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "major"));
    result.put("forge_minor", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "minor"));
    result.put("forge_rev", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "rev"));
    result.put("forge_build", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "build"));
    result.put("forge_mccversion", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "mccversion"));
    result.put("forge_mcpversion", safeStatic("net.minecraftforge.fml.relauncher.FMLInjectionData", "mcpversion"));
    result.put("transformers", transformers());
    try {
      result.put("registered_coremod_plugins", coremodPlugins());
    } catch (Throwable failure) {
      result.put("coremod_inventory_error", failure.toString());
    }
    List<String> missing = new ArrayList<>();
    Map<String, String> locations = new TreeMap<>();
    for (String name : REQUIRED) {
      try {
        // initialize=false: no static initializers run; only the final
        // transformed definition is observed and hashed by the agent.
        Class.forName(name, false, Launch.classLoader);
        if (!ObservationAgent.hashes().containsKey(name)) missing.add(name);
        else locations.put(name, "observed");
      } catch (Throwable failure) {
        StringBuilder chain = new StringBuilder(failure.toString());
        Throwable cause = failure.getCause();
        while (cause != null) {
          chain.append(" <- ").append(cause);
          cause = cause.getCause();
        }
        missing.add(name + " (" + chain + ")");
      }
    }
    result.put("required_class_locations", locations);
    result.put("required_class_missing", missing);
    result.put("mod_lifecycle_executed", false);
    result.put("registry_listener_phase", "DEFERRED_TO_DEDICATED_SERVER_QUALIFICATION");
    result.put("production_authority", false);
    result.put("server_main_called", false);
    result.put("transformed_classes", ObservationAgent.hashes());
    result.put("transformed_class_loaders", ObservationAgent.loaders());
    Path out = Paths.get(System.getProperty("rustcraft.qualificationResult"));
    Files.createDirectories(out.getParent());
    Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result)
        .getBytes(StandardCharsets.UTF_8));
    System.out.println("RUSTCRAFT_REV_OFFLINE_TRANSFORMATION_QUALIFICATION_COMPLETE");
  }
}
