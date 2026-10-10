package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4.2A C2 — Java-side dirty tracking for retained native snapshots.
 *
 * Coremod hooks in net.minecraft.world.chunk.Chunk record mutations HERE, in
 * pure Java — no JNI per block write. A flush thread (M4Coherency) drains the
 * masks across JNI in batch and refreshes native sections.
 *
 * Tracked separately per chunk: block mask, light mask, biome/full-replacement
 * flag, unload queue. Unknown/unobservable paths resolve to the conservative
 * direction: full invalidation (native use disabled for that chunk), never a
 * silent pass.
 *
 * State entry: int[5] { dim, blockMask, lightMask, fullFlag, biomeFlag }
 * Components tracked separately (M4.2D validity contract): blocks+light per
 * section; biomes as their own flag (pushable via setBiomes, no section work);
 * fullFlag only for identity changes (storage-array replacement). Skylight
 * REGENERATION (Chunk.generateSkylightMap) writes light arrays directly,
 * bypassing setLightFor — hooked to mark every section light-dirty.
 * dim resolved once per chunk; unresolvable => DIM_UNKNOWN => full invalidation.
 */
public final class ChunkMutationTracker {

    public static final int DIM_UNKNOWN = Integer.MIN_VALUE;

    public static final AtomicLong HOOK_BLOCK_SETS = new AtomicLong();
    /** §2 seam-mirror counters: actual-write mirrors vs rejected-write skips */
    public static final AtomicLong MIRROR_STATE_SETS = new AtomicLong();
    public static final AtomicLong MIRROR_STATE_SKIPPED_NULL = new AtomicLong();
    public static final AtomicLong MIRROR_STATE_ERRORS = new AtomicLong();
    public static final AtomicLong MIRROR_SKIP_STATE_NULL = new AtomicLong();
    public static final AtomicLong MIRROR_SKIP_RESULT_NULL = new AtomicLong();
    public static final AtomicLong MIRROR_SID_UNRESOLVED = new AtomicLong();
    public static final AtomicLong MIRROR_NOOP_OR_UNREGISTERED = new AtomicLong();
    private static volatile java.lang.reflect.Method POS_X, POS_Z;
    static volatile java.lang.reflect.Method POS_Y; // OPT-FS-002 test-visible
    public static final AtomicLong HOOK_LIGHT_SETS = new AtomicLong();
    public static final AtomicLong HOOK_STORAGE_REPLACED = new AtomicLong();
    public static final AtomicLong HOOK_BIOME_CHANGED = new AtomicLong();
    public static final AtomicLong HOOK_UNLOADS = new AtomicLong();
    public static final AtomicLong HOOK_CHUNK_LOADED = new AtomicLong();
    public static final AtomicLong HOOK_SKYLIGHT_REGEN = new AtomicLong();

    /** key: Chunk instance (strong ref acceptable: cleared on onChunkUnload,
     *  which vanilla invokes on every loaded-chunk eviction). */
    private static final ConcurrentHashMap<Object, int[]> STATE = new ConcurrentHashMap<>();

    /** unload events pending native eviction: long[] { dim, cx, cz } */
    static final ConcurrentLinkedQueue<int[]> UNLOAD_QUEUE = new ConcurrentLinkedQueue<>();

    private static volatile Field CHUNK_X, CHUNK_Z, CHUNK_WORLD;

    private ChunkMutationTracker() {}

    // --- ASM hook entry points (descriptors are Object: notch/SRG/MCP-proof) ---

