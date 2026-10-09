import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

/**
 * DELIBERATELY BROKEN fixture: injects a branch target into the first
 * method of the target class using a COMPUTE_MAXS-only writer — the
 * dev-ON-1 VerifyError shape (stale StackMapTable). verify_transformer
 * must report VERIFY-FAIL for this class.
 */
public class BrokenFramesTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName,
                            byte[] basicClass) {
        if (!"amu".equals(name) && !"amu".equals(transformedName)) {
            return basicClass;
        }
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(basicClass).accept(cn, 0);
            for (Object mo : cn.methods) {
                MethodNode mn = (MethodNode) mo;
                if (mn.instructions.size() == 0 || mn.name.startsWith("<")) {
                    continue;
                }
                LabelNode end = new LabelNode();
                InsnList head = new InsnList();
                head.add(new LdcInsnNode(1));
                head.add(new JumpInsnNode(org.objectweb.asm.Opcodes.IFEQ,
                        end));
                mn.instructions.insert(head);
                mn.instructions.add(end);
                ClassWriter cw = new ClassWriter(
                        ClassWriter.COMPUTE_MAXS); // <- the bug: no FRAMES
                cn.accept(cw);
                return cw.toByteArray();
            }
            return basicClass;
        } catch (Exception e) {
            return basicClass;
        }
    }
}
