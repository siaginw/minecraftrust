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

    // M5 Semantic Engine Ownership Telemetry Counters
    public static final AtomicLong RUST_READS = new AtomicLong();
    public static final AtomicLong JAVA_READS = new AtomicLong();
    public static final AtomicLong RUST_WRITES = new AtomicLong();
    public static final AtomicLong JAVA_WRITES = new AtomicLong();
    public static final AtomicLong FALLBACKS = new AtomicLong();
    public static final AtomicLong DEMOTIONS = new AtomicLong();
    public static final AtomicLong RESYNCS = new AtomicLong();
    public static final AtomicLong REFLECTION_BYPASS_DETECTED = new AtomicLong();
    public static final AtomicLong STATE_MAPPING_FAILURE = new AtomicLong();
    public static final AtomicLong TE_FALLBACK = new AtomicLong();
    public static final AtomicLong HIGH_STATE_FALLBACK = new AtomicLong();
    public static final AtomicLong STALE_HANDLE_FALLBACK = new AtomicLong();
    public static final AtomicLong ERROR_FALLBACK = new AtomicLong();
    public static final AtomicLong SECTION_CREATIONS = new AtomicLong();
    public static final AtomicLong SECTION_EMPTIED = new AtomicLong();

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
                // RUNSCOPE-JUSTIFIED: this bridge is loaded through the
                // LaunchClassLoader in every shape that reaches here, so the
                // forName resolves in Launch space where the deobf name exists;
                // field_176229_d is the SRG BLOCK_STATE_IDS field (live-verified).
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

    /** OPT-SYNC-001: all-air shell registration with zero Java-side bytes. */
    public static native long registerEmpty(int dim, int cx, int cz);

    /** OPT-SYNC-001: absent-section representation (air/0-light/sky-0). */
    public static native int markSectionAbsent(int dim, int cx, int cz, byte sectionY);

    /** OPT-SYNC-002 packed transfer: BitArray words + palette table +
     * lights (light_len_bytes == 4096) expanded natively. */
    public static native int refreshSectionPacked(int dim, int cx, int cz,
            byte sectionY, int bits, long wordsAddr, int wordsCountU64,
            long paletteAddr, int paletteCountU32, long lightAddr, int lightLenBytes);

    /** OPT-SYNC-002 independent validation read-back (out_len_bytes ==
     * 20480: 4096 LE u32 states + 2048 B block light + 2048 B sky light). */
    public static native int readbackSection(int dim, int cx, int cz,
            byte sectionY, long outAddr, int outLenBytes);

    public static native long seedFromTransport(long transportAddr, int transportLen);

    public static native int materializePrimer(int dim, int cx, int cz, long generationId, long primerOutAddr);

    public static native int getPrimaryBitMask(int dim, int cx, int cz, long generationId);

    public static native int encodePacketPayload(
            int dim, int cx, int cz, long generationId,
            byte skylight, byte fullChunk,
            long outputBufAddress, int outputBufCapacity);

    /**
     * V2 metadata from exactly one native packet serialization. Decode with
     * PacketEncodeResultV2; never pair its payload with a separate mask query.
     * Failure invalidates the output scratch buffer. This additive API does not
     * establish Java capture coherence or enable production packet authority.
     */
    public static native long encodePacketPayloadV2(
            int dim, int cx, int cz, long generationId,
            byte skylight, byte fullChunk,
            long outputBufAddress, int outputBufCapacity);

    /**
     * Measure-only V2 twin: exact payload byte count and emitted mask the real
     * encode would produce for the CURRENT chunk state. Writes nothing. Same
     * packed-long protocol and failure codes as encodePacketPayloadV2 (minus
     * the capacity codes). This is the length oracle that lets the single-copy
     * path frame the complete pre-compression packet header — including the
     * payload-length VarInt — before the payload is encoded straight into the
     * final buffer (exactly one large payload memory movement). The caller
     * must still verify the real encode's byte count against this value.
     */
    public static native long encodePacketPayloadV2Measure(
            int dim, int cx, int cz, long generationId,
            byte skylight, byte fullChunk);

    /** Calls V2 once and decodes only that result, without changing Java metrics. */
    public static PacketEncodeResultV2 encodePacketV2(
            int dim, int cx, int cz, long generationId,
            boolean skylight, boolean fullChunk,
            long outputBufAddress, int outputBufCapacity) {
        if (!nativeLoaded) throw new IllegalStateException("Native chunk library unavailable");
        return PacketEncodeResultV2.decode(encodePacketPayloadV2(
                dim, cx, cz, generationId,
                (byte) (skylight ? 1 : 0), (byte) (fullChunk ? 1 : 0),
                outputBufAddress, outputBufCapacity));
    }

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

    /**
     * M5 Authoritative setBlockState:
     * Sets block state at (x, y, z) in the registered native chunk.
     * Returns packed long containing status, old state, new state, and section flags.
     */
    public static native long setBlockState(int dim, int cx, int cz, int x, int y, int z, int newState);

    /**
     * M5 Direct getBlockState query (fallback / verification path).
     * Returns canonical global block state ID (0..65535), or negative error.
     */
    public static native int getBlockState(int dim, int cx, int cz, int x, int y, int z);

    /** M4.2D write-seam mirror: direct per-write block-light sync. */
    public static native int mirrorBlockLight(int dim, int cx, int cz,
            int x, int yAbs, int z, int value);

    /** §2 mutation-seam BLOCK STATE mirror (full-width u32 id): one Java
     *  semantic write -> one native transition (cell + dirty bit + single
     *  version advance). 1 changed / 0 unchanged-or-unregistered / -3 panic. */
    public static native int mirrorBlockState(int dim, int cx, int cz,
            int x, int yAbs, int z, int stateId);

    /** Plain block-light probe for the zero-stage compare harness (no
     *  generation witness; diagnostics only). */
    public static native int getBlockLightProbe(int dim, int cx, int cz,
            int x, int y, int z);

    /** Plain u16 state-id probe for the zero-stage compare harness:
     *  distinguishes a width-guarded section push (registry holds air)
     *  from a missed mutation hook. Diagnostics only. */
    public static native int getBlockStateProbe(int dim, int cx, int cz,
            int x, int y, int z);

    /**
     * M5 Direct pointers setup: writes 16 x 64-bit pointers to resident sections' states arrays.
     * outPtrsAddr: address of long[16] buffer. Returns 1 on success, 0 if chunk not registered.
     */
    public static native int getSectionPointers(int dim, int cx, int cz, long outPtrsAddr);

    /**
     * M5 Raw pointer to section Y's states array (0 if absent / unregistered).
     */
    public static native long getSectionPointer(int dim, int cx, int cz, int sectionY);

    /**
     * M5.3 Light direct pointers setup: writes 16 x 64-bit block light pointers and 16 x 64-bit sky light pointers.
     * outBlPtrsAddr: address of long[16] buffer for block light.
     * outSlPtrsAddr: address of long[16] buffer for sky light.
     * Returns 1 on success, 0 if chunk not registered.
     */
    public static native int getSectionLightPointers(int dim, int cx, int cz, long outBlPtrsAddr, long outSlPtrsAddr);

    /**
     * M5.3 Raw pointer to section Y's light array (isSkylight: 0 for block light, 1 for sky light).
     */
    public static native long getSectionLightPointer(int dim, int cx, int cz, int sectionY, int isSkylight);

    /**
     * M5.4 Biome direct pointer: returns raw memory address of chunk's [u8; 256] array.
     */
    public static native long getBiomesPointer(int dim, int cx, int cz);

    /**
     * M5.4 Biome direct read/write: gets or sets biome ID (0..255).
     */
    public static native int getBiome(int dim, int cx, int cz, int x, int z);
    public static native int setBiome(int dim, int cx, int cz, int x, int z, int biomeId);

    /**
     * M5.4 Heightmap direct pointer: returns raw memory address of chunk's [u16; 256] array.
     */
    public static native long getHeightmapPointer(int dim, int cx, int cz);

    /**
     * M5.4 Heightmap direct query and recompute.
     */
    public static native int getHeight(int dim, int cx, int cz, int x, int z);
    public static native int recomputeHeight(int dim, int cx, int cz, int x, int z);

    // --- Safe Invocations & Metrics ---

    public static long register(int dim, int cx, int cz, long primerAddr, long biomeAddr) {
        if (!nativeLoaded || primerAddr == 0) return 0L;
        long genId = registerPrimer(dim, cx, cz, primerAddr, biomeAddr);
        if (genId > 0) {
            REGISTERED_CHUNKS.incrementAndGet();
        }
        return genId;
    }

    /** OPT-SYNC-001: the empty-shell fast path (no 256 KiB zero buffer). */
    public static long registerShell(int dim, int cx, int cz) {
        if (!nativeLoaded) return 0L;
        long genId = registerEmpty(dim, cx, cz);
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
        sb.append("m5_rust_reads=").append(RUST_READS.get()).append("\n");
        sb.append("m5_java_reads=").append(JAVA_READS.get()).append("\n");
        sb.append("m5_rust_writes=").append(RUST_WRITES.get()).append("\n");
        sb.append("m5_java_writes=").append(JAVA_WRITES.get()).append("\n");
        sb.append("m5_fallbacks=").append(FALLBACKS.get()).append("\n");
        sb.append("m5_demotions=").append(DEMOTIONS.get()).append("\n");
        sb.append("m5_resyncs=").append(RESYNCS.get()).append("\n");
        sb.append("m5_reflection_bypass=").append(REFLECTION_BYPASS_DETECTED.get()).append("\n");
        sb.append("m5_state_mapping_failure=").append(STATE_MAPPING_FAILURE.get()).append("\n");
        sb.append("m5_te_fallback=").append(TE_FALLBACK.get()).append("\n");
        sb.append("m5_high_state_fallback=").append(HIGH_STATE_FALLBACK.get()).append("\n");
        sb.append("m5_stale_handle_fallback=").append(STALE_HANDLE_FALLBACK.get()).append("\n");
        sb.append("m5_error_fallback=").append(ERROR_FALLBACK.get()).append("\n");
        sb.append("m5_section_creations=").append(SECTION_CREATIONS.get()).append("\n");
        sb.append("m5_section_emptied=").append(SECTION_EMPTIED.get()).append("\n");
        if (nativeLoaded) {
            sb.append("m4_current_registered_count=").append(getRegisteredCount()).append("\n");
        }
    }
}
