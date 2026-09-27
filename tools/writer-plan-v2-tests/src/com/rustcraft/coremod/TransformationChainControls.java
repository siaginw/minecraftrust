package com.rustcraft.coremod;

import com.rustcraft.qualification.SameProcessAcquisition;
import com.rustcraft.qualification.TransformationChainEvidence;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controls for the same-process transformation chain.
 *
 * <p>Every buffer here is a real classfile built or loaded in this JVM, the
 * admission is the real {@link LiveHookSupport} gate reading a generated plan,
 * the certificate is the one this process issues for itself, and the class is
 * really defined by a real loader. The chain the producer renders is therefore a
 * record of what happened, not a description of what should have.</p>
 *
 * <p>The controls that matter most are the refusals. A chain that renders
 * successfully proves very little on its own; what constrains it is that every
 * gap in the evidence -- an unnamed entry buffer, an unobserved hook, a stage
 * that changed nothing -- stops the render rather than being papered over with a
 * value the producer had to invent.</p>
 */
public final class TransformationChainControls {

    private static final String PROCESS = "55555555-5555-4555-8555-555555555555";
    private static final String SESSION = "66666666-6666-4666-8666-666666666666";
    private static final String OTHER_SESSION_UUID = "9a3ccad1-a938-4a64-a7ca-c8c81bef1757";
    private static final String HANDLER = "com/rustcraft/bridge/capture/LiveWriterHooks";
    private static final String HANDLER_DESC = "(Ljava/lang/Object;Ljava/lang/Throwable;)V";
    private static int checks = 0;

    /** A loader that really defines the class, so the bound Class is real. */
    static final class Defining extends ClassLoader {
        Defining() { super(TransformationChainControls.class.getClassLoader()); }
        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] preWriter = Files.readAllBytes(Paths.get(args[0]));
        String out = args[1];
        String internal = SessionBoundFixture.INTERNAL_NAME;
        String binary = internal.replace('/', '.');

        bindEnvironment();
        Defining loader = new Defining();

