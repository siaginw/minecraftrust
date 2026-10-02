package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Installs the RustCraft single-copy outbound handler at the only point where
 * the pipeline is guaranteed to exist and the current thread is the Netty
 * event loop: {@code NetworkManager.channelActive(ChannelHandlerContext)}.
 *
 * <p>Injected bytecode (before the method's final return):</p>
 * <pre>
 *   ALOAD 1
 *   INVOKESTATIC com/rustcraft/bridge/SingleCopyPipeline.install (Ljava/lang/Object;)V
 * </pre>
 *
 * <p>{@code install} itself is fail-open and idempotent (channel-attr guarded):
 * it logs pipeline.names() once, refuses unknown pipeline shapes, and places
 * {@code rustcraft_single_copy} directly after {@code encoder} (shadow mode
 * additionally places {@code rustcraft_body_capture} directly before
 * {@code encoder}). Any failure leaves the channel vanilla and admitted
 * packets use the two-copy writePacketData fallback — never a crash.</p>
 *
 * <p>DEFAULT OFF: zero bytecode modification unless
 * {@code -Drustcraft.singleCopy} or {@code -Drustcraft.singleCopyShadow} is
 * set. The handler and capture method names are stable across notch/SRG
 * runtimes because they override Netty library methods (launchwrapper never
 * renames library overrides); the class names are matched for both.</p>
 */
public class NetworkManagerSingleCopyTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.network.NetworkManager";
    /** 1.12.2 notch name, identified by the unique NetworkManager string
     *  constant "handleDisconnection() called twice". */
    private static final String TARGET_CLASS_OBF = "gw";

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
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);

            boolean transformed = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                if (!"channelActive".equals(mn.name)
                        || !"(Lio/netty/channel/ChannelHandlerContext;)V".equals(mn.desc)) {
                    continue;
                }
                if (alreadyInstrumented(mn)) {
                    lastTransformStatus = "ALREADY_INSTRUMENTED";
                    return basicClass;
                }
                injectInstallHook(mn);
                transformed = true;
            }

            if (!transformed) {
                lastTransformStatus = "CHANNEL_ACTIVE_NOT_FOUND";
                return basicClass; // fail open: vanilla NetworkManager
            }

            org.objectweb.asm.ClassWriter cw =
                    new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                            | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "INSTALL_HOOK_PLACED";
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RustCraft] NetworkManager single-copy transform failed (vanilla preserved): " + t);
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }

    private static boolean alreadyInstrumented(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("com/rustcraft/bridge/SingleCopyPipeline".equals(call.owner)
                        && "install".equals(call.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void injectInstallHook(MethodNode mn) {
        InsnList hook = new InsnList();
        // channelActive(ChannelHandlerContext ctx): pass ctx.channel() (the
        // Channel), not the context itself.
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelHandlerContext",
                "channel",
                "()Lio/netty/channel/Channel;",
                true));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/SingleCopyPipeline",
                "install",
                "(Ljava/lang/Object;)V",
                false));

        // Inject immediately before the final return (the super call and the
        // channel field assignment have run; the pipeline is live and the
        // thread is the event loop).
        AbstractInsnNode last = mn.instructions.getLast();
        while (last != null && last.getOpcode() == -1) {
            last = last.getPrevious();
        }
        if (last != null && last.getOpcode() == Opcodes.RETURN) {
            mn.instructions.insertBefore(last, hook);
        } else {
            mn.instructions.add(hook);
            mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        }
    }
}
