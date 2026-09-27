package com.rustcraft.coremod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Synthetic admission controls only; never a runtime certificate. */
public final class WriterPlanIdentityV2Test implements Opcodes {
    private static int checks;
    private interface Action { void run(); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void reject(Action action, String message) {
        try { action.run(); } catch (LiveHookSupport.ProfileFailure expected) { checks++; return; }
        throw new AssertionError("accepted " + message);
    }
    private static byte[] fixture(int value, boolean reverse) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "example/PlanFixture", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, reverse ? "second" : "first", "I", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, reverse ? "first" : "second", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitIntInsn(BIPUSH, value); method.visitInsn(IRETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static LiveWriterPlan.Hook hook(String mode, String name, String semantic, String order) {
        return new LiveWriterPlan.Hook("X", "OWNERSHIP", name, "value", "()I", "WRITE_BEGIN",
                "synthetic.X", LiveWriterPlan.EMPTY_FINGERPRINT, semantic, mode, order);
    }
    private static void verify(LiveWriterPlan.Hook h, byte[] bytes) {
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {h}, bytes);
    }
    public static void main(String[] args) throws Exception {
        final byte[] bytes = fixture(1, false);
        final CanonicalClassIdentityV2.Result id = CanonicalClassIdentityV2.identify(bytes);
        final String name = "example.PlanFixture";
        final LiveWriterPlan.Hook valid = hook("CANONICAL_ID_V2", name, id.semanticSha256, id.declarationOrderSha256);
        verify(valid, bytes); check(true, "valid V2");
        LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {valid, valid}, bytes); check(true, "repeated same identity");
        verify(hook("RAW", name, LiveHookSupport.sha256(bytes), null), bytes); check(true, "historical RAW");
        verify(hook("CANONICAL_ID_V1", name, LiveHookSupport.canonicalSha256(bytes), null), bytes); check(true, "explicit historical V1");
        final byte[] changed = fixture(2, false);
        check(LiveHookSupport.canonicalSha256(bytes).equals(LiveHookSupport.canonicalSha256(changed)), "V1 misses operand control");
        reject(() -> verify(valid, changed), "V2 changed int operand");
        final byte[] reordered = fixture(1, true);
        CanonicalClassIdentityV2.Result order = CanonicalClassIdentityV2.identify(reordered);
        check(id.semanticSha256.equals(order.semanticSha256), "declaration reorder semantic normalization");
        check(!id.declarationOrderSha256.equals(order.declarationOrderSha256), "declaration order changed");
        reject(() -> verify(valid, reordered), "order-only drift");
        reject(() -> verify(hook("CANONICAL_ID_V2", name, id.semanticSha256, null), bytes), "missing order");
        reject(() -> verify(hook("CANONICAL_ID_V2", name, null, id.declarationOrderSha256), bytes), "missing semantic");
        reject(() -> verify(hook("CANONICAL_ID_V2", name, id.semanticSha256, "bad"), bytes), "bad order");
        reject(() -> verify(hook("CANONICAL_ID_V2", "example.Other", id.semanticSha256, id.declarationOrderSha256), bytes), "wrong class");
        reject(() -> verify(hook("CANONICAL_ID_V3", name, LiveHookSupport.sha256(bytes), null), bytes), "unknown mode no RAW fallback");
        reject(() -> verify(hook(null, name, LiveHookSupport.sha256(bytes), null), bytes), "null mode");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(null, bytes), "null plan");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[0], bytes), "empty plan");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {null}, bytes), "null hook");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {valid, null}, bytes), "trailing null hook");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {valid, hook("RAW", name, LiveHookSupport.sha256(bytes), null)}, bytes), "mixed modes");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {valid, hook("CANONICAL_ID_V2", "example.Other", id.semanticSha256, id.declarationOrderSha256)}, bytes), "mixed classes");
        reject(() -> LiveHookSupport.verifyPreHookIdentity(new LiveWriterPlan.Hook[] {valid, hook("CANONICAL_ID_V2", name, id.semanticSha256, "bad")}, bytes), "later order conflict");
        reject(() -> verify(LiveWriterPlan.doctoredSha(valid, "bad"), bytes), "doctored V2 semantic");
        check(LiveWriterPlan.doctoredDescriptor(valid, "()V").identitySchema.equals("CANONICAL_ID_V2"), "descriptor helper preserves schema");
        check(LiveWriterPlan.doctoredFingerprint(valid, new String[0][]).declarationOrderSha256.equals(id.declarationOrderSha256), "fingerprint helper preserves order");
        check(LiveWriterPlan.IDENTITY_MODE.equals("RAW"), "default remains historical RAW");
        Files.createDirectories(Paths.get(args[0]));
        Files.write(Paths.get(args[0], "fixture.class"), bytes);
        String json = "{\"schema\":\"CANONICAL_ID_V2\",\"class_name\":\"example/PlanFixture\",\"raw_sha256\":\"" + LiveHookSupport.sha256(bytes)
                + "\",\"semantic_sha256\":\"" + id.semanticSha256 + "\",\"declaration_order_sha256\":\"" + id.declarationOrderSha256 + "\"}";
        Files.write(Paths.get(args[0], "fixture-identity.json"), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS WriterPlanIdentityV2Test assertions=" + checks);
    }
}
