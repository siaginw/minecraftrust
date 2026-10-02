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

    private static final String TARGET_CHUNK = "net.minecraft.world.chunk.Chunk";
    private static final String TARGET_STORAGE = "net.minecraft.world.chunk.storage.ExtendedBlockStorage";

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static boolean enabled() {
        return Boolean.getBoolean("rustcraft.chunkStateAuthorityExperiment");
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if (!TARGET_CHUNK.equals(transformedName) && !TARGET_STORAGE.equals(transformedName)) {
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

            if (TARGET_CHUNK.equals(transformedName)) {
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
                    // setStorageArrays(ExtendedBlockStorage[]) -> func_76602_a([LExtendedBlockStorage;)V
                    else if (isSetStorageArrays(mn)) {
                        injectSetStorageArraysHook(mn);
                        patched++;
                    }
                    // getHeight(int, int) -> func_76611_b(II)I
                    else if (isGetHeight(mn)) {
                        injectGetHeightHook(mn);
                        patched++;
                    }
                    // getBiome(BlockPos, BiomeProvider) -> func_177411_a(LBlockPos;LBiomeProvider;)LBiome;
                    else if (isGetBiome(mn)) {
                        injectGetBiomeHook(mn);
                        patched++;
                    }
                    // setBiomeArray(byte[]) -> func_76616_a([B)V
                    else if (isSetBiomeArray(mn)) {
                        injectSetBiomeArrayHook(mn);
                        patched++;
                    }
                }
            } else if (TARGET_STORAGE.equals(transformedName)) {
                for (MethodNode mn : (List<MethodNode>) cn.methods) {
                    // get(int, int, int) -> func_177485_a(III)LIBlockState;
                    if (isStorageGet(mn)) {
                        injectStorageGetHook(mn);
                        patched++;
                    }
                    // set(int, int, int, IBlockState) -> func_177484_a(IIILIBlockState;)V
                    else if (isStorageSet(mn)) {
                        injectStorageSetHook(mn);
                        patched++;
                    }
                    // getExtBlocklightValue(int, int, int) -> func_76670_c(III)I
                    else if (isGetBlockLight(mn)) {
                        injectGetBlockLightHook(mn);
                        patched++;
                    }
                    // setExtBlocklightValue(int, int, int, int) -> func_76657_c(IIII)V
                    else if (isSetBlockLight(mn)) {
                        injectSetBlockLightHook(mn);
                        patched++;
                    }
                    // getExtSkylightValue(int, int, int) -> func_76674_d(III)I
                    else if (isGetSkyLight(mn)) {
                        injectGetSkyLightHook(mn);
                        patched++;
                    }
                    // setExtSkylightValue(int, int, int, int) -> func_76677_d(IIII)V
                    else if (isSetSkyLight(mn)) {
                        injectSetSkyLightHook(mn);
                        patched++;
                    }
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
            System.err.println("[RustCraft] Failed to transform " + transformedName + " for State Authority: " + t.getMessage());
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

    private boolean isSetStorageArrays(MethodNode mn) {
        return ("func_76602_a".equals(mn.name) || "setStorageArrays".equals(mn.name))
                && "([Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;)V".equals(mn.desc);
    }

    private boolean isStorageGet(MethodNode mn) {
        return ("func_177485_a".equals(mn.name) || "get".equals(mn.name))
                && "(III)Lnet/minecraft/block/state/IBlockState;".equals(mn.desc);
    }

    private boolean isStorageSet(MethodNode mn) {
        return ("func_177484_a".equals(mn.name) || "set".equals(mn.name))
                && "(IIILnet/minecraft/block/state/IBlockState;)V".equals(mn.desc);
    }

    private void injectSetStorageArraysHook(MethodNode mn) {
        InsnList hook = new InsnList();
        // ChunkStateAuthorityBridge.onStorageArraysReplaced(this, newStorageArrays);
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (Chunk)
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // newStorageArrays
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "onStorageArraysReplaced",
                "(Lnet/minecraft/world/chunk/Chunk;[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;)V",
                false
        ));
        mn.instructions.insert(hook);
    }

    private void injectStorageGetHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // IBlockState state = ChunkStateAuthorityBridge.getSectionBlockState(this, x, y, z);
        // if (state != null) return state;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (ExtendedBlockStorage)
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getSectionBlockState",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;III)Lnet/minecraft/block/state/IBlockState;",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFNULL, continueOriginal));
        hook.add(new InsnNode(Opcodes.ARETURN)); // Return Rust authoritative state

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard null

        mn.instructions.insert(hook);
    }

    private void injectStorageSetHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // if (ChunkStateAuthorityBridge.trySetSectionBlockState(this, x, y, z, state)) return;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (ExtendedBlockStorage)
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new VarInsnNode(Opcodes.ALOAD, 4)); // state
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "trySetSectionBlockState",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;IIILnet/minecraft/block/state/IBlockState;)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
        hook.add(new InsnNode(Opcodes.RETURN)); // Early return: committed to Rust

        hook.add(continueOriginal);

        mn.instructions.insert(hook);
    }

    private boolean isGetBlockLight(MethodNode mn) {
        return ("func_76670_c".equals(mn.name) || "getExtBlocklightValue".equals(mn.name))
                && "(III)I".equals(mn.desc);
    }

    private boolean isSetBlockLight(MethodNode mn) {
        return ("func_76657_c".equals(mn.name) || "setExtBlocklightValue".equals(mn.name))
                && "(IIII)V".equals(mn.desc);
    }

    private boolean isGetSkyLight(MethodNode mn) {
        return ("func_76674_d".equals(mn.name) || "getExtSkylightValue".equals(mn.name))
                && "(III)I".equals(mn.desc);
    }

    private boolean isSetSkyLight(MethodNode mn) {
        return ("func_76677_d".equals(mn.name) || "setExtSkylightValue".equals(mn.name))
                && "(IIII)V".equals(mn.desc);
    }

    private void injectGetBlockLightHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // int light = ChunkStateAuthorityBridge.getBlockLight(this, x, y, z);
        // if (light >= 0) return light;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getBlockLight",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;III)I",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFLT, continueOriginal)); // if light < 0 (not handled), continue
        hook.add(new InsnNode(Opcodes.IRETURN)); // return light

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard -1

        mn.instructions.insert(hook);
    }

    private void injectSetBlockLightHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // if (ChunkStateAuthorityBridge.setBlockLight(this, x, y, z, val)) return;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new VarInsnNode(Opcodes.ILOAD, 4)); // val
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "setBlockLight",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;IIII)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
        hook.add(new InsnNode(Opcodes.RETURN)); // Early return

        hook.add(continueOriginal);

        mn.instructions.insert(hook);
    }

    private void injectGetSkyLightHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // int light = ChunkStateAuthorityBridge.getSkyLight(this, x, y, z);
        // if (light >= 0) return light;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getSkyLight",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;III)I",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFLT, continueOriginal)); // if light < 0, continue
        hook.add(new InsnNode(Opcodes.IRETURN)); // return light

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard -1

        mn.instructions.insert(hook);
    }

    private void injectSetSkyLightHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // if (ChunkStateAuthorityBridge.setSkyLight(this, x, y, z, val)) return;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // y
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // z
        hook.add(new VarInsnNode(Opcodes.ILOAD, 4)); // val
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "setSkyLight",
                "(Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;IIII)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
        hook.add(new InsnNode(Opcodes.RETURN)); // Early return

        hook.add(continueOriginal);

        mn.instructions.insert(hook);
    }

    private boolean isGetHeight(MethodNode mn) {
        return ("func_76611_b".equals(mn.name) || "getHeightValue".equals(mn.name))
                && "(II)I".equals(mn.desc);
    }

    private void injectGetHeightHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // int h = ChunkStateAuthorityBridge.getHeight(this, x, z);
        // if (h >= 0) return h;
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (Chunk)
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // x
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // z
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getHeight",
                "(Lnet/minecraft/world/chunk/Chunk;II)I",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFLT, continueOriginal)); // if h < 0, continue
        hook.add(new InsnNode(Opcodes.IRETURN));

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard -1

        mn.instructions.insert(hook);
    }

    private boolean isGetBiome(MethodNode mn) {
        return ("func_177411_a".equals(mn.name) || "getBiome".equals(mn.name))
                && "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/biome/BiomeProvider;)Lnet/minecraft/world/biome/Biome;".equals(mn.desc);
    }

    private void injectGetBiomeHook(MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        // int biomeId = ChunkStateAuthorityBridge.getBiome(this, pos.getX() & 15, pos.getZ() & 15);
        // if (biomeId >= 0) return Biome.getBiome(biomeId, Biomes.DEFAULT);
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (Chunk)
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // BlockPos
        hook.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/util/math/BlockPos",
                "func_177958_n",
                "()I",
                false
        ));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // BlockPos
        hook.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/util/math/BlockPos",
                "func_177952_p",
                "()I",
                false
        ));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "getBiome",
                "(Lnet/minecraft/world/chunk/Chunk;II)I",
                false
        ));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFLT, continueOriginal)); // if biomeId < 0, continue

        // Biome.getBiome(biomeId, Biomes.DEFAULT) -> Biome.getBiome(biomeId)
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/biome/Biome",
                "func_150568_d", // Biome.getBiome(int)
                "(I)Lnet/minecraft/world/biome/Biome;",
                false
        ));
        hook.add(new InsnNode(Opcodes.ARETURN));

        hook.add(continueOriginal);
        hook.add(new InsnNode(Opcodes.POP)); // Discard -1

        mn.instructions.insert(hook);
    }

    private boolean isSetBiomeArray(MethodNode mn) {
        return ("func_76616_a".equals(mn.name) || "setBiomeArray".equals(mn.name))
                && "([B)V".equals(mn.desc);
    }

    private void injectSetBiomeArrayHook(MethodNode mn) {
        InsnList hook = new InsnList();
        // ChunkStateAuthorityBridge.onBiomeArraySet(this, biomes);
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); // byte[]
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/ChunkStateAuthorityBridge",
                "onBiomeArraySet",
                "(Lnet/minecraft/world/chunk/Chunk;[B)V",
                false
        ));
        mn.instructions.insert(hook);
    }
}
