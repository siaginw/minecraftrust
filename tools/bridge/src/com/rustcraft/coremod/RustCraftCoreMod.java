package com.rustcraft.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.rustcraft."})
@IFMLLoadingPlugin.SortingIndex(1005) // Runs after standard AccessTransformers and early coremods
public class RustCraftCoreMod implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{
                "com.rustcraft.coremod.SPacketChunkDataTransformer",
                "com.rustcraft.coremod.NetworkManagerCompressionTransformer",
                "com.rustcraft.coremod.FrameShadowHookTransformer",
                "com.rustcraft.coremod.WorldCollisionProbeTransformer",
                "com.rustcraft.coremod.WorldgenShadowTransformer",
                "com.rustcraft.coremod.ChunkMutationTransformer"
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Target A observability: dump M1 metrics + transformer status at JVM exit.
        // Force early class-init so §11/§12 status is observable in the log:
        // native DLL load result, runtime mode, transformer status.
        // DEFERRED (M1 final): do NOT touch com.rustcraft.bridge.NativeChunkPacket
        // here. Its <clinit> loads net.minecraft.world.chunk.Chunk, which breaks
        // Phosphor-style Mixins that must transform Chunk before first load
        // (FTB Revelation: MixinTargetAlreadyLoadedException). Bridge initializes
        // lazily on first SPacketChunkData population, after all mixins apply.
        System.out.println("[RustCraft] coremod injectData: bridge init deferred (mixin-safe)");
        // Periodic metrics dump (10s) so §14 evidence survives hard kills.
        // M1I whole-server perf closure: the same thread also samples the
        // vanilla server tick-time array (last 100 ticks, nanoseconds) every
        // 5s into a cumulative 0.5ms-bucket histogram (compute MSPT on the
        // ServerThread; worker/IO threads excluded by construction). The full
        // cumulative bucket vector is dumped each cycle so offline analysis
        // can window phases by subtraction. Measurement instrumentation only:
        // it never touches packet processing.
        final long[] msptBuckets = new long[201]; // 0.5ms buckets: [0]=<0.25ms ... [200]>=100ms
        final long[] msptMeta = new long[3];      // count, max ns, sum ns
        Thread dumper = new Thread(() -> {
            // Do not touch the bridge (and thus SPacketChunkData/Chunk) until
            // our transformer has actually seen SPacketChunkData load through
            // the full LaunchClassLoader pipeline (incl. Phosphor mixins).
            while (com.rustcraft.coremod.SPacketChunkDataTransformer.transformCount < 1) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
            System.out.println("[RustCraft] MSPT dumper active (transform gate passed)");
            java.lang.reflect.Method getServer = null;
            java.util.List<java.lang.reflect.Field> tickCandidates = null;
            java.lang.reflect.Field tickTimes = null;
            Object server = null;
            Object probeHandler = null;
            boolean diagPrinted = false;
            while (true) {
                // --- MSPT sampling (every 5s; array holds 100 ticks = 5s window) ---
                try {
                    if (getServer == null) {
                        // Stable path: FML is never obfuscated.
                        Class<?> fml = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler");
                        Object handler = fml.getMethod("instance").invoke(null);
                        getServer = fml.getMethod("getMinecraftServerInstance");
                        probeHandler = handler; // store BEFORE any probe; early calls may throw
                        Class<?> mcs = Class.forName("net.minecraft.server.MinecraftServer");
                        System.out.println("[RustCraft] MSPT: MinecraftServer loaded=" + mcs.getName());
                        tickCandidates = new java.util.ArrayList<>();
                        for (java.lang.reflect.Field f : mcs.getDeclaredFields()) {
                            if (f.getType() == long[].class) {
                                f.setAccessible(true);
                                tickCandidates.add(f);
                            }
                        }
                    }
                    if (getServer != null && tickCandidates != null && tickTimes == null) {
                        Object s = getServer.invoke(probeHandler);
                        if (s != null) {
                            for (java.lang.reflect.Field f : tickCandidates) {
                                long[] arr = (long[]) f.get(s);
                                if (arr != null && arr.length == 100) {
                                    tickTimes = f;
                                    server = s;
                                    System.out.println("[RustCraft] MSPT sampler attached (tick array field "
                                            + f.getName() + ")");
                                    break;
                                }
                            }
                        }
                    }
                    if (tickTimes != null) {
                        long[] arr = (long[]) tickTimes.get(server);
                        if (arr != null) {
                            synchronized (msptBuckets) {
                                for (long ns : arr) {
                                    if (ns <= 0) continue;
                                    double ms = ns / 1_000_000.0;
                                    int b = (int) Math.round(ms / 0.5);
                                    if (b > 200) b = 200;
                                    msptBuckets[b]++;
                                    msptMeta[0]++;
                                    msptMeta[1] = Math.max(msptMeta[1], ns);
                                    msptMeta[2] += ns;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // measurement must never break the server
                    if (!diagPrinted) {
                        diagPrinted = true;
                        System.out.println("[RustCraft] MSPT sampler first exception: " + t);
                    }
                }
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                if (server == null) continue;
                try {
                    java.io.File f = new java.io.File("m1-metrics.txt");
                    try (java.io.PrintWriter pw = new java.io.PrintWriter(f, "UTF-8")) {
                        pw.println("# periodic dump t=" + System.currentTimeMillis());
                        pw.println(com.rustcraft.bridge.NativeChunkPacket.dumpMetrics());
                        pw.println(com.rustcraft.bridge.NativeCompressionEncoder.dumpMetrics());
                        pw.println(com.rustcraft.bridge.FrameShadowLiveHook.dumpMetrics()); // M-CK5 live observer
                        pw.println(com.rustcraft.bridge.FrameAuthorityHandler.dumpMetrics()); // M-CK6 authority
                        pw.println("frameShadowHook.transformCount=" + com.rustcraft.coremod.FrameShadowHookTransformer.transformCount
                                + " status=" + com.rustcraft.coremod.FrameShadowHookTransformer.lastStatus);
                        if (com.rustcraft.coremod.WorldCollisionProbeTransformer.transformCount > 0) {
                            pw.println(com.rustcraft.bridge.CollisionProbe.dump());
                        }
                        if (com.rustcraft.bridge.WorldgenShadow.enabled() || com.rustcraft.coremod.WorldgenShadowTransformer.transformCount > 0) {
                            pw.println(com.rustcraft.bridge.WorldgenShadow.dumpMetrics());
                        }
                        pw.println("worldgen.transformCount=" + com.rustcraft.coremod.WorldgenShadowTransformer.transformCount
                                + " status=" + com.rustcraft.coremod.WorldgenShadowTransformer.lastStatus);
                        pw.println(nettyWorkerCpu());
                        pw.println("nmCompression.transformCount=" + com.rustcraft.coremod.NetworkManagerCompressionTransformer.transformCount
                                + " status=" + com.rustcraft.coremod.NetworkManagerCompressionTransformer.lastStatus);
                        pw.println("transformer.transformCount=" + com.rustcraft.coremod.SPacketChunkDataTransformer.transformCount
                                + " status=" + com.rustcraft.coremod.SPacketChunkDataTransformer.lastTransformStatus);
                        synchronized (msptBuckets) {
                            StringBuilder hb = new StringBuilder("mspt_hist=");
                            for (int i = 0; i < msptBuckets.length; i++) {
                                if (i > 0) hb.append(',');
                                hb.append(msptBuckets[i]);
                            }
                            pw.println(hb);
                            pw.println("mspt_n=" + msptMeta[0] + " mspt_max_ms=" + (msptMeta[1] / 1_000_000.0)
                                    + " mspt_mean_ms=" + (msptMeta[0] == 0 ? 0 : msptMeta[2] / 1_000_000.0 / msptMeta[0])
                                    + " mspt_deadline_over_50ms="
                                    + sumRange(msptBuckets, 100, 200));
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[RustCraft] periodic dump failed: " + t);
                }
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        });
        dumper.setDaemon(true);
        dumper.setName("rustcraft-m1-metrics");
        dumper.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("[RustCraft] === M1 metrics at shutdown ===");
                System.out.println(com.rustcraft.bridge.NativeChunkPacket.dumpMetrics());
                System.out.println(com.rustcraft.bridge.NativeCompressionEncoder.dumpMetrics());
                System.out.println(com.rustcraft.bridge.FrameShadowLiveHook.dumpMetrics()); // M-CK5 live observer
                System.out.println(com.rustcraft.bridge.FrameAuthorityHandler.dumpMetrics()); // M-CK6 authority
                if (com.rustcraft.coremod.WorldCollisionProbeTransformer.transformCount > 0) {
                    System.out.println(com.rustcraft.bridge.CollisionProbe.dump());
                }
                if (com.rustcraft.bridge.WorldgenShadow.enabled() || com.rustcraft.coremod.WorldgenShadowTransformer.transformCount > 0) {
                    com.rustcraft.bridge.WorldgenShadow.closeAll();
                    System.out.println(com.rustcraft.bridge.WorldgenShadow.dumpMetrics());
                    com.rustcraft.bridge.WorldgenShadow.writePrePopFile("m3wg-prepop-" + com.rustcraft.bridge.WorldgenShadow.RUNTIME_MODE + ".txt");
                }
                    try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File("m1-metrics.txt"), "UTF-8")) {
                        pw.println("# final shutdown dump t=" + System.currentTimeMillis());
                        pw.println(com.rustcraft.bridge.NativeChunkPacket.dumpMetrics());
                        pw.println(com.rustcraft.bridge.NativeCompressionEncoder.dumpMetrics());
                        pw.println(com.rustcraft.bridge.FrameShadowLiveHook.dumpMetrics()); // M-CK5 live observer
                        pw.println(com.rustcraft.bridge.FrameAuthorityHandler.dumpMetrics()); // M-CK6 authority
                        pw.println("frameShadowHook.transformCount=" + com.rustcraft.coremod.FrameShadowHookTransformer.transformCount
                                + " status=" + com.rustcraft.coremod.FrameShadowHookTransformer.lastStatus);
                    if (com.rustcraft.bridge.WorldgenShadow.enabled() || com.rustcraft.coremod.WorldgenShadowTransformer.transformCount > 0) {
                        pw.println(com.rustcraft.bridge.WorldgenShadow.dumpMetrics());
                    }
                    StringBuilder m4sb = new StringBuilder();
                    com.rustcraft.bridge.NativeChunkBridge.dumpMetrics(m4sb);
                    pw.print(m4sb.toString());
                    pw.println("worldgen.transformCount=" + com.rustcraft.coremod.WorldgenShadowTransformer.transformCount
                            + " status=" + com.rustcraft.coremod.WorldgenShadowTransformer.lastStatus);
                } catch (Throwable ignore) { }
                // M4.3 12: explicit IN-JVM post-cleanup lifecycle measurement —
                // clear the native registry while this JVM is still alive and dump
                // the post-cleanup stats (not inferred from process teardown).
                try {
                    int removed = com.rustcraft.bridge.M4Coherency.shutdownCleanup();
                    java.nio.ByteBuffer pb = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder());
                    java.lang.reflect.Field af = java.nio.Buffer.class.getDeclaredField("address");
                    af.setAccessible(true);
                    long paddr = af.getLong(pb);
                    com.rustcraft.bridge.NativeChunkBridge.getRegistryStats(paddr);
                    try (java.io.PrintWriter pw2 = new java.io.PrintWriter(new java.io.File("m1-metrics-postcleanup.txt"), "UTF-8")) {
                        pw2.println("# post-cleanup dump t=" + System.currentTimeMillis());
                        pw2.println("postcleanup_chunks_removed=" + removed);
                        pw2.println("postcleanup_chunks_current=" + pb.getLong(0));
                        pw2.println("postcleanup_sections_current=" + pb.getLong(8));
                        pw2.println("postcleanup_bytes_retained=" + pb.getLong(16));
                        pw2.println("postcleanup_sections_allocated_total=" + pb.getLong(24));
                        pw2.println("postcleanup_sections_released_total=" + pb.getLong(32));
                        pw2.println("postcleanup_chunks_evicted_total=" + pb.getLong(40));
                        pw2.println(com.rustcraft.bridge.M4NativeStatePayload.statsLine());
                        pw2.println(com.rustcraft.bridge.M4PacketCompare.statsLine());
                    }
                } catch (Throwable ignore2) { }
                System.out.println("[RustCraft] worldgen.transformCount="
                        + com.rustcraft.coremod.WorldgenShadowTransformer.transformCount
                        + " status=" + com.rustcraft.coremod.WorldgenShadowTransformer.lastStatus);
                System.out.println("[RustCraft] " + nettyWorkerCpu());
                System.out.println("[RustCraft] nmCompression.transformCount=" + com.rustcraft.coremod.NetworkManagerCompressionTransformer.transformCount
                        + " status=" + com.rustcraft.coremod.NetworkManagerCompressionTransformer.lastStatus);
                System.out.println("[RustCraft] transformer.transformCount="
                        + com.rustcraft.coremod.SPacketChunkDataTransformer.transformCount
                        + " status=" + com.rustcraft.coremod.SPacketChunkDataTransformer.lastTransformStatus);
            } catch (Throwable t) {
                System.err.println("[RustCraft] metrics dump failed: " + t);
            }
        }));
    }

    /** M2CP: total CPU of the Netty Server IO event-loop threads (the
     *  network-worker surface the compression runs on). One-shot dump,
     *  no per-packet cost. */
    static String nettyWorkerCpu() {
        try {
            java.lang.management.ThreadMXBean tm =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            if (!tm.isThreadCpuTimeSupported() || !tm.isThreadCpuTimeEnabled()) {
                return "netty_worker_cpu=UNSUPPORTED";
            }
            long totalMs = 0; int n = 0;
            for (java.lang.management.ThreadInfo ti : tm.dumpAllThreads(false, false)) {
                String nm = ti.getThreadName();
                if (nm != null && nm.startsWith("Netty Server IO")) {
                    long ns = tm.getThreadCpuTime(ti.getThreadId());
                    if (ns >= 0) { totalMs += ns / 1_000_000L; n++; }
                }
            }
            return "netty_worker_threads=" + n + " netty_worker_cpu_ms=" + totalMs;
        } catch (Throwable t) {
            return "netty_worker_cpu=ERROR";
        }
    }

    private static long sumRange(long[] buckets, int from, int to) {
        long s = 0;
        for (int i = from; i <= to && i < buckets.length; i++) s += buckets[i];
        return s;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
