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

    /**
     * ClassWriter whose getCommonSuperClass resolves through the
     * LaunchClassLoader. COMPUTE_FRAMES needs common-supers of Phosphor
     * types (PooledLongQueue etc.) which the default Class.forName-based
     * resolution cannot see from the ASM loader — the NPE that made the
     * first live transform fail.
     */
    private static final class ResolvingClassWriter extends org.objectweb.asm.ClassWriter {
        private final net.minecraft.launchwrapper.LaunchClassLoader loader;
        ResolvingClassWriter(int flags,
                             net.minecraft.launchwrapper.LaunchClassLoader loader) {
            super(flags);
            this.loader = loader;
        }
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (c1.isAssignableFrom(c2)) return type1;
                if (c2.isAssignableFrom(c1)) return type2;
                if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                Class<?> c = c1;
                do {
                    c = c.getSuperclass();
                } while (c != null && !c.isAssignableFrom(c2));
                return c == null ? "java/lang/Object"
                        : c.getName().replace('.', '/');
            } catch (Exception e) {
                return super.getCommonSuperClass(type1, type2);
            }
        }
    }

    private static final String TARGET =
            "me.jellysquid.mods.phosphor.mod.world.lighting.LightingEngine";
    private static final String HOOK = "com/rustcraft/bridge/PhosphorLightHook";
    private static final String DESC =
            "(Lnet/minecraft/world/EnumSkyBlock;)V";
    /** The INNER drain receives (type, queue) and consumes the batch —
     *  instrumenting IT means every invocation is a real job and the
     *  positions arrive as an argument (instrumenting the outer ForType,
     *  4,836 schedules drained past our HEAD read with an apparently-empty
     *  queue — dev23). */
    private static final String INNER_NAME = "processLightUpdatesForTypeInner";
    private static final String INNER_DESC =
            "(Lnet/minecraft/world/EnumSkyBlock;"
                    + "Lme/jellysquid/mods/phosphor/mod/collections/"
                    + "PooledLongQueue;)V";

    public static volatile int transformCount = 0;
    public static volatile int phosphorClassesSeen = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.PhosphorLightHook.ENABLED) {
            return basicClass;
        }
        if (name != null && name.contains("phosphor") && !name.contains("core")) {
            phosphorClassesSeen++;
        }
        if (!TARGET.equals(transformedName) && !TARGET.equals(name)) {
            if (name != null && name.endsWith("LightingEngine")) {
                System.out.println("[RustCraft-Light] LightingEngine passed but"
                        + " name mismatch: name=" + name + " transformedName="
                        + transformedName);
            }
            return basicClass;
        }
        if (basicClass == null) {
            return basicClass; // nothing to transform (tweaker-time probe)
        }
        try {
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);
            boolean hooked = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                if (("(Lnet/minecraft/world/EnumSkyBlock;"
                        + "Lnet/minecraft/util/math/BlockPos;)V").equals(mn.desc)
                        && "scheduleLightUpdate".equals(mn.name)) {
                    // scheduler-side counter: proves setblock traffic
                    // reaches the engine queue
                    InsnList schedHead = new InsnList();
                    schedHead.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HOOK, "scheduled", "()V", false));
                    mn.instructions.insert(schedHead);
                    hooked = true;
                    continue;
                }
                if ("()V".equals(mn.desc)
                        && "processLightUpdates".equals(mn.name)) {
                    // caller-side counter: proves whether the drain TICKS
                    InsnList callerHead = new InsnList();
                    callerHead.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HOOK, "drainTicked", "()V", false));
                    mn.instructions.insert(callerHead);
                    hooked = true;
                    continue;
                }
                if (INNER_DESC.equals(mn.desc)
                        && INNER_NAME.equals(mn.name)) {
                    InsnList head = new InsnList();
                    LabelNode runOriginal = new LabelNode();
                    head.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    head.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
                    head.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 2));
                    head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                            "jobStart", "(Ljava/lang/Object;Ljava/lang/Object;"
                                    + "Ljava/lang/Object;)Z", false));
                    head.add(new JumpInsnNode(Opcodes.IFEQ, runOriginal));
                    head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
                    head.add(runOriginal);
                    mn.instructions.insert(head);
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
                                    "jobEnd", "(Ljava/lang/Object;Ljava/lang/Object;"
                                            + ")V", false));
                            mn.instructions.insertBefore(ret, tail);
                        }
                    }
                    hooked = true;
                    continue;
                }
                if (DESC.equals(mn.desc)
                        && "processLightUpdatesForType".equals(mn.name)) {
                    // OUTER drain stays uninstrumented (dev23: its HEAD read
                    // saw an apparently-empty queue and would double-count
                    // against the INNER seam); only the counters remain.
                    hooked = true;
                    continue;
                }
            }
            if (!hooked) {
                lastTransformStatus = "SEAM_NOT_FOUND";
                return basicClass;
            }
            // The transformer class may be loaded by the APP classloader
            // (campaign jar on -cp) — do NOT cast our own loader. Resolve
            // the live LaunchClassLoader statically; absent (or not yet
            // created), fall back to ASM's default common-super resolution.
            net.minecraft.launchwrapper.LaunchClassLoader loader;
            try {
                Object cl = net.minecraft.launchwrapper.Launch.classLoader;
                loader = (cl instanceof net.minecraft.launchwrapper.LaunchClassLoader)
                        ? (net.minecraft.launchwrapper.LaunchClassLoader) cl
                        : null;
            } catch (Throwable t) {
                loader = null;
            }
            org.objectweb.asm.ClassWriter cw =
                    (loader == null)
                    ? new org.objectweb.asm.ClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                                    | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES)
                    : new ResolvingClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                                    | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES,
                            loader);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "LIGHT_HOOK_PLACED mode="
                    + (com.rustcraft.bridge.PhosphorLightHook.SHADOW ? "SHADOW" : "ON");
            return cw.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            // the log filter truncates multi-line STDERR; persist the full
            // stack to a file for the next diagnostic pass
            try {
                java.io.PrintWriter pw = new java.io.PrintWriter(
                        new java.io.FileWriter("light-transform-fail.txt", true));
                pw.println("[RustCraft-Light] transform failed");
                t.printStackTrace(pw);
                pw.close();
            } catch (Throwable ignore) { }
            System.err.println("[RustCraft-Light] transform failed: " + t);
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }
}
