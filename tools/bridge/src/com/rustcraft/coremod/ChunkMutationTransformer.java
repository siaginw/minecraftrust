package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
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
        boolean isChunk = "net.minecraft.world.chunk.Chunk".equals(transformedName);
        boolean isEbs = "net.minecraft.world.chunk.storage.ExtendedBlockStorage"
                .equals(transformedName);
        if ((!isChunk && !isEbs) || basicClass == null) {
            return basicClass;
        }
        if (isEbs) {
            // §1 seam selection (runtime evidence): Chunk.setBlockState's
            // Forge-patched bytecode reuses arg slots at ARETURN (zsa5/zsa6:
            // slot2 read a BlockPos, slot0 an IBlockState) — chunk-level
            // args are untrustworthy for a mirror. EBS.set is a trivial
            // method with stable int args and covers MORE writes (chunk
            // sets AND direct EBS writes). The state is read back from the
            // EBS itself post-write (canonical), so no arg slot is trusted
            // for identity.
            try {
                ClassReader cr = new ClassReader(basicClass);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                int patched = 0;
                for (MethodNode mn : AsmTreeCompat.methods(cn)) {
                    // EBS.set(x,y,z,state) — func_177484_a, notch a (III Lawt;)V
                    if (isExact(mn, "func_177484_a", "a",
                            "(IIILnet/minecraft/block/state/IBlockState;)V",
                            "(IIILawt;)V")) {
                        patched += injectEbsStateHook(mn);
                    }
                    // EBS.setBlockLight(x,y,z,value) — func_76677_d, notch b
                    // (IIII)V — the M4.2D light mirror seam (was previously
                    // registered under the Chunk-only gate: DEAD CODE — the
                    // class gate never matched ExtendedBlockStorage, so light
                    // coherence actually came from the section pull)
                    else if (isExact(mn, "func_76677_d", "b", "(IIII)V", "(IIII)V")) {
                        patched += injectEbsLightHook(mn);
                    }
                }
                if (patched > 0) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    transformCount++;
                    lastStatus = "EBS_HOOKS_" + patched;
                    return cw.toByteArray();
                }
                lastStatus = "EBS_NO_TARGETS";
                return basicClass;
            } catch (Throwable t) {
                lastStatus = "EBS_TRANSFORM_ERROR: " + t.getMessage();
                return basicClass;
            }
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
            // Through AsmTreeCompat, not cn.methods directly: that is the only
            // place a raw-typed ASM list is cast, and it is what lets these
            // sources compile against the generic-stripped asm-all the
            // Revelation runtime pins without swapping in another ASM.
            for (MethodNode mn : AsmTreeCompat.methods(cn)) {
                // setBlockState(BlockPos, IBlockState) — arg1 = pos,
                // arg2 = state (the mutation-seam STATE MIRROR needs the
                // actual IBlockState to resolve the full-width global id)
                // marks only — the STATE mirror lives at the EBS.set seam
                // (Forge chunk bytecode arg slots proved unreliable: zsa5/6)
                if (isExact(mn, "func_177436_a", "a", "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;", "(Let;Lawt;)Lawt;")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;Ljava/lang/Object;)V", "onBlockSet", 0, 1);
                }
                // setLightFor(EnumSkyBlock, BlockPos, int) — arg2 = pos
                else if (isExact(mn, "func_177431_a", "a", "(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;I)V", "(Lana;Let;I)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;Ljava/lang/Object;)V", "onLightSet", 0, 2);
                }
                // setStorageArrays(ExtendedBlockStorage[])
                else if (isExact(mn, "func_76602_a", "a", "([Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;)V", "([Laxx;)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onStorageReplaced", 0, -1);
                }
                // setBiomeArray(byte[])
                else if (isExact(mn, "func_76616_a", "a", "([B)V", "([B)V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onBiomeChanged", 0, -1);
                }
                // generateSkylightMap() — writes sky light directly, bypassing setLightFor
                else if (isExact(mn, "func_76630_e", "e", "()V", "()V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onSkylightRegenerated", 0, -1);
                }
                // onLoad() — early first-touch sync for registered chunks
                else if (isExact(mn, "func_76631_c", "c", "()V", "()V")) {
                    patched += injectAllReturns(mn, "(Ljava/lang/Object;)V", "onChunkLoaded", 0, -1);
                }
                // onChunkUnload()
                else if (isExact(mn, "func_76623_d", "d", "()V", "()V")) {
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

    /** Exact notch-name match (joined.srg): the length<=2 wildcard above
     *  SHADOWED seams — func_76630_e ()V matched every short ()V method,
     *  so onLoad (axw/c) and onChunkUnload (axw/d) were silently patched as
     *  onSkylightRegenerated and the world-registry hooks never fired. */
    private static boolean isExact(MethodNode mn, String srgName, String notchName,
                                   String srgDesc, String notchDesc) {
        if (srgName.equals(mn.name) && srgDesc.equals(mn.desc)) return true;
        return notchName.equals(mn.name) && notchDesc.equals(mn.desc);
    }

    /** Inserts a static tracker call before EVERY return in the method.
     *  chunkVar = the `this` local index; posVar = the BlockPos local (or -1). */
    /** EBS.set(x,y,z,state) STATE-MIRROR seam: this + x,y,z before every
     *  RETURN. The written state is read back from the EBS post-write in
     *  the tracker (canonical; no arg slot trusted for identity). */
    private static int injectEbsStateHook(MethodNode mn) {
        int count = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                org.objectweb.asm.tree.InsnList hook =
                        new org.objectweb.asm.tree.InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 3));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/ChunkMutationTracker",
                        "onEbsBlockSet",
                        "(Ljava/lang/Object;III)V", false));
                mn.instructions.insertBefore(insn, hook);
                count++;
            }
        }
        return count;
    }

    /** EBS.setBlockLight hook: this + x,y,z,value (4 ints) before every
     *  RETURN. Mirrors the light write into the native registry at the
     *  mutation point (M4.2D write seam). */
    private static int injectEbsLightHook(MethodNode mn) {
        int count = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                org.objectweb.asm.tree.InsnList hook =
                        new org.objectweb.asm.tree.InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 3));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 4));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/ChunkMutationTracker",
                        "onEbsBlockLightSet",
                        "(Ljava/lang/Object;IIII)V", false));
                mn.instructions.insertBefore(insn, hook);
                count++;
            }
        }
        return count;
    }

    /** ARETURN-only injection for the STATE MIRROR seam: DUPs the return
     *  value and passes (chunk, pos, state, result) — the mirror skips
     *  null results (vanilla rejects those writes, nothing was mutated).
     *  Straight-line code before existing returns: no new branch targets,
     *  stack shape at ARETURN is unchanged ([ret]). */
    private static int injectReturnsPassingResult(MethodNode mn, String trackerMethod,
                                                  int chunkVar, int posVar, int stateVar) {
        int count = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                org.objectweb.asm.tree.InsnList hook = new org.objectweb.asm.tree.InsnList();
                hook.add(new InsnNode(Opcodes.DUP));
                hook.add(new VarInsnNode(Opcodes.ALOAD, chunkVar));
                hook.add(new VarInsnNode(Opcodes.ALOAD, posVar));
                hook.add(new VarInsnNode(Opcodes.ALOAD, stateVar));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/ChunkMutationTracker",
                        trackerMethod,
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                        false));
                mn.instructions.insertBefore(insn, hook);
                count++;
            }
        }
        return count;
    }

    private static int injectAllReturns(MethodNode mn, String trackerDesc, String trackerMethod,
                                        int chunkVar, int posVar, int... extraVars) {
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
                for (int ev : extraVars) {
                    hook.add(new VarInsnNode(Opcodes.ALOAD, ev));
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
