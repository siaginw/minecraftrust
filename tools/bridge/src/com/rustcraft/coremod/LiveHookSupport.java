package com.rustcraft.coremod;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Shared fail-closed machinery for the live-writer transformers. Every hook is
 * driven by {@link LiveWriterPlan}: the input class bytes must hash to the
 * qualified pre-hook profile identity, the exact method/descriptor must exist,
 * and the plan's instruction fragments must match the method bytecode in order.
 * Any mismatch throws {@link LiveHookSupport.ProfileFailure} — the class load
 * fails loudly and the runtime is NOT transformed (no partial installation of
 * the required hook set). Duplicate instrumentation (a marker constant already
 * present) is likewise refused.
 *
 * <p>Fragment matching is ORDER-based and offset-free: the plan's BCI numbers
 * are documentation of the qualified pre-hook bytes (verified independently by
 * the javap-based profile lane); this check binds the same instruction anchors
 * as an ordered sequence so no variable-size-opcode approximation can desync.</p>
 */
public final class LiveHookSupport {

    private static final String CANONICAL_NEWLINE = "\n";

    public static final String HOOKS_CLASS = "com/rustcraft/bridge/capture/LiveWriterHooks";
    public static final String MARKER_PREFIX = "liveWriter.";

    /** Raised instead of transforming when the runtime does not match the qualified profile. */
    public static final class ProfileFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ProfileFailure(String detail) {
            super("LIVE_WRITER_PROFILE_FAILURE: " + detail);
        }
    }

    private LiveHookSupport() { }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format("%02x", value & 255));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new ProfileFailure("sha256 unavailable: " + failure);
        }
    }

    /**
     * Runtime identity of the JVM performing the qualification. A session-bound
     * certificate binds one specific process/transformation session and one
     * specific defining loader, so the transforming runtime must identify
     * itself before any session-bound normalization may be considered.
     * The plan supplies both values; a runtime that cannot supply them must
     * leave them null, which makes authorization INCOMPLETE rather than
     * silently permissive.
     */
    public interface SessionEnvironment {
        String processId();
        String transformationSessionId();
        String definingLoaderIdentity(ClassLoader loader);
    }

    private static SessionEnvironment environment;

    /** Binds the runtime identity used by every session-bound authorization. */
    public static void bindSessionEnvironment(SessionEnvironment value) {
        environment = value;
    }

    /**
     * Default runtime session binding. The process and transformation-session
     * identities are supplied by the same-process acquisition stage that
     * recorded the pre-writer bytes; a runtime that cannot supply them reports
     * null, which makes every session-bound admission INCOMPLETE.
     */
    public static final class SystemPropertySessionEnvironment implements SessionEnvironment {
        public static final String PROCESS_PROPERTY = "rustcraft.session.processId";
        public static final String SESSION_PROPERTY = "rustcraft.session.transformationSessionId";

        public String processId() {
            return System.getProperty(PROCESS_PROPERTY);
        }

        public String transformationSessionId() {
            return System.getProperty(SESSION_PROPERTY);
        }

        public String definingLoaderIdentity(ClassLoader loader) {
            if (loader == null) return null;
            // Concrete type + identity hash + own name: two distinct loaders that
            // happen to share a simple name are not conflated. getName() is only
            // available on URLClassLoader, so it is read reflectively through a
            // declared supertype rather than assumed.
            String named = null;
            try {
                if (loader instanceof java.net.URLClassLoader) {
                    java.lang.reflect.Method getName =
                            java.net.URLClassLoader.class.getMethod("getName");
                    Object value = getName.invoke(loader);
                    if (value instanceof String) named = (String) value;
                }
            } catch (Exception unavailable) {
                named = null;
            }
            return loader.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(loader))
                    + "[" + (named == null ? "" : named) + "]";
        }
    }

    /** Finds the plan hooks for one transformer and one transformed class name. */
    public static LiveWriterPlan.Hook[] hooksFor(String transformer, String transformedName) {
        List<LiveWriterPlan.Hook> result = new ArrayList<LiveWriterPlan.Hook>();
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS) {
            if (hook.transformer.equals(transformer) && hook.className.equals(transformedName)) {
                result.add(hook);
            }
        }
        return result.toArray(new LiveWriterPlan.Hook[result.size()]);
    }

    /** Exact pre-hook identity: the source bytes must be the qualified profile's bytes. */
    public static void verifyPreHookIdentity(LiveWriterPlan.Hook[] hooks, byte[] basicClass) {
        verifyPreHookIdentity(hooks, basicClass, null);
    }

    /**
     * Pre-hook identity gate. `basicClass` is the exact pre-writer buffer handed
     * to this transformer for this class; `loader` is the loader that will
     * define it, and is required for -- and only for -- session-bound classes.
     */
    public static void verifyPreHookIdentity(LiveWriterPlan.Hook[] hooks, byte[] basicClass,
            ClassLoader loader) {
        if (hooks == null || hooks.length == 0)
            throw new ProfileFailure("missing pre-hook identity contract");
        String mode = hooks[0] == null ? null : hooks[0].identitySchema;
        String name = hooks[0] == null ? null : hooks[0].className;
        if (name == null || name.length() == 0)
            throw new ProfileFailure("missing pre-hook class identity");
        for (LiveWriterPlan.Hook hook : hooks) {
            if (hook == null || mode == null || !mode.equals(hook.identitySchema)
                    || !name.equals(hook.className))
                throw new ProfileFailure("mixed pre-hook identity contracts");
        }
        if (CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND.equals(mode)) {
            verifySessionBoundIdentity(hooks, basicClass, loader, name);
            return;
        }
        if (CanonicalClassIdentityV2.SCHEMA.equals(mode)) {
            // Both digests come from this exact class buffer. Never fall back to V1/RAW.
            CanonicalClassIdentityV2.Result identity = verifyCanonicalIdentityV2(basicClass,
                    mode, name.replace('.', '/'), hooks[0].preHookClassSha256,
                    hooks[0].declarationOrderSha256);
            for (LiveWriterPlan.Hook hook : hooks) {
                if (!identity.semanticSha256.equals(hook.preHookClassSha256)
                        || !identity.declarationOrderSha256.equals(hook.declarationOrderSha256))
                    throw new ProfileFailure("inconsistent V2 identity in pre-hook plan");
            }
            return;
        }
        if (!"RAW".equals(mode) && !"CANONICAL_ID_V1".equals(mode))
            throw new ProfileFailure("unsupported pre-hook identity schema");
        String actual = "CANONICAL_ID_V1".equals(mode) ? canonicalSha256(basicClass) : sha256(basicClass);
        for (LiveWriterPlan.Hook hook : hooks) {
            String expected = hook.preHookClassSha256;
            if (!expected.equals(actual)) {
                throw new ProfileFailure("class " + hooks[0].className
                        + " does not match the qualified pre-hook profile (expected "
                        + expected + ", observed " + actual + ")");
            }
        }
    }

    /**
     * Session-bound admission, in the mandated order and fail-closed at every
     * step: exact identity, then the session projection, then provenance
     * extraction, then the certificate binding (process, transformation session,
     * defining loader, bytes, exact identity, invariant, session UUID, masked
     * locations and counts). The hook transformation is permitted only after
     * every one of these agrees; any failure throws before a single hook is
     * applied, so a class is never left partially instrumented.
     */
    private static void verifySessionBoundIdentity(LiveWriterPlan.Hook[] hooks, byte[] basicClass,
            ClassLoader loader, String name) {
        // 1. exact CANONICAL_ID_V2 identity, never weakened
        CanonicalClassIdentityV2.Result exact = verifyCanonicalIdentityV2(basicClass,
                CanonicalClassIdentityV2.SCHEMA, name.replace('.', '/'),
                hooks[0].preHookClassSha256, hooks[0].declarationOrderSha256);
        // 2./3. session projection and provenance extraction from the same buffer
        CanonicalClassIdentityV2.Result session;
        try {
            session = CanonicalClassIdentityV2.identifySessionBound(basicClass);
        } catch (CanonicalClassIdentityV2.IdentityFailure malformed) {
            throw (ProfileFailure) new ProfileFailure(
                    "session-bound identity could not be established for " + name
                            + ": " + malformed.getMessage()).initCause(malformed);
        }
        if (!exact.semanticSha256.equals(session.semanticSha256)
                || !exact.declarationOrderSha256.equals(session.declarationOrderSha256)
                || !exact.rawSha256.equals(session.rawSha256))
            throw new ProfileFailure("session projection disagrees with the exact identity for " + name);
        // 4.-8. evidence binding: parse, then verify against the observed runtime
        SessionBoundIdentityCertificate certificate =
                parseSessionCertificate(hooks[0], name);
        for (LiveWriterPlan.Hook hook : hooks) {
            if (!certificate.sessionInvariantSha256.equals(hook.sessionInvariantSha256))
                throw new ProfileFailure("plan entry for " + name
                        + " does not bind the certified session invariant");
            if (hook.sessionCertificateJson == null
                    || !hook.sessionCertificateJson.equals(hooks[0].sessionCertificateJson))
                throw new ProfileFailure("mixed session certificates for " + name);
        }
        if (environment == null)
            throw (ProfileFailure) new ProfileFailure(
                    "no session environment bound; session-bound identity for " + name
                            + " is INCOMPLETE, not authorized").initCause(
                    new SessionBoundIdentityCertificate.Refusal(
                            SessionBoundIdentityCertificate.Refusal.Kind.INCOMPLETE,
                            "NO_SESSION_ENVIRONMENT", "no runtime session identity is bound"));
        authorize(certificate, loader, basicClass, session, name);
    }

    /** Strict certificate extraction; any problem refuses the class load. */
    private static SessionBoundIdentityCertificate parseSessionCertificate(
            LiveWriterPlan.Hook hook, String name) {
        try {
            return SessionBoundIdentityCertificate.parse(hook.sessionCertificateJson);
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            throw (ProfileFailure) new ProfileFailure(
                    "session-bound identity for " + name + " is not certified: "
                            + refused.getMessage()).initCause(refused);
        }
    }

    /**
     * Runs the certificate against freshly observed runtime state and records the
     * exact identity, the session invariant and the provenance. Split out so the
     * negative controls can drive it with doctored observations.
     */
    static void authorize(SessionBoundIdentityCertificate certificate, ClassLoader loader,
            byte[] basicClass, CanonicalClassIdentityV2.Result session, String name) {
        SessionBoundIdentityCertificate.Observation observation =
                new SessionBoundIdentityCertificate.Observation(
                        environment.processId(),
                        environment.transformationSessionId(),
                        loader == null ? null : environment.definingLoaderIdentity(loader),
                        basicClass, session);
        try {
            certificate.authorize(observation);
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            throw (ProfileFailure) new ProfileFailure(
                    "session-bound identity for " + name + " is not authorized: "
                            + refused.getMessage()).initCause(refused);
        }
        if (certificate.sessionInvariantSha256.equals(certificate.exactSemanticSha256))
            throw new ProfileFailure("session certificate for " + name
                    + " does not actually project away a process-bound value");
        SESSION_BOUND_EVIDENCE.put(name, new String[] {
            certificate.exactSemanticSha256,
            certificate.sessionInvariantSha256,
            certificate.expectedSessionUuid,
            certificate.certificateSha256(),
            certificate.definingLoaderIdentity,
        });
    }

    /**
     * Session-bound admission evidence recorded for the qualification engine,
     * keyed by transformed class name. Populated only by a fully authorized
     * admission; an empty entry means session-bound identity was never proven.
     */
    public static final Map<String, String[]> SESSION_BOUND_EVIDENCE =
            new java.util.concurrent.ConcurrentHashMap<String, String[]>();

    /** The last admission record for a class, or null when it was never authorized. */
    public static String[] sessionBoundEvidence(String className) {
        return SESSION_BOUND_EVIDENCE.get(className);
    }

    /**
     * Historical CANONICAL_ID_V1 receipt reproduction ONLY. This lossy format
     * omits behavior-relevant operands and metadata; equal hashes do not prove
     * semantic equivalence. Kept unchanged so existing historical evidence can
     * be inspected. New qualification must use the explicit V2 composite
     * contract; do not regenerate new V1 admission profiles with this method.
     */
    public static String canonicalSha256(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            reader.accept(cn, 0);
            StringBuilder text = new StringBuilder();
            text.append("class ").append(cn.name).append(CANONICAL_NEWLINE);
            text.append("super ").append(cn.superName).append(CANONICAL_NEWLINE);
            List<String> interfaces = new ArrayList<String>(cn.interfaces);
            java.util.Collections.sort(interfaces);
            for (String iface : interfaces) text.append("iface ").append(iface).append(CANONICAL_NEWLINE);
            List<String> fields = new ArrayList<String>();
            for (FieldNode fn : cn.fields) fields.add(fn.desc + " " + fn.name);
            java.util.Collections.sort(fields);
            for (String field : fields) text.append("field ").append(field).append(CANONICAL_NEWLINE);
            List<MethodNode> methods = new ArrayList<MethodNode>(cn.methods);
            java.util.Collections.sort(methods, new java.util.Comparator<MethodNode>() {
                @Override public int compare(MethodNode a, MethodNode b) {
                    int byName = a.name.compareTo(b.name);
                    return byName != 0 ? byName : a.desc.compareTo(b.desc);
                }
            });
            for (MethodNode mn : methods) {
                text.append("method ").append(mn.name).append(mn.desc).append(CANONICAL_NEWLINE);
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof LabelNode || insn.getOpcode() == -1) continue;
                    text.append(describe(insn)).append(CANONICAL_NEWLINE);
                }
                for (TryCatchBlockNode block : mn.tryCatchBlocks) {
                    text.append("catch ").append(block.type == null ? "*" : block.type).append(CANONICAL_NEWLINE);
                }
            }
            return sha256(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw (ProfileFailure) new ProfileFailure(
                    "canonical identity read failed: " + failure).initCause(failure);
        }
    }

    /** Complete V2 identity; callers must retain both semantic and declaration-order hashes. */
    public static CanonicalClassIdentityV2.Result canonicalIdentityV2(byte[] classBytes) {
        return CanonicalClassIdentityV2.identify(classBytes);
    }

    /**
     * Explicit V2 admission API. It accepts no V1 alias, no missing declaration
     * order receipt, and no digest synthesized from unrelated observations.
     * This does not grant packet authority or change the existing profile gate.
     */
    public static CanonicalClassIdentityV2.Result verifyCanonicalIdentityV2(byte[] classBytes,
            String schema, String expectedClass, String expectedSemanticSha256,
            String expectedDeclarationOrderSha256) {
        if (!CanonicalClassIdentityV2.SCHEMA.equals(schema))
            throw new ProfileFailure("V2 identity requires CANONICAL_ID_V2 schema");
        if (expectedSemanticSha256 == null || !expectedSemanticSha256.matches("[0-9a-f]{64}")
                || expectedDeclarationOrderSha256 == null
                || !expectedDeclarationOrderSha256.matches("[0-9a-f]{64}"))
            throw new ProfileFailure("V2 identity requires both complete SHA-256 receipts");
        CanonicalClassIdentityV2.Result actual = canonicalIdentityV2(classBytes);
        if (!actual.className.equals(expectedClass)
                || !actual.semanticSha256.equals(expectedSemanticSha256)
                || !actual.declarationOrderSha256.equals(expectedDeclarationOrderSha256))
            throw new ProfileFailure("V2 composite identity mismatch for " + expectedClass);
        return actual;
    }

    public static void refuseMarkerString(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof LdcInsnNode) {
                    Object constant = ((LdcInsnNode) insn).cst;
                    if (constant instanceof String && ((String) constant).startsWith(MARKER_PREFIX)) {
                        throw new ProfileFailure("class " + cn.name
                                + " already instrumented (marker " + ((String) constant) + ")");
                    }
                }
            }
        }
    }

    public static MethodNode findMethod(ClassNode cn, LiveWriterPlan.Hook hook) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(hook.methodName) && mn.desc.equals(hook.descriptor)) return mn;
        }
        throw new ProfileFailure("method " + hook.className + "." + hook.methodName
                + hook.descriptor + " not found in the runtime class");
    }

    /**
     * Verifies the plan's instruction anchors appear in the method in order, and
     * returns the matched instruction nodes (insertion anchors) in the same order.
     */
    public static List<AbstractInsnNode> verifyAnchorsInOrder(MethodNode mn, LiveWriterPlan.Hook hook) {
        List<AbstractInsnNode> anchors = new ArrayList<AbstractInsnNode>();
        if (!hook.hasFingerprint()) return anchors;
        AbstractInsnNode cursor = mn.instructions.getFirst();
        for (String[] assertion : hook.fingerprint) {
            String expected = assertion[1];
            AbstractInsnNode match = null;
            while (cursor != null) {
                AbstractInsnNode candidate = cursor;
                cursor = cursor.getNext();
                if (candidate instanceof LabelNode || candidate.getOpcode() == -1) continue;
                if (fragmentMatches(describe(candidate), expected)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                throw new ProfileFailure("anchor \"" + expected + "\" for " + hook.id
                        + " not found (in order) in " + hook.className + "." + hook.methodName);
            }
            anchors.add(match);
        }
        return anchors;
    }

    /** Describes one instruction in the profile's fragment vocabulary. */
    static String describe(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            String name = call.name.equals("<init>") ? "\"<init>\"" : call.name;
            return opcodeName(call.getOpcode()) + " " + call.owner + "." + name;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) insn;
            return opcodeName(field.getOpcode()) + " " + field.name + ":" + field.desc;
        }
        if (insn instanceof VarInsnNode) {
            VarInsnNode var = (VarInsnNode) insn;
            if (var.getOpcode() == Opcodes.ALOAD) {
                return var.var <= 3 ? "aload_" + var.var : "aload " + var.var;
            }
        }
        return opcodeName(insn.getOpcode());
    }

    private static String opcodeName(int opcode) {
        switch (opcode) {
            case Opcodes.PUTSTATIC: return "putstatic";
            case Opcodes.PUTFIELD: return "putfield";
            case Opcodes.GETSTATIC: return "getstatic";
            case Opcodes.GETFIELD: return "getfield";
            case Opcodes.INVOKEVIRTUAL: return "invokevirtual";
            case Opcodes.INVOKESPECIAL: return "invokespecial";
            case Opcodes.INVOKESTATIC: return "invokestatic";
            case Opcodes.INVOKEINTERFACE: return "invokeinterface";
            case Opcodes.BASTORE: return "bastore";
            case Opcodes.IASTORE: return "iastore";
            case Opcodes.RETURN: return "return";
            case Opcodes.ARETURN: return "areturn";
            case Opcodes.IRETURN: return "ireturn";
            case Opcodes.IFNONNULL: return "ifnonnull";
            case Opcodes.IFNULL: return "ifnull";
            case Opcodes.ALOAD: return "aload";
            case Opcodes.DUP: return "dup";
            case Opcodes.ACONST_NULL: return "aconst_null";
            case Opcodes.ATHROW: return "athrow";
            case Opcodes.MONITORENTER: return "monitorenter";
            case Opcodes.MONITOREXIT: return "monitorexit";
            default: return "op" + opcode;
        }
    }

    private static boolean fragmentMatches(String observed, String expected) {
        int space = expected.indexOf(' ');
        String eOpcode = space < 0 ? expected : expected.substring(0, space);
        String eRest = space < 0 ? "" : expected.substring(space + 1);
        String observedOpcode = observed.contains(" ")
                ? observed.substring(0, observed.indexOf(' ')) : observed;
        String observedRest = observed.contains(" ")
                ? observed.substring(observed.indexOf(' ') + 1) : "";
        if (!observedOpcode.equals(eOpcode)) return false;
        return eRest.isEmpty() || observedRest.contains(eRest);
    }

    /** Refuses when a label (potential branch target) sits immediately before the anchor. */
    public static void requireNoLabelBefore(MethodNode mn, AbstractInsnNode anchor, String what) {
        AbstractInsnNode previous = anchor.getPrevious();
        while (previous instanceof org.objectweb.asm.tree.FrameNode
                || previous instanceof org.objectweb.asm.tree.LineNumberNode) {
            previous = previous.getPrevious();
        }
        if (previous instanceof LabelNode) {
            throw new ProfileFailure("insertion point for " + what
                    + " is a branch-target boundary; refusing");
        }
    }

    /** Whole-method writer bracket: begin at entry, end before every return, catch-all end+rethrow. */
    public static void injectWriterBracket(MethodNode mn, String opId) {
        InsnList beginArgs = new InsnList();
        if ((mn.access & Opcodes.ACC_STATIC) != 0) {
            beginArgs.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            beginArgs.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        beginArgs.add(new LdcInsnNode(opId));
        injectScopeBracket(mn, "writerBegin",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", beginArgs, "writerEnd");
    }

    /**
     * Generic scope bracket: `scope = begin(args)` at entry; `end(scope, null)`
     * before every return; catch-all `end(scope, ex); athrow ex`. The scope local
     * is pre-initialized to null, and the facade tolerates a null scope, so an
     * exception during the begin call itself cannot leak or read garbage. When
     * two brackets nest, inject the INNER bracket first: its handler must precede
     * the outer handler in the exception-table order, while its begin lands after
     * the outer begin (the outer begin is inserted at the new list head).
     */
    public static void injectScopeBracket(MethodNode mn, String beginMethod, String beginDescriptor,
                                          InsnList beginArgs, String endMethod) {
        int scopeLocal = mn.maxLocals;
        mn.maxLocals += 1;
        int exLocal = mn.maxLocals;
        mn.maxLocals += 1;

        // Order matters for BOTH the verifier and the exception table:
        //   [token = null]  →  tryStart  →  [token = begin(args)]
        // The try range starts AFTER the pre-init (ACONST_NULL/ASTORE cannot throw),
        // so the handler's merged token local is WriterScope|null, never TOP.
        // One well-formed prefix block spliced at the method head:
        //   [token = null]  →  tryStart  →  [token = begin(args)]
        // The try range starts AFTER the pre-init (ACONST_NULL/ASTORE cannot throw),
        // so the handler's merged token local is WriterScope|null, never TOP.
        InsnList prefix = new InsnList();
        prefix.add(new InsnNode(Opcodes.ACONST_NULL));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, scopeLocal));
        LabelNode tryStart = new LabelNode();
        prefix.add(tryStart);
        prefix.add(beginArgs);
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, beginMethod, beginDescriptor, false));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, scopeLocal));
        mn.instructions.insertBefore(mn.instructions.getFirst(), prefix);

        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (isReturn(insn)) {
                InsnList end = new InsnList();
                end.add(new VarInsnNode(Opcodes.ALOAD, scopeLocal));
                end.add(new InsnNode(Opcodes.ACONST_NULL));
                end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, endMethod,
                        "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
                mn.instructions.insertBefore(insn, end);
            }
        }
        LabelNode handler = new LabelNode();
        InsnList handlerCode = new InsnList();
        handlerCode.add(new VarInsnNode(Opcodes.ASTORE, exLocal));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, scopeLocal));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
        handlerCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, endMethod,
                "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
        handlerCode.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.add(handler);
        mn.instructions.add(handlerCode);
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, handler, handler, null));
    }

    private static boolean isReturn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.DRETURN || op == Opcodes.FRETURN;
    }

    /** Injects a marker call at the method entry (before the first instruction). */
    public static void injectEntryMarker(MethodNode mn, String facadeMethod, String descriptor) {
        InsnList marker = new InsnList();
        marker.add(new VarInsnNode(Opcodes.ALOAD, 0));
        marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, facadeMethod, descriptor, false));
        mn.instructions.insertBefore(mn.instructions.getFirst(), marker);
    }

    /** Builds an INVOKESTATIC hook call list from argument-loading instructions. */
    public static InsnList call(String facadeMethod, String descriptor, InsnList argumentLoaders) {
        InsnList list = new InsnList();
        list.add(argumentLoaders);
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, facadeMethod, descriptor, false));
        return list;
    }

    public static InsnList loadLocal(int index) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, index));
        return list;
    }

    public static InsnList loadIntLocal(int index) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ILOAD, index));
        return list;
    }

    public static InsnList loadThisField(String owner, String name, String desc) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new FieldInsnNode(Opcodes.GETFIELD, owner, name, desc));
        return list;
    }

    public static InsnList dup() {
        InsnList list = new InsnList();
        list.add(new InsnNode(Opcodes.DUP));
        return list;
    }

    public static InsnList loadString(String value) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(value));
        return list;
    }

    public static InsnList nullBacking() {
        InsnList list = new InsnList();
        list.add(new InsnNode(Opcodes.ACONST_NULL));
        return list;
    }

    /** Constructor registration: insert after the super <init> call at the top of the constructor. */
    public static void insertAfterSuperCtor(MethodNode mn, InsnList hook) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("<init>".equals(call.name)) {
                    mn.instructions.insert(call, hook);
                    return;
                }
            }
        }
        throw new ProfileFailure("no super constructor call found for registration hook");
    }

    public static byte[] writeClass(ClassNode cn) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Resolve common supers from the SRG study jar index WITHOUT loading
                // classes: loading through the launch loader mid-transform would
                // re-enter this transformer (hash refusal) or double-define classes.
                try {
                    return commonSuper(type1, type2, new java.util.HashSet<String>());
                } catch (Throwable failure) {
                    throw new ProfileFailure("frame computation failed for "
                            + type1 + " / " + type2 + ": " + failure);
                }
            }
        };
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static volatile Map<String, byte[]> srgClassIndex;

    /** Bytes of every class in the SRG study jar (set via -Drustcraft.srgJar). */
    private static synchronized Map<String, byte[]> srgClasses() throws java.io.IOException {
        if (srgClassIndex != null) return srgClassIndex;
        String path = System.getProperty("rustcraft.srgJar");
        if (path == null) throw new IllegalStateException("rustcraft.srgJar property not set");
        Map<String, byte[]> index = new java.util.HashMap<String, byte[]>();
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(path)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] block = new byte[65536];
                    try (java.io.InputStream in = jar.getInputStream(entry)) {
                        int read;
                        while ((read = in.read(block)) > 0) buffer.write(block, 0, read);
                    }
                    index.put(entry.getName().substring(0, entry.getName().length() - 6), buffer.toByteArray());
                }
            }
        }
        srgClassIndex = index;
        return index;
    }

    private static String classSuperName(String type, Map<String, byte[]> index) {
        byte[] bytes = index.get(type);
        if (bytes == null) return null;
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        return reader.getSuperName();
    }

    private static String commonSuper(String type1, String type2, java.util.Set<String> seen) {
        if (type1.equals(type2)) return type1;
        if (type1 == null || type2 == null) return "java/lang/Object";
        Map<String, byte[]> index;
        try {
            index = srgClasses();
        } catch (java.io.IOException failure) {
            throw new ProfileFailure("srg jar index unavailable: " + failure);
        }
        // Ancestors of type1 (class chain + all interfaces, transitively).
        java.util.Set<String> ancestors = new java.util.HashSet<String>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<String>();
        String cursor = type1;
        while (cursor != null && !isInterface(cursor, index)) {
            ancestors.add(cursor);
            cursor = classSuperName(cursor, index);
        }
        collectInterfaces(type1, index, queue, ancestors);
        String walk = type2;
        while (walk != null) {
            if (ancestors.contains(walk)) return walk;
            for (String iface : interfacesOf(walk, index)) {
                if (ancestors.contains(iface)) return iface;
            }
            walk = classSuperName(walk, index);
        }
        return "java/lang/Object";
    }

    private static boolean isInterface(String type, Map<String, byte[]> index) {
        byte[] bytes = index.get(type);
        if (bytes == null) return false;
        return (new org.objectweb.asm.ClassReader(bytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    private static void collectInterfaces(String type, Map<String, byte[]> index,
                                          java.util.Deque<String> queue, java.util.Set<String> out) {
        String cursor = type;
        while (cursor != null) {
            byte[] bytes = index.get(cursor);
            if (bytes == null) return;
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            for (String iface : reader.getInterfaces()) {
                if (out.add(iface)) collectInterfaces(iface, index, queue, out);
            }
            cursor = reader.getSuperName();
        }
    }

    private static List<String> interfacesOf(String type, Map<String, byte[]> index) {
        List<String> result = new ArrayList<String>();
        byte[] bytes = index.get(type);
        if (bytes != null) {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            result.addAll(Arrays.asList(reader.getInterfaces()));
        }
        return result;
    }

    public static ClassNode readClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        return cn;
    }
}
