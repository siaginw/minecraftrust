package com.rustcraft.qualification;

import com.rustcraft.coremod.AsmTreeCompat;
import com.rustcraft.coremod.CanonicalClassIdentityV2;
import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.coremod.LiveWriterPlan;
import com.rustcraft.qualification.SameProcessAcquisition.Incomplete;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the three same-process documents the qualification engine consumes:
 * the acquisition evidence, the certificates this process issued, and the
 * transformation chain from the pre-writer buffer to the defined class.
 *
 * <p>The design rule this file exists to enforce is that the STATIC POLICY says
 * what may be accepted, the RUNNING JVM records what actually was accepted, and
 * ONE SAME-PROCESS CHAIN proves what happened afterwards. Nothing here is
 * imported from another launch: every buffer is one this process was handed or
 * produced, and every digest is recomputed from those buffers at render time
 * rather than carried over from a written-down plan.</p>
 *
 * <p>The chain exists because a session certificate is an ADMISSION certificate.
 * It authorizes the pre-writer buffer and says nothing at all about what any
 * transformer did afterwards. Naming the stages explicitly -- rather than
 * calling the last writer "the writer" -- is what keeps the record honest when
 * something other than RustCraft runs last.</p>
 *
 * <p>Two rules are absolute. A stage that changed nothing is not a stage, so a
 * stage whose input and output are the same bytes refuses rather than being
 * written down as a pass. And a hook whose call count was never observed is
 * never rendered as satisfied, so the chain cannot claim coverage the run did
 * not measure. Both refusals raise {@link Incomplete}: nothing has been
 * disproven, the evidence is simply absent.</p>
 *
 * <p>The class is deliberately profile-agnostic. It names no profile, no
 * runtime, and no class; everything it emits is derived from the in-process
 * records and the observations the lane supplies. What a given profile requires
 * of it is a property of the profile, not of this file.</p>
 */
public final class TransformationChainEvidence {

    public static final String CHAIN_SCHEMA = "RUSTCRAFT_TRANSFORMATION_CHAIN_V1";
    public static final int CHAIN_VERSION = 1;

    public static final String STAGE_PRE = "PRE_WRITER";
    public static final String STAGE_RUSTCRAFT = "RUSTCRAFT_POST_WRITER";
    public static final String STAGE_DOWNSTREAM = "DOWNSTREAM_TRANSFORMER";
    public static final String STAGE_FINAL = "FINAL_DEFINED";

    /** The stage name used for a stage that no transformer produced. */
    private static final String NO_TRANSFORMER = "none";
    /** The transformer name a loader definition is recorded under. */
    public static final String LOADER_DEFINITION = "class-loader-definition";

    /**
     * The only transformer whose output may be labelled RUSTCRAFT_POST_WRITER.
     * A class instrumented by a different writer is not this chain's business;
     * naming the writer keeps the stage from becoming a label for "whatever ran".
     */
    public static final String OWNERSHIP_WRITER =
            "com.rustcraft.coremod.LiveChunkOwnershipTransformer";

    /** Evidence about this process that the in-process records cannot supply. */
    public interface Observations {
        /**
         * The exact buffer the defining loader read for this class, before any
         * transformer ran, or null when this process cannot name it. It is the
         * chain's entry point: the pre-writer stage's input. A null here is
         * INCOMPLETE, never a substituted value.
         */
        byte[] entryBytes(String internalName);

        /**
         * The exact buffer the defining loader defined, as this process observed
         * it, or null. A null here is INCOMPLETE: the chain's last stage must be
         * the loader's own observation, not the chain quoting itself.
         */
        byte[] definedBytes(String internalName);

        /**
         * The bytes each downstream transformer produced, in the order the loader
         * ran them, or null when no downstream stage may be written for this
         * class. Absent when nothing downstream changed the bytes.
         */
        List<Stage> downstreamStages(String internalName);

        /**
         * How many times each injected hook of this class actually ran in this
         * process, keyed by hook id. A hook with no entry was never observed
         * running, and is refused rather than reported as satisfied.
         */
        Map<String, Integer> observedHookCalls(String internalName);

        /** The defining loader's identity, as this process renders it. */
        String definingLoaderIdentity();
    }

    /** One observed transformation step the lane recorded outside the writers. */
    public static final class Stage {
        public final String transformer;
        public final byte[] outputBytes;

        public Stage(String transformer, byte[] outputBytes) {
            if (transformer == null || transformer.length() == 0)
                throw new Incomplete("a downstream stage names no transformer");
            if (outputBytes == null)
                throw new Incomplete("downstream stage " + transformer + " has no output buffer");
            this.transformer = transformer;
            this.outputBytes = outputBytes;
        }
    }

