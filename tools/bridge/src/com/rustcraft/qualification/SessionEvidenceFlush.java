package com.rustcraft.qualification;

import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.coremod.LiveWriterOrdering;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ONE end-of-launch evidence producer, shared by the offline qualification
 * oracle and a real FML server launch. Everything it writes comes from this
 * process's OWN records: the same-process acquisition the writers opened, the
 * chain the loader actually executed, the passive agent's observation of the
 * definitions the loader actually made. It observes and certifies; it never
 * invents the fact it is certifying.
 *
 * <p>Outputs (property-driven, identical for both launch shapes):</p>
 * <ul>
 *   <li>{@code rustcraft.qualificationResult}: qualification.json — the launch
 *       facts the collector transcribes (transformers, coremods, frame
 *       witness, acquisition rows, measured writer ordering)</li>
 *   <li>{@code rustcraft.transformationChain}: the chain render with the
 *       session certificates and acquisition carried VERBATIM</li>
 *   <li>{@code rustcraft.observationDir}/{pre,classes}: the pre-writer and
 *       post-writer byte files the engine reparses itself</li>
 * </ul>
 */
public final class SessionEvidenceFlush {

    private SessionEvidenceFlush() { }

    /**
     * Emits the shared evidence sections on top of the caller's launch-shape
     * base facts (offline oracle facts or real-server facts). Never throws:
     * an evidence failure is recorded in the receipt, not turned into a
     * behavior change.
     */
    public static void emit(Map<String, Object> result) {
        try {
            ClassLoader loader = (ClassLoader) Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            // ---- the same-process chain, and an INDEPENDENT definition witness ----
            // Both are statements about what this launch did. Neither is derived
            // from the other: the chain is built from the writers' own records,
            // the witness from the passive agent's record of the loader's
            // definitions. A chain that could only confirm itself would prove
            // nothing, so the engine is given both and may disagree with either.
            int boundDefinitions = LoaderTransformChain.bindDefinitions(
                    loader, LiveHookSupport.boundAcquisition());
            result.put("definitions_bound_to_classes", Integer.valueOf(boundDefinitions));
            result.put("downstream_transformers_after_live_writers",
                    LoaderTransformChain.downstreamTransformers());
            result.put("loader_identity", LoaderTransformChain.loaderIdentity());
            result.put("writer_ordering", LiveWriterOrdering.measuredOrder());
            String chainProperty = System.getProperty("rustcraft.transformationChain");
            if (chainProperty != null && chainProperty.length() > 0) {
                try {
                    String document = LoaderTransformChain.forThisLaunch().render();
                    Path chainOut = Paths.get(chainProperty);
                    if (chainOut.getParent() != null) Files.createDirectories(chainOut.getParent());
                    Files.write(chainOut, document.getBytes(StandardCharsets.UTF_8));
                    result.put("transformation_chain_status", "RENDERED");
                    result.put("transformation_chain_sha256", LiveHookSupport.sha256(
                            document.getBytes(StandardCharsets.UTF_8)));
                } catch (Throwable incomplete) {
                    // A chain that cannot be proven is not an error here: the
                    // receipt says exactly why, and the engine reads an absent
                    // chain as INCOMPLETE rather than as a defect.
                    result.put("transformation_chain_status", "INCOMPLETE: " + incomplete);
                }
            }
            // Why each definition stands where it does.
            List<Object> acquisitionRows = new ArrayList<Object>();
            if (LiveHookSupport.boundAcquisition() != null) {
                for (SameProcessAcquisition.Definition definition
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
            // The engine does not take the writers' word for any class identity:
            // it reparses the bytes itself, so the pre-writer and post-writer
            // buffers leave this process as FILES, from the same records the
            // chain is built from.
            String observationDir = System.getProperty("rustcraft.observationDir");
            if (observationDir != null && observationDir.length() > 0
                    && LiveHookSupport.boundAcquisition() != null) {
                Path root = Paths.get(observationDir);
                for (SameProcessAcquisition.Definition definition
                        : LiveHookSupport.boundAcquisition().definitions()) {
                    String relative = definition.binaryName.replace('.', '/') + ".class";
                    writeClass(root.resolve("pre").resolve(relative), definition.preWriterBytes);
                    writeClass(root.resolve("classes").resolve(relative),
                            definition.postWriterBuffer());
                }
                result.put("observation_dir", observationDir);
            }
            // Classes the writers could not admit this launch, with reasons.
            // The engine fails the missing hook placements from this record:
            // integrity lives in the checks, not in a crashed server.
            result.put("writer_non_admissions",
                    new TreeMap<String, String>(LiveHookSupport.WRITER_NON_ADMISSIONS));
            result.put("entry_observer_failures", new TreeMap<String, String>(
                    LoaderTransformChain.EntryObserver.failures()));
            // The frame/hierarchy witness is the GENERIC one, the same machinery
            // Clean Forge uses, running in this process against this loader and
            // these defined buffers. It is not a second, weaker proof.
            String frameTypes = System.getProperty("rustcraft.frameTypes");
            String frameQueries = System.getProperty("rustcraft.frameQueries");
            if (frameTypes != null && frameQueries != null) {
                try {
                    result.put("frame_relation_witness",
                            com.rustcraft.livetransformer.FrameRelationWitness.run(
                                    System.getProperty("rustcraft.definedDump"),
                                    agentHashes(), frameTypes, frameQueries));
                    result.put("frame_witness", "GENERIC_FRAME_RELATION_WITNESS_V1");
                } catch (Throwable incomplete) {
                    result.put("frame_relation_witness_status", "INCOMPLETE: " + incomplete);
                }
            } else {
                result.put("frame_relation_witness_status", "INCOMPLETE_NO_FRAME_INPUTS");
            }
            result.put("loader_definition_witness", LoaderDefinitionWitness.witness(
                    agentHashes(), agentLoaders(), LoaderTransformChain.loaderIdentity(),
                    LiveHookSupport.boundAcquisition()));
            String outProperty = System.getProperty("rustcraft.qualificationResult");
            if (outProperty != null && outProperty.length() > 0) {
                Path out = Paths.get(outProperty);
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                Files.write(out, json(result).getBytes(StandardCharsets.UTF_8));
                System.out.println("RUSTCRAFT_SESSION_EVIDENCE_FLUSH_COMPLETE "
                        + (result.containsKey("profile") ? result.get("profile") : ""));
            }
        } catch (Throwable failure) {
            System.err.println("[RustCraft] session evidence flush failed: " + failure);
            try {
                result.put("flush_failure", String.valueOf(failure));
                String outProperty = System.getProperty("rustcraft.qualificationResult");
                if (outProperty != null && outProperty.length() > 0) {
                    Path out = Paths.get(outProperty);
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.write(out, json(result).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
        }
    }

    /** The passive agent's definition hashes, via its own static accessor. */
    public static java.util.Map<String, String> agentHashes() {
        try {
            Class<?> agent = Class.forName("com.rustcraft.offline.agent.ObservationAgent");
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> hashes =
                    (java.util.Map<String, String>) agent.getMethod("hashes").invoke(null);
            return hashes;
        } catch (Throwable absent) {
            return new java.util.LinkedHashMap<String, String>();
        }
    }

    public static java.util.Map<String, String> agentLoaders() {
        try {
            Class<?> agent = Class.forName("com.rustcraft.offline.agent.ObservationAgent");
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> loaders =
                    (java.util.Map<String, String>) agent.getMethod("loaders").invoke(null);
            return loaders;
        } catch (Throwable absent) {
            return new java.util.LinkedHashMap<String, String>();
        }
    }

    /** Transformer names in the loader's actual chain order. */
    public static List<String> transformers() {
        List<String> names = new ArrayList<String>();
        try {
            Object loader = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            java.lang.reflect.Method published = loader.getClass().getMethod("getTransformers");
            for (Object transformer : (Iterable<?>) published.invoke(loader))
                names.add(transformer.getClass().getName());
        } catch (Throwable unavailable) {
            names.add("UNAVAILABLE: " + unavailable);
        }
        return names;
    }

    /** The coremod plugin inventory the runtime actually loaded. */
    public static List<Map<String, String>> coremodPlugins() {
        List<Map<String, String>> coremods = new ArrayList<Map<String, String>>();
        try {
            Field plugins = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager")
                    .getDeclaredField("loadPlugins");
            plugins.setAccessible(true);
            for (Object wrapper : (Iterable<?>) plugins.get(null)) {
                Map<String, String> plugin = new LinkedHashMap<String, String>();
                try {
                    Field instanceField = wrapper.getClass().getDeclaredField("coreModInstance");
                    instanceField.setAccessible(true);
                    Object instance = instanceField.get(wrapper);
                    plugin.put("class", instance == null ? "null"
                            : instance.getClass().getName());
                    plugin.put("location", instance == null ? "null"
                            : String.valueOf(instance.getClass().getProtectionDomain()
                                    .getCodeSource().getLocation()));
                } catch (NoSuchFieldException noInstance) {
                    plugin.put("class", wrapper.getClass().getName() + " (no instance field)");
                }
                coremods.add(plugin);
            }
        } catch (Throwable unavailable) {
            Map<String, String> error = new LinkedHashMap<String, String>();
            error.put("error", String.valueOf(unavailable));
            coremods.add(error);
        }
        return coremods;
    }

    public static Object safeStatic(String className, String field) {
        try {
            Class<?> type = Class.forName(className);
            Field declared = type.getDeclaredField(field);
            declared.setAccessible(true);
            return declared.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Minimal JSON writer (no runtime dependency; the collector reparses). */
    public static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof String || value instanceof Enum<?>) {
            sb.append('"').append(escape(String.valueOf(value))).append('"'); return;
        }
        if (value instanceof Number || value instanceof Boolean) { sb.append(value); return; }
        if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                writeJson(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
            return;
        }
        sb.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void writeClass(Path file, byte[] bytes) {
        try {
            if (bytes == null) return;
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (Exception unwritable) {
            // A class whose buffer could not be written is a gap the engine
            // reports as INCOMPLETE against the missing file — more honest
            // than a receipt claiming a capture no file exists for.
        }
    }
}
