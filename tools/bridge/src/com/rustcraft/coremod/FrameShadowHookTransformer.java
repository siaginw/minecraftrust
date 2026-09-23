package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * M-CK5-LIVE: injects the FrameShadowObserver live-install hook at the RETURN
 * instructions of NetworkManager.setCompressionThreshold (the unique (I)V
 * method referencing the "compress" handler name — same identification
 * fingerprint as NetworkManagerCompressionTransformer, verified by javap).
 *
 * Injected before every RETURN of that method:
 *   FrameShadowLiveHook.onCompressionThresholdSet(this, threshold);
 * (descriptor uses Object so it is identical in notch/SRG/MCP runtimes).
 *
 * The hook itself is property-gated (minecraftrust.frame_shadow, default OFF
 * -> returns immediately) and FAILS CLOSED: it verifies the live pipeline
 * layout by handler name before installing anything and never touches Java
 * behavior on any mismatch. When the property is OFF this transformer makes
 * NO changes at all.
 */
public class FrameShadowHookTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    private static final String TARGET = "net.minecraft.network.NetworkManager";
    private static final String HOOK_OWNER = "com/rustcraft/bridge/FrameShadowLiveHook";
    private static final String HOOK_DESC = "(Ljava/lang/Object;I)V";

    private static final boolean ENABLED =
            "LIVE".equalsIgnoreCase(System.getProperty("minecraftrust.frame_shadow", "OFF"))
            || !"OFF".equalsIgnoreCase(System.getProperty("minecraftrust.frame_authority", "OFF")); // M-CK6

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!TARGET.equals(transformedName) || basicClass == null) {
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
                if (!"V".equals(mn.desc.substring(mn.desc.indexOf(')') + 1))) continue; // void returns only
                if (!referencesCompress(mn)) continue;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (NetworkManager)
                        hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // threshold
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                "onCompressionThresholdSet", HOOK_DESC, false));
                        mn.instructions.insertBefore(insn, hook);
                        patched++;
                    }
                }
            }
            if (patched > 0) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastStatus = "HOOK_INSTALLED x" + patched;
                return cw.toByteArray();
            }
            lastStatus = "RETURN_SITE_NOT_FOUND";
            return basicClass;
        } catch (Throwable t) {
            lastStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass; // safe fallback: unmodified bytecode
        }
    }

    private static boolean referencesCompress(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.LDC) {
                Object cst = ((LdcInsnNode) insn).cst;
                if ("compress".equals(cst)) return true;
            }
        }
        return false;
    }
}
