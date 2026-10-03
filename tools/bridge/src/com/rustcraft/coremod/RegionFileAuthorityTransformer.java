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
 * Installs the RUST_REGION_WRITE_AUTHORITY experiment into
 * {@code net.minecraft.world.chunk.storage.RegionFile}:
 *
 * <ol>
 *   <li>adds a public per-instance state field {@code rustcraft$rwState}
 *       (Object, holds a RustRegionWriteHook.State);</li>
 *   <li>at the ENTRY of {@code func_76706_a(II[BI)V} (the synchronized seam
 *       ChunkBuffer.close hands the deflate stream to) injects the Rust
 *       admission call; on true the vanilla body is skipped with RETURN;</li>
 *   <li>before EVERY ORIGINAL return of the vanilla body injects the
 *       fallback note (the vanilla write completed; the engine resyncs);</li>
 *   <li>at the ENTRY of {@code func_76708_c()V} (close) injects the native
 *       handle free.</li>
 * </ol>
 *
 * <p>Injected bytecode at the write seam:</p>
 * <pre>
 *   ALOAD 0; ILOAD 1; ILOAD 2; ALOAD 3; ILOAD 4
 *   INVOKESTATIC com/rustcraft/bridge/RustRegionWriteHook.regionWriteEntry
 *       (Ljava/lang/Object;II[BI)Z
 *   IFEQ vanilla
 *   RETURN                       // Rust committed; vanilla body skipped
 *   vanilla:                     // IFEQ lands here
 *   ... original body, each ORIGINAL RETURN preceded by ...
 *   ALOAD 0; ILOAD 1; ILOAD 2
 *   INVOKESTATIC RustRegionWriteHook.regionWriteExit (Ljava/lang/Object;II)V
 * </pre>
 *
 * <p>DEFAULT OFF: zero bytecode modification unless
 * {@code -Drustcraft.regionWriteExperiment=true}. The hook itself fail-opens
 * on every path (never throws into RegionFile, always falls back to the
 * vanilla body). Method names are SRG (production Forge runtime); dev names
 * are matched as fallbacks so the transformer stays correct in fully-deobf
 * runtimes. func_76706_a is ACC_SYNCHRONIZED: the monitor is acquired by the
 * JVM before the first injected instruction, so admission and state init are
 * serialized per RegionFile by construction.</p>
 */
public class RegionFileAuthorityTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF =
            "net.minecraft.world.chunk.storage.RegionFile";
    /** 1.12.2 notch name of RegionFile: the only Minecraft class holding a
     *  java/io/RandomAccessFile (javap-verified on the notch server jar:
     *  fields b=File, c=RandomAccessFile, d=int[1024] offsets, e=int[1024]
     *  timestamps, f=List<Boolean> free sectors). At this transformer's chain
     *  position launchwrapper passes the NOTCH name as both name and
     *  transformedName — FML's deobfuscation runs later in the chain. */
    private static final String TARGET_CLASS_OBF = "ayj";
    private static final String HOOK = "com/rustcraft/bridge/RustRegionWriteHook";
    private static final String STATE_FIELD = "rustcraft$rwState";
    private static final String WRITE_DESC = "(II[BI)V";
    /** Write seam: notch "a" (overload disambiguated by the descriptor),
     *  SRG func_76706_a, dev write. */
    private static final String[] WRITE_NAMES = {"a", "func_76706_a", "write"};
    /** close(): notch "c" (throws IOException), SRG func_76708_c, dev close. */
    private static final String[] CLOSE_NAMES = {"c", "func_76708_c", "close"};

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";
    /** Boot-time diagnostics (shutdown log lines race log4j shutdown). */
    public static volatile boolean seamClassSeen = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.RustRegionWriteHook.ENABLED) {
            return basicClass; // default OFF: no bytecode modification
        }
        if (!TARGET_CLASS_DEOBF.equals(transformedName) && !TARGET_CLASS_OBF.equals(name)) {
            return basicClass;
        }
        seamClassSeen = true;
        // print DURING BOOT: shutdown-time prints race the log appender
        System.out.println("[RustCraft-RegionWrite] seam class seen: name=" + name
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

            boolean writeHooked = false;
            boolean closeHooked = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                if (WRITE_DESC.equals(mn.desc) && matchName(mn.name, WRITE_NAMES)) {
                    if (callsHook(mn, "regionWriteEntry")) {
                        writeHooked = true; // already instrumented (re-transform)
                    } else {
                        injectWriteHook(mn);
                        writeHooked = true;
                    }
                } else if (matchName(mn.name, CLOSE_NAMES) && "()V".equals(mn.desc)) {
                    if (!callsHook(mn, "regionFileClosing")) {
                        injectCloseHook(mn);
                    }
                    closeHooked = true;
                }
            }

            if (!writeHooked) {
                lastTransformStatus = "WRITE_SEAM_NOT_FOUND";
                return basicClass; // fail open: vanilla RegionFile
            }

            org.objectweb.asm.ClassWriter cw =
                    new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                            | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            transformCount++;
            lastTransformStatus = "REGION_WRITE_HOOK_PLACED close=" + closeHooked;
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[RustCraft] RegionFile authority transform failed "
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

    private static void injectWriteHook(MethodNode mn) {
        // 1. snapshot the ORIGINAL returns BEFORE any insertion so the
        //    skip-vanilla RETURN we add ourselves never gets an exit note
        List<AbstractInsnNode> originalReturns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                originalReturns.add(insn);
            }
        }

        // 2. entry hook: admit to Rust; true -> skip the vanilla body
        InsnList entry = new InsnList();
        LabelNode vanilla = new LabelNode();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 1));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 2));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 4));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "regionWriteEntry", "(Ljava/lang/Object;II[BI)Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        entry.add(new InsnNode(Opcodes.RETURN));
        entry.add(vanilla);
        mn.instructions.insert(entry);

        // 3. fallback note before every ORIGINAL return
        for (AbstractInsnNode ret : originalReturns) {
            mn.instructions.insertBefore(ret, buildExitNote());
        }
    }

    private static InsnList buildExitNote() {
        InsnList note = new InsnList();
        note.add(new VarInsnNode(Opcodes.ALOAD, 0));
        note.add(new VarInsnNode(Opcodes.ILOAD, 1));
        note.add(new VarInsnNode(Opcodes.ILOAD, 2));
        note.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "regionWriteExit", "(Ljava/lang/Object;II)V", false));
        return note;
    }

    private static void injectCloseHook(MethodNode mn) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "regionFileClosing", "(Ljava/lang/Object;)V", false));
        mn.instructions.insert(hook);
    }
}
