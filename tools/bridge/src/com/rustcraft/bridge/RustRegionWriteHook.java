package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * LIVE region-write authority hook (RUST_REGION_WRITE_AUTHORITY).
 *
 * Called from bytecode the RegionFileAuthorityTransformer injects into
 * net.minecraft.world.chunk.storage.RegionFile:
 *
 * <ul>
 *   <li>{@code func_76706_a(II[BI)V} entry: {@link #regionWriteEntry} —
 *       returns true when Rust committed the write and the vanilla body must
 *       be skipped (RETURN injected immediately after the call);</li>
 *   <li>{@code func_76706_a} before EVERY vanilla return:
 *       {@link #regionWriteExit} — vanilla completed the write (fallback or
 *       declined), so the engine's map must resync;</li>
 *   <li>{@code func_76708_c()} (close) entry: {@link #regionFileClosing} —
 *       frees the native engine handle.</li>
 * </ul>
 *
 * CONTRACT (fail-open): no method here may ever throw into the injected call
 * site; every failure path returns false / does nothing and the vanilla body
 * runs untouched. Default OFF: the transformer injects nothing unless
 * -Drustcraft.regionWriteExperiment=true, and the hook double-checks the flag
 * at runtime.
 *
 * GENERATION TICKETS: Java's per-state AtomicLongArray is the single source
 * of truth. Tickets start at the engine's committed floors (so a second
 * RegionFile instance attached to a live engine never re-issues spent
 * tickets) and advance on every Rust admission AND every vanilla fallback;
 * Rust stores exactly the ticket it is told.
 *
 * MODES (-Drustcraft.regionWriteMode):
 *   SHADOW          — Rust writes a mirror copy under
 *                     -Drustcraft.regionWriteMirror; the real file is always
 *                     written by vanilla. Offline comparison afterwards.
 *   ON_EXPERIMENTAL — Rust writes the REAL region file and the vanilla body
 *                     is skipped; the returned entry is mirrored into
 *                     RegionFile's in-memory offset/timestamp arrays so
 *                     in-session reads stay coherent. NOT production
 *                     authority (PRODUCTION_AUTHORITY=false).
 */
public final class RustRegionWriteHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.regionWriteExperiment");
    public static final String MODE =
            System.getProperty("rustcraft.regionWriteMode", "SHADOW");
    public static final boolean SHADOW = !"ON_EXPERIMENTAL".equals(MODE);
    public static final int CAP =
            Integer.getInteger("rustcraft.regionWriteCap", Integer.MAX_VALUE);
    /** Stress only: decline every Nth Rust admission (vanilla body runs and
     *  the exit note interleaves fallbacks with admissions). Default 0 = off. */
    public static final int FAIL_EVERY =
            Integer.getInteger("rustcraft.regionWriteFailEvery", 0);
    public static final AtomicLong SIMULATED_FAILURES = new AtomicLong();
    /** Failure breakdown by negative status code (index = -code, 0 unused). */
    public static final AtomicLong[] RUST_ERR_BY_CODE = new AtomicLong[8];
    static {
        for (int i = 0; i < RUST_ERR_BY_CODE.length; i++) {
            RUST_ERR_BY_CODE[i] = new AtomicLong();
        }
    }
    static final String MIRROR_ROOT = System.getProperty("rustcraft.regionWriteMirror", "");

    // lifecycle/counters (m1-metrics style)
    public static final AtomicLong ENTRY_CALLS = new AtomicLong();
    public static final AtomicLong RUST_ADMITTED = new AtomicLong();
    public static final AtomicLong RUST_OK = new AtomicLong();
    public static final AtomicLong RUST_FAILED = new AtomicLong();
    public static final AtomicLong VANILLA_FALLBACKS = new AtomicLong();
    public static final AtomicLong EXIT_NOTES = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static volatile long TICKET_SEED_MAX = 0;

    /** Per-RegionFile state stored in the transformer-injected public field. */
    public static final class State {
        public final RegionWriteCtx ctx = new RegionWriteCtx();
        public final AtomicLongArray tickets;
        /**
         * Grow-only direct scratch for the deflate payload. Thread-confined
         * BY DESIGN: func_76706_a is synchronized on the RegionFile, so all
         * writers of one state are serialized; independent regions never
         * share a state.
         */
        public ByteBuffer scratch;
        /** true once the process-level admission cap is exhausted. */
        public volatile boolean capped;
        State(long[] floors) {
            long[] seed = floors != null ? floors : new long[1024];
            tickets = new AtomicLongArray(seed);
            long max = 0;
            for (long f : seed) max = Math.max(max, f);
            TICKET_SEED_MAX = Math.max(TICKET_SEED_MAX, max);
        }
        /** Copies the heap payload into the direct scratch; returns it or
         *  null on allocation failure (fail-open to vanilla). */
        ByteBuffer stage(byte[] data, int len) {
            ByteBuffer buf = scratch;
            if (buf == null || buf.capacity() < len) {
                scratch = buf = ByteBuffer.allocateDirect(Math.max(4096, len));
            }
            buf.clear();
            if (len > 0) buf.put(data, 0, len);
            buf.flip();
            return buf;
        }
    }

    private RustRegionWriteHook() { }

    // ------------------------------------------------------------------
    // injected call sites
    // ------------------------------------------------------------------

    /**
     * func_76706_a entry. Returns true when the caller must RETURN
     * immediately (Rust committed the write); false to run the vanilla body.
     * Lazily creates and stores the per-instance state in the
     * transformer-injected {@code rustcraft$rwState} field (the seam is
     * synchronized, so creation is race-free).
     */
    public static boolean regionWriteEntry(Object regionFile, int x, int z, byte[] data, int len) {
        if (!ENABLED) return false;
        try {
            State st = (State) state(regionFile);
            if (st == null) return false;
            ENTRY_CALLS.incrementAndGet();
            if (st.capped || RUST_ADMITTED.get() >= CAP) {
                st.capped = true;
                return false;
            }
            int index = (z & 31) * 32 + (x & 31);
            long ticket = st.tickets.incrementAndGet(index);
            ByteBuffer payload = st.stage(data, len);
            if (payload == null) {
                RUST_FAILED.incrementAndGet();
                return false;
            }
            if (!SHADOW && FAIL_EVERY > 0
                    && (RUST_ADMITTED.get() + 1) % FAIL_EVERY == 0) {
                // stress: decline so the vanilla body runs and the exit note
                // interleaves a real fallback between Rust admissions
                SIMULATED_FAILURES.incrementAndGet();
                RUST_FAILED.incrementAndGet();
                RUST_ADMITTED.incrementAndGet();
                return false;
            }
            if (SHADOW) {
                // Rust commits to the mirror file; the real file is always
                // written by the vanilla body below.
                long entry = st.ctx.write(x & 31, z & 31, payload, len, ticket);
                RUST_ADMITTED.incrementAndGet();
                if (entry > 0) {
                    RUST_OK.incrementAndGet();
                    return false; // vanilla body still writes the real file
                }
                tallyFailure(entry);
                RUST_FAILED.incrementAndGet();
                return false;
            }
            // ON_EXPERIMENTAL: Rust writes the REAL file; success skips vanilla.
            long entry = st.ctx.write(x & 31, z & 31, payload, len, ticket);
            RUST_ADMITTED.incrementAndGet();
            if (entry > 0) {
                RUST_OK.incrementAndGet();
                mirrorEntry(regionFile, index, (int) entry);
                mirrorFreeList(st.ctx, regionFile);
                return true;
            }
            tallyFailure(entry);
            RUST_FAILED.incrementAndGet();
            return false; // any failure -> vanilla body runs
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return false;
        }
    }

    /**
     * func_76706_a before a vanilla return: the vanilla body completed its
     * own write. SHADOW: nothing to do (mirror map tracks only its own
     * writes). ON_EXPERIMENTAL: resync the engine so its sector map stays
     * coherent with the real file.
     */
    public static void regionWriteExit(Object regionFile, int x, int z) {
        if (!ENABLED) return;
        try {
            State st = (State) state(regionFile);
            if (st == null || st.capped || SHADOW) return;
            int index = (z & 31) * 32 + (x & 31);
            int[] offsets = offsets(regionFile);
            if (offsets == null) return;
            int entry = offsets[index];
            if (entry == 0) return;
            long ticket = st.tickets.incrementAndGet(index);
            int rc = st.ctx.noteExternal(x & 31, z & 31, entry, ticket);
            if (rc == RegionWriteCtx.ST_SUCCESS) {
                EXIT_NOTES.incrementAndGet();
                // the engine resynced from disk: publish its view back into
                // Java so the next fallback allocation sees the real occupancy
                mirrorFreeList(st.ctx, regionFile);
            } else {
                VANILLA_FALLBACKS.incrementAndGet();
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    /** func_76708_c (close) entry: free the native engine handle exactly once. */
    public static void regionFileClosing(Object regionFile) {
        if (!ENABLED) return;
        try {
            Object st = peekState(regionFile);
            if (st != null) ((State) st).ctx.free();
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // per-instance state (stored in the injected rustcraft$rwState field)
    // ------------------------------------------------------------------

    private static volatile Field F_STATE;

    private static Field stateField(Class<?> c) {
        Field f = F_STATE;
        if (f == null) {
            try {
                f = c.getDeclaredField("rustcraft$rwState");
                f.setAccessible(true);
                F_STATE = f;
            } catch (Throwable t) {
                return null; // transformer did not inject the field
            }
        }
        return f;
    }

    /** Read-only: never creates state (used by the close hook). */
    private static Object peekState(Object regionFile) throws IllegalAccessException {
        Field f = stateField(regionFile.getClass());
        return f == null ? null : f.get(regionFile);
    }

    private static Object state(Object regionFile) throws IllegalAccessException {
        Field f = stateField(regionFile.getClass());
        if (f == null) return null;
        Object st = f.get(regionFile);
        if (st == null) {
            st = makeState(regionFile);
            if (st != null) f.set(regionFile, st);
        }
        return st;
    }

    /**
     * Creates the per-RegionFile state. Returns null on ANY failure — the
     * caller leaves the field null and every later call fail-opens.
     */
    static State makeState(Object regionFile) {
        if (!ENABLED) return null;
        try {
            java.io.File f = regionFilePath(regionFile);
            if (f == null) return null;
            String path = f.getAbsolutePath();
            if (SHADOW) {
                if (MIRROR_ROOT.length() == 0) return null;
                path = mirrorPath(path);
                java.io.File pf = new java.io.File(path).getParentFile();
                if (pf != null) pf.mkdirs();
            }
            State st = new State(null);
            if (!st.ctx.ensureCreated(path)) return null;
            long[] floors = st.ctx.floors();
            if (floors != null) {
                for (int i = 0; i < floors.length; i++) {
                    st.tickets.set(i, floors[i]);
                }
            }
            return st;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // reflection (cached; SRG names with dev-name fallbacks)
    // ------------------------------------------------------------------

    private static volatile Field F_FILE, F_OFFSETS, F_TIMESTAMPS;

    private static Field findField(Class<?> c, String[] names, String type) {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                if (f.getType().getName().equals(type)) return f;
            } catch (Throwable ignore) { }
        }
        return null;
    }

    static java.io.File regionFilePath(Object regionFile) {
        try {
            Field f = F_FILE;
            if (f == null) {
                f = findField(regionFile.getClass(),
                        new String[]{"field_76718_b", "fileName"}, "java.io.File");
                if (f == null) return null;
                F_FILE = f;
            }
            return (java.io.File) f.get(regionFile);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] offsets(Object regionFile) {
        try {
            Field f = F_OFFSETS;
            if (f == null) {
                f = findField(regionFile.getClass(),
                        new String[]{"field_76716_d", "offsets"}, "[I");
                if (f == null) return null;
                F_OFFSETS = f;
            }
            return (int[]) f.get(regionFile);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mirror a Rust-committed entry into RegionFile's in-memory arrays so
     *  in-session reads (func_76704_a -> func_76705_d) see the new data. */
    private static void mirrorEntry(Object regionFile, int index, int entry) {
        try {
            int[] offsets = offsets(regionFile);
            if (offsets != null && index < offsets.length) {
                offsets[index] = entry;
            }
            Field f = F_TIMESTAMPS;
            if (f == null) {
                f = findField(regionFile.getClass(),
                        new String[]{"field_76717_e", "timestamps"}, "[I");
                if (f != null) F_TIMESTAMPS = f;
            }
            if (f != null) {
                int[] ts = (int[]) f.get(regionFile);
                if (ts != null && index < ts.length) {
                    ts[index] = (int) (System.currentTimeMillis() / 1000L);
                }
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    private static volatile Field F_FREELIST;

    /**
     * Rebuild RegionFile's in-memory sector free list (field_76714_f, one
     * Boolean per sector, TRUE = free) from the engine's used-map. Vanilla's
     * READ path returns null when offset+count exceeds the list's size(), and
     * vanilla's own fallback allocator allocates from it — a Rust write that
     * grew the file or occupied sectors MUST be mirrored here or in-session
     * reads break and fallback writes clobber Rust records.
     */
    private static void mirrorFreeList(RegionWriteCtx ctx, Object regionFile) {
        try {
            byte[] used = ctx.usedMap();
            if (used == null || used.length == 0) return;
            Field f = F_FREELIST;
            if (f == null) {
                f = findField(regionFile.getClass(),
                        new String[]{"field_76714_f", "sectorFree"}, "java.util.List");
                if (f == null) return;
                F_FREELIST = f;
            }
            java.util.List<Boolean> free = new java.util.ArrayList<Boolean>(used.length);
            for (int s = 0; s < used.length; s++) {
                free.add(used[s] != 0 ? Boolean.FALSE : Boolean.TRUE);
            }
            f.set(regionFile, free);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    /**
     * Collision-free deterministic mirror path: the sanitized absolute path
     * under the mirror root (drive colon + separators folded to '_').
     */
    public static String mirrorPath(String absolutePath) {
        String safe = absolutePath.replace(':', '_').replace('\\', '_').replace('/', '_');
        return MIRROR_ROOT + (MIRROR_ROOT.endsWith("\\") || MIRROR_ROOT.endsWith("/")
                ? safe : java.io.File.separator + safe);
    }

    private static void tallyFailure(long entry) {
        int code = (int) Math.max(1, Math.min(7, -entry));
        RUST_ERR_BY_CODE[code].incrementAndGet();
    }

    public static String dumpMetrics() {
        StringBuilder errs = new StringBuilder();
        for (int i = 1; i < RUST_ERR_BY_CODE.length; i++) {
            long n = RUST_ERR_BY_CODE[i].get();
            if (n != 0) errs.append(" err").append(i).append('=').append(n);
        }
        return "regionWrite.hook enabled=" + ENABLED + " mode=" + MODE + " cap=" + CAP
                + " entryCalls=" + ENTRY_CALLS.get()
                + " rustAdmitted=" + RUST_ADMITTED.get()
                + " rustOk=" + RUST_OK.get()
                + " rustFailed=" + RUST_FAILED.get()
                + " vanillaFallbacks=" + VANILLA_FALLBACKS.get()
                + " exitNotes=" + EXIT_NOTES.get()
                + " errors=" + ERRORS.get()
                + " ticketSeedMax=" + TICKET_SEED_MAX
                + errs;
    }
}
