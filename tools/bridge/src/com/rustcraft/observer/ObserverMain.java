package com.rustcraft.observer;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * FULL-STACK BENCHMARK measurement sampler (both arms, identical). JVM-8,
 * stdlib + com.sun.management only; no Minecraft classes are linked or
 * named — the server instance and its tick ring are found through the
 * LIVE object graph (the "Server thread"'s target Runnable), per the
 * repo's cross-loader rule (no Class.forName on game classes).
 *
 * Tick identity: MinecraftServer.tickTimes is a 100-slot ring written
 * once per tick at tickCounter%100. Sampling every 250ms sees at most
 * ceil(250/50)=5 new entries per slot-cycle — far below the ring length
 * — so a slot whose value CHANGED since the previous snapshot is exactly
 * one new tick (durations are System.nanoTime values; identical
 * consecutive durations do not occur in practice). Uninitialized slots
 * (0) are skipped, lag pauses produce no phantom ticks, and no tick is
 * counted twice. {@link #newTicks(long[], long[])} is static and pure —
 * unit-tested by MsptDedupRegression.
 *
 * Output (gameDir): observer-metrics.jsonl — one line per sampling
 * interval with ONLY the new tick durations plus process/server-thread
 * CPU, allocation, heap, and GC deltas (all deltas; cumulative values are
 * never emitted). Phases are assigned by wall-clock join against the
 * runner's phases.json.
 */
public final class ObserverMain {

    static final long SAMPLE_MS = 250;
    private static volatile boolean started;
    private static volatile Thread sampler;

    private ObserverMain() { }

    public static synchronized void start(final File gameDir) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> run(gameDir), "rustcraft-observer");
        t.setDaemon(true);
        t.start();
        sampler = t;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Thread s = sampler;
            if (s != null) {
                try { s.join(3000); } catch (InterruptedException e) { }
            }
        }, "rustcraft-observer-shutdown"));
    }

    /** pure tick-identity extraction: values in cur that differ from prev
     * (and are > 0) are new ticks. Package-visible for the unit test. */
    static long[] newTicks(long[] prev, long[] cur) {
        if (cur == null) return new long[0];
        int n = 0;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] > 0 && (prev == null || prev.length <= i
                    || cur[i] != prev[i])) n++;
        }
        long[] out = new long[n];
        int k = 0;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] > 0 && (prev == null || prev.length <= i
                    || cur[i] != prev[i])) out[k++] = cur[i];
        }
        return out;
    }

    private static void run(File gameDir) {
        final java.lang.management.ThreadMXBean tmx =
                ManagementFactory.getThreadMXBean();
        final com.sun.management.ThreadMXBean tmxa =
                (tmx instanceof com.sun.management.ThreadMXBean)
                        ? (com.sun.management.ThreadMXBean) tmx : null;
        final com.sun.management.OperatingSystemMXBean osx =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
        final List<GarbageCollectorMXBean> gcs =
                ManagementFactory.getGarbageCollectorMXBeans();
        final java.lang.management.MemoryMXBean memx =
                ManagementFactory.getMemoryMXBean();

        Object server = null;
        Field tickTimes = null;
        long[] prev = null;
        long prevProcCpu = osx.getProcessCpuTime();
        long prevGcCount = 0, prevGcMs = 0;
        for (GarbageCollectorMXBean g : gcs) {
            prevGcCount += Math.max(0, g.getCollectionCount());
            prevGcMs += Math.max(0, g.getCollectionTime());
        }
        long srvThreadId = -1;
        long prevSrvAlloc = 0;
        long prevSrvCpu = 0;

        try (PrintWriter out = new PrintWriter(
                new FileWriter(new File(gameDir, "observer-metrics.jsonl"),
                        true))) {
            while (true) {
                long t0 = System.currentTimeMillis();
                try {
                    if (tickTimes == null) {
                        Object s = findServerInstance();
                        Field f = s != null ? findTickRing(s) : null;
                        if (s != null && f != null) {
                            server = s; tickTimes = f;
                            out.println(j(t0, "attach",
                                    "\"field\":\"" + f.getName() + "\""));
                            out.flush();
                        }
                    } else {
                        long[] cur = (long[]) tickTimes.get(server);
                        long[] fresh = newTicks(prev, cur);
                        prev = cur != null ? cur.clone() : null;
                        long procCpu = osx.getProcessCpuTime();
                        long gcCount = 0, gcMs = 0;
                        for (GarbageCollectorMXBean g : gcs) {
                            gcCount += Math.max(0, g.getCollectionCount());
                            gcMs += Math.max(0, g.getCollectionTime());
                        }
                        if (srvThreadId < 0) {
                            for (Thread t : Thread.getAllStackTraces().keySet()) {
                                if ("Server thread".equals(t.getName())) {
                                    srvThreadId = t.getId();
                                    break;
                                }
                            }
                        }
                        long srvCpu = -1, srvAlloc = -1;
                        if (srvThreadId >= 0) {
                            try {
                                srvCpu = tmx.getThreadCpuTime(srvThreadId);
                            } catch (Throwable e) { }
                            if (tmxa != null) {
                                try {
                                    srvAlloc = tmxa.getThreadAllocatedBytes(
                                            srvThreadId);
                                } catch (Throwable e) { }
                            }
                        }
                        StringBuilder sb = new StringBuilder("{");
                        sb.append("\"t\":").append(t0)
                          .append(",\"tick_ns\":[");
                        for (int i = 0; i < fresh.length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(fresh[i]);
                        }
                        sb.append("],\"proc_cpu_ns\":").append(procCpu - prevProcCpu)
                          .append(",\"gc_n\":").append(gcCount - prevGcCount)
                          .append(",\"gc_ms\":").append(gcMs - prevGcMs);
                        if (srvCpu >= 0) {
                            sb.append(",\"srv_cpu_ns\":").append(
                                    Math.max(0, srvCpu - prevSrvCpu));
                            prevSrvCpu = srvCpu;
                        }
                        if (srvAlloc >= 0 && prevSrvAlloc >= 0
                                && srvAlloc >= prevSrvAlloc) {
                            sb.append(",\"srv_alloc_b\":")
                              .append(srvAlloc - prevSrvAlloc);
                        }
                        if (srvAlloc >= 0) prevSrvAlloc = srvAlloc;
                        java.lang.management.MemoryUsage mu =
                                memx.getHeapMemoryUsage();
                        sb.append(",\"heap_used\":").append(mu.getUsed())
                          .append(",\"heap_committed\":").append(mu.getCommitted())
                          .append('}');
                        out.println(sb);
                        out.flush();
                        prevProcCpu = procCpu;
                        prevGcCount = gcCount;
                        prevGcMs = gcMs;
                    }
                } catch (Throwable t) {
                    // measurement must never break the server; one diag line
                    try {
                        out.println(j(System.currentTimeMillis(), "error",
                                "\"msg\":\"" + escape(String.valueOf(t))
                                + "\""));
                        out.flush();
                    } catch (Throwable ignore) { }
                }
                long sleep = SAMPLE_MS - (System.currentTimeMillis() - t0);
                if (sleep > 0) {
                    try { Thread.sleep(sleep); }
                    catch (InterruptedException e) { return; }
                }
            }
        } catch (Throwable fatal) {
            System.err.println("[observer] fatal: " + fatal);
        }
    }

    private static String j(long t, String kind, String fields) {
        return "{\"t\":" + t + ",\"kind\":\"" + kind + "\"," + fields + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /** server instance via the LIVE object graph: the dedicated server
     * thread's target Runnable IS the MinecraftServer (DedicatedServer
     * constructs the thread with itself). No Class.forName on game
     * classes (cross-loader rule). */
    private static Object findServerInstance() {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (!"Server thread".equals(t.getName())) continue;
                Field tf = Thread.class.getDeclaredField("target");
                tf.setAccessible(true);
                Object cand = tf.get(t);
                Class<?> k = cand != null ? cand.getClass() : null;
                while (k != null && !k.getName().equals(
                        "net.minecraft.server.MinecraftServer")) {
                    k = k.getSuperclass();
                }
                if (k != null) return cand;
            }
        } catch (Throwable t) { }
        return null;
    }

    /** the 100-slot tickTimes long[] on the instance's OWN class chain. */
    private static Field findTickRing(Object server) {
        try {
            Class<?> k = server.getClass();
            while (k != null && !k.getName().equals(
                    "net.minecraft.server.MinecraftServer")) {
                k = k.getSuperclass();
            }
            if (k == null) return null;
            List<Field> found = new ArrayList<>();
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == long[].class) {
                    f.setAccessible(true);
                    found.add(f);
                }
            }
            for (Field f : found) {
                long[] arr = (long[]) f.get(server);
                if (arr != null && arr.length == 100) return f;
            }
        } catch (Throwable t) { }
        return null;
    }
}
