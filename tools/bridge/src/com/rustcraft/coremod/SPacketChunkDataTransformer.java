package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.List;

/**
 * M1.4 CoreMod Class-Transformer for SPacketChunkData.
 * Injects a non-invasive conditional hook into SPacketChunkData.<init>(Chunk, int)
 * while strictly preserving original bytecode, descriptors, and Java fallback logic.
 */
public class SPacketChunkDataTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.network.play.server.SPacketChunkData";
    private static final String TARGET_CLASS_OBF = "ji";

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
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
                // Match constructor (Chunk, int) -> (Lnet/minecraft/world/chunk/Chunk;I)V
                if ("<init>".equals(mn.name) && mn.desc.contains("Lnet/minecraft/world/chunk/Chunk;I")) {
                    transformed = transformConstructor(cn, mn);
                    break;
                }
            }

            if (transformed) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastTransformStatus = "TRANSFORMED_SUCCESS";
                return cw.toByteArray();
            } else {
                if (!"UNKNOWN_LAYOUT_FALLBACK".equals(lastTransformStatus)) {
                    lastTransformStatus = "CONSTRUCTOR_NOT_FOUND";
                }
                return basicClass;
            }
        } catch (Throwable t) {
            System.err.println("[RustCraft] Failed to transform SPacketChunkData: " + t.getMessage());
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass; // Safe fallback: return unmodified bytecode
        }
    }

    private boolean transformConstructor(ClassNode cn, MethodNode mn) {
        // Fingerprint check: ensure standard instructions exist
        boolean hasCalculateSize = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode minsn = (MethodInsnNode) insn;
                if (minsn.name.equals("func_189556_a") || minsn.name.equals("calculateChunkSize")) {
                    hasCalculateSize = true;
                    break;
                }
            }
        }

        if (!hasCalculateSize) {
            System.err.println("[RustCraft] Warning: SPacketChunkData constructor has unexpected bytecode layout. Using UNKNOWN_LAYOUT_FALLBACK.");
            lastTransformStatus = "UNKNOWN_LAYOUT_FALLBACK";
            return false;
        }

        // Inject hook at method entry right after super() call:
        // boolean handled = NativeChunkPacket.populatePacket(this, chunkIn, changedSectionFilter);
        // if (handled) return;

        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // Load this, chunkIn (var 1), changedSectionFilter (var 2)
        // Descriptor uses Object/Object so it is identical in notch/SRG/MCP runtimes
        // and matches NativeChunkPacket.populatePacket(Object, Object, int).
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/NativeChunkPacket",
                "populatePacket",
                "(Ljava/lang/Object;Ljava/lang/Object;I)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(continueOriginal);

        // Find insertion point after super() call (INVOKESPECIAL java/lang/Object.<init>)
        AbstractInsnNode targetNode = null;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode minsn = (MethodInsnNode) insn;
                if ("java/lang/Object".equals(minsn.owner) && "<init>".equals(minsn.name)) {
                    targetNode = insn;
                    break;
                }
            }
        }

        if (targetNode != null) {
            mn.instructions.insert(targetNode, hook);
        } else {
            mn.instructions.insert(hook);
        }

        // M4.2B: live packet comparator — at EVERY constructor return, hand the
        // fully built Java packet to M4PacketCompare (no-op unless
        // -Dminecraftrust.m4.packet_compare=SHADOW; Java body ran untouched).
        if ("SHADOW".equalsIgnoreCase(System.getProperty("minecraftrust.m4.packet_compare", "OFF"))) {
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList cmp = new InsnList();
                    cmp.add(new VarInsnNode(Opcodes.ALOAD, 0)); // packet
                    cmp.add(new VarInsnNode(Opcodes.ALOAD, 1)); // chunkIn
                    cmp.add(new VarInsnNode(Opcodes.ILOAD, 2)); // changedSectionFilter
                    cmp.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/rustcraft/bridge/M4PacketCompare", "onPacketBuilt",
                            "(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
                    mn.instructions.insertBefore(insn, cmp);
                }
            }
        }
        return true;
    }
}
