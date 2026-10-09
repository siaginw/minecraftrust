import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Hand-authored finite frame rewrites atop the independently authored hook fixture. */
public final class FrameFixtures implements Opcodes {
    static byte[] build(boolean post,boolean missing) {
        ClassReader reader=new ClassReader(PlacementFixtures.example(post));
        ClassWriter writer=new ClassWriter(reader,0);
        // Retain every pre-frame constant even when a post frame uses a subtype.
        writer.newClass("java/lang/Object");writer.newClass("[Ljava/lang/Object;");
        reader.accept(writer,0);
        typed(writer,"integer",ICONST_1,ISTORE,post?INTEGER:TOP,1);
        typed(writer,"longValue",LCONST_1,LSTORE,post?LONG:TOP,2);
        MethodVisitor method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,"reference","(Z)V",null,null);
        method.visitCode();method.visitLdcInsn("kept");method.visitVarInsn(ASTORE,1);
        branch(method,new Object[]{INTEGER,post?"java/lang/String":"java/lang/Object"},1,2);
        method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,"nullArray","(Z)V",null,null);
        method.visitCode();method.visitInsn(ACONST_NULL);method.visitVarInsn(ASTORE,1);
        branch(method,new Object[]{INTEGER,post?(missing?"missing/Unknown":"[Ljava/lang/String;"):"[Ljava/lang/Object;"},1,2);
        method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,"allocation","()V",null,null);
        method.visitCode();Label allocation=new Label(),target=new Label();method.visitLabel(allocation);
        method.visitTypeInsn(NEW,"java/lang/Object");method.visitVarInsn(ASTORE,0);method.visitJumpInsn(GOTO,target);
        method.visitLabel(target);method.visitFrame(F_FULL,1,new Object[]{allocation},0,new Object[0]);
        method.visitVarInsn(ALOAD,0);method.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);method.visitInsn(RETURN);method.visitMaxs(1,1);method.visitEnd();
        writer.visitEnd();return writer.toByteArray();
    }
    static void typed(ClassWriter writer,String name,int constant,int store,Object type,int width){
        MethodVisitor method=writer.visitMethod(ACC_PRIVATE|ACC_STATIC,name,"(Z)V",null,null);
        method.visitCode();method.visitInsn(constant);method.visitVarInsn(store,1);
        branch(method,width==2&&type==TOP?new Object[]{INTEGER,TOP,TOP}:new Object[]{INTEGER,type},width,width+1);
    }
    static void branch(MethodVisitor method,Object[] locals,int maximum,int localCount){
        Label end=new Label();method.visitVarInsn(ILOAD,0);method.visitJumpInsn(IFEQ,end);method.visitInsn(RETURN);
        method.visitLabel(end);method.visitFrame(F_FULL,locals.length,locals,0,new Object[0]);method.visitInsn(RETURN);
        method.visitMaxs(maximum,localCount);method.visitEnd();
    }
    public static void main(String[] args)throws Exception {
        Path out=Paths.get(args[0]);Files.createDirectories(out);
        Files.write(out.resolve("pre.class"),build(false,false));Files.write(out.resolve("post.class"),build(true,false));
        Files.write(out.resolve("missing-type.class"),build(true,true));System.out.println("FRAME_FIXTURES 3");
    }
}
