package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4.3 — AUTHORITATIVE native-state packet payload source (clean Forge Target A
 * only, default OFF behind -Dminecraftrust.m1.native_state=ON_EXPERIMENTAL).
 *
 * Responsibility split (verified against the installed SPacketChunkData):
 *   RUST OWNS:  Protocol-340 section payload — block states, block light, sky
 *               light, biomes — encoded directly from the synchronized
 *               NativeChunk registry state.
 *   JAVA OWNS:  packet shell (chunkX/Z, mask, fullChunk flag), TileEntity
 *               enumeration + getUpdateTag list, PacketBuffer framing,
 *               compression/Netty. finishPacketPopulation performs all of it.
 *
 * STRICT eligibility — the packet stays JAVA unless native state is ALREADY
 * ready (no packet-thread sync, no waiting): registered + current generation +
 * fully synced + no pending tracker work (blocks/light/biomes all settled) +
 * full-chunk filter + supported mode. Any miss = immediate Java fallback with a
 * counted reason. The untouched Java constructor body runs on fallback.
 *
 * Sampled wire verification: a sampled fraction of authoritative payloads is
 * captured (bounded) and verified asynchronously against a freshly built Java
 * reference packet — never on the send path.
 */
public final class M4NativeStatePayload {

    public static final String MODE =
            System.getProperty("minecraftrust.m1.native_state", "OFF").toUpperCase();

    public static final AtomicLong ELIGIBLE = new AtomicLong();
    public static final AtomicLong TRANSMITTED = new AtomicLong();
    public static final AtomicLong BYTES_ON_WIRE = new AtomicLong();
    public static final AtomicLong FALLBACK_NOT_SYNCED = new AtomicLong();
    public static final AtomicLong FALLBACK_PENDING_WORK = new AtomicLong();
    public static final AtomicLong FALLBACK_NOT_REGISTERED = new AtomicLong();
    public static final AtomicLong FALLBACK_UNSUPPORTED_FILTER = new AtomicLong();
    public static final AtomicLong FALLBACK_ENCODE = new AtomicLong();
    public static final AtomicLong FALLBACK_ERROR = new AtomicLong();
    public static final AtomicLong FALLBACK_VERSION_GUARD = new AtomicLong();
    public static final AtomicLong GUARD_ACCEPTED = new AtomicLong();
    public static final AtomicLong SAMPLES_ENQUEUED = new AtomicLong();
    public static final AtomicLong BIOMES_REPUSHED = new AtomicLong();
    public static final AtomicLong LIGHT_SKY_REFRESHED = new AtomicLong();
    public static final AtomicLong LIGHT_BLOCK_REFRESHED = new AtomicLong();
    public static final AtomicLong LIGHT_FALLBACK = new AtomicLong();
    private static java.nio.ByteBuffer BIOME_CHECK;
    private static java.lang.reflect.Method M_GET_BIOMES_ARR;

    /** verification sample rate: every Nth eligible packet (0 = disabled). */
    public static final int SAMPLE_EVERY = Integer.getInteger("minecraftrust.m43.sample_every", 16);

    private static volatile boolean DISABLED = false;

    private static final java.nio.ByteBuffer OUT =
            java.nio.ByteBuffer.allocateDirect(262144).order(java.nio.ByteOrder.nativeOrder());

    private static int encodeOnce(int dim, int cx, int cz, long gen, boolean sky) throws Exception {
        OUT.clear();
        return NativeChunkBridge.encodePacket(dim, cx, cz, gen, sky, true, address(OUT), OUT.capacity());
    }

    private M4NativeStatePayload() {}

    /** Last native payload on this thread (identity guard: the pairing
     *  verifier must never pair a payload against itself). */
    static final ThreadLocal<byte[]> LAST_NATIVE_PAYLOAD = new ThreadLocal<>();

    /** Reentrancy guard: building the sampled Java REFERENCE constructs a
     *  nested SPacketChunkData, whose transformer-injected populatePacket would
     *  re-enter the native-state branch (recursion; the M4.3B live NPE). */
    static final ThreadLocal<Object> REF_BUILDING = new ThreadLocal<>();

