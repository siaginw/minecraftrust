package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

/**
 * Negative controls for the structurally bound session mask. The baseline
 * fixture carries the proven Mixin merged-method provenance
 * (exact MixinMerged descriptor + sessionId element + UUID value) plus four
 * unrelated UUID-shaped bytes that MUST stay semantically significant.
 * Every control mutates exactly one thing and requires the session-invariant
 * identity to CHANGE (masking must not absorb it), while the pure sessionId
 * process swap (A -> B, no other change) must keep the invariant STABLE.
 */
public final class SessionBoundMaskControls {

    static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    static final String OTHER_ANN = "Lsb/OtherAnn;";

    static final String SESSION_A = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    static final String SESSION_B = "9d3ccad1-a938-4a64-a7ca-c8c81bef1757";
    static final String UNRELATED_LDC = "11111111-2222-3333-4444-555555555555";
    static final String UNRELATED_FIELD = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String UNRELATED_OTHER_ANN = "99999999-8888-7777-6666-555555555555";
    static final String UNRELATED_MIXIN_ELEMENT = "12121212-3434-5656-7878-909090909090";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        byte[] baseline = fixture(SESSION_A, false, false);
        CanonicalClassIdentityV2.Result base = CanonicalClassIdentityV2.identifySessionBound(baseline);

        // provenance bookkeeping on the baseline
        check("baseline: one distinct qualified session id",
                base.maskedValues.size() == 1 && SESSION_A.equals(base.maskedValues.get(0)),
                String.valueOf(base.maskedValues));
        check("baseline: annotation locations recorded (one per MixinMerged method)",
                base.maskedLocations.size() == 1 && base.maskedLocations.get(0)
                        .startsWith("method:handler$zzf000"),
                String.valueOf(base.maskedLocations));
        check("baseline: occurrence count covers annotation + pool utf8",
                base.maskedOccurrenceCount == 2,
                String.valueOf(base.maskedOccurrenceCount));

        // cross-process simulation: sessionId swap alone keeps invariant stable
        CanonicalClassIdentityV2.Result swapped =
                CanonicalClassIdentityV2.identifySessionBound(fixture(SESSION_B, false, false));
        check("process swap: invariant stable across sessionId A->B",
                base.sessionInvariantSha256.equals(swapped.sessionInvariantSha256),
                base.sessionInvariantSha256 + " vs " + swapped.sessionInvariantSha256);
        check("process swap: exact identity changes",
                !base.semanticSha256.equals(swapped.semanticSha256), null);
        check("process swap: masked value is the new session id",
                swapped.maskedValues.size() == 1 && SESSION_B.equals(swapped.maskedValues.get(0)),
                String.valueOf(swapped.maskedValues));

        // negative controls: each mutation must change the invariant identity
        control("unrelated UUID LDC constant", fixture(SESSION_A, true, false), base);
        control("unrelated UUID field constant", fixture(SESSION_A, false, true), base);
        control("UUID under another annotation", otherAnnotationFixture(SESSION_A, UNRELATED_OTHER_ANN, false), base);
        control("UUID under another MixinMerged element", fixture(SESSION_A, false, false, UNRELATED_MIXIN_ELEMENT), base);
        // changed descriptor: the renamed annotation no longer carries provenance,
        // so session-bound identification REFUSES (fail-closed) instead of
        // silently producing an unmasked invariant.
        renamedDescriptorControl("changed MixinMerged descriptor",
                renamedDescriptorFixture(SESSION_A, false, false));
        control("sessionId swap + changed instruction", fixturePlusInstruction(SESSION_B), base);
        control("sessionId swap + changed method metadata", fixturePlusMetadata(SESSION_B), base);

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    /** Fail-closed variant: the mutated fixture must be REFUSED outright. */
    static void renamedDescriptorControl(String name, byte[] mutated) {
        try {
            CanonicalClassIdentityV2.identifySessionBound(mutated);
            check("mask control: " + name + " fails closed", false,
                    "identification unexpectedly succeeded");
        } catch (CanonicalClassIdentityV2.IdentityFailure expected) {
            check("mask control: " + name + " fails closed", true,
                    String.valueOf(expected.getMessage()).substring(0, 60));
        }
    }

