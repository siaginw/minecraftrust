package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controls A-N for the evidence-bound session certificate.
 *
 * <p>Control A is the only permitted admission path: a structurally valid
 * {@code MixinMerged.sessionId} PLUS a certificate that binds process,
 * transformation session, defining loader, bytes, exact identity, invariant,
 * UUID, locations, counts, recipe, runtime manifest and acquisition evidence.
 * Every other control must refuse.</p>
 *
 * <p>Controls C, D and E isolate individual verifier rules by doctoring a
 * certificate that is otherwise valid for the observed bytes, which proves the
 * verifier does not merely compare the invariant hash. Controls F through N
 * are natural mutations of the classfile or of the observed runtime state.</p>
 */
public final class SessionBoundCertificateControls {

    static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    static final String SESSION_A = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    static final String SESSION_B = "9d3ccad1-a938-4a64-a7ca-c8c81bef1757";
    static final String PROCESS = "11111111-1111-4111-8111-111111111111";
    static final String SESSION = "22222222-2222-4222-8222-222222222222";
    static final String LOADER = "net.minecraft.launchwrapper.LaunchClassLoader@7f3a9c21";
    static final String OTHER_LOADER = "cpw.mods.fml.common.asm.transformers.ASMEventTransformer@1c5d0aa4";
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
        byte[] baseline = qualified();

        // ---- A: valid structure + valid certificate -> allowed -------------
        SessionBoundIdentityCertificate certificate = issue(baseline);
        SessionBoundIdentityCertificate.Observation observation = observe(baseline);
        try {
            certificate.authorize(observation);
            check("A: valid certificate admits the qualified class", true, null);
        } catch (Throwable failure) {
            check("A: valid certificate admits the qualified class", false, String.valueOf(failure.getMessage()));
        }
        check("A: certificate round-trips through strict parsing",
                SessionBoundIdentityCertificate.parse(certificate.toJson())
                        .certificateSha256().equals(certificate.certificateSha256()), null);
        check("A: certificate binds a session invariant, not the exact identity",
                certificate.sessionInvariantSha256.equals(
                        CanonicalClassIdentityV2.identifySessionBound(baseline).sessionInvariantSha256)
                        && !certificate.exactSemanticSha256.equals(certificate.sessionInvariantSha256), null);

        // ---- B: valid structure, NO certificate -> INCOMPLETE --------------
        refuseKind("B: no certificate is INCOMPLETE, not a masked fallback",
                SessionBoundIdentityCertificate.Refusal.Kind.INCOMPLETE,
                null, observation);
        refuseKind("B: a certificate missing required evidence fields is INCOMPLETE",
                SessionBoundIdentityCertificate.Refusal.Kind.INCOMPLETE,
                "{\"schema\":\"" + SessionBoundIdentityCertificate.SCHEMA + "\"}", observation);
        refuseKind("B: a certificate with an unknown field is REFUSED, not ignored",
                SessionBoundIdentityCertificate.Refusal.Kind.REFUSE,
                withExtraField(certificate.toJson(), "unrecognised_binding", jsonString("x")), observation);
        refuseKind("B: a non-JSON certificate is REFUSED, not ignored",
                SessionBoundIdentityCertificate.Refusal.Kind.REFUSE,
                "not-json-at-all", observation);
        check("B: exact CANONICAL_ID_V2 alone yields no session projection",
                CanonicalClassIdentityV2.identify(baseline).sessionInvariantSha256 == null, null);

        // ---- C: correct structure, WRONG expected session UUID -------------
        String wrongUuid = doctorJson(certificate.toJson(), "expected_session_uuid", jsonString(SESSION_B));
        refuseReason("C: wrong expected session UUID refuses", "SESSION_UUID_MISMATCH", wrongUuid, observation);
        SessionBoundIdentityCertificate parsedWrong = SessionBoundIdentityCertificate.parse(wrongUuid);
        check("C: the invariant hash alone does not authorize a wrong session UUID",
                parsedWrong.sessionInvariantSha256.equals(certificate.sessionInvariantSha256)
                        && !parsedWrong.expectedSessionUuid.equals(certificate.expectedSessionUuid), null);
        refuseReason("C: a matching invariant is still refused on the session UUID binding",
                "SESSION_UUID_MISMATCH", wrongUuid, observation);

