package com.rustcraft.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — the coarse ON seam hook.
 *
 * Injected at the HEAD of World.checkLight (SRG func_175664_x, notch
 * amu.w, (BlockPos)Z) — the coarse per-mutation entry (goal §14-§15).
 * onCheckLight returns -1 (run the original body = Java/Phosphor owns the
 * whole job) or 0/1 (Rust owned the BLOCK job; the caller returns the
 * packed SKY flag | true — the SKY half already ran inside the hook via
 * the same checkLightFor the original body would call, preserving the
 * provider.hasSkyLight() guard proven in the 2847 bytes).
 *
 * Authority model (goal §4-§15):
 * - SKY: NEVER owned. Routed through the live checkLightFor (vanilla, or
 *   Phosphor's cancelled version on Gate C — each shape's own SKY path).
 * - One JNI crossing per job: stage the ±17 cell box (state id + current
 *   light) into ONE direct buffer, call LightAuthorityBridge.runJob, commit
 *   the diff grid via World.setLightFor.
 * - §12 whole-job eligibility: every staged state id must be classified
 *   STATIC_LIGHT_SEMANTICS before staging completes; anything dynamic or
 *   unknown fails the WHOLE job back to Java (no per-cell callbacks).
 * - §11 classification: block classes that do not override Forge's
 *   world-aware Block.getLightOpacity/getLightValue are static by
 *   construction; overriders are probed at 3 positions and must agree.
 * - §13 generation safety: the job is single-threaded on the server thread
 *   inside one checkLight call — chunk unload cannot interleave; the
 *   unload/reload gate is §25's campaign test (no stale job can outlive
 *   its own stack frame by construction).
 * - §14 unloaded neighbors: replicate vanilla's own guard —
 *   isAreaLoaded(pos, 16, false) false -> fallback (vanilla body then
 *   no-ops identically).
 * - §22 sampling: rustcraft.lightSampleRate of eligible jobs defer to
 *   vanilla WITH the shadow comparator active (WorldLightHook), keeping
 *   live oracle validation inside ON campaigns.
 */