    public static boolean enabled() {
        return "ON_EXPERIMENTAL".equalsIgnoreCase(MODE) && !DISABLED && REF_BUILDING.get() == null;
    }

    /**
     * Attempt the native-state payload for (chunk, filter).
     * Returns the payload bytes when native-authoritative is safe, else null
     * (caller runs the untouched Java path). No sync, no waiting, no TE work
     * here — all Java-owned behavior stays in NativeChunkPacket.
     */
    public static byte[] tryEncode(Object chunk, int filter) {
        final Object this_ = M4NativeStatePayload.class; // guard token
        try {
            if ((filter & 0xFFFF) != 0xFFFF) {
                FALLBACK_UNSUPPORTED_FILTER.incrementAndGet();
                return null; // partial-filter packets remain Java-only
            }
            if (!M4Coherency.isFullySynced(chunk)) {
                FALLBACK_NOT_SYNCED.incrementAndGet();
                return null;
            }
            if (M4Coherency.lastSyncedVersion(chunk) != ChunkMutationTracker.versionOf(chunk)) {
                FALLBACK_VERSION_GUARD.incrementAndGet(); // stale snapshot: NEVER transmit
                M4Coherency.requestDirtyRefresh(chunk);
                return null;
            }
            int[] pending = ChunkMutationTracker.peekWork(chunk);
            if (pending != null && ((pending[1] | pending[2] | pending[4]) != 0 || pending[3] != 0)) {
                FALLBACK_PENDING_WORK.incrementAndGet();
                return null;
            }
            int dim = ChunkMutationTracker.dimOf(chunk);
            if (dim == ChunkMutationTracker.DIM_UNKNOWN) {
                FALLBACK_NOT_REGISTERED.incrementAndGet();
                return null;
            }
            long[] k = ChunkMutationTracker.chunkCoords(chunk);
            if (M_GET_BIOMES_ARR == null) {
                M_GET_BIOMES_ARR = chunk.getClass().getMethod("func_76605_m");
                M_GET_BIOMES_ARR.setAccessible(true);
            }
            long gen = NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]);
            if (gen <= 0) {
                FALLBACK_NOT_REGISTERED.incrementAndGet();
                return null;
            }
            boolean sky = skylightOf(chunk);
            int n = encodeOnce(dim, (int) k[0], (int) k[1], gen, sky);
            if (n <= 0) {
                FALLBACK_ENCODE.incrementAndGet();
                return null; // includes -3 stale-token rejection
            }
            byte[] payload = new byte[n];
            OUT.clear(); OUT.get(payload);
            LAST_NATIVE_PAYLOAD.set(payload); // identity: pairing skips self

            // M4.3C biome freshness: vanilla's surface pass writes biome bytes
            // DIRECTLY into the chunk array (no setBiomeArray call — unobservable
            // by hooks). Cheap authoritative check: compare current Java bytes to
            // native and re-push if stale (256-byte copy, no sync).
            try {
                if (BIOME_CHECK == null) {
                    BIOME_CHECK = java.nio.ByteBuffer.allocateDirect(256).order(java.nio.ByteOrder.nativeOrder());
                }
                BIOME_CHECK.clear();
                if (NativeChunkBridge.getBiomes(dim, (int) k[0], (int) k[1], address(BIOME_CHECK)) == 1) {
                    byte[] live = (byte[]) M_GET_BIOMES_ARR.invoke(chunk);
                    BIOME_CHECK.clear();
                    byte[] nat = new byte[256];
                    BIOME_CHECK.get(nat);
                    if (!java.util.Arrays.equals(live, nat)) {
                        BIOME_CHECK.clear(); BIOME_CHECK.put(live); BIOME_CHECK.clear();
                        NativeChunkBridge.setBiomes(dim, (int) k[0], (int) k[1], address(BIOME_CHECK));
                        BIOMES_REPUSHED.incrementAndGet();
                    }
                }
            } catch (Throwable t) {
                FALLBACK_ERROR.incrementAndGet();
                return null; // cannot prove biome freshness: Java fallback
            }