    /** Chunk.setBlockState(BlockPos, IBlockState) at RETURN. Conservative:
     *  may over-mark no-op sets; never under-marks. LIGHT IS MARKED FOR ALL
     *  SECTIONS (M4.2D): setBlockState's relightBlock/propagateSkylightOcclusion
     *  write light nibbles DIRECTLY (not through setLightFor), so light outside
     *  the mutated section changes unobservably — caught offline by the
     *  empty->nonempty cycle case (section-4 skyLight below a y=200 placement).
     *  Precision light hooking is a future optimization; correctness first. */
    /** Marks-only (conservative dirty/light-work + mutation version).
     *  The STATE MIRROR lives at the EBS.set seam (onEbsBlockSet) — the
     *  Forge-patched Chunk.setBlockState bytecode reuses arg slots at
     *  ARETURN (zsa5/zsa6 live evidence), so chunk-level args are not
     *  trustworthy for a mirror. */
    public static void onBlockSet(Object chunk, Object pos) {
        HOOK_BLOCK_SETS.incrementAndGet();
        int y = blockPosY(pos) >> 4;
        int[] st = state(chunk);
        synchronized (st) {
            st[1] |= 1 << (y & 15);
            st[2] |= 0xFFFF; // conservative: any block edit may have re-lit anything
            st[5]++;         // Java mutation version (M4.2E temporal contract)
        }
        com.rustcraft.bridge.M4Coherency.requestDirtyRefresh(chunk);
    }

    /** Chunk.setLightFor(EnumSkyBlock, BlockPos, int) at RETURN. */
    public static void onLightSet(Object chunk, Object pos) {
        HOOK_LIGHT_SETS.incrementAndGet();
        int y = blockPosY(pos) >> 4;
        int[] st = state(chunk);
        synchronized (st) {
            st[2] |= 1 << (y & 15);
            st[5]++;
        }
    }

    /** Chunk.setStorageArrays(ExtendedBlockStorage[]) at RETURN — whole-chunk
     *  replacement (Anvil load): full invalidation AND EBS re-ownership —
     *  the loader can swap in NEW EBS objects after onLoad bound the old
     *  ones (zsa10: every seam-mirror call silently skipped on owner==null). */
    public static void onStorageReplaced(Object chunk) {
        HOOK_STORAGE_REPLACED.incrementAndGet();
        int[] st = state(chunk);
        synchronized (st) {
            st[3] = 1;
        }
        bindEbsOwners(chunk);
    }

    /** Chunk.setBiomeArray(byte[]) at RETURN — biomes stale; sections unaffected. */
    public static void onBiomeChanged(Object chunk) {
        HOOK_BIOME_CHANGED.incrementAndGet();
        int[] st = state(chunk);
        synchronized (st) {
            st[4] = 1;
            st[5]++;
        }
    }

    /** Chunk.generateSkylightMap() at RETURN — skylight rewritten directly,
     *  unobservable through setLightFor: mark every section light-dirty. */
    public static void onSkylightRegenerated(Object chunk) {
        HOOK_SKYLIGHT_REGEN.incrementAndGet();
        int[] st = state(chunk);
        synchronized (st) {
            st[2] |= 0xFFFF;
            st[5]++;
        }
    }

    /** EBS → owning Chunk association (identity map): the EBS light
     *  write seam does not know its chunk; onLoad binds them. Cleared on
     *  unload (stale EBS instances must not mirror into the wrong owner). */
    private static final java.util.Map<Object, Object> EBS_OWNER =
            new java.util.IdentityHashMap<>();
    // Resolution history (live evidence): the static init used
    // Class.forName on the SYSTEM classloader (never the LaunchClassLoader
    // copy) — dead. The first fix guessed the field name field_76647_h —
    // that is Chunk.z (notch axw.c), NOT sections (notch axw.f). The
    // robust source of truth is the PUBLIC GETTER
    // func_76587_i getBlockStorageArray()[EBS — subclass-proof.
    private static volatile java.lang.reflect.Method EBS_ARRAY_GET;

