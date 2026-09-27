package com.rustcraft.coremod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Deterministic semantic mutation and normalization controls; no game runtime. */
public final class CanonicalClassIdentityV2Test implements Opcodes {
    private interface Mutation { void apply(ClassNode c); }
    private static Path output;
    private static final List<String> controls = new ArrayList<String>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("fixture output directory required");
        output = Paths.get(args[0]); Files.createDirectories(output);
        byte[] baseline = write(fixture(), false);
        save("baseline", baseline);
        changed("bipush", c -> first(c, IntInsnNode.class, BIPUSH).operand++);
        changed("sipush", c -> first(c, IntInsnNode.class, SIPUSH).operand++);
        changed("ldc", c -> first(c, LdcInsnNode.class, LDC).cst = "different literal");
        changed("branch-target", c -> first(c, JumpInsnNode.class, IFEQ).label = table(c).dflt);
        changed("tableswitch-key", c -> { table(c).min++; table(c).max++; });
        changed("tableswitch-target", c -> table(c).labels.set(0, table(c).dflt));
        changed("lookupswitch-key", c -> lookup(c).keys.set(0, 8));
        changed("lookupswitch-target", c -> lookup(c).labels.set(0, lookup(c).dflt));
        changed("try-range", c -> main(c).tryCatchBlocks.get(0).start = table(c).dflt);
        changed("handler-target", c -> main(c).tryCatchBlocks.get(0).handler = table(c).dflt);
        changed("catch-type", c -> main(c).tryCatchBlocks.get(0).type = "java/lang/RuntimeException");
        changed("invocation-descriptor", c -> call(c).desc = "(Ljava/lang/String;)Ljava/lang/Object;");
        changed("invocation-owner", c -> call(c).owner = "example/Other");
        changed("invocation-interface-bit", c -> call(c).itf = true);
        changed("field-owner", c -> field(c).owner = "example/Other");
        changed("field-descriptor", c -> field(c).desc = "Ljava/lang/Object;");
        changed("local-index", c -> first(c, VarInsnNode.class, ILOAD).var++);
        changed("iinc-index", c -> first(c, IincInsnNode.class, IINC).var++);
        changed("iinc-increment", c -> first(c, IincInsnNode.class, IINC).incr++);
        changed("type-operand", c -> first(c, TypeInsnNode.class, CHECKCAST).desc = "java/lang/String");
        changed("multiarray-descriptor", c -> first(c, MultiANewArrayInsnNode.class, MULTIANEWARRAY).desc = "[[J");
        changed("multiarray-dimensions", c -> first(c, MultiANewArrayInsnNode.class, MULTIANEWARRAY).dims = 1);
        changed("method-access", c -> main(c).access = ACC_PRIVATE | ACC_STATIC);
        changed("method-synchronized", c -> main(c).access |= ACC_SYNCHRONIZED);
        changed("field-volatile", c -> c.fields.get(0).access |= ACC_VOLATILE);
        changed("declared-exception", c -> main(c).exceptions.set(0, "java/io/IOException"));
        changed("superclass", c -> c.superName = "java/lang/Number");
        changed("interface", c -> c.interfaces.set(0, "java/lang/Cloneable"));
        changed("reflection-member", c -> c.fields.get(0).name = "renamed");
        changed("field-signature", c -> c.fields.get(1).signature = "Ljava/util/List<Ljava/lang/Integer;>;");
        changed("class-signature", c -> c.signature = "Ljava/lang/Object;Ljava/lang/Cloneable;");
        changed("method-signature", c -> main(c).signature = "<T:Ljava/lang/Object;>(I)V");
        changed("class-annotation", c -> c.visibleAnnotations.get(0).values.set(1, "changed"));
        changed("field-annotation", c -> c.fields.get(0).visibleAnnotations.get(0).values.set(1, "changed"));
        changed("method-annotation", c -> main(c).visibleAnnotations.get(0).values.set(1, "changed"));
        changed("parameter-annotation", c -> main(c).visibleParameterAnnotations[0].get(0).values.set(1, "changed"));
        changed("type-annotation", c -> c.visibleTypeAnnotations.get(0).desc = "Lexample/DifferentTypeAnnotation;");
        changed("parameter-name", c -> main(c).parameters.get(0).name = "different");
        changed("parameter-access", c -> main(c).parameters.get(0).access ^= ACC_FINAL);
        changed("inner-class-metadata", c -> c.innerClasses.get(0).innerName = "Different");
        changed("outer-method", c -> c.outerMethod = "other");
        changed("source-file", c -> c.sourceFile = "Different.java");
        changed("source-debug", c -> c.sourceDebug = "different debug mapping");
        changed("line-number", c -> first(c, LineNumberNode.class, -1).line++);
        changed("local-variable-metadata", c -> main(c).localVariables.get(0).name = "different");
        changed("max-stack", c -> main(c).maxStack++);
        changed("max-locals", c -> main(c).maxLocals++);
        changed("bootstrap-argument", c -> dynamic(c).bsmArgs[0] = "changed");
        changed("bootstrap-method", c -> dynamic(c).bsm = new Handle(H_INVOKESTATIC,
                "example/Bootstrap", "other", "()Ljava/lang/invoke/CallSite;", false));
        changed("invokedynamic-descriptor", c -> dynamic(c).desc = "()I");
        changed("long-constant", c -> c.fields.get(2).value = Long.valueOf(43));
        changed("float-nan-payload", c -> c.fields.get(3).value = Float.intBitsToFloat(0x7fc00002));
        changed("double-nan-payload", c -> c.fields.get(4).value = Double.longBitsToDouble(0x7ff8000000000002L));
        changed("annotation-default", c -> c.methods.get(1).annotationDefault = "different");
        changed("frame-verification-type", c -> first(c, FrameNode.class, -1).local.set(0, FLOAT));
        changed("instruction-type-annotation", c -> first(c, TypeInsnNode.class, CHECKCAST)
                .visibleTypeAnnotations.get(0).desc = "Lexample/OtherType;");
        changed("local-type-annotation", c -> main(c).visibleLocalVariableAnnotations.get(0).desc = "Lexample/OtherType;");

