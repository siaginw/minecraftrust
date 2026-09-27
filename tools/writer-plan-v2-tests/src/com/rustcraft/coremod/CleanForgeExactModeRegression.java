package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

/**
 * Clean Forge exact-mode regression. Clean Forge does not require
 * session-bound identity and must stay on exact CANONICAL_ID_V2.
 *
 * <p>The decisive control is a class that DOES carry the qualified
 * MixinMerged.sessionId provenance. Under exact V2 it must still be admitted by
 * its exact identity, and a different process session must make that exact
 * identity change and be refused. If the exact path ever consulted the session
 * projection, the second half of that assertion would fail.</p>
 */
public final class CleanForgeExactModeRegression {

    private static final String SESSION_A = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    private static final String SESSION_B = "9d3ccad1-a938-4a64-a7ca-c8c81bef1757";
    private static int checks = 0;

    public static void main(String[] args) {
        String name = "net/minecraft/world/World";
        byte[] processA = qualified(name, SESSION_A);
        byte[] processB = qualified(name, SESSION_B);

        CanonicalClassIdentityV2.Result exactA = CanonicalClassIdentityV2.identify(processA);
        CanonicalClassIdentityV2.Result sessionA = CanonicalClassIdentityV2.identifySessionBound(processA);
        check(exactA.rawSha256.equals(sessionA.rawSha256), "both projections read the same buffer");
        check(!exactA.semanticSha256.equals(sessionA.sessionInvariantSha256),
                "exact and session-invariant identities are different domains");
        check(sessionA.maskedOccurrenceCount > 0, "the fixture really does carry session provenance");
        check(CanonicalClassIdentityV2.identify(processA).semanticSha256.equals(exactA.semanticSha256),
                "the exact identity is unmasked and stable");

        // A Clean Forge plan entry: exact CANONICAL_ID_V2, no certificate at all.
        LiveWriterPlan.Hook exactHook = new LiveWriterPlan.Hook("W01", "OWNERSHIP", name,
                "func_180501_a", "(Lnet/minecraft/util/math/BlockPos;)Z", "WRITE_BEGIN",
                "liveWriter.W01.World.func_180501_a", LiveWriterPlan.EMPTY_FINGERPRINT,
                exactA.semanticSha256, CanonicalClassIdentityV2.SCHEMA,
                exactA.declarationOrderSha256, null, null);
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {exactHook}, processA, null);
        check(true, "Clean Forge exact mode admits a class carrying session provenance");

        check(LiveHookSupport.sessionBoundEvidence(name) == null,
                "the exact path records no session-bound evidence");
        check(!exactHook.sessionBound(), "the Clean Forge entry is not in session-bound mode");

        // A second process produces different bytes and a different EXACT
        // identity: Clean Forge refuses rather than tolerating the difference.
        CanonicalClassIdentityV2.Result exactB = CanonicalClassIdentityV2.identify(processB);
        check(!exactA.semanticSha256.equals(exactB.semanticSha256),
                "a process session swap changes the exact identity");
        check(exactA.sessionInvariantSha256 == null,
                "the exact result carries no session invariant to fall back on");
        refuses("Clean Forge refuses a different process session", exactHook, processB);

        // No silent upgrade: an entry that claims session-bound mode without a
        // certificate is refused rather than demoted to exact or raw.
        LiveWriterPlan.Hook unbound = new LiveWriterPlan.Hook("W01", "OWNERSHIP", name,
                "func_180501_a", "(Lnet/minecraft/util/math/BlockPos;)Z", "WRITE_BEGIN",
                "liveWriter.W01.World.func_180501_a", LiveWriterPlan.EMPTY_FINGERPRINT,
                exactA.semanticSha256, CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND,
                exactA.declarationOrderSha256, null, null);
        refuses("an uncertified session-bound entry is refused, not demoted", unbound, processA);

        // ... and an exact entry that merely carries a certificate is still
        // exact: the certificate is inert outside session-bound mode.
        LiveWriterPlan.Hook withCertificate = new LiveWriterPlan.Hook("W01", "OWNERSHIP", name,
                "func_180501_a", "(Lnet/minecraft/util/math/BlockPos;)Z", "WRITE_BEGIN",
                "liveWriter.W01.World.func_180501_a", LiveWriterPlan.EMPTY_FINGERPRINT,
                exactA.semanticSha256, CanonicalClassIdentityV2.SCHEMA,
                exactA.declarationOrderSha256, "{\"schema\":\"anything\"}", sessionA.sessionInvariantSha256);
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {withCertificate}, processA, null);
        check(LiveHookSupport.sessionBoundEvidence(name) == null,
                "a certificate on an exact entry authorizes nothing");

        System.out.println("PASS Clean Forge exact mode; assertions=" + checks
                + "; session-bound masking is never invoked on this path");
    }

    private static void refuses(String what, LiveWriterPlan.Hook hook, byte[] bytes) {
        try {
            LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {hook}, bytes, null);
            check(false, what + " -> unexpectedly admitted");
        } catch (LiveHookSupport.ProfileFailure refused) {
            check(true, what);
        }
    }

    private static byte[] qualified(String internalName, String sessionId) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        AnnotationNode merged = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
        merged.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionId", sessionId);
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, "handler$zzf000$checkLightFor",
                "(Lnet/minecraft/util/math/BlockPos;)V", null, null);
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

    private CleanForgeExactModeRegression() { }
}
