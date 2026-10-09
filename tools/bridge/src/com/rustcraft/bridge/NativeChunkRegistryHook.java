package com.rustcraft.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * NATIVECHUNK_WORLD_REGISTRY (§1-§9): world-lifecycle-driven NativeChunk
 * registration. Loaded Java Chunk → NativeChunk registered exactly once
 * (Chunk.onLoad = SRG func_76631_c, injected by ChunkMutationTransformer —
 * fires on every load path regardless of packets, clients, or visibility);
 * unloaded Java Chunk → NativeChunk generation-invalidated/unregistered
 * (Chunk.onChunkUnload = func_76623_d). Packet admission REGISTERS nothing
 * anymore — it only LOOKS UP (§37: one lifecycle owner).
 *
 * Registration snapshot = the existing M4 machinery: registerPrimer (chunk
 * shell + states) + M4Coherency.refreshChunkNow (first-touch full sync of
 * states + block light + sky light + biomes, §13 — one Java→Rust snapshot
 * per CHUNK LOAD, never per light job). Coherence after registration is the
 * existing proven model: setBlockState → ChunkMutationTracker.onBlockSet →
 * native update + dirty refresh; light writes → onLightSet → light-dirty.
 *
 * DEFAULT OFF (-Drustcraft.worldRegistry=true).
 */
