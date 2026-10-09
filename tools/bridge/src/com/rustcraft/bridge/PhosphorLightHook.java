package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LIVE block-light authority hook over Phosphor's LightingEngine
 * (RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY).
 *
 * Called from bytecode injected into
 * me.jellysquid.mods.phosphor.mod.world.lighting.LightingEngine
 * .processLightUpdatesForType(net.minecraft.world.EnumSkyBlock):
 *
 * <ul>
 *   <li>SHADOW — {@link #jobStart} snapshots the scheduled positions and the
 *       pre-drain block-light grid over the affected bounds (radius 15 per
 *       scheduled position, clipped to 0..255); the injected code then lets
 *       Phosphor drain (authoritative); {@link #jobEnd} replays the same job
 *       through the Rust frontier from the pre-state and compares final
 *       block-light cell-exact over the bounds.</li>
 *   <li>ON_EXPERIMENTAL — {@link #jobStart} returns true when the job is
 *       admitted: the injected code RETURNs (Phosphor's block drain is
 *       skipped), Rust owns propagation, and {@link #jobEnd} commits the
 *       changed cells into the world (world.setLightFor → NativeSection
 *       nibbles). SKY drains are untouched. Fail-closed: any Rust failure
 *       before commit falls back to Phosphor.</li>
 * </ul>
 *
 * Phosphor remains the compatibility oracle: opacity/emission come from the
 * live World (IBlockState.getBlockOpacity / getLightValue) per bounds cell,
 * which is Java semantics even though the per-cell reads are Java→Java (the
 * JNI boundary is crossed ONCE per job).
 */
public final class PhosphorLightHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.lightExperiment");
    public static final String MODE =
            System.getProperty("rustcraft.lightMode", "SHADOW");
    public static final boolean SHADOW = !"ON_EXPERIMENTAL".equals(MODE);
    public static final int RADIUS = 15;

    public static final AtomicLong JOBS = new AtomicLong();
    public static final AtomicLong ADMITTED = new AtomicLong();
    public static final AtomicLong FALLBACKS = new AtomicLong();
    public static final AtomicLong COMPARED = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();
    public static final AtomicLong COMMITTED_CELLS = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static final AtomicLong SKIPPED_BOUNDS = new AtomicLong();
    public static final AtomicLong DEPTH_LEAKS_HEALED = new AtomicLong();
    public static final AtomicLong DRAIN_TICKED = new AtomicLong();
    public static final AtomicLong SCHEDULED = new AtomicLong();
    public static final AtomicLong SKIPPED_CASCADE = new AtomicLong();

    /** §21 comparison window: opened by the campaign runner (flag file,
     *  polled by the tweaker's periodic thread) right before mutation
     *  traffic starts. Jobs outside the window are worldgen cascades whose
     *  border conditions make any bounded replay incomparable — they are
     *  counted (SKIPPED_CASCADE), never compared. */
    public static volatile boolean COMPARE_WINDOW = false;
    /** Mutation anchor (x,y,z) from the window flag: admitted jobs must
     *  have every scheduled position within RADIUS of it — late worldgen
     *  cascades elsewhere stay counted-but-uncompared even inside the
     *  window (dev16 compared a cascade job at the world origin). */
    public static volatile int[] COMPARE_ANCHOR = null;

    /** Injected at scheduleLightUpdate(EnumSkyBlock, BlockPos) HEAD. */
    public static void scheduled() {
        SCHEDULED.incrementAndGet();
    }

    /** Injected at processLightUpdates() HEAD: counts drain ticks
     *  independent of the per-type seam. */
    public static void drainTicked() {
        DRAIN_TICKED.incrementAndGet();
    }
    public static volatile String LAST_MISMATCH = "";

    /** Shadow replay cost cap: jobs whose affected bounds exceed this many
     *  cells are skipped (counted, never compared) — boot/worldgen drains
     *  fan out over huge bounds; gameplay mutations stay tiny. */
    static final int MAX_SHADOW_CELLS = 1 << 16;

    /** Per-job state (server thread — the engine's ownedThread — so a plain
     *  thread-local is sufficient). */
    static final class Job {
        List<long[]> positions = new ArrayList<>();
        Object engine;
        Object world;
        Object blockType; // the EnumSkyBlock BLOCK constant (Launch loader)
        PhosphorLightBridge bridge;
        int minX, minY, minZ, maxX, maxY, maxZ;
        int[] preLight;
        boolean captured;
    }

    private static final ThreadLocal<Job> JOB = new ThreadLocal<>();
    /** Drain nesting depth: Phosphor re-enters processLightUpdatesForType
     *  from inside a drain; only the OUTERMOST frame is one logical job
     *  (nested frames overwrote the thread-local and orphaned compares —
     *  1,325 jobs captured, 2 compared in dev7). */
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();
    // int[0] = drain nesting depth. If the drain method THROWS, no IRETURN
    // runs and jobEnd never decrements — the depth leaks and every later
    // job is misread as nested (dev8: one boot-time exception -> jobs=0 for
    // the whole session). Self-heal: depth>1 with no live outer job and a
    // stale OUTER-FRAME stamp is treated as leaked and reset. The stamp is
    // advanced ONLY when the depth crosses 0<->1 (refreshing it on every
    // nested call made the heal impossible — dev9-11 permanent-zero bug).

    private static final ThreadLocal<Long> STAMP = new ThreadLocal<>();

    private static boolean depthIsLeaked(int depth) {
        if (depth <= 1 || JOB.get() != null) return false;
        Long st = STAMP.get();
        long age = System.nanoTime() - (st == null ? 0 : st);
        return age > 5_000_000_000L;
    }

    private PhosphorLightHook() { }

    /** §5 discrimination by enum NAME (SKY is ordinal 0 — the old ordinal
     *  gate was inverted). Returns the passed instance's ordinal for queue
     *  indexing, or -1 when not BLOCK. */
    private static int blockOrdinal(Object skyBlock) {
        if (!(skyBlock instanceof java.lang.Enum)) return -1;
        java.lang.Enum<?> e = (java.lang.Enum) skyBlock;
        return "BLOCK".equals(e.name()) ? e.ordinal() : -1;
    }

    /**
     * Injected at processLightUpdatesForType HEAD. Returns true when the
     * caller must RETURN (job fully handled by Rust, ON mode).
     */
    public static boolean jobStart(Object engine, Object skyBlock, Object queue) {
        if (!ENABLED) return false;
        int[] h = DEPTH.get();
        int depth = (h == null ? 0 : h[0]) + 1;
        if (TRACE_REMAINING > 0) {
            TRACE_REMAINING--;
            System.out.println("[RustCraft-Light] jobStart trace: depth=" + depth
                    + " type=" + (skyBlock instanceof java.lang.Enum
                            ? ((java.lang.Enum) skyBlock).name() : "?")
                    + " engine=" + (engine == null ? "null"
                            : engine.getClass().getName()));
        }
        if (depthIsLeaked(depth)) {
            depth = 1; // leaked frame healed
            DEPTH_LEAKS_HEALED.incrementAndGet();
        }
        if (depth == 1) {
            STAMP.set(System.nanoTime());
        }
        DEPTH.set(new int[]{depth});
        if (depth > 1) {
            return false; // nested drain: part of the outer job
        }
        Job job = null;
        try {
            int ordinal = blockOrdinal(skyBlock);
            if (ordinal < 0) return false; // SKY untouched (Java-owned)
            job = new Job();
            job.engine = engine;
            job.blockType = skyBlock;
            job.world = engineWorld(engine);
            if (job.world == null) return false;
            // scheduled positions from THE QUEUE THIS DRAIN CONSUMES
            job.positions = scheduledPositions(queue, engine);
            if (job.positions.isEmpty()) return false; // nothing scheduled

            // bounds: every scheduled pos +/- RADIUS, clipped to world.
            // Y is clipped to the affected band too — a full 0..255 column
            // would exceed the shadow cap even for a single torch.
            job.minX = Integer.MAX_VALUE; job.maxX = Integer.MIN_VALUE;
            job.minY = Integer.MAX_VALUE; job.maxY = Integer.MIN_VALUE;
            job.minZ = Integer.MAX_VALUE; job.maxZ = Integer.MIN_VALUE;
            // +1 halo: the outermost ring lies beyond any single job's
            // reach (light <= 15 travels <= 15 cells), so its POST-drain
            // value equals its pre value and seeds the replay with the
            // same boundary conditions Phosphor saw (without the halo the
            // replay box is light-isolated and under-counts — dev7).
            for (long[] p : job.positions) {
                int x = (int) p[0], y = (int) p[1], z = (int) p[2];
                job.minX = Math.min(job.minX, x - RADIUS - 1);
                job.maxX = Math.max(job.maxX, x + RADIUS + 1);
                job.minY = Math.max(0, Math.min(job.minY, y - RADIUS - 1));
                job.maxY = Math.min(255, Math.max(job.maxY, y + RADIUS + 1));
                job.minZ = Math.min(job.minZ, z - RADIUS - 1);
                job.maxZ = Math.max(job.maxZ, z + RADIUS + 1);
            }
            long volume = (long) (job.maxX - job.minX)
                    * (job.maxY - job.minY) * (long) (job.maxZ - job.minZ);
            // worldgen column relights cascade across chunks: light
            // inflow from outside any bounded box makes the replay
            // incomparable (the 30 worldgen-job mismatches in dev13).
            // Admit jobs whose SCHEDULED POSITIONS all sit in one chunk —
            // exactly the gameplay-mutation shape (a ±16 box always spans
            // chunks, so the box itself can never be the criterion —
            // dev14 skipped all 11 real mutation jobs that way). The halo
            // ring handles the box-edge boundary conditions.
            boolean chunkLocal = true;
            {
                int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
                for (long[] p : job.positions) {
                    int pcx = ((int) p[0]) >> 4, pcz = ((int) p[2]) >> 4;
                    if (cx == Integer.MIN_VALUE) {
                        cx = pcx;
                        cz = pcz;
                    } else if (pcx != cx || pcz != cz) {
                        chunkLocal = false;
                        break;
                    }
                }
            }
            boolean nearAnchor = true;
            if (COMPARE_ANCHOR != null) {
                for (long[] p : job.positions) {
                    int dx = ((int) p[0]) - COMPARE_ANCHOR[0];
                    int dy = ((int) p[1]) - COMPARE_ANCHOR[1];
                    int dz = ((int) p[2]) - COMPARE_ANCHOR[2];
                    if (Math.abs(dx) > RADIUS || Math.abs(dy) > RADIUS
                            || Math.abs(dz) > RADIUS) {
                        nearAnchor = false;
                        break;
                    }
                }
            }
            if (SHADOW && (volume > MAX_SHADOW_CELLS || !chunkLocal
                    || !COMPARE_WINDOW || !nearAnchor)) {
                String why = !COMPARE_WINDOW ? "pre-window"
                        : !nearAnchor ? "far-from-anchor"
                        : !chunkLocal ? "multi-chunk" : "oversized";
                if (SKIP_TRACE_REMAINING > 0) {
                    SKIP_TRACE_REMAINING--;
                    System.out.println("[RustCraft-Light] job skip: " + why
                            + " positions=" + job.positions.size()
                            + " vol=" + volume + " window=" + COMPARE_WINDOW
                            + " anchor="
                            + java.util.Arrays.toString(COMPARE_ANCHOR)
                            + " first=" + job.positions.get(0)[0] + ","
                            + job.positions.get(0)[1] + ","
                            + job.positions.get(0)[2]);
                }
                SKIPPED_BOUNDS.incrementAndGet();
                if (!COMPARE_WINDOW || !nearAnchor) {
                    SKIPPED_CASCADE.incrementAndGet();
                }
                JOBS.incrementAndGet();
                return false;
            }

            if (SHADOW) {
                if (SURFACE_ERROR != null) {
                    // surface broken: fail fast, no 27k-iteration storm
                    recordError(new IllegalStateException("surface unavailable"));
                    return false;
                }
                try {
                    ensureSurface(job.world);
                } catch (Throwable t) {
                    SURFACE_ERROR = String.valueOf(t);
                    recordError(t);
                    return false;
                }
                // snapshot pre-drain light over bounds (Java authoritative —
                // this read happens BEFORE Phosphor mutates anything)
                int sx = job.maxX - job.minX;
                int sy = job.maxY - job.minY;
                int sz = job.maxZ - job.minZ;
                job.preLight = new int[sx * sy * sz];
                int i = 0;
                boolean readFailure = false;
                for (int yy = job.minY; yy < job.maxY && !readFailure; yy++) {
                    for (int zz = job.minZ; zz < job.maxZ && !readFailure; zz++) {
                        for (int xx = job.minX; xx < job.maxX; xx++) {
                            int v = worldGetLight(job, xx, yy, zz);
                            if (v < 0) {
                                // one broken read poisons the whole
                                // snapshot — abort the job (Phosphor stands)
                                readFailure = true;
                                break;
                            }
                            job.preLight[i++] = v;
                        }
                    }
                }
                if (readFailure) {
                    return false;
                }
                job.captured = true;
                JOBS.incrementAndGet();
                JOB.set(job);
                return false; // Phosphor drains (authoritative)
            }
            // ON mode
            JOBS.incrementAndGet();
            JOB.set(job);
            return admit(job);
        } catch (Throwable t) {
            if (SURFACE_ERROR == null && job != null && job.world != null) {
                SURFACE_ERROR = String.valueOf(t);
            }
            recordError(t);
            return false;
        }
    }

    /**
     * Injected before RETURN. SHADOW: replay + compare. ON: commit.
     * Always cheap when no job is in flight.
     */
    public static void jobEnd(Object engine, Object skyBlock) {
        if (!ENABLED) return;
        // decrement FIRST (every frame, any type — jobStart incremented for
        // every frame): an earlier scripted patch restored a version without
        // this, so depth never fell and every drain after the first was
        // misread as nested (jobs=0 in dev8-12)
        int[] h = DEPTH.get();
        int depth = (h == null ? 1 : h[0]);
        DEPTH.set(new int[]{Math.max(0, depth - 1)});
        if (depth > 1) {
            return; // nested drain frame
        }
        try {
            int ordinal = blockOrdinal(skyBlock);
            if (ordinal < 0) return; // SKY untouched (Java-owned)
            if (SHADOW) {
                Job job = JOB.get();
                if (job == null || !job.captured) return;
                JOB.remove();
                replayAndCompare(job);
            } else {
                Job job = JOB.get();
                if (job == null) return;
                JOB.remove();
                commit(job);
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------

    private static boolean admit(Job job) {
        try {
            job.bridge = PhosphorLightBridge.open(
                    job.minX, job.minY, job.minZ, job.maxX, job.maxY, job.maxZ);
            if (job.bridge == null) {
                FALLBACKS.incrementAndGet();
                return false;
            }
            for (int y = job.minY; y < job.maxY; y++) {
                for (int z = job.minZ; z < job.maxZ; z++) {
                    for (int x = job.minX; x < job.maxX; x++) {
                        job.bridge.addInitial(x, y, z, worldGetLight(job, x, y, z));
                        job.bridge.addOpacity(x, y, z, worldOpacity(job, x, y, z));
                        job.bridge.addEmission(x, y, z, worldEmission(job, x, y, z));
                    }
                }
            }
            for (long[] p : job.positions) {
                job.bridge.addNotify((int) p[0], (int) p[1], (int) p[2]);
            }
            job.bridge.flushInitial();
            job.bridge.flushOpacity();
            job.bridge.flushEmission();
            job.bridge.flushNotify();
            long changed = job.bridge.propagate();
            ADMITTED.incrementAndGet();
            COMMITTED_CELLS.addAndGet(changed);
            return true;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            FALLBACKS.incrementAndGet();
            if (job.bridge != null) job.bridge.close();
            job.bridge = null;
            return false;
        }
    }

    private static void commit(Job job) {
        if (job.bridge == null) return;
        try {
            for (int y = job.minY; y < job.maxY; y++) {
                for (int z = job.minZ; z < job.maxZ; z++) {
                    for (int x = job.minX; x < job.maxX; x++) {
                        int rust = job.bridge.readResult(x, y, z);
                        int cur = worldGetLight(job, x, y, z);
                        if (rust != cur) {
                            worldSetLight(job, x, y, z, rust);
                        }
                    }
                }
            }
        } finally {
            job.bridge.close();
            job.bridge = null;
        }
    }

    private static void replayAndCompare(Job job) {
        PhosphorLightBridge bridge = null;
        try {
            bridge = PhosphorLightBridge.open(
                    job.minX, job.minY, job.minZ, job.maxX, job.maxY, job.maxZ);
            if (bridge == null) return; // no admission data; Phosphor stands
            // rebuild pre-state in Rust, then replay Phosphor's RESULT as the
            // mutation outcome: seed Rust with the POST values as "emission
            // targets" is NOT valid — instead Rust recomputes from pre-state
            // + current opacity/emission; Phosphor already did exactly that.
            for (int y = job.minY; y < job.maxY; y++) {
                for (int z = job.minZ; z < job.maxZ; z++) {
                    for (int x = job.minX; x < job.maxX; x++) {
                        boolean halo = x == job.minX || x == job.maxX - 1
                                || y == job.minY || y == job.maxY - 1
                                || z == job.minZ || z == job.maxZ - 1;
                        int initial = halo
                                ? worldGetLight(job, x, y, z)  // post == pre
                                : job.preLight[index(job, x, y, z)];
                        if (initial < 0) initial = 0;
                        bridge.addInitial(x, y, z, initial);
                        bridge.addOpacity(x, y, z, worldOpacity(job, x, y, z));
                        bridge.addEmission(x, y, z, worldEmission(job, x, y, z));
                    }
                }
            }
            for (long[] p : job.positions) {
                bridge.addNotify((int) p[0], (int) p[1], (int) p[2]);
            }
            bridge.flushInitial();
            bridge.flushOpacity();
            bridge.flushEmission();
            bridge.flushNotify();
            bridge.propagate();
            int mismatches = 0;
            for (int y = job.minY; y < job.maxY; y++) {
                for (int z = job.minZ; z < job.maxZ; z++) {
                    for (int x = job.minX; x < job.maxX; x++) {
                        int idx = index(job, x, y, z);
                        int rust = bridge.readResult(x, y, z);
                        int javaNow = worldGetLight(job, x, y, z);
                        if (rust != javaNow) {
                            mismatches++;
                            if (mismatches <= 3) {
                                LAST_MISMATCH = "x=" + x + " y=" + y + " z=" + z
                                        + " java=" + javaNow + " rust=" + rust;
                            }
                        }
                    }
                }
            }
            COMPARED.incrementAndGet();
            if (mismatches > 0) {
                MISMATCHES.addAndGet(mismatches);
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        } finally {
            if (bridge != null) bridge.close();
        }
    }

    private static void recordError(Throwable t) {
        if (LAST_ERROR.length() > 0 && ERRORS.get() % 10000 != 1) return;
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 2; i++) {
            sb.append(" @ ").append(st[i].getClassName())
                    .append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber());
        }
        String msg = sb.toString();
        LAST_ERROR = msg.length() > 400 ? msg.substring(0, 400) : msg;
    }

    private static int index(Job job, int x, int y, int z) {
        return (y - job.minY) * (job.maxZ - job.minZ) * (job.maxX - job.minX)
                + (z - job.minZ) * (job.maxX - job.minX)
                + (x - job.minX);
    }

    // ------------------------------------------------------------------
    // world + engine access — ALL object-derived (no Class.forName): the
    // hook class is loaded by the APP loader where the deobf names do not
    // exist; enum/pos/state classes come from the live instances
    // ------------------------------------------------------------------

    private static volatile Field F_ENGINE_WORLD, F_QUEUED_UPDATES;
    private static volatile Method M_QUEUE_COPY;

    private static volatile Method M_GETLIGHT, M_SETLIGHT, M_GET_STATE;
    private static volatile Method M_LIGHT_VALUE, M_LIGHT_OPACITY, M_GET_BLOCK;
    private static volatile java.lang.reflect.Constructor<?> POS_CTOR;
    private static volatile boolean SURFACE_READY;
    private static volatile String SURFACE_ERROR = null;
    public static volatile String LAST_ERROR = "";
    static volatile int TRACE_REMAINING = 5;
    static volatile int SKIP_TRACE_REMAINING = 10;

    private static void ensureSurface(Object world) throws Exception {
        if (SURFACE_READY) return;
        if (SURFACE_ERROR != null) {
            throw new IllegalStateException("surface failed earlier: "
                    + SURFACE_ERROR);
        }
        synchronized (PhosphorLightHook.class) {
            if (SURFACE_READY) return;
            // the world's getBlockState param type IS the BlockPos class of
            // the live (Launch) loader
            for (Method m : world.getClass().getMethods()) {
                if (("getBlockState".equals(m.getName())
                        || "func_180495_p".equals(m.getName()))
                        && m.getParameterCount() == 1
                        && !m.getReturnType().equals(void.class)) {
                    M_GET_STATE = m;
                    m.setAccessible(true);
                    POS_CTOR = m.getParameterTypes()[0]
                            .getConstructor(int.class, int.class, int.class);
                    break;
                }
            }
            if (M_GET_STATE == null) {
                throw new IllegalStateException("getBlockState not found");
            }
            Class<?> posClass = M_GET_STATE.getParameterTypes()[0];
            M_GETLIGHT = findMethod(world.getClass(),
                    new String[]{"getLightFor", "func_175642_b"}, 2, int.class);
            M_SETLIGHT = findMethod(world.getClass(),
                    new String[]{"setLightFor", "func_175653_a"}, 3, void.class);
            // state -> block -> Forge world-aware light/opacity (the exact
            // calls the live engine's rule makes)
            for (Method m : M_GET_STATE.getReturnType().getMethods()) {
                if (("getBlock".equals(m.getName())
                        || "func_177230_c".equals(m.getName()))
                        && m.getParameterCount() == 0) {
                    M_GET_BLOCK = m;
                    m.setAccessible(true);
                    break;
                }
            }
            if (M_GET_BLOCK == null) {
                throw new IllegalStateException("getBlock not found");
            }
            M_LIGHT_VALUE = findMethod(M_GET_BLOCK.getReturnType(),
                    new String[]{"getLightValue"}, 3, int.class);
            M_LIGHT_OPACITY = findMethod(M_GET_BLOCK.getReturnType(),
                    new String[]{"getLightOpacity"}, 3, int.class);
            SURFACE_READY = true;
        }
        SURFACE_ERROR = null;
    }

    private static Method findMethod(Class<?> owner, String[] names,
                                     int paramCount, Class<?> ret) {
        for (Method m : owner.getMethods()) {
            boolean hit = false;
            for (String n : names) {
                if (n.equals(m.getName())) {
                    hit = true;
                    break;
                }
            }
            if (hit && m.getParameterCount() == paramCount
                    && m.getReturnType().equals(ret)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("seam method missing: "
                + owner.getName() + " " + java.util.Arrays.toString(names));
    }

    private static Object newPos(Object world, int x, int y, int z)
            throws Exception {
        ensureSurface(world);
        return POS_CTOR.newInstance(x, y, z);
    }

    private static int worldGetLight(Job job, int x, int y, int z) {
        try {
            ensureSurface(job.world);
            return (Integer) M_GETLIGHT.invoke(job.world, job.blockType,
                    newPos(job.world, x, y, z));
        } catch (Throwable t) {
            recordError(t);
            ERRORS.incrementAndGet();
            return -1;
        }
    }

    private static void worldSetLight(Job job, int x, int y, int z, int v) {
        try {
            ensureSurface(job.world);
            M_SETLIGHT.invoke(job.world, job.blockType,
                    newPos(job.world, x, y, z), v);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    private static int worldOpacity(Job job, int x, int y, int z) {
        try {
            ensureSurface(job.world);
            Object pos = newPos(job.world, x, y, z);
            Object state = M_GET_STATE.invoke(job.world, pos);
            Object block = M_GET_BLOCK.invoke(state);
            return (Integer) M_LIGHT_OPACITY.invoke(block, state, job.world, pos);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return 0;
        }
    }

    private static int worldEmission(Job job, int x, int y, int z) {
        try {
            ensureSurface(job.world);
            Object pos = newPos(job.world, x, y, z);
            Object state = M_GET_STATE.invoke(job.world, pos);
            Object block = M_GET_BLOCK.invoke(state);
            return (Integer) M_LIGHT_VALUE.invoke(block, state, job.world, pos);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return 0;
        }
    }

    private static int skyBlockOrdinal(Object skyBlock) {
        Number n = (Number) unsafeOrdinal(skyBlock);
        return n == null ? -1 : n.intValue();
    }

    private static Object unsafeOrdinal(Object e) {
        try {
            return e.getClass().getMethod("ordinal").invoke(e);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object engineWorld(Object engine) {
        try {
            Field f = F_ENGINE_WORLD;
            if (f == null) {
                f = engine.getClass().getDeclaredField("world");
                f.setAccessible(true);
                F_ENGINE_WORLD = f;
            }
            return f.get(engine);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Scheduled positions from the QUEUE THE DRAIN IS CONSUMING — passed
     *  in as an argument by the INNER-seam injection (no field lookup, no
     *  outer-queue visibility race). Each entry packs (x,y,z). */
    @SuppressWarnings("unchecked")
    private static List<long[]> scheduledPositions(Object queue, Object engine) {
        List<long[]> out = new ArrayList<>();
        try {
            Method copy = M_QUEUE_COPY;
            if (copy == null) {
                Class<?> pqq = queue.getClass();
                for (Method m : pqq.getDeclaredMethods()) {
                    // look for a method returning long[] or an iterator
                    if (m.getParameterCount() == 0
                            && (m.getReturnType() == long[].class
                                || m.getReturnType().getName().contains("Iterator"))) {
                        m.setAccessible(true);
                        copy = m;
                        M_QUEUE_COPY = copy;
                        break;
                    }
                }
            }
            if (copy == null) return out;
            Object result = copy.invoke(queue);
            if (result instanceof long[]) {
                for (long packed : (long[]) result) {
                    out.add(unpackPhosphor(engine, packed));
                }
            } else {
                // PooledLongQueue.iterator() -> LongQueueIterator: plain
                // class with hasNext()/next()long (not java.util.Iterator)
                Method hasNext = result.getClass().getMethod("hasNext");
                Method next = result.getClass().getMethod("next");
                while ((Boolean) hasNext.invoke(result)) {
                    long packed = (Long) next.invoke(result);
                    out.add(unpackPhosphor(engine, packed));
                }
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
        return out;
    }

    /** Unpack Phosphor's packed position (lX/lY/lZ masks in LightingEngine).
     *  Layout (from bytecode constants): x bits 0-25 shifted by sX=38? —
     *  resolved at runtime from the static masks via reflection so we never
     *  hardcode. */
    private static long[] PHOSPHOR_MASKS;

    private static long[] unpackPhosphor(Object engine, long packed) {
        try {
            if (PHOSPHOR_MASKS == null) {
                // masks come from the LIVE ENGINE INSTANCE's class — this
                // hook is App-loader-loaded (parent delegation from the
                // injected call site) where Class.forName by the mod class
                // name CANNOT resolve (the CNFE silently returned (0,0,0)
                // for every position — dev13-20)
                Field lx = engine.getClass().getDeclaredField("lX");
                Field ly = engine.getClass().getDeclaredField("lY");
                Field lz = engine.getClass().getDeclaredField("lZ");
                Field sx = engine.getClass().getDeclaredField("sX");
                Field sy = engine.getClass().getDeclaredField("sY");
                Field sz = engine.getClass().getDeclaredField("sZ");
                for (Field f2 : new Field[]{lx, ly, lz, sx, sy, sz}) {
                    f2.setAccessible(true);
                }
                // the masks/shifts are INT fields (javap: private static
                // final int lX ...) — getLong threw per position and the
                // catch returned (0,0,0), rejecting every job as
                // far-from-anchor (dev17/18 skip traces)
                PHOSPHOR_MASKS = new long[]{
                        lx.getInt(null), ly.getInt(null), lz.getInt(null),
                        sx.getInt(null), sy.getInt(null), sz.getInt(null)};
            }
            // lX/lY/lZ are BIT WIDTHS (javap: lX=26, lY=8, lZ=26; shifts
            // sZ=0, sX=26, sY=52) — masks derive as (1<<width)-1. Using a
            // width as a mask decoded every position to (0,0,0) (dev13-19).
            long mx = (1L << PHOSPHOR_MASKS[0]) - 1;
            long my = (1L << PHOSPHOR_MASKS[1]) - 1;
            long mz = (1L << PHOSPHOR_MASKS[2]) - 1;
            int x = (int) ((packed >>> PHOSPHOR_MASKS[3]) & mx);
            int y = (int) ((packed >>> PHOSPHOR_MASKS[4]) & my);
            int z = (int) ((packed >>> PHOSPHOR_MASKS[5]) & mz);
            // x/z are BIAS-encoded (encodeWorldCoord adds 2^25 before
            // shifting: verified in the engine bytecode) — subtract the
            // bias, never two's-complement sign-extension (dev20/21
            // decoded x=192 as -33554240)
            x -= (1 << 25);
            z -= (1 << 25);
            return new long[]{x, y, z};
        } catch (Throwable t) {
            recordError(t);
            return new long[]{0, 0, 0};
        }
    }

    public static String dumpMetrics() {
        return "phosphorLight.hook enabled=" + ENABLED + " mode=" + MODE
                + " jobs=" + JOBS.get()
                + " admitted=" + ADMITTED.get()
                + " fallbacks=" + FALLBACKS.get()
                + " compared=" + COMPARED.get()
                + " mismatches=" + MISMATCHES.get()
                + " committedCells=" + COMMITTED_CELLS.get()
                + " errors=" + ERRORS.get()
                + " skippedBounds=" + SKIPPED_BOUNDS.get()
                + " depthLeaksHealed=" + DEPTH_LEAKS_HEALED.get()
                + " drainTicked=" + DRAIN_TICKED.get()
                + " scheduled=" + SCHEDULED.get()
                + " skippedCascade=" + SKIPPED_CASCADE.get()
                + " lastError=" + LAST_ERROR
                + " lastMismatch=" + LAST_MISMATCH;
    }
}
