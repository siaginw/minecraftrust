package com.rustcraft.bench;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class BenchAgent {
    public static String workload = System.getProperty("bench.workload", "unspecified");
    public static String benchType = System.getProperty("bench.type", "AUTHORITATIVE BASELINE");
    public static int warmupSeconds = Integer.getInteger("bench.warmup", 60);
    public static int durationSeconds = Integer.getInteger("bench.duration", 60);
    public static String outputDir = System.getProperty("bench.output", "benchmarks/baseline/p0-2-v2");

    private static volatile boolean initialized = false;
    private static volatile boolean warmupDone = false;
    private static volatile boolean measurementDone = false;

    private static long startTimeNs = 0;
    private static long warmupEndNs = 0;
    private static long measurementEndNs = 0;

    private static long lastTickStartNs = 0;
    private static int tickCounter = 0;
    private static int measuredTickCounter = 0;

    private static final int MAX_TICKS = 200000;
    private static final long[] tickComputeNs = new long[MAX_TICKS];
    private static final long[] tickWallNs = new long[MAX_TICKS];
    private static final long[] tickStartNsArr = new long[MAX_TICKS];
    private static final long[] tickEndNsArr = new long[MAX_TICKS];

    private static long currentTickStart = 0;

    // Boot GC state (from JVM start until end of warmup)
    private static long bootYoungGcCount = 0;
    private static long bootYoungGcTime = 0;
    private static long bootFullGcCount = 0;
    private static long bootFullGcTime = 0;

    // Measurement GC & Memory state
    private static long heapBeforeBytes = 0;
    private static long heapAfterBytes = 0;
    private static long youngGcCountStart = 0;
    private static long youngGcTimeStart = 0;
    private static long fullGcCountStart = 0;
    private static long fullGcTimeStart = 0;

    private static long youngGcCountEnd = 0;
    private static long youngGcTimeEnd = 0;
    private static long fullGcCountEnd = 0;
    private static long fullGcTimeEnd = 0;

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[BENCH-AGENT] Initializing BenchAgent probe...");
        System.out.println("[BENCH-AGENT] Workload: " + workload + " (" + benchType + ")");
        System.out.println("[BENCH-AGENT] Warmup: " + warmupSeconds + "s | Duration: " + durationSeconds + "s");
        System.out.println("[BENCH-AGENT] Output dir: " + outputDir);

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if ("net/minecraft/server/MinecraftServer".equals(className)) {
                    try {
                        ClassReader cr = new ClassReader(classfileBuffer);
                        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                        ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (("C".equals(name) || "func_71217_p".equals(name) || "tick".equals(name)) && "()V".equals(desc)) {
                                    System.out.println("[BENCH-AGENT] Hooking MinecraftServer." + name + desc);
                                    return new MethodVisitor(Opcodes.ASM5, mv) {
                                        @Override
                                        public void visitCode() {
                                            super.visitCode();
                                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/rustcraft/bench/BenchAgent", "onTickStart", "()V", false);
                                        }

                                        @Override
                                        public void visitInsn(int opcode) {
                                            if (opcode == Opcodes.RETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/rustcraft/bench/BenchAgent", "onTickEnd", "()V", false);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                return mv;
                            }
                        };
                        cr.accept(cv, ClassReader.EXPAND_FRAMES);
                        return cw.toByteArray();
                    } catch (Throwable t) {
                        System.err.println("[BENCH-AGENT] Transformation failed: " + t);
                        t.printStackTrace();
                    }
                }
                return classfileBuffer;
            }
        }, true);
    }

    public static void onTickStart() {
        long now = System.nanoTime();
        if (!initialized) {
            initialized = true;
            startTimeNs = now;
            System.out.println("[BENCH-AGENT] First tick detected. Warmup started (" + warmupSeconds + "s)...");
        }

        tickCounter++;
        currentTickStart = now;

        if (!warmupDone) {
            long elapsedWarmupSec = (now - startTimeNs) / 1_000_000_000L;
            if (elapsedWarmupSec >= warmupSeconds) {
                warmupDone = true;
                warmupEndNs = now;
                snapshotGcAndMemoryStart();
                System.out.println("[BENCH-AGENT] >>> WARMUP COMPLETE (" + elapsedWarmupSec + "s). Starting MEASUREMENT phase (" + durationSeconds + "s)...");
            }
        }

        if (warmupDone && !measurementDone) {
            long wallNs = (lastTickStartNs > 0) ? (now - lastTickStartNs) : 50_000_000L;
            if (measuredTickCounter < MAX_TICKS) {
                tickStartNsArr[measuredTickCounter] = now;
                tickWallNs[measuredTickCounter] = wallNs;
            }
        }
        lastTickStartNs = now;
    }

    public static void onTickEnd() {
        long now = System.nanoTime();
        if (warmupDone && !measurementDone) {
            long computeNs = now - currentTickStart;
            if (measuredTickCounter < MAX_TICKS) {
                tickEndNsArr[measuredTickCounter] = now;
                tickComputeNs[measuredTickCounter] = computeNs;
                measuredTickCounter++;
            }

            long elapsedMeasureSec = (now - warmupEndNs) / 1_000_000_000L;
            if (elapsedMeasureSec >= durationSeconds) {
                measurementDone = true;
                measurementEndNs = now;
                snapshotGcAndMemoryEnd();
                System.out.println("[BENCH-AGENT] >>> MEASUREMENT PHASE COMPLETE (" + elapsedMeasureSec + "s, " + measuredTickCounter + " ticks). Generating reports...");
                generateReports();
            }
        }
    }

    private static void snapshotGcAndMemoryStart() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        heapBeforeBytes = mem.getHeapMemoryUsage().getUsed();

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            String name = gc.getName();
            if (isYoungGc(name)) {
                bootYoungGcCount += gc.getCollectionCount();
                bootYoungGcTime += gc.getCollectionTime();
                youngGcCountStart += gc.getCollectionCount();
                youngGcTimeStart += gc.getCollectionTime();
            } else {
                bootFullGcCount += gc.getCollectionCount();
                bootFullGcTime += gc.getCollectionTime();
                fullGcCountStart += gc.getCollectionCount();
                fullGcTimeStart += gc.getCollectionTime();
            }
        }
    }

    private static void snapshotGcAndMemoryEnd() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        heapAfterBytes = mem.getHeapMemoryUsage().getUsed();

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            String name = gc.getName();
            if (isYoungGc(name)) {
                youngGcCountEnd += gc.getCollectionCount();
                youngGcTimeEnd += gc.getCollectionTime();
            } else {
                fullGcCountEnd += gc.getCollectionCount();
                fullGcTimeEnd += gc.getCollectionTime();
            }
        }
    }

    private static boolean isYoungGc(String name) {
        return name.contains("Scavenge") || name.contains("ParNew") || name.contains("Young") || name.contains("Copy");
    }

    private static void generateReports() {
        try {
            File outDir = new File(outputDir);
            outDir.mkdirs();

            int n = measuredTickCounter;
            if (n == 0) {
                System.err.println("[BENCH-AGENT] No measurement ticks captured!");
                return;
            }

            double[] computeMs = new double[n];
            double[] wallMs = new double[n];
            double sumCompute = 0.0;
            double sumWall = 0.0;

            int over25 = 0;
            int over40 = 0;
            int over50 = 0;
            int over100 = 0;
            int over250 = 0;

            for (int i = 0; i < n; i++) {
                double c = tickComputeNs[i] / 1_000_000.0;
                double w = tickWallNs[i] / 1_000_000.0;
                computeMs[i] = c;
                wallMs[i] = w;
                sumCompute += c;
                sumWall += w;

                if (c > 25.0) over25++;
                if (c > 40.0) over40++;
                if (c > 50.0) over50++;
                if (c > 100.0) over100++;
                if (c > 250.0) over250++;
            }

            double meanCompute = sumCompute / n;
            double meanWall = sumWall / n;

            double sumSqDiff = 0.0;
            for (int i = 0; i < n; i++) {
                double diff = computeMs[i] - meanCompute;
                sumSqDiff += diff * diff;
            }
            double stddevCompute = Math.sqrt(sumSqDiff / n);

            double[] sortedCompute = Arrays.copyOf(computeMs, n);
            Arrays.sort(sortedCompute);

            double p50 = percentile(sortedCompute, 50.0);
            double p90 = percentile(sortedCompute, 90.0);
            double p95 = percentile(sortedCompute, 95.0);
            double p99 = percentile(sortedCompute, 99.0);
            double p99_9 = percentile(sortedCompute, 99.9);
            double minCompute = sortedCompute[0];
            double maxCompute = sortedCompute[n - 1];

            double[] sortedWall = Arrays.copyOf(wallMs, n);
            Arrays.sort(sortedWall);
            double p50Wall = percentile(sortedWall, 50.0);
            double p95Wall = percentile(sortedWall, 95.0);
            double p99Wall = percentile(sortedWall, 99.0);

            long totalMeasurementTimeNs = measurementEndNs - warmupEndNs;
            double totalMeasurementSec = totalMeasurementTimeNs / 1_000_000_000.0;
            double tps = n / totalMeasurementSec;

            long youngGcCount = youngGcCountEnd - youngGcCountStart;
            long youngGcTimeMs = youngGcTimeEnd - youngGcTimeStart;
            long fullGcCount = fullGcCountEnd - fullGcCountStart;
            long fullGcTimeMs = fullGcTimeEnd - fullGcTimeStart;

            RuntimeMXBean rmb = ManagementFactory.getRuntimeMXBean();
            OperatingSystemMXBean osb = ManagementFactory.getOperatingSystemMXBean();
            long jvmUptimeSec = rmb.getUptime() / 1000L;

            // Write CSV
            File csvFile = new File(outDir, workload + "_ticks.csv");
            try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
                pw.println("tick_index,tick_start_ns,tick_end_ns,compute_ns,compute_ms,wall_cadence_ns,wall_cadence_ms");
                for (int i = 0; i < n; i++) {
                    pw.printf("%d,%d,%d,%d,%.4f,%d,%.4f%n",
                            i, tickStartNsArr[i], tickEndNsArr[i], tickComputeNs[i], computeMs[i], tickWallNs[i], wallMs[i]);
                }
            }

            // Write YAML summary
            File yamlFile = new File(outDir, workload + ".yaml");
            try (PrintWriter pw = new PrintWriter(new FileWriter(yamlFile))) {
                pw.println("workload: \"" + workload + "\"");
                pw.println("benchmark_type: \"" + benchType + "\"");
                pw.println("status: \"COMPLETED\"");
                pw.println("environment:");
                pw.println("  os: \"" + osb.getName() + " " + osb.getVersion() + " (" + osb.getArch() + ")\"");
                pw.println("  available_processors: " + osb.getAvailableProcessors());
                pw.println("  java_version: \"" + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\"");
                pw.println("  jvm_name: \"" + rmb.getVmName() + " (" + rmb.getVmVersion() + ")\"");
                pw.println("  jvm_input_arguments: " + rmb.getInputArguments().toString());
                pw.println("methodology:");
                pw.println("  probe: \"JavaAgent Bytecode Instrumentation on MinecraftServer.tick()\"");
                pw.println("  clock: \"System.nanoTime()\"");
                pw.println("  warmup_seconds: " + warmupSeconds);
                pw.println("  measurement_seconds: " + durationSeconds);
                pw.println("  actual_measurement_seconds: " + String.format("%.2f", totalMeasurementSec));
                pw.println("  total_measured_ticks: " + n);
                pw.println("  jvm_uptime_at_measurement_start_seconds: " + (warmupEndNs - startTimeNs) / 1_000_000_000L);
                pw.println("  jvm_uptime_at_measurement_end_seconds: " + jvmUptimeSec);
                pw.println("cadence:");
                pw.println("  tps: " + String.format("%.2f", tps));
                pw.println("  wall_cadence_mean_ms: " + String.format("%.2f", meanWall));
                pw.println("  wall_cadence_p50_ms: " + String.format("%.2f", p50Wall));
                pw.println("  wall_cadence_p95_ms: " + String.format("%.2f", p95Wall));
                pw.println("  wall_cadence_p99_ms: " + String.format("%.2f", p99Wall));
                pw.println("compute_mspt:");
                pw.println("  mean: " + String.format("%.3f", meanCompute));
                pw.println("  median_p50: " + String.format("%.3f", p50));
                pw.println("  p90: " + String.format("%.3f", p90));
                pw.println("  p95: " + String.format("%.3f", p95));
                pw.println("  p99: " + String.format("%.3f", p99));
                pw.println("  p99_9: " + String.format("%.3f", p99_9));
                pw.println("  min: " + String.format("%.3f", minCompute));
                pw.println("  max: " + String.format("%.3f", maxCompute));
                pw.println("  stddev: " + String.format("%.3f", stddevCompute));
                pw.println("deadline_analysis:");
                pw.println("  missed_50ms_deadlines: " + over50);
                pw.println("  ticks_over_25ms: " + over25);
                pw.println("  ticks_over_40ms: " + over40);
                pw.println("  ticks_over_50ms: " + over50);
                pw.println("  ticks_over_100ms: " + over100);
                pw.println("  ticks_over_250ms: " + over250);
                pw.println("gc_and_memory:");
                pw.println("  boot_phase:");
                pw.println("    young_gc_count: " + bootYoungGcCount);
                pw.println("    young_gc_pause_ms: " + bootYoungGcTime);
                pw.println("    full_gc_count: " + bootFullGcCount);
                pw.println("    full_gc_pause_ms: " + bootFullGcTime);
                pw.println("  measurement_phase:");
                pw.println("    young_gc_count: " + youngGcCount);
                pw.println("    young_gc_pause_ms: " + youngGcTimeMs);
                pw.println("    full_gc_count: " + fullGcCount);
                pw.println("    full_gc_pause_ms: " + fullGcTimeMs);
                pw.println("    heap_before_bytes: " + heapBeforeBytes);
                pw.println("    heap_after_bytes: " + heapAfterBytes);
                pw.println("    heap_before_mb: " + String.format("%.2f", heapBeforeBytes / (1024.0 * 1024.0)));
                pw.println("    heap_after_mb: " + String.format("%.2f", heapAfterBytes / (1024.0 * 1024.0)));
            }

            System.out.println("[BENCH-AGENT] ========================================================");
            System.out.println("[BENCH-AGENT] BENCHMARK COMPLETE: " + workload + " (" + benchType + ")");
            System.out.println("[BENCH-AGENT] Ticks: " + n + " in " + String.format("%.2f", totalMeasurementSec) + "s | TPS: " + String.format("%.2f", tps));
            System.out.println("[BENCH-AGENT] COMPUTE MSPT: mean=" + String.format("%.2f", meanCompute) + "ms | p50=" + String.format("%.2f", p50) + "ms | p95=" + String.format("%.2f", p95) + "ms | p99=" + String.format("%.2f", p99) + "ms | max=" + String.format("%.2f", maxCompute) + "ms");
            System.out.println("[BENCH-AGENT] WALL CADENCE: mean=" + String.format("%.2f", meanWall) + "ms");
            System.out.println("[BENCH-AGENT] DEADLINE MISSES (>50ms): " + over50);
            System.out.println("[BENCH-AGENT] BOOT GC: young=" + bootYoungGcCount + " (" + bootYoungGcTime + "ms) | full=" + bootFullGcCount + " (" + bootFullGcTime + "ms)");
            System.out.println("[BENCH-AGENT] STEADY-STATE GC: young=" + youngGcCount + " (" + youngGcTimeMs + "ms) | full=" + fullGcCount + " (" + fullGcTimeMs + "ms)");
            System.out.println("[BENCH-AGENT] Written: " + yamlFile.getAbsolutePath());
            System.out.println("[BENCH-AGENT] Written: " + csvFile.getAbsolutePath());
            System.out.println("[BENCH-AGENT] ========================================================");

        } catch (Throwable t) {
            System.err.println("[BENCH-AGENT] Error generating reports: " + t);
            t.printStackTrace();
        }
    }

    private static double percentile(double[] sorted, double pct) {
        if (sorted.length == 0) return 0.0;
        if (sorted.length == 1) return sorted[0];
        double rank = (pct / 100.0) * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sorted[low];
        double weight = rank - low;
        return sorted[low] * (1.0 - weight) + sorted[high] * weight;
    }
}