        // These swaps change use-site bindings while preserving the entire pool.
        // Merely hashing pool values therefore cannot make these controls pass.
        poolPreservingChange("ldc-binding-swap", c -> {
            List<LdcInsnNode> nodes = all(c, LdcInsnNode.class);
            Object value = nodes.get(0).cst; nodes.get(0).cst = nodes.get(1).cst; nodes.get(1).cst = value;
        });
        poolPreservingChange("method-binding-swap", c -> {
            List<MethodInsnNode> ns = all(c, MethodInsnNode.class);
            MethodInsnNode x = ns.get(0), y = ns.get(1);
            String owner = x.owner, name = x.name, desc = x.desc; boolean itf = x.itf;
            x.owner = y.owner; x.name = y.name; x.desc = y.desc; x.itf = y.itf;
            y.owner = owner; y.name = name; y.desc = desc; y.itf = itf;
        });
        poolPreservingChange("field-binding-swap", c -> {
            List<FieldInsnNode> ns = all(c, FieldInsnNode.class);
            FieldInsnNode x = ns.get(0), y = ns.get(1);
            String owner = x.owner, name = x.name, desc = x.desc;
            x.owner = y.owner; x.name = y.name; x.desc = y.desc;
            y.owner = owner; y.name = name; y.desc = desc;
        });

