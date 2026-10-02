package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;

/**
 * M2C: installs the native compression encoder at the ONE vanilla seam.
 *
 * Verified vanilla behavior (javap of NetworkManager.setCompressionThreshold):
 *   handler = pipeline.get("compress");
 *   if (handler != null) ((NettyCompressionEncoder)handler).func_179299_a(t);
 *   else pipeline.addBefore("encoder", "compress", new NettyCompressionEncoder(t));
 *
 * This transformer rewrites ONLY the construction inside the method that
 * references the "compress" handler name: NEW NettyCompressionEncoder <init>(I)V
 * becomes NEW com/rustcraft/bridge/NativeCompressionEncoder <init>(I)V.
 * Consequences:
 * - the vanilla cast to NettyCompressionEncoder stays valid (ours subclasses it);
 * - runtime threshold updates (func_179299_a) route through our override;
 * - an already-present handler at "compress" is NEVER replaced (vanilla branch
 *   calls func_179299_a on whatever is there - unchanged vanilla semantics);
 * - mod code constructing NettyCompressionEncoder elsewhere is untouched
 *   (we only rewrite the method containing the "compress" LDC).
 * Gated by system property minecraftrust.native_compress (default OFF ->
 * no transformation at all).
 */
public class NetworkManagerCompressionTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    private static final String TARGET = "net.minecraft.network.NetworkManager";
    private static final String VANILLA_ENCODER = "net/minecraft/network/NettyCompressionEncoder";
    private static final String NATIVE_ENCODER = "com/rustcraft/bridge/NativeCompressionEncoder";

    private static final boolean ENABLED =
            !"OFF".equalsIgnoreCase(System.getProperty("minecraftrust.native_compress", "OFF"))
            // The passive corpus tap needs the swapped encoder even when the
            // compression MODE is OFF: the tap lives on the subclass and
            // capture must not depend on the authority mode.
            || System.getProperty("rustcraft.compressionCorpus") != null;

    /** 1.12.2 notch name for NetworkManager, identified by the unique
     *  "handleDisconnection() called twice" constant (same proof as the
     *  single-copy pair). Notch-named runtimes deliver the class under this
     *  name; SRG/deobf runtimes deliver the name above. */
    private static final String TARGET_OBF = "gw";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass != null && transformedName != null
                && transformedName.endsWith("NetworkManager")) {
            // One-shot visibility line: proves this transformer is registered,
            // which name shape the runtime uses, and the swap verdict.
            System.out.println("[RustCraft-Compression] NetworkManager transform: target=" + TARGET
                    + " transformedName=" + transformedName + " name=" + name
                    + " enabled=" + ENABLED);
        }
        boolean isTarget = TARGET.equals(transformedName)
                || (name != null && TARGET_OBF.equals(name));
        if (!isTarget || basicClass == null) {
            return basicClass;
        }
        if (!ENABLED) {
            lastStatus = "DISABLED_BY_PROPERTY";
            return basicClass;
        }
        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            int patched = 0;
            for (MethodNode mn : cn.methods) {
                if (!methodReferencesCompress(mn)) continue;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.NEW) {
                        TypeInsnNode t = (TypeInsnNode) insn;
                        if (VANILLA_ENCODER.equals(t.desc)) {
                            t.desc = NATIVE_ENCODER;
                            patched++;
                        }
                    } else if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                        MethodInsnNode m = (MethodInsnNode) insn;
                        if (VANILLA_ENCODER.equals(m.owner) && "<init>".equals(m.name)) {
                            m.owner = NATIVE_ENCODER;
                        }
                    }
                }
            }
            if (patched > 0) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastStatus = "TRANSFORMED_SUCCESS";
                return cw.toByteArray();
            }
            lastStatus = "COMPRESS_SITE_NOT_FOUND";
            return basicClass;
        } catch (Throwable t) {
            lastStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass; // safe fallback: unmodified bytecode
        }
    }

    private static boolean methodReferencesCompress(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.LDC) {
                Object cst = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
                if ("compress".equals(cst)) return true;
            }
        }
        return false;
    }
}
