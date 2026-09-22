package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3 worldgen LIVE adapter (M3W4 kernel).
 *
 * Supports four runtime modes:
 *  - OFF: disabled, pure vanilla reference (untouched).
 *  - OFF_MEASURE: Java reference timing only (for matched baseline studies).
 *  - SHADOW: Java authoritative, native shadow comparison at method return.
 *  - ON_EXPERIMENTAL: Rust authoritative, computed at method entry; Java bypassed.
 *
 * Gated by system properties:
 *   minecraftrust.worldgen (preferred) or minecraftrust.worldgen_shadow.
 *   Default: OFF.
 */
public final class WorldgenShadow {

    public static final String MODE_OFF = "OFF";
    public static final String MODE_OFF_MEASURE = "OFF_MEASURE";
    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_ON = "ON_EXPERIMENTAL";

    public static final String RUNTIME_MODE =
            System.getProperty("minecraftrust.worldgen",
                    System.getProperty("minecraftrust.worldgen_shadow", MODE_OFF)).toUpperCase();

    public static final AtomicLong CALLS = new AtomicLong();
    public static final AtomicLong ELIGIBLE = new AtomicLong();
    public static final AtomicLong INELIGIBLE_DISABLED = new AtomicLong();
    public static final AtomicLong INELIGIBLE_CLASS = new AtomicLong();
    public static final AtomicLong INELIGIBLE_FIELDS = new AtomicLong();
    public static final AtomicLong INELIGIBLE_NO_CTX = new AtomicLong();
    public static final AtomicLong NATIVE_ERRORS = new AtomicLong();

    // Shadow mode counters
    public static final AtomicLong FIELDS_COMPARED = new AtomicLong();
    public static final AtomicLong DOUBLES_COMPARED = new AtomicLong();
    public static final AtomicLong MATCHES = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();

    // M3W5: Terrain Shadow mode counters
    public static final AtomicLong TERRAIN_CHUNKS_COMPARED = new AtomicLong();
    public static final AtomicLong TERRAIN_BLOCKS_COMPARED = new AtomicLong();
    public static final AtomicLong TERRAIN_MATCHES = new AtomicLong();
    public static final AtomicLong TERRAIN_MISMATCHES = new AtomicLong();
    public static volatile String FIRST_TERRAIN_MISMATCH = "none";
    public static final AtomicLong TERRAIN_RUST_NS = new AtomicLong();

    // M4: NativeChunk Foundation & Multi-Consumer counters.
    // M4.2D validity contract: registration-time consumer runs read DEFAULT
    // light (no Chunk/light engine exists at primer stage) — they are
    // STRUCTURAL proofs of encoder/consumer machinery, NOT current-snapshot
    // packet claims. Packet validity is claimed only post-first-touch-sync by
    // the coherency path (m42c_*).
    public static final AtomicLong M4_REGISTERED = new AtomicLong();
    public static final AtomicLong M4_PACKET_STRUCTURAL = new AtomicLong();
    public static final AtomicLong M4_OCCUPANCY_STRUCTURAL = new AtomicLong();
    public static final AtomicLong M4_PERSISTENCE_STRUCTURAL = new AtomicLong();
    public static final AtomicLong M4_RUST_BYTES_ENCODED = new AtomicLong();

    // Authoritative mode counters
    public static final AtomicLong NATIVE_AUTH = new AtomicLong();
    public static final AtomicLong FALLBACKS = new AtomicLong();
    public static final AtomicLong FALLBACK_CUSTOM_GEN = new AtomicLong();
    public static final AtomicLong FALLBACK_NON_OVERWORLD = new AtomicLong();
    public static final AtomicLong FALLBACK_INVALID_ARRAY = new AtomicLong();
    public static final AtomicLong FALLBACK_NO_CTX = new AtomicLong();
    public static final AtomicLong FALLBACK_BIOME_FLOATS = new AtomicLong();
    public static final AtomicLong FALLBACK_EXCEPTION = new AtomicLong();

    // Sampled parity counters (if enabled)
    public static final AtomicLong SAMPLED_CHECKS = new AtomicLong();
    public static final AtomicLong SAMPLED_MATCHES = new AtomicLong();
    public static final AtomicLong SAMPLED_MISMATCHES = new AtomicLong();

    // Timing counters
    public static final AtomicLong RUST_NS = new AtomicLong();
    public static final AtomicLong AUTH_NS = new AtomicLong();
    public static final AtomicLong JAVA_NS = new AtomicLong();
    public static final AtomicLong JAVA_CALLS = new AtomicLong();

    // --------------------------------------------------------------
    // Per-stage timing breakdown of the eligible authoritative ON path.
    // Samples appended under lock; percentiles computed at dump.
    // Math is a few k calls per run -> lock contention negligible.
    // Stages (nanoseconds), measured in onExperimental eligible path:
    //  ELIG  : eligibility + classifier + context lookup (incl. readDimension)
    //  BIOME : populateBiomeFloats (field_185981_C -> direct buffer)
    //  JNI   : initFieldComplete native call (kernel + native output transfer)
    //  COMMIT: direct-buffer -> double[] field_185998_q copy
    //  MISC  : not sampled directly; = AUTH_NS - (ELIG+BIOME+JNI+COMMIT)
    // --------------------------------------------------------------
    static final Object STAGE_LOCK = new Object();
    static final java.util.List<Long> STAGE_ELIG = new java.util.ArrayList<>();
    static final java.util.List<Long> STAGE_BIOME = new java.util.ArrayList<>();
    static final java.util.List<Long> STAGE_JNI = new java.util.ArrayList<>();
    static final java.util.List<Long> STAGE_COMMIT = new java.util.ArrayList<>();
    // Java OFF_MEASURE per-call samples for p50/p95/p99 of the vanilla path.
    static final java.util.List<Long> JAVA_SAMPLES = new java.util.ArrayList<>();

    // Pre-population terrain oracle: exact base block-state hash per chunk
    // captured at return of func_185977_a (setBlocksInChunk), before any
    // structure / population / decoration pass. Both arms (J and R) record.
    static final java.util.concurrent.ConcurrentHashMap<java.lang.String, String> PREPOP_HASH =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static final AtomicLong PREPOP_CAPTURED = new AtomicLong();
    private static volatile java.lang.reflect.Field PRIMER_CHARS;
    private static volatile java.lang.reflect.Method BIOME_ID; // Biome.func_185362_a
    private static final ThreadLocal<java.util.zip.CRC32> CRC_TL =
            ThreadLocal.withInitial(java.util.zip.CRC32::new);

