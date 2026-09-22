package com.rustcraft.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI Bridge for M4 NativeChunk foundation:
 * - Persistent native chunk state retention
 * - Bulk materialization & conversion
 * - Zero-copy M1 SPacketChunkData payload encoding
 * - Spatial occupancy summaries & persistence staging
 * - Coherency invalidation & unload tracking
 */
public final class NativeChunkBridge {

    private static volatile boolean nativeLoaded = false;

    public static final AtomicLong REGISTERED_CHUNKS = new AtomicLong();
    public static final AtomicLong MATERIALIZED_CHUNKS = new AtomicLong();
    public static final AtomicLong PACKET_ENCODED_CHUNKS = new AtomicLong();
    public static final AtomicLong INVALIDATED_CHUNKS = new AtomicLong();
    public static final AtomicLong UNLOADED_CHUNKS = new AtomicLong();
    public static final AtomicLong OCCUPANCY_QUERIES = new AtomicLong();
    public static final AtomicLong PERSISTENCE_STAGES = new AtomicLong();

    static {
        try {
            try {
                System.loadLibrary("rustcraft_ffi");
                nativeLoaded = true;
            } catch (Throwable t) {
                try {
                    System.load(new java.io.File("rustcraft_ffi.dll").getAbsolutePath());
                    nativeLoaded = true;
                } catch (Throwable t2) {
                    nativeLoaded = false;
                }
            }
        } catch (Throwable t) {
            nativeLoaded = false;
        }
        if (nativeLoaded) {
            try {
                // Global-palette bit width = ceil(log2(BLOCK_STATE_IDS.size())), the exact
                // vanilla formula (MathHelper.log2). Reflective so this class stays
                // loadable without Minecraft on the classpath.
                java.lang.reflect.Field regF = Class.forName("net.minecraft.block.Block")
                        .getField("field_176229_d");
                Object reg = regF.get(null);
                int size = (Integer) reg.getClass().getMethod("func_186804_a").invoke(reg);
                // MathHelper.log2 = ceil(log2(size)) = 32 - nlz(size-1), verified
                // against the real method incl. power-of-two boundaries (M4.2A B1):
                // pow2 sizes take the isPowerOfTwo branch and do NOT round up.
                int bits = size <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(size - 1);
                setGlobalPaletteBits((byte) Math.max(5, bits));
                LIVE_GLOBAL_PALETTE_BITS = bits;
            } catch (Throwable t) {
                // No registry on classpath (offline benches): keep native default (vanilla 8602 -> 14)
                LIVE_GLOBAL_PALETTE_BITS = -1;
            }
        } else {
            LIVE_GLOBAL_PALETTE_BITS = -1;
        }
    }

    /** Bits actually set from the live registry, or -1 when unavailable. */
    public static volatile int LIVE_GLOBAL_PALETTE_BITS = -1;

    public static boolean isAvailable() {
        return nativeLoaded;
    }

    // --- JNI Native Declarations ---

    public static native long registerPrimer(int dim, int cx, int cz, long primerAddr, long biomeAddr);

    public static native int materializePrimer(int dim, int cx, int cz, long generationId, long primerOutAddr);

    public static native int getPrimaryBitMask(int dim, int cx, int cz, long generationId);

    public static native int encodePacketPayload(
            int dim, int cx, int cz, long generationId,
            byte skylight, byte fullChunk,
            long outputBufAddress, int outputBufCapacity);

    public static native int getOccupancySummary(int dim, int cx, int cz, long generationId, long outCountAddr);

    public static native int stagePersistence(
            int dim, int cx, int cz, long generationId,
            long outputBufAddress, int outputBufCapacity);

    public static native int invalidateChunk(int dim, int cx, int cz);

    public static native int unloadChunk(int dim, int cx, int cz);

    public static native int markMutation(int dim, int cx, int cz);

    /** M4.1 refresh model: section-granular dirty mark. Returns new dirty mask, or -1. */
    public static native int markSectionMutation(int dim, int cx, int cz, byte sectionY);

    /** M4.1 refresh model: replace one section (8KB states + optional 2KB+2KB light).
     *  Returns remaining dirty mask, or -1. */
    public static native int refreshSection(int dim, int cx, int cz, byte sectionY,
            long statesAddr, long blockLightAddr, long skyLightAddr);

