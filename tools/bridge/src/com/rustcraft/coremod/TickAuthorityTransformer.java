package com.rustcraft.coremod;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * M3-A: scheduled-tick scheduler authority. Authorities-tier transformer
 * (registered AFTER the writer block — the [foreign][writers][authorities]
 * tiered topology; the writers' foreign-stage identity pins are unaffected
 * because this class injects after them, exactly like the other authority
 * transformers).
 *
 * Two head injections on WorldServer, both fail-closed (the hook returns
 * fall-through on any failure and the vanilla body runs):
 *
 * 1. func_175654_a (updateBlockTick) (BlockPos, Block, I, I)V head:
 *        aload 1..4 + invokestatic TickSchedulerHook.onUpdateBlockTick
 *        pop -> ifeq? NO: the hook ALWAYS returns false in v1 (it feeds
 *        the Rust queue; the vanilla body maintains the Java mirror), but
 *        the branch shape is kept so a future authoritative mirror-write
 *        flips one constant:
 *        if (hook(...)) return;
 *
 * 2. func_72955_a (tickUpdates) (Z)Z head:
 *        boolean r = TickSchedulerHook.onTickUpdates(this, runAllPending);
 *        if (TickSchedulerHook.LAST_HANDLED) return r;
 *    (the static handshake distinguishes handled from fall-through without
 *    a second local)
 *
 * Self-gates on rustcraft.tickAuthority != OFF. No other class is touched.
 */
public final class TickAuthorityTransformer implements IClassTransformer {

    public static int transformCount;
    private static volatile boolean diagPrinted;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    private static final String TARGET = "net.minecraft.world.WorldServer";
    private static final String TARGET_OBF = "oo";
    private static final String HOOK = "com/rustcraft/bridge/TickSchedulerHook";

    private static final boolean ENABLED =
            !"OFF".equalsIgnoreCase(System.getProperty("rustcraft.tickAuthorityMode", "OFF"));

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!ENABLED || basicClass == null) return basicClass;
        if (!TARGET.equals(transformedName) && !TARGET_OBF.equals(name)) return basicClass;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(basicClass).accept(cn, 0);
            int placed = 0;
            for (Object mo : cn.methods) {
                MethodNode mn = (MethodNode) mo;
                if (isUpdateBlockTick(mn)) {
                    injectUpdateBlockTickHead(mn);
                    placed++;
                } else if (isTickUpdates(mn)) {
                    injectTickUpdatesHead(mn);
                    placed++;
                }
            }
            if (placed != 2) {
                // anchor miss = refuse (fail closed, never partial)
                if (!diagPrinted) {
                    diagPrinted = true;
                    StringBuilder names = new StringBuilder();
                    for (Object mo : cn.methods) {
                        MethodNode m2 = (MethodNode) mo;
                        if ("a".equals(m2.name) || m2.name.startsWith("func_")) {
                            names.append(m2.name).append(m2.desc).append(' ');
                        }
                    }
                    System.out.println("[RustCraft-Tick] ANCHOR_MISS placed=" + placed
                            + " methods(sample)=" + names.substring(0, Math.min(400, names.length())));
                }
                lastStatus = "ANCHOR_MISS placed=" + placed;
                return basicClass;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); // branch injections need frames (dev-ON-3)
            cn.accept(cw);
            transformCount++;
            lastStatus = "TICK_AUTHORITY_HOOKED";
            return cw.toByteArray();
        } catch (Throwable t) {
            lastStatus = "ERROR: " + t;
            return basicClass; // fail closed
        }
    }

    // func_175654_a (Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V
    // name sets like every live transformer: notch "a" + SRG (the chain
    // may present either at our position); descriptor in both class-name
    // forms (deobf-full and notch-short Let;/Laow;)
    private static boolean isUpdateBlockTick(MethodNode mn) {
        boolean name = "a".equals(mn.name) || "func_175654_a".equals(mn.name)
                || "updateBlockTick".equals(mn.name);
        boolean desc = ("(Lnet/minecraft/util/math/BlockPos;"
                + "Lnet/minecraft/block/Block;II)V").equals(mn.desc)
                || "(Let;Laow;II)V".equals(mn.desc);
        return name && desc;
    }

    // func_72955_a (Z)Z
    private static boolean isTickUpdates(MethodNode mn) {
        return ("a".equals(mn.name) || "func_72955_a".equals(mn.name)
                || "tickUpdates".equals(mn.name)) && "(Z)Z".equals(mn.desc);
    }

    private static void injectUpdateBlockTickHead(MethodNode mn) {
        // head: if (TickSchedulerHook.onUpdateBlockTick(this, p1, p2, p3, p4)) return;
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onUpdateBlockTick",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;II)Z", false));
        LabelNode cont = new LabelNode();
        il.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, cont));
        il.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        il.add(cont);
        mn.instructions.insert(il);
    }

    private static void injectTickUpdatesHead(MethodNode mn) {
        // head: boolean r = Hook.onTickUpdates(this, p1);
        //       if (TickSchedulerHook.LAST_HANDLED) return r;
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onTickUpdates",
                "(Ljava/lang/Object;Z)Z", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));
        il.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, HOOK,
                "LAST_HANDLED", "Z"));
        LabelNode cont = new LabelNode();
        il.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, cont));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        il.add(cont);
        mn.instructions.insert(il);
    }

}
