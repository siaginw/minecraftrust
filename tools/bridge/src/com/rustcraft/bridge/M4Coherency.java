package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4.2A C2/E — mutation-coherency flush engine.
 *
 * Drains ChunkMutationTracker state across JNI in BATCH (never per block
 * write): unloads -> native unloadChunk; full flags -> invalidate; section
 * dirty bits -> extract Java states/light -> refreshSection -> validate the
 * refreshed native state by decoding encodePacket output with the independent
 * decoder and comparing every cell back against Java.
 *
 * Honesty rules:
 *  - a chunk whose native state is absent (never registered / replaced) is
 *    skipped, never silently treated as covered;
 *  - the FIRST unexplained validation mismatch DISABLES the refresh path
 *    (M42_REFRESH_DISABLED=true) and is dumped;
 *  - snapshot recency: refresh+encode run under the native write lock with the
 *    snapshot token check, so output is never published from a snapshot that
 *    changed mid-capture (encode returns an error -> counted, not published).
 *
 * Enabled only when -Dminecraftrust.m4.coherency=true AND worldgen SHADOW
 * retained state exists. Default off everywhere.
 */
public final class M4Coherency {

    public static final AtomicLong FLUSH_CYCLES = new AtomicLong();
    public static final AtomicLong UNLOADS_FLUSHED = new AtomicLong();
    public static final AtomicLong FULL_INVALIDATIONS = new AtomicLong();
    public static final AtomicLong SECTIONS_REFRESHED = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATED = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATED_ALLAIR_ABSENT = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATION_MISMATCH = new AtomicLong();
    public static final AtomicLong STALE_ENCODE_REJECTED = new AtomicLong();
    public static final AtomicLong NOT_REGISTERED_SKIPPED = new AtomicLong();
    public static volatile boolean REFRESH_DISABLED = false;
    public static volatile String FIRST_MISMATCH = "none";

    private static volatile Thread FLUSHER;
    private static volatile boolean started = false;

    private static final int FLUSH_INTERVAL_MS = 2000;

    // cached reflection
    private static Method M_GET_STORAGE, M_CONTAINER, M_GET_STATE, M_REG_GET_ID, M_GET_BL, M_GET_SL, M_NIBBLE_BYTES;
    private static Field F_EMPTY_STORAGE;
    private static Object REG_MAP;
    private static Object AIR_STATE;