        // ---- D: correct UUID, UNEXPECTED annotation location ---------------
        String unexpected = "method:absent$handler(I)I visible=true " + MIXIN_MERGED + "#sessionId";
        String doctoredD = doctorJson(certificate.toJson(), "masked_annotation_locations",
                jsonStringArray(new String[] { unexpected }));
        RefusalInfo d = captureRefusal(doctoredD, observation);
        check("D: unexpected certified location refuses", d.refused, d.detail);
        check("D: refusal is an unexpected-location (certified-only) difference",
                d.refused && d.certifiedOnly, d.detail);

        // ---- E: MISSING expected annotation location -----------------------
        String present = "method:handler$zzf000 visible=true " + MIXIN_MERGED + "#sessionId=" + SESSION_A;
        String doctoredE = doctorJson(certificate.toJson(), "masked_annotation_locations",
                jsonStringArray(new String[] { "class:sb/CertFixture visible=false " + MIXIN_MERGED + "#sessionId=" + SESSION_A, present }));
        RefusalInfo e = captureRefusal(doctoredE, observation);
        check("E: missing expected location refuses", e.refused, e.detail);
        check("E: refusal is a missing-location (observed-only) difference",
                e.refused && e.observedOnly, e.detail);

        // ---- F: two different session UUIDs in one qualified class ----------
        byte[] twoSessions = qualifiedWithTwoSessions();
        SessionBoundIdentityCertificate.Observation twoObs = observe(twoSessions);
        refuseReason("F: two qualified session UUIDs refuse", "MULTIPLE_SESSION_UUIDS",
                certificate.toJson(), twoObs);
        check("F: a certificate may not be issued for a two-UUID class",
                issuanceRefuses(twoSessions), null);

        // ---- G: correct sessionId + unrelated UUID constant change ---------
        mutationControl("G: unrelated UUID constant change", certificate, observation, baseline, unrelatedConstant());

        // ---- H: correct sessionId + method instruction change ---------------
        mutationControl("H: method instruction change", certificate, observation, baseline, instructionChange());

        // ---- I: correct sessionId + field/method metadata change -----------
        mutationControl("I: field/method metadata change", certificate, observation, baseline, metadataChange());

        // ---- J: correct sessionId + handler/control-flow change ------------
        mutationControl("J: handler/control-flow change", certificate, observation, baseline, controlFlowChange());

        // ---- K: changed annotation descriptor -> fail closed ---------------
        failsClosed("K: changed annotation descriptor", renamedDescriptor());

        // ---- L: changed annotation element name -> fail closed -------------
        failsClosed("L: changed annotation element name", renamedElement());

        // ---- M: same qualified sessionId, different defining loader --------
        SessionBoundIdentityCertificate.Observation otherLoader = new SessionBoundIdentityCertificate.Observation(
                PROCESS, SESSION, OTHER_LOADER, baseline,
                CanonicalClassIdentityV2.identifySessionBound(baseline));
        refuseReason("M: a different defining loader refuses", "LOADER_MISMATCH",
                certificate.toJson(), otherLoader);

        // ---- N: same binary class name, different successful definition ----
        byte[] secondDefinition = qualifiedSecondDefinition();
        SessionBoundIdentityCertificate.Observation secondObs = observe(secondDefinition);
        refuseReason("N: a second successful definition of the same binary name refuses", "PRE_WRITER_BYTES_MISMATCH",
                certificate.toJson(), secondObs);
        SessionBoundIdentityCertificate secondCertificate = issue(secondDefinition);
        check("N: the two definitions are not conflated into one certificate",
                !secondCertificate.certificateSha256().equals(certificate.certificateSha256())
                        && !secondCertificate.exactSemanticSha256.equals(certificate.exactSemanticSha256),
                secondCertificate.certificateSha256());

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ rules

    /** A behaviour-changing mutation must change the invariant and refuse admission. */
    static void mutationControl(String name, SessionBoundIdentityCertificate certificate,
            SessionBoundIdentityCertificate.Observation observation, byte[] baseline, byte[] mutated) {
        CanonicalClassIdentityV2.Result after = CanonicalClassIdentityV2.identifySessionBound(mutated);
        check("control " + name + " changes the invariant identity",
                !certificate.sessionInvariantSha256.equals(after.sessionInvariantSha256),
                after.sessionInvariantSha256);
        check("control " + name + " changes the exact identity",
                !certificate.exactSemanticSha256.equals(after.semanticSha256), null);
        RefusalInfo info = captureRefusal(certificate.toJson(), observe(mutated));
        check("control " + name + " refuses admission", info.refused, info.detail);
        check("control " + name + " refuses even though the session UUID is identical",
                info.refused && SESSION_A.equals(after.maskedValues.get(0)), info.detail);
    }