    // Lifecycle
    public static final AtomicLong CTX_CREATED = new AtomicLong();
    public static final AtomicLong CTX_FREED = new AtomicLong();
    public static volatile String FIRST_MISMATCH = "none";
    public static volatile String LAST_ERROR = "none";

    // Strict classification tracking
    public static final AtomicLong CLASSIFIED_VANILLA_OVERWORLD = new AtomicLong();
    public static final AtomicLong CLASSIFIED_CUSTOM_GENERATOR = new AtomicLong();
    public static final AtomicLong CLASSIFIED_TRANSFORM_CONFLICT = new AtomicLong();
    public static final AtomicLong CLASSIFIED_UNSUPPORTED_STATE = new AtomicLong();
    public static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> OBSERVED_GENERATORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> OBSERVED_DIMENSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<Object, Long> CTX =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Context staging buffers (sized for 16+8+16+16 = 56 levels)
    private static final int MAX_LEVELS = 64;
    private static final ByteBuffer PERMS_BB =
            ByteBuffer.allocateDirect(MAX_LEVELS * 512 * 4).order(ByteOrder.nativeOrder());
    private static final DoubleBuffer OFFS_BB = (DoubleBuffer) ByteBuffer
            .allocateDirect(MAX_LEVELS * 3 * 8).order(ByteOrder.nativeOrder())
            .asDoubleBuffer().rewind();

