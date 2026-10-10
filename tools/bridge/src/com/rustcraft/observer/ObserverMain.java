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
        final boolean allocSampler = Boolean.getBoolean(
                "rustcraft.observer.allocSampler");
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

        // OPT-FS-001 §9 diagnostic: attribute server-thread allocation RATE
        // to stack tops by 1ms delta sampling (off-thread; the getStackTrace
        // safepoint cost lands in this diagnostic run only, never in a
        // performance-result run). Aggregated in-JVM; dumped at exit.
        final java.util.concurrent.ConcurrentHashMap<String, long[]>
                allocSites = new java.util.concurrent.ConcurrentHashMap<>();
        Thread allocSamplerThread = null;
        if (allocSampler && tmxa != null) {
            allocSamplerThread = new Thread(() -> {
                Thread server = null;
                long prev = 0;
                boolean prevValid = false;
                long beat = 0;
                while (true) {
                    try {
                        if (server == null || !server.isAlive()) {
                            for (Thread t : Thread.getAllStackTraces().keySet()) {
                                if ("Server thread".equals(t.getName())) {
                                    server = t;
                                    break;
                                }
                            }
                        }
                        if (server != null && server.isAlive()) {
                            long a = tmxa.getThreadAllocatedBytes(
                                    server.getId());
                            if (prevValid && a > prev) {
                                long d = a - prev;
                                StackTraceElement[] st = server.getStackTrace();
                                String top = st.length > 0
                                        ? st[0].toString() : "?";
                                // deepest rustcraft frame for context
                                String rust = "-";
                                for (StackTraceElement f : st) {
                                    if (f.getClassName()
                                            .startsWith("com.rustcraft")) {
                                        rust = f.toString();
                                        break;
                                    }
                                }
                                String key = top + " || " + rust;
                                // [bytes, count, firstMs, lastMs] — the
                                // analyzer phase-joins by wall clock
                                long[] agg = allocSites.computeIfAbsent(
                                        key, k -> new long[4]);
                                long now = System.currentTimeMillis();
                                synchronized (agg) {
                                    agg[0] += d;
                                    agg[1]++;
                                    if (agg[2] == 0) agg[2] = now;
                                    agg[3] = now;
                                }
                            }
                            prev = a;
                            prevValid = true;
                        }
                        Thread.sleep(1);
                        if ((++beat & 8191) == 0) {
                            dumpSites(gameDir, allocSites, false);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        try { Thread.sleep(50); } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "rustcraft-observer-allocdiag");
            allocSamplerThread.setDaemon(true);
            allocSamplerThread.start();
            System.out.println("[observer] alloc sampler ACTIVE"
                    + " (1ms delta sampling; diagnostic run)");
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    dumpSites(gameDir, allocSites, true),
                    "rustcraft-observer-allocdiag-dump"));
        }

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

    /** dump the allocation-site aggregation; called periodically from the
     * sampler loop (overwrite) and once at shutdown — the shutdown-hook
     * path proved unreliable (an exception in the Server Shutdown Thread
     * silently killed the dump in fs-attr3/fs-attr4), so the periodic
     * dump is the primary and the hook the fallback. Failures print to
     * stderr, never swallowed. */
    static void dumpSites(File gameDir,
            java.util.concurrent.ConcurrentHashMap<String, long[]> sites,
            boolean finalDump) {
        try {
            java.util.List<long[]> vals = new java.util.ArrayList<>();
            java.util.List<String> keys = new java.util.ArrayList<>();
            long tot = 0;
            for (java.util.Map.Entry<String, long[]> e : sites.entrySet()) {
                keys.add(e.getKey());
                vals.add(e.getValue());
                tot += e.getValue()[0];
            }
            Integer[] order = new Integer[keys.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final java.util.List<long[]> v = vals;
            java.util.Arrays.sort(order, (x, y) ->
                    Long.compare(v.get(y)[0], v.get(x)[0]));
            java.io.File f = new java.io.File(gameDir,
                    "observer-alloc-sites.txt");
            java.io.File tmp = new java.io.File(gameDir,
                    "observer-alloc-sites.tmp");
            try (PrintWriter pw = new PrintWriter(tmp, "UTF-8")) {
                pw.println("# total attributed bytes: " + tot
                        + " (1ms delta sampling; attribution is statistical"
                        + (finalDump ? "; final" : "; periodic") + ")");
                for (int idx : order) {
                    long[] a = vals.get(idx);
                    pw.println(a[0] + "\t" + a[1] + "\t" + a[2] + "\t"
                            + a[3] + "\t" + keys.get(idx));
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) {
                    System.err.println("[observer] alloc-sites rename failed");
                    tmp.delete(); // retro: never leave the .tmp behind
                }
            }
        } catch (Throwable t) {
            System.err.println("[observer] alloc-sites dump failed: " + t);
        }
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