    static void failsClosed(String name, byte[] mutated) {
        try {
            CanonicalClassIdentityV2.identifySessionBound(mutated);
            check("control " + name + " fails closed", false, "identification unexpectedly succeeded");
        } catch (CanonicalClassIdentityV2.IdentityFailure expected) {
            check("control " + name + " fails closed", true, expected.getMessage());
        }
    }

    static boolean issuanceRefuses(byte[] bytes) {
        try {
            issue(bytes);
            return false;
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            return true;
        }
    }

    static final class RefusalInfo {
        boolean refused;
        String detail;
        boolean certifiedOnly;
        boolean observedOnly;
    }

    static RefusalInfo captureRefusal(String certificateJson,
            SessionBoundIdentityCertificate.Observation observation) {
        RefusalInfo info = new RefusalInfo();
        try {
            SessionBoundIdentityCertificate.parse(certificateJson).authorize(observation);
            info.detail = "admission unexpectedly permitted";
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            info.refused = true;
            info.detail = refused.reason;
            List<String> certified = SessionBoundIdentityCertificate.parse(certificateJson).maskedAnnotationLocations;
            List<String> observed = observation.identity.maskedLocations;
            for (String location : certified) if (!observed.contains(location)) info.certifiedOnly = true;
            for (String location : observed) if (!certified.contains(location)) info.observedOnly = true;
        }
        return info;
    }

    static void refuseKind(String name, SessionBoundIdentityCertificate.Refusal.Kind expected,
            String certificateJson, SessionBoundIdentityCertificate.Observation observation) {
        try {
            if (certificateJson == null) throw new SessionBoundIdentityCertificate.Refusal(
                    SessionBoundIdentityCertificate.Refusal.Kind.INCOMPLETE, "NO_CERTIFICATE", "no session certificate present");
            SessionBoundIdentityCertificate.parse(certificateJson).authorize(observation);
            check(name, false, "admission unexpectedly permitted");
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            check(name, refused.kind == expected, "expected " + expected + ", got " + refused.kind + " [" + refused.reason + "]");
        }
    }

    static void refuseReason(String name, String expectedReason, String certificateJson,
            SessionBoundIdentityCertificate.Observation observation) {
        try {
            SessionBoundIdentityCertificate.parse(certificateJson).authorize(observation);
            check(name, false, "admission unexpectedly permitted");
        } catch (SessionBoundIdentityCertificate.Refusal refused) {
            check(name, expectedReason.equals(refused.reason),
                    "expected " + expectedReason + ", got " + refused.reason);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println(name + " -> PASS"); }
        else { fail++; System.out.println(name + " -> FAIL " + (detail == null ? "" : detail)); }
    }

    // ------------------------------------------------------------- certificate

    static SessionBoundIdentityCertificate issue(byte[] bytes) {
        return SessionBoundIdentityCertificate.issue(PROCESS, SESSION, LOADER, bytes,
                CanonicalClassIdentityV2.identifySessionBound(bytes), RECIPE, MANIFEST, POLICY, EVIDENCE);
    }

    static SessionBoundIdentityCertificate.Observation observe(byte[] bytes) {
        return new SessionBoundIdentityCertificate.Observation(PROCESS, SESSION, LOADER, bytes,
                CanonicalClassIdentityV2.identifySessionBound(bytes));
    }

    static String jsonString(String value) {
        return "\"" + value + "\"";
    }

    static String jsonStringArray(String[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(',');
            text.append(jsonString(values[i]));
        }
        return text.append(']').toString();
    }

    /** Replaces one certificate field's value, keeping the document well-formed. */
    static String doctorJson(String json, String key, String replacementValue) {
        return json.replaceAll("(?<=\"" + Pattern.quote(key) + "\":)(\"[^\"]*\"|\\[[^\\]]*\\])",
                Matcher.quoteReplacement(replacementValue));
    }

    /** Injects a field the certificate schema does not define. */
    static String withExtraField(String json, String key, String value) {
        return "{\"" + key + "\":" + value + "," + json.substring(1);
    }