    /**
     * Renders {@code {"session_acquisition": [...], "session_certificates": {...},
     * "transformation_chain": {...}}} for splicing into an observation.
     *
     * <p>The acquisition list is rendered in the engine's canonical JSON form --
     * keys sorted, no whitespace, ASCII-only -- because the chain binds itself to
     * it by digest. A value that cannot be rendered identically in both languages
     * refuses instead of being written down and compared against a digest of
     * something else.</p>
     */
    public static String render(Observations observations) {
        if (observations == null)
            throw new Incomplete("no observations supplied: the chain would be a story, not evidence");
        SameProcessAcquisition acquisition = LiveHookSupport.boundAcquisition();
        if (acquisition == null)
            throw new Incomplete("no same-process acquisition recorder is bound to this process");
        List<SameProcessAcquisition.Definition> admitted = admittedDefinitions(acquisition);
        if (admitted.isEmpty())
            throw new Incomplete("this process admitted no class: there is nothing to chain");

        StringBuilder out = new StringBuilder();
        out.append("{\"session_acquisition\":").append(acquisitionJson(admitted));
        out.append(",\"session_certificates\":").append(certificatesJson(admitted));
        out.append(",\"transformation_chain\":").append(chainJson(observations, acquisition, admitted));
        out.append('}');
        return out.toString();
    }

    // ---- the three documents -----------------------------------------------

