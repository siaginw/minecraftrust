package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

/**
 * Cross-session admission controls: the SAME launch-independent policy
 * admits two DIFFERENT JVM sessions, each through its own fresh certificate,
 * and never across. This is the control matrix for the live-admission model:
 * the policy is reusable, the certificate is not.
 */
public final class SessionCrossSessionControls {

    static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    static final String SESSION_A = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    static final String SESSION_B = "9d3ccad1-a938-4a64-a7ca-c8c81bef1757";
    static final String SESSION_C = "5d1f2b7e-8c4a-4e91-9d3f-6a1e2c9b0e77";
    static final String PROCESS_A = "11111111-1111-4111-8111-111111111111";
    static final String PROCESS_B = "33333333-3333-4333-8333-333333333333";
    static final String TRANSFORM_A = "aaaaaaaa-2222-4222-8222-222222222222";
    static final String TRANSFORM_B = "bbbbbbbb-4444-4444-8444-444444444444";
    static final String LOADER_A = "net.minecraft.launchwrapper.LaunchClassLoader@5a1f1";
    static final String LOADER_B = "net.minecraft.launchwrapper.LaunchClassLoader@7c9d2";
    static final String RECIPE = repeat('a');
    static final String MANIFEST = repeat('b');
    static final String POLICY = repeat('d');
    static final String EVIDENCE = repeat('c');

