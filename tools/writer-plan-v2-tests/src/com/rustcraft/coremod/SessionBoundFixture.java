package com.rustcraft.coremod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Writes the session-bound fixture class and BOTH of its identities to disk.
 *
 * <p>The offline lane needs the session-invariant identity to build a static
 * admission policy, and the exact identity to bind the plan. Those come from a
 * real classfile carrying real {@code MixinMerged.sessionId} provenance rather
 * than from a constant, so the policy is derived the way §8 requires: from
 * observed cross-launch evidence.</p>
 *
 * <p>The bytes are built deterministically from fixed inputs, so the control that
 * later rebuilds this class gets the identical buffer and therefore the identical
 * identities. That is what lets the plan be generated before the control runs.</p>
 */
public final class SessionBoundFixture {

    public static final String INTERNAL_NAME = "example/PlanFixture";
    public static final String SESSION_UUID = "0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4";
    public static final String METHOD = "handler$zzf000";
    /** The loader class the admission policy names. The control runs on the app loader. */
    public static final String LOADER_CLASS = SessionBoundFixture.class.getClassLoader().getClass().getName();
    public static final String RUNTIME_PROFILE = "SYNTHETIC_NOT_QUALIFIED";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--environment".equals(args[0])) {
            // Published, not duplicated in Python: the policy and the control must
            // name the same loader and the same runtime profile, and a literal on
            // each side would let them drift without failing anything.
            System.setProperty(LiveHookSupport.RUNTIME_PROFILE_PROPERTY, RUNTIME_PROFILE);
            System.out.println(SessionBoundFixture.class.getClassLoader().getClass().getName());
            System.out.println(RUNTIME_PROFILE);
            return;
        }
        byte[] bytes = build();
        CanonicalClassIdentityV2.Result exact = CanonicalClassIdentityV2.identify(bytes);
        CanonicalClassIdentityV2.Result session = CanonicalClassIdentityV2.identifySessionBound(bytes);
        if (session.maskedValues.size() != 1)
            throw new AssertionError("fixture must carry exactly one session UUID, found " + session.maskedValues);
        String json = "{\"schema\":\"CANONICAL_ID_V2\",\"class_name\":\"" + INTERNAL_NAME
                + "\",\"raw_sha256\":\"" + LiveHookSupport.sha256(bytes)
                + "\",\"semantic_sha256\":\"" + exact.semanticSha256
                + "\",\"declaration_order_sha256\":\"" + exact.declarationOrderSha256
                + "\",\"session_invariant_sha256\":\"" + session.sessionInvariantSha256
                + "\",\"masked_occurrences\":" + session.maskedOccurrenceCount
                + ",\"masked_locations\":[\"" + session.maskedLocations.get(0) + "\"]}";
        Files.createDirectories(Paths.get(args[0]));
        Files.write(Paths.get(args[0], "session-fixture.class"), bytes);
        Files.write(Paths.get(args[0], "session-fixture-identity.json"), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS SessionBoundFixture");
    }

    /** The fixture classfile, with this launch's session UUID. */
    public static byte[] build() {
        return buildWithSession(SESSION_UUID);
    }

    /**
     * The same fixture carrying a different session UUID. The bytes differ only
     * in that value, which is what makes the two launches share a session-INVARIANT
     * identity while their exact identities and certificates differ.
     */
    public static byte[] buildWithSession(String sessionUuid) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = INTERNAL_NAME;
        cn.superName = "java/lang/Object";
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, METHOD, "()V", null, null);
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        AnnotationNode merged = new AnnotationNode(CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
        merged.values = Arrays.asList("mixin", "mixinA", "priority", 1000, "sessionId", sessionUuid);
        handler.visibleAnnotations = Arrays.asList(merged);
        cn.methods.add(handler);
        ClassWriter writer = new ClassWriter(0);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private SessionBoundFixture() { }
}
