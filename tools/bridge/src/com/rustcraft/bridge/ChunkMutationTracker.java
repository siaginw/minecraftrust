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
     *  replacement (Anvil load): full invalidation. */
    public static void onStorageReplaced(Object chunk) {
        HOOK_STORAGE_REPLACED.incrementAndGet();
        int[] st = state(chunk);
        synchronized (st) {
            st[3] = 1;
        }
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

    /** Chunk.onLoad() at RETURN: if a native chunk is registered for these
     *  coords, request its first-touch full sync NOW — the Chunk exists at load
     *  (post-primer, light computed), so the flusher can sync before any player
     *  enters view. M4.3 authoritative-eligibility timing fix. */
    public static void onChunkLoaded(Object chunk) {
        HOOK_CHUNK_LOADED.incrementAndGet();
        try {
            int dim = resolveDim(chunk);
            if (dim == DIM_UNKNOWN) return;
            long[] k = chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            if (com.rustcraft.bridge.NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]) > 0) {
                com.rustcraft.bridge.M4Coherency.requestSync(chunk);
            }
        } catch (Throwable ignore) { }
    }

    /** Chunk.onChunkUnload() at RETURN. */
    public static void onUnload(Object chunk) {
        HOOK_UNLOADS.incrementAndGet();
        int[] st = STATE.remove(chunk);
        if (st != null) {
            int dim = st[0];
            long[] k = chunkCoords(chunk);
            UNLOAD_QUEUE.add(new int[] { dim, (int) k[0], (int) k[1] });
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

    /** Direct access for iteration (does not clear). */
    public static ConcurrentHashMap<Object, int[]> stateView() { return STATE; }

    public static int trackedChunks() { return STATE.size(); }

    public static int pendingUnloads() { return UNLOAD_QUEUE.size(); }

    // --- helpers ---

    private static int[] state(Object chunk) {
        return STATE.computeIfAbsent(chunk, c -> new int[] { resolveDim(c), 0, 0, 0, 0, 0 });
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

    private static int blockPosY(Object pos) {
        try {
            Method m = pos.getClass().getMethod("func_177956_o");
            m.setAccessible(true);
            return (Integer) m.invoke(pos);
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
