package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Installs the RUST_REGION_READ_DECOMPRESSION_AUTHORITY experiment into
 * {@code net.minecraft.world.chunk.storage.RegionFile}'s synchronized read
 * seam {@code func_76704_a(II)Ljava/io/DataInputStream;} (notch {@code ayj},
 * method {@code a} — overload disambiguated by the descriptor; FML's
 * deobfuscation runs later in the chain, matching the write-side precedent).
 *
 * <p>State field {@code rustcraft$rrState} is added alongside the write
 * experiment's field.</p>
 *
 * <p>ON_EXPERIMENTAL — injected at method ENTRY:</p>
 * <pre>
 *   ALOAD 0; ILOAD 1; ILOAD 2
 *   INVOKESTATIC RustRegionReadHook.regionRead (Ljava/lang/Object;II)Ljava/io/DataInputStream;
 *   DUP; IFNULL vanilla
 *   ARETURN                       // Rust supplied the fully-validated stream
 *   vanilla: POP                  // drop the null
 *   ... original body ...
 * </pre>
 *
 * <p>SHADOW — ENTRY hook stashes Rust's independent decompression, and
 * before EVERY original ARETURN the vanilla-authoritative stream is wrapped
 * in the tee comparator:</p>
 * <pre>
 *   ... original body ...
 *   // before each original ARETURN (value on stack):
 *   ASTORE v; ALOAD 0; ILOAD 1; ILOAD 2
 *   INVOKESTATIC RustRegionReadHook.shadowWrap ... then re-load v and wrap
 * </pre>
 * implemented as: store the original result, call
 * {@code shadowWrap(DataInputStream, Object, int, int)}, leave the (possibly
 * wrapped) result on the stack for ARETURN.
 *
 * <p>DEFAULT OFF: zero bytecode modification unless
 * {@code -Drustcraft.regionReadExperiment=true}. The hook fail-opens on every
 * path. Idempotent via the injected-call marker check.</p>
 */
public class RegionFileReadTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF =
            "net.minecraft.world.chunk.storage.RegionFile";
    private static final String TARGET_CLASS_OBF = "ayj";
    private static final String HOOK = "com/rustcraft/bridge/RustRegionReadHook";
    private static final String STATE_FIELD = "rustcraft$rrState";
    private static final String READ_DESC = "(II)Ljava/io/DataInputStream;";
    private static final String[] READ_NAMES = {"a", "func_76704_a", "read"};
    private static final String[] CLOSE_NAMES = {"c", "func_76708_c", "close"};

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";
    public static volatile boolean seamClassSeen = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.RustRegionReadHook.ENABLED) {
            return basicClass; // default OFF: no bytecode modification
        }
        if (!TARGET_CLASS_DEOBF.equals(transformedName) && !TARGET_CLASS_OBF.equals(name)) {
            return basicClass;
        }
        seamClassSeen = true;
        System.out.println("[RustCraft-RegionRead] seam class seen: name=" + name
                + " transformedName=" + transformedName
                + " bytes=" + (basicClass == null ? -1 : basicClass.length));
        if (basicClass == null) {
            lastTransformStatus = "NULL_BYTECODE";
            return basicClass;
        }
        try {
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);

            boolean hasField = false;
            for (Object fObj : cn.fields) {
                if (STATE_FIELD.equals(((org.objectweb.asm.tree.FieldNode) fObj).name)) {
                    hasField = true;
                    break;
                }
            }
            if (!hasField) {
                cn.fields.add(new org.objectweb.asm.tree.FieldNode(
                        Opcodes.ACC_PUBLIC, STATE_FIELD,
                        "Ljava/lang/Object;", null, null));
            }

            boolean shadow = com.rustcraft.bridge.RustRegionReadHook.SHADOW;
            boolean readHooked = false;
            boolean closeHooked = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                if (READ_DESC.equals(mn.desc) && matchName(mn.name, READ_NAMES)) {
                    if (callsHook(mn, shadow ? "regionReadEnter" : "regionRead")) {
                        readHooked = true; // already instrumented
                    } else {
                        injectReadHook(mn, shadow);
                        readHooked = true;
                    }
                } else if (matchName(mn.name, CLOSE_NAMES) && "()V".equals(mn.desc)) {
                    if (!callsHook(mn, "regionFileClosing")) {
                        injectCloseHook(mn);
                    }
                    closeHooked = true;
                }
            }

            if (!readHooked) {
                lastTransformStatus = "READ_SEAM_NOT_FOUND";
                return basicClass; // fail open: vanilla RegionFile
            }

            org.objectweb.asm.ClassWriter cw =
                    new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                            | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "REGION_READ_HOOK_PLACED mode="
                    + (shadow ? "SHADOW" : "ON") + " close=" + closeHooked;
            System.out.println("[RustCraft-RegionRead] " + lastTransformStatus);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RustCraft-RegionRead] transform failed "
                    + "(vanilla preserved): " + t);
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            return basicClass;
        }
    }

    private static boolean matchName(String actual, String[] candidates) {
        for (String c : candidates) {
            if (c.equals(actual)) return true;
        }
        return false;
    }

    private static boolean callsHook(MethodNode mn, String hookMethod) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (HOOK.equals(call.owner) && hookMethod.equals(call.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void injectReadHook(MethodNode mn, boolean shadow) {
        if (!shadow) {
            InsnList entry = new InsnList();
            LabelNode vanilla = new LabelNode();
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new VarInsnNode(Opcodes.ILOAD, 1));
            entry.add(new VarInsnNode(Opcodes.ILOAD, 2));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    "regionRead", "(Ljava/lang/Object;II)Ljava/io/DataInputStream;", false));
            entry.add(new InsnNode(Opcodes.DUP));
            entry.add(new JumpInsnNode(Opcodes.IFNULL, vanilla));
            entry.add(new InsnNode(Opcodes.ARETURN));
            entry.add(vanilla);
            entry.add(new InsnNode(Opcodes.POP));
            mn.instructions.insert(entry);
            return;
        }
        // SHADOW: entry snapshot
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 1));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 2));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "regionReadEnter", "(Ljava/lang/Object;II)Z", false));
        entry.add(new InsnNode(Opcodes.POP));
        mn.instructions.insert(entry);

        // wrap before every original ARETURN (the DataInputStream result is
        // on the stack): result -> shadowWrap(result, this, x, z).
        // Scratch local slot = mn.maxLocals (never touched by the original
        // body; the body stores sectorOffset in slot 5 etc.) — bump maxLocals.
        List<AbstractInsnNode> originalReturns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                originalReturns.add(insn);
            }
        }
        int scratch = mn.maxLocals;
        mn.maxLocals = scratch + 1;
        for (AbstractInsnNode ret : originalReturns) {
            InsnList wrap = new InsnList();
            wrap.add(new VarInsnNode(Opcodes.ASTORE, scratch));
            wrap.add(new VarInsnNode(Opcodes.ALOAD, scratch));
            wrap.add(new VarInsnNode(Opcodes.ALOAD, 0));
            wrap.add(new VarInsnNode(Opcodes.ILOAD, 1));
            wrap.add(new VarInsnNode(Opcodes.ILOAD, 2));
            wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    "shadowWrap",
                    "(Ljava/io/DataInputStream;Ljava/lang/Object;II)Ljava/io/DataInputStream;",
                    false));
            mn.instructions.insertBefore(ret, wrap);
        }
    }

    private static void injectCloseHook(MethodNode mn) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "regionFileClosing", "(Ljava/lang/Object;)V", false));
        mn.instructions.insert(hook);
    }
}