            GUARD_ACCEPTED.incrementAndGet();
            // M5.2 LIGHT freshness domain (its own validity, like biomes):
            // compare live Java nibble arrays vs native for every mask section;
            // stale => coarse refresh via markSectionMutation + re-extract push,
            // or Java fallback. Never transmit stale light.
            try {
                Object[] storages = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                java.lang.reflect.Method getBL = null, getSL = null, nibGet = null;
                getBL = storages.getClass().getComponentType().getMethod("func_76661_k");
                getBL.setAccessible(true);
                getSL = storages.getClass().getComponentType().getMethod("func_76671_l");
                getSL.setAccessible(true);
                nibGet = Class.forName("net.minecraft.world.chunk.NibbleArray").getMethod("func_177481_a");
                nibGet.setAccessible(true);
                java.nio.ByteBuffer lb = java.nio.ByteBuffer.allocateDirect(4096)
                        .order(java.nio.ByteOrder.nativeOrder());
                long laddr = address(lb);
                int mask = NativeChunkBridge.getPrimaryBitMask(dim, (int) k[0], (int) k[1], gen);
                for (int y = 0; y < 16; y++) {
                    if ((mask & (1 << y)) == 0) continue;
                    Object st = storages[y];
                    if (st == null) continue;
                    // live Java arrays
                    byte[] jbl = new byte[2048], jsl = new byte[2048];
                    Object blN = getBL.invoke(st);
                    if (blN != null) System.arraycopy(nibGet.invoke(blN), 0, jbl, 0, 2048);
                    Object slN = getSL.invoke(st);
                    if (slN != null) System.arraycopy(nibGet.invoke(slN), 0, jsl, 0, 2048);
                    // native arrays
                    lb.clear();
                    if (NativeChunkBridge.getSectionLight(dim, (int) k[0], (int) k[1], (byte) y, laddr) != 1) {
                        LIGHT_FALLBACK.incrementAndGet();
                        return null; // cannot prove light freshness: Java fallback
                    }
                    byte[] nat = new byte[4096];
                    lb.clear(); lb.get(nat);
                    boolean blEq = java.util.Arrays.equals(jbl, java.util.Arrays.copyOfRange(nat, 0, 2048));
                    boolean slEq = java.util.Arrays.equals(jsl, java.util.Arrays.copyOfRange(nat, 2048, 4096));
                    if (!blEq) LIGHT_BLOCK_REFRESHED.incrementAndGet();
                    if (!slEq) LIGHT_SKY_REFRESHED.incrementAndGet();
                    if (!blEq || !slEq) {
                        // coarse refresh: re-push this section's full state+light
                        M4Coherency.refreshOneSectionCoarse(chunk, dim, (int) k[0], (int) k[1], y, st);
                        // recheck once
                        lb.clear();
                        if (NativeChunkBridge.getSectionLight(dim, (int) k[0], (int) k[1], (byte) y, laddr) != 1) {
                            LIGHT_FALLBACK.incrementAndGet();
                            return null;
                        }
                        byte[] nat2 = new byte[4096];
                        lb.clear(); lb.get(nat2);
                        boolean ok2 = java.util.Arrays.equals(jbl, java.util.Arrays.copyOfRange(nat2, 0, 2048))
                                && java.util.Arrays.equals(jsl, java.util.Arrays.copyOfRange(nat2, 2048, 4096));
                        if (!ok2) {
                            LIGHT_FALLBACK.incrementAndGet();
                            return null; // still stale: Java fallback
                        }
                    }
                }
            } catch (Throwable t) {
                LIGHT_FALLBACK.incrementAndGet();
                return null; // cannot prove: Java fallback
            }

            // re-encode AFTER the light domain may have refreshed sections
            n = encodeOnce(dim, (int) k[0], (int) k[1], gen, sky);
            if (n <= 0) {
                FALLBACK_ENCODE.incrementAndGet();
                return null;
            }

            payload = new byte[n];   // re-encoded payload (post-light-refresh)
            OUT.clear(); OUT.get(payload);
            LAST_NATIVE_PAYLOAD.set(payload);

            ELIGIBLE.incrementAndGet();
            TRANSMITTED.incrementAndGet();
            BYTES_ON_WIRE.addAndGet(n);

