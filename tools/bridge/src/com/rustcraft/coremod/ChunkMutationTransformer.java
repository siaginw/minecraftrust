package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * M4.2A C2 — Chunk mutation hooks.
 *
 * Injects pure-Java bookkeeping calls (ChunkMutationTracker) into
 * net.minecraft.world.chunk.Chunk. NO JNI here: block writes record a dirty
 * bit; the M4Coherency flush drains masks across JNI in batch.
 *
 * Hook sites (SRG, matched by descriptor so notch/SRG/MCP all bind):
 *   func_177436_a (LBlockPos;LIBlockState;)LIBlockState;  setBlockState  -> onBlockSet(this, pos)   [all returns]
 *   func_177431_a (LEnumSkyBlock;LBlockPos;I)V            setLightFor    -> onLightSet(this, pos)   [all returns]
 *   func_76602_a   (LExtendedBlockStorage[];)V            setStorageArrays -> onStorageReplaced(this)
 *   func_76616_a   ([B)V                                  setBiomeArray  -> onBiomeChanged(this)
 *   func_76623_d   ()V                                    onChunkUnload  -> onUnload(this)
 *
 * Over-marking (no-op sets) is intentionally conservative: a spurious bit
 * costs one redundant section refresh, never incorrectness.
 *
 * Active only when -Dminecraftrust.m4.coherency=true (default off; hooks
 * compile to a single static call guarded by nothing further — the tracker
 * itself is a counter + bit set, ~ns).
 */
public class ChunkMutationTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static boolean enabled() {
        return Boolean.getBoolean("minecraftrust.m4.coherency");
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!"net.minecraft.world.chunk.Chunk".equals(transformedName) || basicClass == null) {
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
            for (MethodNode mn : cn.methods) {
                // setBlockState(BlockPos, IBlockState) — arg1 = pos
                if (is(mn, "func_177436_a", "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;Ljava/lang/Object;)V", "onBlockSet", 0, 1);
                }
                // setLightFor(EnumSkyBlock, BlockPos, int) — arg2 = pos
                else if (is(mn, "func_177431_a", "(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;I)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;Ljava/lang/Object;)V", "onLightSet", 0, 2);
                }
                // setStorageArrays(ExtendedBlockStorage[])
                else if (is(mn, "func_76602_a", "([Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onStorageReplaced", 0, -1);
                }
                // setBiomeArray(byte[])
                else if (is(mn, "func_76616_a", "([B)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onBiomeChanged", 0, -1);
                }
                // generateSkylightMap() — writes sky light directly, bypassing setLightFor
                else if (is(mn, "func_76630_e", "()V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onSkylightRegenerated", 0, -1);
                }
                // onLoad() — early first-touch sync for registered chunks
                else if (is(mn, "func_76631_c", "()V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onChunkLoaded", 0, -1);
                }
                // onChunkUnload()
                else if (is(mn, "func_76623_d", "()V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onUnload", 0, -1);
                }
            }

            if (patched > 0) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                transformCount++;
                lastStatus = "HOOKS_INSTALLED_" + patched;
                return cw.toByteArray();
            }
            lastStatus = "NO_TARGET_METHODS";
            return basicClass;
        } catch (Throwable t) {
            lastStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }

    private static boolean is(MethodNode mn, String srgName, String desc) {
        return mn.desc.equals(desc) && (srgName.equals(mn.name) || mn.name.length() <= 2);
    }

    /** Inserts a static tracker call before EVERY return in the method.
     *  chunkVar = the `this` local index; posVar = the BlockPos local (or -1). */
    private static int injectAllReturns(MethodNode mn, String trackerDesc, String trackerMethod,
                                        int chunkVar, int posVar) {
        int count = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN
                    || insn.getOpcode() == Opcodes.ARETURN
                    || insn.getOpcode() == Opcodes.IRETURN) {
                org.objectweb.asm.tree.InsnList hook = new org.objectweb.asm.tree.InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, chunkVar));
                if (posVar >= 0) {
                    hook.add(new VarInsnNode(Opcodes.ALOAD, posVar));
                }
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/ChunkMutationTracker",
                        trackerMethod, trackerDesc, false));
                mn.instructions.insertBefore(insn, hook);
                count++;
            }
        }
        return count;
    }
}
