import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.List;

public class DisassembleSPacketChunkData {
    public static void main(String[] args) throws Exception {
        ClassReader cr = new ClassReader("net.minecraft.network.play.server.SPacketChunkData");
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        System.out.println("Superclass: " + cn.superName);
        System.out.println("Interfaces: " + cn.interfaces);

        for (MethodNode mn : (List<MethodNode>) cn.methods) {
            System.out.println("\nMethod: " + mn.name + " " + mn.desc);
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                System.out.printf("  %-15s opcode=%d\n", insn.getClass().getSimpleName(), insn.getOpcode());
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode minsn = (MethodInsnNode) insn;
                    System.out.println("    -> " + minsn.owner + "." + minsn.name + " " + minsn.desc);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode finsn = (FieldInsnNode) insn;
                    System.out.println("    -> " + finsn.owner + "." + finsn.name + " " + finsn.desc);
                } else if (insn instanceof VarInsnNode) {
                    VarInsnNode vinsn = (VarInsnNode) insn;
                    System.out.println("    -> var=" + vinsn.var);
                } else if (insn instanceof TypeInsnNode) {
                    TypeInsnNode tinsn = (TypeInsnNode) insn;
                    System.out.println("    -> type=" + tinsn.desc);
                }
            }
        }
    }
}
