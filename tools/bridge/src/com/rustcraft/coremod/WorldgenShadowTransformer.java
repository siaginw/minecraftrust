package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * M3 worldgen coremod transformer.
 *
 * Hooks ChunkGeneratorOverworld.func_185978_a(III)V (a(III)V).
 *
 * Modes (via minecraftrust.worldgen or minecraftrust.worldgen_shadow):
 *  - OFF: disabled, return unmodified class.
 *  - OFF_MEASURE: start/end timing hooks around vanilla method.
 *  - SHADOW: observe/compare hook at method RETURN.
 *  - ON_EXPERIMENTAL: authoritative native call at method ENTRY;
 *      if true  => RETURN immediately (Java noise computation bypassed);
 *      if false => fallback to original vanilla body.
 */
public class WorldgenShadowTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static String getMode() {
        return System.getProperty("minecraftrust.worldgen",
                System.getProperty("minecraftrust.worldgen_shadow", "OFF")).toUpperCase();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        String mode = getMode();
        if (!"net.minecraft.world.gen.ChunkGeneratorOverworld".equals(transformedName)
                || basicClass == null) {
            return basicClass;
        }
        if (!"SHADOW".equals(mode) && !"ON_EXPERIMENTAL".equals(mode) && !"OFF_MEASURE".equals(mode)) {
            lastStatus = "DISABLED_BY_PROPERTY";
            return basicClass;
        }

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            String fieldName = "field_185998_q";
            for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
                if ("[D".equals(fn.desc) && ("field_185998_q".equals(fn.name) || "q".equals(fn.name))) {
                    fieldName = fn.name;
                    break;
                }
            }

            boolean patched = false;
            for (MethodNode mn : cn.methods) {
                boolean isTarget = "(III)V".equals(mn.desc)
                        && ("func_185978_a".equals(mn.name) || "a".equals(mn.name));
                boolean isSetBlocks = "(IILnet/minecraft/world/chunk/ChunkPrimer;[Lnet/minecraft/world/biome/Biome;)V".equals(mn.desc)
                        && ("func_185977_a".equals(mn.name) || "a".equals(mn.name));
                boolean isTerrainGen = ("(IILnet/minecraft/world/chunk/ChunkPrimer;)V".equals(mn.desc)
                        || "(IILayw;)V".equals(mn.desc))
                        && ("func_185976_a".equals(mn.name) || "a".equals(mn.name));
                if ((isTarget || isSetBlocks || isTerrainGen) && ("SHADOW".equals(mode) || "ON_EXPERIMENTAL".equals(mode) || "OFF_MEASURE".equals(mode))) {
                    if (isTerrainGen && "SHADOW".equals(mode)) {
                        for (AbstractInsnNode insn : mn.instructions.toArray()) {
                            if (insn.getOpcode() == Opcodes.RETURN) {
                                InsnList hook = new InsnList();
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // generator (this)
                                hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // chunkX
                                hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // chunkZ
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 3)); // primer
                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/rustcraft/bridge/WorldgenShadow", "shadowTerrain",
                                        "(Ljava/lang/Object;IILjava/lang/Object;)V", false));
                                mn.instructions.insertBefore(insn, hook);
                                patched = true;
                            }
                        }
                    }
                    if (isTarget && "ON_EXPERIMENTAL".equals(mode)) {
                    // Inject at entry:
                    // if (WorldgenShadow.onExperimental(this, x4, z4, this.field_185998_q)) return;
                    LabelNode lContinue = new LabelNode();
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));          // this
                    hook.add(new VarInsnNode(Opcodes.ILOAD, 1));          // x4 (var1)
                    hook.add(new VarInsnNode(Opcodes.ILOAD, 3));          // z4 (var3)
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));          // this
                    hook.add(new FieldInsnNode(Opcodes.GETFIELD,
                            cn.name, fieldName, "[D"));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/rustcraft/bridge/WorldgenShadow", "onExperimental",
                            "(Ljava/lang/Object;II[D)Z", false));
                    hook.add(new JumpInsnNode(Opcodes.IFEQ, lContinue));
                    hook.add(new InsnNode(Opcodes.RETURN));
                    hook.add(lContinue);
                    hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                    mn.instructions.insertBefore(mn.instructions.getFirst(), hook);
                    patched = true;
                    lastStatus = "ON_EXPERIMENTAL_HOOK_INSTALLED";
                    } else if (isTarget && "OFF_MEASURE".equals(mode)) {
                    InsnList startHook = new InsnList();
                    startHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/rustcraft/bridge/WorldgenShadow", "measureStart", "()V", false));
                    mn.instructions.insertBefore(mn.instructions.getFirst(), startHook);

                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            InsnList endHook = new InsnList();
                            endHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "com/rustcraft/bridge/WorldgenShadow", "measureEnd", "()V", false));
                            mn.instructions.insertBefore(insn, endHook);
                        }
                    }
                    patched = true;
                    lastStatus = "OFF_MEASURE_HOOK_INSTALLED";
                    } else if (isTarget && "SHADOW".equals(mode)) {
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            InsnList hook = new InsnList();
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));          // this
                            hook.add(new VarInsnNode(Opcodes.ILOAD, 1));          // x4 (var1)
                            hook.add(new VarInsnNode(Opcodes.ILOAD, 3));          // z4 (var3)
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));          // this
                            hook.add(new FieldInsnNode(Opcodes.GETFIELD,
                                    cn.name, fieldName, "[D"));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "com/rustcraft/bridge/WorldgenShadow", "shadow",
                                    "(Ljava/lang/Object;II[D)V", false));
                            mn.instructions.insertBefore(insn, hook);
                            patched = true;
                        }
                    }
                    lastStatus = "SHADOW_HOOK_INSTALLED";
                    }
                    // Pre-population oracle hook at return of setBlocksInChunk
                    // (func_185977_a). Runs in OFF_MEASURE and ON_EXPERIMENTAL
                    // so both arms capture the exact base-terrain state before
                    // any population/decoration pass. READ-ONLY.
                    if (isSetBlocks) {
                        for (AbstractInsnNode insn : mn.instructions.toArray()) {
                            if (insn.getOpcode() == Opcodes.RETURN) {
                                InsnList pp = new InsnList();
                                pp.add(new VarInsnNode(Opcodes.ALOAD, 0)); // generator
                                pp.add(new VarInsnNode(Opcodes.ALOAD, 3)); // primer (var3)
                                pp.add(new VarInsnNode(Opcodes.ILOAD, 1)); // chunk-x (block coord /16)
                                pp.add(new VarInsnNode(Opcodes.ILOAD, 2)); // chunk-z
                                pp.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/rustcraft/bridge/WorldgenShadow", "prePopOracle",
                                        "(Ljava/lang/Object;Ljava/lang/Object;II)V", false));
                                mn.instructions.insertBefore(insn, pp);
                                patched = true;
                            }
                        }
                    }
                }
            }

            if (patched) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                transformCount++;
                return cw.toByteArray();
            }
            lastStatus = "INITNOISE_NOT_FOUND";
            return basicClass;
        } catch (Throwable t) {
            lastStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }
}
