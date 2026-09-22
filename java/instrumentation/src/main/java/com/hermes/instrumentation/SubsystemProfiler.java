package com.hermes.instrumentation;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class SubsystemProfiler {
    public static final SubsystemProfiler INSTANCE = new SubsystemProfiler();

    public final AtomicLong chunkLoadTimeNanos = new AtomicLong(0);
    public final AtomicLong chunkSaveTimeNanos = new AtomicLong(0);
    public final AtomicLong nbtCodecTimeNanos = new AtomicLong(0);
    public final AtomicLong compressionTimeNanos = new AtomicLong(0);
    public final AtomicLong networkPacketBytes = new AtomicLong(0);

    private SubsystemProfiler() {}

    public static long getTotalGcTimeMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) total += time;
        }
        return total;
    }

    public static long getUsedMemoryBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