    static String repeat(char unit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 64; i++) text.append(unit);
        return text.toString();
    }

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        byte[] classA = qualified(SESSION_A);
        byte[] classB = qualified(SESSION_B);
        byte[] classBPrime = qualified(SESSION_C);

        // ---- 1: one policy, two sessions, same invariant projection ------
        String invariantA = CanonicalClassIdentityV2.identifySessionBound(classA).sessionInvariantSha256;
        String invariantB = CanonicalClassIdentityV2.identifySessionBound(classB).sessionInvariantSha256;
        check("1: the launch-independent policy covers both sessions: identical masked invariant",
                invariantA.equals(invariantB), null);
        String rawA = CanonicalClassIdentityV2.identifySessionBound(classA).semanticSha256;
        String rawB = CanonicalClassIdentityV2.identifySessionBound(classB).semanticSha256;
        // ---- 7: raw exact difference alone does not reject ----------------
        check("7: raw exact identities differ across sessions (expected, not a rejection ground)",
                !rawA.equals(rawB), null);

        // ---- 2/3: each session's fresh certificate admits its own ----------
        SessionBoundIdentityCertificate certA = SessionBoundIdentityCertificate.issue(
                PROCESS_A, TRANSFORM_A, LOADER_A, classA,
                CanonicalClassIdentityV2.identifySessionBound(classA),
                RECIPE, MANIFEST, POLICY, EVIDENCE);
        admits("2: session A's fresh certificate admits A's observation", certA,
                PROCESS_A, TRANSFORM_A, LOADER_A, classA);
        SessionBoundIdentityCertificate certB = SessionBoundIdentityCertificate.issue(
                PROCESS_B, TRANSFORM_B, LOADER_B, classB,
                CanonicalClassIdentityV2.identifySessionBound(classB),
                RECIPE, MANIFEST, POLICY, EVIDENCE);
        admits("3: session B's fresh certificate admits B's observation (same policy)",
                certB, PROCESS_B, TRANSFORM_B, LOADER_B, classB);

        // ---- 4/5: cross-session certificates never admit -------------------
        refuses("4: A's certificate does not admit B's process/session/loader/bytes",
                certA, PROCESS_B, TRANSFORM_B, LOADER_B, classB);
        refuses("5: B's certificate does not admit A's process/session/loader/bytes",
                certB, PROCESS_A, TRANSFORM_A, LOADER_A, classA);

        // ---- 6: changed sessionId admits ONLY via a fresh certificate ------
        SessionBoundIdentityCertificate certBPrime = SessionBoundIdentityCertificate.issue(
                PROCESS_B, TRANSFORM_B, LOADER_B, classBPrime,
                CanonicalClassIdentityV2.identifySessionBound(classBPrime),
                RECIPE, MANIFEST, POLICY, EVIDENCE);
        admits("6: a changed Mixin sessionId is admitted through its own fresh certificate",
                certBPrime, PROCESS_B, TRANSFORM_B, LOADER_B, classBPrime);
        refuses("6b: the OLD certificate never admits the changed-session bytes",
                certB, PROCESS_B, TRANSFORM_B, LOADER_B, classBPrime);

        // ---- 8: invariant structural difference rejects --------------------
        byte[] structuralB = structuralVariant(SESSION_B);
        String invariantStructural =
                CanonicalClassIdentityV2.identifySessionBound(structuralB).sessionInvariantSha256;
        check("8a: a structural variant changes the masked invariant",
                !invariantStructural.equals(invariantB), null);
        refuses("8b: the invariant difference rejects even with matching session facts",
                certB, PROCESS_B, TRANSFORM_B, LOADER_B, structuralB);

        // ---- 9: wrong loader rejects ---------------------------------------
        refuses("9: a certificate from loader A never admits loader B's observation",
                SessionBoundIdentityCertificate.issue(PROCESS_A, TRANSFORM_A, LOADER_A, classA,
                        CanonicalClassIdentityV2.identifySessionBound(classA),
                        RECIPE, MANIFEST, POLICY, EVIDENCE),
                PROCESS_A, TRANSFORM_A, LOADER_B, classA);

        // ---- 13/14: recipe/policy hashes are carried and tamper-evident ----
        // The engine rejects stale recipe/policy bindings at its own layer
        // (certificate_must_bind_this_manifest / must_name_the_policy). At
        // the certificate layer the proof is that the bindings are part of
        // the signed document: doctoring either hash changes the digest and
        // the strict parse or the authorization refuses.
        check("13: the certificate carries the recipe hash it was issued under",
                certA.recipeSha256 != null && certA.recipeSha256.equals(RECIPE), null);
        check("14: the certificate names the policy hash that admitted it",
                certA.policySha256 != null && certA.policySha256.equals(POLICY), null);
        // A doctored document re-parses as a self-consistent certificate --
        // that is exactly why the engine refuses to trust a certificate by
        // itself: it compares the REPARSED digest against the digest the
        // acquisition recorded for the issuance. Any field tampering
        // necessarily diverges that digest.
        String doctoredRecipe = certA.toJson()
                .replace("\"" + RECIPE + "\"", "\"" + repeat('e') + "\"");
        check("13b: a doctored recipe hash diverges the recorded digest",
                !SessionBoundIdentityCertificate.parse(doctoredRecipe)
                        .certificateSha256().equals(certA.certificateSha256()), null);
        String doctoredPolicy = certA.toJson()
                .replace("\"" + POLICY + "\"", "\"" + repeat('f') + "\"");
        check("14b: a doctored policy hash diverges the recorded digest",
                !SessionBoundIdentityCertificate.parse(doctoredPolicy)
                        .certificateSha256().equals(certA.certificateSha256()), null);

        // ---- 17: no authority anywhere -------------------------------------
        boolean authorityGrant = false;
        for (java.lang.reflect.Field field : SessionBoundIdentityCertificate.class.getFields())
            if (field.getName().toLowerCase().contains("authority")) authorityGrant = true;
        check("17: the session certificate carries no authority field of any kind",
                !authorityGrant, null);

        System.out.println("SessionCrossSessionControls " + (fail == 0 ? "PASS" : "FAIL")
                + " (" + pass + " assertions)");
        if (fail > 0) System.exit(1);
    }

    static void admits(String name, SessionBoundIdentityCertificate certificate,
                       String process, String transform, String loader, byte[] bytes) {
        try {
            certificate.authorize(new SessionBoundIdentityCertificate.Observation(
                    process, transform, loader, bytes,
                    CanonicalClassIdentityV2.identifySessionBound(bytes)));
            check(name, true, null);
        } catch (Throwable failure) {
            check(name, false, String.valueOf(failure.getMessage()));
        }
    }

    static void refuses(String name, SessionBoundIdentityCertificate certificate,
                        String process, String transform, String loader, byte[] bytes) {
        try {
            certificate.authorize(new SessionBoundIdentityCertificate.Observation(
                    process, transform, loader, bytes,
                    CanonicalClassIdentityV2.identifySessionBound(bytes)));
            check(name, false, "authorized when it must refuse");
        } catch (Throwable expected) {
            check(name, true, null);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println(name + " -> PASS"); }
        else { fail++; System.out.println(name + " -> FAIL " + (detail == null ? "" : detail)); }
    }

    // ---------------------------------------------------------------- fixture

    /** One merged handler carrying the given session UUID: the launch-scoped
     *  provenance a real Mixin writes. */
    static byte[] qualified(String session) {
        ClassNode cn = skeleton();
        cn.name = "sb/CrossSessionFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", session));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode plain = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()I", null, null);
        plain.instructions.add(new InsnNode(Opcodes.ICONST_0));
        plain.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(plain);
        return write(cn);
    }

    /** Same session UUID but a structurally different masked projection. */
    static byte[] structuralVariant(String session) {
        ClassNode cn = skeleton();
        cn.name = "sb/CrossSessionFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", session));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode extra = new MethodNode(Opcodes.ACC_PUBLIC, "structuralMarker", "()J", null, null);
        extra.instructions.add(new InsnNode(Opcodes.LCONST_0));
        extra.instructions.add(new InsnNode(Opcodes.LRETURN));
        cn.methods.add(extra);
        return write(cn);
    }

    static AnnotationNode mixinMerged(String mixin, String session) {
        AnnotationNode node = new AnnotationNode(MIXIN_MERGED);
        node.values = Arrays.<Object>asList("mixin", mixin, "priority", Integer.valueOf(1000),
                "sessionId", session);
        return node;
    }

    static ClassNode skeleton() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC;
        cn.superName = "java/lang/Object";
        cn.interfaces = Arrays.asList();
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(writer);
        return writer.toByteArray();
    }
}