        ClassNode withUnknown = fixture(); withUnknown.attrs = new ArrayList<Attribute>();
        withUnknown.attrs.add(new Attribute("UnknownSemanticAttribute") {
            @Override protected ByteVector write(ClassWriter w, byte[] code, int len, int stack, int locals) {
                return new ByteVector().putInt(42);
            }
        });
        rejected("unknown-attribute", write(withUnknown, false));
        ClassNode newer = fixture(); newer.version = 53; rejected("newer-classfile", write(newer, false));
        rejected("trailing-bytes", Arrays.copyOf(baseline, baseline.length + 1));
        rejected("truncated-classfile", Arrays.copyOf(baseline, baseline.length - 1));
        ClassNode malformedAttribute = fixture(); malformedAttribute.attrs = new ArrayList<Attribute>();
        malformedAttribute.attrs.add(new Attribute("Synthetic") {
            @Override protected ByteVector write(ClassWriter w, byte[] code, int len, int stack, int locals) {
                return new ByteVector().putInt(42);
            }
        });
        rejected("recognized-attribute-trailing-bytes", write(malformedAttribute, false));
        ClassNode orphan = fixture();
        main(orphan).attrs = new ArrayList<Attribute>();
        main(orphan).attrs.add(new Attribute("LocalVariableTypeTable") {
            @Override public boolean isCodeAttribute() { return true; }
            @Override protected ByteVector write(ClassWriter w, byte[] code, int len, int stack, int locals) {
                return new ByteVector().putShort(1).putShort(0).putShort(1)
                        .putShort(w.newUTF8("orphan")).putShort(w.newUTF8("Ljava/util/List<Ljava/lang/String;>;"))
                        .putShort(0);
            }
        });
        rejected("orphan-local-variable-type", write(orphan, false));
        metadataPresence("empty-method-parameters", "MethodParameters");
        metadataPresence("empty-visible-parameter-annotations", "RuntimeVisibleParameterAnnotations");
        metadataPresence("empty-invisible-parameter-annotations", "RuntimeInvisibleParameterAnnotations");