    private static final ThreadLocal<ByteBuffer> STATES_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096 * 2).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> LIGHT_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> PKT_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> STATS_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()));

    private M4Coherency() {}

    /** True when the chunk has completed first-touch full synchronization. */
    public static boolean isFullySynced(Object chunk) {
        return FULLY_SYNCED.containsKey(chunk);
    }

    /** Snapshot of synced chunk keys for the verification sweep. */
    public static java.util.List<Object> syncedChunkSnapshot() {
        synchronized (FULLY_SYNCED) {
            return new java.util.ArrayList<>(FULLY_SYNCED.keySet());
        }
    }

    /** Skylight context of a chunk's world (sweep uses same flag as packets). */
    public static boolean chunkSkylight(Object chunk) {
        try {
            Object w = findWorldOf(chunk);
            if (w == null) return true;
            java.lang.reflect.Field pf = findHier(w.getClass(), "field_73011_w");
            pf.setAccessible(true);
            Object prov = pf.get(w);
            Class<?> pc = prov.getClass();
            while (pc != null) {
                try {
                    java.lang.reflect.Method m = pc.getDeclaredMethod("func_191066_m");
                    m.setAccessible(true);
                    return (Boolean) m.invoke(prov);
                } catch (NoSuchMethodException e) { pc = pc.getSuperclass(); }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static java.lang.reflect.Field findHier(Class<?> c, String name) throws Exception {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object findWorldOf(Object chunk) {
        try {
            java.lang.reflect.Field f = chunk.getClass().getDeclaredField("field_76637_e");
            f.setAccessible(true);
            return f.get(chunk);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int lastSyncedVersion(Object chunk) {
        Integer v = FULLY_SYNCED.get(chunk);
        return v == null ? Integer.MIN_VALUE : v;
    }

    


    /** M4.2D lifecycle: stop accepting new flush work, let the flusher exit,
     *  drain the unload queue, and clear the registry in-JVM. Returns chunks
     *  removed. Safe to repeat (second call drains nothing). Stale generation
     *  handles are rejected by the registry afterwards by construction. */
    public static int shutdownCleanup() {
        try {
            started = false;                 // no new flusher
            Thread f = FLUSHER;
            if (f != null) f.interrupt();    // in-flight cycle finishes, thread exits
            int[] u;
            while ((u = ChunkMutationTracker.UNLOAD_QUEUE.poll()) != null) {
                if (u[0] != ChunkMutationTracker.DIM_UNKNOWN) {
                    NativeChunkBridge.unload(u[0], u[1], u[2]);
                    UNLOADS_FLUSHED.incrementAndGet();
                }
            }
            ChunkMutationTracker.stateView().clear();
            return NativeChunkBridge.registryClear();
        } catch (Throwable t) {
            WorldgenShadow.recordCoherencyError("shutdownCleanup: " + t, t);
            return -1;
        }
    }

    public static void startIfNeeded() {
        if (started || !NativeChunkBridge.isAvailable()
                || !Boolean.getBoolean("minecraftrust.m4.coherency")) {
            return;
        }
        started = true;
        FLUSHER = new Thread(M4Coherency::run, "m4-coherency-flush");
        FLUSHER.setDaemon(true);
        FLUSHER.start();
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
                flushOnce();
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // flush errors never crash the server; counted via WorldgenShadow LAST_ERROR
                WorldgenShadow.recordCoherencyError("flush: " + t, t);
            }
        }
    }

    public static void flushOnce() {
        FLUSH_CYCLES.incrementAndGet();

        // M4.3B: first-touch readiness is event-driven on dedicated workers;
        // this periodic flush remains for maintenance (dirty refresh, deferred compares).

        // M4.2E deferred comparisons (after syncs land)
        if (com.rustcraft.bridge.M4PacketCompare.enabled() && !com.rustcraft.bridge.M4PacketCompare.DISABLED) {
            com.rustcraft.bridge.M4PacketCompare.processDeferred(128);
        }


        // M4.3D quiescent verification sweep: for synced chunks whose version
        // is stable across a full Java-reference build, verify native vs Java
        // payloads by independent decode (Gate B). Double version check closes
        // the torn-read window: if the version moved during the build, SKIP.
        // M5.1: retired sweep disabled (thread-confined ctor; historical evidence in M4.3D raw) — quiescentSweep(8);
        // M4.3 sampled verification of authoritative native payloads

        // M5.8-R canary: ONE-SHOT, max three requests, acknowledged — no periodic re-fire
        if (Boolean.getBoolean("minecraftrust.m58.canary") && !M5ResendDriver.CANARY_FIRED) {
            M5ResendDriver.CANARY_FIRED = true;
            com.rustcraft.bridge.M5ResendDriver.requestResends(3);
            System.err.println("[M58-CANARY] one-shot requested=3 accepted="
                    + M5ResendDriver.ACCEPTED.get() + " (ack)");
        }
        // M5.3: dead auth-sample builder DISABLED (0 samples ever; its off-thread
        // ctor NPEs were the last active validator exceptions). Historical
        // evidence in M4.3D/M5.2 raw. Gate-B comparator remains the verifier.
        // com.rustcraft.bridge.M4PacketCompare.processAuthSamples(32);

        // 1. unloads
        int[] u;
        while ((u = ChunkMutationTracker.UNLOAD_QUEUE.poll()) != null) {
            if (u[0] != ChunkMutationTracker.DIM_UNKNOWN) {
                NativeChunkBridge.unload(u[0], u[1], u[2]);
                UNLOADS_FLUSHED.incrementAndGet();
            }
        }

        // 2. per-chunk work
        for (java.util.Map.Entry<Object, int[]> e : ChunkMutationTracker.stateView().entrySet()) {
            Object chunk = e.getKey();
            int[] work = ChunkMutationTracker.takeWork(chunk);
            if (work == null) continue;
            if (REFRESH_DISABLED) break;
            try {
                handleChunk(chunk, work);
            } catch (Throwable t) {
                WorldgenShadow.recordCoherencyError("handle: " + t, t);
            }
        }
    }


    // ==================================================================
    // M4.3B — fast section extraction. Per-cell reflective getState costs
    // ~65k invokes/chunk (~20 ms); this reads the container's BitArray longs
    // and palette ONCE per section (3 reflective calls) and decodes the
    // LSB-first packing with plain int math (~4096 ops/section).
    // ==================================================================

    private static java.lang.reflect.Method M_BITS, M_WORDS, M_PAL_BYID, M_CONTAINER_FIELDS;
    private static java.lang.reflect.Field F_CONTAINER_BITS, F_CONTAINER_PAL, F_CONTAINER_ARR;

    static void initFastExtract() throws Exception {
        Class<?> cont = Class.forName("net.minecraft.world.chunk.BlockStateContainer");
        F_CONTAINER_ARR = cont.getDeclaredField("field_186021_b"); // BitArray
        F_CONTAINER_ARR.setAccessible(true);
        F_CONTAINER_PAL = cont.getDeclaredField("field_186022_c"); // IBlockStatePalette
        F_CONTAINER_PAL.setAccessible(true);
        F_CONTAINER_BITS = cont.getDeclaredField("field_186024_e"); // bitsPerEntry
        F_CONTAINER_BITS.setAccessible(true);
        Class<?> bitArr = Class.forName("net.minecraft.util.BitArray");
        M_WORDS = bitArr.getMethod("func_188143_a"); // long[]
        M_WORDS.setAccessible(true);
        Class<?> palIface = Class.forName("net.minecraft.world.chunk.IBlockStatePalette");
        M_PAL_BYID = palIface.getMethod("func_186039_a", int.class); // byId -> IBlockState
        M_PAL_BYID.setAccessible(true);
    }

    /** Decode one container into global registry ids. Returns null on any
     *  structural surprise (caller falls back to the per-cell path). */
    /** public test accessor (M4.3C parity harness). */
    public static int[] fastExtractPublic(Object container, int[] out) {
        return fastExtractGlobalIds(container, out);
    }

    static String fastDebugInfo(Object container) {
        try {
            if (F_CONTAINER_ARR == null) initFastExtract();
            Object bitArray = F_CONTAINER_ARR.get(container);
            Object palette = F_CONTAINER_PAL.get(container);
            int bits = F_CONTAINER_BITS.getInt(container);
            long[] words = (long[]) M_WORDS.invoke(bitArray);
            return "bits=" + bits + " words=" + (words == null ? -1 : words.length)
                + " palette=" + (palette == null ? "null" : palette.getClass().getName());
        } catch (Throwable t) {
            return "init/extract threw: " + t;
        }
    }

    static int[] fastExtractGlobalIds(Object container, int[] fallbackOut) {
        try {
            if (F_CONTAINER_ARR == null) initFastExtract();
            if (REG_MAP == null || M_REG_GET_ID == null) {
                java.lang.reflect.Field regF = net.minecraft.block.Block.class.getDeclaredField("field_176229_d");
                regF.setAccessible(true);
                REG_MAP = regF.get(null);
                M_REG_GET_ID = REG_MAP.getClass().getMethod("func_148747_b", Object.class);
                M_REG_GET_ID.setAccessible(true);
            }
            Object bitArray = F_CONTAINER_ARR.get(container);
            Object palette = F_CONTAINER_PAL.get(container);
            int bits = F_CONTAINER_BITS.getInt(container);
            long[] words = (long[]) M_WORDS.invoke(bitArray);
            if (words == null || bits < 4 || bits > 16) return null;

            // local -> global id table (<=256 palette entries)
            int palLen = 1 << Math.min(8, bits);
            if (bits > 8) palLen = Integer.MAX_VALUE; // registry palette: entry == global id
            int[] gid = fallbackOut;
            if (bits <= 8) {
                int tableLen = 1 << bits;
                int[] table = new int[tableLen];
                for (int i = 0; i < tableLen; i++) {
                    Object st = M_PAL_BYID.invoke(palette, i);
                    Integer g = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                    table[i] = (g == null) ? 0 : g;
                }
                for (int idx = 0; idx < 4096; idx++) {
                    int bitPos = idx * bits, w0 = bitPos >>> 6, off = bitPos & 63, fit = 64 - off;
                    int entry;
                    if (fit >= bits) entry = (int) ((words[w0] >>> off) & ((1L << bits) - 1));
                    else {
                        int rest = bits - fit;
                        long lo = (words[w0] >>> off) & ((1L << fit) - 1);
                        long hi = words[w0 + 1] & ((1L << rest) - 1);
                        entry = (int) (lo | (hi << fit));
                    }
                    gid[idx] = (entry >= 0 && entry < tableLen) ? table[entry] : 0;
                }
            } else {
                // registry palette: entries are raw global ids
                for (int idx = 0; idx < 4096; idx++) {
                    int bitPos = idx * bits, w0 = bitPos >>> 6, off = bitPos & 63, fit = 64 - off;
                    int entry;
                    if (fit >= bits) entry = (int) ((words[w0] >>> off) & ((1L << bits) - 1));
                    else {
                        int rest = bits - fit;
                        long lo = (words[w0] >>> off) & ((1L << fit) - 1);
                        long hi = words[w0 + 1] & ((1L << rest) - 1);
                        entry = (int) (lo | (hi << fit));
                    }
                    gid[idx] = entry;
                }
            }
            return gid;
        } catch (Throwable t) {
            return null; // structural surprise: caller uses the per-cell path
        }
    }

    // ==================================================================
    // M4.3B — event-driven dedicated sync executor (no periodic-cadence
    // dependency for first readiness). Bounded queue; 2 workers; a sync
    // marks the chunk READY; packet thread never waits.
    // ==================================================================

    static final java.util.concurrent.ArrayBlockingQueue<Object> SYNC_QUEUE =
            new java.util.concurrent.ArrayBlockingQueue<>(2048);
    static volatile boolean syncWorkersRunning = false;
    public static final AtomicLong SYNC_ENQUEUED = new AtomicLong();
    public static final AtomicLong SYNC_DROPPED_FULL = new AtomicLong();
    public static final AtomicLong SYNC_DONE = new AtomicLong();
    public static final AtomicLong SYNC_NS_TOTAL = new AtomicLong();
    public static final AtomicLong SYNC_QUEUE_DELAY_MS_TOTAL = new AtomicLong();
    static final java.util.concurrent.ConcurrentHashMap<Object, Long> SYNC_REQUEST_TS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Earliest-event sync request: called from onLoad (and any lifecycle event). */
    public static void requestSync(Object chunk) {
        if (chunk == null || !NativeChunkBridge.isAvailable()) return;
        if (FULLY_SYNCED.containsKey(chunk)) return;
        if (!SYNC_QUEUE.offer(chunk)) {
            SYNC_DROPPED_FULL.incrementAndGet();
            return;
        }
        SYNC_REQUEST_TS.put(chunk, System.currentTimeMillis());
        SYNC_ENQUEUED.incrementAndGet();
        startSyncWorkers();
    }

    static synchronized void startSyncWorkers() {
        if (syncWorkersRunning) return;
        syncWorkersRunning = true;
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(M4Coherency::syncWorkerLoop, "m43b-sync-worker-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    static void syncWorkerLoop() {
        while (true) {
            Object chunk = null;
            try {
                chunk = SYNC_QUEUE.take();
            } catch (InterruptedException ie) {
                return;
            }
            try {
                long t0 = System.nanoTime();
                if (FULLY_SYNCED.containsKey(chunk)) {
                    // M4.3B: already synced — this is an event-driven DIRTY refresh
                    // (population/light churn after onLoad). Clears pending work so
                    // the next packet finds the snapshot current.
                    refreshChunkNow(chunk);
                    DIRTY_REFRESHES.incrementAndGet();
                } else {
                    fullSync(chunk);
                    SYNC_DONE.incrementAndGet();
                    Long ts = SYNC_REQUEST_TS.remove(chunk);
                    if (ts != null) SYNC_QUEUE_DELAY_MS_TOTAL.addAndGet(System.currentTimeMillis() - ts);
                }
                SYNC_NS_TOTAL.addAndGet(System.nanoTime() - t0);
            } catch (Throwable t) {
                WorldgenShadow.recordCoherencyError("syncWorker: " + t, t);
            } finally {
                QUEUED_FOR_REFRESH.remove(chunk);
            }
        }
    }

    /** Throttled event-driven dirty refresh: mutations on a SYNCED chunk schedule
     *  one refresh (no queue flooding from mutation bursts). */
    public static void requestDirtyRefresh(Object chunk) {
        if (chunk == null || !FULLY_SYNCED.containsKey(chunk)) return;
        if (QUEUED_FOR_REFRESH.putIfAbsent(chunk, Boolean.TRUE) != null) return;
        if (!SYNC_QUEUE.offer(chunk)) {
            QUEUED_FOR_REFRESH.remove(chunk);
            return;
        }
        DIRTY_ENQUEUED.incrementAndGet();
    }

    private static final java.util.concurrent.ConcurrentHashMap<Object, Boolean> QUEUED_FOR_REFRESH =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static final AtomicLong DIRTY_REFRESHES = new AtomicLong();
    public static final AtomicLong GUARD_REARMED = new AtomicLong();
    public static final AtomicLong HIGH_ID_REJECTED = new AtomicLong();
    public static final AtomicLong UNKNOWN_STATE_REJECTED = new AtomicLong();
    public static final AtomicLong DIRTY_ENQUEUED = new AtomicLong();


    // ==================================================================
    // M4.3C — per-chunk refresh serialization (diagnostic): no two concurrent
    // full syncs / section refreshes for the same ChunkKey across workers and
    // the packet thread. Per-key token map, NOT a global lock.
    // ==================================================================
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> REFRESH_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static Object refreshLockFor(int dim, int cx, int cz) {
        return refreshLock(dim, cx, cz);
    }

    static Object refreshLock(int dim, int cx, int cz) {
        String k = dim + ":" + cx + ":" + cz;
        Object l = new Object();
        Object prev = REFRESH_LOCKS.putIfAbsent(k, l);
        return prev == null ? l : prev;
    }

    // ==================================================================
    // M4.3C — dual-path section verify: at a low rate, run the SLOW per-cell
    // extractor alongside the FAST one and compare cell-by-cell. On divergence:
    // full palette/BitArray dump, disable the fast extractor globally.
    // ==================================================================
    public static final java.util.concurrent.atomic.AtomicLong DUAL_VERIFY_RUNS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DUAL_VERIFY_DIVERGE = new java.util.concurrent.atomic.AtomicLong();
    public static volatile String DUAL_FIRST_DIVERGENCE = "none";
    public static final java.util.concurrent.atomic.AtomicLong DUAL_MUTATION_INFLIGHT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long DUAL_VERIFY_EVERY = 8;
    public static volatile boolean FAST_EXTRACTOR_ENABLED = true;

    /** Compare fast vs slow extraction for one section; dump on divergence. */
    static void dualVerify(int dim, int cx, int cz, int y, Object container, Object storage,
                           int[] fastResult) {
        try {
            if (DUAL_VERIFY_RUNS.incrementAndGet() % DUAL_VERIFY_EVERY != 0) return;
            int[] slow = new int[4096];
            for (int i = 0; i < 4096; i++) {
                Object st = M_GET_STATE.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
                Integer gid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                if (gid == null) { UNKNOWN_STATE_REJECTED.incrementAndGet(); slow[i] = -1; }
                else slow[i] = gid;
            }
            for (int i = 0; i < 4096; i++) {
                if (slow[i] != fastResult[i]) {
                    // M4.3C finding: worker extraction runs concurrently with server
                    // population — the JAVA STATE ITSELF may change between the fast
                    // and slow reads (proven live: coal_ore-vs-stone dump). Re-read
                    // for stability: only a STABLE slow!=fast is a real divergence.
                    int[] slow2 = new int[4096];
                    for (int j = 0; j < 4096; j++) {
                        Object st2 = M_GET_STATE.invoke(container, j & 15, j >> 8, (j >> 4) & 15);
                        Integer gid2 = (Integer) M_REG_GET_ID.invoke(REG_MAP, st2);
                        slow2[j] = gid2 == null ? -1 : gid2;
                    }
                    if (!java.util.Arrays.equals(slow, slow2)) {
                        DUAL_MUTATION_INFLIGHT.incrementAndGet();
                        return; // benign: state moved between reads
                    }
                    DUAL_VERIFY_DIVERGE.incrementAndGet();
                    FAST_EXTRACTOR_ENABLED = false;
                    DUAL_FIRST_DIVERGENCE = dumpDivergence(dim, cx, cz, y, i, container, slow, fastResult);
                    System.err.println("[M43C-FAST-DIVERGENCE] " + DUAL_FIRST_DIVERGENCE);
                    return;
                }
            }
        } catch (Throwable t) {
            WorldgenShadow.recordCoherencyError("dualVerify: " + t, t);
        }
    }

    /** Preserve everything the directive demands on extractor divergence. */
    static String dumpDivergence(int dim, int cx, int cz, int y, int cell,
                                 Object container, int[] slow, int[] fast) {
        try {
            StringBuilder sb = new StringBuilder();
            int bits = F_CONTAINER_BITS.getInt(container);
            Object palette = F_CONTAINER_PAL.get(container);
            Object bitArray = F_CONTAINER_ARR.get(container);
            long[] words = (long[]) M_WORDS.invoke(bitArray);
            int bitPos = cell * bits, w0 = bitPos >>> 6, off = bitPos & 63;
            int entry = (int) ((words[w0] >>> off) & ((1L << bits) - 1));
            Object st = M_GET_STATE.invoke(container, cell & 15, cell >> 8, (cell >> 4) & 15);
            Integer jgid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
            String palType = palette == null ? "null" : palette.getClass().getSimpleName();
            sb.append("dim=").append(dim).append(" cx=").append(cx).append(" cz=").append(cz)
               .append(" y=").append(y).append(" cell=").append(cell)
               .append(" xyz=(").append(cell & 15).append(",").append((y << 4) | (cell >> 8)).append(",").append((cell >> 4) & 15).append(")")
               .append(" javaState=").append(st)
               .append(" javaGid=").append(jgid)
               .append(" slowGid=").append(slow[cell])
               .append(" fastGid=").append(fast[cell])
               .append(" palette=").append(palType)
               .append(" bits=").append(bits)
               .append(" bitPos=").append(bitPos).append(" word=").append(w0).append(" off=").append(off)
               .append(" entry=").append(entry);
            if (bits <= 8 && palette != null) {
                sb.append(" paletteMap=");
                for (int i = 0; i < Math.min(16, 1 << bits); i++) {
                    Object ps = M_PAL_BYID.invoke(palette, i);
                    Integer pg = ps == null ? null : (Integer) M_REG_GET_ID.invoke(REG_MAP, ps);
                    sb.append(i).append("->").append(pg == null ? "null" : pg).append(",");
                }
            }
            sb.append(" words0=").append(Long.toUnsignedString(words.length > 0 ? words[0] : 0, 16));
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter("m43c-extractor-divergence.txt", true))) {
                pw.println(sb);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "dump-failed: " + t;
        }
    }

    /** M5.2: extract + push ONE section (states + light) natively. */
    static int refreshOneSection(int dim, int cx, int cz, int y, Object storage) throws Exception {
        try {
            Object[] storages = (Object[]) storage; // unused shim
        } catch (Throwable ignored) { }
        int[] g = new int[4096];
        byte[] bl = new byte[2048], sl = new byte[2048];
        boolean present = storage != null
                && (F_EMPTY_STORAGE == null || storage != F_EMPTY_STORAGE.get(null));
        if (present) {
            Object container = M_CONTAINER.invoke(storage);
            int[] fast = FAST_EXTRACTOR_ENABLED ? fastExtractGlobalIds(container, g) : null;
            if (fast == null) {
                for (int i = 0; i < 4096; i++) {
                    Object st = M_GET_STATE.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
                    Integer gid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                    if (gid == null) { UNKNOWN_STATE_REJECTED.incrementAndGet(); g[i] = -1; }
                    else g[i] = gid;
                }
            }
            Object blN = M_GET_BL.invoke(storage);
            if (blN != null) System.arraycopy(M_NIBBLE_BYTES.invoke(blN), 0, bl, 0, 2048);
            Object slN = M_GET_SL.invoke(storage);
            if (slN != null) System.arraycopy(M_NIBBLE_BYTES.invoke(slN), 0, sl, 0, 2048);
        }
        // M5.8-R2 width guard: the staging buffer is u16 (char) — ANY state id
        // above 0xFFFF MUST be rejected BEFORE narrowing (the -23,-7 event was
        // 76916 -> 11380 == 76916 & 0xFFFF truncated exactly here). Reject the
        // refresh; the chunk stays unsynced/stale and packets fall back to Java.
        for (int i = 0; i < 4096; i++) {
            if ((g[i] & 0xFFFF0000) != 0) {
                HIGH_ID_REJECTED.incrementAndGet();
                return -2; // unsupported width: no truncated write, ever
            }
        }
        java.nio.ByteBuffer sBB = java.nio.ByteBuffer.allocateDirect(4096 * 2).order(java.nio.ByteOrder.nativeOrder());
        java.nio.CharBuffer cb = sBB.asCharBuffer();
        for (int i = 0; i < 4096; i++) cb.put(i, (char) g[i]);
        java.nio.ByteBuffer lBB = java.nio.ByteBuffer.allocateDirect(4096).order(java.nio.ByteOrder.nativeOrder());
        lBB.put(bl); lBB.put(sl); lBB.clear();
        java.lang.reflect.Field af = java.nio.Buffer.class.getDeclaredField("address");
        af.setAccessible(true);
        NativeChunkBridge.markSectionMutation(dim, cx, cz, (byte) y);
        return NativeChunkBridge.refreshSection(dim, cx, cz, (byte) y,
                af.getLong(sBB), af.getLong(lBB), af.getLong(lBB) + 2048);
    }

    /** M5.2: coarse single-section refresh pushing live states+light natively. */
    public static void refreshOneSectionCoarse(Object chunk, int dim, int cx, int cz, int y, Object storage) {
        try {
            if (initReflection(chunk)) {
                int rc = refreshOneSection(dim, cx, cz, y, storage);
                if (rc >= 0) LIGHT_COARSE_PUSHES.incrementAndGet();
            }
        } catch (Throwable t) {
            WorldgenShadow.recordCoherencyError("coarseLightRefresh: " + t, t);
        }
    }
    public static final AtomicLong LIGHT_COARSE_PUSHES = new AtomicLong();

    public static boolean initReflectionPublicGate(Object chunk) throws Exception {
        return initReflection(chunk);
    }

    /** Synchronous refresh of one chunk's pending tracker work (used by the
     *  flush thread AND by the M4.2B packet comparator before comparing, so the
     *  snapshot is current at read time). Returns sections refreshed. */
    public static int refreshChunkNow(Object chunk) throws Exception {
        long[] _k = ChunkMutationTracker.chunkCoords(chunk);
        if (_k[0] != Long.MIN_VALUE) {
            Object _lock = refreshLock(ChunkMutationTracker.dimOf(chunk), (int) _k[0], (int) _k[1]);
            synchronized (_lock) {
                return refreshChunkNowSerialized(chunk);
            }
        }
        return refreshChunkNowSerialized(chunk);
    }

    private static int refreshChunkNowSerialized(Object chunk) throws Exception {
        // First-touch full synchronization (M4.2C skyLight finding): registration
        // light is a DEFAULT; the Java light engine sets sky light in sections
        // that never had a block mutation (no dirty bit). Until a chunk has been
        // fully synced once, refresh ALL sections + biomes, not just dirty ones.
        if (!FULLY_SYNCED.containsKey(chunk)) {
            return fullSync(chunk);
        }
        int[] work = ChunkMutationTracker.takeWork(chunk);
        // Biomes are separately tracked from sections: the live registration
        // carries zero biomes, so the current Java array is pushed on every
        // refresh pass (M4.2B biome[0] finding) before any consumer read.
        if (work == null) {
            pushBiomes(chunk, null); // no section work: still keep biomes current
            return 0;
        }
        pushBiomes(chunk, work);
        long before = SECTIONS_REFRESHED.get();
        handleChunk(chunk, work);
        // re-arm the quiescence guard: the refresh just consumed this version's work
        FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
        GUARD_REARMED.incrementAndGet();
        return (int) Math.min(Integer.MAX_VALUE, SECTIONS_REFRESHED.get() - before);
    }

    /** First full pull of a chunk: every section (states + light) + biomes,
     *  reusing the handleChunk dirty-loop with a full mask so validation runs
     *  on every synced section. */
    private static int fullSync(Object chunk) throws Exception {
        int dim = ChunkMutationTracker.dimOf(chunk);
        if (dim == ChunkMutationTracker.DIM_UNKNOWN) {
            FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
            return 0; // unknown dim: native use excluded for this chunk
        }
        long[] k = ChunkMutationTracker.chunkCoords(chunk);
        if (k[0] == Long.MIN_VALUE) return 0;
        if (NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]) <= 0) {
            FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
            return 0; // not registered: nothing to sync
        }
        int[] fullWork = { dim, 0xFFFF, 0xFFFF, 0, 0 };
        long before = SECTIONS_REFRESHED.get();
        handleChunk(chunk, fullWork);
        pushBiomes(chunk, fullWork);
        FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
        FULL_SYNCS.incrementAndGet();
        return (int) Math.min(Integer.MAX_VALUE, SECTIONS_REFRESHED.get() - before);
    }

    /** First-touch sync state. Weak: entries vanish when unloaded chunks are
     *  collected — a reloaded chunk re-syncs fully on its next consumer read. */
    private static final java.util.Map<Object, Integer> FULLY_SYNCED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Integer>());
    public static final AtomicLong FULL_SYNCS = new AtomicLong();
    public static final AtomicLong BIOMES_PUSHED = new AtomicLong();
    private static java.lang.reflect.Method M_GET_BIOMES;
    private static final ThreadLocal<java.nio.ByteBuffer> BIOME_BB_TL = ThreadLocal.withInitial(() ->
            java.nio.ByteBuffer.allocateDirect(256).order(java.nio.ByteOrder.nativeOrder()));

    private static void pushBiomes(Object chunk, int[] work) {
        try {
            int dim = (work != null) ? work[0] : ChunkMutationTracker.dimOf(chunk);
            if (dim == ChunkMutationTracker.DIM_UNKNOWN) return;
            long[] k = ChunkMutationTracker.chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            if (M_GET_BIOMES == null) {
                M_GET_BIOMES = chunk.getClass().getMethod("func_76605_m");
                M_GET_BIOMES.setAccessible(true);
            }
            // M5.4: per-thread buffer — the shared static raced across sync
            // workers (BufferOverflow, caught, non-corrupting but real)
            java.nio.ByteBuffer bb = BIOME_BB_TL.get();
            byte[] biomes = (byte[]) M_GET_BIOMES.invoke(chunk);
            bb.clear(); bb.put(biomes); bb.clear();
            NativeChunkBridge.setBiomes(dim, (int) k[0], (int) k[1], address0(bb));
            BIOMES_PUSHED.incrementAndGet();
        } catch (Throwable t) {
            WorldgenShadow.recordCoherencyError("pushBiomes: " + t, t);
        }
    }

    private static void handleChunk(Object chunk, int[] work) throws Exception {
        long[] coords = ChunkMutationTracker.chunkCoords(chunk);
        int cx = (int) coords[0], cz = (int) coords[1];
        if (cx == Long.MIN_VALUE) return; // unresolvable coords: leave (no silent coverage claim)
        int dim = work[0];

        if (dim == ChunkMutationTracker.DIM_UNKNOWN || work[3] != 0) {
            // Unknown dimension or whole-chunk replacement/biome change:
            // conservative full invalidation of native state.
            if (dim != ChunkMutationTracker.DIM_UNKNOWN) {
                NativeChunkBridge.invalidate(dim, cx, cz);
            }
            FULL_INVALIDATIONS.incrementAndGet();
            return;
        }

        // Biome-only change: push the array, sections untouched (M4.2D contract)
        if (work[4] != 0) {
            pushBiomes(chunk, work);
        }
        int mask = work[1] | work[2];
        if (mask == 0) return;

        long gen = NativeChunkBridge.findGeneration(dim, cx, cz);
        if (gen <= 0) {
            NOT_REGISTERED_SKIPPED.incrementAndGet();
            return;
        }

        if (!initReflection(chunk)) return;

        Object[] storages = (Object[]) M_GET_STORAGE.invoke(chunk);
        for (int y = 0; y < 16; y++) {
            if ((mask & (1 << y)) == 0) continue;
            refreshAndValidateSection(dim, cx, cz, gen, y, storages[y]);
            if (REFRESH_DISABLED) return;
        }
    }

    private static void refreshAndValidateSection(int dim, int cx, int cz, long gen,
                                                  int y, Object storage) throws Exception {
        ByteBuffer statesBB = STATES_TL.get();
        CharBuffer sc = statesBB.asCharBuffer();
        ByteBuffer lightBB = LIGHT_TL.get();
        ByteBuffer pktBB = PKT_TL.get();

        int[] javaGid = new int[4096];
        byte[] javaBl = new byte[2048];
        byte[] javaSl = new byte[2048];

        boolean hasStorage = storage != null
                && (F_EMPTY_STORAGE == null || storage != F_EMPTY_STORAGE.get(null));
        if (hasStorage) {
            Object container = M_CONTAINER.invoke(storage);
            // M4.3B fast path (M4.3C: dual-verified at low rate; disabled on divergence)
            int[] fast = FAST_EXTRACTOR_ENABLED ? fastExtractGlobalIds(container, javaGid) : null;
            if (fast != null && dim != Integer.MIN_VALUE) {
                dualVerify(dim, cx, cz, y, container, storage, javaGid);
            }
            if (fast == null) {
                for (int i = 0; i < 4096; i++) {
                    int x = i & 15, z = (i >> 4) & 15, subY = i >> 8;
                    Object st = M_GET_STATE.invoke(container, x, subY, z);
                    Integer gid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                    if (gid == null) { UNKNOWN_STATE_REJECTED.incrementAndGet(); javaGid[i] = -1; }
                    else javaGid[i] = gid;
                }
            }
            Object blArr = M_GET_BL.invoke(storage);
            if (blArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(blArr), 0, javaBl, 0, 2048);
            Object slArr = M_GET_SL.invoke(storage);
            if (slArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(slArr), 0, javaSl, 0, 2048);
        }
        // else: section emptied/absent in Java -> refresh to all-air (states stay 0)

        boolean highId = false;
        for (int i = 0; i < 4096; i++) {
            if ((javaGid[i] & 0xFFFF0000) != 0) { highId = true; break; }
        }
        if (highId) {
            HIGH_ID_REJECTED.incrementAndGet(); // M5.8-R2: reject before u16 narrowing
            NativeChunkBridge.invalidate(dim, cx, cz); // never leave a falsely-current snapshot
            return;
        }
        for (int i = 0; i < 4096; i++) sc.put(i, (char) javaGid[i]);
        lightBB.clear();
        lightBB.put(javaBl).put(javaSl);
        lightBB.clear();

        int remaining = NativeChunkBridge.refreshSection(dim, cx, cz, (byte) y,
                address(statesBB), address(lightBB), address(lightBB) + 2048);
        if (remaining < 0) {
            SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
            disableOnMismatch("refreshSection returned " + remaining
                    + " dim=" + dim + " cx=" + cx + " cz=" + cz + " y=" + y);
            return;
        }
        SECTIONS_REFRESHED.incrementAndGet();

        // Independent decode-validate: encode then decode the section bytes and
        // compare every cell against the Java-extracted ids.
        int n = NativeChunkBridge.encodePacket(dim, cx, cz, gen, true, true, address(pktBB), pktBB.capacity());
        if (n == -3) { // stale snapshot: not published; count and re-queue
            STALE_ENCODE_REJECTED.incrementAndGet();
            return;
        }
        if (n <= 0) {
            SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
            disableOnMismatch("encodePacket returned " + n + " after refresh dim=" + dim
                    + " cx=" + cx + " cz=" + cz + " y=" + y);
            return;
        }

        byte[] wire = new byte[n];
        pktBB.clear(); pktBB.get(wire);
        int nativeMask = NativeChunkBridge.getPrimaryBitMask(dim, cx, cz, gen);
        if (!decodeEquals(wire, y, javaGid, javaBl, javaSl, nativeMask)) {
            SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
            disableOnMismatch(FIRST_MISMATCH + " dim=" + dim + " cx=" + cx + " cz=" + cz + " y=" + y);
            return;
        }
        SECTIONS_VALIDATED.incrementAndGet();
    }

    /** Independent mask-driven decode-validate (M4.2D): parses the native
     *  packet with its actual mask via the shared bounded parser, then compares
     *  section y's 4096 cells + both light arrays against the Java extraction.
     *  Absence is decided BY THE MASK and only acceptable when Java's cells are
     *  all-air (vanilla full-chunk empty-section omission). Parse failures are
     *  validation errors, never successes. */
    private static boolean decodeEquals(byte[] wire, int wantY, int[] javaGid,
                                        byte[] javaBl, byte[] javaSl, int nativeMask) {
        M4PacketParityHarness.ParsedJ p = M4PacketParityHarness.parseByMask(wire, nativeMask, true);
        if (p == null) {
            FIRST_MISMATCH = "packet failed mask-driven parse (mask=" + nativeMask + ")";
            return false;
        }
        M4PacketParityHarness.Sec found = null;
        for (Object o : p.sections) {
            M4PacketParityHarness.Sec s2 = (M4PacketParityHarness.Sec) o;
            if (s2.idx == wantY) { found = s2; break; }
        }
        if (found == null) {
            for (int g : javaGid) {
                if (g != 0) {
                    FIRST_MISMATCH = "mask omits section " + wantY + " but Java non-air";
                    return false;
                }
            }
            SECTIONS_VALIDATED_ALLAIR_ABSENT.incrementAndGet();
            return true;
        }
        for (int i = 0; i < 4096; i++) {
            if (found.cellGid[i] != javaGid[i]) {
                FIRST_MISMATCH = "cell idx=" + i + " wireGid=" + found.cellGid[i] + " javaGid=" + javaGid[i];
                return false;
            }
        }
        for (int i = 0; i < 2048; i++) {
            if ((found.bl[i] & 0xFF) != (javaBl[i] & 0xFF)) { FIRST_MISMATCH = "blockLight[" + i + "]"; return false; }
            if ((found.sl[i] & 0xFF) != (javaSl[i] & 0xFF)) { FIRST_MISMATCH = "skyLight[" + i + "]"; return false; }
        }
        return true;
    }

    private static void disableOnMismatch(String why) {
        if (!REFRESH_DISABLED) {
            REFRESH_DISABLED = true;
            FIRST_MISMATCH = why;
        }
    }

    static boolean initReflection(Object chunk) throws Exception {
        if (M_GET_STORAGE != null) return true;
        Class<?> chunkCls = chunk.getClass();
        M_GET_STORAGE = chunkCls.getMethod("func_76587_i");
        M_GET_STORAGE.setAccessible(true);
        F_EMPTY_STORAGE = net.minecraft.world.chunk.Chunk.class.getField("field_186036_a");
        F_EMPTY_STORAGE.setAccessible(true);

        Field regF = net.minecraft.block.Block.class.getDeclaredField("field_176229_d");
        regF.setAccessible(true);
        REG_MAP = regF.get(null);
        M_REG_GET_ID = REG_MAP.getClass().getMethod("func_148747_b", Object.class);
        M_REG_GET_ID.setAccessible(true);

        Class<?> storageCls = Class.forName("net.minecraft.world.chunk.storage.ExtendedBlockStorage");
        M_CONTAINER = storageCls.getMethod("func_186049_g");
        M_CONTAINER.setAccessible(true);
        M_GET_BL = storageCls.getMethod("func_76661_k");
        M_GET_BL.setAccessible(true);
        M_GET_SL = storageCls.getMethod("func_76671_l");
        M_GET_SL.setAccessible(true);

        Class<?> contCls = Class.forName("net.minecraft.world.chunk.BlockStateContainer");
        M_GET_STATE = contCls.getMethod("func_186016_a", int.class, int.class, int.class);
        M_GET_STATE.setAccessible(true);

        Class<?> nibCls = Class.forName("net.minecraft.world.chunk.NibbleArray");
        M_NIBBLE_BYTES = nibCls.getMethod("func_177481_a");
        M_NIBBLE_BYTES.setAccessible(true);
        return true;
    }

    private static long address(ByteBuffer b) throws Exception {
        Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }

    public static String statsLine() {
        StringBuilder sb = new StringBuilder();
        java.nio.ByteBuffer st = STATS_TL.get();
        st.clear();
        if (NativeChunkBridge.isAvailable() && NativeChunkBridge.getRegistryStats(address0(st)) > 0) {
            long chunks = st.getLong(0), sections = st.getLong(8), bytes = st.getLong(16);
            long alloc = st.getLong(24), released = st.getLong(32), evicted = st.getLong(40);
            sb.append(" m4_stats_chunks=").append(chunks)
              .append(" m4_stats_sections=").append(sections)
              .append(" m4_stats_retained_bytes=").append(bytes)
              .append(" m4_stats_sections_allocated=").append(alloc)
              .append(" m4_stats_sections_released=").append(released)
              .append(" m4_stats_chunks_evicted=").append(evicted);
        }
        sb.append(" m42_flush_cycles=").append(FLUSH_CYCLES.get())
          .append(" m42_unloads_flushed=").append(UNLOADS_FLUSHED.get())
          .append(" m42_full_invalidations=").append(FULL_INVALIDATIONS.get())
          .append(" m42_sections_refreshed=").append(SECTIONS_REFRESHED.get())
          .append(" m42_sections_validated=").append(SECTIONS_VALIDATED.get())
          .append(" m42_biomes_pushed=").append(BIOMES_PUSHED.get())
          .append(" m42_full_syncs=").append(FULL_SYNCS.get())
          .append(" m43b_sync_enqueued=").append(SYNC_ENQUEUED.get())
          .append(" m43b_sync_done=").append(SYNC_DONE.get())
          .append(" m43b_sync_dropped_full=").append(SYNC_DROPPED_FULL.get())
          .append(" m43b_sync_ns_total=").append(SYNC_NS_TOTAL.get())
          .append(" m43b_sync_queue_delay_ms_total=").append(SYNC_QUEUE_DELAY_MS_TOTAL.get())
          .append(" m43b_dirty_enqueued=").append(DIRTY_ENQUEUED.get())
          .append(" m43b_dirty_refreshes=").append(DIRTY_REFRESHES.get())
          .append(" m43g_guard_rearmed=").append(GUARD_REARMED.get())
          .append(" m58r2_high_id_rejected=").append(HIGH_ID_REJECTED.get())
          .append(" m58h_unknown_state_rejected=").append(UNKNOWN_STATE_REJECTED.get())
          .append(" m43c_dual_verify_runs=").append(DUAL_VERIFY_RUNS.get())
          .append(" m43c_dual_verify_diverge=").append(DUAL_VERIFY_DIVERGE.get())
          .append(" m43c_fast_extractor_enabled=").append(FAST_EXTRACTOR_ENABLED)
          .append(" m43c_dual_mutation_inflight=").append(DUAL_MUTATION_INFLIGHT.get())
          .append(" m42_sections_validated_allair_absent=").append(SECTIONS_VALIDATED_ALLAIR_ABSENT.get())
          .append(" m42_validation_mismatches=").append(SECTIONS_VALIDATION_MISMATCH.get())
          .append(" m42_stale_encode_rejected=").append(STALE_ENCODE_REJECTED.get())
          .append(" m42_not_registered_skipped=").append(NOT_REGISTERED_SKIPPED.get())
          .append(" m42_refresh_disabled=").append(REFRESH_DISABLED)
          .append(" m42_first_mismatch=").append(FIRST_MISMATCH)
          .append(" m42_tracker_chunks=").append(ChunkMutationTracker.trackedChunks())
          .append(" m42_tracker_pending_unloads=").append(ChunkMutationTracker.pendingUnloads())
          .append(" m42_hook_block_sets=").append(ChunkMutationTracker.HOOK_BLOCK_SETS.get())
          .append(" m42_hook_light_sets=").append(ChunkMutationTracker.HOOK_LIGHT_SETS.get())
          .append(" m42_hook_storage_replaced=").append(ChunkMutationTracker.HOOK_STORAGE_REPLACED.get())
          .append(" m42_hook_biome_changed=").append(ChunkMutationTracker.HOOK_BIOME_CHANGED.get())
          .append(" m42_hook_unloads=").append(ChunkMutationTracker.HOOK_UNLOADS.get())
          .append(" m42_hook_skylight_regen=").append(ChunkMutationTracker.HOOK_SKYLIGHT_REGEN.get())
          .append(" m42_hook_chunk_loaded=").append(ChunkMutationTracker.HOOK_CHUNK_LOADED.get());
        try {
            sb.append(" m42_chunk_transformer=").append(com.rustcraft.coremod.ChunkMutationTransformer.lastStatus)
              .append("/").append(com.rustcraft.coremod.ChunkMutationTransformer.transformCount);
        } catch (Throwable ignore) { }
        return sb.toString();
    }

    private static long address0(java.nio.ByteBuffer b) {
        try {
            Method m = b.getClass().getMethod("address");
            m.setAccessible(true);
            return (Long) m.invoke(b);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int readVarInt(byte[] b, int p) {
        int result = 0, shift = 0;
        while (true) {
            byte by = b[p++];
            result |= (by & 0x7F) << shift;
            if ((by & 0x80) == 0) return result;
            shift += 7;
        }
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { v >>>= 7; n++; }
        return n;
    }
}
