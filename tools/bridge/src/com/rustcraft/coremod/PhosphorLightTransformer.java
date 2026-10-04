package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY: hooks Phosphor's
 * LightingEngine.processLightUpdatesForType(EnumSkyBlock) — the job boundary
 * of the BLOCK drain (jar-verified: MixinWorld cancels vanilla
 * checkLightFor and schedules into this engine).
 *
 * SHADOW: HEAD jobStart(engine, type) snapshots scheduled positions +
 * pre-state; original runs (Phosphor authoritative); before RETURN
 * jobEnd(engine, type) replays + compares.
 * ON_EXPERIMENTAL: HEAD jobStart returns true for admitted BLOCK jobs ->
 * injected RETURN skips Phosphor's block drain entirely (Rust committed);
 * SKY untouched.
 *
 * DEFAULT OFF (-Drustcraft.lightExperiment).
 */
public class PhosphorLightTransformer implements IClassTransformer {

    private static final String TARGET =
            "me.jellysquid.mods.phosphor.mod.world.lighting.LightingEngine";
    private static final String HOOK = "com/rustcraft/bridge/PhosphorLightHook";
    private static final String DESC =
            "(Lnet/minecraft/world/EnumSkyBlock;)V";

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.PhosphorLightHook.ENABLED) {
            return basicClass;
        }
        if (!TARGET.equals(transformedName) && !TARGET.equals(name)) {
            return basicClass;
        }
        try {
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);
            boolean hooked = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                if (!DESC.equals(mn.desc) || !"processLightUpdatesForType".equals(mn.name)) {
                    continue;
                }
                InsnList head = new InsnList();
                LabelNode runOriginal = new LabelNode();
                head.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                        "jobStart", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, runOriginal));
                head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
                head.add(runOriginal);
                mn.instructions.insert(head);
                hooked = true;
                // SHADOW: jobEnd before the final RETURN (replay + compare)
                if (com.rustcraft.bridge.PhosphorLightHook.SHADOW) {
                    AbstractInsnNode ret = mn.instructions.getLast();
                    while (ret != null && ret.getOpcode() != Opcodes.RETURN) {
                        ret = ret.getPrevious();
                    }
                    if (ret != null) {
                        InsnList tail = new InsnList();
                        tail.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                        tail.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
                        tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                                "jobEnd", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                        mn.instructions.insertBefore(ret, tail);
                    }
                }
            }
            if (!hooked) {
                lastTransformStatus = "SEAM_NOT_FOUND";
                return basicClass;
            }
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                    org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                            | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "LIGHT_HOOK_PLACED mode="
                    + (com.rustcraft.bridge.PhosphorLightHook.SHADOW ? "SHADOW" : "ON");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RustCraft-Light] transform failed: " + t);
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }
}
