import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Independently hand-written bytecode examples, never runs a production transformer. */
public final class PlacementFixtures implements Opcodes {
    static final String OWNER = "fixture/Writer";
    static final String HOOKS = "com/rustcraft/bridge/capture/LiveWriterHooks";
    static final String END = "(Ljava/lang/Object;Ljava/lang/Throwable;)V";
    static byte[] example(boolean post) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitSource("PlacementFixture.java", null);
        cw.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode(); c.visitVarInsn(ALOAD,0);
        c.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        if(post) { c.visitVarInsn(ALOAD,0); c.visitInsn(ACONST_NULL); c.visitMethodInsn(INVOKESTATIC,HOOKS,"registerNew","(Ljava/lang/Object;Ljava/lang/Object;)V",false); }
        c.visitInsn(RETURN); c.visitMaxs(0,0); c.visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC,"set","(I)V",null,null);
        Label start=new Label(), zero=new Label(), handler=new Label(), body=new Label(), end=new Label();
        m.visitCode();
        if(post) {
            m.visitInsn(ACONST_NULL); m.visitVarInsn(ASTORE,2); m.visitLabel(start);
            m.visitVarInsn(ALOAD,0); m.visitLdcInsn("liveWriter.W01.Writer.set");
            m.visitMethodInsn(INVOKESTATIC,HOOKS,"writerBegin","(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",false);
            m.visitVarInsn(ASTORE,2);
        }
        m.visitLabel(body); m.visitLineNumber(10,body);
        m.visitVarInsn(ALOAD,0); m.visitVarInsn(ILOAD,1); m.visitFieldInsn(PUTFIELD,OWNER,"value","I");
        m.visitVarInsn(ILOAD,1); m.visitJumpInsn(IFEQ,zero);
        if(post) finish(m);
        m.visitInsn(RETURN); m.visitLabel(zero); m.visitLineNumber(11,zero);
        if(post) finish(m);
        m.visitInsn(RETURN); m.visitLabel(end);
        m.visitLocalVariable("this","Lfixture/Writer;",null,body,end,0);
        m.visitLocalVariable("value","I",null,body,end,1);
        if(post) {
            m.visitLabel(handler); m.visitVarInsn(ASTORE,3); m.visitVarInsn(ALOAD,2); m.visitVarInsn(ALOAD,3);
            m.visitMethodInsn(INVOKESTATIC,HOOKS,"writerEnd",END,false); m.visitVarInsn(ALOAD,3); m.visitInsn(ATHROW);
            m.visitTryCatchBlock(start,handler,handler,null);
        }
        m.visitMaxs(0,0); m.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }
    static void finish(MethodVisitor m) {
        m.visitVarInsn(ALOAD,2); m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC,HOOKS,"writerEnd",END,false);
    }
    static MethodNode set(ClassNode cn) { for(MethodNode m:cn.methods) if(m.name.equals("set"))return m; throw new AssertionError(); }
    static MethodInsnNode call(MethodNode m,String name) { for(AbstractInsnNode n:m.instructions.toArray())if(n instanceof MethodInsnNode && ((MethodInsnNode)n).name.equals(name))return (MethodInsnNode)n; throw new AssertionError(); }
    static AbstractInsnNode opcode(MethodNode m,int op) { for(AbstractInsnNode n:m.instructions.toArray())if(n.getOpcode()==op)return n; throw new AssertionError(); }
    static byte[] mutate(byte[] input,String mutation) {
        ClassReader r=new ClassReader(input); ClassNode c=new ClassNode(); r.accept(c,0); MethodNode m=set(c);
        MethodInsnNode begin=call(m,"writerBegin"), end=call(m,"writerEnd");
        switch(mutation) {
            case "missing-begin": m.instructions.remove(begin); break;
            case "duplicate-begin": m.instructions.insert(begin,new MethodInsnNode(INVOKESTATIC,HOOKS,"writerBegin",begin.desc,false)); break;
            case "moved-begin": m.instructions.remove(begin); m.instructions.insertBefore(end,begin); break;
            case "wrong-owner": begin.owner="fixture/Wrong"; break;
            case "wrong-descriptor": begin.desc="(Ljava/lang/Object;)Ljava/lang/Object;"; break;
            case "wrong-interface-bit": begin.itf=true; break;
            case "wrong-marker": ((LdcInsnNode)opcode(m,LDC)).cst="liveWriter.WRONG.Writer.set"; break;
            case "wrong-token-local": ((VarInsnNode)opcode(m,ASTORE)).var=1; break;
            case "missing-normal-end": m.instructions.remove(end); break;
            case "extra-end": m.instructions.insert(begin,new MethodInsnNode(INVOKESTATIC,HOOKS,"writerEnd",END,false)); break;
            case "handler-range": m.tryCatchBlocks.get(0).start=m.tryCatchBlocks.get(0).handler; break;
            case "handler-target": m.tryCatchBlocks.get(0).handler=m.tryCatchBlocks.get(0).start; break;
            case "missing-handler": m.tryCatchBlocks.clear(); break;
            case "extra-handler": m.tryCatchBlocks.add(m.tryCatchBlocks.get(0)); break;
            case "wrong-original-operand": ((VarInsnNode)opcode(m,ILOAD)).var=0; break;
            case "wrong-branch-target": ((JumpInsnNode)opcode(m,IFEQ)).label=m.tryCatchBlocks.get(0).handler; break;
            case "extra-instruction": m.instructions.insert(begin,new InsnNode(NOP)); break;
            case "method-access": m.access |= ACC_FINAL; break;
            case "field-access": c.fields.get(0).access |= ACC_PUBLIC; break;
            case "class-signature": c.signature="Ljava/lang/Object;"; break;
            case "declaration-order": Collections.reverse(c.methods); break;
            case "extra-method": c.methods.add(new MethodNode(ACC_PUBLIC|ACC_ABSTRACT,"added","()V",null,null)); break;
            case "max-stack": m.maxStack++; break;
            case "max-locals": m.maxLocals++; break;
            case "frame-local": for(AbstractInsnNode n:m.instructions.toArray())if(n instanceof FrameNode){FrameNode f=(FrameNode)n;if(f.local!=null && !f.local.isEmpty()){f.local.set(0,INTEGER);break;}} break;
            case "wrong-constructor-binding": for(MethodNode x:c.methods)if(x.name.equals("<init>")){((VarInsnNode)call(x,"registerNew").getPrevious().getPrevious()).var=1;} break;
            case "unused-pool-entry": break;
            default: throw new IllegalArgumentException(mutation);
        }
        // Retain existing CP entries; mutations exercise exact preservation, not
        // accidentally unrelated pool deletions from a wholesale rewrite.
        ClassWriter out=new ClassWriter(r,0);
        if(mutation.equals("unused-pool-entry"))out.newUTF8("UNDECLARED_UNUSED_CONSTANT");
        c.accept(out); return out.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]); Files.createDirectories(root);
        byte[] pre=example(false), post=example(true);
        Files.write(root.resolve("pre.class"),pre); Files.write(root.resolve("valid.class"),post);
        String[] names={"missing-begin","duplicate-begin","moved-begin","wrong-owner","wrong-descriptor","wrong-interface-bit","wrong-marker","wrong-token-local","missing-normal-end","extra-end","handler-range","handler-target","missing-handler","extra-handler","wrong-original-operand","wrong-branch-target","extra-instruction","method-access","field-access","class-signature","declaration-order","extra-method","max-stack","max-locals","frame-local","wrong-constructor-binding","unused-pool-entry"};
        for(String name:names)Files.write(root.resolve(name+".class"),mutate(post,name));
        System.out.println("FIXTURES "+(names.length+2));
    }
}