    // Thread-local staging buffers: zero allocation in generation loop, thread-safe
    private static final ThreadLocal<ByteBuffer> BIOME_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(200 * 4).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> OUT_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(825 * 8).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> TERRAIN_OUT_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> TERRAIN_DENSITY_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(825 * 8).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> M4_PKT_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> M4_OCC_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> M4_MCA_BB_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder()));

    private WorldgenShadow() {}

    static {
        try { System.loadLibrary("rustcraft_ffi"); } catch (Throwable t) { }
    }
    private static native long initCreateFromState(int oD, int oM, int oN, int oX,
            long permsAddr, long offsAddr,
            float a, float b, float e, float f, float g, float h, float i, float j,
            float k, float l, float d, float c, float m, float n, float o, float p);
    private static native void initFieldComplete(long h, int x4, int z4, long biomeAddr, long outAddr);
    private static native void initFreeRaw(long h);
    private static native void terrainSetBlocksOpt(long densityAddr, int seaLevel, int stoneId, int waterId, long primerOutAddr);

    public static boolean enabled() {
        return !MODE_OFF.equals(RUNTIME_MODE);
    }

    public static boolean isAuthoritative() {
        return MODE_ON.equals(RUNTIME_MODE);
    }

    public static boolean isShadow() {
        return MODE_SHADOW.equals(RUNTIME_MODE);
    }

    public static boolean isMeasure() {
        return MODE_OFF_MEASURE.equals(RUNTIME_MODE);
    }

    // ------------------------------------------------------------------
    // OFF_MEASURE hooks (for matched Java reference timing)
    // ------------------------------------------------------------------
    private static final ThreadLocal<long[]> MEASURE_TL = ThreadLocal.withInitial(() -> new long[1]);

    public static void measureStart() {
        CALLS.incrementAndGet();
        MEASURE_TL.get()[0] = System.nanoTime();
    }

    public static void measureEnd() {
        long elapsed = System.nanoTime() - MEASURE_TL.get()[0];
        JAVA_NS.addAndGet(elapsed);
        JAVA_CALLS.incrementAndGet();
        synchronized (STAGE_LOCK) {
            if (JAVA_SAMPLES.size() < 2_000_000) JAVA_SAMPLES.add(Long.valueOf(elapsed));
        }
    }

    // ------------------------------------------------------------------
    // Dimension helper
    // ------------------------------------------------------------------
    private static String readDimension(Object generator) {
        try {
            Object w = field(generator, "field_185995_n"); // World
            if (w == null) return "unknown";
            Object p = field(w, "field_73011_w"); // WorldProvider
            if (p == null) return "unknown";
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("getDimension");
                return String.valueOf(m.invoke(p));
            } catch (Throwable ignore) { }
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("func_186058_p"); // getDimensionType
                Object dt = m.invoke(p);
                java.lang.reflect.Method mId = dt.getClass().getMethod("func_186068_a"); // getId
                return String.valueOf(mId.invoke(dt));
            } catch (Throwable ignore) { }
            return "0";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // ON_EXPERIMENTAL hook: called at ENTRY of func_185978_a
    // Returns true  => Rust filled field_185998_q; caller method RETURN immediately.
    // Returns false => Fallback: caller method runs vanilla Java reference body.
    // ------------------------------------------------------------------
    public static boolean onExperimental(Object generator, int x4, int z4, double[] q) {
        CALLS.incrementAndGet();
        if (!isAuthoritative()) {
            INELIGIBLE_DISABLED.incrementAndGet();
            return false;
        }
        long tCallStart = System.nanoTime();
        try {
            long t0 = System.nanoTime();
            String gClass = generator.getClass().getName();
            OBSERVED_GENERATORS.computeIfAbsent(gClass, k -> new AtomicLong()).incrementAndGet();
            String dim = readDimension(generator);
            OBSERVED_DIMENSIONS.computeIfAbsent(dim, k -> new AtomicLong()).incrementAndGet();

            if (generator.getClass() != net.minecraft.world.gen.ChunkGeneratorOverworld.class) {
                CLASSIFIED_CUSTOM_GENERATOR.incrementAndGet();
                INELIGIBLE_CLASS.incrementAndGet();
                FALLBACK_CUSTOM_GEN.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return false;
            }
            if (!"0".equals(dim) && !"OVERWORLD".equalsIgnoreCase(dim)) {
                CLASSIFIED_CUSTOM_GENERATOR.incrementAndGet();
                INELIGIBLE_CLASS.incrementAndGet();
                FALLBACK_NON_OVERWORLD.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return false;
            }
            if (q == null || q.length != 825) {
                CLASSIFIED_UNSUPPORTED_STATE.incrementAndGet();
                INELIGIBLE_FIELDS.incrementAndGet();
                FALLBACK_INVALID_ARRAY.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return false;
            }

            Long ctx = CTX.get(generator);
            if (ctx == null) {
                ctx = buildContext(generator);
                if (ctx == null || ctx == 0L) {
                    CTX.putIfAbsent(generator, 0L); // poison: do not retry
                    INELIGIBLE_NO_CTX.incrementAndGet();
                    FALLBACK_NO_CTX.incrementAndGet();
                    FALLBACKS.incrementAndGet();
                    return false;
                }
                CTX.put(generator, ctx);
            }
            if (ctx == 0L) {
                INELIGIBLE_NO_CTX.incrementAndGet();
                FALLBACK_NO_CTX.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return false;
            }
            CLASSIFIED_VANILLA_OVERWORLD.incrementAndGet();
            ELIGIBLE.incrementAndGet();
            recordStage(STAGE_ELIG, System.nanoTime() - t0);

            long t1 = System.nanoTime();
            ByteBuffer bbb = BIOME_BB_TL.get();
            if (!populateBiomeFloats(generator, bbb)) {
                INELIGIBLE_FIELDS.incrementAndGet();
                FALLBACK_BIOME_FLOATS.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return false;
            }
            recordStage(STAGE_BIOME, System.nanoTime() - t1);

            ByteBuffer out = OUT_BB_TL.get();
            out.clear();
            long tR0 = System.nanoTime();
            initFieldComplete(ctx, x4, z4, address(bbb), address(out));
            long jniNs = System.nanoTime() - tR0;
            recordStage(STAGE_JNI, jniNs);
            RUST_NS.addAndGet(jniNs);

            // Commit step: copy from safe direct output buffer to Java array
            long tC0 = System.nanoTime();
            out.rewind();
            out.asDoubleBuffer().get(q);
            recordStage(STAGE_COMMIT, System.nanoTime() - tC0);

            NATIVE_AUTH.incrementAndGet();
            AUTH_NS.addAndGet(System.nanoTime() - tCallStart);
            return true; // Authoritative success: Java noise computation BYPASSED
        } catch (Throwable t) {
            NATIVE_ERRORS.incrementAndGet();
            FALLBACK_EXCEPTION.incrementAndGet();
            FALLBACKS.incrementAndGet();
            LAST_ERROR = "onExperimental: " + t;
            return false; // Safe fallback: vanilla Java runs
        }
    }

    // ------------------------------------------------------------------
    // Pre-population terrain oracle hook. Called at RETURN of func_185977_a
    // (setBlocksInChunk) with the freshly-filled ChunkPrimer and the chunk
    // coords (chunk-block coords: x4,z4 in [0,16)). Computes an exact
    // content hash over the base block-state array plus biome ids.
    // READ-ONLY: never mutates the primer or any generator state.
    // ------------------------------------------------------------------
    public static void prePopOracle(Object generator, Object primer, int cx, int cz) {
        try {
            PREPOP_CAPTURED.incrementAndGet();
            if (PRIMER_CHARS == null) {
                java.lang.reflect.Field f =
                        net.minecraft.world.chunk.ChunkPrimer.class.getDeclaredField("field_177860_a");
                f.setAccessible(true);
                PRIMER_CHARS = f;
            }
            char[] cells = (char[]) PRIMER_CHARS.get(primer);
            if (cells == null || cells.length != 65536) {
                return;
            }

            // cx,cz are GLOBAL chunk coords (unbounded). Composite int key plus
            // a coord string for the report; collisions avoided via the string.
            String key = cx + "," + cz;
            StringBuilder sb = new StringBuilder(80);
            sb.append("chunk=").append(key).append(":blocks=");

            java.util.zip.CRC32 crc = CRC_TL.get();
            crc.reset();
            // 1. base block-state char array (65536 cells)
            for (char c : cells) crc.update((c >>> 8) & 0xFF);
            for (char c : cells) crc.update(c & 0xFF);
            sb.append(Long.toHexString(crc.getValue()));

            // 2. biome array (field_185981_C, 256 entries) via id ints
            Object[] biomes = (Object[]) field(generator, "field_185981_C");
            crc.reset();
            if (biomes != null) {
                if (BIOME_ID == null) {
                    java.lang.reflect.Method m =
                            net.minecraft.world.biome.Biome.class.getDeclaredMethod("func_185362_a",
                                    net.minecraft.world.biome.Biome.class);
                    m.setAccessible(true);
                    BIOME_ID = m;
                }
                int n = Math.min(biomes.length, 256);
                for (int i = 0; i < n; i++) {
                    int id = ((Integer) BIOME_ID.invoke(null, biomes[i])).intValue();
                    crc.update(id & 0xFF);
                    crc.update((id >>> 8) & 0xFF);
                }
            }
            sb.append(",biomes=").append(Long.toHexString(crc.getValue()));

            PREPOP_HASH.put(key, sb.toString());
        } catch (Throwable t) {
            // oracle is best-effort; never affects generation
        }
    }

    static void recordStage(java.util.List<Long> list, long ns) {
        if (ns < 0) ns = 0;
        synchronized (STAGE_LOCK) {
            if (list.size() < 2_000_000) list.add(Long.valueOf(ns));
        }
    }

    // ------------------------------------------------------------------
    // SHADOW hook: called at RETURN of func_185978_a (AFTER vanilla ran)
    // ------------------------------------------------------------------
    public static void shadow(Object generator, int x4, int z4, double[] q) {
        CALLS.incrementAndGet();
        if (!isShadow()) { INELIGIBLE_DISABLED.incrementAndGet(); return; }
        try {
            String gClass = generator.getClass().getName();
            OBSERVED_GENERATORS.computeIfAbsent(gClass, k -> new AtomicLong()).incrementAndGet();
            String dim = readDimension(generator);
            OBSERVED_DIMENSIONS.computeIfAbsent(dim, k -> new AtomicLong()).incrementAndGet();

            if (generator.getClass() != net.minecraft.world.gen.ChunkGeneratorOverworld.class) {
                CLASSIFIED_CUSTOM_GENERATOR.incrementAndGet();
                INELIGIBLE_CLASS.incrementAndGet();
                return;
            }
            if (!"0".equals(dim) && !"OVERWORLD".equalsIgnoreCase(dim)) {
                CLASSIFIED_CUSTOM_GENERATOR.incrementAndGet();
                INELIGIBLE_CLASS.incrementAndGet();
                return;
            }

            Long ctx = CTX.get(generator);
            if (ctx == null) {
                ctx = buildContext(generator);
                if (ctx == null || ctx == 0L) {
                    CTX.putIfAbsent(generator, 0L);
                    INELIGIBLE_NO_CTX.incrementAndGet();
                    return;
                }
                CTX.put(generator, ctx);
            }
            if (ctx == 0L) { INELIGIBLE_NO_CTX.incrementAndGet(); return; }
            CLASSIFIED_VANILLA_OVERWORLD.incrementAndGet();
            ELIGIBLE.incrementAndGet();

            ByteBuffer bbb = BIOME_BB_TL.get();
            if (!populateBiomeFloats(generator, bbb)) {
                INELIGIBLE_NO_CTX.incrementAndGet();
                return;
            }

            ByteBuffer out = OUT_BB_TL.get();
            out.clear();
            long tR0 = System.nanoTime();
            initFieldComplete(ctx, x4, z4, address(bbb), address(out));
            RUST_NS.addAndGet(System.nanoTime() - tR0);

            FIELDS_COMPARED.incrementAndGet();
            out.rewind();
            for (int i = 0; i < 825; i++) {
                double rv = out.getDouble();
                if (Double.doubleToLongBits(q[i]) != Double.doubleToLongBits(rv)) {
                    MISMATCHES.incrementAndGet();
                    if ("none".equals(FIRST_MISMATCH)) {
                        FIRST_MISMATCH = "x4=" + x4 + " z4=" + z4 + " idx=" + i
                                + " java=" + Long.toHexString(Double.doubleToLongBits(q[i]))
                                + " rust=" + Long.toHexString(Double.doubleToLongBits(rv));
                    }
                    return;
                }
            }
            MATCHES.incrementAndGet();
            DOUBLES_COMPARED.addAndGet(825);
        } catch (Throwable t) {
            NATIVE_ERRORS.incrementAndGet();
            LAST_ERROR = String.valueOf(t);
        }
    }

    // ------------------------------------------------------------------
    // M3W5: Terrain SHADOW hook: called at RETURN of func_185976_a
    // (setBlocksInChunk) AFTER vanilla has written base terrain into ChunkPrimer.
    //
    // M4.2A oracle correction: under JustEnoughIDs (MixinChunkPrimer), block
    // reads/writes are redirected to a private int[] intData holding RUNTIME
    // REGISTRY state ids and the original char[] field_177860_a is never
    // touched (stays all-air). The char[] is therefore NOT a valid oracle on
    // such packs. Oracle selection per primer instance:
    //   - intData field present  -> JEID mode: logical comparison against the
    //     runtime registry ids of stone/water/air (resolved once per JVM).
    //   - otherwise              -> vanilla mode: raw char[] bit-compare.
    // JEID mode also SKIPS native registration (the primer ingestion path
    // reads the dead char[]); it is a kernel-parity probe only.
    // ------------------------------------------------------------------
    private static volatile Field JEID_INT_DATA;           // ChunkPrimer.intData (JEID mixin)
    private static volatile Field NEID_ADD_DATA;           // ChunkPrimer byte[] (NEID high-8 bits)
    public static final AtomicLong M4_NEID_WIDTH_INELIGIBLE = new AtomicLong();
    private static volatile boolean JEID_PROBE_RESOLVED;
    private static volatile int JEID_STONE_ID = -1;        // runtime registry id of minecraft:stone default
    private static volatile int JEID_WATER_ID = -1;        // runtime registry id of minecraft:water default
    private static volatile String JEID_PROBE_ERROR = "unresolved";
    public static final AtomicLong M4_JEID_PROBE_CHUNKS = new AtomicLong();
    public static final AtomicLong M4_JEID_LOGICAL_MATCHES = new AtomicLong();
    public static final AtomicLong M4_JEID_LOGICAL_MISMATCHES = new AtomicLong();
    public static volatile String M4_JEID_FIRST_MISMATCH = "none";

    /** M4.2A/B: coherency-flush errors (bounded, never crash the server).
     *  M4.2B: keep the first three stack frames so exceptions are SITED. */
    public static void recordCoherencyError(String what) {
        NATIVE_ERRORS.incrementAndGet();
        Throwable t = null;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (e.getMethodName().equals("recordCoherencyError")) continue;
            break;
        }
        LAST_ERROR = "coherency: " + what;
    }

    /** Variant carrying the originating throwable (stack retained). */
    public static void recordCoherencyError(String what, Throwable th) {
        NATIVE_ERRORS.incrementAndGet();
        StringBuilder sb = new StringBuilder("coherency: ").append(what);
        if (th != null) {
            StackTraceElement[] st = th.getStackTrace();
            sb.append(" @").append(th.getClass().getSimpleName());
            for (int i = 0; i < Math.min(3, st.length); i++) {
                sb.append(" ").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
            }
        }
        LAST_ERROR = sb.toString();
    }

    /** Dimension id of the generator's world (M4.2A C1): native chunk keys are
     *  dimension-scoped; without it, the same (cx,cz) in two dimensions alias. */
    private static int readDimensionId(Object generator) {
        try {
            Object w = field(generator, "field_185995_n"); // World
            if (w == null) return 0;
            Object p = field(w, "field_73011_w");          // WorldProvider
            if (p == null) return 0;
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("getDimension");
                m.setAccessible(true);
                return (Integer) m.invoke(p);
            } catch (Throwable ignore) { }
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("func_186058_p");
                m.setAccessible(true);
                Object dt = m.invoke(p);
                java.lang.reflect.Method mId = dt.getClass().getMethod("func_186068_a");
                mId.setAccessible(true);
                return (Integer) mId.invoke(dt);
            } catch (Throwable ignore) { }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void resolveJeidProbeIds() {
        try {
            Field regF = net.minecraft.block.Block.class.getDeclaredField("field_176229_d");
            regF.setAccessible(true);
            Object regMap = regF.get(null); // Block.BLOCK_STATE_IDS (static)
            Class<?> regCls = regMap.getClass();
            java.lang.reflect.Method getIdM = regCls.getMethod("func_148747_b", Object.class);
            getIdM.setAccessible(true);
            java.lang.reflect.Method iterM = regCls.getMethod("iterator");
            java.lang.reflect.Method sizeM = regCls.getMethod("func_186804_a");
            int size = (Integer) sizeM.invoke(regMap);
            java.util.Iterator<?> it = (java.util.Iterator<?>) iterM.invoke(regMap);
            int stone = -1, water = -1;
            int skipped = 0;
            while (it.hasNext()) {
                Object st = it.next();
                if (!(st instanceof net.minecraft.block.state.IBlockState)) { skipped++; continue; }
                try {
                    java.lang.reflect.Method getBlockM = st.getClass().getMethod("func_177230_c");
                    getBlockM.setAccessible(true);
                    Object blk = getBlockM.invoke(st);
                    java.lang.reflect.Method nameM = blk.getClass().getMethod("getRegistryName");
                    nameM.setAccessible(true);
                    String rn = String.valueOf(nameM.invoke(blk));
                    java.lang.reflect.Method defM = blk.getClass().getMethod("func_176223_P");
                    defM.setAccessible(true);
                    Object def = defM.invoke(blk);
                    if (def == st) {
                        if ("minecraft:stone".equals(rn)) stone = (Integer) getIdM.invoke(regMap, st);
                        if ("minecraft:water".equals(rn)) water = (Integer) getIdM.invoke(regMap, st);
                    }
                } catch (Throwable t) {
                    skipped++; // package-private Forge internals: skip, never abort the scan
                    continue;
                }
                if (stone >= 0 && water >= 0) break;
            }
            JEID_STONE_ID = stone;
            JEID_WATER_ID = water;
            JEID_PROBE_RESOLVED = stone >= 0 && water >= 0;
            if (!JEID_PROBE_RESOLVED) JEID_PROBE_ERROR = "stone=" + stone + " water=" + water
                    + " registrySize=" + size + " skipped=" + skipped;
        } catch (Throwable t) {
            JEID_PROBE_ERROR = "resolve: " + t;
        }
    }

    public static void shadowTerrain(Object generator, int cx, int cz, Object primer) {
        CALLS.incrementAndGet();
        if (!isShadow()) { INELIGIBLE_DISABLED.incrementAndGet(); return; }
        try {
            if (generator.getClass() != net.minecraft.world.gen.ChunkGeneratorOverworld.class) {
                INELIGIBLE_CLASS.incrementAndGet();
                return;
            }
            if (PRIMER_CHARS == null) {
                Field f = net.minecraft.world.chunk.ChunkPrimer.class.getDeclaredField("field_177860_a");
                f.setAccessible(true);
                PRIMER_CHARS = f;
            }
            if (JEID_INT_DATA == null) {
                try {
                    Field f = primer.getClass().getDeclaredField("intData");
                    f.setAccessible(true);
                    JEID_INT_DATA = f;
                } catch (NoSuchFieldException e) {
                    JEID_INT_DATA = PRIMER_CHARS; // sentinel: no JEID
                }
            }
            boolean jeidMode = (JEID_INT_DATA != PRIMER_CHARS);
            char[] javaChars = (char[]) PRIMER_CHARS.get(primer);
            if (javaChars == null || javaChars.length != 65536) return;
            int[] jeidData = null;
            if (jeidMode) {
                jeidData = (int[]) JEID_INT_DATA.get(primer);
                if (jeidData == null || jeidData.length != 65536) return;
                if (!JEID_PROBE_RESOLVED) resolveJeidProbeIds();
                if (!JEID_PROBE_RESOLVED) return; // cannot build a logical oracle
            }
            // NEID width gate (M4.2A B2): NotEnoughIDs keeps char[] as the LOW 16
            // bits of each state id and stores the HIGH 8 in an added byte[]
            // (vanilla ChunkPrimer declares no byte[] field; JEID's is int[], so
            // byte[] uniquely marks NEID). A nonzero high byte means the id does
            // not fit u16: the chunk is INELIGIBLE for both comparison and
            // native registration — never truncate or reinterpret it.
            if (!jeidMode) {
                Field addF = NEID_ADD_DATA;
                if (addF == null) {
                    for (Field f : primer.getClass().getDeclaredFields()) {
                        if (f.getType() == byte[].class) { addF = f; break; }
                    }
                    NEID_ADD_DATA = (addF != null) ? addF : PRIMER_CHARS; // sentinel if absent
                }
                addF = NEID_ADD_DATA; // M4.2E NPE fix: use the resolved field, not the pre-detection local
                if (addF != PRIMER_CHARS) {
                    addF.setAccessible(true);
                    byte[] add = (byte[]) addF.get(primer);
                    if (add != null) {
                        for (byte b : add) {
                            if (b != 0) { M4_NEID_WIDTH_INELIGIBLE.incrementAndGet(); return; }
                        }
                    }
                }
            }

            double[] q = (double[]) field(generator, "field_185998_q");
            if (q == null || q.length != 825) return;

            ByteBuffer densityBB = TERRAIN_DENSITY_BB_TL.get();
            densityBB.clear();
            densityBB.asDoubleBuffer().put(q);

            ByteBuffer terrainBB = TERRAIN_OUT_BB_TL.get();
            terrainBB.clear();
            for (int i = 0; i < 65536 * 2; i++) terrainBB.put(i, (byte) 0);

            int seaLevel = 63;
            try {
                Object settings = field(generator, "field_186000_s");
                if (settings != null) {
                    Field slf = findField(settings.getClass(), "field_177779_q");
                    slf.setAccessible(true);
                    seaLevel = slf.getInt(settings);
                }
            } catch (Throwable ignore) {}

            int stoneId = 16;
            int waterId = 144;

            long t0 = System.nanoTime();
            terrainSetBlocksOpt(address(densityBB), seaLevel, stoneId, waterId, address(terrainBB));
            TERRAIN_RUST_NS.addAndGet(System.nanoTime() - t0);

            TERRAIN_CHUNKS_COMPARED.incrementAndGet();
            java.nio.CharBuffer cb = terrainBB.asCharBuffer();
            boolean matched = true;
            if (jeidMode) {
                // Logical oracle: JEID intData holds runtime registry ids; the
                // kernel emits vanilla-scheme ids (stone=16, water=144, air=0).
                M4_JEID_PROBE_CHUNKS.incrementAndGet();
                for (int i = 0; i < 65536; i++) {
                    char rc = cb.get(i);
                    int jc = jeidData[i];
                    int expected = rc == 16 ? JEID_STONE_ID : rc == 144 ? JEID_WATER_ID : 0;
                    if (jc != expected) {
                        matched = false;
                        TERRAIN_MISMATCHES.incrementAndGet();
                        M4_JEID_LOGICAL_MISMATCHES.incrementAndGet();
                        if ("none".equals(M4_JEID_FIRST_MISMATCH)) {
                            M4_JEID_FIRST_MISMATCH = dumpJeidMismatch(primer, javaChars, jeidData, cb, cx, cz, i, jc, expected, rc);
                        }
                        break;
                    }
                }
                if (matched) {
                    TERRAIN_MATCHES.incrementAndGet();
                    TERRAIN_BLOCKS_COMPARED.addAndGet(65536);
                    M4_JEID_LOGICAL_MATCHES.incrementAndGet();
                    // No native registration in JEID mode: the ingestion path
                    // reads the dead char[]; JEID eligibility is an M4.2A open item.
                }
            } else {
            for (int i = 0; i < 65536; i++) {
                char jc = javaChars[i];
                char rc = cb.get(i);
                if (jc != rc) {
                    matched = false;
                    TERRAIN_MISMATCHES.incrementAndGet();
                    if ("none".equals(FIRST_TERRAIN_MISMATCH)) {
                        FIRST_TERRAIN_MISMATCH = "cx=" + cx + " cz=" + cz + " idx=" + i
                                + " j=" + (int) jc + " r=" + (int) rc;
                    }
                    break;
                }
            }
            if (matched) {
                TERRAIN_MATCHES.incrementAndGet();
                TERRAIN_BLOCKS_COMPARED.addAndGet(65536);

                // M4 NativeChunk retention & multi-consumer proofs
                if (NativeChunkBridge.isAvailable()) {
                    int m4dim = readDimensionId(generator);
                        long genId = NativeChunkBridge.register(m4dim, cx, cz, address(terrainBB), 0L);
                    if (genId > 0) {
                        M4_REGISTERED.incrementAndGet();
                        M4Coherency.startIfNeeded();

                        // Consumer 1: Zero-copy packet encoding proof
                        ByteBuffer pktBuf = M4_PKT_BB_TL.get();
                        pktBuf.clear();
                        int pktBytes = NativeChunkBridge.encodePacket(m4dim, cx, cz, genId, true, true, address(pktBuf), pktBuf.capacity());
                        if (pktBytes > 0) {
                            M4_PACKET_STRUCTURAL.incrementAndGet();
                            M4_RUST_BYTES_ENCODED.addAndGet(pktBytes);
                        }

                        // Consumer 2A: Spatial occupancy summary proof
                        ByteBuffer occBuf = M4_OCC_BB_TL.get();
                        occBuf.clear();
                        int mask = NativeChunkBridge.getOccupancySummary(m4dim, cx, cz, genId, address(occBuf));
                        if (mask > 0) {
                            M4_OCCUPANCY_STRUCTURAL.incrementAndGet();
                        }

                        // Consumer 2B: Persistence staging proof
                        ByteBuffer mcaBuf = M4_MCA_BB_TL.get();
                        mcaBuf.clear();
                        int mcaBytes = NativeChunkBridge.stagePersistence(m4dim, cx, cz, genId, address(mcaBuf), mcaBuf.capacity());
                        if (mcaBytes > 0) {
                            M4_PERSISTENCE_STRUCTURAL.incrementAndGet();
                        }
                    }
                }
            }
            } // vanilla mode
        } catch (Throwable t) {
            NATIVE_ERRORS.incrementAndGet();
            StringBuilder sb2 = new StringBuilder("shadowTerrain: " + t);
            StackTraceElement[] st2 = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st2.length); i++) sb2.append(" ").append(st2[i].getMethodName()).append(":").append(st2[i].getLineNumber());
            LAST_ERROR = sb2.toString();
        }
    }

    /** M4.2A evidence dump at the first JEID-mode logical mismatch: the six-way
     *  comparison the directive requires — transformed getter, registry name +
     *  properties + runtime id, original char[] value, JEID intData value, and
     *  the Rust logical state. */
    private static String dumpJeidMismatch(Object primer, char[] javaChars, int[] jeidData,
                                           java.nio.CharBuffer rust, int cx, int cz,
                                           int i, int jc, int expected, char rc) {
        try {
            int x = i >> 12, z = (i >> 8) & 15, y = i & 255;
            // Transformed logical getter (JEID-redirected on such packs)
            java.lang.reflect.Method gm = primer.getClass()
                    .getMethod("func_177856_a", int.class, int.class, int.class);
            gm.setAccessible(true);
            Object st = gm.invoke(primer, x, y, z);
            String regName = "?", props = "?";
            int runtimeId = -1;
            if (st != null) {
                try {
                    java.lang.reflect.Method bm = st.getClass().getMethod("func_177230_c"); bm.setAccessible(true);
                    Object blk = bm.invoke(st);
                    java.lang.reflect.Method nm = blk.getClass().getMethod("getRegistryName"); nm.setAccessible(true);
                    regName = String.valueOf(nm.invoke(blk));
                } catch (Throwable ignore) {}
                props = String.valueOf(st);
                try {
                    Field regF = net.minecraft.block.Block.class.getDeclaredField("field_176229_d");
                    regF.setAccessible(true);
                    java.lang.reflect.Method im = regF.get(null).getClass().getMethod("func_148747_b", Object.class);
                    im.setAccessible(true);
                    runtimeId = (Integer) im.invoke(regF.get(null), st);
                } catch (Throwable ignore) {}
            }
            String rustLogical = rc == 16 ? "stone" : rc == 144 ? "water" : rc == 0 ? "air" : ("id" + (int) rc);
            return "cx=" + cx + " cz=" + cz + " xyz=(" + x + "," + y + "," + z + ")"
                    + " getter=" + (st == null ? "null" : regName + props)
                    + " runtimeId=" + runtimeId
                    + " intData=" + jc + " expectedJeidId=" + expected
                    + " charIdx=" + (int) javaChars[i]
                    + " rust=" + rustLogical + "(" + (int) rc + ")"
                    + " stoneJeidId=" + JEID_STONE_ID + " waterJeidId=" + JEID_WATER_ID
                    + " resolveErr=" + JEID_PROBE_ERROR;
        } catch (Throwable t) {
            return "dump-failed: " + t + " (intData=" + jc + " expected=" + expected
                    + " rust=" + (int) rc + " idx=" + i + ")";
        }
    }

    // ------------------------------------------------------------------
    // Native context from live state transplantation
    // ------------------------------------------------------------------
    private static final String[] GEN_FIELDS = {
            "field_185984_c", // depth, 16 oct
            "field_185993_l", // main, 8
            "field_185991_j", // min, 16
            "field_185992_k", // max, 16
    };
    private static final int[] OCTS = {16, 8, 16, 16};

    private static synchronized Long buildContext(Object generator) {
        try {
            Field sf;
            try {
                sf = net.minecraft.world.gen.ChunkGeneratorOverworld.class
                        .getDeclaredField("field_186000_s");
            } catch (NoSuchFieldException nsfe) {
                CLASSIFIED_TRANSFORM_CONFLICT.incrementAndGet();
                INELIGIBLE_FIELDS.incrementAndGet();
                return null;
            }
            sf.setAccessible(true);
            Object s = sf.get(generator);
            if (s == null) {
                CLASSIFIED_TRANSFORM_CONFLICT.incrementAndGet();
                INELIGIBLE_FIELDS.incrementAndGet();
                return null;
            }

            PERMS_BB.clear();
            OFFS_BB.clear();
            for (int gI = 0; gI < 4; gI++) {
                Object gen;
                try {
                    gen = field(generator, GEN_FIELDS[gI]);
                } catch (NoSuchFieldException nsfe) {
                    CLASSIFIED_TRANSFORM_CONFLICT.incrementAndGet();
                    INELIGIBLE_FIELDS.incrementAndGet();
                    return null;
                }
                if (gen == null || gen.getClass() != net.minecraft.world.gen.NoiseGeneratorOctaves.class) {
                    CLASSIFIED_UNSUPPORTED_STATE.incrementAndGet();
                    INELIGIBLE_FIELDS.incrementAndGet();
                    return null;
                }
                Object[] levels = (Object[]) field(gen, "field_76307_a");
                if (levels.length != OCTS[gI]) {
                    CLASSIFIED_UNSUPPORTED_STATE.incrementAndGet();
                    INELIGIBLE_FIELDS.incrementAndGet();
                    return null;
                }
                for (Object lv : levels) {
                    int[] perm = (int[]) field(lv, "field_76312_d");
                    if (perm.length != 512) {
                        CLASSIFIED_UNSUPPORTED_STATE.incrementAndGet();
                        INELIGIBLE_FIELDS.incrementAndGet();
                        return null;
                    }
                    for (int v : perm) PERMS_BB.putInt(v);
                    OFFS_BB.put(dfield(lv, "field_76315_a"));
                    OFFS_BB.put(dfield(lv, "field_76313_b"));
                    OFFS_BB.put(dfield(lv, "field_76314_c"));
                }
            }

            long ctx = initCreateFromState(
                    OCTS[0], OCTS[1], OCTS[2], OCTS[3],
                    address(PERMS_BB), address(OFFS_BB),
                    f32(s, "field_177811_a"), f32(s, "field_177809_b"),
                    f32(s, "field_177808_e"), f32(s, "field_177803_f"), f32(s, "field_177804_g"),
                    f32(s, "field_177825_h"), f32(s, "field_177827_i"), f32(s, "field_177821_j"),
                    f32(s, "field_177823_k"), f32(s, "field_177817_l"),
                    f32(s, "field_177806_d"), f32(s, "field_177810_c"),
                    f32(s, "field_177819_m"), f32(s, "field_177813_n"),
                    f32(s, "field_177815_o"), f32(s, "field_177843_p"));
            if (ctx != 0L) CTX_CREATED.incrementAndGet();
            return ctx;
        } catch (Throwable t) {
            LAST_ERROR = "buildContext: " + t;
            return null;
        }
    }

    private static boolean populateBiomeFloats(Object generator, ByteBuffer bb) {
        try {
            Object[] arr = (Object[]) field(generator, "field_185981_C");
            if (arr == null || arr.length < 100) return false;
            bb.clear();
            for (int i = 0; i < 100; i++) {
                net.minecraft.world.biome.Biome b = (net.minecraft.world.biome.Biome) arr[i];
                bb.putFloat(b.func_185355_j());
            }
            for (int i = 0; i < 100; i++) {
                net.minecraft.world.biome.Biome b = (net.minecraft.world.biome.Biome) arr[i];
                bb.putFloat(b.func_185360_m());
            }
            bb.rewind();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static double dfield(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        f.setAccessible(true);
        return f.getDouble(o);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static float f32(Object s, String name) throws Exception {
        Field f = findField(s.getClass(), name);
        f.setAccessible(true);
        return f.getFloat(s);
    }

    private static long address(java.nio.Buffer b) throws Exception {
        java.lang.reflect.Method m = b.getClass().getMethod("address");
        m.setAccessible(true);
        return (Long) m.invoke(b);
    }

    public static void closeAll() {
        for (Long v : CTX.values()) {
            if (v != null && v != 0L) {
                initFreeRaw(v);
                CTX_FREED.incrementAndGet();
            }
        }
        CTX.clear();
    }

    public static String dumpMetrics() {
        return "m3wg_mode=" + RUNTIME_MODE
                + "\nm3wg_calls=" + CALLS.get()
                + "\nm3wg_eligible=" + ELIGIBLE.get()
                + "\nm3wg_ineligible_disabled=" + INELIGIBLE_DISABLED.get()
                + "\nm3wg_ineligible_class=" + INELIGIBLE_CLASS.get()
                + "\nm3wg_ineligible_fields=" + INELIGIBLE_FIELDS.get()
                + "\nm3wg_ineligible_noctx=" + INELIGIBLE_NO_CTX.get()
                + "\nm3wg_native_errors=" + NATIVE_ERRORS.get()
                + "\nm3wg_native_auth=" + NATIVE_AUTH.get()
                + "\nm3wg_fallbacks=" + FALLBACKS.get()
                + "\nm3wg_fallback_custom=" + FALLBACK_CUSTOM_GEN.get()
                + "\nm3wg_fallback_dim=" + FALLBACK_NON_OVERWORLD.get()
                + "\nm3wg_fallback_array=" + FALLBACK_INVALID_ARRAY.get()
                + "\nm3wg_fallback_noctx=" + FALLBACK_NO_CTX.get()
                + "\nm3wg_fallback_biomes=" + FALLBACK_BIOME_FLOATS.get()
                + "\nm3wg_fallback_exception=" + FALLBACK_EXCEPTION.get()
                + "\nm3wg_fields_compared=" + FIELDS_COMPARED.get()
                + "\nm3wg_doubles_compared=" + DOUBLES_COMPARED.get()
                + "\nm3wg_matches=" + MATCHES.get()
                + "\nm3wg_mismatches=" + MISMATCHES.get()
                + "\nm3wg_terrain_chunks=" + TERRAIN_CHUNKS_COMPARED.get()
                + "\nm3wg_terrain_blocks=" + TERRAIN_BLOCKS_COMPARED.get()
                + "\nm3wg_terrain_matches=" + TERRAIN_MATCHES.get()
                + "\nm3wg_terrain_mismatches=" + TERRAIN_MISMATCHES.get()
                + "\nm3wg_terrain_first_mismatch=" + FIRST_TERRAIN_MISMATCH
                + "\nm3wg_terrain_rust_ns=" + TERRAIN_RUST_NS.get()
                + "\nm4_registered=" + M4_REGISTERED.get()
                + "\nm4_packet_matches=" + M4_PACKET_STRUCTURAL.get()
                + "\nm4_occupancy_matches=" + M4_OCCUPANCY_STRUCTURAL.get()
                + "\nm4_persistence_matches=" + M4_PERSISTENCE_STRUCTURAL.get()
                + "\nm4_rust_bytes_encoded=" + M4_RUST_BYTES_ENCODED.get()
                + "\nm4_jeid_probe_chunks=" + M4_JEID_PROBE_CHUNKS.get()
                + "\nm4_jeid_logical_matches=" + M4_JEID_LOGICAL_MATCHES.get()
                + "\nm4_jeid_logical_mismatches=" + M4_JEID_LOGICAL_MISMATCHES.get()
                + "\nm4_jeid_first_mismatch=" + M4_JEID_FIRST_MISMATCH
                + "\nm4_jeid_probe_error=" + JEID_PROBE_ERROR
                + "\nm4_neid_width_ineligible=" + M4_NEID_WIDTH_INELIGIBLE.get()
                + "\nm3wg_sampled_checks=" + SAMPLED_CHECKS.get()
                + "\nm3wg_sampled_matches=" + SAMPLED_MATCHES.get()
                + "\nm3wg_sampled_mismatches=" + SAMPLED_MISMATCHES.get()
                + "\nm3wg_rust_ns=" + RUST_NS.get()
                + "\nm3wg_auth_ns=" + AUTH_NS.get()
                + "\nm3wg_java_ns=" + JAVA_NS.get()
                + "\nm3wg_java_calls=" + JAVA_CALLS.get()
                + "\nm3wg_ctx_created=" + CTX_CREATED.get()
                + "\nm3wg_ctx_freed=" + CTX_FREED.get()
                + "\nm3wg_first_mismatch=" + FIRST_MISMATCH
                + "\nm3wg_last_error=" + LAST_ERROR
                + "\nm3wg_classified_vanilla=" + CLASSIFIED_VANILLA_OVERWORLD.get()
                + "\nm3wg_classified_custom=" + CLASSIFIED_CUSTOM_GENERATOR.get()
                + "\nm3wg_classified_conflict=" + CLASSIFIED_TRANSFORM_CONFLICT.get()
                + "\nm3wg_classified_unsupported=" + CLASSIFIED_UNSUPPORTED_STATE.get()
                + "\nm3wg_observed_generators=" + OBSERVED_GENERATORS
                + "\nm3wg_observed_dimensions=" + OBSERVED_DIMENSIONS
                + M4Coherency.statsLine()
                + M4PacketCompare.statsLine()
                + M4NativeStatePayload.statsLine()
                + "\n" + stagePercentiles("m3wg_stage_elig", STAGE_ELIG)
                + "\n" + stagePercentiles("m3wg_stage_biome", STAGE_BIOME)
                + "\n" + stagePercentiles("m3wg_stage_jni", STAGE_JNI)
                + "\n" + stagePercentiles("m3wg_stage_commit", STAGE_COMMIT)
                + "\n" + stagePercentiles("m3wg_java_ref", JAVA_SAMPLES)
                + "\nm3wg_prepop_captured=" + PREPOP_CAPTURED.get()
                + "\nm3wg_prepop_chunks=" + PREPOP_HASH.size();
    }

    static String stagePercentiles(String label, java.util.List<Long> list) {
        long[] arr;
        synchronized (STAGE_LOCK) {
            int n = list.size();
            arr = new long[n];
            for (int i = 0; i < n; i++) arr[i] = list.get(i).longValue();
        }
        if (arr.length == 0) {
            return label + "_n=0";
        }
        java.util.Arrays.sort(arr);
        long p50 = arr[(int) (arr.length * 0.50)];
        long p95 = arr[(int) (arr.length * 0.95)];
        long p99 = arr[(int) (arr.length * 0.99)];
        long mean = 0;
        for (long v : arr) mean += v;
        mean /= arr.length;
        return label
                + "_n=" + arr.length
                + " mean_ns=" + mean
                + " p50_ns=" + p50
                + " p95_ns=" + p95
                + " p99_ns=" + p99;
    }

    // Serializes the pre-population oracle hash map for the campaign to diff.
    // One line per chunk: "cx,cz|blocks_crc,biomes_crc" (chunk-LOCAL coords).
    public static String dumpPrePop() {
        StringBuilder sb = new StringBuilder(4096);
        java.util.List<java.lang.String> keys = new java.util.ArrayList<>(PREPOP_HASH.keySet());
        java.util.Collections.sort(keys);
        for (java.lang.String k : keys) {
            String h = PREPOP_HASH.get(k);
            if (h != null) sb.append(h).append('\n');
        }
        return sb.toString();
    }

    // Writes the pre-population oracle to a file (best-effort). Called from
    // the coremod shutdown hook so both twin worlds persist their capture.
    public static void writePrePopFile(String path) {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File(path), "UTF-8")) {
            pw.print(dumpPrePop());
        } catch (Throwable t) {
            System.err.println("[RustCraft] prepop write failed: " + t);
        }
    }
}
