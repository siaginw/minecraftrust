import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.rustcraft.coremod.SPacketChunkDataTransformer;
import java.io.*;
import java.util.List;

public class DisassembleTransformed {
    public static void main(String[] args) throws Exception {
        String targetClassName = "net.minecraft.network.play.server.SPacketChunkData";
        String resourcePath = targetClassName.replace('.', '/') + ".class";
        InputStream in = DisassembleTransformed.class.getClassLoader().getResourceAsStream(resourcePath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        byte[] originalBytes = baos.toByteArray();

        SPacketChunkDataTransformer transformer = new SPacketChunkDataTransformer();
        byte[] transformedBytes = transformer.transform(targetClassName, targetClassName, originalBytes);

        ClassReader cr = new ClassReader(transformedBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        for (MethodNode mn : (List<MethodNode>) cn.methods) {
            if ("<init>".equals(mn.name) && mn.desc.contains("Chunk;I")) {
                System.out.println("Method: " + mn.name + " " + mn.desc);
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    System.out.printf("  %-15s opcode=%d\n", insn.getClass().getSimpleName(), insn.getOpcode());
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        System.out.println("    -> " + minsn.owner + "." + minsn.name + " " + minsn.desc);
                    }
                }
            }
        }
    }
}