    private static Object[] ebsArrayOf(Object chunk) {
        try {
            java.lang.reflect.Method m = EBS_ARRAY_GET;
            if (m == null) {
                m = chunk.getClass().getMethod("func_76587_i");
                m.setAccessible(true);
                EBS_ARRAY_GET = m;
            }
            return (Object[]) m.invoke(chunk);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Chunk.onLoad RETURN (before registry work): bind EBS instances. */
    private static void bindEbsOwners(Object chunk) {
        try {
            Object[] arr = ebsArrayOf(chunk);
            if (arr == null) return;
            synchronized (EBS_OWNER) {
                for (Object ebs : arr) {
                    if (ebs != null) EBS_OWNER.put(ebs, chunk);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** EBS.setBlockLight(x,y,z,value) RETURN — the relightBlock write
     *  seam (M4.2D): mirror the value into the native registry AT THE
     *  MUTATION POINT via the owning chunk's key. No refresh, no
     *  snapshot; one write, one native update. */
    private static volatile java.lang.reflect.Field EBS_YBASE;
    private static volatile java.lang.reflect.Method EBS_GET;

    /** §1/§4 STATE MIRROR at the EBS.set seam (func_177484_a): the write
     *  has completed by RETURN — read the CANONICAL state back from the
     *  EBS (func_177485_a) and land its native twin immediately. Covers
     *  every write path that reaches an owned EBS (Chunk.setBlockState,
     *  worldgen, direct EBS writes). No arg slot is trusted for identity. */
    public static void onEbsBlockSet(Object ebs, int x, int y, int z) {
        if ((x & ~15) != 0 || (y & ~15) != 0 || (z & ~15) != 0) {
            MIRROR_SKIP_STATE_NULL.incrementAndGet(); // int-slot garbage guard
            return;
        }
        try {
            Object chunk;
            synchronized (EBS_OWNER) {
                chunk = EBS_OWNER.get(ebs);
            }
            if (chunk == null) {
                MIRROR_NOOP_OR_UNREGISTERED.incrementAndGet(); // owner-null observability
                return; // unowned/unloaded: registration sync owns it
            }
            long[] k = chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            int dim = resolveDim(chunk);
            if (dim == DIM_UNKNOWN) return;
            java.lang.reflect.Field yBase = EBS_YBASE;
            if (yBase == null) {
                yBase = ebs.getClass().getDeclaredField("field_76684_a");
                yBase.setAccessible(true);
                EBS_YBASE = yBase;
            }
            java.lang.reflect.Method get = EBS_GET;
            if (get == null) {
                get = ebs.getClass().getMethod("func_177485_a",
                        int.class, int.class, int.class);
                get.setAccessible(true);
                EBS_GET = get;
            }
            Object state = get.invoke(ebs, x, y, z);
            int sid = com.rustcraft.bridge.LightAuthorityHook.stateIdOf(state);
            if (sid < 0) {
                MIRROR_SID_UNRESOLVED.incrementAndGet();
                return;
            }
            int yAbs = yBase.getInt(ebs) + y;
            int r = NativeChunkBridge.mirrorBlockState(dim,
                    (int) k[0], (int) k[1], x, yAbs, z, sid);
            if (r == 1) MIRROR_STATE_SETS.incrementAndGet();
            else MIRROR_NOOP_OR_UNREGISTERED.incrementAndGet();
        } catch (Throwable t) {
            MIRROR_STATE_ERRORS.incrementAndGet();
        }
    }

    public static void onEbsBlockLightSet(Object ebs, int x, int y, int z,
                                          int value) {
        try {
            Object chunk;
            synchronized (EBS_OWNER) {
                chunk = EBS_OWNER.get(ebs);
            }
            if (chunk == null) return;
            long[] k = chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            int dim = resolveDim(chunk);
            if (dim == DIM_UNKNOWN) return;
            int yAbs = y; // EBS stores section-local; yBase added below
            java.lang.reflect.Field yBase;
            try {
                yBase = ebs.getClass().getDeclaredField("field_76684_a");
                yBase.setAccessible(true);
                yAbs = yBase.getInt(ebs) + y;
            } catch (Throwable ignore) {
                return;
            }
            NativeChunkRegistryHook.mirrorLight(dim, (int) k[0], (int) k[1],
                    x, yAbs, z, value);
        } catch (Throwable ignore) {
        }
    }

    /** Chunk.onChunkUnload RETURN: drop stale EBS bindings. By VALUE —
     *  storage replacement can leave pre-swap EBS objects in the map that
     *  the (already-replaced) array walk would miss; a value-walk removes
     *  every binding this chunk ever made. */
    private static void unbindEbsOwners(Object chunk) {
        synchronized (EBS_OWNER) {
            EBS_OWNER.values().removeIf(v -> v == chunk);
        }
    }

    /** Chunk.onLoad() at RETURN: if a native chunk is registered for these
     *  coords, request its first-touch full sync NOW — the Chunk exists at load
     *  (post-primer, light computed), so the flusher can sync before any player
     *  enters view. M4.3 authoritative-eligibility timing fix. */
    public static void onChunkLoaded(Object chunk) {
        HOOK_CHUNK_LOADED.incrementAndGet();
        bindEbsOwners(chunk);
        try {
            int dim = resolveDim(chunk);
            if (dim == DIM_UNKNOWN) return;
            long[] k = chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            // NATIVECHUNK_WORLD_REGISTRY §5: the chunk is in the loaded set
            // NOW — register it (packet admission is no longer the creator)
            NativeChunkRegistryHook.onChunkLoaded(chunk);
            if (com.rustcraft.bridge.NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]) > 0) {
                com.rustcraft.bridge.M4Coherency.requestSync(chunk);
            }
        } catch (Throwable ignore) { }
    }

    /** Chunk.onChunkUnload() at RETURN. */
    public static void onUnload(Object chunk) {
        HOOK_UNLOADS.incrementAndGet();
        unbindEbsOwners(chunk);
        int[] st = STATE.remove(chunk);
        // NATIVECHUNK_WORLD_REGISTRY §5: left the loaded set — invalidate
        // the native generation regardless of tracker state
        NativeChunkRegistryHook.onChunkUnload(chunk);
        if (st != null) {
            int dim = st[0];
            long[] k = chunkCoords(chunk);
            UNLOAD_QUEUE.add(new int[] { dim, (int) k[0], (int) k[1] });
            ChunkStateAuthorityBridge.unregisterChunkAuthority(dim, (int) k[0], (int) k[1]);
        }
    }

    // --- consumer-side drain (M4Coherency) ---

    /** Snapshot + clear pending work for one chunk. Returns the state array
     *  (dim, blockMask, lightMask, fullFlag) or null if untracked. */
    public static int[] takeWork(Object chunk) {
        int[] st = STATE.get(chunk);
        if (st == null) return null;
        int[] out;
        synchronized (st) {
            out = new int[] { st[0], st[1], st[2], st[3], st[4] };
            st[1] = 0; st[2] = 0; st[3] = 0; st[4] = 0;
        }
        return out;
    }

    /** Peek pending work WITHOUT consuming (M4.3 eligibility: all-zero means
     *  the native snapshot is current for blocks/light/biomes). Null if untracked. */
    public static int[] peekWork(Object chunk) {
        int[] st = STATE.get(chunk);
        if (st == null) return null;
        synchronized (st) {
            return new int[] { st[0], st[1], st[2], st[3], st[4] };
        }
    }

    /** Diagnostics: is the chunk's section EBS instance owner-bound? */
    public static boolean ebsOwnerBound(Object chunk, int sectionY) {
        try {
            Object[] arr = ebsArrayOf(chunk);
            if (arr == null || sectionY >= arr.length) return false;
            synchronized (EBS_OWNER) {
                return arr[sectionY] != null
                        && EBS_OWNER.get(arr[sectionY]) == chunk;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Direct access for iteration (does not clear). */
    public static ConcurrentHashMap<Object, int[]> stateView() { return STATE; }

    public static int trackedChunks() { return STATE.size(); }

    public static int pendingUnloads() { return UNLOAD_QUEUE.size(); }

    // --- helpers ---

    private static int[] state(Object chunk) {
        // get/putIfAbsent (NOT computeIfAbsent): JDK8's CHM.computeIfAbsent
        // has an NPE race under concurrent worldgen resize (seen live: 7
        // NPEs at ConcurrentHashMap.java:1645 during zsa1)
        int[] st = STATE.get(chunk);
        if (st != null) return st;
        st = new int[] { resolveDim(chunk), 0, 0, 0, 0, 0 };
        int[] prev = STATE.putIfAbsent(chunk, st);
        return prev != null ? prev : st;
    }

    /** Java-side mutation version of a chunk (monotonic; 0 = never mutated).
     *  The M4.2E temporal contract: a deferred packet captured at version V is
     *  comparable only if the version is STILL V when the native sync ran. */
    public static int versionOf(Object chunk) {
        int[] st = STATE.get(chunk);
        return st == null ? 0 : st[5];
    }

    /** Current (cached-or-resolved) dimension for a chunk, without creating an entry. */
    public static int dimOf(Object chunk) {
        int[] st = STATE.get(chunk);
        if (st != null) return st[0];
        return resolveDim(chunk);
    }

    private static int resolveDim(Object chunk) {
        try {
            if (CHUNK_WORLD == null) {
                CHUNK_WORLD = chunk.getClass().getDeclaredField("field_76637_e");
                CHUNK_WORLD.setAccessible(true);
            }
            Object world = CHUNK_WORLD.get(chunk);
            if (world == null) return 0;
            Object provider = null;
            try { provider = getAccessible(world.getClass(), "field_73011_w").get(world); } catch (Throwable ignore) {}
            if (provider == null) return DIM_UNKNOWN;
            try {
                Method m = provider.getClass().getMethod("getDimension");
                m.setAccessible(true);
                return (Integer) m.invoke(provider);
            } catch (Throwable ignore) { }
            try {
                Method m = provider.getClass().getMethod("func_186058_p");
                m.setAccessible(true);
                Object dt = m.invoke(provider);
                Method mId = dt.getClass().getMethod("func_186068_a");
                mId.setAccessible(true);
                return (Integer) mId.invoke(dt);
            } catch (Throwable ignore) { }
            return DIM_UNKNOWN;
        } catch (Throwable t) {
            return DIM_UNKNOWN;
        }
    }

    private static Field getAccessible(Class<?> c, String name) throws Exception {
        // Walk the hierarchy: WorldServer does not declare World's fields
        // (getDeclaredField alone returned NoSuchField -> DIM_UNKNOWN for every
        // chunk in the first instrumented coherency run).
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static volatile java.lang.reflect.Method CHUNK_GET_BLOCK_STATE;

    /** Chunk.getBlockState(BlockPos) = func_177435_g — the post-write
     *  canonical state at pos (identity-stable: the container's palette
     *  instance). This is the mirror's source of truth for the NEW state. */
    private static Object chunkGetBlockState(Object chunk, Object pos) throws Exception {
        java.lang.reflect.Method m = CHUNK_GET_BLOCK_STATE;
        if (m == null) {
            m = chunk.getClass().getMethod("func_177435_g", pos.getClass());
            m.setAccessible(true);
            CHUNK_GET_BLOCK_STATE = m;
        }
        return m.invoke(chunk, pos);
    }

    private static int blockPosX(Object pos) {
        try {
            if (POS_X == null) {
                POS_X = pos.getClass().getMethod("func_177958_n"); // Vec3i.getX (symbols-verified; func_177952_p is getZ)
                POS_X.setAccessible(true);
            }
            return (Integer) POS_X.invoke(pos);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static int blockPosZ(Object pos) {
        try {
            if (POS_Z == null) {
                POS_Z = pos.getClass().getMethod("func_177952_p"); // Vec3i.getZ (symbols-verified)
                POS_Z.setAccessible(true);
            }
            return (Integer) POS_Z.invoke(pos);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static int blockPosY(Object pos) {
        try {
            // OPT-FS-002: this was a PER-CALL getMethod+setAccessible (the
            // OPT-SYNC-006-C1 bug class) on one of the hottest hooks in the
            // system (onLightSet — ~34k calls/run; setAccessible's security
            // path allocates and shows as intern/getCallerClass windows in
            // the allocation sampler). Cache like POS_X/POS_Z.
            if (POS_Y == null) {
                POS_Y = pos.getClass().getMethod("func_177956_o");
                POS_Y.setAccessible(true);
            }
            return (Integer) POS_Y.invoke(pos);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** long[] { cx, cz } from the chunk's final position fields. */
    static long[] chunkCoords(Object chunk) {
        try {
            if (CHUNK_X == null) {
                CHUNK_X = getAccessible(chunk.getClass(), "field_76635_g");
                CHUNK_Z = getAccessible(chunk.getClass(), "field_76647_h");
                CHUNK_X.setAccessible(true);
                CHUNK_Z.setAccessible(true);
            }
            return new long[] { CHUNK_X.getInt(chunk), CHUNK_Z.getInt(chunk) };
        } catch (Throwable t) {
            return new long[] { Long.MIN_VALUE, Long.MIN_VALUE };
        }
    }
}
