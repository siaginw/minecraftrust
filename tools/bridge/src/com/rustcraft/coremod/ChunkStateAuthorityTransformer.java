package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;

/**
 * CoreMod transformer for RustCraft SEMANTIC ENGINE OWNERSHIP.
 *
 * Injects conditional entry hooks into:
 * - Chunk.getBlockState(int, int, int) [func_186032_a]
 * - Chunk.setBlockState(BlockPos, IBlockState) [func_177436_a]
 *
 * Preserves 100% of vanilla and Forge bytecode; fails closed to original
 * logic whenever ChunkStateAuthorityBridge does not handle the operation.
 */
public class ChunkStateAuthorityTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "net.minecraft.world.chunk.Chunk";

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static boolean enabled() {
        return Boolean.getBoolean("rustcraft.chunkStateAuthorityExperiment");
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!TARGET_CLASS.equals(transformedName) || basicClass == null) {
            return basicClass;
        }
        if (!enabled()) {
            lastStatus = "DISABLED_BY_PROPERTY";
            return basicClass;
        }

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            int patched = 0;

            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                // getBlockState(int, int, int) -> func_186032_a(III)LIBlockState;
                if (isGetBlockState(mn)) {
                    injectGetBlockStateHook(mn);
                    patched++;
                }
                // setBlockState(BlockPos, IBlockState) -> func_177436_a(LBlockPos;LIBlockState;)LIBlockState;
                else if (isSetBlockState(mn)) {
                    injectSetBlockStateHook(mn);
                    patched++;
                }
            }

            if (patched > 0) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastStatus = "HOOKS_INSTALLED_" + patched;
                return cw.toByteArray();
            }

            lastStatus = "TARGET_METHODS_NOT_FOUND";
            return basicClass;

        } catch (Throwable t) {
            System.err.println("[RustCraft] Failed to transform Chunk for State Authority: " + t.getMessage());
            lastStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }

    private boolean isGetBlockState(MethodNode mn) {
        return ("func_186032_a".equals(mn.name) || "getBlockState".equals(mn.name))
                && "(III)Lnet/minecraft/block/state/IBlockState;".equals(mn.desc);
    }

    private boolean isSetBlockState(MethodNode mn) {
        return ("func_177436_a".equals(mn.name) || "setBlockState".equals(mn.name))
                && "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;".equals(mn.desc);
    }

    private void injectGetBlockStateHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // IBlockState state = ChunkStateAuthorityBridge.getBlockState(this, x, y, z);
        // if (state != null) return state;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getBlockState",
                "(Lnet/minecraft/world/chunk/Chunk;III)Lnet/minecraft/block/state/IBlockState;",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFNULL, continueOriginal));
        hook.add(new InsnNode(Opcodes.ARETURN)); // Return Rust authoritative state

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard null

        mn.instructions.insert(hook);
    }

    private void injectSetBlockStateHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // MutationResult res = ChunkStateAuthorityBridge.trySetBlockState(this, pos, state);
        // if (res.handled) return res.oldState;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // pos
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2)); // state
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "trySetBlockState",
                "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lcom/rustcraft/bridge/ChunkStateAuthorityBridge$MutationResult;",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge$MutationResult",
                "handled",
                "Z"
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));

        // Handled: return res.oldState
        hook.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge$MutationResult",
                "oldState",
                "Lnet/minecraft/block/state/IBlockState;"
        ));
        hook.add(new InsnNode(Opcodes.ARETURN));

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard MutationResult

        mn.instructions.insert(hook);
    }
}