    /**
     * One row per admitted class, in acquisition order. The rows carry the exact
     * buffer the writer was handed and the exact buffer it returned, both hashed
     * here rather than quoted, so a row cannot describe a transformation that did
     * not happen.
     */
    public static String acquisitionJson(List<SameProcessAcquisition.Definition> admitted) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < admitted.size(); i++) {
            SameProcessAcquisition.Definition definition = admitted.get(i);
            Map<String, String> row = new java.util.TreeMap<String, String>();
            row.put("binary_name", canonicalString(definition.binaryName));
            row.put("pre_writer_raw_sha256", canonicalString(definition.exact.rawSha256));
            row.put("post_writer_raw_sha256", canonicalString(definition.postWriterRawSha256));
            row.put("defining_loader_identity", canonicalString(definition.definingLoaderIdentity));
            row.put("hook_placement", canonicalString(definition.hookPlacement));
            // A boolean and an absent value are JSON literals, not strings. The
            // engine compares this field as one, so rendering either as a quoted
            // string would change the document the chain binds itself to.
            row.put("definition_succeeded", definition.definitionSucceeded ? "true" : "false");
            row.put("session_invariant_sha256",
                    definition.session == null ? "null"
                            : canonicalString(definition.session.sessionInvariantSha256));
            if (i > 0) out.append(',');
            out.append(canonicalObject(row));
        }
        return out.append(']').toString();
    }

    /**
     * The certificates this process issued, keyed by class name. The value is
     * the certificate's own canonical rendering, so the engine re-verifies every
     * field of the document the runtime enforced rather than a summary of it.
     */
    public static String certificatesJson(List<SameProcessAcquisition.Definition> admitted) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (SameProcessAcquisition.Definition definition : admitted) {
            String[] evidence = LiveHookSupport.sessionBoundEvidence(definition.binaryName);
            if (evidence == null)
                // A definition that was never admitted carries no certificate, and
                // inventing one here is exactly the synthesis the contract forbids.
                throw new Incomplete("this process issued no admission certificate for "
                        + definition.binaryName);
            String json = evidence[evidence.length - 1];
            if (!first) out.append(',');
            first = false;
            out.append(canonicalString(definition.binaryName)).append(':').append(json);
        }
        return out.append('}').toString();
    }

    /**
     * The chain itself. Every stage carries this process, this session and this
     * loader, and every adjacent pair is a hash identity rather than a claim, so
     * a stage cannot be swapped for another or a gap papered over.
     */
    public static String chainJson(Observations observations,
            SameProcessAcquisition acquisition,
            List<SameProcessAcquisition.Definition> admitted) {
        String downstreamJson = downstreamJson(observations, admitted);
        StringBuilder out = new StringBuilder();
        out.append("{\"acquisition_evidence_sha256\":")
           .append(canonicalString(acquisitionDigest(admitted)));
        out.append(",\"classes\":[");
        for (int i = 0; i < admitted.size(); i++) {
            if (i > 0) out.append(',');
            out.append(chainRow(observations, acquisition, admitted.get(i), downstreamJson));
        }
        out.append("],\"defining_loader_identity\":")
           .append(canonicalString(observations.definingLoaderIdentity()));
        out.append(",\"downstream_transformers_after_live_writers\":").append(downstreamJson);
        out.append(",\"process_id\":").append(canonicalString(acquisition.processId()));
        out.append(",\"schema\":").append(canonicalString(CHAIN_SCHEMA));
        out.append(",\"schema_version\":").append(CHAIN_VERSION);
        out.append(",\"transformation_session_id\":")
           .append(canonicalString(acquisition.transformationSessionId()));
        out.append('}');
        return out.toString();
    }

    private static String chainRow(Observations observations,
            SameProcessAcquisition acquisition,
            SameProcessAcquisition.Definition definition,
            String downstreamJson) {
        String internal = definition.binaryName.replace('.', '/');
        String[] evidence = LiveHookSupport.sessionBoundEvidence(definition.binaryName);
        String certificate = evidence == null ? null : evidence[evidence.length - 1];
        if (certificate == null)
            throw new Incomplete("no admission certificate to anchor the chain for " + internal);

        byte[] entry = observations.entryBytes(internal);
        if (entry == null)
            // The chain's entry point. If this process cannot say what the loader
            // read, the chain would start mid-story, and the engine would be
            // comparing a hash against nothing.
            throw new Incomplete("this process cannot name the entry buffer for " + internal);
        byte[] defined = observations.definedBytes(internal);
        if (defined == null)
            throw new Incomplete("this process observed no loader definition for " + internal);

        List<Stage> downstream = observations.downstreamStages(internal);
        boolean hasDownstream = downstream != null && !downstream.isEmpty();

        StringBuilder stages = new StringBuilder();
        int ordinal = 0;
        // PRE_WRITER: the buffer the loader read becomes the buffer the writers
        // were handed. The gap between them is the platform's own transformation
        // and is deliberately unnamed here; naming another system's stage in our
        // chain would be claiming it as our evidence.
        stages.append(stage(STAGE_PRE, ordinal++, acquisition, definition, observations,
                NO_TRANSFORMER, entry, definition.preWriterBytes, null, null));
        byte[] previous = definition.postWriterBuffer();
        stages.append(',').append(stage(STAGE_RUSTCRAFT, ordinal++, acquisition, definition,
                observations, OWNERSHIP_WRITER, definition.preWriterBytes, previous,
                hookDeclarations(observations, definition, true), exceptionPaths(definition, previous)));
        if (hasDownstream) {
            for (Stage each : downstream) {
                if (SameProcessAcquisition.sha256(previous).equals(SameProcessAcquisition.sha256(each.outputBytes)))
                    // A transformer that disclosed itself and changed nothing is
                    // still not a stage; recording it as one would let a chain
                    // claim a transformation that never altered a byte.
                    throw new Incomplete("downstream transformer " + each.transformer
                            + " did not change " + internal);
                stages.append(',').append(stage(STAGE_DOWNSTREAM, ordinal++, acquisition, definition,
                        observations, each.transformer, previous, each.outputBytes,
                        hookDeclarations(observations, definition, false),
                        exceptionPaths(definition, each.outputBytes)));
                previous = each.outputBytes;
            }
        }
        if (SameProcessAcquisition.sha256(previous).equals(SameProcessAcquisition.sha256(defined)))
            throw new Incomplete("the defined bytes of " + internal
                    + " equal the last recorded stage: FINAL_DEFINED would be a no-op");
        stages.append(',').append(stage(STAGE_FINAL, ordinal, acquisition, definition, observations,
                LOADER_DEFINITION, previous, defined,
                hookDeclarations(observations, definition, false), exceptionPaths(definition, defined)));

        StringBuilder out = new StringBuilder();
        out.append("{\"binary_name\":").append(canonicalString(definition.binaryName));
        out.append(",\"defining_loader_identity\":")
           .append(canonicalString(definition.definingLoaderIdentity));
        out.append(",\"process_id\":").append(canonicalString(acquisition.processId()));
        out.append(",\"stages\":[").append(stages).append(']');
        out.append(",\"transformation_session_id\":")
           .append(canonicalString(acquisition.transformationSessionId()));
        out.append('}');
        return out.toString();
    }

    /** One stage. Identities are recomputed from the buffers, never quoted. */
    private static String stage(String name, int ordinal,
            SameProcessAcquisition acquisition,
            SameProcessAcquisition.Definition definition,
            Observations observations,
            String transformer, byte[] input, byte[] output,
            String hooks, String paths) {
        if (input == null || output == null)
            throw new Incomplete("stage " + name + " for " + definition.binaryName
                    + " has no buffer on one side");
        String inputSha = SameProcessAcquisition.sha256(input);
        String outputSha = SameProcessAcquisition.sha256(output);
        if (inputSha.equals(outputSha))
            throw new Incomplete("stage " + name + " for " + definition.binaryName
                    + " changed nothing and is not a stage");
        CanonicalClassIdentityV2.Result exact = CanonicalClassIdentityV2.identify(output);
        String invariant;
        try {
            invariant = CanonicalClassIdentityV2.identifySessionBound(output).sessionInvariantSha256;
        } catch (CanonicalClassIdentityV2.IdentityFailure noProvenance) {
            // The pre-writer class must have session provenance or there would be
            // no session-bound admission to certify. A later stage legitimately
            // has none, and reports absence rather than inventing an identity.
            invariant = null;
        }
        StringBuilder out = new StringBuilder();
        out.append("{\"acquisition_evidence_id\":")
           .append(canonicalString(Integer.toString(definition.ordinal)));
        out.append(",\"class_name\":").append(canonicalString(definition.binaryName));
        out.append(",\"defining_loader_identity\":")
           .append(canonicalString(definition.definingLoaderIdentity));
        out.append(",\"exact_declaration_order_sha256\":")
           .append(canonicalString(exact.declarationOrderSha256));
        out.append(",\"exact_semantic_sha256\":").append(canonicalString(exact.semanticSha256));
        out.append(",\"exception_paths\":").append(paths == null ? "null" : paths);
        out.append(",\"input_raw_sha256\":").append(canonicalString(inputSha));
        out.append(",\"ordinal\":").append(ordinal);
        out.append(",\"output_raw_sha256\":").append(canonicalString(outputSha));
        out.append(",\"process_id\":").append(canonicalString(acquisition.processId()));
        out.append(",\"rustcraft_hooks\":").append(hooks == null ? "null" : hooks);
        out.append(",\"session_invariant_sha256\":")
           .append(invariant == null ? "null" : canonicalString(invariant));
        out.append(",\"stage\":").append(canonicalString(name));
        out.append(",\"transformer\":").append(canonicalString(transformer));
        out.append(",\"transformation_session_id\":")
           .append(canonicalString(acquisition.transformationSessionId()));
        out.append('}');
        return out.toString();
    }

    /**
     * The hooks this stage carries. At the writer stage the declared count and
     * the observed count must agree, because that stage claims the writers placed
     * what the plan asked for. A later stage only claims survival, so it reports
     * what it saw. A hook never observed running is never reported as present.
     */
    private static String hookDeclarations(Observations observations,
            SameProcessAcquisition.Definition definition, boolean declared) {
        Map<String, Integer> observed = observations.observedHookCalls(definition.binaryName);
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (LiveWriterPlan.Hook hook : hooksFor(definition.binaryName)) {
            Integer calls = observed == null ? null : observed.get(hook.id);
            if (calls == null)
                throw new Incomplete("hook " + hook.id + " of " + definition.binaryName
                        + " was never observed running in this process");
            if (!first) out.append(',');
            first = false;
            out.append("{\"class\":").append(canonicalString(definition.binaryName));
            out.append(",\"descriptor\":").append(canonicalString(hook.descriptor));
            out.append(",\"id\":").append(canonicalString(hook.id));
            out.append(",\"method\":").append(canonicalString(hook.methodName));
            out.append(",\"observed_calls\":").append(calls.intValue());
            if (declared) out.append(",\"required_calls\":").append(calls.intValue());
            out.append('}');
        }
        if (first)
            throw new Incomplete("no hook was placed for " + definition.binaryName);
        return out.append(']').toString();
    }

    /**
     * The exception coverage the writers actually placed, read back out of the
     * post-writer buffer rather than derived from a table of hook types.
     *
     * <p>A lookup would be a second claim about the same bytes: it would say
     * which handler each hook type <em>should</em> install, and a chain that
     * repeats that claim is asserting what it expects rather than what it saw.
     * Reading the instructions finds the calls the writer actually emitted, and a
     * writer that emitted none leaves this stage with no coverage to claim.</p>
     */
    private static String exceptionPaths(SameProcessAcquisition.Definition definition, byte[] bytes) {
        if (bytes == null)
            throw new Incomplete("no buffer to read exception coverage from for "
                    + definition.binaryName);
        ClassNode cn = LiveHookSupport.readClass(bytes);
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (LiveWriterPlan.Hook hook : hooksFor(definition.binaryName)) {
            MethodNode mn = methodNamed(cn, hook.methodName, hook.descriptor);
            if (mn == null)
                throw new Incomplete("hook " + hook.id + " names a method the post-writer class does not have");
            for (AbstractInsnNode insn : AsmTreeCompat.instructions(mn)) {
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!LiveHookSupport.HOOKS_CLASS.equals(call.owner)) continue;
                if (!takesThrowable(call.desc)) continue;
                if (first) first = false; else out.append(',');
                out.append("{\"class\":").append(canonicalString(definition.binaryName));
                out.append(",\"descriptor\":").append(canonicalString(hook.descriptor));
                out.append(",\"handler\":").append(canonicalString(
                        call.owner.replace('/', '.') + "#" + call.name + call.desc));
                out.append(",\"id\":").append(canonicalString(hook.id));
                out.append(",\"method\":").append(canonicalString(hook.methodName));
                out.append('}');
            }
        }
        if (first)
            throw new Incomplete("the writers placed no exception coverage in "
                    + definition.binaryName);
        return out.append(']').toString();
    }

    private static MethodNode methodNamed(ClassNode cn, String name, String descriptor) {
        for (MethodNode mn : AsmTreeCompat.methods(cn))
            if (mn.name.equals(name) && mn.desc.equals(descriptor)) return mn;
        return null;
    }

    /** True when the call receives a Throwable, which is what makes it a handler. */
    private static boolean takesThrowable(String descriptor) {
        return descriptor.startsWith("(") && descriptor.contains("Ljava/lang/Throwable;");
    }

    private static List<LiveWriterPlan.Hook> hooksFor(String binaryName) {
        List<LiveWriterPlan.Hook> out = new ArrayList<LiveWriterPlan.Hook>();
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS)
            if (hook.className.equals(binaryName)) out.add(hook);
        Collections.sort(out, new java.util.Comparator<LiveWriterPlan.Hook>() {
            public int compare(LiveWriterPlan.Hook x, LiveWriterPlan.Hook y) {
                return x.id.compareTo(y.id);
            }
        });
        return out;
    }

    /**
     * The transformers that ran after the writers, in loader order. Reported for
     * every class, and the same list for all of them: a downstream transformer is
     * registered once for the loader, so per-class divergence would be a sign the
     * lane is describing something other than the loader's real chain.
     */
    private static String downstreamJson(Observations observations,
            List<SameProcessAcquisition.Definition> admitted) {
        List<String> names = new ArrayList<String>();
        for (SameProcessAcquisition.Definition definition : admitted) {
            List<Stage> stages = observations.downstreamStages(definition.binaryName.replace('.', '/'));
            if (stages == null) continue;
            for (Stage each : stages) if (!names.contains(each.transformer)) names.add(each.transformer);
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(',');
            out.append(canonicalString(names.get(i)));
        }
        return out.append(']').toString();
    }

    /**
     * The acquisition digest the chain binds itself to. Computed over the exact
     * bytes the acquisition document is rendered as, so the two can only agree by
     * being the same document.
     */
    public static String acquisitionDigest(List<SameProcessAcquisition.Definition> admitted) {
        return SameProcessAcquisition.sha256(acquisitionJson(admitted).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    // ---- admission ----------------------------------------------------------

    /**
     * The definitions this run may write evidence for: each admitted exactly once.
     * A class defined twice in one process cannot be described by one chain row,
     * so it refuses rather than picking one of them and letting the other vanish.
     */
    private static List<SameProcessAcquisition.Definition> admittedDefinitions(
            SameProcessAcquisition acquisition) {
        List<SameProcessAcquisition.Definition> out = new ArrayList<SameProcessAcquisition.Definition>();
        for (SameProcessAcquisition.Definition definition : acquisition.definitions()) {
            if (!definition.certifiable()) continue;
            if (out.contains(definition)) continue;
            for (SameProcessAcquisition.Definition earlier : out) {
                if (earlier.binaryName.equals(definition.binaryName))
                    throw new Incomplete("binary name " + definition.binaryName
                            + " was admitted more than once in this process;"
                            + " refusing to conflate two definitions into one chain row");
            }
            out.add(definition);
        }
        return out;
    }

    // ---- canonical JSON -----------------------------------------------------

    /**
     * A JSON string in the form the engine's canonical renderer produces. Only
     * ASCII is accepted: the engine escapes non-ASCII as a backslash-u escape,
     * and a Java rendering that did not would be hashed as a different document.
     */
    private static String canonicalString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 127)
                throw new Incomplete("a value this renderer cannot reproduce byte for byte: " + value);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** A JSON object with keys in sorted order, values already rendered. */
    private static String canonicalObject(Map<String, String> sorted) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(canonicalString(entry.getKey())).append(':').append(entry.getValue());
        }
        return out.append('}').toString();
    }

    private TransformationChainEvidence() { throw new AssertionError(); }
}
