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
    public static volatile String LAST_MISMATCH = "";

    /** Per-job state (server thread — the engine's ownedThread — so a plain
     *  thread-local is sufficient). */
    static final class Job {
        List<long[]> positions = new ArrayList<>();
        Object engine;
        Object world;
        PhosphorLightBridge bridge;
        int minX, minY, minZ, maxX, maxY, maxZ;
        int[] preLight;
        boolean captured;
    }

    private static final ThreadLocal<Job> JOB = new ThreadLocal<>();

    private PhosphorLightHook() { }

    /**
     * Injected at processLightUpdatesForType HEAD. Returns true when the
     * caller must RETURN (job fully handled by Rust, ON mode).
     */
    public static boolean jobStart(Object engine, Object skyBlock) {
        if (!ENABLED) return false;
        try {
            int ordinal = skyBlockOrdinal(skyBlock);
            if (ordinal != 0) return false; // 0 = BLOCK; SKY untouched
            Job job = new Job();
            job.engine = engine;
            job.world = engineWorld(engine);
            if (job.world == null) return false;
            // scheduled positions from Phosphor's queue
            job.positions = scheduledPositions(engine, ordinal);
            if (job.positions.isEmpty()) return false; // nothing scheduled

            // bounds: every scheduled pos +/- RADIUS, clipped to world
            job.minX = Integer.MAX_VALUE; job.maxX = Integer.MIN_VALUE;
            job.minY = 0; job.maxY = 255;
            job.minZ = Integer.MAX_VALUE; job.maxZ = Integer.MIN_VALUE;
            for (long[] p : job.positions) {
                int x = (int) p[0], y = (int) p[1], z = (int) p[2];
                job.minX = Math.min(job.minX, x - RADIUS);
                job.maxX = Math.max(job.maxX, x + RADIUS);
                job.minZ = Math.min(job.minZ, z - RADIUS);
                job.maxZ = Math.max(job.maxZ, z + RADIUS);
            }

            if (SHADOW) {
                // snapshot pre-drain light over bounds (Java authoritative —
                // this read happens BEFORE Phosphor mutates anything)
                int sx = job.maxX - job.minX;
                int sy = job.maxY - job.minY;
                int sz = job.maxZ - job.minZ;
                job.preLight = new int[sx * sy * sz];
                int i = 0;
                for (int yy = job.minY; yy < job.maxY; yy++) {
                    for (int zz = job.minZ; zz < job.maxZ; zz++) {
                        for (int xx = job.minX; xx < job.maxX; xx++) {
                            job.preLight[i++] = worldGetLight(job.world, xx, yy, zz);
                        }
                    }
                }
                job.captured = true;
                JOBS.incrementAndGet();
                return false; // Phosphor drains (authoritative)
            }
            // ON mode
            JOBS.incrementAndGet();
            JOB.set(job);
            return admit(job);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return false;
        }
    }

    /**
     * Injected before RETURN. SHADOW: replay + compare. ON: commit.
     * Always cheap when no job is in flight.
     */
    public static void jobEnd(Object engine, Object skyBlock) {
        if (!ENABLED) return;
        try {
            int ordinal = skyBlockOrdinal(skyBlock);
            if (ordinal != 0) return;
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
                        job.bridge.addInitial(x, y, z, worldGetLight(job.world, x, y, z));
                        job.bridge.addOpacity(x, y, z, worldOpacity(job.world, x, y, z));
                        job.bridge.addEmission(x, y, z, worldEmission(job.world, x, y, z));
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
                        int cur = worldGetLight(job.world, x, y, z);
                        if (rust != cur) {
                            worldSetLight(job.world, x, y, z, rust);
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
                        int idx = index(job, x, y, z);
                        bridge.addInitial(x, y, z, job.preLight[idx]);
                        bridge.addOpacity(x, y, z, worldOpacity(job.world, x, y, z));
                        bridge.addEmission(x, y, z, worldEmission(job.world, x, y, z));
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
                        int javaNow = worldGetLight(job.world, x, y, z);
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

    private static int index(Job job, int x, int y, int z) {
        return (y - job.minY) * (job.maxZ - job.minZ) * (job.maxX - job.minX)
                + (z - job.minZ) * (job.maxX - job.minX)
                + (x - job.minX);
    }

    // ------------------------------------------------------------------
    // world + engine access (reflection over the live runtime)
    // ------------------------------------------------------------------

    private static volatile Method M_GETLIGHT, M_SETLIGHT, M_OPACITY, M_EMISSION;
    private static volatile Field F_ENGINE_WORLD, F_QUEUED_UPDATES;
    private static volatile Method M_QUEUE_COPY;

    private static int worldGetLight(Object world, int x, int y, int z) {
        try {
            Method m = M_GETLIGHT;
            if (m == null) {
                m = world.getClass().getMethod(
                        "getLightFor",
                        Class.forName("net.minecraft.world.EnumSkyBlock"),
                        Class.forName("net.minecraft.util.math.BlockPos"));
                m.setAccessible(true);
                M_GETLIGHT = m;
            }
            Object pos = blockPos(x, y, z);
            Object skyBlock = enumSkyBlock(0);
            return (int) m.invoke(world, skyBlock, pos);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void worldSetLight(Object world, int x, int y, int z, int v) {
        try {
            Method m = M_SETLIGHT;
            if (m == null) {
                m = world.getClass().getMethod(
                        "setLightFor",
                        Class.forName("net.minecraft.world.EnumSkyBlock"),
                        Class.forName("net.minecraft.util.math.BlockPos"),
                        int.class);
                m.setAccessible(true);
                M_SETLIGHT = m;
            }
            m.invoke(world, enumSkyBlock(0), blockPos(x, y, z), v);
        } catch (Throwable ignore) { }
    }

    private static int worldOpacity(Object world, int x, int y, int z) {
        try {
            Method m = M_OPACITY;
            if (m == null) {
                m = world.getClass().getMethod("getBlockOpacity",
                        Class.forName("net.minecraft.block.state.IBlockState"),
                        Class.forName("net.minecraft.util.math.BlockPos"));
                m.setAccessible(true);
                M_OPACITY = m;
            }
            Object pos = blockPos(x, y, z);
            Object state = world.getClass().getMethod(
                    "getBlockState", pos.getClass()).invoke(world, pos);
            return (int) m.invoke(world, state, pos);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int worldEmission(Object world, int x, int y, int z) {
        try {
            Method m = M_EMISSION;
            if (m == null) {
                m = world.getClass().getMethod("getLightValue",
                        Class.forName("net.minecraft.block.state.IBlockState"),
                        Class.forName("net.minecraft.util.math.BlockPos"));
                m.setAccessible(true);
                M_EMISSION = m;
            }
            Object pos = blockPos(x, y, z);
            Object state = world.getClass().getMethod(
                    "getBlockState", pos.getClass()).invoke(world, pos);
            return (int) m.invoke(world, state, pos);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object blockPos(int x, int y, int z) throws Exception {
        Class<?> c = Class.forName("net.minecraft.util.math.BlockPos");
        return c.getConstructor(int.class, int.class, int.class)
                .newInstance(x, y, z);
    }

    private static Object enumSkyBlock(int ordinal) throws Exception {
        Class<?> c = Class.forName("net.minecraft.world.EnumSkyBlock");
        return c.getEnumConstants()[ordinal];
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

    /** Scheduled positions: drain-free copy of Phosphor's queuedLightUpdates
     *  [BLOCK] via PooledLongQueue reflection. Each entry packs (x,y,z,level). */
    @SuppressWarnings("unchecked")
    private static List<long[]> scheduledPositions(Object engine, int ordinal) {
        List<long[]> out = new ArrayList<>();
        try {
            Field f = F_QUEUED_UPDATES;
            if (f == null) {
                f = engine.getClass().getDeclaredField("queuedLightUpdates");
                f.setAccessible(true);
                F_QUEUED_UPDATES = f;
            }
            Object[] queues = (Object[]) f.get(engine);
            Object queue = queues[ordinal];
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
                    out.add(unpackPhosphor(packed));
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

    private static long[] unpackPhosphor(long packed) {
        try {
            if (PHOSPHOR_MASKS == null) {
                Class<?> c = Class.forName(
                        "me.jellysquid.mods.phosphor.mod.world.lighting.LightingEngine");
                Field lx = c.getDeclaredField("lX");
                Field ly = c.getDeclaredField("lY");
                Field lz = c.getDeclaredField("lZ");
                Field sx = c.getDeclaredField("sX");
                Field sy = c.getDeclaredField("sY");
                Field sz = c.getDeclaredField("sZ");
                for (Field f2 : new Field[]{lx, ly, lz, sx, sy, sz}) {
                    f2.setAccessible(true);
                }
                PHOSPHOR_MASKS = new long[]{
                        lx.getLong(null), ly.getLong(null), lz.getLong(null),
                        sx.getLong(null), sy.getLong(null), sz.getLong(null)};
            }
            int x = (int) ((packed & PHOSPHOR_MASKS[0]) >>> PHOSPHOR_MASKS[3]);
            int y = (int) ((packed & PHOSPHOR_MASKS[1]) >>> PHOSPHOR_MASKS[4]);
            int z = (int) ((packed & PHOSPHOR_MASKS[2]) >>> PHOSPHOR_MASKS[5]);
            return new long[]{x, y, z};
        } catch (Throwable t) {
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
                + " lastMismatch=" + LAST_MISMATCH;
    }
}
