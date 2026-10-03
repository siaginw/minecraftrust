package com.rustcraft.offline.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/**
 * RUST_REGION_WRITE_AUTHORITY goal-§4 dynamic writer attribution agent.
 *
 * TEST-ONLY: active exclusively under -Drustcraft.writerAttribution=true.
 * Instruments java.io.RandomAccessFile's write entry points so EVERY Java-side
 * write to a region .mca path is captured with thread + stack + offset +
 * length. The helper (WriterAttribution) is appended to the bootstrap search
 * because RandomAccessFile is bootstrap-loaded.
 *
 * Injection into RandomAccessFile.write(byte[], int, int):
 * <pre>
 *   ALOAD 0; ALOAD 0; GETFIELD path Ljava/lang/String;; ILOAD 3
 *   INVOKESTATIC com/rustcraft/offline/agent/WriterAttribution.rafWrite
 *       (Ljava/lang/Object;Ljava/lang/String;I)V
 * </pre>
 * and write(int) pushes iload_1 instead. All region-file writes funnel
 * through these two entries on JDK 8 (writeInt/write(byte[]) delegate here).
 */
public final class WriterAttributionAgent implements ClassFileTransformer {

    public static void premain(String args, Instrumentation inst) {
        if (!Boolean.getBoolean("rustcraft.writerAttribution")) {
            return; // test-only: zero transformation by default
        }
        try {
            File self = selfJar();
            if (self != null) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
            } else {
                System.err.println("[writer-attribution] cannot locate agent "
                        + "jar; helper bootstrap attach skipped");
            }
            String dir = System.getProperty("rustcraft.writerAttributionDir",
                    "writer-attribution");
            WriterAttribution.init(new File(dir, "writer-attribution.jsonl")
                    .getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[writer-attribution] init failed: " + t);
            return;
        }
        inst.addTransformer(new WriterAttributionAgent(), false);
        System.out.println("[writer-attribution] agent active (RandomAccessFile "
                + "write capture)");
    }

    static File selfJar() {
        try {
            return new File(WriterAttributionAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> beingRedefined, ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (!"java/io/RandomAccessFile".equals(className)) {
            return null;
        }
        try {
            return instrument(classfileBuffer);
        } catch (Throwable t) {
            System.err.println("[writer-attribution] transform failed: " + t);
            return null; // fail-open: uninstrumented JDK class
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private byte[] instrument(byte[] bytes) throws Exception {
        org.objectweb.asm.ClassReader cr =
                new org.objectweb.asm.ClassReader(bytes);
        org.objectweb.asm.tree.ClassNode cn =
                new org.objectweb.asm.tree.ClassNode();
        cr.accept(cn, 0);
        boolean done = false;
        for (Object mObj : cn.methods) {
            org.objectweb.asm.tree.MethodNode mn =
                    (org.objectweb.asm.tree.MethodNode) mObj;
            boolean isBaii = "(BII)V".equals(mn.desc)
                    && "write".equals(mn.name);
            boolean isI = "(I)V".equals(mn.desc) && "write".equals(mn.name);
            if (!isBaii && !isI) {
                continue;
            }
            org.objectweb.asm.tree.InsnList hook =
                    new org.objectweb.asm.tree.InsnList();
            hook.add(new org.objectweb.asm.tree.VarInsnNode(
                    org.objectweb.asm.Opcodes.ALOAD, 0));
            hook.add(new org.objectweb.asm.tree.VarInsnNode(
                    org.objectweb.asm.Opcodes.ALOAD, 0));
            hook.add(new org.objectweb.asm.tree.FieldInsnNode(
                    org.objectweb.asm.Opcodes.GETFIELD,
                    "java/io/RandomAccessFile", "path",
                    "Ljava/lang/String;"));
            hook.add(new org.objectweb.asm.tree.VarInsnNode(
                    org.objectweb.asm.Opcodes.ILOAD,
                    isBaii ? 3 : 1));
            hook.add(new org.objectweb.asm.tree.MethodInsnNode(
                    org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "com/rustcraft/offline/agent/WriterAttribution",
                    "rafWrite",
                    "(Ljava/lang/Object;Ljava/lang/String;I)V", false));
            mn.instructions.insert(hook);
            done = true;
        }
        if (!done) {
            System.err.println("[writer-attribution] no write methods found");
            return null;
        }
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