        // The admission is the real gate against the real generated plan: the
        // policy the plan carries is what authorizes these bytes, and the
        // certificate that comes back is minted here, for this process.
        LiveWriterPlan.Hook planned = hookFor(binary);
        check(planned != null, "the generated plan declares a hook for " + binary);
        check(CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND.equals(planned.identitySchema),
                "the generated plan is session-bound, not exact");
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {planned}, preWriter, loader);
        check(LiveHookSupport.sessionBoundEvidence(binary) != null,
                "this process admitted the class and recorded its evidence");

        // ---- the same-process acquisition ---------------------------------
        SameProcessAcquisition acquisition = new SameProcessAcquisition(PROCESS, SESSION);
        SameProcessAcquisition.bind(acquisition);
        SameProcessAcquisition.Definition definition = acquisition.begin(binary, loader, preWriter);
        acquisition.record(definition);
        byte[] postWriter = instrument(preWriter);
        definition.instrumentedWith(postWriter, SameProcessAcquisition.HookPlacement.PLACED);
        Class<?> returned = loader.define(internal, postWriter);
        definition.defined(returned, SameProcessAcquisition.identityOf(loader));
        check(returned.getClassLoader() == loader, "the class was really defined by that loader");
        check(definition.certifiable(), "the record is complete and certifiable");

        // The entry buffer is a genuinely different buffer that shares this
        // class's session-INVARIANT identity: same structure and same annotation
        // site, different session value. That is the cross-launch fact the
        // policy is built from, and it is why the chain's first stage is a real
        // transformation rather than a formality.
        byte[] entry = SessionBoundFixture.buildWithSession(OTHER_SESSION_UUID);
        CanonicalClassIdentityV2.Result entrySession = CanonicalClassIdentityV2.identifySessionBound(entry);
        check(entrySession.sessionInvariantSha256.equals(definition.session.sessionInvariantSha256),
                "the entry buffer and the admitted buffer share a session-invariant identity");
        check(!SameProcessAcquisition.sha256(entry).equals(definition.exact.rawSha256),
                "the entry buffer is a different buffer from the admitted one");

        final byte[] defined = secondMarker(postWriter);
        final Map<String, Integer> observed = new HashMap<String, Integer>();
        observed.put(planned.id, Integer.valueOf(1));
        final String[] target = {internal};
        TransformationChainEvidence.Observations observations =
                new TransformationChainEvidence.Observations() {
                    public byte[] entryBytes(String name) {
                        return target[0].equals(name) ? entry : null;
                    }
                    public byte[] definedBytes(String name) {
                        return target[0].equals(name) ? defined : null;
                    }
                    public List<TransformationChainEvidence.Stage> downstreamStages(String name) {
                        return null;
                    }
                    public Map<String, Integer> observedHookCalls(String name) {
                        return observed;
                    }
                    public String definingLoaderIdentity() {
                        return SameProcessAcquisition.identityOf(loader);
                    }
                };

        // ---- the chain renders over real evidence -------------------------
        String document = TransformationChainEvidence.render(observations);
        check(document.startsWith("{\"session_acquisition\":") && document.endsWith("}"),
                "the render is one document carrying all three parts");
        check(document.contains("\"schema\":\"" + TransformationChainEvidence.CHAIN_SCHEMA + "\""),
                "the chain declares its schema");
        check(document.contains("\"stage\":\"" + TransformationChainEvidence.STAGE_PRE + "\"")
                && document.contains("\"stage\":\"" + TransformationChainEvidence.STAGE_RUSTCRAFT + "\"")
                && document.contains("\"stage\":\"" + TransformationChainEvidence.STAGE_FINAL + "\""),
                "the chain runs PRE, RUSTCRAFT_POST, FINAL when nothing ran downstream");
        check(!document.contains(TransformationChainEvidence.STAGE_DOWNSTREAM),
                "a chain with no downstream transformer writes no downstream stage");
        check(document.contains(HANDLER.replace('/', '.') + "#writerEnd"),
                "the exception path names a handler read out of the injected bytes");
        check(document.contains("\"required_calls\":1"), "the writer stage declares its call count");
        check(document.contains("\"acquisition_evidence_id\":\"" + definition.ordinal + "\""),
                "each stage names the acquisition record it came from");
        // The FINAL stage's bytes are the loader's own observation, recomputed
        // here from the buffer this process defined, not quoted from the writer.
        check(document.contains("\"output_raw_sha256\":\"" + SameProcessAcquisition.sha256(defined) + "\""),
                "the final stage reports the bytes this loader was handed");
        Files.write(Paths.get(out), document.getBytes(StandardCharsets.UTF_8));

        // ---- the refusals --------------------------------------------------
        refuses("an unnamed entry buffer is not invented", noEntry(observations));
        refuses("defined bytes equal to the last stage are not written as a no-op",
                observationsWithDefined(observations, postWriter));
        refuses("a hook that never ran is not reported as present",
                observationsWithoutCounts(observations));
        refuses("a downstream transformer that changed nothing is not a stage",
                observationsWithDownstream(observations, postWriter));

        // ---- a downstream transformer that DID change bytes ---------------
        // A third distinct buffer: the downstream output has to differ from the
        // writer's output AND from the defined bytes, or one of the two stages
        // around it is a no-op and the producer is right to refuse.
        String withDownstream = TransformationChainEvidence.render(
                observationsWithDownstream(observations, thirdMarker(postWriter)));
        check(withDownstream.contains("\"stage\":\"" + TransformationChainEvidence.STAGE_DOWNSTREAM + "\""),
                "a downstream transformer that changed the bytes becomes its own stage");
        check(withDownstream.contains("example.Downstream"),
                "the downstream stage names the transformer that ran");

        // ---- two definitions of one binary name are not conflated ---------
        SameProcessAcquisition twice = new SameProcessAcquisition(PROCESS, SESSION);
        SameProcessAcquisition.bind(twice);
        // A loader cannot define one name twice, so the second definition comes
        // from a second loader. That is exactly the case the producer must not
        // merge: two successful definitions of one binary name, each with its own
        // buffer, and no basis for choosing between them.
        for (int i = 0; i < 2; i++) {
            byte[] post = i == 0 ? postWriter : defined;
            Defining each = new Defining();
            SameProcessAcquisition.Definition record = twice.begin(binary, each, preWriter);
            twice.record(record);
            record.instrumentedWith(post, SameProcessAcquisition.HookPlacement.PLACED);
            record.defined(each.define(internal, post), SameProcessAcquisition.identityOf(each));
        }
        refuses("two successful definitions of one class are not merged into one row",
                observations);

        System.out.println("PASS TransformationChainControls assertions=" + checks
                + "; PRE -> RUSTCRAFT_POST -> FINAL from one process, no imported evidence");
    }

    // ---- observation variants used by the refusals ------------------------

    private interface Render { String get(); }

    private static void refuses(String what, TransformationChainEvidence.Observations o) {
        refusesChain(what, () -> TransformationChainEvidence.render(o));
    }

    private static void refusesChain(String what, Render render) {
        try {
            render.get();
            check(false, what + " -> unexpectedly rendered");
        } catch (SameProcessAcquisition.Incomplete expected) {
            check(true, what + " (" + expected.getMessage() + ")");
        }
    }

    private static TransformationChainEvidence.Observations noEntry(
            final TransformationChainEvidence.Observations base) {
        return new Delegating(base) {
            public byte[] entryBytes(String name) { return null; }
        };
    }

    private static TransformationChainEvidence.Observations observationsWithDefined(
            final TransformationChainEvidence.Observations base, final byte[] defined) {
        return new Delegating(base) {
            public byte[] definedBytes(String name) { return defined; }
        };
    }

    private static TransformationChainEvidence.Observations observationsWithoutCounts(
            final TransformationChainEvidence.Observations base) {
        return new Delegating(base) {
            public Map<String, Integer> observedHookCalls(String name) {
                return new HashMap<String, Integer>();
            }
        };
    }

    private static TransformationChainEvidence.Observations observationsWithDownstream(
            final TransformationChainEvidence.Observations base, final byte[] downstreamOutput) {
        return new Delegating(base) {
            public List<TransformationChainEvidence.Stage> downstreamStages(String name) {
                List<TransformationChainEvidence.Stage> out =
                        new ArrayList<TransformationChainEvidence.Stage>();
                out.add(new TransformationChainEvidence.Stage("example.Downstream", downstreamOutput));
                return out;
            }
        };
    }

    private static class Delegating implements TransformationChainEvidence.Observations {
        private final TransformationChainEvidence.Observations base;
        Delegating(TransformationChainEvidence.Observations base) { this.base = base; }
        public byte[] entryBytes(String n) { return base.entryBytes(n); }
        public byte[] definedBytes(String n) { return base.definedBytes(n); }
        public List<TransformationChainEvidence.Stage> downstreamStages(String n) {
            return base.downstreamStages(n);
        }
        public Map<String, Integer> observedHookCalls(String n) { return base.observedHookCalls(n); }
        public String definingLoaderIdentity() { return base.definingLoaderIdentity(); }
    }

    // ---- real buffers ------------------------------------------------------

    /**
     * The instrumentation step: a marker field plus a real call into the hook
     * facade that takes a Throwable. The producer reads the handler back out of
     * these bytes, so a handler that is not there cannot be reported.
     */
    private static byte[] instrument(byte[] preWriter) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(preWriter).accept(cn, 0);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "liveWriterAcquisitionMarker", "I", null, Integer.valueOf(1)));
        for (MethodNode mn : cn.methods) {
            if (!SessionBoundFixture.METHOD.equals(mn.name)) continue;
            InsnList call = new InsnList();
            call.add(new InsnNode(Opcodes.ACONST_NULL));
            call.add(new InsnNode(Opcodes.ACONST_NULL));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HANDLER, "writerEnd", HANDLER_DESC,
                    false));
            mn.instructions.insert(call);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    /** A downstream transformer's output: distinct from both neighbours. */
    private static byte[] thirdMarker(byte[] postWriter) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(postWriter).accept(cn, 0);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "downstreamMarker", "I", null, Integer.valueOf(3)));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    /** What the loader defined: the writer's output with one more field. */
    private static byte[] secondMarker(byte[] postWriter) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(postWriter).accept(cn, 0);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "liveWriterDefinitionMarker", "I", null, Integer.valueOf(2)));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static LiveWriterPlan.Hook hookFor(String binary) {
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS)
            if (hook.className.equals(binary)) return hook;
        return null;
    }

    private static void bindEnvironment() {
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY, PROCESS);
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY, SESSION);
        System.setProperty(LiveHookSupport.RUNTIME_PROFILE_PROPERTY, SessionBoundFixture.RUNTIME_PROFILE);
        LiveHookSupport.bindSessionEnvironment(new LiveHookSupport.SystemPropertySessionEnvironment());
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private TransformationChainControls() { }
}
