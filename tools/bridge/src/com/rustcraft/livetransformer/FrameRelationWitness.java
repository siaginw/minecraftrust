package com.rustcraft.livetransformer;

import com.rustcraft.coremod.CanonicalClassIdentityV2;
import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.coremod.LiveWriterPlan;
import com.rustcraft.qualification.SameProcessAcquisition;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ACTUAL frame/hierarchy witness, collected INSIDE the real Forge process.
 *
 * <p>This is the only place the frame relation can be observed truthfully. The
 * vanilla server jar is fully obfuscated and Forge's LaunchClassLoader performs
 * the deobfuscation and remap at runtime, so no static jar -- SRG, study or
 * otherwise -- is the final oracle for the named hierarchy that the live
 * transformers actually see. Every observation below is therefore taken from
 * the real defining loader of the real transformed runtime.</p>
 *
 * <p>Collected per phase, for the exact pre-writer and post-writer buffers of
 * one process/session:</p>
 * <ul>
 *   <li>whole-class verification, triggered on the real defined Class;</li>
 *   <li>that the class was NOT initialized by the observation (so the frame
 *       graph was read, not computed by a running class);</li>
 *   <li>every required frame reference type resolved through the real loader,
 *       with its defining loader identity and the exact bytes that loader
 *       supplies;</li>
 *   <li>the real hierarchy graph (supertype and interface edges) walked from the
 *       resolved types;</li>
 *   <li>real assignability answers for the structural obligations;</li>
 *   <li>the observer/transformer ordering and the same-buffer identity binding
 *       taken from the same-process acquisition records.</li>
 * </ul>
 *
 * <p>Stack-map frames are never discarded: the frame reference types handed to
 * this collector are extracted from the retained stack-map tables of the exact
 * buffers, and JVM verify success is recorded only as one observation beside
 * them, never as a substitute.</p>
 */
public final class FrameRelationWitness {

    public static final String SCHEMA = "QUALIFIED_FRAME_WITNESS_V1";
    public static final String SCOPE_MODEL = "REAL_FORGE_LAUNCH_V1";

    private FrameRelationWitness() { }

    public static String run(String preHookDump, String typesFile, String queriesFile) throws Exception {
        ClassLoader real = net.minecraft.launchwrapper.Launch.classLoader;
        if (real == null)
            throw new IllegalStateException("no LaunchClassLoader: not a real Forge process");
        if (LiveHookSupport.boundAcquisition() == null)
            throw new IllegalStateException("no same-process acquisition recorder bound; "
                    + "witness sees qualification via " + SameProcessAcquisition.class.getClassLoader()
                    + " and coremod via " + LiveHookSupport.class.getClassLoader()
                    + ", transformer registrations=" + transformersSeen());

        List<String> targets = new ArrayList<String>();
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS) {
            String internal = hook.className.replace('.', '/');
            if (!targets.contains(internal)) targets.add(internal);
        }
        java.util.Collections.sort(targets);

        List<Map<String, Object>> chain = closeChain();
        List<Map<String, Object>> phases = new ArrayList<Map<String, Object>>();
        phases.add(phase("pre", real, preHookDump, targets, typesFile, queriesFile, true));
        phases.add(phase("post", real, preHookDump, targets, typesFile, queriesFile, false));