            // M4.3B sampled INDEPENDENT verification by PAIRING: record this
            // native payload; the NEXT REAL Java-serialized packet for the same
            // chunk at the SAME mutation version becomes the reference (fully
            // independent encoders, temporally identical state, no nested
            // construction — the nested ctor NPE'd inside the live constructor).
            if (SAMPLE_EVERY > 0 && (TRANSMITTED.get() % SAMPLE_EVERY) == 0) {
                // M5.3: pending-sample capture disabled with the builder (dead path);
                // counters can no longer increment.
                // M4PacketCompare.recordPendingAuthSample(chunk, dim, (int) k[0], (int) k[1],
                //         ChunkMutationTracker.versionOf(chunk), payload, gen);
                // SAMPLES_ENQUEUED.incrementAndGet();
            }
            return payload;
        } catch (Throwable t) {
            FALLBACK_ERROR.incrementAndGet();
            WorldgenShadow.recordCoherencyError("nativeStatePayload: " + t, t);
            return null;
        }
    }

    /** Disable the authoritative source after a correctness failure (stop condition). */
    public static void disableAuthoritative(String why) {
        DISABLED = true;
        System.err.println("[M43-NATIVE-STATE-DISABLED] " + why);
    }

    public static boolean isDisabled() { return DISABLED; }

    private static Method PROV_SKY_M;
    private static Field W_PROV_F, CHUNK_WORLD_F;

    private static boolean skylightOf(Object chunk) throws Exception {
        if (CHUNK_WORLD_F == null) {
            CHUNK_WORLD_F = findField(chunk.getClass(), "field_76637_e");
            CHUNK_WORLD_F.setAccessible(true);
        }
        Object world = CHUNK_WORLD_F.get(chunk);
        if (world == null) return true; // overworld default
        if (W_PROV_F == null) {
            W_PROV_F = findField(world.getClass(), "field_73011_w");
            W_PROV_F.setAccessible(true);
        }
        Object prov = W_PROV_F.get(world);
        if (prov == null) return true;
        if (PROV_SKY_M == null) {
            Class<?> c = prov.getClass();
            while (c != null) {
                try { PROV_SKY_M = c.getDeclaredMethod("func_191066_m"); break; }
                catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            }
            if (PROV_SKY_M != null) PROV_SKY_M.setAccessible(true);
        }
        return PROV_SKY_M != null && (Boolean) PROV_SKY_M.invoke(prov);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static long address(java.nio.Buffer b) throws Exception {
        if (AF == null) { AF = java.nio.Buffer.class.getDeclaredField("address"); AF.setAccessible(true); }
        return AF.getLong(b);
    }
    private static Field AF;

    public static String statsLine() {
        return " m43_mode=" + MODE
                + " m43_disabled=" + DISABLED
                + " m43_eligible=" + ELIGIBLE.get()
                + " m43_transmitted=" + TRANSMITTED.get()
                + " m43_bytes_on_wire=" + BYTES_ON_WIRE.get()
                + " m43_fallback_not_synced=" + FALLBACK_NOT_SYNCED.get()
                + " m43_fallback_pending_work=" + FALLBACK_PENDING_WORK.get()
                + " m43_fallback_not_registered=" + FALLBACK_NOT_REGISTERED.get()
                + " m43_fallback_unsupported_filter=" + FALLBACK_UNSUPPORTED_FILTER.get()
                + " m43_fallback_encode=" + FALLBACK_ENCODE.get()
                + " m43_fallback_error=" + FALLBACK_ERROR.get()
                + " m43_samples_enqueued=" + SAMPLES_ENQUEUED.get()
                + " m43_biomes_repushed=" + BIOMES_REPUSHED.get()
                + " m43_guard_accepted=" + GUARD_ACCEPTED.get()
                + " m43_guard_rejected=" + FALLBACK_VERSION_GUARD.get()
                + " m52_light_sky_refreshed=" + LIGHT_SKY_REFRESHED.get()
                + " m52_light_block_refreshed=" + LIGHT_BLOCK_REFRESHED.get()
                + " m52_light_fallback=" + LIGHT_FALLBACK.get();
    }
}
