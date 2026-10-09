package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY (first live proof: per-cell shadow).
 *
 * Hooks vanilla World.checkLightFor for the BLOCK type. Canonical identities
 * (tools/symbols, joined.srg — do not guess):
 *   MCP   checkLightFor  (Lnet/minecraft/world/EnumSkyBlock;
 *                          Lnet/minecraft/util/math/BlockPos;)Z
 *   SRG   func_180500_c  same deobf descriptor
 *   NOTCH amu.c         (Lana;Let;)Z
 * func_175638_a is World.getRawLight — (BlockPos, EnumSkyBlock)I, notch
 * amu.a (Let;Lana;)I — a DIFFERENT method (it is checkLightFor's value
 * kernel); targeting it as checkLightFor is a bug (guarded below).
 *
 * At this transformer's chain position launchwrapper passes the NOTCH class
 * name as both name and transformedName and the bytes are NOTCH (FML's
 * deobfuscation to SRG members runs later in the chain — proven live by the
 * RegionFile authority seam). The notch shapes are therefore the PRIMARY
 * match; SRG/MCP shapes are accepted for MCP-mapped launch shapes.
 *
 * HEAD: WorldLightHook.cellStart(world, type, pos) — captures the 6
 * neighbor block-light values + pos emission/opacity (via the same calls
 * vanilla makes: Block.getLightValue/getLightOpacity(state, world, pos))
 * and evaluates the Rust kernel for this cell.
 * RETURN (every IRETURN): WorldLightHook.cellEnd(world, type, pos) —
 * compares vanilla's own result against the kernel evaluation.
 *
 * Vanilla stays authoritative (SHADOW semantics). DEFAULT OFF
 * (-Drustcraft.lightExperiment). Per-cell = DIAGNOSTIC ONLY; ON authority
 * must move to a coarse boundary (no per-cell JNI).
 */