public final class LightAuthorityHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.lightExperiment");
    public static final String MODE =
            System.getProperty("rustcraft.lightMode", "SHADOW");
    public static final boolean ON = "ON_EXPERIMENTAL".equals(MODE);
    /** §22 sampling rate for eligible jobs -> vanilla+shadow deferral. */
    public static final double SAMPLE_RATE =
            Double.parseDouble(System.getProperty("rustcraft.lightSampleRate",
                    "0.05"));
    /** §15 hard bounds. */
    public static final int BOX = 24;          // staged radius = authority bound
    public static final int GROW_STEP = 12;    // growth step per OOB escape
    public static final int MAX_RADIUS = 24;   // §15 hard cap: cascades wider
    // than this fall back to vanilla (the dev-ON-24/25 growth loop proved
    // worldgen relight chains DO exceed 15-24; a Java-reflection staging of
    // a >24 box eats 25s+ settle windows, so the cap keeps job latency
    // bounded — fallback, not truncation, is the §15 overflow answer)
    // (existing light gradients from OTHER sources let a correction
    // cascade walk past any small fixed box — dev-ON-21/24 OOB at
    // origin-18/-21 on worldgen relight chains; growth-before-fallback
    // honors §15's never-truncate rule)
    public static final int MAX_STATES = 1 << 16;

    public static final AtomicLong JOBS = new AtomicLong();
    public static final AtomicLong ADMITTED = new AtomicLong();
    public static final AtomicLong FALLBACKS = new AtomicLong();
    public static final AtomicLong FALLBACK_UNLOADED = new AtomicLong();
    public static final AtomicLong FALLBACK_BOUNDS = new AtomicLong();
    public static final AtomicLong FALLBACK_DYNAMIC = new AtomicLong();
    public static final AtomicLong FALLBACK_ERROR = new AtomicLong();
    public static final AtomicLong SAMPLE_DEFERRED = new AtomicLong();
    public static final AtomicLong COMMITTED_CELLS = new AtomicLong();
    public static final AtomicLong SKY_CALLS = new AtomicLong();
    public static final AtomicLong JNI_NANOS = new AtomicLong();
    public static final AtomicLong STAGE_NANOS = new AtomicLong();
    public static final AtomicLong STAGE_CLASSIFY_NANOS = new AtomicLong();
    public static final AtomicLong STAGE_STATE_NANOS = new AtomicLong();
    public static final AtomicLong STAGE_LIGHT_NANOS = new AtomicLong();
    public static final AtomicLong STAGE_BUF_NANOS = new AtomicLong();
    public static final AtomicLong COMMIT_NANOS = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static volatile String LAST_ERROR = "";

    // -- reflection surface (all derived from live objects; no Class.forName
    //    by mod/MC names — the hook is App-loader-loaded, parent delegation
    //    gives it none of the runtime classes) --
    private static volatile boolean RESOLVED;
    private static Method M_CHECK_LIGHT_FOR; // World.checkLightFor(EnumSkyBlock, BlockPos)Z
    private static Method M_GET_LIGHT_FOR;   // World.getLightFor(EnumSkyBlock, BlockPos)I
    private static Method M_SET_LIGHT_FOR;   // World.setLightFor(EnumSkyBlock, BlockPos, int)V
    private static Method M_GET_BLOCK_STATE; // World.getBlockState(BlockPos)
    private static Method M_IS_AREA_LOADED;  // World.isAreaLoaded(BlockPos, int, boolean)Z
    private static Method M_GET_X, M_GET_Y, M_GET_Z;
    private static Method M_LIGHT_VALUE, M_LIGHT_OPACITY; // Forge world-aware on Block
    private static Method M_HAS_SKY_LIGHT;   // WorldProvider.hasSkyLight()Z
    private static Field F_PROVIDER;         // World.provider (Forge literal name)
    private static Object BLOCK_TYPE;        // EnumSkyBlock BLOCK constant
    private static Object SKY_TYPE;          // EnumSkyBlock SKY constant
    private static Constructor<?> POS_CTOR;
    private static Map<Object, Integer> STATE_IDS; // IBlockState -> id cache
    private static Object REGISTRY;                // BLOCK_STATE_IDS (RegistryNamespaced)
    private static Method M_STATE_ID;              // registry.getID(state)I

    // §10 session light table: stateId -> {opacity, emission}; absent =
    // unclassified (whole-job fallback per §12)
    private static final Map<Integer, byte[]> LIGHT_TABLE = new HashMap<>();
    private static final Map<Integer, Object> STATE_BY_SID = new HashMap<>();
    private static final Map<Object, byte[]> CLASSIFIED = new HashMap<>();

    private static final java.util.Random RNG = new java.util.Random();

    private LightAuthorityHook() { }

    /** Injected at checkLight HEAD. -1 = original body; else the boolean
     *  value checkLight should return (Rust owned BLOCK + SKY handled). */
    public static int onCheckLight(Object world, Object pos) {
        if (!ENABLED || !ON) return -1;
        try {
            ensureSurface(world, pos);
            // §14: replicate vanilla's own loaded guard exactly
            if (!(Boolean) M_IS_AREA_LOADED.invoke(world, pos, BOX, false)) {
                FALLBACK_UNLOADED.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return -1;
            }
            // §14/§15: world-height bounds (staging halo must fit at cap)
            int y = (Integer) M_GET_Y.invoke(pos);
            if (y - MAX_RADIUS - 1 < 0 || y + MAX_RADIUS + 1 > 255) {
                FALLBACK_BOUNDS.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return -1;
            }
            // §22 sampling: defer an honest slice of eligible jobs to
            // vanilla WITH the shadow comparator watching
            if (SAMPLE_RATE > 0 && RNG.nextDouble() < SAMPLE_RATE) {
                SAMPLE_DEFERRED.incrementAndGet();
                FALLBACKS.incrementAndGet();
                return -1;
            }

            JOBS.incrementAndGet();
            int x = (Integer) M_GET_X.invoke(pos);
            int z = (Integer) M_GET_Z.invoke(pos);
            long t0 = System.nanoTime();

            // ZERO-STAGING PATH (RUST_BLOCK_LIGHT_ZERO_STAGING): ONE JNI
            // call with a minimal descriptor — NO Java section snapshot,
            // NO state-id buffer, NO light buffer. Rust resolves the
            // world-lifecycle NativeChunk registry directly (frontier
            // discovers chunks lazily), commits block light natively,
            // marks sections dirty (packet/wire invalidation), and returns
            // the diff; the Java mirror below applies the same values via
            // setLightFor (the M4 refresh model is Java→Rust: Java's EBS
            // arrays must see them or the next refresh overwrites native).
            // Falls back (fail closed, original Java/Phosphor path) on any
            // ZS_ERR_*: missing chunk, unknown state, capacity, stale gen.
                if (com.rustcraft.bridge.NativeChunkRegistryHook.ENABLED) {
                    ensureSurface(world, pos); // light-method reflection only
                    ensureFullTable(world); // complete opacity/emission table
                    // §8 TRUE ZERO-STAGING: NO refreshChunkNow on the light
                    // path. Block STATE coherence now lands at the MUTATION
                    // SEAM (ChunkMutationTracker.onBlockSet -> mirrorBlockState
                    // — one cell, one version advance) and LIGHT coherence at
                    // the EBS write seam (onEbsBlockLightSet -> mirrorBlockLight),
                    // so the NativeChunk is ALREADY coherent before this job.
                    // (History: po7 removed this call when only the light
                    // mirror existed and every job went rc=0 — the state
                    // seam mirror is what makes removal correct now.)
                    // Reload-repair (§16/§17) stays RECOVERY-ONLY: a LOADED
                    // Java chunk absent from the registry is an invariant
                    // violation (reload path misses onLoad — cmp8); a clean
                    // gate run shows zsRepairs=0.
                    try {
                        Object chunk = M_WORLD_GET_CHUNK.invoke(world,
                                x >> 4, z >> 4);
                        int zsDim2 = dimOf(world);
                        if (chunk != null && NativeChunkBridge.findGeneration(
                                zsDim2, x >> 4, z >> 4) <= 0) {
                            NativeChunkRegistryHook.repairRegister(chunk, zsDim2,
                                    x >> 4, z >> 4);
                            ZS_REPAIRS.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        LAST_ERROR = "preRefresh: " + t;
                    }
                int dim = worldDim(world);
                java.nio.ByteBuffer zsTable = buildTable();
                java.nio.ByteBuffer zsOut = java.nio.ByteBuffer
                        .allocateDirect(4 * 1024 * 1024)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                // §2 SAME-JOB COMPARISON HARNESS (bounded, gated on
                // COMPARE_ANCHOR = the mutation anchor): staged oracle
                // COMPUTE-ONLY vs zero-stage COMPUTE-ONLY on THIS
                // invocation — neither commits during comparison; the
                // real zero-stage run below is unchanged.
                int[] cmpAnchor = com.rustcraft.bridge.PhosphorLightHook
                        .COMPARE_ANCHOR;
                if (cmpAnchor != null && ZS_CMP_N < 5
                        && Math.abs(x - cmpAnchor[0]) <= 8
                        && Math.abs(y - cmpAnchor[1]) <= 8
                        && Math.abs(z - cmpAnchor[2]) <= 8
                        && stagedPositive(world, x, y, z, zsTable)) {
                    zsCompare(world, pos, x, y, z, dim, zsTable);
                    ZS_CMP_N++;
                }
                long j0 = System.nanoTime();
                int zrc = LightAuthorityBridge.runZeroStage(
                        dim, x, y, z, zsTable, zsOut,
                        1L << 22, 64, true);
                JNI_NANOS.addAndGet(System.nanoTime() - j0);
                if (zrc < 0) {
                    // structured fallback (§13): untouched original path
                    switch (zrc) {
                        case -1: FALLBACK_UNLOADED.incrementAndGet(); break;
                        case -2: FALLBACK_DYNAMIC.incrementAndGet(); break;
                        case -5: FALLBACK_BOUNDS.incrementAndGet(); break;
                        case -6: STALE_GEN.incrementAndGet(); break;
                        default: FALLBACK_ERROR.incrementAndGet(); break;
                    }
                    FALLBACKS.incrementAndGet();
                    LAST_ERROR = "runZeroStage rc=" + zrc;
                    return -1;
                }
                // Java mirror commit — OPT-MIRROR-001: section-batched
                // direct-nibble publication by default; -Drustcraft.
                //legacyMirror=true reproduces the per-cell setLightFor
                // path for A/B. Direct publication writes the EBS
                // NibbleArray backing bytes at local indices: same final
                // Java-visible state (identical array), chunk.dirty once
                // per touched chunk, and it BYPASSES our own
                // Chunk.setLightFor/EBS hooks whose per-cell work
                // (tracker marks + a redundant mirrorBlockLight JNI
                // write-back of the value Rust just committed) was pure
                // overhead for mirror-originated writes.
                long c0 = System.nanoTime();
                if (LEGACY_MIRROR) {
                    zsOut.position(0);
                    for (int i = 0; i < zrc; i++) {
                        int dx = zsOut.getInt(), dy = zsOut.getInt();
                        int dz = zsOut.getInt();
                        int v = zsOut.getInt();
                        Object p = POS_CTOR.newInstance(dx, dy, dz);
                        M_SET_LIGHT_FOR.invoke(world, BLOCK_TYPE, p, v);
                    }
                } else {
                    mirrorBatch(world, dim, zsOut, zrc);
                }
                COMMIT_NANOS.addAndGet(System.nanoTime() - c0);
                COMMITTED_CELLS.addAndGet(zrc);
                ZERO_STAGE_JOBS.incrementAndGet();

                // SKY passthrough (never owned)
                boolean skyFlag = false;
                Object provider = F_PROVIDER.get(world);
                if (provider != null
                        && (Boolean) M_HAS_SKY_LIGHT.invoke(provider)) {
                    skyFlag = (Boolean) M_CHECK_LIGHT_FOR.invoke(world,
                            SKY_TYPE, pos);
                    SKY_CALLS.incrementAndGet();
                }
                ADMITTED.incrementAndGet();
                return skyFlag ? 1 : 0;
            }

            // STAGED NATIVE FAST PATH (NON-PRODUCTION — §21: kept as the
            // diagnostic/comparison oracle; the production path above is
            // zero-staging): snapshot the box's chunk sections into bulk
            // buffers, ONE JNI crossing runs the frozen kernel over the
            // section slices, the diff commits via setLightFor.
            ensureNativeSurface();
            int radius = BOX;
            int minX = x - (radius - 1), maxX = x + (radius - 1);
            int minY = y - (radius - 1), maxY = y + (radius - 1);
            int minZ = z - (radius - 1), maxZ = z + (radius - 1);

            int cMinX = (x - radius) >> 4, cMaxX = (x + radius) >> 4;
            int cMinZ = (z - radius) >> 4, cMaxZ = (z + radius) >> 4;
            int sMinY = Math.max(0, (y - radius) >> 4);
            int sMaxY = Math.min(15, (y + radius) >> 4);
            int colsX = cMaxX - cMinX + 1;
            int colsZ = cMaxZ - cMinZ + 1;
            int levels = sMaxY - sMinY + 1;
            int sectionCount = colsX * colsZ * levels;

            java.nio.ByteBuffer dir = java.nio.ByteBuffer
                    .allocateDirect(sectionCount * 5 * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            java.nio.ByteBuffer states = java.nio.ByteBuffer
                    .allocateDirect(sectionCount * 4096 * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            java.nio.ByteBuffer light = java.nio.ByteBuffer
                    .allocateDirect(sectionCount * 2048)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int slot = 0;
            for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                for (int cx = cMinX; cx <= cMaxX; cx++) {
                    for (int sy = sMinY; sy <= sMaxY; sy++) {
                        int statesOff = slot * 4096;   // u16 ELEMENT offset
                        int lightOff = slot * 2048;    // BYTE offset
                        dir.putInt(cx);
                        dir.putInt(cz);
                        dir.putInt(sy);
                        dir.putInt(statesOff);
                        dir.putInt(lightOff);
                        if (!snapshotSection(world, cx, cz, sy, statesOff,
                                lightOff, states, light)) {
                            FALLBACK_DYNAMIC.incrementAndGet();
                            FALLBACKS.incrementAndGet();
                            return -1;
                        }
                        slot++;
                    }
                }
            }
            // §8 prescan runs inside Rust over the section states (fail
            // closed on any unclassified id)

            int tblSids = liveStateTableSids();
            java.nio.ByteBuffer table = java.nio.ByteBuffer
                    .allocateDirect(tblSids * 3)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte[] fill = new byte[tblSids * 3]; // 0 = unclassified
            table.put(fill);
            table.clear();
            for (Map.Entry<Integer, byte[]> e : LIGHT_TABLE.entrySet()) {
                if (e.getKey() >= 0 && e.getKey() < tblSids) {
                    // 3-byte entry: [classified=1, opacity, emission] — the
                    // flag byte is REQUIRED (its absence made every entry
                    // read as unclassified: the dev-FAST-11 tbl0=0,0,0)
                    table.position(e.getKey() * 3);
                    table.put((byte) 1);
                    table.put(e.getValue()[0]);
                    table.put(e.getValue()[1]);
                    table.position(0);
                }
            }

            java.nio.ByteBuffer out = java.nio.ByteBuffer
                    .allocateDirect(4 * 1024 * 1024) // 1M diff records
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            STAGE_NANOS.addAndGet(System.nanoTime() - t0);

            // ---- ONE JNI crossing ----
            long j0 = System.nanoTime();
            dir.position(0);
            states.position(0);
            light.position(0);
            table.position(0);
            out.position(0);
            int rc = LightAuthorityBridge.runJobNative(dir, states, light,
                    table, x, y, z,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    out, 4L * 1024 * 1024);
            JNI_NANOS.addAndGet(System.nanoTime() - j0);
            if (rc < 0) {
                // §15/§12/§11 fail closed BEFORE commit; the vanilla body
                // owns this job (its own cascade is unbounded)
                FALLBACK_ERROR.incrementAndGet();
                FALLBACKS.incrementAndGet();
                if (rc == -2) {
                    int badSid = LightAuthorityBridge.lastNativeUnknownSid();
                    Object badState = badSid >= 0
                            ? STATE_BY_SID.get(badSid) : null;
                    byte[] te = badSid < LIGHT_TABLE.size()
                            ? LIGHT_TABLE.get(badSid) : null;
                    String teS = "none";
                    if (te != null && te.length >= 3) {
                        teS = te[0] + "," + te[1] + "," + te[2];
                    }
                    int t0v = 0, t1v = 0, t2v = 0;
                    if (table.remaining() >= 3) {
                        t0v = table.get(0);
                        t1v = table.get(1);
                        t2v = table.get(2);
                    }
                    LAST_ERROR = "runJobNative rc=-2 unknownSidNative=" + badSid
                            + " state=" + (badState == null ? "null"
                                    : badState.getClass().getName())
                            + " tableEntry=" + teS
                            + " tbl0=" + t0v + "," + t1v + "," + t2v;
                } else {
                    LAST_ERROR = "runJobNative rc=" + rc + " dir="
                            + dir.remaining();
                }
                return -1;
            }

            // ---- commit diffs into the live arrays ----
            long c0 = System.nanoTime();
            long committed = 0;
            out.position(0);
            for (int i = 0; i < rc; i++) {
                // §1 record decode: getInt, NOT get (get() = one byte —
                // the x-word's bytes read as 4 fake records)
                int dx = out.getInt(), dy = out.getInt(), dz = out.getInt();
                int v = out.getInt();
                Object p = POS_CTOR.newInstance(dx, dy, dz);
                M_SET_LIGHT_FOR.invoke(world, BLOCK_TYPE, p, v);
                committed++;
            }
            COMMIT_NANOS.addAndGet(System.nanoTime() - c0);
            COMMITTED_CELLS.addAndGet(committed);

            // ---- SKY passthrough (never owned; each shape's own path) ----
            boolean skyFlag = false;
            Object provider = F_PROVIDER.get(world);
            if (provider != null && (Boolean) M_HAS_SKY_LIGHT.invoke(provider)) {
                skyFlag = (Boolean) M_CHECK_LIGHT_FOR.invoke(world,
                        SKY_TYPE, pos);
                SKY_CALLS.incrementAndGet();
            }
            ADMITTED.incrementAndGet();
            NATIVE_JOBS.incrementAndGet();
            return skyFlag ? 1 : 0;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = String.valueOf(t);
            FALLBACK_ERROR.incrementAndGet();
            FALLBACKS.incrementAndGet();
            return -1; // fail closed: Java/Phosphor owns the job
        }
    }

    private static void putHeader(java.nio.ByteBuffer input, int x, int y,
                                  int z, int radius) {
        int[] h = {
                x, y, z,
                x - (radius - 2), y - (radius - 2), z - (radius - 2),
                x + (radius - 2), y + (radius - 2), z + (radius - 2),
                2 * radius + 1, 2 * radius + 1, 2 * radius + 1,
                0,
        };
        for (int v : h) input.putInt(v);
    }

    // -- native fast path staging (goal §3/§10): bulk section snapshots,
    //    NO per-cell JNI, NO per-cell reflection beyond one resolved
    //    container accessor per section --

    private static void ensureNativeSurface() throws Exception {
        if (NATIVE_RESOLVED) return;
        synchronized (LightAuthorityHook.class) {
            if (NATIVE_RESOLVED) return;
            // World.getChunkFromChunkCoords(II)Chunk (SRG func_72964_e)
            Class<?> worldClass = M_GET_LIGHT_FOR.getDeclaringClass();
            M_WORLD_GET_CHUNK = findOn(worldClass,
                    new String[]{"getChunkFromChunkCoords", "func_72964_e"},
                    2, null);
            // Chunk.sections field: type ExtendedBlockStorage[]
            Class<?> chunkClass = M_WORLD_GET_CHUNK.getReturnType();
            for (Field f : chunkClass.getDeclaredFields()) {
                if (f.getType().getName()
                        .equals("[Lnet.minecraft.world.chunk.storage."
                                + "ExtendedBlockStorage;")) {
                    F_CHUNK_SECTIONS = f;
                    F_CHUNK_SECTIONS.setAccessible(true);
                    break;
                }
            }
            if (F_CHUNK_SECTIONS == null) {
                throw new IllegalStateException("Chunk.sections not found");
            }
            Class<?> ebs = F_CHUNK_SECTIONS.getType().getComponentType();
            for (Field f : ebs.getDeclaredFields()) {
                String t = f.getType().getName();
                if (t.equals("net.minecraft.chunk.BlockStateContainer")
                        || t.equals("net.minecraft.world.chunk."
                                + "BlockStateContainer")) {
                    F_EBS_DATA = f;
                    F_EBS_DATA.setAccessible(true);
                }
                if (t.equals("net.minecraft.chunk.NibbleArray")
                        || t.equals("net.minecraft.world.chunk.NibbleArray")) {
                    // first NibbleArray field = blockLight (declared before
                    // skyLight in the vanilla source)
                    if (F_EBS_BLOCKLIGHT == null) {
                        F_EBS_BLOCKLIGHT = f;
                        F_EBS_BLOCKLIGHT.setAccessible(true);
                    }
                }
            }
            if (F_EBS_DATA == null || F_EBS_BLOCKLIGHT == null) {
                throw new IllegalStateException("EBS fields not found");
            }
            // resolve accessors on the DECLARED field types (no instances
            // needed — the field types are the runtime classes)
            M_CONTAINER_GET = findOn(F_EBS_DATA.getType(),
                    new String[]{"getBlockState", "func_186015_a"}, 1, null);
            M_NIBBLE_DATA = findOn(F_EBS_BLOCKLIGHT.getType(),
                    new String[]{"getData", "func_177481_a"}, 0,
                    byte[].class);
            NATIVE_RESOLVED = true;
        }
    }

    /** Snapshot one section: global state ids into `states` at statesOff
     *  (u16 each), raw block-light nibbles into `light` at lightOff
     *  (2048 bytes). Returns false when a state could not be classified
     *  (§12 whole-job fallback). Null vanilla section = all air (zeroed
     *  by the caller). */
    private static boolean snapshotSection(Object world, int cx, int cz,
            int sy, int statesOff, int lightOff, java.nio.ByteBuffer states,
            java.nio.ByteBuffer light) throws Exception {
        Object chunk = M_WORLD_GET_CHUNK.invoke(world, cx, cz);
        Object[] sections = (Object[]) F_CHUNK_SECTIONS.get(chunk);
        Object ebs = sections[sy];
        if (ebs == null) {
            return true; // vanilla null section = all air, light 0
        }
        Object container = F_EBS_DATA.get(ebs);
        Object nibble = F_EBS_BLOCKLIGHT.get(ebs);
        for (int i = 0; i < 4096; i++) {
            Object state = M_CONTAINER_GET.invoke(container, i);
            Integer sid = STATE_IDS.get(state);
            if (sid == null) {
                sid = classify(state, world, 0, 0, 0);
                if (sid == null) return false; // §12 dynamic/unknown
            }
            states.putShort((statesOff + i) * 2, sid.shortValue());
        }
        byte[] raw = (byte[]) M_NIBBLE_DATA.invoke(nibble);
        java.nio.ByteBuffer dst = light.duplicate();
        dst.position(lightOff);
        dst.put(raw);
        return true;
    }

    /** Session light table buffer (3-byte entries, global state id keyed).
     *  FULL-WIDTH: sized to the LIVE registry's max id + 1 — modded
     *  registries (Revelation) exceed the old 1<<16 ceiling; flat sid*3
     *  indexing survives because the Rust side bounds-checks every lookup
     *  (unknown sid fail-closes there). Rebuilt per job for now — the
     *  table is session-stable, so a cached buffer is a trivial follow-up
     *  if it ever shows. */
    /// OPT-MIRROR-001 winner: group the diff by (chunk, section), resolve
    /// the Java chunk + EBS + NibbleArray backing ONCE per section, write
    /// nibbles directly at local indices. Saves per cell: BlockPos alloc,
    /// setLightFor reflection, isValid/isBlockLoaded, chunk lookup,
    /// notifyLightSet (empty on dedicated servers — symbols-proven),
    /// chunk.dirty write, AND our own two hooks incl. the redundant
    /// mirrorBlockLight JNI write-back. Fail-open: any reflection miss on
    /// a section falls back to per-cell setLightFor for that section.
    private static void mirrorBatch(Object world, int dim,
            java.nio.ByteBuffer diff, int rc) {
        long t0 = System.nanoTime();
        int sections = 0;
        long fallbackCells = 0;
        // last-section cache: the kernel's diff is sorted by world
        // (x,y,z), so consecutive records share the section — resolve
        // chunk/EBS/NibbleArray backing ONCE per section transition,
        // zero allocation per cell
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE,
                lastSy = -1;
        Object lastChunk = null;
        Object[] lastStorages = null;
        byte[] lastBacking = null;
        try {
            diff.position(0);
            for (int i = 0; i < rc; i++) {
                int x = diff.getInt(), y = diff.getInt(), z = diff.getInt();
                int v = diff.getInt();
                int cx = x >> 4, cz = z >> 4, sy = y >> 4;
                if (cx != lastCx || cz != lastCz || sy != lastSy) {
                    lastCx = cx; lastCz = cz; lastSy = sy;
                    lastChunk = null; lastStorages = null; lastBacking = null;
                    try {
                        Object chunk = M_WORLD_GET_CHUNK.invoke(world, cx, cz);
                        if (chunk != null) {
                            if (F_CHUNK_SECTIONS_MIRROR == null) {
                                // cross-loader rule (the runscope lint's
                                // exact class): derive from the LIVE
                                // object, never Class.forName
                                Class<?> chunkCls = chunk.getClass();
                                F_CHUNK_SECTIONS_MIRROR = chunkCls
                                        .getDeclaredField("field_76652_q");
                                F_CHUNK_SECTIONS_MIRROR.setAccessible(true);
                                F_CHUNK_DIRTY = chunkCls.getDeclaredField(
                                        "field_76643_l");
                                F_CHUNK_DIRTY.setAccessible(true);
                            }
                            lastChunk = chunk;
                            lastStorages = (Object[]) F_CHUNK_SECTIONS_MIRROR
                                    .get(chunk);
                        }
                    } catch (Throwable t) {
                        LAST_ERROR = "mirrorBatch-resolve: " + t;
                    }
                }
                byte[] backing = lastBacking;
                if (lastStorages != null && lastBacking == null
                        && lastChunk != null && sy >= 0 && sy < 16) {
                    // resolve this section's nibble backing once
                    try {
                        Object ebs = lastStorages[sy];
                        if (ebs != null) {
                            if (M_EBS_GET_BL_MIRROR == null) {
                                M_EBS_GET_BL_MIRROR = ebs.getClass()
                                        .getMethod("func_76661_k");
                                M_EBS_GET_BL_MIRROR.setAccessible(true);
                                M_NIBBLE_DATA_MIRROR = M_EBS_GET_BL_MIRROR
                                        .getReturnType().getMethod(
                                                "func_177481_a");
                                M_NIBBLE_DATA_MIRROR.setAccessible(true);
                            }
                            Object nibble = M_EBS_GET_BL_MIRROR.invoke(ebs);
                            if (nibble != null) {
                                byte[] bk = (byte[]) M_NIBBLE_DATA_MIRROR
                                        .invoke(nibble);
                                if (bk != null && bk.length == 2048) {
                                    backing = bk;
                                    lastBacking = bk;
                                    F_CHUNK_DIRTY.set(lastChunk, Boolean.TRUE);
                                    sections++;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LAST_ERROR = "mirrorBatch-section: " + t;
                    }
                } else {
                    backing = lastBacking;
                }
                if (backing != null) {
                    int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                    int half = idx >> 1;
                    if ((idx & 1) == 0) {
                        backing[half] = (byte) ((backing[half] & 0xF0) | v);
                    } else {
                        backing[half] = (byte) ((backing[half] & 0x0F) | (v << 4));
                    }
                    MIRROR_PUBLISHED_CELLS.incrementAndGet();
                } else {
                    // absent EBS or resolve failure: legacy per-cell
                    // publication (matches setLightFor's create-on-demand)
                    try {
                        Object p2 = POS_CTOR.newInstance(x, y, z);
                        M_SET_LIGHT_FOR.invoke(world, BLOCK_TYPE, p2, v);
                        fallbackCells++;
                        MIRROR_PUBLISHED_CELLS.incrementAndGet();
                    } catch (Throwable t) {
                        LAST_ERROR = "mirror-fallback: " + t;
                    }
                }
            }
        } catch (Throwable t) {
            LAST_ERROR = "mirrorBatch: " + t;
        }
        MIRROR_BATCH_NS.addAndGet(System.nanoTime() - t0);
        MIRROR_SECTIONS.addAndGet(sections);
        MIRROR_FALLBACK_CELLS.addAndGet(fallbackCells);
    }

    static int liveStateTableSids() {
        int maxSid = 1;
        for (Map.Entry<Integer, byte[]> e : LIGHT_TABLE.entrySet()) {
            if (e.getKey() >= maxSid) maxSid = e.getKey() + 1;
        }
        return Math.max(maxSid, 2);
    }

    private static java.nio.ByteBuffer buildTable() {
        int sids = liveStateTableSids();
        java.nio.ByteBuffer t = java.nio.ByteBuffer
                .allocateDirect(sids * 3)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] fill = new byte[sids * 3];
        t.put(fill);
        t.clear();
        for (Map.Entry<Integer, byte[]> e : LIGHT_TABLE.entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < sids) {
                t.position(e.getKey() * 3);
                t.put((byte) 1);
                t.put(e.getValue()[0]);
                t.put(e.getValue()[1]);
                t.position(0);
            }
        }
        return t;
    }

    /** Full-width global state id for the mutation-seam mirror (and any
     *  diagnostic): the STATE_IDS cache first, registry lookup on miss.
     *  Returns -1 when unresolvable. */
    public static int stateIdOf(Object state) {
        if (state == null) return -1;
        Map<Object, Integer> ids = STATE_IDS;
        if (ids != null) {
            Integer sid = ids.get(state);
            if (sid != null) return sid;
        }
        try {
            // SELF-SUFFICIENT resolution (zsa3 lesson: 2,798 worldgen-era
            // setblocks fire BEFORE any light job runs ensureSurface — the
            // mirror must not depend on it). Block.field_176229_d is a
            // static: reachable from the state alone via IBlockState.getBlock.
            if (REGISTRY == null || M_STATE_ID == null) {
                java.lang.reflect.Method getBlock = state.getClass()
                        .getMethod("func_177230_c"); // IBlockState.getBlock
                Object block = getBlock.invoke(state);
                Class<?> blockClass = block.getClass();
                while (blockClass != null
                        && !blockClass.getName().equals("net.minecraft.block.Block")) {
                    blockClass = blockClass.getSuperclass();
                }
                if (blockClass == null) return -1;
                java.lang.reflect.Field idsF =
                        blockClass.getDeclaredField("field_176229_d");
                idsF.setAccessible(true);
                REGISTRY = idsF.get(null);
                M_STATE_ID = findOn(REGISTRY.getClass(),
                        new String[]{"get"}, 1, int.class);
            }
            Integer sid = (Integer) M_STATE_ID.invoke(REGISTRY, state);
            if (sid == null) return -1;
            if (ids != null) ids.put(state, sid);
            return sid;
        } catch (Throwable t) {
            return -1;
        }
    }


    private static volatile boolean FULL_TABLE_BUILT;
    // §2 comparative-diagnostic budgets (removed after closure)
    private static volatile int ZS_CMP_N;
    private static volatile int ZS_CMP_DBG;
    private static volatile java.lang.reflect.Constructor<?> POS_CCTOR;
    public static final java.util.concurrent.atomic.AtomicLong ZS_REPAIRS =
            new java.util.concurrent.atomic.AtomicLong();
    /** §29: per-state classification REJECTION is a state verdict (the
     *  dynamic-fallback domain), not an authority error — deterministic
     *  modded-state throwers landed here 527x/run on Gate C and tripped
     *  the 0-errors gate while the runtime correctly fail-closed. */
    public static final java.util.concurrent.atomic.AtomicLong CLASSIFY_REJECTED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<Object, Integer> CLASSIFY_FAILURES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int dimOf(Object world) {
        try {
            return worldDim(world);
        } catch (Throwable t) {
            return 0;
        }
    }
    private static volatile int ZS_CMP_EMIT_N;

    /** Zero-stage requires a COMPLETE session table: the staged path
     *  classified only states it happened to stage, but zero-stage reads
     *  opacity/emission from Rust for every state the frontier touches.
     *  One-time classification of the entire runtime state registry
     *  (~10k states; classify() caches by state identity). */
    private static void ensureFullTable(Object world) {
        if (FULL_TABLE_BUILT) return;
        synchronized (LightAuthorityHook.class) {
            if (FULL_TABLE_BUILT) return;
            try {
                // iterate the id map via Iterable (the runtime class is a
                // Forge anonymous subclass whose 1-arg accessors are not
                // reliably reflectable; iterator() is inherited-public);
                // ids via the proven get(Object)int path
                int n = 0;
                java.util.Iterator<?> it = ((Iterable<?>) REGISTRY).iterator();
                while (it.hasNext()) {
                    Object state = it.next();
                    if (state == null) continue;
                    classify(state, world, 0, 64, 0);
                    n++;
                }
                FULL_TABLE_BUILT = true;
                System.out.println("[RustCraft-Light] full state table "
                        + "classified: " + n + " states, LIGHT_TABLE="
                        + LIGHT_TABLE.size());
            } catch (Throwable t) {
                LAST_ERROR = "ensureFullTable: " + t;
                System.out.println("[RustCraft-Light] full table FAILED: " + t);
            }
        }
    }

    private static volatile Method M_WORLD_PROVIDER_GET_DIM;

    private static int worldDim(Object world) throws Exception {
        // dimension id via the provider (WorldProvider.getDimension /
        // func_186058_p -> DimensionType.getId fallback)
        Object provider = F_PROVIDER.get(world);
        if (provider == null) return 0;
        if (M_WORLD_PROVIDER_GET_DIM == null) {
            Method m = null;
            for (String n : new String[]{"getDimension", "func_186058_p"}) {
                try {
                    m = provider.getClass().getMethod(n);
                    m.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignore) {
                    // try next
                }
            }
            if (m == null) {
                throw new IllegalStateException(
                        "provider dimension accessor missing");
            }
            M_WORLD_PROVIDER_GET_DIM = m;
        }
        Object r = M_WORLD_PROVIDER_GET_DIM.invoke(provider);
        if (r instanceof Integer) {
            return (Integer) r;
        }
        // DimensionType: getId()
        return (Integer) r.getClass().getMethod("getId").invoke(r);
    }

    /** Light-context filter: capture only jobs whose origin emits or has
     *  a lit neighbor (the place/remove/gradient jobs) — the plain-air
     *  worldgen jobs are provable no-ops and eat the budget otherwise. */
    private static boolean hasLightContext(Object world, int x, int y, int z) {
        try {
            Object p0 = POS_CTOR.newInstance(x, y, z);
            Object st = M_GET_BLOCK_STATE.invoke(world, p0);
            Integer sid = STATE_IDS.get(st);
            if (sid != null) {
                byte[] e = LIGHT_TABLE.get(sid);
                if (e != null && e.length > 1 && e[1] > 0) return true;
            }
            int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
                    {0, 0, 1}, {0, 0, -1}};
            for (int[] c : nb) {
                int jl = (Integer) M_GET_LIGHT_FOR.invoke(world,
                        BLOCK_TYPE,
                        POS_CTOR.newInstance(x + c[0], y + c[1], z + c[2]));
                if (jl > 0) return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** Deterministic positive-capture gate: run the CHEAP staged
     *  compute first; capture only jobs where the proven oracle itself
     *  finds work (changed != 0). Every capture is then a both-relevant
     *  positive — no salt luck, no budget burned on provable no-ops. */
    private static boolean stagedPositive(Object world, int x, int y, int z,
                                         java.nio.ByteBuffer table) {
        try {
            StagedCompute sc = stagedComputeOnly(world, x, y, z, table);
            return sc != null && sc.changed > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** §2 same-job comparison: staged oracle COMPUTE-ONLY (snapshot box +
     *  runJobNative, diff discarded) vs zero-stage COMPUTE-ONLY
     *  (runZeroStage commit=false). Prints the §6 A/B/C classification
     *  inputs. Neither commits. */
    /// OPT-MIRROR-001: section-batched direct-nibble publication.
    /// legacyMirror=true reproduces the per-cell setLightFor path (A/B).
    public static final boolean LEGACY_MIRROR =
            Boolean.getBoolean("rustcraft.legacyMirror");
    public static final java.util.concurrent.atomic.AtomicLong MIRROR_BATCH_NS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MIRROR_SECTIONS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MIRROR_FALLBACK_CELLS =
            new java.util.concurrent.atomic.AtomicLong();
    /// Cells actually published to Java (batched + fallback). Gate
    /// invariant: must cover the committed-cell count — a silently
    /// dropped mirror (the ClassNotFoundException incident) otherwise
    /// passes every gate while Java-visible light goes stale.
    public static final java.util.concurrent.atomic.AtomicLong MIRROR_PUBLISHED_CELLS =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile java.lang.reflect.Field F_CHUNK_SECTIONS_MIRROR;
    private static volatile java.lang.reflect.Method M_EBS_GET_BL_MIRROR, M_NIBBLE_DATA_MIRROR;
    private static volatile java.lang.reflect.Field F_EBS_DATA_MIRROR;
    private static volatile java.lang.reflect.Field F_CHUNK_DIRTY;

    /** §10 deferred ground truth: disputed cells from the previous
     *  capture, re-read (Java + native) at the NEXT capture — seconds
     *  later, after the world settles. Settled Java light is the final
     *  state the live server actually holds. */
    private static final java.util.List<int[]> ZS_GT_CELLS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile Object ZS_GT_WORLD;

    private static void zsCompare(Object world, Object pos, int x, int y,
                                  int z, int dim, java.nio.ByteBuffer table) {
        try {
            if (POS_CCTOR == null) {
                POS_CCTOR = pos.getClass().getConstructor(
                        int.class, int.class, int.class);
            }
            // §10: dump ground truth for the previous capture's disputed
            // cells against the settled world
            if (!ZS_GT_CELLS.isEmpty()) {
                Object gtWorld = ZS_GT_WORLD != null ? ZS_GT_WORLD : world;
                StringBuilder gt = new StringBuilder();
                int gtDim = dimOf(world);
                for (int[] c : ZS_GT_CELLS) {
                    try {
                        Object p = POS_CTOR.newInstance(c[0], c[1], c[2]);
                        int jl = (Integer) M_GET_LIGHT_FOR.invoke(
                                gtWorld, BLOCK_TYPE, p);
                        int nl = NativeChunkBridge.getBlockLightProbe(
                                gtDim, c[0] >> 4, c[2] >> 4,
                                c[0] & 15, c[1], c[2] & 15);
                        gt.append('(').append(c[0]).append(',')
                                .append(c[1]).append(',').append(c[2])
                                .append(" claimed=").append(c[3])
                                .append(" jl=").append(jl)
                                .append(" nl=").append(nl).append(") ");
                    } catch (Throwable ignore) {
                        // next capture retries
                    }
                }
                System.out.println("[zs-cmp] GT " + gt.toString().trim());
                ZS_GT_CELLS.clear();
            }
            ZS_GT_WORLD = world;
            // precondition cells (§4): source + 6 neighbors, Java vs Native
            StringBuilder pre = new StringBuilder();
            int[][] cells = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                    {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] c : cells) {
                int cx = x + c[0], cy = y + c[1], cz = z + c[2];
                Object p = POS_CCTOR.newInstance(cx, cy, cz);
                Object st = M_GET_BLOCK_STATE.invoke(world, p);
                Integer jsid = STATE_IDS.get(st);
                int jl = (Integer) M_GET_LIGHT_FOR.invoke(world, BLOCK_TYPE, p);
                int nl = NativeChunkBridge.getBlockLightProbe(
                        dim, cx >> 4, cz >> 4, cx & 15, cy, cz & 15);
                int ns = NativeChunkBridge.getBlockStateProbe(
                        dim, cx >> 4, cz >> 4, cx & 15, cy, cz & 15);
                pre.append('[').append(jsid).append('/')
                        .append(jl).append('/').append(nl)
                        .append('/').append(ns).append(']');
            }
            // seam-mirror ownership probe: is the origin chunk's section-4
            // EBS instance keyed in the tracker's owner map?
            String ownProbe = "?";
            try {
                Object chunk = M_WORLD_GET_CHUNK.invoke(world, x >> 4, z >> 4);
                ownProbe = String.valueOf(com.rustcraft.bridge.ChunkMutationTracker
                        .ebsOwnerBound(chunk, (y >> 4) & 15));
            } catch (Throwable ignore) {
            }
            // staged oracle compute-only (the proven non-production path)
            int stagedChanged = -1;
            long stagedHash = 0;
            try {
                java.nio.ByteBuffer sTable = buildTable();
                StagedCompute sc = stagedComputeOnly(world, x, y, z, sTable);
                if (sc != null) {
                    stagedChanged = sc.changed;
                    stagedHash = sc.hash;
                }
            } catch (Throwable t) {
                System.out.println("[zs-cmp] stagedCompute failed: " + t);
            }
            // zero-stage compute-only — §2 POISONED OUTPUT: fill the
            // entire buffer with an invalid pattern first; after return
            // decode ONLY rc records and verify the tail is still poison
            int zeroChanged = -99;
            long zeroHash = 0;
            java.nio.ByteBuffer zOut = java.nio.ByteBuffer
                    .allocateDirect(4 * 1024 * 1024)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte[] poison = new byte[64 * 1024];
            java.util.Arrays.fill(poison, (byte) 0xA5);
            while (zOut.remaining() >= poison.length) {
                zOut.put(poison);   // fill the WHOLE 4MB, not one chunk
            }
            zOut.clear();
            int zrc = LightAuthorityBridge.runZeroStage(
                    dim, x, y, z, table, zOut, 1L << 22, 64, false);
            // §2 poison-tail verification (bounded print) + raw first
            // records: offset-0 content distinguishes decode bugs from
            // write-address bugs at a glance
            if (zrc >= 0 && ZS_CMP_DBG < 6) {
                ZS_CMP_DBG++;
                zOut.position(zrc * 16);
                int poisonPeek = zOut.getInt(zrc * 16);
                zOut.position(0);
                java.util.HashSet<String> uniq = new java.util.HashSet<>();
                for (int i = 0; i < zrc; i++) {
                    uniq.add(zOut.getInt() + "," + zOut.getInt() + ","
                            + zOut.getInt() + "," + zOut.getInt());
                }
                int nShow = Math.min(zrc, 3);
                StringBuilder raw = new StringBuilder();
                for (int i = 0; i < nShow * 4; i++) {
                    raw.append(zOut.getInt(i * 4)).append(' ');
                }
                // period probe: records at spread indices expose a
                // repeating-block structure (39 distinct among 2472 needs
                // a visible period) vs genuine distinct records
                StringBuilder probe = new StringBuilder();
                int[] probeIdx = {3, 20, 39, 40, 100, 500, 1000,
                        Math.max(0, zrc / 2), Math.max(0, zrc - 2)};
                for (int pi : probeIdx) {
                    if (pi < zrc) {
                        probe.append('#').append(pi).append('=')
                                .append(zOut.getInt(pi * 16)).append(',')
                                .append(zOut.getInt(pi * 16 + 4)).append(',')
                                .append(zOut.getInt(pi * 16 + 8)).append(',')
                                .append(zOut.getInt(pi * 16 + 12)).append(' ');
                    }
                }
                System.out.println("[zs-cmp] CONTRACT rc=" + zrc
                        + " uniqueKeys=" + uniq.size()
                        + " tailPoison=" + (poisonPeek == 0xA5A5A5A5
                                || poisonPeek == -1515870811)
                        + " first=[" + raw.toString().trim() + "] "
                        + probe.toString().trim());
            }
            zOut.position(0);
            java.util.TreeSet<String> zeroSet = new java.util.TreeSet<>();
            if (zrc >= 0) {
                zeroChanged = zrc;
                zOut.position(0);
                for (int i = 0; i < zrc; i++) {
                    int dx = zOut.getInt(), dy = zOut.getInt();
                    int dz = zOut.getInt();
                    int v = zOut.getInt();
                    // order-invariant: injective key (§6) — identical
                    // content ⇒ identical sorted set ⇒ identical hash
                    zeroSet.add(cellKey(dx, dy, dz, v));
                }
                for (String k : zeroSet) {
                    zeroHash = zeroHash * 1000003L + k.hashCode();
                }
            }
            System.out.println("[zs-cmp] job pos=(" + x + "," + y + "," + z
                    + ") pre(sid/jl/nl/ns)=" + pre
                    + " own=" + ownProbe
                    + " widthRejects=" + com.rustcraft.bridge.M4Coherency
                            .HIGH_ID_REJECTED.get()
                    + " staged(changed=" + stagedChanged
                    + " hash=" + stagedHash + ")"
                    + " zero(rc=" + zrc + " changed=" + zeroChanged
                    + " hash=" + zeroHash + ")");
            // §8: when both compute but differ, capture the first
            // differing cells (only-in-zero, only-in-staged, value
            // conflicts) with distance-from-origin for bounds analysis
            if (stagedChanged > 0 && zrc > 0 && zeroChanged != stagedChanged) {
                java.util.Map<String, Integer> sm = new java.util.HashMap<>();
                java.util.Map<String, Integer> zm = new java.util.HashMap<>();
                try {
                    java.nio.ByteBuffer sOut2 = java.nio.ByteBuffer
                            .allocateDirect(4 * 1024 * 1024)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    StagedCompute sc2 = stagedComputeOnly(world, x, y, z,
                            buildTable());
                    if (sc2 != null && sc2.changed > 0) {
                        // stagedComputeOnly returns hash only; recompute to
                        // keep cells — simplest: extend below via zero out
                    }
                } catch (Throwable ignore) {
                }
                // zero diff cells
                zOut.position(0);
                for (int i = 0; i < zrc; i++) {
                    int dx = zOut.getInt(), dy = zOut.getInt();
                    int dz = zOut.getInt();
                    int v = zOut.getInt();
                    zm.put(dx + "," + dy + "," + dz, v);
                }
                // staged cells: rerun snapshot+compute capturing the buffer
                try {
                    java.nio.ByteBuffer sOut3 = stagedComputeCells(world,
                            x, y, z, buildTable());
                    if (sOut3 != null) {
                        for (int i = 0; i < stagedChanged; i++) {
                            int dx = sOut3.getInt(), dy = sOut3.getInt();
                            int dz = sOut3.getInt();
                            int v = sOut3.getInt();
                            sm.put(dx + "," + dy + "," + dz, v);
                        }
                    }
                } catch (Throwable ignore) {
                }
                // Section 3 set summary with physical sanity bounds: a
                // single source job cannot change cells beyond Chebyshev
                // 32 - records beyond that are counted as ORACLE-ARTIFACT
                // and shown (they indicate a read/buffer issue, not
                // physics)
                int interSame = 0, valConflict = 0, onlyZero = 0,
                        onlyStaged = 0, zeroFar = 0, stagedFar = 0;
                int zoNearShown = 0, osNearShown = 0, vcShown = 0;
                for (java.util.Map.Entry<String, Integer> e
                        : zm.entrySet()) {
                    String[] p2 = e.getKey().split(",");
                    int ddx = Integer.parseInt(p2[0]) - x;
                    int ddy = Integer.parseInt(p2[1]) - y;
                    int ddz = Integer.parseInt(p2[2]) - z;
                    int dist = Math.max(Math.abs(ddx),
                            Math.max(Math.abs(ddy), Math.abs(ddz)));
                    if (dist > 32) {
                        zeroFar++;
                        if (zeroFar <= 2) {
                            System.out.println("[zs-cmp] zero-ARTIFACT "
                                    + e.getKey() + " v=" + e.getValue()
                                    + " dist=" + dist);
                        }
                        continue;
                    }
                    Integer sv = sm.get(e.getKey());
                    if (sv == null) {
                        onlyZero++;
                        if (ZS_GT_CELLS.size() < 8) {
                            ZS_GT_CELLS.add(new int[]{
                                    Integer.parseInt(p2[0]),
                                    Integer.parseInt(p2[1]),
                                    Integer.parseInt(p2[2]),
                                    e.getValue()});
                        }
                        if (zoNearShown < 3) {
                            zoNearShown++;
                            System.out.println("[zs-cmp] only-zero "
                                    + e.getKey() + " v=" + e.getValue()
                                    + " dist=" + dist + " chunkD="
                                    + (ddx >> 4) + "," + (ddz >> 4));
                        }
                    } else if (!sv.equals(e.getValue())) {
                        valConflict++;
                        if (vcShown < 3) {
                            vcShown++;
                            System.out.println("[zs-cmp] value-conflict "
                                    + e.getKey() + " staged=" + sv
                                    + " zero=" + e.getValue());
                        }
                    } else {
                        interSame++;
                    }
                }
                for (java.util.Map.Entry<String, Integer> e
                        : sm.entrySet()) {
                    if (zm.containsKey(e.getKey())) continue;
                    String[] p3 = e.getKey().split(",");
                    int ddx = Integer.parseInt(p3[0]) - x;
                    int ddy = Integer.parseInt(p3[1]) - y;
                    int ddz = Integer.parseInt(p3[2]) - z;
                    int dist = Math.max(Math.abs(ddx),
                            Math.max(Math.abs(ddy), Math.abs(ddz)));
                    if (dist > 32) {
                        stagedFar++;
                        continue;
                    }
                    onlyStaged++;
                    if (osNearShown < 3) {
                        osNearShown++;
                        System.out.println("[zs-cmp] only-staged "
                                + e.getKey() + " v=" + e.getValue()
                                + " dist=" + dist);
                    }
                }
                System.out.println("[zs-cmp] SUMMARY interSame=" + interSame
                        + " valConflict=" + valConflict
                        + " onlyZero=" + onlyZero
                        + " onlyStaged=" + onlyStaged
                        + " zeroFarArtifact=" + zeroFar
                        + " stagedFarArtifact=" + stagedFar);
            }
        } catch (Throwable t) {
            System.out.println("[zs-cmp] harness failed: " + t);
        }
    }

    /** Snapshot-box staged compute (§2A): reuses the staged oracle's own
     *  snapshot code WITHOUT the commit. */
    /** §6 collision-free cell key. The old packed-long key ((dx+1e6)<<44 ^
     *  (dy+1e6)<<24 ^ (dz+1e6)<<4 ^ v) had overlapping fields: dz+1e6 needs
     *  21 bits, so (dz+1e6)<<4 reaches bit 24 and XORs into dy's field, and
     *  (dx+1e6)<<44 overflows 64 bits for |dx| > ~15000. Distinct cells
     *  could collide and equal cells could differ — hashes built on it are
     *  not evidence. String form is injective by construction. */
    static String cellKey(int dx, int dy, int dz, int v) {
        return dx + "," + dy + "," + dz + "," + v;
    }

    private static final class StagedCompute {
        final int changed;
        final long hash;
        StagedCompute(int c, long h) { changed = c; hash = h; }
    }

    private static StagedCompute stagedComputeOnly(Object world, int x, int y,
                                                  int z, java.nio.ByteBuffer table)
            throws Exception {
        ensureNativeSurface();
        int radius = BOX;
        int cMinX = (x - radius) >> 4, cMaxX = (x + radius) >> 4;
        int cMinZ = (z - radius) >> 4, cMaxZ = (z + radius) >> 4;
        int sMinY = Math.max(0, (y - radius) >> 4);
        int sMaxY = Math.min(15, (y + radius) >> 4);
        int sectionCount = (cMaxX - cMinX + 1) * (cMaxZ - cMinZ + 1)
                * (sMaxY - sMinY + 1);
        java.nio.ByteBuffer dir = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 5 * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer states = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 4096 * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer light = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 2048)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int slot = 0;
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                for (int sy = sMinY; sy <= sMaxY; sy++) {
                    int statesOff = slot * 4096;
                    int lightOff = slot * 2048;
                    dir.putInt(cx);
                    dir.putInt(cz);
                    dir.putInt(sy);
                    dir.putInt(statesOff);
                    dir.putInt(lightOff);
                    if (!snapshotSection(world, cx, cz, sy, statesOff,
                            lightOff, states, light)) {
                        return null; // dynamic state: not admissible
                    }
                    slot++;
                }
            }
        }
        java.nio.ByteBuffer out = java.nio.ByteBuffer
                .allocateDirect(4 * 1024 * 1024)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        dir.position(0);
        states.position(0);
        light.position(0);
        table.position(0);
        out.position(0);
        int rc = LightAuthorityBridge.runJobNative(dir, states, light,
                table, x, y, z,
                x - (radius - 1), y - (radius - 1), z - (radius - 1),
                x + (radius - 1), y + (radius - 1), z + (radius - 1),
                out, 4L * 1024 * 1024);
        if (rc < 0) {
            return new StagedCompute(rc, 0);
        }
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        out.position(0);
        for (int i = 0; i < rc; i++) {
            int dx = out.getInt(), dy = out.getInt(), dz = out.getInt();
            int v = out.getInt();
            set.add(cellKey(dx, dy, dz, v));
        }
        long hash = 0;
        for (String k : set) {
            hash = hash * 1000003L + k.hashCode();
        }
        return new StagedCompute(rc, hash);
    }

    /** §8: staged compute returning the RAW out buffer (cells readable). */
    private static java.nio.ByteBuffer stagedComputeCells(Object world, int x,
            int y, int z, java.nio.ByteBuffer table) throws Exception {
        ensureNativeSurface();
        int radius = BOX;
        int cMinX = (x - radius) >> 4, cMaxX = (x + radius) >> 4;
        int cMinZ = (z - radius) >> 4, cMaxZ = (z + radius) >> 4;
        int sMinY = Math.max(0, (y - radius) >> 4);
        int sMaxY = Math.min(15, (y + radius) >> 4);
        int sectionCount = (cMaxX - cMinX + 1) * (cMaxZ - cMinZ + 1)
                * (sMaxY - sMinY + 1);
        java.nio.ByteBuffer dir = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 5 * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer states = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 4096 * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer light = java.nio.ByteBuffer
                .allocateDirect(sectionCount * 2048)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int slot = 0;
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                for (int sy = sMinY; sy <= sMaxY; sy++) {
                    int statesOff = slot * 4096;
                    int lightOff = slot * 2048;
                    dir.putInt(cx);
                    dir.putInt(cz);
                    dir.putInt(sy);
                    dir.putInt(statesOff);
                    dir.putInt(lightOff);
                    if (!snapshotSection(world, cx, cz, sy, statesOff,
                            lightOff, states, light)) {
                        return null;
                    }
                    slot++;
                }
            }
        }
        java.nio.ByteBuffer out = java.nio.ByteBuffer
                .allocateDirect(4 * 1024 * 1024)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        dir.position(0);
        states.position(0);
        light.position(0);
        table.position(0);
        out.position(0);
        int rc = LightAuthorityBridge.runJobNative(dir, states, light,
                table, x, y, z,
                x - (radius - 1), y - (radius - 1), z - (radius - 1),
                x + (radius - 1), y + (radius - 1), z + (radius - 1),
                out, 4L * 1024 * 1024);
        if (rc < 0) return null;
        out.position(0);
        java.nio.ByteBuffer copy = java.nio.ByteBuffer
                .allocateDirect(rc * 16)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rc * 4; i++) {
            copy.putInt(out.getInt());
        }
        copy.position(0);
        return copy;
    }

    // -- classification (goal §9-§11) --

    /** Classify one state; null = dynamic/unknown (whole-job fallback).
     *  Static by construction when the block class does not override
     *  Forge's world-aware light methods; otherwise probed at 3 positions
     *  and required to agree (§11). */
    private static Integer classify(Object state, Object world, int x, int y,
                                    int z) {
        if (CLASSIFIED.containsKey(state)) {
            byte[] pe = CLASSIFIED.get(state);  // null = permanent dynamic
            if (pe == null) return null;
            Integer sid = STATE_IDS.get(state);
            if (sid != null && sid < MAX_STATES) {
                LIGHT_TABLE.put(sid, pe);
                return sid;
            }
            return null;
        }
        try {
            Object block = M_GET_BLOCK.invoke(state);
            boolean overrides = overridesWorldAwareLight(block.getClass());
            int op;
            int em;
            if (!overrides) {
                // Forge default: delegate to the state's plain values
                op = (Integer) M_PLAIN_OPACITY.invoke(state);
                em = (Integer) M_PLAIN_EMISSION.invoke(state);
            } else {
                // §11 probe: 3 diverse positions must agree
                int[] ops = new int[3];
                int[] ems = new int[3];
                int[][] offs = {{0, 0, 0}, {3, 5, -2}, {-4, -7, 6}};
                for (int i = 0; i < 3; i++) {
                    Object p = POS_CTOR.newInstance(x + offs[i][0],
                            Math.max(1, Math.min(254, y + offs[i][1])),
                            z + offs[i][2]);
                    ops[i] = (Integer) M_LIGHT_OPACITY.invoke(block, state,
                            world, p);
                    ems[i] = (Integer) M_LIGHT_VALUE.invoke(block, state,
                            world, p);
                }
                if (ops[0] != ops[1] || ops[0] != ops[2]
                        || ems[0] != ems[1] || ems[0] != ems[2]) {
                    LAST_ERROR = "dynamic state class="
                            + block.getClass().getName()
                            + " ops=" + ops[0] + "," + ops[1] + "," + ops[2]
                            + " ems=" + ems[0] + "," + ems[1] + "," + ems[2];
                    CLASSIFIED.put(state, null);
                    return null; // dynamic
                }
                op = ops[0];
                em = ems[0];
            }
            byte[] pe = new byte[]{(byte) op, (byte) em};
            CLASSIFIED.put(state, pe);
            Integer sid = STATE_IDS.computeIfAbsent(state, st -> {
                try {
                    return (Integer) M_STATE_ID.invoke(REGISTRY, st);
                } catch (Throwable t) {
                    return null;
                }
            });
            // FULL-WIDTH: any non-negative runtime id is storable; the
            // table is sized to the live registry (see buildTable)
            if (sid == null || sid < 0) return null;
            STATE_BY_SID.put(sid, state);
            LIGHT_TABLE.put(sid, pe);
            return sid;
        } catch (Throwable t) {
            // A per-state classification failure is a STATE VERDICT, not an
            // authority error (§29: dynamic fallback is a legal outcome).
            // Genuinely transient failures (world not ready) retry; a state
            // that fails TWICE is a deterministic thrower (Gate C: 527/run)
            // and is marked permanently dynamic so retries converge.
            CLASSIFY_REJECTED.incrementAndGet();
            Integer n = CLASSIFY_FAILURES.merge(state, 1, Integer::sum);
            if (n >= 2) {
                CLASSIFIED.put(state, null); // permanent dynamic verdict
            }
            LAST_ERROR = "classify(" + n + "): " + t;
            return null;
        }
    }

    /** True when the block's class chain (below net.minecraft.block.Block)
     *  declares its own world-aware light methods. */
    private static boolean overridesWorldAwareLight(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals("net.minecraft.block.Block")) return false;
            for (Method m : k.getDeclaredMethods()) {
                if (("getLightOpacity".equals(m.getName())
                        || "getLightValue".equals(m.getName()))
                        && m.getParameterCount() == 3) {
                    return true;
                }
            }
        }
        return false;
    }

    // -- one-time surface resolution --

    private static void ensureSurface(Object world, Object pos)
            throws Exception {
        if (RESOLVED) return;
        synchronized (LightAuthorityHook.class) {
            if (RESOLVED) return;
            M_CHECK_LIGHT_FOR = find(world, new String[]{
                    "checkLightFor", "func_180500_c"}, 2, boolean.class);
            M_GET_LIGHT_FOR = find(world, new String[]{
                    "getLightFor", "func_175642_b"}, 2, int.class);
            M_SET_LIGHT_FOR = find(world, new String[]{
                    "setLightFor", "func_175653_a"}, 3, void.class);
            M_GET_BLOCK_STATE = find(world, new String[]{
                    "getBlockState", "func_180495_p"}, 1, null);
            M_IS_AREA_LOADED = find(world, new String[]{
                    "isAreaLoaded", "func_175648_a"}, 3, boolean.class);
            M_GET_X = find(pos, new String[]{"getX", "func_177958_n"}, 0,
                    int.class);
            M_GET_Y = find(pos, new String[]{"getY", "func_177956_o"}, 0,
                    int.class);
            M_GET_Z = find(pos, new String[]{"getZ", "func_177952_p"}, 0,
                    int.class);
            Class<?> enumClass = M_GET_LIGHT_FOR.getParameterTypes()[0];
            for (Object c : enumClass.getEnumConstants()) {
                String n = ((java.lang.Enum) c).name();
                if ("BLOCK".equals(n)) BLOCK_TYPE = c;
                if ("SKY".equals(n)) SKY_TYPE = c;
            }
            if (BLOCK_TYPE == null || SKY_TYPE == null) {
                throw new IllegalStateException("EnumSkyBlock constants");
            }
            POS_CTOR = pos.getClass().getConstructor(int.class, int.class,
                    int.class);
            // provider field: resolve by NAME + TYPE (a name-only lookup
            // handed back a WorldInfo once — Gate-A-ON-dev4)
            for (Class<?> k = world.getClass(); k != null;
                 k = k.getSuperclass()) {
                if (!k.getName().equals("net.minecraft.world.World")) continue;
                for (Field f : k.getDeclaredFields()) {
                    String fn = f.getName();
                    String ft = f.getType().getName();
                    if ((fn.equals("provider") || fn.equals("field_73011_w"))
                            && ft.equals("net.minecraft.world.WorldProvider")) {
                        F_PROVIDER = f;
                        F_PROVIDER.setAccessible(true);
                        break;
                    }
                }
                break;
            }
            if (F_PROVIDER == null) {
                throw new IllegalStateException(
                        "WorldProvider field not found on World");
            }
            Object provider = F_PROVIDER.get(world);
            M_HAS_SKY_LIGHT = find(provider, new String[]{
                    "hasSkyLight", "func_191066_m"}, 0, boolean.class);
            // state id lookup (Block.BLOCK_STATE_IDS — symbols reflect:
            // declared type net.minecraft.util.ObjectIntIdentityMap, Forge
            // assigns ClearableObjectIntIdentityMap; the id accessor is
            // Forge's LITERAL-NAME get(Object)I — the same call chunk
            // serialization uses. NOT a List, NOT RegistryNamespaced.)
            Object stateProbe = M_GET_BLOCK_STATE.invoke(world, pos);
            M_GET_BLOCK = findOn(stateProbe.getClass(),
                    new String[]{"getBlock", "func_177230_c"}, 0, null);
            Object block = M_GET_BLOCK.invoke(stateProbe);
            Class<?> blockClass = block.getClass();
            while (blockClass != null
                    && !blockClass.getName().equals("net.minecraft.block.Block")) {
                blockClass = blockClass.getSuperclass();
            }
            if (blockClass == null) {
                throw new IllegalStateException("Block class not found");
            }
            Field ids = blockClass.getDeclaredField("field_176229_d");
            ids.setAccessible(true);
            REGISTRY = ids.get(null);
            M_STATE_ID = findOn(REGISTRY.getClass(),
                    new String[]{"get"}, 1, int.class);
            STATE_IDS = new java.util.IdentityHashMap<>();
            STATE_BY_SID.clear();
            // plain (non-world-aware) state light accessors — SRG names
            // (func_185891_c getLightOpacity / func_185906_d getLightValue
            // on IBlockProperties; runtime members are SRG)
            M_PLAIN_OPACITY = stateProbe.getClass().getMethod(
                    "func_185891_c");
            M_PLAIN_EMISSION = stateProbe.getClass().getMethod(
                    "func_185906_d");
            M_LIGHT_VALUE = findOn(M_GET_BLOCK.getReturnType(),
                    new String[]{"getLightValue"}, 3, int.class);
            M_LIGHT_OPACITY = findOn(M_GET_BLOCK.getReturnType(),
                    new String[]{"getLightOpacity"}, 3, int.class);
            RESOLVED = true;
        }
    }

    private static Method M_PLAIN_OPACITY, M_PLAIN_EMISSION, M_GET_BLOCK;
    // section-level accessors (native fast path, goal §3/§10)
    private static Method M_WORLD_GET_CHUNK;   // World.getChunkFromChunkCoords(II)Chunk
    private static Field F_CHUNK_SECTIONS;     // Chunk.sections: ExtendedBlockStorage[]
    private static Field F_EBS_DATA;           // ExtendedBlockStorage.data: BlockStateContainer
    private static Field F_EBS_BLOCKLIGHT;     // ExtendedBlockStorage.blockLight: NibbleArray
    private static Method M_CONTAINER_GET;     // BlockStateContainer.getBlockState(int)IBlockState
    private static Method M_NIBBLE_DATA;       // NibbleArray.getData()[B
    private static volatile boolean NATIVE_RESOLVED;
    /** §27: legacy reflected staging is the NON-PRODUCTION oracle. */
    public static final AtomicLong NATIVE_JOBS = new AtomicLong();
    public static final AtomicLong LEGACY_JOBS = new AtomicLong();
    public static final AtomicLong NATIVE_STAGENANOS = new AtomicLong();
    public static final AtomicLong ZERO_STAGE_JOBS = new AtomicLong();
    public static final AtomicLong STALE_GEN = new AtomicLong();

    private static Method find(Object owner, String[] names, int params,
                               Class<?> ret) {
        return findOn(owner.getClass(), names, params, ret);
    }

    private static Method findOn(Class<?> owner, String[] names, int params,
                                 Class<?> ret) {
        // pass 1: public methods up the hierarchy
        for (Method m : owner.getMethods()) {
            boolean hit = false;
            for (String n : names) {
                if (n.equals(m.getName())) {
                    hit = true;
                    break;
                }
            }
            boolean retOk = (ret == null)
                    ? !m.getReturnType().equals(void.class)
                    : m.getReturnType().equals(ret);
            if (hit && m.getParameterCount() == params && retOk) {
                m.setAccessible(true);
                return m;
            }
        }
        // pass 2: DECLARED methods down the hierarchy — vanilla accessors
        // like BlockStateContainer.func_186015_a are protected and never
        // show in getMethods (dev-FAST-1)
        for (Class<?> k = owner; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                boolean hit = false;
                for (String n : names) {
                    if (n.equals(m.getName())) {
                        hit = true;
                        break;
                    }
                }
                boolean retOk = (ret == null)
                        ? !m.getReturnType().equals(void.class)
                        : m.getReturnType().equals(ret);
                if (hit && m.getParameterCount() == params && retOk) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("seam method missing: "
                + owner.getName() + " " + java.util.Arrays.toString(names));
    }

    public static String dumpMetrics() {
        return "lightAuthority.hook enabled=" + ENABLED + " mode=" + MODE
                + " jobs=" + JOBS.get()
                + " admitted=" + ADMITTED.get()
                + " fallbacks=" + FALLBACKS.get()
                + " fbUnloaded=" + FALLBACK_UNLOADED.get()
                + " fbBounds=" + FALLBACK_BOUNDS.get()
                + " fbDynamic=" + FALLBACK_DYNAMIC.get()
                + " fbError=" + FALLBACK_ERROR.get()
                + " sampleDeferred=" + SAMPLE_DEFERRED.get()
                + " committedCells=" + COMMITTED_CELLS.get()
                + " skyCalls=" + SKY_CALLS.get()
                + " stageNanos=" + STAGE_NANOS.get()
                + " stageClassifyNanos=" + STAGE_CLASSIFY_NANOS.get()
                + " stageStateNanos=" + STAGE_STATE_NANOS.get()
                + " stageLightNanos=" + STAGE_LIGHT_NANOS.get()
                + " stageBufNanos=" + STAGE_BUF_NANOS.get()
                + " jniNanos=" + JNI_NANOS.get()
                + " commitNanos=" + COMMIT_NANOS.get()
                + " errors=" + ERRORS.get()
                + " zeroStageJobs=" + ZERO_STAGE_JOBS.get()
                + " zsRepairs=" + ZS_REPAIRS.get()
                + " staleGen=" + STALE_GEN.get()
                + " lastError=" + LAST_ERROR;
    }
}
