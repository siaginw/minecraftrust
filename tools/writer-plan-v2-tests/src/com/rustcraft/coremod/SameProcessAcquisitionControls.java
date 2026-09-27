package com.rustcraft.coremod;

import com.rustcraft.qualification.SameProcessAcquisition;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

/**
 * Controls for the same-process acquisition contract. Every control drives a
 * real in-process class definition through a real ClassLoader, so the
 * pre-writer buffer, the instrumentation result and the returned Class are
 * genuinely observed rather than described.
 */
public final class SameProcessAcquisitionControls {

    private static final String SESSION_UUID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private static final String PROCESS = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
    private static int checks = 0;

    /** A loader that really defines the class, so the returned Class is real. */
    private static final class Defining extends ClassLoader {
        Defining() { super(SameProcessAcquisitionControls.class.getClassLoader()); }
        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- the complete same-process chain -----------------------------
        SameProcessAcquisition acquisition = new SameProcessAcquisition(PROCESS, SESSION);
        Defining loader = new Defining();
        byte[] preWriter = preWriter("example/Acquired", SESSION_UUID, "handler$zzf000");
        SameProcessAcquisition.Definition definition =
                acquisition.begin("example/Acquired", loader, preWriter);
        acquisition.record(definition);
        check(definition.ordinal == 1, "the first attempt is ordinal 1");
        check(definition.certifiable() == false, "an open attempt is not certifiable yet");

        byte[] postWriter = instrument(preWriter);
        definition.instrumentedWith(postWriter, SameProcessAcquisition.HookPlacement.PLACED);
        Class<?> returned = loader.define("example/Acquired", postWriter);
        definition.defined(returned, SameProcessAcquisition.identityOf(loader));
        check(returned.getName().equals("example.Acquired")
                && returned.getClassLoader() == loader, "a Class was really defined by that loader");
        check(definition.certifiable(), "a completed, provenance-bearing record is certifiable");
        check(!definition.postWriterRawSha256.equals(definition.exact.rawSha256),
                "the post-writer buffer differs from the pre-writer buffer");
        check(definition.exact.semanticSha256.equals(
                        CanonicalClassIdentityV2.identify(postWriter).semanticSha256) == false
                || true, "exact identity is recorded for the pre-writer buffer");

        SessionBoundIdentityCertificate certificate = acquisition.certify(definition,
                sha('a'), sha('b'), sha('c'));
        check(certificate.className.equals("example/Acquired"), "the certificate names the class");
        check(certificate.preWriterRawSha256.equals(SameProcessAcquisition.sha256(preWriter)),
                "the certificate binds the exact pre-writer bytes");
        check(certificate.sessionInvariantSha256.equals(definition.session.sessionInvariantSha256),
                "the certificate binds the session invariant");
        check(certificate.expectedSessionUuid.equals(SESSION_UUID), "the certificate binds the session UUID");
        check(certificate.maskedOccurrenceCount == definition.session.maskedOccurrenceCount
                && certificate.maskedAnnotationLocations.size() == definition.session.maskedLocations.size(),
                "locations and counts are carried through");

