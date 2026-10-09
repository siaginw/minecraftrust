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
    /// OPT-SYNC-002: canonical-state -> global-id cache (IBlockState
    /// instances are interned; IdentityHashMap is sound and kills the
    /// per-palette-entry registry reflective invoke)
    private static final java.util.Map<Object, Integer> GID_CACHE =
            new java.util.IdentityHashMap<>();

    /// OPT-SYNC-002 phase breakdown (per real-section refresh; Relaxed —
    /// server thread dominates; diagnostics only)
    public static final AtomicLong PHASE_EXTRACT_NS = new AtomicLong();
    public static final AtomicLong PHASE_STAGE_NS = new AtomicLong();
    public static final AtomicLong PHASE_JNI_NS = new AtomicLong();
    public static final AtomicLong PHASE_VALIDATE_NS = new AtomicLong();
    public static final AtomicLong PHASE_PRECHECK_NS = new AtomicLong();
    public static final AtomicLong PHASE_ABSENT_NS = new AtomicLong();
    public static final AtomicLong PHASE_CHUNKSYNC_NS = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATED = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATED_ALLAIR_ABSENT = new AtomicLong();
    public static final AtomicLong SECTIONS_VALIDATION_MISMATCH = new AtomicLong();
    public static final AtomicLong STALE_ENCODE_REJECTED = new AtomicLong();
    public static final AtomicLong NOT_REGISTERED_SKIPPED = new AtomicLong();
    public static volatile boolean REFRESH_DISABLED = false;
    public static volatile String FIRST_MISMATCH = "none";

    /// OPT-SYNC-005 reconciling chunk-op timeline (attribution task §3/§4):
    /// per-op wall/CPU/alloc/GC + mutually exclusive child phases so the
    /// chunksync residue is ACCOUNTED, not silent. OP_WALL starts at
    /// refreshChunkNow entry (includes lock acquire); PHASE_CHUNKSYNC_NS
    /// keeps its old narrower meaning (handleChunk+pushBiomes of fullSync).
    /// Unaccounted(op) = OP_WALL - TRACKER - RESOLVE - BIOMES - SEC_TOTAL.
    public static final AtomicLong OP_COUNT = new AtomicLong();
    public static final AtomicLong OP_WALL_NS = new AtomicLong();
    public static final AtomicLong OP_CPU_NS = new AtomicLong();
    public static final AtomicLong OP_ALLOC_BYTES = new AtomicLong();
    public static final AtomicLong OP_GC_PAUSE_NS = new AtomicLong();
    public static final AtomicLong OP_GC_COUNT = new AtomicLong();
    public static final AtomicLong CH_RESOLVE_NS = new AtomicLong();
    public static final AtomicLong CH_TRACKER_NS = new AtomicLong();
    public static final AtomicLong BIOMES_WALL_NS = new AtomicLong();
    public static final AtomicLong SEC_TOTAL_NS = new AtomicLong();
    public static final AtomicLong SEC_ALLOC_NS = new AtomicLong();
    public static final AtomicLong DV_VERIFY_NS = new AtomicLong();
    // PHASE_PRECHECK_NS semantics CORRECTED (OPT-SYNC-005): it used to wrap
    // an empty span on the absent branch (~0 by construction — the
    // "zero on a broken timer" trap); it now wraps the REAL precheck
    // (isNativeDefaultSection on present sections).

    // OPT-SYNC-005 samplers. CPU time may quantize per-op on Windows; the
    // per-run SUM still separates CPU-bound from wait-bound.
    private static final java.lang.management.ThreadMXBean TMX =
            java.lang.management.ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean TMXA =
            (TMX instanceof com.sun.management.ThreadMXBean)
                    ? (com.sun.management.ThreadMXBean) TMX : null;
    private static final java.util.List<java.lang.management.GarbageCollectorMXBean> GC_BEANS =
            java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();

    // RUNSCOPE-JUSTIFIED: diagnostic samplers — 0 marks UNMEASURED
    // (unsupported JVM/bean), never a semantic value and never a claim.
    private static long cpuNow() {
        try {
            return TMX.isCurrentThreadCpuTimeSupported()
                    ? TMX.getCurrentThreadCpuTime() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
    private static long allocNow() {
        try {
            return TMXA != null
                    ? TMXA.getThreadAllocatedBytes(Thread.currentThread().getId()) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
    private static long gcPauseNow() {
        long t = 0;
        for (java.lang.management.GarbageCollectorMXBean b : GC_BEANS) {
            long ms = b.getCollectionTime();
            if (ms > 0) t += ms;
        }
        return t * 1000000L; // bean reports ms
    }
    private static long gcCountNow() {
        long c = 0;
        for (java.lang.management.GarbageCollectorMXBean b : GC_BEANS) {
            long n = b.getCollectionCount();
            if (n > 0) c += n;
        }
        return c;
    }

    // per-op detail slots (ThreadLocal: concurrent worldgen registrations
    // each get their own op; section loop accumulates into the owner op)
    private static final int S_WALL0 = 0, S_CPU0 = 1, S_ALLOC0 = 2, S_GC0 = 3, S_GCC0 = 4,
            S_DIM = 5, S_CX = 6, S_CZ = 7, S_GEN = 8,
            S_SEC_TOTAL = 9, S_SEC_ALLOC = 10, S_PRE = 11, S_EXTRACT = 12, S_STAGE = 13,
            S_JNI = 14, S_VALIDATE = 15, S_ABSENT = 16, S_RESOLVE = 17, S_TRACKER = 18,
            S_BIOMES = 19, S_DV = 20, S_FULL = 21, S_SLOTS = 22;
    private static final ThreadLocal<long[]> OP_CTX = new ThreadLocal<>();
    private static final AtomicLong TRACE_SEQ_REG = new AtomicLong();
    private static final AtomicLong TRACE_SEQ_DIRTY = new AtomicLong();

    /** add elapsed-since-fromNanos to a global counter AND the current
     * thread's op detail (if any). No-op on null ctx (direct harness calls). */
    private static void addPhase(int slot, AtomicLong global, long fromNanos) {
        long d = System.nanoTime() - fromNanos;
        global.addAndGet(d);
        long[] c = OP_CTX.get();
        if (c != null) c[slot] += d;
    }

    private static long[] opBegin() {
        long[] c = new long[S_SLOTS];
        c[S_WALL0] = System.nanoTime();
        c[S_CPU0] = cpuNow();
        c[S_ALLOC0] = allocNow();
        c[S_GC0] = gcPauseNow();
        c[S_GCC0] = gcCountNow();
        OP_CTX.set(c);
        return c;
    }

    private static void opEnd(long[] c) {
        OP_CTX.remove();
        boolean full = c[S_FULL] != 0;
        long wall = System.nanoTime() - c[S_WALL0];
        long cpu = cpuNow() - c[S_CPU0];
        long alloc = allocNow() - c[S_ALLOC0];
        long gcP = gcPauseNow() - c[S_GC0];
        long gcC = gcCountNow() - c[S_GCC0];
        OP_COUNT.incrementAndGet();
        OP_WALL_NS.addAndGet(wall);
        OP_CPU_NS.addAndGet(cpu < 0 ? 0 : cpu);
        OP_ALLOC_BYTES.addAndGet(alloc < 0 ? 0 : alloc);
        OP_GC_PAUSE_NS.addAndGet(gcP < 0 ? 0 : gcP);
        OP_GC_COUNT.addAndGet(gcC < 0 ? 0 : gcC);
        // bounded diagnostic trace: first 8 ops per reason per JVM
        long seq = (full ? TRACE_SEQ_REG : TRACE_SEQ_DIRTY).incrementAndGet();
        if (seq <= 8) {
            long secChildren = c[S_SEC_ALLOC] + c[S_PRE] + c[S_EXTRACT] + c[S_STAGE]
                    + c[S_JNI] + c[S_VALIDATE] + c[S_ABSENT];
            System.out.println("[sync-op-trace] {\"reason\":\""
                    + (full ? "registration" : "dirty-refresh")
                    + "\",\"seq\":" + seq
                    + ",\"opId\":{\"dim\":" + c[S_DIM] + ",\"cx\":" + c[S_CX]
                    + ",\"cz\":" + c[S_CZ] + ",\"gen\":" + c[S_GEN] + "}"
                    + ",\"wallUs\":" + wall / 1000
                    + ",\"cpuUs\":" + cpu / 1000
                    + ",\"allocKB\":" + alloc / 1024
                    + ",\"gcPauseUs\":" + gcP / 1000
                    + ",\"gcCount\":" + gcC
                    + ",\"phUs\":{\"tracker\":" + c[S_TRACKER] / 1000
                    + ",\"resolve\":" + c[S_RESOLVE] / 1000
                    + ",\"biomes\":" + c[S_BIOMES] / 1000
                    + ",\"secTotal\":" + c[S_SEC_TOTAL] / 1000
                    + ",\"secAlloc\":" + c[S_SEC_ALLOC] / 1000
                    + ",\"precheck\":" + c[S_PRE] / 1000
                    + ",\"extract\":" + c[S_EXTRACT] / 1000
                    + ",\"stage\":" + c[S_STAGE] / 1000
                    + ",\"jni\":" + c[S_JNI] / 1000
                    + ",\"validate\":" + c[S_VALIDATE] / 1000
                    + ",\"absent\":" + c[S_ABSENT] / 1000
                    + ",\"dv\":" + c[S_DV] / 1000 + "}"
                    + ",\"opUnaccountedUs\":" + (wall - c[S_TRACKER] - c[S_RESOLVE]
                        - c[S_BIOMES] - c[S_SEC_TOTAL]) / 1000
                    + ",\"secUnaccountedUs\":" + (c[S_SEC_TOTAL] - secChildren) / 1000
                    + "}");
        }
    }

    private static volatile Thread FLUSHER;

    // ===================== OPT-SYNC-006 bake-off machinery =====================
    // C0 = shipped implementation (default; production behavior unchanged).
    // C1 = reuse pack: leased readback buffer + scratch arrays (A,B),
    //      ClassValue-cached precheck Method (C), op-scoped ChunkCtx (D).
    // C2 = C1 + MethodHandle independent-verifier invocation (§8 candidate).
    static final class SyncImpl {
        final boolean leases, cachedPre, useCtx, mhVerify;
        SyncImpl(boolean leases, boolean cachedPre, boolean useCtx, boolean mhVerify) {
            this.leases = leases; this.cachedPre = cachedPre;
            this.useCtx = useCtx; this.mhVerify = mhVerify;
        }
    }
    static final SyncImpl IMPL_C0 = new SyncImpl(false, false, false, false);
    static final SyncImpl IMPL_C1 = new SyncImpl(true, true, true, false);
    static final SyncImpl IMPL_C2 = new SyncImpl(true, true, true, true);
    public static volatile SyncImpl ACTIVE_IMPL = IMPL_C0;
    static {
        // OPT-SYNC-006 PROMOTION: C2 is the DEFAULT (live A/B 3/3 pairs won,
        // clean separation: worst-C2 214.0ms < best-C0 222.7ms; DV −26%,
        // validate −17%, alloc churn −91%; receipt OPT-SYNC-006). The
        // shipped C0 path stays reproducible with -Drustcraft.syncPath=C0.
        String p = System.getProperty("rustcraft.syncPath", "C2");
        if ("C0".equals(p)) ACTIVE_IMPL = IMPL_C0;
        else if ("C1".equals(p)) ACTIVE_IMPL = IMPL_C1;
        else if ("C2".equals(p)) ACTIVE_IMPL = IMPL_C2;
        else System.err.println("[sync-impl] unknown rustcraft.syncPath=" + p + " (C0|C1|C2); using C2");
        // retro 2026-10-09: ALWAYS print the effective selection — the
        // intended-candidate check (bake-off/live-A/B §13) must be a grep,
        // not a deduction from counter signatures
        System.out.println("[sync-impl] active=" + p
                + (System.getProperty("rustcraft.syncPath") == null ? " (default)" : ""));
    }

    // per-arm phase timer set: normal runs wrap the EXISTING statics (receipt
    // continuity); paired bake-off arms wrap the BAKE0_/BAKE1_ statics
    static final class PhaseSet {
        final AtomicLong secTotal, secAlloc, pre, extract, dv, stage, jni, validate, absent, sections, alloc;
        PhaseSet(AtomicLong secTotal, AtomicLong secAlloc, AtomicLong pre, AtomicLong extract,
                 AtomicLong dv, AtomicLong stage, AtomicLong jni, AtomicLong validate,
                 AtomicLong absent, AtomicLong sections, AtomicLong alloc) {
            this.secTotal = secTotal; this.secAlloc = secAlloc; this.pre = pre;
            this.extract = extract; this.dv = dv; this.stage = stage; this.jni = jni;
            this.validate = validate; this.absent = absent; this.sections = sections;
            this.alloc = alloc;
        }
    }
    public static final AtomicLong BAKE0_SEC_TOTAL_NS = new AtomicLong();
    public static final AtomicLong BAKE0_SEC_ALLOC_NS = new AtomicLong();
    public static final AtomicLong BAKE0_PRECHECK_NS = new AtomicLong();
    public static final AtomicLong BAKE0_EXTRACT_NS = new AtomicLong();
    public static final AtomicLong BAKE0_DV_NS = new AtomicLong();
    public static final AtomicLong BAKE0_STAGE_NS = new AtomicLong();
    public static final AtomicLong BAKE0_JNI_NS = new AtomicLong();
    public static final AtomicLong BAKE0_VALIDATE_NS = new AtomicLong();
    public static final AtomicLong BAKE0_ABSENT_NS = new AtomicLong();
    public static final AtomicLong BAKE0_SECTIONS = new AtomicLong();
    public static final AtomicLong BAKE0_ALLOC_BYTES = new AtomicLong();
    public static final AtomicLong BAKE1_SEC_TOTAL_NS = new AtomicLong();
    public static final AtomicLong BAKE1_SEC_ALLOC_NS = new AtomicLong();
    public static final AtomicLong BAKE1_PRECHECK_NS = new AtomicLong();
    public static final AtomicLong BAKE1_EXTRACT_NS = new AtomicLong();
    public static final AtomicLong BAKE1_DV_NS = new AtomicLong();
    public static final AtomicLong BAKE1_STAGE_NS = new AtomicLong();
    public static final AtomicLong BAKE1_JNI_NS = new AtomicLong();
    public static final AtomicLong BAKE1_VALIDATE_NS = new AtomicLong();
    public static final AtomicLong BAKE1_ABSENT_NS = new AtomicLong();
    public static final AtomicLong BAKE1_SECTIONS = new AtomicLong();
    public static final AtomicLong BAKE1_ALLOC_BYTES = new AtomicLong();
    public static final AtomicLong[] BAKE_DV_RUNS = { new AtomicLong(), new AtomicLong() };
    static final PhaseSet TS_NORMAL = new PhaseSet(SEC_TOTAL_NS, SEC_ALLOC_NS,
            PHASE_PRECHECK_NS, PHASE_EXTRACT_NS, DV_VERIFY_NS, PHASE_STAGE_NS,
            PHASE_JNI_NS, PHASE_VALIDATE_NS, PHASE_ABSENT_NS, null, null);
    static final PhaseSet TS_BAKE0 = new PhaseSet(BAKE0_SEC_TOTAL_NS, BAKE0_SEC_ALLOC_NS,
            BAKE0_PRECHECK_NS, BAKE0_EXTRACT_NS, BAKE0_DV_NS, BAKE0_STAGE_NS,
            BAKE0_JNI_NS, BAKE0_VALIDATE_NS, BAKE0_ABSENT_NS, BAKE0_SECTIONS, BAKE0_ALLOC_BYTES);
    static final PhaseSet TS_BAKE1 = new PhaseSet(BAKE1_SEC_TOTAL_NS, BAKE1_SEC_ALLOC_NS,
            BAKE1_PRECHECK_NS, BAKE1_EXTRACT_NS, BAKE1_DV_NS, BAKE1_STAGE_NS,
            BAKE1_JNI_NS, BAKE1_VALIDATE_NS, BAKE1_ABSENT_NS, BAKE1_SECTIONS, BAKE1_ALLOC_BYTES);

    /// §10 paired in-vivo harness: rustcraft.syncPair=C0:C1 runs BOTH
    /// implementations back-to-back on the SAME live section objects (the
    /// strongest common-corpus guarantee), alternating execution order per
    /// section index to cancel ordering bias. Work counters count once
    /// (arm 0); correctness counters count on BOTH arms. Double-running a
    /// section is idempotent (identical bytes written; readback validates
    /// each arm independently).
    static final SyncImpl[] PAIR_IMPLS;
    static final java.util.concurrent.atomic.AtomicInteger PAIR_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    static {
        SyncImpl[] pr = null;
        String ps = System.getProperty("rustcraft.syncPair");
        if (ps != null) {
            String[] parts = ps.split(":");
            SyncImpl a = implByName(parts[0]), b = parts.length > 1 ? implByName(parts[1]) : null;
            if (a == null || b == null) {
                System.err.println("[sync-pair] bad rustcraft.syncPair=" + ps + " (want e.g. C0:C1); pairing disabled");
            } else {
                pr = new SyncImpl[] { a, b };
                System.out.println("[sync-pair] paired bake-off: " + ps
                        + " (order alternates per section; per-arm BAKE0_/BAKE1_ timers)");
            }
        }
        PAIR_IMPLS = pr;
    }
    static SyncImpl implByName(String n) {
        if ("C0".equals(n)) return IMPL_C0;
        if ("C1".equals(n)) return IMPL_C1;
        if ("C2".equals(n)) return IMPL_C2;
        return null;
    }

    /// §5 op-scoped scratch leasing. Slot model: each PURPOSE has its own
    /// slot + held flag; slots are independent (gid and dv NEVER alias —
    /// §6 verifier independence). Re-entrant acquire of a HELD slot yields
    /// a FRESH instance instead of aliasing (nested-sync safety; counted).
    /// light slots are ZEROED on lease (null-nibble semantics must not see
    /// stale tails); int slots are fully-overwritten-by-contract (packed
    /// decode, fallback extractor, and dualVerify each write all 4096).
    /// readback: direct 20480 LE, position reset on lease; the native side
    /// writes exactly 20480 bytes on success (rbr==0) or returns negative.
    public static final AtomicLong LEASE_ACQ = new AtomicLong();
    public static final AtomicLong LEASE_REENTRANT_FALLBACK = new AtomicLong();
    public static final AtomicLong LEASE_RETAINED_BYTES = new AtomicLong();

    static final class SyncScratch {
        int[] gid; boolean gidHeld;
        byte[] bl; boolean blHeld;
        byte[] sl; boolean slHeld;
        int[] dv; boolean dvHeld;
        ByteBuffer readback; boolean rbHeld;
        java.nio.IntBuffer statesView; // absolute-put view over the fixed STATES_TL buffer
    }
    static final ThreadLocal<SyncScratch> SCRATCH_TL = new ThreadLocal<SyncScratch>() {
        @Override protected SyncScratch initialValue() {
            SyncScratch s = new SyncScratch();
            s.readback = ByteBuffer.allocateDirect(20480).order(ByteOrder.LITTLE_ENDIAN);
            LEASE_RETAINED_BYTES.addAndGet(20480);
            return s;
        }
    };
    static int[] leaseGid(SyncScratch s) {
        if (s.gid == null) s.gid = new int[4096];
        if (!s.gidHeld) { s.gidHeld = true; LEASE_ACQ.incrementAndGet(); return s.gid; }
        LEASE_REENTRANT_FALLBACK.incrementAndGet();
        return new int[4096];
    }
    static void releaseGid(SyncScratch s, int[] a) {
        if (a == s.gid) s.gidHeld = false;
    }
    static byte[] leaseBl(SyncScratch s) {
        if (s.bl == null) s.bl = new byte[2048];
        if (!s.blHeld) {
            s.blHeld = true; LEASE_ACQ.incrementAndGet();
            java.util.Arrays.fill(s.bl, (byte) 0);
            return s.bl;
        }
        LEASE_REENTRANT_FALLBACK.incrementAndGet();
        return new byte[2048];
    }
    static void releaseBl(SyncScratch s, byte[] a) { if (a == s.bl) s.blHeld = false; }
    static byte[] leaseSl(SyncScratch s) {
        if (s.sl == null) s.sl = new byte[2048];
        if (!s.slHeld) {
            s.slHeld = true; LEASE_ACQ.incrementAndGet();
            java.util.Arrays.fill(s.sl, (byte) 0);
            return s.sl;
        }
        LEASE_REENTRANT_FALLBACK.incrementAndGet();
        return new byte[2048];
    }
    static void releaseSl(SyncScratch s, byte[] a) { if (a == s.sl) s.slHeld = false; }
    static int[] leaseDv(SyncScratch s) {
        if (s.dv == null) s.dv = new int[4096];
        if (!s.dvHeld) { s.dvHeld = true; LEASE_ACQ.incrementAndGet(); return s.dv; }
        LEASE_REENTRANT_FALLBACK.incrementAndGet();
        return new int[4096];
    }
    static void releaseDv(SyncScratch s, int[] a) { if (a == s.dv) s.dvHeld = false; }
    static ByteBuffer leaseReadback(SyncScratch s) {
        if (!s.rbHeld) {
            s.rbHeld = true; LEASE_ACQ.incrementAndGet();
            s.readback.clear();
            return s.readback;
        }
        LEASE_REENTRANT_FALLBACK.incrementAndGet();
        return ByteBuffer.allocateDirect(20480).order(ByteOrder.LITTLE_ENDIAN);
    }
    static void releaseReadback(SyncScratch s, ByteBuffer b) {
        if (b == s.readback) s.rbHeld = false;
    }

    /// §7 reflection caches keyed on RUNTIME class identity. ClassValue
    /// entries die with the class (and its loader) — no unbounded global
    /// cache retaining discarded LaunchClassLoader classes. Exact member
    /// identity: name + parameter types via getMethod; miss => null and
    /// the caller keeps its fail-open behavior.
    public static final AtomicLong CV_PRE_HITS = new AtomicLong();
    public static final AtomicLong CV_PRE_MISSES = new AtomicLong();
    static final ClassValue<Method> CV_IS_EMPTY = new ClassValue<Method>() {
        @Override protected Method computeValue(Class<?> c) {
            try {
                Method m = c.getMethod("func_76663_a");
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                return null;
            }
        }
    };

    /// §8 C2: MethodHandle plans unreflected from the SAME runtime-class
    /// Methods (virtual dispatch and access identical to the reflective
    /// baseline; access established at plan build, per JDK8 unreflect of
    /// an accessible Method). Null plan => caller falls back to Method.invoke.
    public static final AtomicLong MH_PLANS_BUILT = new AtomicLong();
    private static final java.lang.invoke.MethodHandles.Lookup MH_LOOKUP =
            java.lang.invoke.MethodHandles.lookup();
    private static final ClassValue<java.lang.invoke.MethodHandle> CV_MH_GET_STATE =
            new ClassValue<java.lang.invoke.MethodHandle>() {
                @Override protected java.lang.invoke.MethodHandle computeValue(Class<?> c) {
                    try {
                        Method m = c.getMethod("func_186016_a", int.class, int.class, int.class);
                        m.setAccessible(true);
                        MH_PLANS_BUILT.incrementAndGet();
                        return MH_LOOKUP.unreflect(m);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            };
    private static volatile java.lang.invoke.MethodHandle MH_REG_GET_ID;
    private static java.lang.invoke.MethodHandle regGetIdPlan() {
        java.lang.invoke.MethodHandle h = MH_REG_GET_ID;
        if (h == null) {
            try {
                Method m = REG_MAP.getClass().getMethod("func_148747_b", Object.class);
                m.setAccessible(true);
                h = MH_LOOKUP.unreflect(m);
                MH_PLANS_BUILT.incrementAndGet();
                MH_REG_GET_ID = h;
            } catch (Throwable t) {
                return null;
            }
        }
        return h;
    }

    /// §4-D op-scoped chunk context: resolved ONCE per sync op under the
    /// per-chunk refreshLock (single-threaded within the op; no reuse
    /// across ops — revalidation happens by construction on the next op).
    static final class ChunkCtx {
        final int dim, cx, cz;
        final long gen;
        ChunkCtx(int dim, int cx, int cz, long gen) {
            this.dim = dim; this.cx = cx; this.cz = cz; this.gen = gen;
        }
    }
    private static volatile boolean started = false;

    private static final int FLUSH_INTERVAL_MS = 2000;

    // cached reflection
    private static Method M_GET_STORAGE, M_CONTAINER, M_GET_STATE, M_REG_GET_ID, M_GET_BL, M_GET_SL, M_NIBBLE_BYTES;
    private static Field F_EMPTY_STORAGE;
    private static Object REG_MAP;
    private static Object AIR_STATE;

    private static final ThreadLocal<ByteBuffer> STATES_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()));
    /// OPT-SYNC-002: reusable packed-transfer staging (grown to max
    /// needed; the first-cut per-section allocateDirect×2 cost more than
    /// the transfer saved — direct allocation is ~µs-tens-of-µs each)
    private static final ThreadLocal<ByteBuffer> PACKED_WORDS_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(1024 * 8).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<ByteBuffer> PACKED_PAL_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.LITTLE_ENDIAN));
    /// the palette-table BACKING buffer (stable address; the IntBuffer is
    /// a per-section view over it)
    private static final ThreadLocal<ByteBuffer> PACKED_PAL_BACKING_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.LITTLE_ENDIAN));
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
                handleChunk(chunk, work, null);
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

    /// OPT-SYNC-004 ROOT-CAUSE FIX: derive every class from the LIVE
    /// container object's class graph — Class.forName uses the SYSTEM
    /// loader and cannot see LaunchClassLoader classes, so the packed
    /// extraction and fastExtract silently failed on EVERY section since
    /// OPT-SYNC-002 shipped (dv2-probe: packed=false on first gate;
    /// per-cell fallback ran instead). Same trap as OPT-MIRROR-001's
    /// mirrorBatch. The container class IS the return type of the
    /// already-live-resolved M_CONTAINER (EBS.func_186049_g).
    static void initFastExtract(Object liveContainer) throws Exception {
        Class<?> cont = liveContainer.getClass();
        F_CONTAINER_ARR = cont.getDeclaredField("field_186021_b"); // BitArray
        F_CONTAINER_ARR.setAccessible(true);
        F_CONTAINER_PAL = cont.getDeclaredField("field_186022_c"); // palette
        F_CONTAINER_PAL.setAccessible(true);
        F_CONTAINER_BITS = cont.getDeclaredField("field_186024_e"); // bits
        F_CONTAINER_BITS.setAccessible(true);
        Class<?> bitArr = F_CONTAINER_ARR.getType(); // BitArray
        M_WORDS = bitArr.getMethod("func_188143_a"); // long[]
        M_WORDS.setAccessible(true);
        Class<?> palIface = F_CONTAINER_PAL.getType(); // palette iface
        M_PAL_BYID = palIface.getMethod("func_186039_a", int.class);
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
            if (F_CONTAINER_ARR == null) initFastExtract(container);
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
            if (F_CONTAINER_ARR == null) initFastExtract(container);
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
                // Both branches go through refreshChunkNow so EVERY Java ->
                // native section push takes the per-chunk refresh lock: the
                // single-copy admission encodes under that same lock, and an
                // unlocked first-sync push racing the encode produced
                // single-cell shadow divergences on revisited chunks.
                boolean synced = FULLY_SYNCED.containsKey(chunk);
                refreshChunkNow(chunk);
                if (synced) {
                    DIRTY_REFRESHES.incrementAndGet();
                } else {
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
    /// §2 OPT-SYNC-004 probe: arrivals at the packed dualVerify gate
    /// (NOT_REQUESTED vs INELIGIBLE vs ATTEMPTED discrimination)
    public static final java.util.concurrent.atomic.AtomicLong DV_DECISIONS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DUAL_VERIFY_DIVERGE = new java.util.concurrent.atomic.AtomicLong();
    public static volatile String DUAL_FIRST_DIVERGENCE = "none";
    public static final java.util.concurrent.atomic.AtomicLong DUAL_MUTATION_INFLIGHT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long DUAL_VERIFY_EVERY = 8;
    public static volatile boolean FAST_EXTRACTOR_ENABLED = true;

    /**
     * Arbitration for a byte-different shadow compare: extract the chunk's
     * CURRENT cells and light faithfully (per-cell vanilla read), or null on
     * any reflection surprise. The capture handler compares this against the
     * Java-transmitted payload semantics: equal means the transmitted packet
     * was faithful to the chunk's current state and the frozen body encoded
     * an earlier state (concurrent mutation - including off-tracker in-place
     * writes); different means the body matches no Java view (real mismatch).
     * Index [section] = int[4096] gids; light sections are 2 x 2048 byte
     * arrays packed as [bl|sl] per section.
     */
    public static Object[] extractFaithfulForArbitration(Object chunk) {
        try {
            if (M_GET_STORAGE == null) return null;
            Object[] sections = (Object[]) M_GET_STORAGE.invoke(chunk);
            if (sections == null || sections.length != 16) return null;
            Object[] out = new Object[16];
            for (int y = 0; y < 16; y++) {
                Object storage = sections[y];
                if (storage == null
                        || (F_EMPTY_STORAGE != null && storage == F_EMPTY_STORAGE.get(null))) {
                    continue;
                }
                Object container = M_CONTAINER.invoke(storage);
                if (container == null) continue;
                int[] gids = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    Object st = M_GET_STATE.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
                    Integer gid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                    gids[i] = gid == null ? -1 : gid;
                }
                byte[] bl = new byte[2048];
                byte[] sl = new byte[2048];
                Object blArr = M_GET_BL.invoke(storage);
                if (blArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(blArr), 0, bl, 0, 2048);
                Object slArr = M_GET_SL.invoke(storage);
                if (slArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(slArr), 0, sl, 0, 2048);
                byte[] light = new byte[4096];
                System.arraycopy(bl, 0, light, 0, 2048);
                System.arraycopy(sl, 0, light, 2048, 2048);
                out[y] = new Object[] { gids, light };
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Admission gate for Rust packet authority: verify the native registry
     * state equals the FAITHFUL per-cell vanilla view (func_186016_a x
     * registry lookup) for every non-empty section. The refresh push uses the
     * fast bulk extractor, and the in-refresh validation shares it, so a
     * fast-extractor misread on a modded palette layout passes validation yet
     * diverges from the vanilla packet by a cell (observed live: 48-vs-32 on
     * revisited Revelation chunks). Full-rate, on the calling thread, under
     * the per-chunk refresh lock; returns false (caller falls back to the
     * untouched Java path) on any stable divergence or reflection surprise.
     */
    public static boolean verifyExtractionFaithfulForAdmission(Object chunk) {
        try {
            if (M_GET_STORAGE == null) return false;
            Object[] sections = (Object[]) M_GET_STORAGE.invoke(chunk);
            if (sections == null || sections.length != 16) return false;
            for (int y = 0; y < 16; y++) {
                Object storage = sections[y];
                if (storage == null
                        || (F_EMPTY_STORAGE != null && storage == F_EMPTY_STORAGE.get(null))) {
                    continue;
                }
                Object container = M_CONTAINER.invoke(storage);
                if (container == null) return false;
                int[] fast = fastExtractGlobalIds(container, new int[4096]);
                if (fast == null) fast = null;
                int[] slow = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    Object st = M_GET_STATE.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
                    Integer gid = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                    slow[i] = gid == null ? -1 : gid;
                }
                if (fast != null && !java.util.Arrays.equals(fast, slow)) {
                    // Re-read for stability: a concurrent in-place mutation is
                    // benign (dualVerify precedent); a stable difference is a
                    // real extractor divergence -> refuse this admission.
                    int[] slow2 = new int[4096];
                    for (int j = 0; j < 4096; j++) {
                        Object st2 = M_GET_STATE.invoke(container, j & 15, j >> 8, (j >> 4) & 15);
                        Integer gid2 = (Integer) M_REG_GET_ID.invoke(REG_MAP, st2);
                        slow2[j] = gid2 == null ? -1 : gid2;
                    }
                    if (java.util.Arrays.equals(slow, slow2)) {
                        System.err.println("[RustCraft-SingleCopy] faithful-verify divergence: "
                                + dumpDivergence(findDim(chunk), chunkX(chunk), chunkZ(chunk),
                                        y, firstDiffIndex(fast, slow), container, slow, fast));
                        return false;
                    }
                    return false; // unstable state: refuse anyway, fail closed
                }
                if (fast == null) {
                    continue; // extraction fell back to the faithful path already
                }
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[RustCraft-SingleCopy] faithful-verify error: " + t);
            return false;
        }
    }

    private static int firstDiffIndex(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return i;
        }
        return -1;
    }

    private static int findDim(Object chunk) {
        return ChunkMutationTracker.dimOf(chunk);
    }

    private static int chunkX(Object chunk) {
        long[] k = ChunkMutationTracker.chunkCoords(chunk);
        return k[0] == Long.MIN_VALUE ? -1 : (int) k[0];
    }

    private static int chunkZ(Object chunk) {
        long[] k = ChunkMutationTracker.chunkCoords(chunk);
        return k[0] == Long.MIN_VALUE ? -1 : (int) k[1];
    }

    /** Compare fast vs slow extraction for one section; dump on divergence. */
    static void dualVerify(int dim, int cx, int cz, int y, Object container, Object storage,
                           int[] fastResult, int arm, SyncImpl impl) {
        try {
            AtomicLong runs = arm < 0 ? DUAL_VERIFY_RUNS : BAKE_DV_RUNS[arm];
            if (runs.incrementAndGet() % DUAL_VERIFY_EVERY != 0) return;
            // §8 C2: MethodHandle invocation vehicle — plans unreflected
            // from the SAME runtime-class Methods (identical virtual
            // dispatch + access); null plan falls back to Method.invoke.
            java.lang.invoke.MethodHandle mhGet = null, mhReg = null;
            boolean mh = false;
            if (impl != null && impl.mhVerify) {
                mhGet = CV_MH_GET_STATE.get(container.getClass());
                mhReg = regGetIdPlan();
                mh = mhGet != null && mhReg != null;
            }
            // §5 lease: dv scratch NEVER aliases fastResult (§6 — the two
            // witnesses must be distinct storage); re-entrant acquire is
            // impossible here (no sync call inside dualVerify) but the
            // slot model still guards it.
            SyncScratch sc = (impl != null && impl.leases) ? SCRATCH_TL.get() : null;
            int[] slow = (sc != null) ? leaseDv(sc) : new int[4096];
            try {
                for (int i = 0; i < 4096; i++) {
                    Object st = mh
                            ? mhGet.invoke(container, i & 15, i >> 8, (i >> 4) & 15)
                            : M_GET_STATE.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
                    Integer gid = mh
                            ? (Integer) mhReg.invoke(REG_MAP, st)
                            : (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
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
                            Object st2 = mh
                                    ? mhGet.invoke(container, j & 15, j >> 8, (j >> 4) & 15)
                                    : M_GET_STATE.invoke(container, j & 15, j >> 8, (j >> 4) & 15);
                            Integer gid2 = mh
                                    ? (Integer) mhReg.invoke(REG_MAP, st2)
                                    : (Integer) M_REG_GET_ID.invoke(REG_MAP, st2);
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
            } finally {
                if (sc != null) releaseDv(sc, slow);
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

    /// OPT-SYNC-001: TRUE when the Java section is exactly the native
    /// default (EBS empty, block light all-zero, sky light all-15 —
    /// the NativeSection::new defaults). Skipping refreshSection for
    /// such a section is BIT-EXACT: from_primer created it with those
    /// defaults. Starlight precedent (TECHNICAL_DETAILS): light storage
    /// only exists near non-empty sections — empty sections carry
    /// nothing. Measured: 67-70% of refreshed sections qualify.
    private static final java.util.concurrent.atomic.AtomicLong SYNC_SKIPPED = new java.util.concurrent.atomic.AtomicLong();

    private static boolean isNativeDefaultSection(Object storage, boolean useCache) {
        try {
            // EBS.isEmpty = func_76663_a (symbols-verified; live-maintained
            // by vanilla's incremental set() bookkeeping). C1 resolves it
            // through the ClassValue cache keyed on the RUNTIME class (§7);
            // a cache miss (no such member on that class) keeps the same
            // fail-open-to-refresh behavior as the old NoSuchMethod path.
            java.lang.reflect.Method m;
            if (useCache) {
                m = CV_IS_EMPTY.get(storage.getClass());
                if (m == null) {
                    CV_PRE_MISSES.incrementAndGet();
                    return false;
                }
                CV_PRE_HITS.incrementAndGet();
            } else {
                m = storage.getClass().getMethod("func_76663_a");
                m.setAccessible(true);
            }
            if (!(Boolean) m.invoke(storage)) {
                return false;
            }
            // block light: native default = 0 everywhere
            Object blN = M_GET_BL.invoke(storage);
            if (blN != null) {
                byte[] bl = (byte[]) M_NIBBLE_BYTES.invoke(blN);
                if (bl != null) {
                    for (byte b : bl) {
                        if (b != 0) return false;
                    }
                }
            }
            // sky light: native default = 15 everywhere (0xFF nibble pairs)
            Object slN = M_GET_SL.invoke(storage);
            if (slN != null) {
                byte[] sl = (byte[]) M_NIBBLE_BYTES.invoke(slN);
                if (sl != null) {
                    for (byte b : sl) {
                        if (b != (byte) 0xFF) return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false; // any doubt: refresh (fail-open to the old path)
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
        // FULL-WIDTH: u32 staging; only unresolvable ids (-1) reject
        for (int i = 0; i < 4096; i++) {
            if (g[i] < 0) {
                HIGH_ID_REJECTED.incrementAndGet();
                return -2; // unresolvable state: no partial write, ever
            }
        }
        java.nio.ByteBuffer sBB = java.nio.ByteBuffer.allocateDirect(4096 * 4).order(java.nio.ByteOrder.nativeOrder());
        java.nio.IntBuffer cb = sBB.asIntBuffer();
        for (int i = 0; i < 4096; i++) cb.put(i, g[i]);
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
        // OPT-SYNC-005: op wall starts HERE (entry through publication),
        // so lock acquire + chunk resolution are inside the accounting.
        long[] op = opBegin();
        try {
            long rs0 = System.nanoTime();
            long[] _k = ChunkMutationTracker.chunkCoords(chunk);
            Object _lock = null;
            int _dim = ChunkMutationTracker.DIM_UNKNOWN;
            if (_k[0] != Long.MIN_VALUE) {
                _dim = ChunkMutationTracker.dimOf(chunk);
                _lock = refreshLock(_dim, (int) _k[0], (int) _k[1]);
            }
            // §4-D: op-scoped ctx for the C1/C2 paths (pair mode keeps
            // chunk-level code C0-shaped; only the section body is paired)
            ChunkCtx ctx = (ACTIVE_IMPL.useCtx && PAIR_IMPLS == null
                    && _k[0] != Long.MIN_VALUE)
                    ? new ChunkCtx(_dim, (int) _k[0], (int) _k[1], 0L) : null;
            addPhase(S_RESOLVE, CH_RESOLVE_NS, rs0);
            if (_lock != null) {
                synchronized (_lock) {
                    return refreshChunkNowSerialized(chunk, op, ctx);
                }
            }
            return refreshChunkNowSerialized(chunk, op, ctx);
        } finally {
            opEnd(op);
        }
    }

    private static int refreshChunkNowSerialized(Object chunk, long[] op, ChunkCtx ctx) throws Exception {
        // First-touch full synchronization (M4.2C skyLight finding): registration
        // light is a DEFAULT; the Java light engine sets sky light in sections
        // that never had a block mutation (no dirty bit). Until a chunk has been
        // fully synced once, refresh ALL sections + biomes, not just dirty ones.
        long tk0 = System.nanoTime();
        boolean full = !FULLY_SYNCED.containsKey(chunk);
        addPhase(S_TRACKER, CH_TRACKER_NS, tk0);
        if (full) {
            op[S_FULL] = 1;
            return fullSync(chunk, ctx);
        }
        long tk1 = System.nanoTime();
        int[] work = ChunkMutationTracker.takeWork(chunk);
        addPhase(S_TRACKER, CH_TRACKER_NS, tk1);
        // Biomes are separately tracked from sections: the live registration
        // carries zero biomes, so the current Java array is pushed on every
        // refresh pass (M4.2B biome[0] finding) before any consumer read.
        if (work == null) {
            pushBiomes(chunk, null, ctx); // no section work: still keep biomes current
            return 0;
        }
        pushBiomes(chunk, work, ctx);
        long before = SECTIONS_REFRESHED.get();
        handleChunk(chunk, work, ctx);
        // re-arm the quiescence guard: the refresh just consumed this version's work
        long tk2 = System.nanoTime();
        FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
        addPhase(S_TRACKER, CH_TRACKER_NS, tk2);
        GUARD_REARMED.incrementAndGet();
        return (int) Math.min(Integer.MAX_VALUE, SECTIONS_REFRESHED.get() - before);
    }

    /** First full pull of a chunk: every section (states + light) + biomes,
     *  reusing the handleChunk dirty-loop with a full mask so validation runs
     *  on every synced section. */
    private static int fullSync(Object chunk, ChunkCtx ctxIn) throws Exception {
        boolean useCtx = ctxIn != null && ACTIVE_IMPL.useCtx;
        long rs0 = System.nanoTime();
        int dim = useCtx ? ctxIn.dim : ChunkMutationTracker.dimOf(chunk);
        long[] k = (dim == ChunkMutationTracker.DIM_UNKNOWN)
                ? null : (useCtx ? new long[] { ctxIn.cx, ctxIn.cz }
                : ChunkMutationTracker.chunkCoords(chunk));
        if (!useCtx || dim == ChunkMutationTracker.DIM_UNKNOWN) addPhase(S_RESOLVE, CH_RESOLVE_NS, rs0);
        long[] c = OP_CTX.get();
        if (c != null) {
            c[S_DIM] = dim;
            if (k != null) { c[S_CX] = k[0]; c[S_CZ] = k[1]; }
        }
        if (dim == ChunkMutationTracker.DIM_UNKNOWN) {
            long tk0 = System.nanoTime();
            FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
            addPhase(S_TRACKER, CH_TRACKER_NS, tk0);
            return 0; // unknown dim: native use excluded for this chunk
        }
        if (k[0] == Long.MIN_VALUE) return 0;
        long rs1 = System.nanoTime();
        long gen = NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]);
        addPhase(S_RESOLVE, CH_RESOLVE_NS, rs1);
        if (gen <= 0) {
            long tk0 = System.nanoTime();
            FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
            addPhase(S_TRACKER, CH_TRACKER_NS, tk0);
            return 0; // not registered: nothing to sync
        }
        if (c != null) c[S_GEN] = gen;
        ChunkCtx ctx = new ChunkCtx(dim, (int) k[0], (int) k[1], gen);
        int[] fullWork = { dim, 0xFFFF, 0xFFFF, 0, 0 };
        long before = SECTIONS_REFRESHED.get();
        long fs0 = System.nanoTime();
        handleChunk(chunk, fullWork, ctx);
        pushBiomes(chunk, fullWork, ctx);
        PHASE_CHUNKSYNC_NS.addAndGet(System.nanoTime() - fs0);
        long tk1 = System.nanoTime();
        FULLY_SYNCED.put(chunk, ChunkMutationTracker.versionOf(chunk));
        addPhase(S_TRACKER, CH_TRACKER_NS, tk1);
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

    private static void pushBiomes(Object chunk, int[] work, ChunkCtx ctx) {
        long b0 = System.nanoTime();
        try {
            pushBiomesInner(chunk, work, ctx);
        } finally {
            addPhase(S_BIOMES, BIOMES_WALL_NS, b0);
        }
    }

    private static void pushBiomesInner(Object chunk, int[] work, ChunkCtx ctx) {
        try {
            int dim;
            long[] k;
            if (ctx != null && ACTIVE_IMPL.useCtx) {
                dim = (work != null) ? work[0] : ctx.dim;
                k = new long[] { ctx.cx, ctx.cz };
            } else {
                dim = (work != null) ? work[0] : ChunkMutationTracker.dimOf(chunk);
                k = ChunkMutationTracker.chunkCoords(chunk);
            }
            if (dim == ChunkMutationTracker.DIM_UNKNOWN) return;
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

    private static void handleChunk(Object chunk, int[] work, ChunkCtx ctxIn) throws Exception {
        long rs0 = System.nanoTime();
        boolean useCtx = ctxIn != null && ACTIVE_IMPL.useCtx;
        long[] coords = useCtx ? new long[] { ctxIn.cx, ctxIn.cz }
                : ChunkMutationTracker.chunkCoords(chunk);
        int cx = (int) coords[0], cz = (int) coords[1];
        if (cx == Long.MIN_VALUE) return; // unresolvable coords: leave (no silent coverage claim)
        int dim = work[0];
        long[] c = OP_CTX.get();
        if (c != null) { c[S_CX] = cx; c[S_CZ] = cz; c[S_DIM] = dim; }

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
            pushBiomes(chunk, work, ctxIn);
        }
        int mask = work[1] | work[2];
        if (mask == 0) return;

        long gen = (useCtx && ctxIn.gen > 0) ? ctxIn.gen
                : NativeChunkBridge.findGeneration(dim, cx, cz);
        if (c != null) c[S_GEN] = gen;
        if (gen <= 0) {
            NOT_REGISTERED_SKIPPED.incrementAndGet();
            return;
        }

        if (!initReflection(chunk)) return;

        Object[] storages = (Object[]) M_GET_STORAGE.invoke(chunk);
        // chunk prologue resolved; the early returns above fold into
        // op-unaccounted (FULL_INVALIDATIONS/NOT_REGISTERED_SKIPPED paths)
        addPhase(S_RESOLVE, CH_RESOLVE_NS, rs0);
        for (int y = 0; y < 16; y++) {
            if ((mask & (1 << y)) == 0) continue;
            refreshAndValidateSection(dim, cx, cz, gen, y, storages[y]);
            if (REFRESH_DISABLED) return;
        }
    }

    private static void refreshAndValidateSection(int dim, int cx, int cz, long gen,
                                                  int y, Object storage) throws Exception {
        if (PAIR_IMPLS != null) {
            // §10 paired in-vivo: BOTH impls run on the SAME live section
            // objects (identical inputs by construction), execution order
            // alternating per section index to cancel ordering bias. The
            // double-run is idempotent (identical bytes written twice; the
            // readback validates each arm). Work counters count arm 0 only.
            int seq = PAIR_SEQ.getAndIncrement();
            if ((seq & 1) == 0) {
                runSectionBody(PAIR_IMPLS[0], TS_BAKE0, 0, dim, cx, cz, gen, y, storage);
                runSectionBody(PAIR_IMPLS[1], TS_BAKE1, 1, dim, cx, cz, gen, y, storage);
            } else {
                runSectionBody(PAIR_IMPLS[1], TS_BAKE1, 1, dim, cx, cz, gen, y, storage);
                runSectionBody(PAIR_IMPLS[0], TS_BAKE0, 0, dim, cx, cz, gen, y, storage);
            }
            return;
        }
        long secT0 = System.nanoTime();
        try {
            runSectionBody(ACTIVE_IMPL, TS_NORMAL, -1, dim, cx, cz, gen, y, storage);
        } finally {
            addPhase(S_SEC_TOTAL, SEC_TOTAL_NS, secT0);
        }
    }

    /** add to a per-arm phase field; normal mode (arm<0) also feeds the
     * op-trace context so [sync-op-trace] keeps working. */
    private static void addP(AtomicLong field, int slot, long fromNanos, boolean ctxToo) {
        long d = System.nanoTime() - fromNanos;
        field.addAndGet(d);
        if (ctxToo) {
            long[] c = OP_CTX.get();
            if (c != null) c[slot] += d;
        }
    }

    private static void runSectionBody(SyncImpl impl, PhaseSet T, int arm, int dim, int cx, int cz,
                                       long gen, int y, Object storage) throws Exception {
        final boolean ctxToo = arm < 0; // op-trace ctx only in normal mode
        final boolean countWork = arm <= 0;
        long a0 = T.alloc != null ? allocNow() : 0L;
        long tStart = System.nanoTime();
        boolean heldGid = false, heldBl = false, heldSl = false;
        SyncScratch s = impl.leases ? SCRATCH_TL.get() : null;
        int[] javaGid = null;
        byte[] javaBl = null, javaSl = null;
        try {
        long al0 = System.nanoTime();
        ByteBuffer statesBB = STATES_TL.get();
        java.nio.IntBuffer sc;
        if (impl.leases) {
            if (s.statesView == null) s.statesView = statesBB.asIntBuffer();
            sc = s.statesView; // absolute puts only: no position state
        } else {
            sc = statesBB.asIntBuffer();
        }
        ByteBuffer lightBB = LIGHT_TL.get();

        if (impl.leases) {
            javaGid = leaseGid(s); heldGid = true;
            javaBl = leaseBl(s); heldBl = true;   // zeroed on lease (null-nibble contract)
            javaSl = leaseSl(s); heldSl = true;
        } else {
            javaGid = new int[4096];
            javaBl = new byte[2048];
            javaSl = new byte[2048];
        }
        addP(T.secAlloc, S_SEC_ALLOC, al0, ctxToo);
        // OPT-SYNC-002 packed representation (method scope: built in the
        // extraction phase, consumed by the transfer phase below)
        java.nio.ByteBuffer packedWords = null;
        int packedBits = -1;
        java.nio.IntBuffer packedPal = null;

        boolean hasStorage = storage != null
                && (F_EMPTY_STORAGE == null || storage != F_EMPTY_STORAGE.get(null));
        if (hasStorage) {
            // OPT-SYNC-005: PRECHECK times the REAL scan. C1 uses the
            // ClassValue-cached Method (§7: keyed on runtime class; entries
            // die with the class/loader).
            long pc1 = System.nanoTime();
            boolean nativeDefault = isNativeDefaultSection(storage, impl.cachedPre);
            addP(T.pre, S_PRE, pc1, ctxToo);
            if (nativeDefault) {
                // OPT-SYNC-001: section IS the native default — the 20 KiB
                // staging + JNI refresh would write what from_primer already
                // put there. Skip; validation counters still cover refreshed
                // sections (skipped sections are bit-equal by construction).
                if (countWork) SYNC_SKIPPED.incrementAndGet();
                return;
            }
        }
        if (hasStorage) {
            long ph0 = System.nanoTime();
            // OPT-SYNC-002 merged pass: ONE extraction — build the packed
            // representation (words + palette table, GID_CACHE hits) and
            // decode javaGid from it (plain int math; the old path ran
            // the reflective per-entry registry lookup + a SECOND
            // extraction for validation — 441ms/run measured)
            Object container = M_CONTAINER.invoke(storage);
            try {
                if (F_CONTAINER_ARR == null) initFastExtract(container);
                Object bitArray = F_CONTAINER_ARR.get(container);
                int bits = F_CONTAINER_BITS.getInt(container);
                long[] words = (long[]) M_WORDS.invoke(bitArray);
                Object palette = F_CONTAINER_PAL.get(container);
                if (words != null && bits >= 4 && bits <= 16) {
                    int needed = (4096 * bits + 63) / 64;
                    if (words.length >= needed) {
                        java.nio.ByteBuffer wb = PACKED_WORDS_TL.get();
                        if (wb.capacity() < needed * 8) {
                            wb = java.nio.ByteBuffer
                                    .allocateDirect(needed * 8)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        }
                        wb.clear();
                        wb.limit(needed * 8);
                        for (int wi2 = 0; wi2 < needed; wi2++) {
                            wb.putLong(words[wi2]);
                        }
                        wb.clear();
                        packedWords = wb;
                        packedBits = bits;
                        int[] table = null;
                        if (bits <= 8) {
                            int tableLen = 1 << bits;
                            table = new int[tableLen];
                            for (int i = 0; i < tableLen; i++) {
                                Object st = M_PAL_BYID.invoke(palette, i);
                                Integer g = (st == null) ? null : GID_CACHE.get(st);
                                if (g == null && st != null) {
                                    g = (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                                    if (g != null) GID_CACHE.put(st, g);
                                }
                                table[i] = g == null ? 0 : g;
                            }
                            java.nio.ByteBuffer pbb = PACKED_PAL_BACKING_TL.get();
                            if (pbb.capacity() < tableLen * 4) {
                                pbb = java.nio.ByteBuffer
                                        .allocateDirect(tableLen * 4)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                                PACKED_PAL_BACKING_TL.set(pbb);
                            }
                            pbb.clear();
                            pbb.limit(tableLen * 4);
                            java.nio.IntBuffer pb = pbb.asIntBuffer();
                            pb.put(table, 0, tableLen);
                            pb.rewind();
                            packedPal = pb;
                        }
                        // decode javaGid from packed (validation ground truth)
                        for (int idx = 0; idx < 4096; idx++) {
                            int bitPos = idx * bits, w0 = bitPos >>> 6,
                                    off = bitPos & 63, fit = 64 - off;
                            int entry;
                            if (fit >= bits) {
                                entry = (int) ((words[w0] >>> off)
                                        & ((1L << bits) - 1));
                            } else {
                                int rest = bits - fit;
                                long lo = (words[w0] >>> off) & ((1L << fit) - 1);
                                long hi = words[w0 + 1] & ((1L << rest) - 1);
                                entry = (int) (lo | (hi << fit));
                            }
                            javaGid[idx] = (table != null)
                                    ? ((entry >= 0 && entry < table.length)
                                        ? table[entry] : 0)
                                    : entry; // registry palette
                        }
                    }
                }
            } catch (Throwable t) {
                packedWords = null; // fall back to the expanded path below
            }
            if (packedWords == null) {
                int[] fast = FAST_EXTRACTOR_ENABLED
                        ? fastExtractGlobalIds(container, javaGid) : null;
                if (fast != null && dim != Integer.MIN_VALUE) {
                    long dv0 = System.nanoTime();
                    dualVerify(dim, cx, cz, y, container, storage, javaGid, arm, impl);
                    addP(T.dv, S_DV, dv0, ctxToo);
                }
                if (fast == null) {
                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15, z = (i >> 4) & 15, subY = i >> 8;
                        Object st;
                        try {
                            st = M_GET_STATE.invoke(container, x, subY, z);
                        } catch (Throwable t) {
                            st = null;
                        }
                        Integer gid2 = (st == null) ? null
                                : (Integer) M_REG_GET_ID.invoke(REG_MAP, st);
                        javaGid[i] = (gid2 == null) ? -1 : gid2;
                    }
                }
            }
            if (countWork) {
                DV_DECISIONS.incrementAndGet();
                if (DV_DECISIONS.get() == 1) {
                    System.out.println("[dv-probe] first packed-gate: dim=" + dim
                            + " hasStorage=" + hasStorage
                            + " packed=" + (packedWords != null));
                }
            }
            if (packedWords != null && dim != Integer.MIN_VALUE) {
                // §5 independence chain: per-cell container.get ground
                // truth validates the PACKED decode (this call); the
                // readback validates native vs the packed decode. Without
                // this, both comparison sides share the packed decoder.
                long dv0 = System.nanoTime();
                dualVerify(dim, cx, cz, y, container, storage, javaGid, arm, impl);
                addP(T.dv, S_DV, dv0, ctxToo);
            }
            Object blArr = M_GET_BL.invoke(storage);
            if (blArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(blArr), 0, javaBl, 0, 2048);
            Object slArr = M_GET_SL.invoke(storage);
            if (slArr != null) System.arraycopy(M_NIBBLE_BYTES.invoke(slArr), 0, javaSl, 0, 2048);
            addP(T.extract, S_EXTRACT, ph0, ctxToo);
        }
        // OPT-SYNC-001: ABSENT path — bit-equal native memsets replace
        // the staged 20 KiB zero refresh (states stay 0, block light 0,
        // SKY 0: the absent-section semantics this sync always wrote)
        if (!hasStorage) {
            long ab0 = System.nanoTime();
            NativeChunkBridge.markSectionAbsent(dim, cx, cz, (byte) y);
            addP(T.absent, S_ABSENT, ab0, ctxToo);
            if (countWork) SYNC_SKIPPED.incrementAndGet();
            return;
        }
        // else: section emptied/absent in Java -> refresh to all-air (states stay 0)

        // FULL-WIDTH (u16 ceiling removed): negative-only rejection. The
        // historical M5.8-R2 >0xFFFF reject froze every Revelation section
        // containing modded ids; staging is now u32 and ids flow verbatim.
        for (int i = 0; i < 4096; i++) {
            if (javaGid[i] < 0) {
                HIGH_ID_REJECTED.incrementAndGet(); // unresolvable state id
                NativeChunkBridge.invalidate(dim, cx, cz); // never leave a falsely-current snapshot
                return;
            }
        }
        // OPT-SYNC-002: transfer — packed words + palette table cross ONE
        // JNI when the merged extraction produced them; Rust expands (the
        // §48 bake-off proved native u32 expansion µs-class). Fallback:
        // expanded staging + refreshSection.
        long ph1 = System.nanoTime();
        int remaining;
        if (packedWords != null) {
            lightBB.clear();
            lightBB.put(javaBl).put(javaSl);
            lightBB.clear();
            addP(T.stage, S_STAGE, ph1, ctxToo);
            long ph2 = System.nanoTime();
            // palette table: address the BACKING ByteBuffer (an
            // IntBuffer.rewind() returns Buffer — casting it threw CCE
            // and aborted fullSync per chunk in dv3)
            long palAddr = 0;
            int palCount = 0;
            if (packedPal != null) {
                java.nio.ByteBuffer palBacking = PACKED_PAL_BACKING_TL.get();
                palCount = packedPal.capacity();
                palAddr = address(palBacking);
            }
            remaining = NativeChunkBridge.refreshSectionPacked(
                    dim, cx, cz, (byte) y, packedBits,
                    address(packedWords), packedWords.capacity() / 8,
                    palAddr, palCount, address(lightBB), 4096);
            addP(T.jni, S_JNI, ph2, ctxToo);
        } else {
            for (int i = 0; i < 4096; i++) sc.put(i, javaGid[i]);
            lightBB.clear();
            lightBB.put(javaBl).put(javaSl);
            lightBB.clear();
            addP(T.stage, S_STAGE, ph1, ctxToo);
            long ph2 = System.nanoTime();
            remaining = NativeChunkBridge.refreshSection(dim, cx, cz, (byte) y,
                    address(statesBB), address(lightBB), address(lightBB) + 2048);
            addP(T.jni, S_JNI, ph2, ctxToo);
        }
        if (remaining < 0) {
            SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
            disableOnMismatch("refreshSection returned " + remaining
                    + " dim=" + dim + " cx=" + cx + " cz=" + cz + " y=" + y);
            return;
        }
        if (countWork) SECTIONS_REFRESHED.incrementAndGet();

        long ph3 = System.nanoTime();
        try {
        // OPT-SYNC-002: INDEPENDENT validation via native read-back — one
        // JNI copies the exact installed u32 states + both light arrays
        // out; Java compares cell-for-cell. Same independence as the old
        // whole-chunk encodePacket+parse (it read the published section
        // back through the packet path) at O(section) instead of
        // O(chunk) — the old path re-encoded EVERY section of the chunk
        // per refreshed section (O(sections^2) per chunk).
        // OPT-SYNC-006 C1: the readback buffer is LEASED (§5): direct
        // 20480 LE, position reset on lease; native writes exactly 20480
        // bytes on success (rbr==0) — the fixed-offset compare below is
        // the full written region. §6: this buffer is the NATIVE witness;
        // it never aliases javaGid (the packed-decode witness) or the
        // dualVerify scratch (the per-cell witness).
        java.nio.ByteBuffer rb;
        boolean heldRb = false;
        if (impl.leases) { rb = leaseReadback(s); heldRb = true; }
        else rb = java.nio.ByteBuffer.allocateDirect(20480)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        try {
        int rbr = NativeChunkBridge.readbackSection(
                dim, cx, cz, (byte) y, address(rb), rb.capacity());
        if (rbr == -3) {
            // native absent: acceptable ONLY when Java cells are all-air
            // (the OPT-SYNC-001 absent path owns this shape)
            for (int g : javaGid) {
                if (g != 0) {
                    SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
                    disableOnMismatch("native absent but Java non-air dim="
                            + dim + " cx=" + cx + " cz=" + cz + " y=" + y);
                    return;
                }
            }
        } else if (rbr < 0) {
            SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
            disableOnMismatch("readback rc=" + rbr + " dim=" + dim
                    + " cx=" + cx + " cz=" + cz + " y=" + y);
            return;
        } else {
            for (int i = 0; i < 4096; i++) {
                if (rb.getInt(i * 4) != javaGid[i]) {
                    SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
                    disableOnMismatch("state[" + i + "] dim=" + dim
                            + " cx=" + cx + " cz=" + cz + " y=" + y);
                    return;
                }
            }
            for (int i = 0; i < 2048; i++) {
                int blB = rb.get(16384 + i) & 0xFF;
                int actualBl0 = blB & 0x0F, actualBl1 = blB >> 4;
                int wantBl0 = javaBl[i] & 0x0F, wantBl1 = (javaBl[i] & 0xFF) >> 4;
                if (actualBl0 != wantBl0 || actualBl1 != wantBl1) {
                    SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
                    disableOnMismatch("blockLight[" + i + "]");
                    return;
                }
                int skB = rb.get(18432 + i) & 0xFF;
                int actualSk0 = skB & 0x0F, actualSk1 = skB >> 4;
                int wantSk0 = javaSl[i] & 0x0F, wantSk1 = (javaSl[i] & 0xFF) >> 4;
                if (actualSk0 != wantSk0 || actualSk1 != wantSk1) {
                    SECTIONS_VALIDATION_MISMATCH.incrementAndGet();
                    disableOnMismatch("skyLight[" + i + "]");
                    return;
                }
            }
        }
        if (countWork) SECTIONS_VALIDATED.incrementAndGet();
        if (T.sections != null) T.sections.incrementAndGet();
        } finally {
            if (heldRb) releaseReadback(s, rb);
        }
        } finally {
            addP(T.validate, S_VALIDATE, ph3, ctxToo);
        }
        } finally {
            if (heldGid) releaseGid(s, javaGid);
            if (heldBl) releaseBl(s, javaBl);
            if (heldSl) releaseSl(s, javaSl);
            long d = System.nanoTime() - tStart;
            T.secTotal.addAndGet(d);
            if (ctxToo) {
                long[] c = OP_CTX.get();
                if (c != null) c[S_SEC_TOTAL] += d;
            }
            if (T.alloc != null) {
                long da = allocNow() - a0;
                if (da > 0) T.alloc.addAndGet(da);
            }
        }
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
        // Resolve Minecraft classes through the CHUNK's own defining loader.
        // This bridge loads on the launch classpath, whose loader cannot see
        // the SRG-named runtime classes (they live on the child transforming
        // loader); literal `Chunk.class` references and caller-loader
        // Class.forName silently broke the whole sync path on a real server
        // while it kept working in the offline harness (SRG jar on -cp).
        ClassLoader runtimeLoader = chunk.getClass().getClassLoader();
        Class<?> chunkCls = chunk.getClass();
        M_GET_STORAGE = chunkCls.getMethod("func_76587_i");
        M_GET_STORAGE.setAccessible(true);
        Class<?> chunkType = Class.forName("net.minecraft.world.chunk.Chunk", true, runtimeLoader);
        F_EMPTY_STORAGE = chunkType.getField("field_186036_a");
        F_EMPTY_STORAGE.setAccessible(true);

        Field regF = Class.forName("net.minecraft.block.Block", true, runtimeLoader)
                .getDeclaredField("field_176229_d");
        regF.setAccessible(true);
        REG_MAP = regF.get(null);
        M_REG_GET_ID = REG_MAP.getClass().getMethod("func_148747_b", Object.class);
        M_REG_GET_ID.setAccessible(true);

        Class<?> storageCls = Class.forName("net.minecraft.world.chunk.storage.ExtendedBlockStorage", true, runtimeLoader);
        M_CONTAINER = storageCls.getMethod("func_186049_g");
        M_CONTAINER.setAccessible(true);
        M_GET_BL = storageCls.getMethod("func_76661_k");
        M_GET_BL.setAccessible(true);
        M_GET_SL = storageCls.getMethod("func_76671_l");
        M_GET_SL.setAccessible(true);

        Class<?> contCls = Class.forName("net.minecraft.world.chunk.BlockStateContainer", true, runtimeLoader);
        M_GET_STATE = contCls.getMethod("func_186016_a", int.class, int.class, int.class);
        M_GET_STATE.setAccessible(true);

        Class<?> nibCls = Class.forName("net.minecraft.world.chunk.NibbleArray", true, runtimeLoader);
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
