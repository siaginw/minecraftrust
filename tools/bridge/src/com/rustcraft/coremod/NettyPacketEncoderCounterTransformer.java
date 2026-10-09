package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Entry counter for vanilla {@code NettyPacketEncoder.encode}. This is the
 * explicit bypass instrumentation the single-copy contract requires: for a
 * direct admitted packet the invocation count must not move, while Java
 * fallback packets keep incrementing it.
 *
 * <p>Injected bytecode (method entry, both parameter shapes tolerated):</p>
 * <pre>
 *   INVOKESTATIC com/rustcraft/bridge/SingleCopyPipeline.onPacketEncoderInvoke ()V
 * </pre>
 *
 * <p>DEFAULT OFF: zero bytecode modification unless
 * {@code -Drustcraft.singleCopy} or {@code -Drustcraft.singleCopyShadow} is
 * set. The method name is stable in notch and SRG runtimes alike because
 * {@code encode} overrides a Netty library method (launchwrapper never renames
 * library overrides).</p>
 */
public class NettyPacketEncoderCounterTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.network.NettyPacketEncoder";
    /** 1.12.2 notch name, identified by the unique string constant
     *  "ConnectionProtocol unknown:". */
    private static final String TARGET_CLASS_OBF = "ha";

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.SingleCopyPipeline.enabled()) {
            return basicClass; // default OFF: no bytecode modification
        }
        if (LiveWriterOrdering.deferClass(transformedName)) return basicClass;
        if (LiveWriterOrdering.ensureWritersLast()) return basicClass;
        if (!TARGET_CLASS_DEOBF.equals(transformedName) && !TARGET_CLASS_OBF.equals(name)) {
            return basicClass;
        }
        if (basicClass == null) {
            lastTransformStatus = "NULL_BYTECODE";
            return null;
        }

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean transformed = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                // encode(ChannelHandlerContext, Packet, ByteBuf)V — the only
                // void-returning override named encode (library name, stable
                // across notch/SRG runtimes).
                if (!"encode".equals(mn.name) || !mn.desc.endsWith(")V")) {
                    continue;
                }
                if (alreadyInstrumented(mn)) {
                    lastTransformStatus = "ALREADY_INSTRUMENTED";
                    return basicClass;
                }
                InsnList hook = new InsnList();
                hook.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/SingleCopyPipeline",
                        "onPacketEncoderInvoke",
                        "()V",
                        false));
                mn.instructions.insert(hook);
                transformed = true;
            }

            if (!transformed) {
                lastTransformStatus = "ENCODE_NOT_FOUND";
                return basicClass; // fail open: vanilla encoder, no counter
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "COUNTER_PLACED";
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RustCraft] NettyPacketEncoder counter transform failed (vanilla preserved): " + t);
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }

    private static boolean alreadyInstrumented(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("com/rustcraft/bridge/SingleCopyPipeline".equals(call.owner)
                        && "onPacketEncoderInvoke".equals(call.name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
