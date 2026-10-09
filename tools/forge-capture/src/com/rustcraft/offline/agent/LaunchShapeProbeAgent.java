package com.rustcraft.offline.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GENERIC launch-shape probe (§8/§9 runtime truth): after a configurable
 * delay, reports for each requested class whether it is DEFINED, its
 * defining loader and code source, plus the live launchwrapper transformer
 * inventory and blackboard keys. Knows nothing about any specific mod —
 * the class list comes entirely from the command line.
 *
 * Spec (system properties):
 *   rustcraft.probe.classes  semicolon-separated internal or dotted names
 *   rustcraft.probe.delayS   delay before the first probe (default 240)
 *   rustcraft.probe.repeatS  repeat interval; 0 = probe once (default 0)
 *
 * Output lines are prefixed [rustcraft-launch-probe] so campaign runners
 * can grep them from the server log.
 */
public final class LaunchShapeProbeAgent {

    public static void premain(String args, Instrumentation instrumentation) {
        final String dumpSpec =
                System.getProperty("rustcraft.probe.dumpClasses", "");
        if (!dumpSpec.trim().isEmpty()) {
            instrumentation.addTransformer(
                    new FinalByteDumper(dumpSpec), false);
        }
        final String classes =
                System.getProperty("rustcraft.probe.classes", "");
        if (classes.trim().isEmpty()) {
            return; // inert unless explicitly armed
        }
        final long delayMs = (long) (Double.parseDouble(
                System.getProperty("rustcraft.probe.delayS", "240"))
                * 1000.0);
        final long repeatMs = (long) (Double.parseDouble(
                System.getProperty("rustcraft.probe.repeatS", "0"))
                * 1000.0);
        Thread t = new Thread(() -> {
            long sleep = delayMs;
            while (true) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    return;
                }
                runProbe(classes);
                if (repeatMs <= 0) {
                    return;
                }
                sleep = repeatMs;
            }
        }, "rustcraft-launch-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Records FINAL post-chain bytes for requested classes (first
     *  definition only) into rustcraft.probe.dumpDir. Passive: never
     *  modifies bytes. */
    private static final class FinalByteDumper implements
            java.lang.instrument.ClassFileTransformer {
        private final java.util.Set<String> want = new java.util.HashSet<>();
        private final Path dumpDir;

        FinalByteDumper(String spec) {
            for (String s : spec.split(";")) {
                String n = s.trim().replace('.', '/').replace('$', '$');
                if (!n.isEmpty()) {
                    want.add(n);
                }
            }
            String dir = System.getProperty("rustcraft.probe.dumpDir",
                    "launch-probe-dumps");
            dumpDir = java.nio.file.Paths.get(dir);
        }

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> beingDefined,
                                java.security.ProtectionDomain domain,
                                byte[] bytes) {
            if (className == null || bytes == null
                    || !want.contains(className)) {
                return null;
            }
            try {
                Path out = dumpDir.resolve(className + ".class");
                if (!java.nio.file.Files.exists(out)) {
                    java.nio.file.Files.createDirectories(out.getParent());
                    java.nio.file.Files.write(out, bytes,
                            java.nio.file.StandardOpenOption.CREATE_NEW);
                    System.out.println("[rustcraft-launch-probe] dumped "
                            + className + " bytes=" + bytes.length
                            + " loader=" + (loader == null ? "bootstrap"
                                    : loader.getClass().getName()));
                }
            } catch (Throwable t) {
                System.out.println("[rustcraft-launch-probe] dump FAILED "
                        + className + ": " + t);
            }
            return null;
        }
    }

    private static void runProbe(String classes) {
        for (String raw : classes.split(";")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            probeClass(name);
        }
        probeTransformers();
        probeBlackboard();
    }

    private static ClassLoader launchLoader() {
        try {
            Class<?> launch = Class.forName(
                    "net.minecraft.launchwrapper.Launch", true,
                    LaunchShapeProbeAgent.class.getClassLoader());
            Field f = launch.getField("classLoader");
            Object cl = f.get(null);
            return (ClassLoader) cl;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void probeClass(String name) {
        String dotted = name.replace('/', '.');
        try {
            ClassLoader cl = launchLoader();
            Class<?> c = (cl != null)
                    ? Class.forName(dotted, false, cl)
                    : Class.forName(dotted, false,
                            Thread.currentThread().getContextClassLoader());
            Object source = c.getProtectionDomain().getCodeSource();
            System.out.println("[rustcraft-launch-probe] class " + dotted
                    + " DEFINED loader="
                    + c.getClassLoader().getClass().getName()
                    + " codeSource=" + (source == null ? "none" : source));
        } catch (ClassNotFoundException cnfe) {
            System.out.println("[rustcraft-launch-probe] class " + dotted
                    + " NOT_DEFINED");
        } catch (Throwable t) {
            System.out.println("[rustcraft-launch-probe] class " + dotted
                    + " PROBE_ERROR " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void probeTransformers() {
        try {
            ClassLoader cl = launchLoader();
            if (cl == null) {
                System.out.println("[rustcraft-launch-probe] transformers "
                        + "UNAVAILABLE (no launchwrapper)");
                return;
            }
            Method m = cl.getClass().getMethod("getTransformers");
            java.util.List<Object> chain = (java.util.List<Object>) m.invoke(cl);
            Map<String, Integer> byClass = new TreeMap<>();
            for (Object tr : chain) {
                String cn = tr.getClass().getName();
                byClass.merge(cn, 1, Integer::sum);
            }
            System.out.println("[rustcraft-launch-probe] transformers count="
                    + chain.size() + " " + byClass);
        } catch (Throwable t) {
            System.out.println("[rustcraft-launch-probe] transformers "
                    + "PROBE_ERROR " + t);
        }
    }

    private static void probeBlackboard() {
        try {
            Class<?> launch = Class.forName(
                    "net.minecraft.launchwrapper.Launch", true,
                    LaunchShapeProbeAgent.class.getClassLoader());
            Field f = launch.getField("blackboard");
            Map<Object, Object> bb = (Map<Object, Object>) f.get(null);
            StringBuilder sb = new StringBuilder();
            for (Object k : bb.keySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(k);
            }
            System.out.println("[rustcraft-launch-probe] blackboard keys="
                    + sb);
        } catch (Throwable t) {
            System.out.println("[rustcraft-launch-probe] blackboard "
                    + "PROBE_ERROR " + t);
        }
    }

    private LaunchShapeProbeAgent() { }
}