    /** One control: mutate, require invariant change, keep exact change as well. */
    static void control(String name, byte[] mutated, CanonicalClassIdentityV2.Result base) {
        try {
            CanonicalClassIdentityV2.Result after =
                    CanonicalClassIdentityV2.identifySessionBound(mutated);
            boolean invariantChanged = !base.sessionInvariantSha256
                    .equals(after.sessionInvariantSha256);
            check("mask control: " + name + " changes invariant identity",
                    invariantChanged, "invariant stayed " + base.sessionInvariantSha256);
            check("mask control: " + name + " also changes exact identity",
                    !base.semanticSha256.equals(after.semanticSha256), null);
        } catch (Throwable failure) {
            // A control may fail closed at identification time (e.g. a renamed
            // MixinMerged-like descriptor no longer satisfies the provenance
            // precondition). Refusal IS fail-closed; a silently identical
            // invariant is not.
            check("mask control: " + name + " fails closed",
                    false, "identification threw: " + failure);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println(name + " -> PASS" + (detail == null ? "" : " (" + detail + ")")); }
        else { fail++; System.out.println(name + " -> FAIL " + detail); }
    }

    // ------------------------------------------------------------------
    // fixture construction
    // ------------------------------------------------------------------

    /** Baseline fixture with the full provenance and unrelated UUID bytes. */
    static byte[] fixture(String sessionId, boolean unrelatedLdcChanged,
            boolean unrelatedFieldChanged) {
        ClassNode cn = skeleton();
        cn.name = "sb/Fixture";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId));
        merged.instructions.add(new LdcInsnNode("payload"));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);

        String unrelatedLdc = unrelatedLdcChanged ? UNRELATED_LDC + "x" : UNRELATED_LDC;
        MethodNode carrier = new MethodNode(Opcodes.ACC_PUBLIC, "carrier", "()Ljava/lang/String;", null, null);
        carrier.instructions.add(new LdcInsnNode(unrelatedLdc));
        carrier.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        cn.methods.add(carrier);

        String fieldUuid = unrelatedFieldChanged ? UNRELATED_FIELD + "x" : UNRELATED_FIELD;
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "TOKEN", "Ljava/lang/String;", null, fieldUuid));

        // other annotation with an unrelated UUID under its own element
        AnnotationNode other = new AnnotationNode(OTHER_ANN);
        other.values = Arrays.asList("tag", UNRELATED_OTHER_ANN);
        cn.visibleAnnotations = Arrays.asList(other);
        return write(cn);
    }

    /** UUID under a DIFFERENT MixinMerged element (not sessionId). */
    static byte[] fixture(String sessionId, boolean ignoredA, boolean ignoredB,
            String mixinElementValue) {
        ClassNode cn = skeleton();
        cn.name = "sb/FixtureMixinElement";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged(mixinElementValue, sessionId));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        return write(cn);
    }

    /** MixinMerged-like annotation under a CHANGED descriptor. */
    static byte[] renamedDescriptorFixture(String sessionId, boolean a, boolean b) {
        ClassNode cn = skeleton();
        cn.name = "sb/FixtureRenamed";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId, "Lsb/MixinMergedX;"));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        return write(cn);
    }

    /** UUID under a completely different annotation. */
    static byte[] otherAnnotationFixture(String sessionId, String otherUuid, boolean ignored) {
        ClassNode cn = skeleton();
        cn.name = "sb/FixtureOtherAnn";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        AnnotationNode other = new AnnotationNode(OTHER_ANN);
        other.values = Arrays.asList("sessionId", otherUuid);
        cn.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId), other);
        return write(cn);
    }

    /** sessionId swapped AND an instruction changed. */
    static byte[] fixturePlusInstruction(String sessionId) {
        ClassNode cn = skeleton();
        cn.name = "sb/FixtureInstr";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId));
        merged.instructions.add(new LdcInsnNode("extra-instruction"));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        return write(cn);
    }

    /** sessionId swapped AND method metadata changed. */
    static byte[] fixturePlusMetadata(String sessionId) {
        ClassNode cn = skeleton();
        cn.name = "sb/FixtureMeta";
        MethodNode merged = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged.visibleAnnotations = Arrays.asList(mixinMerged("mixinA", sessionId));
        merged.maxStack = 7; // metadata change
        merged.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        cn.methods.add(merged);
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FLAG", "Z", null, null));
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

    static AnnotationNode mixinMerged(String mixin, String sessionId, String desc) {
        AnnotationNode n = new AnnotationNode(desc);
        n.values = Arrays.asList("mixin", mixin, "priority", 1000, "sessionId", sessionId);
        return n;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private SessionBoundMaskControls() { }
}
