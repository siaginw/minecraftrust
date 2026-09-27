package com.rustcraft.offline.oracle;

import com.google.gson.GsonBuilder;
import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.qualification.LoaderDefinitionWitness;
import com.rustcraft.qualification.LoaderTransformChain;
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

  private static void writeClass(Path file, byte[] bytes) {
    try {
      if (bytes == null) return;
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    } catch (Exception unwritable) {
      // A class whose buffer could not be written is a gap in what the engine
      // can recompute. The engine reports that as INCOMPLETE against the
      // missing file, which is more honest than a receipt claiming a class was
      // captured when no file exists.
    }
  }

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
      // The session environment is what authorizes a session-bound admission,
      // and before this it had no production caller at all: only the synthetic
      // controls bound one. Unbound, every session-bound class load is INCOMPLETE
      // -- the correct fail-closed answer, but one that hides whether the run
      // was ever capable of authorizing anything. Binding it from this launch's
      // OWN properties, before the writers are registered, is what lets them
      // issue certificates for this process. The outcome is recorded either way,
      // so a run that authorized nothing says so in its manifest instead of
      // being indistinguishable from one that was never asked.
      String sessionProcess = System.getProperty(
              LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY);
      String sessionId = System.getProperty(
              LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY);
      boolean sessionBound = sessionProcess != null && sessionProcess.length() > 0
              && sessionId != null && sessionId.length() > 0;
      if (sessionBound) {
        LiveHookSupport.bindSessionEnvironment(
                new LiveHookSupport.SystemPropertySessionEnvironment());
      }
      result.put("session_environment_bound", Boolean.valueOf(sessionBound));
      // The observer goes in at the FRONT of the loader's transformer list, so
      // it sees the buffer the loader was about to transform -- the true entry
      // to this launch's transformation chain. Registered immediately before
      // the writers instead, it would capture the very buffer the writers
      // receive, and the chain's first stage would be a no-op that describes
      // nothing. Registration order is the whole point, and it is also why the
      // chain needs a genuine first edge rather than a restatement.
      result.put("entry_observer_position",
              com.rustcraft.qualification.LoaderTransformChain.installAtFront(Launch.classLoader));
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

    // ---- the same-process chain, and an INDEPENDENT definition witness ----
    // Both are emitted here, after the required classes are defined, because
    // both are statements about what this launch did. Neither is derived from
    // the other: the chain is built from the writers' own records, the witness
    // from the passive agent's record of the loader's definitions. A chain that
    // could only confirm itself would prove nothing, so the engine is given
    // both and is free to disagree with either.
    int boundDefinitions = LoaderTransformChain.bindDefinitions(
            Launch.classLoader, LiveHookSupport.boundAcquisition());
    result.put("definitions_bound_to_classes", Integer.valueOf(boundDefinitions));
    List<String> downstream = LoaderTransformChain.downstreamTransformers();
    result.put("downstream_transformers_after_live_writers", downstream);
    result.put("loader_identity", LoaderTransformChain.loaderIdentity());
    String chainProperty = System.getProperty("rustcraft.transformationChain");
    if (chainProperty != null && chainProperty.length() > 0) {
      try {
        String document = LoaderTransformChain.forThisLaunch().render();
        Path chainOut = Paths.get(chainProperty);
        Files.createDirectories(chainOut.getParent());
        Files.write(chainOut, document.getBytes(StandardCharsets.UTF_8));
        result.put("transformation_chain_status", "RENDERED");
        result.put("transformation_chain_sha256", LiveHookSupport.sha256(
                document.getBytes(StandardCharsets.UTF_8)));
      } catch (Throwable incomplete) {
        // A chain that cannot be proven is not an error here: the receipt says
        // exactly why, and the engine reads an absent chain as INCOMPLETE
        // rather than as a defect.
        result.put("transformation_chain_status", "INCOMPLETE: " + incomplete);
      }
    }
    // Why each definition stands where it does. A chain that renders nothing
    // is indistinguishable, from the receipt alone, between "the writers never
    // ran" and "the writers ran and their records are incomplete"; naming the
    // per-definition state is the difference between a gap in the evidence and
    // a bug in the evidence.
    List<Object> acquisitionRows = new ArrayList<Object>();
    if (LiveHookSupport.boundAcquisition() != null) {
      for (com.rustcraft.qualification.SameProcessAcquisition.Definition definition
          : LiveHookSupport.boundAcquisition().definitions()) {
        Map<String, Object> row = new TreeMap<String, Object>();
        row.put("ordinal", Integer.valueOf(definition.ordinal));
        row.put("binary_name", definition.binaryName);
        row.put("hook_placement", definition.hookPlacement);
        row.put("definition_succeeded", Boolean.valueOf(definition.definitionSucceeded));
        row.put("has_post_writer_buffer",
                Boolean.valueOf(definition.postWriterRawSha256 != null));
        row.put("has_session_provenance", Boolean.valueOf(definition.session != null));
        row.put("certifiable", Boolean.valueOf(definition.certifiable()));
        row.put("failure", definition.failure);
        acquisitionRows.add(row);
      }
    }
    result.put("session_acquisition_definitions", acquisitionRows);
    // The engine does not take the writers' word for any class identity: it
    // reparses the bytes itself. So the pre-writer and post-writer buffers have
    // to leave this process as FILES, written from the same records the chain
    // is built from, or there is nothing for it to recompute and every
    // certificate comparison would be against a value this run asserted.
    String observationDir = System.getProperty("rustcraft.observationDir");
    if (observationDir != null && observationDir.length() > 0
            && LiveHookSupport.boundAcquisition() != null) {
      Path root = Paths.get(observationDir);
      for (com.rustcraft.qualification.SameProcessAcquisition.Definition definition
          : LiveHookSupport.boundAcquisition().definitions()) {
        String relative = definition.binaryName.replace('.', '/') + ".class";
        writeClass(root.resolve("pre").resolve(relative), definition.preWriterBytes);
        writeClass(root.resolve("classes").resolve(relative), definition.postWriterBuffer());
      }
      result.put("observation_dir", observationDir);
    }
    result.put("entry_observer_failures", new TreeMap<String, String>(
            com.rustcraft.qualification.LoaderTransformChain.EntryObserver.failures()));
    // The frame/hierarchy witness is the GENERIC one, the same machinery Clean
    // Forge uses, running in this process against this loader and these defined
    // buffers. It is not a second, weaker Revelation-specific proof: the
    // obligations it answers (which types resolve, which are assignable) are
    // questions, and the answers come from the real loader here.
    String frameTypes = System.getProperty("rustcraft.frameTypes");
    String frameQueries = System.getProperty("rustcraft.frameQueries");
    if (frameTypes != null && frameQueries != null) {
      try {
        result.put("frame_relation_witness",
                com.rustcraft.livetransformer.FrameRelationWitness.run(
                        System.getProperty("rustcraft.definedDump"),
                        ObservationAgent.hashes(), frameTypes, frameQueries));
        result.put("frame_witness", "GENERIC_FRAME_RELATION_WITNESS_V1");
      } catch (Throwable incomplete) {
        result.put("frame_relation_witness_status", "INCOMPLETE: " + incomplete);
      }
    } else {
      result.put("frame_relation_witness_status", "INCOMPLETE_NO_FRAME_INPUTS");
    }
    result.put("loader_definition_witness", LoaderDefinitionWitness.witness(
            ObservationAgent.hashes(), ObservationAgent.loaders(),
            LoaderTransformChain.loaderIdentity(),
            LiveHookSupport.boundAcquisition()));
    Path out = Paths.get(System.getProperty("rustcraft.qualificationResult"));
    Files.createDirectories(out.getParent());
    Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result)
        .getBytes(StandardCharsets.UTF_8));
    System.out.println("RUSTCRAFT_REV_OFFLINE_TRANSFORMATION_QUALIFICATION_COMPLETE");
  }
}
