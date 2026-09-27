import java.nio.file.*;
import org.objectweb.asm.*;

/** Small original fixtures. No Minecraft or production transformer code runs. */
public final class VerificationFixtures implements Opcodes {
    static final String NAME="proof/Target";
    static byte[] build(String variant) {
        ClassWriter cw=new ClassWriter(0);
        cw.visit(V1_8,ACC_PUBLIC|ACC_SUPER,NAME,null,"java/lang/Object",null);
        MethodVisitor ctor=cw.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);
        ctor.visitCode();ctor.visitVarInsn(ALOAD,0);ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(RETURN);ctor.visitMaxs(1,1);ctor.visitEnd();
        MethodVisitor init=cw.visitMethod(ACC_STATIC,"<clinit>","()V",null,null);
        init.visitCode();init.visitLdcInsn("rustcraft.verification.target.initialized");init.visitLdcInsn("YES");
        init.visitMethodInsn(INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false);
        init.visitInsn(POP);init.visitInsn(RETURN);init.visitMaxs(2,0);init.visitEnd();
        MethodVisitor m=cw.visitMethod(ACC_PRIVATE|ACC_STATIC,"dormant","(Z)I",null,null);
        m.visitCode();
        if(variant.equals("bad-dormant")) {
            m.visitInsn(ICONST_0);m.visitInsn(ARETURN);m.visitMaxs(1,1);
        } else {
            if(variant.startsWith("null-"))m.visitInsn(ACONST_NULL);else m.visitLdcInsn("retained");
            m.visitVarInsn(ASTORE,1);m.visitVarInsn(ILOAD,0);
            Label alternate=new Label();m.visitJumpInsn(IFEQ,alternate);m.visitInsn(ICONST_1);m.visitInsn(IRETURN);
            m.visitLabel(alternate);
            Object[] locals=variant.equals("bad-frame") ? new Object[]{"java/lang/String","java/lang/Object"} :
                variant.equals("valid-top") ? new Object[]{INTEGER,TOP} :
                variant.equals("valid-refined") ? new Object[]{INTEGER,"java/lang/String"} :
                variant.equals("null-missing-type") ? new Object[]{INTEGER,"missing/Unknown"} : new Object[]{INTEGER,"java/lang/Object"};
            m.visitFrame(F_FULL,locals.length,locals,0,new Object[0]);
            m.visitInsn(ICONST_2);m.visitInsn(IRETURN);
            m.visitMaxs(variant.equals("valid-max")?3:1,2);
        }
        m.visitEnd();cw.visitEnd();return cw.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        for(String name:new String[]{"valid-object","valid-top","valid-refined","valid-max","bad-dormant","bad-frame","null-object","null-missing-type"})
            Files.write(root.resolve(name+".class"),build(name));
        System.out.println("FIXTURES 8");
    }
}
