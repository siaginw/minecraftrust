package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — the COARSE ON seam (goal §14-§17).
 *
 * Injects at the HEAD of World.checkLight (SRG func_175664_x, notch
 * amu.w, (Let;)Z — the per-mutation Java entry chosen over per-cell
 * checkLightFor). The injected call returns:
 *   -1  -> run the ORIGINAL body (Java/Phosphor owns the whole job)
 *   0/1 -> Rust owned the BLOCK propagation, the SKY half already ran
 *          through the live checkLightFor (Phosphor's scheduled path on
 *          Gate C) — return the packed flag.
 *
 * Exact (name, descriptor) pair matching, notch-first — the same live
 * chain position as WorldLightTransformer (launchwrapper passes notch
 * bytes at the tweaker chain position). The original body is preserved
 * byte-for-byte as the fallback path: SKY semantics, return semantics,
 * exception semantics and Forge side effects are untouched for
 * non-admitted jobs. DEFAULT OFF (-Drustcraft.lightExperiment
 * + -Drustcraft.lightMode=ON_EXPERIMENTAL).
 */
public class CheckLightAuthorityTransformer implements IClassTransformer {

    /** ClassWriter whose getCommonSuperClass resolves through the live
     *  LaunchClassLoader (COMPUTE_FRAMES needs common supers of runtime
     *  types the App loader cannot see). */
    private static final class ResolvingClassWriter
            extends org.objectweb.asm.ClassWriter {
        private final net.minecraft.launchwrapper.LaunchClassLoader loader;

        ResolvingClassWriter(int flags,
                             net.minecraft.launchwrapper.LaunchClassLoader loader) {
            super(flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false,
                        loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false,
                        loader);
                if (c1.isAssignableFrom(c2)) return type1;
                if (c2.isAssignableFrom(c1)) return type2;
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
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

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.world.World";
    /** 1.12.2 notch name of World (joined.srg CL: amu). */
    private static final String TARGET_CLASS_OBF = "amu";
    private static final String HOOK = "com/rustcraft/bridge/LightAuthorityHook";
    private static final String METHOD_NAME = "w"; // notch checkLight
    private static final String METHOD_DESC = "(Let;)Z";

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";
    public static volatile boolean seamClassSeen = false;

    /** Exact-pair predicate (exposed for the self-test). */
    public static boolean isTargetMethod(String name, String desc) {
        // notch bytes at this chain position; the MCP shape is accepted
        // for mapped launch shapes
        return ("w".equals(name) && "(Let;)Z".equals(desc))
                || ("checkLight".equals(name)
                        && "(Lnet/minecraft/util/math/BlockPos;)Z".equals(desc))
                || ("func_175664_x".equals(name)
                        && "(Lnet/minecraft/util/math/BlockPos;)Z".equals(desc));
    }

    @Override
    public byte[] transform(String name, String transformedName,
                            byte[] basicClass) {
        if (!com.rustcraft.bridge.LightAuthorityHook.ON) {
            return basicClass;
        }
        boolean isTarget = TARGET_CLASS_OBF.equals(name)
                || TARGET_CLASS_OBF.equals(transformedName)
                || TARGET_CLASS_DEOBF.equals(name)
                || TARGET_CLASS_DEOBF.equals(transformedName);
        if (!isTarget) {
            return basicClass;
        }
        seamClassSeen = true;
        // print DURING BOOT (the region-transformer lesson: shutdown prints
        // race the log appender)
        System.out.println("[RustCraft-Light] checkLight seam class seen:"
                + " name=" + name + " transformedName=" + transformedName
                + " bytes=" + (basicClass == null ? -1 : basicClass.length)
                + " ON=" + com.rustcraft.bridge.LightAuthorityHook.ON
                + " mode=" + com.rustcraft.bridge.LightAuthorityHook.MODE);
        try {
            org.objectweb.asm.tree.ClassNode cn =
                    new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);
            boolean hooked = false;
            for (org.objectweb.asm.tree.MethodNode mn :
                    (java.util.List<org.objectweb.asm.tree.MethodNode>) (Object) cn.methods) {
                boolean notchHit = METHOD_NAME.equals(mn.name)
                        && METHOD_DESC.equals(mn.desc);
                boolean mcpHit = isTargetMethod(mn.name, mn.desc);
                if (!notchHit && !mcpHit) {
                    continue;
                }
                // HEAD: int r = LightAuthorityHook.onCheckLight(world, pos);
                //       if (r >= 0) return r == 1;
                // Stack-neutral injection: r on the operand stack only,
                // NO local slots (an ISTORE into an original local made
                // COMPUTE_FRAMES NPE at Frame.merge — dev-ON-3). Owned
                // path returns r==1; fallback POPS r and falls through
                // with an exactly-original stack.
                InsnList head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                        "onCheckLight",
                        "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
                head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                LabelNode runOriginal = new LabelNode();
                head.add(new JumpInsnNode(Opcodes.IFLT, runOriginal));
                LabelNode returnZero = new LabelNode();
                head.add(new JumpInsnNode(Opcodes.IFEQ, returnZero));
                head.add(new org.objectweb.asm.tree.InsnNode(
                        Opcodes.ICONST_1));
                head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
                head.add(returnZero);
                head.add(new org.objectweb.asm.tree.InsnNode(
                        Opcodes.ICONST_0));
                head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
                head.add(runOriginal);
                head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
                mn.instructions.insert(head);
                hooked = true;
                // note: local slot 2 is safe — checkLight uses only args
                // (this, pos = slots 0/1); the original body allocates its
                // own locals from slot 2 upward ONLY IF it reaches them,
                // and COMPUTE_MAXS re-sizes frames. To avoid ANY collision
                // with original locals we jump around the body without
                // touching it — the original body's slot usage is
                // unreachable on the owned path.
            }
            if (!hooked) {
                lastTransformStatus = "CHECK_LIGHT_NOT_FOUND";
                return basicClass;
            }
            // COMPUTE_FRAMES is REQUIRED here: the injected branch targets
            // need stack map frames (Java 8 classfiles) and COMPUTE_MAXS
            // does not regenerate them (the dev-ON-1 VerifyError). Common-
            // supers resolve through the LIVE LaunchClassLoader (the
            // PhosphorLightTransformer pattern) — the transformer class
            // itself is App-loader-loaded and cannot see runtime classes.
            net.minecraft.launchwrapper.LaunchClassLoader loader;
            try {
                Object cl = net.minecraft.launchwrapper.Launch.classLoader;
                loader = (cl instanceof net.minecraft.launchwrapper.LaunchClassLoader)
                        ? (net.minecraft.launchwrapper.LaunchClassLoader) cl
                        : null;
            } catch (Throwable t) {
                loader = null;
            }
            org.objectweb.asm.ClassWriter cw = (loader == null)
                    ? new org.objectweb.asm.ClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_FRAMES)
                    : new ResolvingClassWriter(
                            org.objectweb.asm.ClassWriter.COMPUTE_FRAMES,
                            loader);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "CHECK_LIGHT_AUTHORITY_HOOKED";
            System.out.println("[RustCraft-Light] " + lastTransformStatus);
            return cw.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }
}