public class WorldLightTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.world.World";
    /** 1.12.2 notch name of World (joined.srg CL: amu). */
    private static final String TARGET_CLASS_OBF = "amu";
    private static final String HOOK = "com/rustcraft/bridge/WorldLightHook";
    private static final String DEOBF_CHECK_DESC =
            "(Lnet/minecraft/world/EnumSkyBlock;"
                    + "Lnet/minecraft/util/math/BlockPos;)Z";
    /** NOTCH descriptor of checkLightFor (joined.srg MD: amu/c). */
    private static final String NOTCH_CHECK_DESC = "(Lana;Let;)Z";
    /** NOTCH descriptor of getRawLight (joined.srg MD: amu/a) — forbidden. */
    private static final String NOTCH_RAWLIGHT_DESC = "(Let;Lana;)I";
    private static final String FORBIDDEN_NAME = "func_175638_a"; // getRawLight

    public static volatile int transformCount = 0;
    public static volatile int cellsInstrumented = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";
    public static volatile boolean seamClassSeen = false;

    // §2 name-capture diagnostics: bounded, cheap, proves what actually
    // flows through this transformer.
    private static final java.util.concurrent.atomic.AtomicLong CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Set<String> FIRST_NAMES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private static final java.util.Set<String> WORLDISH_NAMES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private static final int NAME_CAP = 300;

    public static String dumpDiagnostics() {
        return "calls=" + CALLS.get() + " seamClassSeen=" + seamClassSeen
                + " status=" + lastTransformStatus
                + " firstNames=" + new java.util.ArrayList<String>(FIRST_NAMES)
                + " worldish=" + new java.util.ArrayList<String>(WORLDISH_NAMES);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!com.rustcraft.bridge.WorldLightHook.ENABLED) {
            return basicClass;
        }
        CALLS.incrementAndGet();
        if (FIRST_NAMES.size() < NAME_CAP) {
            FIRST_NAMES.add(name + "|" + transformedName);
        }
        if (WORLDISH_NAMES.size() < 100
                && ((name != null && (name.equals(TARGET_CLASS_OBF)
                        || name.contains("World")))
                || (transformedName != null
                        && (transformedName.equals(TARGET_CLASS_DEOBF)
                        || transformedName.contains("World"))))) {
            WORLDISH_NAMES.add(name + "|" + transformedName);
        }
        if (!isTargetClass(name, transformedName)) {
            return basicClass;
        }
        seamClassSeen = true;
        // print DURING BOOT: shutdown-time prints race the log appender
        System.out.println("[RustCraft-Light] World seam class seen: name=" + name
                + " transformedName=" + transformedName
                + " bytes=" + (basicClass == null ? -1 : basicClass.length));
        if (basicClass == null) {
            lastTransformStatus = "NULL_BYTECODE";
            writeDiag(name, transformedName, basicClass, lastTransformStatus, null);
            return basicClass;
        }
        try {
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(basicClass).accept(cn, 0);

            MethodNode target = null;
            StringBuilder trace = new StringBuilder();
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                boolean candidate = isForbiddenTarget(mn.name, mn.desc)
                        || isTargetMethod(mn.name, mn.desc);
                if (candidate) {
                    trace.append("candidate name=").append(mn.name)
                            .append(" desc=").append(mn.desc)
                            .append(" exactMatch=").append(isTargetMethod(mn.name, mn.desc))
                            .append(" forbidden=").append(isForbiddenTarget(mn.name, mn.desc))
                            .append('\n');
                }
                // getRawLight PRESENCE in World is normal (it is
                // checkLightFor's value kernel); only ever refuse it as a
                // TARGET (both exact shapes matching at once is impossible)
                if (isTargetMethod(mn.name, mn.desc)) {
                    if (isForbiddenTarget(mn.name, mn.desc)) {
                        throw new IllegalStateException("refusing getRawLight"
                                + " as target — must be checkLightFor");
                    }
                    target = mn;
                }
            }
            writeDiag(name, transformedName, basicClass,
                    target == null ? "CHECK_LIGHT_FOR_NOT_FOUND" : "TARGET_MATCHED",
                    trace.length() == 0 ? "no candidate methods seen\n" : trace.toString());
            if (target == null) {
                lastTransformStatus = "CHECK_LIGHT_FOR_NOT_FOUND";
                return basicClass;
            }
            int returns = inject(target);
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                    org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            transformCount++;
            cellsInstrumented = returns;
            lastTransformStatus = "WORLD_CHECK_LIGHT_HOOKED returns=" + returns
                    + " method=" + target.name + target.desc;
            System.out.println("[RustCraft-Light] " + lastTransformStatus);
            writeDiag(name, transformedName, basicClass, lastTransformStatus, null);
            return cw.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            writeDiag(name, transformedName, basicClass, lastTransformStatus,
                    stackOf(t));
            return basicClass;
        }
    }

    /** True when name/transformedName is World in any launch shape. */
    public static boolean isTargetClass(String name, String transformedName) {
        return TARGET_CLASS_DEOBF.equals(transformedName)
                || TARGET_CLASS_DEOBF.equals(name)
                || TARGET_CLASS_OBF.equals(name)
                || TARGET_CLASS_OBF.equals(transformedName);
    }

    /** Exact (name, descriptor) pair matching — never name alone. */
    public static boolean isTargetMethod(String methodName, String desc) {
        // notch bytes at this chain position
        if ("c".equals(methodName) && NOTCH_CHECK_DESC.equals(desc)) return true;
        // SRG (FML-deobfuscated runtime shape) and MCP-mapped dev shape
        if ("func_180500_c".equals(methodName) && DEOBF_CHECK_DESC.equals(desc)) {
            return true;
        }
        return "checkLightFor".equals(methodName) && DEOBF_CHECK_DESC.equals(desc);
    }

    /** Regression guard: getRawLight (func_175638_a / notch amu.a (Let;Lana;)I)
     *  must never be hooked as checkLightFor. */
    public static boolean isForbiddenTarget(String methodName, String desc) {
        if (FORBIDDEN_NAME.equals(methodName)) return true;
        return "a".equals(methodName) && NOTCH_RAWLIGHT_DESC.equals(desc);
    }

    private static int inject(MethodNode mn) {
        int returns = 0;
        List<AbstractInsnNode> rets = new java.util.ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                rets.add(insn);
                returns++;
            }
        }
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new VarInsnNode(Opcodes.ALOAD, 2));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                "cellStart", "(Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;)V", false));
        mn.instructions.insert(head);
        // The FIRST IRETURN is checkLightFor's early exit (area not loaded:
        // vanilla returns false without recomputing). Comparing there would
        // count stale stored light as a parity failure — tag it separately.
        for (int i = 0; i < rets.size(); i++) {
            String hook = (i == 0) ? "cellEndSkip" : "cellEnd";
            InsnList tail = new InsnList();
            tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
            tail.add(new VarInsnNode(Opcodes.ALOAD, 2));
            tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    hook, "(Ljava/lang/Object;Ljava/lang/Object;"
                            + "Ljava/lang/Object;)V", false));
            mn.instructions.insertBefore(rets.get(i), tail);
        }
        return returns;
    }

    // -- §2 diagnostics: file + stdout, candidate class only (no spam) --

    private static volatile boolean diagWritten = false;

    private static void writeDiag(String name, String transformedName,
                                  byte[] bytes, String status, String detail) {
        Path p = diagPath();
        StringBuilder sb = new StringBuilder();
        sb.append("[WorldLightTransformer diag] ts=")
                .append(System.currentTimeMillis())
                .append(" name=").append(name)
                .append(" transformedName=").append(transformedName)
                .append(" bytes=").append(bytes == null ? -1 : bytes.length)
                .append(" status=").append(status).append('\n');
        if (!diagWritten) {
            // full candidate inventory once — enough to debug matching
            try {
                ClassNode cn = new ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(cn, 0);
                for (MethodNode mn : (List<MethodNode>) cn.methods) {
                    sb.append("  method ").append(mn.name).append(' ')
                            .append(mn.desc).append('\n');
                }
                diagWritten = true;
            } catch (Throwable ignore) {
                // status line above is still written
            }
        }
        if (detail != null) sb.append(detail);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignore) {
            // stdout/status remain the fallback evidence
        }
    }

    private static Path diagPath() {
        String prop = System.getProperty("rustcraft.lightDiagFile");
        if (prop != null && !prop.trim().isEmpty()) {
            return java.nio.file.Paths.get(prop.trim());
        }
        return java.nio.file.Paths.get("worldlight-transform-diag.log");
    }

    private static String stackOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
