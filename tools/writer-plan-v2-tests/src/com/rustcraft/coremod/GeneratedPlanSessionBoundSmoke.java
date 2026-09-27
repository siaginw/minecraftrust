package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

/**
 * End-to-end admission control for a generated CANONICAL_ID_V2_SESSION_BOUND
 * plan. The generated plan's own certificate is bound to the acquisition
 * process, so it must NOT admit this unrelated JVM; a certificate issued here,
 * against the runtime that is actually defining the class, must. Structure
 * alone admits nothing.
 */
public final class GeneratedPlanSessionBoundSmoke {

    private static final String SESSION_UUID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private static final String PROCESS = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
        private static int checks = 0;

    public static void main(String[] args) {
        if (!"CANONICAL_ID_V2_SESSION_BOUND".equals(LiveWriterPlan.IDENTITY_MODE))
            throw new AssertionError("session-bound identity mode not declared");
        if (LiveWriterPlan.HOOKS.length != 1) throw new AssertionError("wrong generated plan");
        LiveWriterPlan.Hook planned = LiveWriterPlan.HOOKS[0];
        check(planned.sessionBound(), "generated entry declares the session-bound mode");
        check(planned.sessionCertificateJson != null, "generated entry carries a certificate");
        check(planned.sessionInvariantSha256 != null, "generated entry carries the invariant binding");
        SessionBoundIdentityCertificate.parse(planned.sessionCertificateJson);

        byte[] bytes = qualified("example/PlanFixture");

        // 1. The generated certificate was issued to a different process, loader
        //    and byte buffer. It must refuse here.
        LiveWriterPlan.Hook bound = withIdentity(planned, bytes);
        refuses("the generated certificate does not admit an unrelated runtime",
                new LiveWriterPlan.Hook[]{bound}, bytes);

        // 2. No bound environment at all: INCOMPLETE, never a silent fallback.
        LiveHookSupport.bindSessionEnvironment(null);
        refuses("an unbound session environment is refused",
                new LiveWriterPlan.Hook[]{bound}, bytes);

        // 3. A certificate issued by the runtime that is actually defining the
        //    class admits it.
        LiveHookSupport.SystemPropertySessionEnvironment environment =
                new LiveHookSupport.SystemPropertySessionEnvironment();
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY, SESSION);
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY, PROCESS);
        LiveHookSupport.bindSessionEnvironment(environment);
        ClassLoader loader = GeneratedPlanSessionBoundSmoke.class.getClassLoader();
        CanonicalClassIdentityV2.Result identity =
                CanonicalClassIdentityV2.identifySessionBound(bytes);
        SessionBoundIdentityCertificate certificate =
                SessionBoundIdentityCertificate.issue(PROCESS, SESSION,
                        environment.definingLoaderIdentity(loader), bytes, identity,
                        sha("a"), sha("b"), sha("c"));
        LiveWriterPlan.Hook runtimeBound = new LiveWriterPlan.Hook(bound.id, bound.transformer,
                bound.className, bound.methodName, bound.descriptor, bound.hookType,
                bound.operationId, bound.fingerprint, bound.preHookClassSha256,
                bound.identitySchema, bound.declarationOrderSha256,
                certificate.toJson(), certificate.sessionInvariantSha256);
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {runtimeBound}, bytes, loader);
        check(true, "a runtime-issued certificate admits its own class");

        // 4. The exact V2 identity is still mandatory: a doctored pre-hook hash
        //    refuses even with a valid certificate.
        refuses("a doctored pre-hook hash still refuses under session-bound mode",
                new LiveWriterPlan.Hook[] {LiveWriterPlan.doctoredSha(runtimeBound, sha("d"))}, bytes);

        // 5. Removing the certificate from a session-bound entry is a refusal,
        //    not a downgrade to exact or raw identity.
        refuses("a session-bound entry with no certificate refuses",
                new LiveWriterPlan.Hook[] {LiveWriterPlan.doctoredCertificate(runtimeBound, null)}, bytes);

        // 6. A different defining loader refuses even with the same session UUID.
        refuses("a different defining loader refuses",
                new LiveWriterPlan.Hook[]{runtimeBound}, bytes, new java.net.URLClassLoader(new java.net.URL[0]));

        String[] evidence = LiveHookSupport.sessionBoundEvidence("example.PlanFixture");
        check(evidence != null && evidence.length == 5
                && identity.sessionInvariantSha256.equals(evidence[1])
                && SESSION_UUID.equals(evidence[2]), "admission evidence is recorded");

        System.out.println("PASS generated session-bound plan admission; assertions=" + checks
                + "; qualification and authority remain absent");
    }

    /** Builds a hook bound to this class's own observed identities. */
    private static LiveWriterPlan.Hook withIdentity(LiveWriterPlan.Hook planned, byte[] bytes) {
        CanonicalClassIdentityV2.Result identity =
                CanonicalClassIdentityV2.identifySessionBound(bytes);
        return new LiveWriterPlan.Hook(planned.id, planned.transformer, planned.className,
                planned.methodName, planned.descriptor, planned.hookType, planned.operationId,
                planned.fingerprint, identity.semanticSha256, planned.identitySchema,
                identity.declarationOrderSha256, planned.sessionCertificateJson,
                identity.sessionInvariantSha256);
    }

    private static void refuses(String what, LiveWriterPlan.Hook[] hooks, byte[] bytes) {
        refuses(what, hooks, bytes, GeneratedPlanSessionBoundSmoke.class.getClassLoader());
    }

    private static void refuses(String what, LiveWriterPlan.Hook[] hooks, byte[] bytes,
            ClassLoader loader) {
        try {
            LiveHookSupport.verifyPreHookIdentity(hooks, bytes, loader);
            check(false, what + " -> unexpectedly admitted");
        } catch (LiveHookSupport.ProfileFailure refused) {
            check(true, what);
        }
    }

    private static String sha(String unit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 64; i++) text.append(unit);
        return text.toString();
    }

    private static byte[] qualified(String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        AnnotationNode merged = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
        merged.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionId", SESSION_UUID);
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000", "()V", null, null);
        handler.visibleAnnotations = Arrays.asList(merged);
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(handler);
        ClassWriter writer = new ClassWriter(0);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private GeneratedPlanSessionBoundSmoke() { }
}