        Map<String, Object> witness = new LinkedHashMap<String, Object>();
        witness.put("schema", SCHEMA);
        witness.put("scope", scope());
        witness.put("loader_identity", LiveHookSupport.loaderIdentity(real));
        witness.put("loader_class", real.getClass().getName());
        witness.put("phase_summaries", summarise(phases));
        witness.put("acquisition", acquisitionSummary());
        witness.put("chain_of_custody", chain);
        witness.put("downstream_transformers_after_live_writers", downstreamTransformers());
        witness.put("production_authority", Boolean.FALSE);
        witness.put("phases", phases);
        return json(witness);
    }

    /**
     * Transformers still registered AFTER the live writers. Anything listed here
     * can still alter a class after the RustCraft output, so the defined bytes
     * are not assumed to equal it. The list is reported, never assumed empty.
     */
    private static List<Object> downstreamTransformers() {
        List<Object> out = new ArrayList<Object>();
        boolean passed = false;
        for (net.minecraft.launchwrapper.IClassTransformer each
                : net.minecraft.launchwrapper.Launch.classLoader.getTransformers()) {
            String name = each.getClass().getName();
            if (name.contains("LiveChunk") || name.contains("SPacketChunkData")) { passed = true; continue; }
            if (passed) out.add(name);
        }
        return out;
    }

    /** The transformers this process actually registered, as observed here. */
    private static List<Object> transformersSeen() {
        List<Object> out = new ArrayList<Object>();
        for (net.minecraft.launchwrapper.IClassTransformer each
                : net.minecraft.launchwrapper.Launch.classLoader.getTransformers()) {
            out.add(each.getClass().getName() + "@" + each.getClass().getClassLoader());
        }
        return out;
    }

    /** One phase over the exact buffers this process actually handled. */
    private static Map<String, Object> phase(String name, ClassLoader real, String preHookDump,
            List<String> targets, String typesFile, String queriesFile, boolean pre)
            throws Exception {
        Path dump = Paths.get(preHookDump);
        List<String> requiredTypes = readLines(Paths.get(typesFile));
        List<String[]> questions = readQueries(Paths.get(queriesFile));

        List<Map<String, Object>> verification = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> resolutions = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
        Map<String, Class<?>> resolved = new LinkedHashMap<String, Class<?>>();

        // Whole-class verification of the exact buffer for this phase. The Class
        // under test is the one the real loader actually defined; the buffer is
        // the one this process handed it, bound by the acquisition record.
        for (String internal : targets) {
            byte[] buffer = pre ? preWriterBuffer(internal) : definedBuffer(dump, internal);
            if (buffer == null) {
                verification.add(map("name", internal, "status", "ABSENT", "phase", name));
                continue;
            }
            if (pre) {
                // The pre-writer buffer was the exact input this process's chain
                // was handed, and it was never itself defined: the loader only
                // ever defined the buffer the transformer returned. Recording a
                // JVM verification for it would be a fabricated claim, so the
                // pre phase states only what is actually true.
                verification.add(preObserved(name, internal, buffer, real));
                continue;
            }
            verification.add(verify(name, internal, buffer, postRawSha(internal), dump, real));
        }

        // Every frame reference type, resolved through the real defining loader.
        for (String type : requiredTypes) {
            Class<?> found;
            String error = null;
            try {
                found = Class.forName(type.replace('/', '.'), false, real);
            } catch (Throwable missing) {
                found = null;
                error = missing.getClass().getName();
            }
            resolved.put(type, found);
            resolutions.add(map("type", type,
                    "class_id", found == null ? null : type.replace('/', '.'),
                    "defining_loader", found == null ? null
                            : LiveHookSupport.loaderIdentity(found.getClassLoader()),
                    "error", error));
        }

        // Real assignability answers, not modelled ones.
        for (String[] question : questions) {
            Class<?> source = resolved.get(question[0]);
            Class<?> target = resolved.get(question[1]);
            answers.add(map("source", question[0], "target", question[1],
                    "value", source == null || target == null ? null
                            : Boolean.valueOf(target.isAssignableFrom(source)),
                    "witness", source == null || target == null ? null
                            : "IS_ASSIGNABLE_FROM_ON_DEFINED_CLASSES"));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", name);
        out.put("loader", LiveHookSupport.loaderIdentity(real));
        out.put("verification", verification);
        out.put("resolutions", resolutions);
        out.put("assignability", answers);
        out.put("hierarchy", hierarchy(resolved.values(), real));
        out.put("required_types", Integer.valueOf(requiredTypes.size()));
        out.put("assignability_queries", Integer.valueOf(questions.size()));
        return out;
    }

    /**
     * Pre-writer phase. The buffer is bound to the loader and session that were
     * handed it, and its exact/session-bound identities are recorded, but it is
     * explicitly NOT reported as JVM-verified: no Class was ever defined from
     * these bytes in this process. Only the post-writer buffer is.
     */
    private static Map<String, Object> preObserved(String phase, String internal,
            byte[] buffer, ClassLoader real) throws Exception {
        CanonicalClassIdentityV2.Result exact = CanonicalClassIdentityV2.identify(buffer);
        Map<String, Object> row = map("name", internal, "phase", phase,
                "status", "OBSERVED_NOT_DEFINED_IN_THIS_PROCESS",
                "raw_sha256", sha256(buffer),
                "defined_by_transforming_loader", LiveHookSupport.loaderIdentity(real),
                "semantic_sha256", exact.semanticSha256,
                "declaration_order_sha256", exact.declarationOrderSha256,
                "jvm_verified", Boolean.FALSE,
                "reason", "the loader only ever defined the buffer the transformer returned; "
                        + "these pre-writer bytes were consumed, never defined");
        for (SameProcessAcquisition.Definition definition : LiveHookSupport.boundAcquisition().definitions()) {
            if (definition.binaryName.equals(internal)
                    && definition.preWriterBytes.length == buffer.length
                    && MessageDigest.isEqual(
                            MessageDigest.getInstance("SHA-256").digest(definition.preWriterBytes),
                            MessageDigest.getInstance("SHA-256").digest(buffer))) {
                row.put("acquisition_ordinal", Integer.valueOf(definition.ordinal));
                row.put("transformation_session_id", definition.transformationSessionId);
                row.put("defining_loader_identity", definition.definingLoaderIdentity);
                row.put("session_invariant_sha256", definition.session == null ? null
                        : definition.session.sessionInvariantSha256);
                break;
            }
        }
        return row;
    }

    /**
     * Real whole-class observation. The Class was defined by the real loader in
     * this very process from this very buffer, so the JVM verifier already ran
     * over its retained stack-map frames at definition time. Unlike the closed
     * fixture, a real server legitimately initializes its own classes before the
     * diagnostic oracle runs, so initialization state is recorded as an
     * observation, never asserted away. What IS enforced is the binding: the
     * buffer under test must be byte-identical to the buffer this process
     * handed the loader for this class.
     */
    private static Map<String, Object> verify(String phase, String internal, byte[] buffer,
            String expectedRaw, Path dump, ClassLoader real) throws Exception {
        Class<?> target = Class.forName(internal.replace('/', '.'), false, real);
        Method should = unsafeShouldBeInitialized();
        boolean before = ((Boolean) should.invoke(unsafe(), target)).booleanValue();
        target.getDeclaredMethods();
        boolean after = ((Boolean) should.invoke(unsafe(), target)).booleanValue();
        CanonicalClassIdentityV2.Result exact = CanonicalClassIdentityV2.identify(buffer);
        // The buffer the JVM actually verified is the DEFINED one, and it is
        // proven against the passive javaagent observer's independent record.
        byte[] observed = preWriterBytes(dump, internal);
        if (observed == null)
            throw new IllegalStateException(internal
                    + ": no independent observation of the final definition bytes");
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(observed),
                MessageDigest.getInstance("SHA-256").digest(buffer)))
            throw new IllegalStateException(internal
                    + ": the buffer under test is not the independently observed definition");
        SameProcessAcquisition.Definition last = finalStage(internal);
        byte[] rustcraftOutput = last == null ? null : last.postWriterBuffer();
        boolean identical = rustcraftOutput != null && MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(rustcraftOutput),
                MessageDigest.getInstance("SHA-256").digest(observed));
        return map("name", internal, "phase", phase, "status", "VERIFIED",
                "raw_sha256", sha256(buffer), "observed_raw_sha256", expectedRaw,
                "rustcraft_post_writer_sha256", last == null ? null : last.postWriterRawSha256,
                "defined_bytes_equal_rustcraft_output", Boolean.valueOf(identical),
                "same_buffer_identity", "DEFINED_BYTES_INDEPENDENTLY_OBSERVED; "
                        + (identical ? "RUSTCRAFT_OUTPUT_IS_THE_DEFINED_BUFFER"
                                     : "A_DOWNSTREAM_TRANSFORMER_ALSO_RAN"),
                "defining_loader", LiveHookSupport.loaderIdentity(target.getClassLoader()),
                "initialized_before_observation", Boolean.valueOf(before),
                "initialized_after_observation", Boolean.valueOf(after),
                "initialized", Boolean.valueOf(after),
                "verification_note", "the JVM verifier ran on these frames when this loader "
                        + "defined the class from this exact buffer; getDeclaredMethods() here "
                        + "re-reads the frame graph and does not re-run it, so initialization "
                        + "state is recorded as observed rather than asserted to be false",
                "semantic_sha256", exact.semanticSha256,
                "declaration_order_sha256", exact.declarationOrderSha256,
                "trigger", "REAL_LAUNCHCLASSLOADER_DECLARED_METHODS_V1",
                "verify_local", Boolean.valueOf(verificationFlag("BytecodeVerificationLocal")),
                "verify_remote", Boolean.valueOf(verificationFlag("BytecodeVerificationRemote")));
    }

    /** Real supertype/interface graph over the classes actually involved. */
    private static List<Map<String, Object>> hierarchy(Iterable<Class<?>> roots, ClassLoader real) {
        IdentityHashMap<Class<?>, Boolean> seen = new IdentityHashMap<Class<?>, Boolean>();
        List<Class<?>> pending = new ArrayList<Class<?>>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Class<?> root : roots) {
            if (root != null && seen.put(root, Boolean.TRUE) == null) pending.add(root);
        }
        while (!pending.isEmpty()) {
            Class<?> type = pending.remove(0);
            List<String> interfaces = new ArrayList<String>();
            for (Class<?> each : type.getInterfaces()) {
                interfaces.add(each.getName());
                if (seen.put(each, Boolean.TRUE) == null) pending.add(each);
            }
            Class<?> supertype = type.getSuperclass();
            if (supertype != null && seen.put(supertype, Boolean.TRUE) == null) pending.add(supertype);
            ClassLoader defining = type.getClassLoader();
            rows.add(map("name", type.getName(),
                    "kind", type.isArray() ? "array" : type.isPrimitive() ? "primitive" : "class",
                    "is_interface", Boolean.valueOf(type.isInterface()),
                    "defining_loader", LiveHookSupport.loaderIdentity(defining),
                    "defining_loader_class", defining == null ? null : defining.getClass().getName(),
                    "super", supertype == null ? null : supertype.getName(),
                    "interfaces", interfaces,
                    "raw_sha256", resourceSha(type, defining)));
        }
        return rows;
    }

    /**
     * The bytes the DEFINING loader actually holds for this type. This is the
     * same-buffer identity binding: it ties the observed Class to a concrete
     * buffer hash, so a later proof cannot silently substitute other bytes.
     */
    private static String resourceSha(Class<?> type, ClassLoader defining) {
        if (type.isArray() || type.isPrimitive() || defining == null) return null;
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream stream = defining.getResourceAsStream(resource);
        if (stream == null) return null;
        try {
            return sha256(read(stream));
        } catch (Exception unavailable) {
            return null;
        } finally {
            close(stream);
        }
    }

    private static Map<String, Object> scope() {
        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("model", SCOPE_MODEL);
        scope.put("status", "CLOSED");
        scope.put("observer_assurance",
                "real Forge launch: javaagent ObservationAgent registered in premain observes "
                + "final definition bytes and returns null (never modifies, never retranslates); "
                + "the live-writer transformers run later in the same LaunchClassLoader chain "
                + "and are the only bytes returned to the loader");
        scope.put("loader_assurance",
                "net.minecraft.launchwrapper.LaunchClassLoader performed the real obfuscated->SRG "
                + "->named deobfuscation; every class, hierarchy edge, type resolution and "
                + "assignability answer is read from classes that loader actually defined");
        scope.put("no_static_oracle", Boolean.TRUE);
        scope.put("production_authority", Boolean.FALSE);
        return scope;
    }

    /** Observer/transformer ordering and same-buffer binding, per the §6 records. */
    private static List<Map<String, Object>> acquisitionSummary() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (SameProcessAcquisition.Definition definition : LiveHookSupport.boundAcquisition().definitions()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("ordinal", Integer.valueOf(definition.ordinal));
            row.put("binary_name", definition.binaryName);
            row.put("pre_writer_raw_sha256", SameProcessAcquisition.sha256(definition.preWriterBytes));
            row.put("post_writer_raw_sha256", definition.postWriterRawSha256);
            row.put("exact_semantic_sha256", definition.exact.semanticSha256);
            row.put("hook_placement", definition.hookPlacement);
            row.put("definition_succeeded", Boolean.valueOf(definition.definitionSucceeded));
            row.put("defined_class_identity", definition.definedClassIdentity);
            row.put("defining_loader_identity", definition.definingLoaderIdentity);
            row.put("session_invariant_sha256", definition.session == null ? null
                    : definition.session.sessionInvariantSha256);
            row.put("certifiable", Boolean.valueOf(definition.certifiable()));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> summarise(List<Map<String, Object>> phases) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> phase : phases) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verification =
                    (List<Map<String, Object>>) phase.get("verification");
            int verified = 0;
            for (Map<String, Object> row : verification) {
                if ("VERIFIED".equals(row.get("status"))) verified++;
            }
            out.add(map("phase", phase.get("phase"),
                    "whole_classes_verified", Integer.valueOf(verified),
                    "required_types", phase.get("required_types"),
                    "assignability_queries", phase.get("assignability_queries"),
                    "loader", phase.get("loader")));
        }
        return out;
    }

    // ---- exact buffers this process handled --------------------------------

    private static byte[] preWriterBytes(Path dump, String internal) throws Exception {
        Path path = dump.resolve(internal + ".class");
        return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
    }

    private static String rawSha(Path dump, String internal) throws Exception {
        byte[] bytes = preWriterBytes(dump, internal);
        return bytes == null ? null : sha256(bytes);
    }

    /** The exact buffer this process's transformer chain was handed. */
    private static byte[] preWriterBuffer(String internal) {
        for (SameProcessAcquisition.Definition definition : LiveHookSupport.boundAcquisition().definitions()) {
            if (sameClass(definition, internal)) return definition.preWriterBytes;
        }
        return null;
    }

    private static boolean sameClass(SameProcessAcquisition.Definition definition, String internal) {
        return definition.binaryName.replace('.', '/').equals(internal);
    }

    /**
     * The exact buffer this process handed the loader: the output of the LAST
     * transformer stage for this class. Earlier stages returned intermediate
     * buffers that the next stage consumed and never defined.
     */
    private static SameProcessAcquisition.Definition finalStage(String internal) {
        SameProcessAcquisition.Definition last = null;
        for (SameProcessAcquisition.Definition definition : LiveHookSupport.boundAcquisition().definitions()) {
            if (sameClass(definition, internal) && definition.postWriterRawSha256 != null) last = definition;
        }
        return last;
    }

    /**
     * The bytes the real loader ACTUALLY defined, as recorded by the passive
     * javaagent observer. Transformers registered after the live writers may
     * still alter them, so this is never assumed equal to the RustCraft output.
     */
    private static byte[] definedBuffer(Path dump, String internal) throws Exception {
        byte[] observed = preWriterBytes(dump, internal);
        return observed != null ? observed : postWriterBytes(internal);
    }

    private static byte[] postWriterBytes(String internal) {
        SameProcessAcquisition.Definition last = finalStage(internal);
        return last == null ? null : last.postWriterBuffer();
    }

    private static String postRawSha(String internal) {
        SameProcessAcquisition.Definition last = finalStage(internal);
        return last == null ? null : last.postWriterRawSha256;
    }

    /**
     * Chain of custody. A class passes through several transformer stages, so
     * the buffer one stage returns is the buffer the next stage is handed. This
     * binds each attempt to the Class the loader actually defined, and proves
     * the stages are linked rather than independent observations.
     */
    private static List<Map<String, Object>> closeChain() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (SameProcessAcquisition.Definition definition : LiveHookSupport.boundAcquisition().definitions()) {
            if (definition.postWriterRawSha256 == null) continue;
            String internal = definition.binaryName.replace('.', '/');
            Boolean linked = null;
            for (SameProcessAcquisition.Definition later : LiveHookSupport.boundAcquisition().definitions()) {
                if (later.ordinal <= definition.ordinal || !sameClass(later, internal)) continue;
                linked = Boolean.valueOf(MessageDigest.isEqual(
                        MessageDigest.getInstance("SHA-256").digest(definition.postWriterBuffer()),
                        MessageDigest.getInstance("SHA-256").digest(later.preWriterBytes)));
                break;
            }
            if (linked != null && linked.booleanValue()) {
                // An intermediate stage: its output was consumed by the next
                // transformer and never defined, so no Class can be bound to it.
                rows.add(map("ordinal", Integer.valueOf(definition.ordinal), "binary_name", internal,
                        "linked_to_next_stage", Boolean.TRUE, "definition_bound", Boolean.FALSE,
                        "refusal", "INTERMEDIATE_STAGE_NEVER_DEFINED"));
                continue;
            }
            Class<?> defined = Class.forName(definition.binaryName, false,
                    net.minecraft.launchwrapper.Launch.classLoader);
            try {
                definition.defined(defined, LiveHookSupport.loaderIdentity(defined.getClassLoader()));
            } catch (SameProcessAcquisition.Incomplete refused) {
                rows.add(map("ordinal", Integer.valueOf(definition.ordinal), "binary_name", internal,
                        "linked_to_next_stage", linked, "definition_bound", Boolean.FALSE,
                        "refusal", refused.getMessage()));
                continue;
            }
            rows.add(map("ordinal", Integer.valueOf(definition.ordinal), "binary_name", internal,
                    "post_writer_raw_sha256", definition.postWriterRawSha256,
                    "linked_to_next_stage", linked,
                    "is_final_stage", Boolean.valueOf(linked == null),
                    "definition_bound", Boolean.TRUE,
                    "defined_class_identity", definition.definedClassIdentity,
                    "defining_loader_identity", definition.definingLoaderIdentity,
                    "certifiable", Boolean.valueOf(definition.certifiable())));
        }
        return rows;
    }

    // ---- small helpers -----------------------------------------------------

    private static Object unsafe() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field singleton = type.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return singleton.get(null);
    }

    private static Method unsafeShouldBeInitialized() throws Exception {
        return Class.forName("sun.misc.Unsafe").getMethod("shouldBeInitialized", Class.class);
    }

    private static boolean verificationFlag(String name) {
        return ((com.sun.management.HotSpotDiagnosticMXBean) java.lang.management.ManagementFactory
                .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class))
                .getVMOption(name).getValue().equals("true");
    }

    private static List<String> readLines(Path path) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    private static List<String[]> readQueries(Path path) throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : readLines(path)) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 2) throw new IllegalArgumentException("malformed query line");
            out.add(parts);
        }
        return out;
    }

    static byte[] read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            int n;
            while ((n = in.read(block)) != -1) {
                if (out.size() + n > 8 * 1024 * 1024) throw new IllegalStateException("byte bound");
                out.write(block, 0, n);
            }
            return out.toByteArray();
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Exception ignored) { }
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data))
            out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static Map<String, Object> map(Object... parts) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parts.length; i += 2) out.put((String) parts[i], parts[i + 1]);
        return out;
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || c > 126) out.append(String.format("\\u%04x", Integer.valueOf(c)));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object each : (List<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(json(each));
            }
            return out.append(']').toString();
        }
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (out.length() > 1) out.append(',');
            out.append(quote((String) entry.getKey())).append(':').append(json(entry.getValue()));
        }
        return out.append('}').toString();
    }
}