    /** M4.1 refresh model: current per-section dirty mask, or -1 if unregistered. */
    public static native int getDirtySections(int dim, int cx, int cz);

    /** M4.1: set global-palette bits from live registry size (vanilla: ceil(log2(BLOCK_STATE_IDS.size()))). */
    public static native int setGlobalPaletteBits(byte bits);

    /** M4.2A C2: current generation id at (dim,cx,cz) or 0. */
    public static native long findGeneration(int dim, int cx, int cz);

    /** M4.2A E: 8x i64 retention/allocation stats written to outAddr. */
    public static native int getRegistryStats(long outAddr);

    /** M4.2B: replace the chunk's 256-byte biome array (flush/comparator path). */
    public static native int setBiomes(int dim, int cx, int cz, long biomeAddr);

    /** M4.2C: 4 x i64 [genId, mutationGen, snapshotGen, dirtyMask] to outAddr. */
    public static native int getGenerationInfo(int dim, int cx, int cz, long outAddr);

    /** M4.2D: in-JVM registry cleanup with release accounting; returns chunks removed. */
    /** M4.3C: read current native biome array for freshness check. */
    public static native int getBiomes(int dim, int cx, int cz, long outAddr);

    /** M5.2: read section light (2048 block + 2048 sky) for freshness checks. */
    public static native int getSectionLight(int dim, int cx, int cz, byte sectionY, long outAddr);

    public static native int registryClear();

    public static native int getRegisteredCount();

    // --- Safe Invocations & Metrics ---

    public static long register(int dim, int cx, int cz, long primerAddr, long biomeAddr) {
        if (!nativeLoaded || primerAddr == 0) return 0L;
        long genId = registerPrimer(dim, cx, cz, primerAddr, biomeAddr);
        if (genId > 0) {
            REGISTERED_CHUNKS.incrementAndGet();
        }
        return genId;
    }

    public static int encodePacket(int dim, int cx, int cz, long genId, boolean skylight, boolean fullChunk, long outAddr, int outCap) {
        if (!nativeLoaded || outAddr == 0 || outCap <= 0) return -1;
        int bytes = encodePacketPayload(dim, cx, cz, genId, (byte)(skylight ? 1 : 0), (byte)(fullChunk ? 1 : 0), outAddr, outCap);
        if (bytes > 0) {
            PACKET_ENCODED_CHUNKS.incrementAndGet();
        }
        return bytes;
    }

    public static int invalidate(int dim, int cx, int cz) {
        if (!nativeLoaded) return 0;
        int res = invalidateChunk(dim, cx, cz);
        if (res > 0) {
            INVALIDATED_CHUNKS.incrementAndGet();
        }
        return res;
    }

    public static int unload(int dim, int cx, int cz) {
        if (!nativeLoaded) return 0;
        int res = unloadChunk(dim, cx, cz);
        if (res > 0) {
            UNLOADED_CHUNKS.incrementAndGet();
        }
        return res;
    }

    public static void dumpMetrics(java.lang.StringBuilder sb) {
        sb.append("m4_native_chunk_available=").append(nativeLoaded).append("\n");
        sb.append("m4_registered_chunks=").append(REGISTERED_CHUNKS.get()).append("\n");
        sb.append("m4_materialized_chunks=").append(MATERIALIZED_CHUNKS.get()).append("\n");
        sb.append("m4_packet_encoded_chunks=").append(PACKET_ENCODED_CHUNKS.get()).append("\n");
        sb.append("m4_invalidated_chunks=").append(INVALIDATED_CHUNKS.get()).append("\n");
        sb.append("m4_unloaded_chunks=").append(UNLOADED_CHUNKS.get()).append("\n");
        sb.append("m4_occupancy_queries=").append(OCCUPANCY_QUERIES.get()).append("\n");
        sb.append("m4_persistence_stages=").append(PERSISTENCE_STAGES.get()).append("\n");
        if (nativeLoaded) {
            sb.append("m4_current_registered_count=").append(getRegisteredCount()).append("\n");
        }
    }
}