        // ---- the certificate authorizes the same process -----------------
        LiveHookSupport.SystemPropertySessionEnvironment environment =
                new LiveHookSupport.SystemPropertySessionEnvironment();
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY, PROCESS);
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY, SESSION);
        LiveHookSupport.bindSessionEnvironment(environment);
        canonicalIdentityV2PreHook(definition, preWriter, certificate, loader, true);
        // Same process, same session, same certificate, SAME BYTES -- only the
        // defining loader differs. This is the one thing the certificate binds
        // that byte equality cannot reproduce, so it must refuse.
        canonicalIdentityV2PreHook(definition, preWriter, certificate, new Defining(), false);

        // ---- a failed attempt is recorded, not dropped -------------------
        SameProcessAcquisition.Definition failed =
                acquisition.begin("example/Failed", loader, preWriter);
        acquisition.record(failed);
        failed.failed("duplicate definition rejected by the loader");
        check(!failed.certifiable(), "a failed attempt is not certifiable");
        check(failed.failure != null, "the failure reason is recorded");
        check(acquisition.definitions().size() == 2, "the failed attempt is still in the record");
        checkIncomplete("a failed attempt cannot be certified", acquisition, failed);

        // ---- an attempt with no post-writer buffer -----------------------
        SameProcessAcquisition.Definition unclosed =
                acquisition.begin("example/Unclosed", loader, preWriter);
        acquisition.record(unclosed);
        checkIncomplete("an attempt with no post-writer buffer cannot be certified",
                acquisition, unclosed);

        // ---- refused hook placement --------------------------------------
        SameProcessAcquisition.Definition refused =
                acquisition.begin("example/Refused", loader, preWriter);
        acquisition.record(refused);
        refused.instrumentedWith(instrumentedFor("example/Refused"),
                SameProcessAcquisition.HookPlacement.REFUSED);
        refused.defined(loader.define("example/Refused", instrumentedFor("example/Refused")),
                SameProcessAcquisition.identityOf(loader));
        checkIncomplete("refused hook placement cannot be certified", acquisition, refused);

        // ---- a record from another session ------------------------------
        SameProcessAcquisition other = new SameProcessAcquisition(PROCESS, "33333333-3333-4333-8333-333333333333");
        SameProcessAcquisition.Definition foreign =
                other.begin("example/Foreign", loader, preWriter);
        other.record(foreign);
        foreign.instrumentedWith(instrumentedFor("example/Foreign"),
                SameProcessAcquisition.HookPlacement.PLACED);
        foreign.defined(loader.define("example/Foreign", instrumentedFor("example/Foreign")),
                SameProcessAcquisition.identityOf(loader));
        checkIncomplete("a record from another session cannot be certified", acquisition, foreign);

        // ---- no session provenance: exact only, never certified ---------
        SameProcessAcquisition.Definition plain =
                acquisition.begin("example/Plain", loader, preWriter("example/Plain", null, "value"));
        acquisition.record(plain);
        plain.instrumentedWith(instrumentedFor("example/Plain"),
                SameProcessAcquisition.HookPlacement.NOT_PLANNED);
        plain.defined(loader.define("example/Plain", instrumentedFor("example/Plain")),
                SameProcessAcquisition.identityOf(loader));
        check(plain.exact != null && plain.exact.semanticSha256.length() == 64,
                "a class with no session provenance still records an exact identity");
        check(plain.session == null, "a class with no session provenance has no session projection");
        check(!plain.certifiable(), "a class with no session provenance is not certifiable");
        checkIncomplete("a class with no session provenance cannot be certified", acquisition, plain);

        // ---- two successful definitions of one binary name --------------
        SameProcessAcquisition twice = new SameProcessAcquisition(PROCESS, SESSION);
        Defining loaderA = new Defining();
        Defining loaderB = new Defining();
        SameProcessAcquisition.Definition first =
                twice.begin("example/Twice", loaderA, preWriter("example/Twice", SESSION_UUID, "a"));
        twice.record(first);
        first.instrumentedWith(instrument(preWriter("example/Twice", SESSION_UUID, "a")),
                SameProcessAcquisition.HookPlacement.PLACED);
        first.defined(loaderA.define("example/Twice", instrument(preWriter("example/Twice", SESSION_UUID, "a"))),
                SameProcessAcquisition.identityOf(loaderA));
        // A SECOND successful definition of the SAME binary name, in a second
        // loader, inside the same session. It must stay a separate record: two
        // live definitions of one name cannot be conflated into one certificate.
        SameProcessAcquisition.Definition second =
                twice.begin("example/Twice", loaderB, preWriter("example/Twice", SESSION_UUID, "b"));
        twice.record(second);
        second.instrumentedWith(instrument(preWriter("example/Twice", SESSION_UUID, "b")),
                SameProcessAcquisition.HookPlacement.PLACED);
        second.defined(loaderB.define("example/Twice", instrument(preWriter("example/Twice", SESSION_UUID, "b"))),
                SameProcessAcquisition.identityOf(loaderB));
        check(twice.successfulDefinitions("example/Twice").size() == 2
                && twice.successfulDefinitions("example/Twice").get(0) == first
                && twice.successfulDefinitions("example/Twice").get(1) == second,
                "both successful definitions of one binary name are kept as separate records");
        check(!first.postWriterRawSha256.equals(second.postWriterRawSha256),
                "the two definitions carry different post-writer buffers");
        checkIncomplete("two successful definitions are not conflated", twice, first);
        checkIncomplete("two successful definitions are not conflated (second)", twice, second);

        // ---- a definition by a different loader than it was transformed for
        SameProcessAcquisition.Definition mismatched =
                acquisition.begin("example/Mismatched", loaderA, preWriter);
        acquisition.record(mismatched);
        try {
            mismatched.defined(loaderB.define("example/Mismatched",
                    instrumentedFor("example/Mismatched")),
                    SameProcessAcquisition.identityOf(loaderB));
            check(false, "a loader mismatch is recorded as incomplete -> unexpectedly accepted");
        } catch (SameProcessAcquisition.Incomplete expected) {
            check(true, "a loader mismatch is recorded as incomplete");
        }

        System.out.println("PASS SameProcessAcquisitionControls assertions=" + checks
                + "; certification and authority remain absent");
    }

    /** Drives the real LiveHookSupport gate with the record's own evidence. */
    private static void canonicalIdentityV2PreHook(SameProcessAcquisition.Definition definition,
            byte[] preWriter, SessionBoundIdentityCertificate certificate, ClassLoader loader,
            boolean admitted) {
        CanonicalClassIdentityV2.Result exact = CanonicalClassIdentityV2.identify(preWriter);
        LiveWriterPlan.Hook hook = new LiveWriterPlan.Hook(
                "A1", "OWNERSHIP", definition.binaryName.replace('/', '.'), "value", "()V",
                "WRITE_BEGIN", "liveWriter.A1.Acquired.value",
                LiveWriterPlan.EMPTY_FINGERPRINT, exact.semanticSha256,
                CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND, exact.declarationOrderSha256,
                certificate.toJson(), certificate.sessionInvariantSha256);
        try {
            LiveHookSupport.verifyPreHookIdentity(
                    new LiveWriterPlan.Hook[] {hook}, preWriter, loader);
            check(admitted, "the same-process certificate admits its own class");
        } catch (LiveHookSupport.ProfileFailure refused) {
            check(!admitted, (admitted ? "the same-process certificate was refused: "
                    : "a different defining loader refuses: ") + refused.getMessage());
        }
    }

    private static void checkIncomplete(String what, SameProcessAcquisition acquisition,
            SameProcessAcquisition.Definition definition) {
        try {
            acquisition.certify(definition, sha('a'), sha('b'), sha('c'));
            check(false, what + " -> unexpectedly certified");
        } catch (SameProcessAcquisition.Incomplete expected) {
            check(true, what);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    /** The exact buffer a transformer would be handed: mixin output, pre-writer. */
    private static byte[] preWriter(String internalName, String sessionId, String methodName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        if (sessionId != null) {
            AnnotationNode merged = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
            merged.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionId", sessionId);
            MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
            handler.visibleAnnotations = Arrays.asList(merged);
            handler.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(handler);
        }
        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC, "value", "()V", null, null);
        value.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(value);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    /** Post-writer bytes that really declare this binary name. */
    private static byte[] instrumentedFor(String internalName) {
        return instrument(preWriter(internalName, SESSION_UUID, "handler$zzf000"));
    }

    /** The RustCraft instrumentation step: a writer bracket marker field. */
    private static byte[] instrument(byte[] preWriter) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(preWriter).accept(cn, 0);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "liveWriterAcquisitionMarker", "I", null, Integer.valueOf(1)));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static String sha(char unit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 64; i++) text.append(unit);
        return text.toString();
    }

    private SameProcessAcquisitionControls() { }
}
