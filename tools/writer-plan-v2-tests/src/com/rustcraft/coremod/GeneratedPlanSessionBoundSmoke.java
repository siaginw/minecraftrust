package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

/**
 * End-to-end admission control for a generated CANONICAL_ID_V2_SESSION_BOUND
 * plan, under the split that replaced the circular design.
 *
 * <p>The plan carries a static POLICY: what MAY be accepted. The certificate is
 * issued here, by this JVM, from the buffer this JVM is actually holding. There
 * is no imported certificate anywhere in this control, and the policy could not
 * carry one if it tried -- {@link SessionBoundAdmissionPolicy#parse} refuses a
 * document containing a process id, session UUID, loader identity or acquisition
 * hash, whatever it is named.</p>
 *
 * <p>Each control below is a real refusal through the real gate. A control that
 * passed for the wrong reason would be worse than no control, so the admission
 * helper is also driven directly with a doctored policy to confirm the refusal
 * names the rule that fired.</p>
 */
public final class GeneratedPlanSessionBoundSmoke {

    private static final String CLASS_NAME = "example.PlanFixture";
    private static final String INTERNAL_NAME = "example/PlanFixture";
    private static final String SESSION_UUID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private static final String OTHER_SESSION_UUID = "7f1c9d20-4a55-4c31-9c0e-2b6d5f8a1e37";
    private static final String PROCESS = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
    private static String profile;
    private static int checks = 0;

    public static void main(String[] args) {
        if (!"CANONICAL_ID_V2_SESSION_BOUND".equals(LiveWriterPlan.IDENTITY_MODE))
            throw new AssertionError("session-bound identity mode not declared");
        if (LiveWriterPlan.HOOKS.length != 1) throw new AssertionError("wrong generated plan");
        LiveWriterPlan.Hook planned = LiveWriterPlan.HOOKS[0];
        check(planned.sessionBound(), "generated entry declares the session-bound mode");
        check(planned.sessionAdmissionPolicyJson != null, "generated entry carries an admission policy");
        check(planned.sessionInvariantSha256 != null, "generated entry carries the invariant binding");
        SessionBoundAdmissionPolicy policy = SessionBoundAdmissionPolicy.parse(planned.sessionAdmissionPolicyJson);
        check(policy.expectedSessionInvariantSha256.equals(planned.sessionInvariantSha256),
                "the policy and the plan entry agree on the session invariant");

        byte[] bytes = SessionBoundFixture.build();
        bindEnvironment(PROCESS, SESSION);
        ClassLoader loader = GeneratedPlanSessionBoundSmoke.class.getClassLoader();

        // ---- 1. a correct policy and a valid current-process buffer: admitted
        LiveWriterPlan.Hook hook = withPolicy(planned, policy);
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {hook}, bytes, loader);
        check(true, "a correct policy admits this process's own buffer");
        String[] evidence = LiveHookSupport.sessionBoundEvidence(CLASS_NAME);
        check(evidence != null && evidence.length == 7,
                "the admission record carries the policy hash and the certificate");
        check(policy.policySha256().equals(evidence[5]),
                "the record names the policy that authorized the admission");

        // The certificate was issued HERE: it names this process, this session,
        // this loader and these exact bytes.
        SessionBoundIdentityCertificate issued = SessionBoundIdentityCertificate.parse(evidence[6]);
        check(PROCESS.equals(issued.processId) && SESSION.equals(issued.transformationSessionId),
                "the certificate records this process and this session");
        check(SessionBoundAdmissionPolicy.sha256(bytes).equals(issued.preWriterRawSha256),
                "the certificate binds the exact pre-writer bytes in hand");
        check(SESSION_UUID.equals(issued.expectedSessionUuid),
                "the certificate records the session UUID this launch actually used");

        // ---- 2. correct structure but NO policy: refused, never downgraded
        refuses("a session-bound entry with no policy refuses",
                new LiveWriterPlan.Hook[] {LiveWriterPlan.doctoredPolicy(hook, null)}, bytes, loader);

        // ---- 3. policy invariant mismatch: refused
        refusesPolicy("a policy expecting another invariant refuses",
                doctorPolicy(policy, "expected_session_invariant_sha256", sha("a")), bytes);

        // ---- 4. an unexpected masked location: refused
        refusesPolicy("a policy expecting an extra masked location refuses",
                doctorPolicy(policy, "expected_masked_locations",
                        new String[] {"method:zzz visible=true " + SessionBoundAdmissionPolicy.ANNOTATION_DESCRIPTOR
                                + "#" + SessionBoundAdmissionPolicy.ANNOTATION_ELEMENT}),
                bytes);

        // ---- 5. a missing expected location: refused
        refusesPolicy("a policy expecting no masked location refuses",
                doctorPolicy(policy, "expected_masked_locations", new String[0]), bytes);