    // ---------------------------------------------------------------- fixtures

    /** Qualified baseline: one MixinMerged.sessionId on a merged handler. */
    static byte[] qualified() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode plain = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()I", null, null);
        plain.instructions.add(new InsnNode(Opcodes.ICONST_0));
        plain.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(plain);
        return write(cn);
    }

    /** Two merged handlers qualified with DIFFERENT session UUIDs. */
    static byte[] qualifiedWithTwoSessions() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode first = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000", "()V", null, null);
        first.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        first.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode second = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf001", "()V", null, null);
        second.visibleAnnotations = Arrays.asList(mixinMerged("mixinB", SESSION_B));
        second.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(first);
        cn.methods.add(second);
        return write(cn);
    }

    /** Same binary class name, different successful class definition. */
    static byte[] qualifiedSecondDefinition() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode extra = new MethodNode(Opcodes.ACC_PUBLIC, "secondDefinitionMarker", "()V", null, null);
        extra.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(extra);
        return write(cn);
    }

    /** Correct sessionId plus an unrelated UUID-shaped constant change. */
    static byte[] unrelatedConstant() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode carrier = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()Ljava/lang/String;", null, null);
        carrier.instructions.add(new LdcInsnNode("44444444-5555-6666-7777-888888888888"));
        carrier.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(carrier);
        MethodNode other = new MethodNode(Opcodes.ACC_PUBLIC, "intMethod", "()I", null, null);
        other.instructions.add(new InsnNode(Opcodes.ICONST_0));
        other.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(other);
        return write(cn);
    }

    /** Correct sessionId plus a changed method instruction. */
    static byte[] instructionChange() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode plain = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()I", null, null);
        plain.instructions.add(new InsnNode(Opcodes.ICONST_0));
        plain.instructions.add(new InsnNode(Opcodes.ICONST_1));
        plain.instructions.add(new InsnNode(Opcodes.IADD));
        plain.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(plain);
        return write(cn);
    }

    /** Correct sessionId plus changed field/method metadata. */
    static byte[] metadataChange() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        merged.maxStack = 5;
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        MethodNode plain = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()I", null, null);
        plain.instructions.add(new InsnNode(Opcodes.ICONST_0));
        plain.instructions.add(new InsnNode(Opcodes.IRETURN));
        plain.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        cn.methods.add(plain);
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FLAG", "Z", null, null));
        return write(cn);
    }

    /** Correct sessionId plus a handler control-flow change. */
    static byte[] controlFlowChange() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A));
        LabelNode start = new LabelNode();
        LabelNode handler = new LabelNode();
        merged.instructions.add(start);
        merged.instructions.add(new InsnNode(Opcodes.ICONST_1));
        merged.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        merged.instructions.add(handler);
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        merged.tryCatchBlocks.add(new TryCatchBlockNode(start, handler, handler, "java/lang/RuntimeException"));
        merged.maxLocals = 2;
        cn.methods.add(merged);
        MethodNode plain = new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()I", null, null);
        plain.instructions.add(new InsnNode(Opcodes.ICONST_0));
        plain.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(plain);
        return write(cn);
    }

    /** MixinMerged-shaped provenance under a changed descriptor. */
    static byte[] renamedDescriptor() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000", "()V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", SESSION_A, "Lsb/MixinMergedX;"));
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        return write(cn);
    }

    /** Provenance under a changed element name (sessionId -> sessionIdRenamed). */
    static byte[] renamedElement() {
        ClassNode cn = skeleton();
        cn.name = "sb/CertFixture";
        AnnotationNode renamed = new AnnotationNode(MIXIN_MERGED);
        renamed.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionIdRenamed", SESSION_A);
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000", "()V", null, null);
        merged.visibleAnnotations = Arrays.asList(renamed);
        merged.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        return write(cn);
    }

    static ClassNode skeleton() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        return cn;
    }

    static AnnotationNode mixinMerged(String mixin, String sessionId) {
        return mixinMerged(mixin, sessionId, MIXIN_MERGED);
    }

    static AnnotationNode mixinMerged(String mixin, String sessionId, String descriptor) {
        AnnotationNode n = new AnnotationNode(descriptor);
        n.values = new ArrayList<Object>(Arrays.asList("mixin", mixin, "priority", 1000, "sessionId", sessionId));
        return n;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private SessionBoundCertificateControls() { }
}