        stable("constant-pool-reorder", baseline, write(fixture(), true), false);
        byte[] narrow = padded(fixture(), false), wide = padded(fixture(), true);
        save("narrow-offset-baseline", narrow);
        stable("wide-offset-type-annotation", narrow, wide, false);
        ClassNode reordered = fixture(); Collections.reverse(reordered.fields); Collections.reverse(reordered.methods);
        stable("member-emission-order", baseline, write(reordered, false), true);
        CanonicalClassIdentityV2.Result admitted = CanonicalClassIdentityV2.identify(baseline);
        check(LiveHookSupport.verifyCanonicalIdentityV2(baseline, admitted.schema, admitted.className,
                admitted.semanticSha256, admitted.declarationOrderSha256).rawSha256.equals(admitted.rawSha256),
                "V2 composite admission binds current operation");
        refusedAdmission(baseline, "CANONICAL_ID_V1", admitted.className, admitted.semanticSha256,
                admitted.declarationOrderSha256, "V1 cannot alias V2");
        refusedAdmission(baseline, admitted.schema, admitted.className, admitted.semanticSha256,
                null, "missing declaration order fails closed");
        refusedAdmission(write(reordered, false), admitted.schema, admitted.className, admitted.semanticSha256,
                admitted.declarationOrderSha256, "member reorder rejected by default composite admission");
        refusedAdmission(baseline, admitted.schema, "example/Wrong", admitted.semanticSha256,
                admitted.declarationOrderSha256, "wrong class identity rejected");
        // A fresh graph has unrelated LabelNode allocation identities. Insert extra
        // labels at existing offsets and allocate unrelated labels before building it.
        for (int i = 0; i < 1000; i++) new Label();
        ClassNode labels = fixture();
        for (AbstractInsnNode n : main(labels).instructions.toArray())
            if (n instanceof LabelNode) main(labels).instructions.insertBefore(n, new LabelNode());
        stable("label-allocation-order", baseline, write(labels, false), false);
        check(CanonicalClassIdentityV2.identify(baseline).schema.equals("CANONICAL_ID_V2"), "explicit schema");
        Files.write(output.resolve("controls.tsv"), controls, StandardCharsets.UTF_8);
        System.out.println("CANONICAL_ID_V2 controls passed: " + checks + "; fixtures=" + output);
    }

    private static void changed(String name, Mutation mutation) throws Exception {
        ClassNode base = fixture(); mutation.apply(base); byte[] bytes = write(base, false);
        CanonicalClassIdentityV2.Result a = CanonicalClassIdentityV2.identify(write(fixture(), false));
        CanonicalClassIdentityV2.Result b = CanonicalClassIdentityV2.identify(bytes);
        check(!a.semanticSha256.equals(b.semanticSha256), name + " changes semantic identity");
        save(name, bytes); controls.add(name + "\tCHANGED\t" + b.semanticSha256 + "\tbaseline");
    }

    private static void poolPreservingChange(String name, Mutation mutation) throws Exception {
        CanonicalClassIdentityV2.Result a = CanonicalClassIdentityV2.identify(write(fixture(), false));
        ClassNode changed = fixture(); mutation.apply(changed); byte[] bytes = write(changed, false);
        CanonicalClassIdentityV2.Result b = CanonicalClassIdentityV2.identify(bytes);
        check(topElement(a.canonicalJson, 17).equals(topElement(b.canonicalJson, 17)), name + " pool unchanged");
        check(!a.semanticSha256.equals(b.semanticSha256), name + " use-site binding changes identity");
        save(name, bytes); controls.add(name + "\tCHANGED\t" + b.semanticSha256 + "\tbaseline");
    }

    private static void metadataPresence(String name, String attribute) throws Exception {
        ClassNode absent = fixture(), present = fixture();
        main(absent).parameters = null; main(absent).visibleParameterAnnotations = null;
        main(present).parameters = null; main(present).visibleParameterAnnotations = null;
        main(present).attrs = new ArrayList<Attribute>();
        main(present).attrs.add(new Attribute(attribute) {
            @Override protected ByteVector write(ClassWriter w, byte[] code, int len, int stack, int locals) {
                return new ByteVector().putByte(0);
            }
        });
        byte[] base = metadataWrite(absent), changed = metadataWrite(present);
        CanonicalClassIdentityV2.Result a = CanonicalClassIdentityV2.identify(base);
        CanonicalClassIdentityV2.Result b = CanonicalClassIdentityV2.identify(changed);
        check(topElement(a.canonicalJson, 17).equals(topElement(b.canonicalJson, 17)), name + " pool unchanged");
        check(!a.semanticSha256.equals(b.semanticSha256), name + " attribute presence retained");
        save("parameter-count-baseline", base); save(name, changed);
        controls.add(name + "\tCHANGED\t" + b.semanticSha256 + "\tparameter-count-baseline");
    }

    private static byte[] metadataWrite(ClassNode c) {
        ClassWriter w = new ClassWriter(0);
        w.newUTF8("MethodParameters"); w.newUTF8("RuntimeVisibleParameterAnnotations");
        w.newUTF8("RuntimeInvisibleParameterAnnotations"); c.accept(w); return w.toByteArray();
    }

    private static String topElement(String json, int desired) {
        int depth = 0, element = 0, start = 1; boolean string = false, escaped = false;
        for (int i = 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '[') depth++;
            else if (c == ']' && depth > 0) depth--;
            else if ((c == ',' || c == ']') && depth == 0) {
                if (element++ == desired) return json.substring(start, i);
                start = i + 1;
            }
        }
        throw new AssertionError("missing JSON element");
    }

    private static void stable(String name, byte[] baseline, byte[] changed, boolean orderChanges) throws Exception {
        CanonicalClassIdentityV2.Result a = CanonicalClassIdentityV2.identify(baseline);
        CanonicalClassIdentityV2.Result b = CanonicalClassIdentityV2.identify(changed);
        check(a.semanticSha256.equals(b.semanticSha256), name + " semantic identity stable");
        if (!name.equals("label-allocation-order"))
            check(!Arrays.equals(baseline, changed), name + " actually changes raw bytes");
        check(a.declarationOrderSha256.equals(b.declarationOrderSha256) != orderChanges,
                name + " declaration order tracked independently");
        save(name, changed); controls.add(name + "\tSTABLE\t" + b.semanticSha256 + "\t"
                + (name.equals("wide-offset-type-annotation") ? "narrow-offset-baseline" : "baseline"));
    }

    private static void rejected(String name, byte[] bytes) throws Exception {
        try { CanonicalClassIdentityV2.identify(bytes); throw new AssertionError(name + " accepted"); }
        catch (CanonicalClassIdentityV2.IdentityFailure expected) { checks++; }
        Files.write(output.resolve(name + ".class"), bytes); controls.add(name + "\tREJECTED\t-\tbaseline");
    }

    private static void save(String name, byte[] bytes) throws Exception {
        Files.write(output.resolve(name + ".class"), bytes);
        CanonicalClassIdentityV2.Result result = CanonicalClassIdentityV2.identify(bytes);
        Files.write(output.resolve(name + ".canonical.json"), result.canonicalJson.getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve(name + ".receipt.json"), result.receiptJson().getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); checks++;
    }

    private static void refusedAdmission(byte[] bytes, String schema, String name, String semantic,
            String order, String description) {
        try {
            LiveHookSupport.verifyCanonicalIdentityV2(bytes, schema, name, semantic, order);
            throw new AssertionError(description);
        } catch (LiveHookSupport.ProfileFailure expected) { checks++; }
    }

    private static ClassNode fixture() {
        ClassNode c = new ClassNode();
        c.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "example/Fixture", null, "java/lang/Object", new String[] {"java/io/Serializable"});
        c.visitSource("Fixture.java", "SMAP\nFixture.java\nJava\n*S Java\n*E\n");
        c.visitOuterClass("example/Enclosing", "factory", "()V");
        c.visitInnerClass("example/Fixture", "example/Enclosing", "Fixture", ACC_PUBLIC | ACC_STATIC);
        c.visitAnnotation("Lexample/Marker;", true).visit("value", "class");
        c.visitTypeAnnotation(TypeReference.newSuperTypeReference(-1).getValue(), null,
                "Lexample/TypeMarker;", true).visit("value", "type");
        FieldVisitor f = c.visitField(ACC_PUBLIC | ACC_STATIC, "value", "I", null, null);
        f.visitAnnotation("Lexample/Marker;", true).visit("value", "field"); f.visitEnd();
        c.visitField(ACC_PUBLIC, "names", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", null).visitEnd();
        c.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "longValue", "J", null, 42L).visitEnd();
        c.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "floatNaN", "F", null, Float.intBitsToFloat(0x7fc00001)).visitEnd();
        c.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "doubleNaN", "D", null, Double.longBitsToDouble(0x7ff8000000000001L)).visitEnd();
        MethodVisitor m = c.visitMethod(ACC_PUBLIC | ACC_STATIC, "exercise", "(I)V", null, new String[] {"java/lang/Exception"});
        m.visitParameter("key", ACC_FINAL);
        m.visitAnnotation("Lexample/Marker;", true).visit("value", "method");
        m.visitParameterAnnotation(0, "Lexample/Marker;", true).visit("value", "parameter");
        m.visitCode();
        Label start = new Label(), table0 = new Label(), table1 = new Label(), td = new Label();
        Label lookup0 = new Label(), ld = new Label(), end = new Label(), handler = new Label(), done = new Label();
        m.visitLabel(start); m.visitLineNumber(10, start);
        m.visitIntInsn(BIPUSH, 12); m.visitInsn(POP);
        m.visitIntInsn(SIPUSH, 1234); m.visitInsn(POP);
        m.visitLdcInsn("literal"); m.visitInsn(POP);
        m.visitLdcInsn("other literal"); m.visitInsn(POP);
        m.visitIincInsn(0, 1);
        m.visitVarInsn(ILOAD, 0); m.visitJumpInsn(IFEQ, table0);
        m.visitVarInsn(ILOAD, 0); m.visitTableSwitchInsn(0, 1, td, table0, table1);
        m.visitLabel(table0); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {});
        m.visitInsn(NOP); m.visitJumpInsn(GOTO, td);
        m.visitLabel(table1); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {});
        m.visitInsn(NOP); m.visitJumpInsn(GOTO, td);
        m.visitLabel(td); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {});
        m.visitVarInsn(ILOAD, 0); m.visitLookupSwitchInsn(ld, new int[] {7}, new Label[] {lookup0});
        m.visitLabel(lookup0); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {});
        m.visitInsn(NOP); m.visitJumpInsn(GOTO, ld);
        m.visitLabel(ld); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {});
        m.visitInsn(ACONST_NULL); m.visitTypeInsn(CHECKCAST, "java/lang/Object");
        m.visitInsnAnnotation(TypeReference.newTypeArgumentReference(TypeReference.CAST, 0).getValue(),
                null, "Lexample/CastType;", true).visit("value", "instruction"); m.visitInsn(POP);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"); m.visitInsn(POP);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"); m.visitInsn(POP);
        m.visitInsn(ACONST_NULL); m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); m.visitInsn(POP);
        m.visitInsn(ICONST_1); m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false); m.visitInsn(POP);
        m.visitInsn(ICONST_1); m.visitInsn(ICONST_1); m.visitMultiANewArrayInsn("[[I", 2); m.visitInsn(POP);
        m.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new Handle(H_INVOKESTATIC, "example/Bootstrap", "bootstrap", "()Ljava/lang/invoke/CallSite;", false), "bootstrap arg");
        m.visitInsn(POP);
        m.visitLabel(end); m.visitJumpInsn(GOTO, done);
        m.visitLabel(handler); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 1, new Object[] {"java/lang/Exception"});
        m.visitInsn(POP); m.visitJumpInsn(GOTO, done);
        m.visitLabel(done); m.visitFrame(F_FULL, 1, new Object[] {INTEGER}, 0, new Object[] {}); m.visitInsn(RETURN);
        m.visitTryCatchBlock(start, end, handler, "java/lang/Exception");
        m.visitLocalVariable("key", "I", null, start, done, 0);
        m.visitLocalVariableAnnotation(TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).getValue(),
                null, new Label[] {start}, new Label[] {done}, new int[] {0}, "Lexample/LocalType;", true)
                .visit("value", "local");
        m.visitMaxs(4, 2); m.visitEnd();
        MethodVisitor other = c.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "annotationLike", "()Ljava/lang/String;", null, null);
        other.visitAnnotationDefault().visit(null, "default"); other.visitEnd();
        c.visitEnd(); return c;
    }

    private static byte[] write(ClassNode c, boolean reorderPool) {
        ClassWriter w = new ClassWriter(0);
        // Seed existing constants in the reverse order without adding any entry.
        if (reorderPool) { w.newConst("bootstrap arg"); w.newConst("literal"); w.newUTF8("exercise"); }
        c.accept(w); return w.toByteArray();
    }
    private static byte[] padded(ClassNode c, boolean wide) {
        ClassWriter w = new ClassWriter(0);
        if (wide) for (int i = 0; i < 300; i++) w.newUTF8("unused-padding-" + i);
        c.accept(w);
        if (!wide) for (int i = 0; i < 300; i++) w.newUTF8("unused-padding-" + i);
        return w.toByteArray();
    }
    private static <T> List<T> all(ClassNode c, Class<T> type) {
        List<T> result = new ArrayList<T>();
        for (AbstractInsnNode n : main(c).instructions.toArray()) if (type.isInstance(n)) result.add(type.cast(n));
        return result;
    }
    private static MethodNode main(ClassNode c) {
        for (MethodNode m : c.methods) if (m.name.equals("exercise")) return m;
        throw new AssertionError("missing exercise");
    }
    private static <T> T first(ClassNode c, Class<T> type, int opcode) {
        for (AbstractInsnNode n : main(c).instructions.toArray())
            if (type.isInstance(n) && n.getOpcode() == opcode) return type.cast(n);
        throw new AssertionError("missing instruction " + type.getName());
    }
    private static TableSwitchInsnNode table(ClassNode c) { return first(c, TableSwitchInsnNode.class, TABLESWITCH); }
    private static LookupSwitchInsnNode lookup(ClassNode c) { return first(c, LookupSwitchInsnNode.class, LOOKUPSWITCH); }
    private static MethodInsnNode call(ClassNode c) { return first(c, MethodInsnNode.class, INVOKESTATIC); }
    private static FieldInsnNode field(ClassNode c) { return first(c, FieldInsnNode.class, GETSTATIC); }
    private static InvokeDynamicInsnNode dynamic(ClassNode c) { return first(c, InvokeDynamicInsnNode.class, INVOKEDYNAMIC); }
}