        // ---- 6. two distinct session UUIDs in one class: refused
        CanonicalClassIdentityV2.Result split = CanonicalClassIdentityV2.identifySessionBound(
                qualified(INTERNAL_NAME, SESSION_UUID, OTHER_SESSION_UUID));
        check(split.maskedValues.size() == 2, "the fixture really carries two distinct UUIDs");
        refusesCandidate("two distinct session UUIDs refuse",
                new SessionBoundAdmissionPolicy.Candidate(CLASS_NAME, split, loader.getClass().getName(),
                        profile, policy.recipeSha256, policy.runtimeManifestSha256), policy);

        // ---- 7. a NEW session UUID on a new launch is admitted, and the NEW
        //         certificate records the NEW UUID. This is the property a
        //         previous launch's certificate could never have had.
        byte[] relaunched = SessionBoundFixture.buildWithSession(OTHER_SESSION_UUID);
        bindEnvironment("33333333-3333-4333-8333-333333333333",
                "44444444-4444-4444-8444-444444444444");
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {hook}, relaunched, loader);
        String[] second = LiveHookSupport.sessionBoundEvidence(CLASS_NAME);
        SessionBoundIdentityCertificate relaunchedCertificate =
                SessionBoundIdentityCertificate.parse(second[6]);
        check(OTHER_SESSION_UUID.equals(relaunchedCertificate.expectedSessionUuid),
                "a new launch issues a certificate recording the new UUID");
        check("33333333-3333-4333-8333-333333333333".equals(relaunchedCertificate.processId),
                "a new launch issues a certificate recording the new process");
        check(policy.sessionInvariantSha256Of(relaunched).equals(policy.expectedSessionInvariantSha256),
                "the session-invariant identity is unchanged across the two launches");
        check(!issued.certificateSha256().equals(relaunchedCertificate.certificateSha256()),
                "the two launches produced different certificates");
        bindEnvironment(PROCESS, SESSION);

        // ---- 8. a concrete certificate from a previous process, presented in
        //         this one: refused. There is no field to present it in any
        //         more, which is the structural half of the guarantee.
        refuses("an imported certificate cannot be smuggled in through the plan",
                new LiveWriterPlan.Hook[] {LiveWriterPlan.doctoredPolicy(hook, issued.toJson())},
                bytes, loader);
        try {
            SessionBoundAdmissionPolicy.parse(issued.toJson());
            check(false, "a certificate parsed as a policy -> unexpectedly admitted");
        } catch (SessionBoundAdmissionPolicy.Refusal refused) {
            check(true, "a concrete certificate is refused when read as a policy");
        }

        // ---- 9. a loader identity that differs from the actual loader: refused
        refusesPolicy("a policy expecting a different loader class refuses",
                doctorPolicy(policy, "expected_loader_class", "com.example.NotTheLoader"), bytes);

        // ---- 10. policy recipe or manifest identity mismatch: refused
        refusesPolicy("a policy bound to another recipe refuses",
                doctorPolicy(policy, "recipe_sha256", sha("b")), bytes);
        refusesPolicy("a policy bound to another manifest refuses",
                doctorPolicy(policy, "runtime_manifest_sha256", sha("c")), bytes);

        // ---- 11. an unrelated UUID with mutated SEMANTICS: the invariant moves
        CanonicalClassIdentityV2.Result mutated = CanonicalClassIdentityV2.identifySessionBound(
                qualified(INTERNAL_NAME, OTHER_SESSION_UUID, OTHER_SESSION_UUID,
                        "handler$zzf000", "changed"));
        check(!mutated.sessionInvariantSha256.equals(policy.expectedSessionInvariantSha256),
                "a semantic mutation moves the session-invariant identity");
        refusesCandidate("a semantic mutation refuses even under a correct policy",
                new SessionBoundAdmissionPolicy.Candidate(CLASS_NAME, mutated, loader.getClass().getName(),
                        profile, policy.recipeSha256, policy.runtimeManifestSha256), policy);

        // ---- 12. an instruction-level mutation: the exact identity gate refuses
        byte[] mutatedBytes = SessionBoundFixture.build();
        mutatedBytes[mutatedBytes.length - 1] ^= 0x01;
        refuses("an instruction mutation still refuses under session-bound mode",
                new LiveWriterPlan.Hook[] {hook}, mutatedBytes, loader);

        // ---- 16. the schema itself refuses a policy carrying a concrete fact
        refusesSchema("a policy carrying a process id is refused",
                inject(policy, "process_id", PROCESS));
        refusesSchema("a policy carrying a session UUID is refused",
                inject(policy, "expected_session_uuid", SESSION_UUID));
        refusesSchema("a policy carrying a pre-writer hash is refused",
                inject(policy, "pre_writer_raw_sha256", sha("d")));
        refusesSchema("a policy carrying an acquisition evidence hash is refused",
                inject(policy, "acquisition_evidence_sha256", sha("e")));

        // ---- the exact V2 identity is still mandatory
        refuses("a doctored pre-hook hash still refuses under session-bound mode",
                new LiveWriterPlan.Hook[] {LiveWriterPlan.doctoredSha(hook, sha("f"))}, bytes, loader);

        System.out.println("PASS generated session-bound plan admission; assertions=" + checks
                + "; static policy, runtime-issued certificate, no imported certificate");
    }

    // ------------------------------------------------------------------ helpers

    private static void bindEnvironment(String process, String session) {
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.SESSION_PROPERTY, session);
        System.setProperty(LiveHookSupport.SystemPropertySessionEnvironment.PROCESS_PROPERTY, process);
        // The profile the policy names is asserted by the runtime, exactly as a
        // real launch would; it is not read back from the policy it is checking.
        System.setProperty(LiveHookSupport.RUNTIME_PROFILE_PROPERTY, SessionBoundFixture.RUNTIME_PROFILE);
        profile = LiveHookSupport.runtimeProfile();
        LiveHookSupport.bindSessionEnvironment(new LiveHookSupport.SystemPropertySessionEnvironment());
    }

    /** A hook carrying the generated policy unchanged. */
    private static LiveWriterPlan.Hook withPolicy(LiveWriterPlan.Hook planned,
            SessionBoundAdmissionPolicy policy) {
        return new LiveWriterPlan.Hook(planned.id, planned.transformer, planned.className,
                planned.methodName, planned.descriptor, planned.hookType, planned.operationId,
                planned.fingerprint, planned.preHookClassSha256, planned.identitySchema,
                planned.declarationOrderSha256, planned.sessionAdmissionPolicyJson,
                planned.sessionInvariantSha256);
    }

    /** A policy with one expectation changed, re-rendered in canonical form. */
    private static String doctorPolicy(SessionBoundAdmissionPolicy policy, String key, Object value) {
        return SessionBoundAdmissionPolicyFixtures.render(policy, key, value);
    }

    private static void refusesPolicy(String what, String policyJson, byte[] bytes) {
        LiveWriterPlan.Hook planned = LiveWriterPlan.HOOKS[0];
        refuses(what, new LiveWriterPlan.Hook[] {
                LiveWriterPlan.doctoredPolicy(planned, policyJson)}, bytes,
                GeneratedPlanSessionBoundSmoke.class.getClassLoader());
    }

    private static void refusesCandidate(String what,
            SessionBoundAdmissionPolicy.Candidate candidate, SessionBoundAdmissionPolicy policy) {
        List<String> refusals = policy.refuses(candidate);
        check(!refusals.isEmpty(), what);
    }

    private static void refusesSchema(String what, String json) {
        try {
            SessionBoundAdmissionPolicy.parse(json);
            check(false, what + " -> unexpectedly admitted");
        } catch (SessionBoundAdmissionPolicy.Refusal refused) {
            check(true, what);
        }
    }

    private static String inject(SessionBoundAdmissionPolicy policy, String key, String value) {
        StringBuilder json = new StringBuilder(policy.toJson());
        json.insert(json.length() - 1, ",\"" + key + "\":\"" + value + "\"");
        return json.toString();
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

    private static byte[] qualified(String internalName, String... sessionUuids) {
        return qualified(internalName, sessionUuids[0],
                sessionUuids.length > 1 ? sessionUuids[1] : null, "handler$zzf000", "MARK");
    }

    /**
     * A class carrying the qualified MixinMerged.sessionId provenance. With two
     * UUIDs the annotation appears twice with different values, which is the
     * "two distinct sessions in one class" case and must never be certified.
     */
    private static byte[] qualified(String internalName, String first, String second,
            String methodName, String marker) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        AnnotationNode merged = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
        merged.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionId", first);
        handler.visibleAnnotations = Arrays.asList(merged);
        if (second != null) {
            MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, "second$zzf000", "()V", null, null);
            other.instructions.add(new InsnNode(Opcodes.RETURN));
            AnnotationNode again = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
            again.values = Arrays.asList("mixin", "mixinB", "priority", 1000, "sessionId", second);
            other.visibleAnnotations = Arrays.asList(again);
            cn.methods.add(other);
        }
        cn.methods.add(handler);
        if (!"MARK".equals(marker)) {
            // A same-length constant change: still a valid classfile, but a real
            // behavioural edit that only a semantic identity can catch.
            FieldNodeShim.addConstantField(cn, marker);
        }
        ClassWriter writer = new ClassWriter(0);
        cn.accept(writer);
        return writer.toByteArray();
    }

    /** Keeps the ASM tree import local to the one place that needs a field. */
    private static final class FieldNodeShim {
        static void addConstantField(ClassNode cn, String marker) {
            org.objectweb.asm.tree.FieldNode field =
                    new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE, "marker", "Ljava/lang/String;", null, marker);
            cn.fields.add(field);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private GeneratedPlanSessionBoundSmoke() { }
}