public final class NativeChunkRegistryHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.worldRegistry");

    // §16 coverage metrics
    public static final AtomicLong REGISTERS = new AtomicLong();
    public static final AtomicLong UNREGISTERS = new AtomicLong();
    public static final AtomicLong BOOTSTRAP_REGISTERS = new AtomicLong();
    public static final AtomicLong ALREADY_REGISTERED = new AtomicLong();
    public static final AtomicLong MISSING_LOOKUP = new AtomicLong();
    public static final AtomicLong STALE_LOOKUP = new AtomicLong();
    public static final AtomicLong REGISTER_NANOS = new AtomicLong();
    /// OPT-SYNC-005: registerShell JNI share of REGISTER_NANOS (the
    /// remainder is the refresh op itself: OP_WALL in M4Coherency)
    public static final AtomicLong REG_SHELL_NS = new AtomicLong();
    public static volatile String LAST_ERROR = "";

    private static volatile boolean BOOTSTRAPPED;

    private NativeChunkRegistryHook() { }

    /** M4.2D write-seam mirror: a Java EBS block-light write lands in
     *  the native registry AT THE MUTATION POINT (goal §14: one semantic
     *  mutation, one native update — no per-job refresh). No-op when the
     *  chunk is not registered (fail-safe: preRefresh/repair still
     *  covers first-touch). */
    public static void mirrorLight(int dim, int cx, int cz, int x, int yAbs,
                                   int z, int value) {
        try {
            NativeChunkBridge.mirrorBlockLight(dim, cx, cz, x, yAbs, z, value);
        } catch (Throwable t) {
            LAST_ERROR = "mirrorLight: " + t;
        }
    }

    /** Lazy invariant repair: register a LOADED chunk the lifecycle
     *  hooks missed (reload path that never reaches onLoad). Idempotent;
     *  counts as a normal register. */
    public static void repairRegister(Object chunk, int dim, int cx, int cz) {
        if (!ENABLED) return;
        try {
            registerChunk(chunk, dim, cx, cz, false);
        } catch (Throwable t) {
            LAST_ERROR = "repair: " + t;
        }
    }

    /** Is the given loaded chunk registered (by dim+coords)? */
    public static boolean isRegistered(int dim, int cx, int cz) {
        return NativeChunkBridge.findGeneration(dim, cx, cz) > 0;
    }

    /**
     * Chunk.onLoad RETURN (via ChunkMutationTracker.onChunkLoaded): the
     * chunk just became part of the server's loaded set. Register if
     * missing. Runs on the server thread.
     */
    public static void onChunkLoaded(Object chunk) {
        if (!ENABLED) return;
        try {
            long[] k = ChunkMutationTracker.chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            int dim = ChunkMutationTracker.dimOf(chunk);
            int cx = (int) k[0];
            int cz = (int) k[1];
            registerChunk(chunk, dim, cx, cz, false);
        } catch (Throwable t) {
            LAST_ERROR = String.valueOf(t);
        }
    }

    /**
     * Chunk.onChunkUnload RETURN (via ChunkMutationTracker.onUnload): the
     * chunk left the loaded set — invalidate the native generation.
     */
    public static void onChunkUnload(Object chunk) {
        if (!ENABLED) return;
        try {
            long[] k = ChunkMutationTracker.chunkCoords(chunk);
            if (k[0] == Long.MIN_VALUE) return;
            int dim = ChunkMutationTracker.dimOf(chunk);
            NativeChunkBridge.unload(dim, (int) k[0], (int) k[1]);
            UNREGISTERS.incrementAndGet();
        } catch (Throwable t) {
            LAST_ERROR = String.valueOf(t);
        }
    }

    private static void registerChunk(Object chunk, int dim, int cx, int cz,
                                      boolean bootstrap) {
        if (isRegistered(dim, cx, cz)) {
            ALREADY_REGISTERED.incrementAndGet();
            return;
        }
        long t0 = System.nanoTime();
        // shell with an EMPTY primer (all-air, zero biomes), then the
        // existing first-touch full sync fills real states + both lights
        // (M4Coherency.fullSync runs only for registered chunks)
        // OPT-SYNC-001: all-air shell with ZERO staged bytes (the old
        // path allocated + addressed a 256 KiB all-zero direct buffer per
        // chunk — 625 registrations/run = 160 MB of zeros crossed for nothing)
        long sh0 = System.nanoTime();
        long gen = NativeChunkBridge.registerShell(dim, cx, cz);
        REG_SHELL_NS.addAndGet(System.nanoTime() - sh0);
        if (gen > 0) {
            try {
                M4Coherency.refreshChunkNow(chunk);
            } catch (Exception e) {
                LAST_ERROR = "fullSync: " + e;
            }
            REGISTERS.incrementAndGet();
            if (bootstrap) BOOTSTRAP_REGISTERS.incrementAndGet();
            REGISTER_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    /**
     * §7 one-shot bootstrap: register every currently-loaded chunk (the
     * hook may initialize after chunks loaded). Invoked lazily from the
     * light authority's first job — no periodic world scans.
     */
    public static void bootstrapIfNeeded(Object world) {
        if (!ENABLED || BOOTSTRAPPED) return;
        synchronized (NativeChunkRegistryHook.class) {
            if (BOOTSTRAPPED) return;
            try {
                int n = 0;
                for (Object chunk : enumerateLoadedChunks(world)) {
                    long[] k = ChunkMutationTracker.chunkCoords(chunk);
                    if (k[0] == Long.MIN_VALUE) continue;
                    registerChunk(chunk, ChunkMutationTracker.dimOf(chunk),
                            (int) k[0], (int) k[1], true);
                    n++;
                }
                BOOTSTRAPPED = true;
                System.out.println("[RustCraft-WorldRegistry] bootstrap: "
                        + n + " loaded chunks, registered="
                        + BOOTSTRAP_REGISTERS.get());
            } catch (Throwable t) {
                LAST_ERROR = "bootstrap: " + t;
            }
        }
    }

    /** Loaded-chunk enumeration via the world's chunk provider map. */
    private static Iterable<Object> enumerateLoadedChunks(Object world)
            throws Exception {
        java.lang.reflect.Method getProvider = findMethod(world.getClass(),
                new String[]{"getChunkProvider", "func_72863_F"}, 0);
        Object provider = getProvider.invoke(world);
        // ChunkProviderServer.id2ChunkMap (Iterable<Chunk>) — SRG field_73244_b  // SRG-LINT-JUSTIFIED TODO: absent from index+forge.srg, verify
        for (java.lang.reflect.Field f : provider.getClass()
                .getDeclaredFields()) {
            if (java.util.Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object maybe = f.get(provider);
                if (maybe instanceof java.util.Map
                        && isChunkMap((java.util.Map<?, ?>) maybe)) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) maybe;
                    return new java.util.ArrayList<>(java.util.Collections
                            .unmodifiableCollection(
                                    (java.util.Collection<Object>) (Object)
                                            m.values()));
                }
            } else if (f.getType().getName().contains("Long2Object")) {
                f.setAccessible(true);
                Object maybe = f.get(provider);
                java.lang.reflect.Method values = maybe.getClass()
                        .getMethod("values");
                Object vals = values.invoke(maybe);
                return new java.util.ArrayList<>((java.util.Collection<Object>)
                        (Object) ((Iterable<?>) vals));
            }
        }
        throw new IllegalStateException("loaded-chunk map not found on "
                + provider.getClass().getName());
    }

    private static boolean isChunkMap(java.util.Map<?, ?> m) {
        for (Object v : m.values()) {
            return v != null && v.getClass().getName()
                    .endsWith(".Chunk");
        }
        return false;
    }

    private static java.lang.reflect.Method findMethod(Class<?> owner,
            String[] names, int params) throws Exception {
        for (java.lang.reflect.Method mm : owner.getMethods()) {
            for (String n : names) {
                if (n.equals(mm.getName()) && mm.getParameterCount() == params) {
                    mm.setAccessible(true);
                    return mm;
                }
            }
        }
        throw new IllegalStateException("method not found: "
                + java.util.Arrays.toString(names));
    }

    public static String dumpMetrics() {
        return "worldRegistry.enabled=" + ENABLED
                + " registers=" + REGISTERS.get()
                + " unregisters=" + UNREGISTERS.get()
                + " bootstrapRegisters=" + BOOTSTRAP_REGISTERS.get()
                + " alreadyRegistered=" + ALREADY_REGISTERED.get()
                + " missingLookup=" + MISSING_LOOKUP.get()
                + " staleLookup=" + STALE_LOOKUP.get()
                + " registerNanos=" + REGISTER_NANOS.get()
                + " lastError=" + LAST_ERROR;
    }
}
