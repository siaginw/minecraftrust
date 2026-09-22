package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BitArray;
import net.minecraft.util.IntIdentityHashBiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native SPacketChunkData bridge for M1.4.
 *
 * Modes (volatile, runtime-toggleable):
 *   OFF             - 100% Java reference behavior.
 *   SHADOW          - Java payload transmitted; native payload computed,
 *                     byte-compared against Java reference, released. (M1.3 proven path)
 *   ON_EXPERIMENTAL - native section payload used for the packet; on success the
 *                     Java constructor body is skipped; any failure falls back to
 *                     full Java construction for that packet.
 *
 * Timing integrity rule (M1.4 §0/§0B): JNI transition cost is MEASURED via the
 * jniNoop calibration export; no timing value is ever hardcoded.
 *
 * Buffer ownership rule (M1.4 §12): in ON_EXPERIMENTAL the direct output buffer
 * NEVER enters the Netty pipeline. Native bytes are copied into the packet's
 * heap byte[] (same field vanilla allocates) and the direct buffer is released
 * before return. Ownership unambiguous: acquire refCnt=1 -> release refCnt=0 in
 * the same statement scope; no window where an external component can hold it.
 */
public class NativeChunkPacket {

    public static final short SCHEMA_VERSION = 1;
    public static final int MAX_STAGING_CAPACITY = 262144; // 256 KB
    public static final int MAX_OUTPUT_CAPACITY = 262144;  // 256 KB

    private static volatile boolean nativeLoaded = false;
    private static volatile String runtimeMode =
            System.getProperty("minecraftrust.native_chunk_packet", "OFF");

    // ------------------------------------------------------------------
    // Metrics (M1.4 §29)
    // ------------------------------------------------------------------
    public static final AtomicLong M1_NATIVE_PACKETS_TRANSMITTED = new AtomicLong(0);
    public static final AtomicLong M1_JAVA_PACKETS_TRANSMITTED = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_ELIGIBLE = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_INELIGIBLE = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_FALLBACKS = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_FALLBACK_SCHEMA = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_FALLBACK_EXTRACTOR = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_FALLBACK_BUFFER = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_FALLBACK_ENCODE = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_BYTES_TRANSMITTED = new AtomicLong(0);

    public static final AtomicLong M1_SHADOW_PACKETS = new AtomicLong(0);
    public static final AtomicLong M1_PAYLOAD_PREDICT_NS = new AtomicLong(0);
    /** M1.4-R2 S11: packets below this predicted wire size go to the Java path. */
    public static volatile int M1_NATIVE_MIN_WIRE_BYTES = 8192;
    /** M1.4-R2 S3: test hook — 0 disables the gate (rule applied offline). */
    public static void setMinWireBytes(int v) { M1_NATIVE_MIN_WIRE_BYTES = v; }
    /** M1.4-R2 S11: packets skipped because predicted wire size < threshold. */
    public static final AtomicLong M1_NATIVE_SKIPPED_SMALL = new AtomicLong(0);
    public static final AtomicLong M1_ALLOC_NS = new AtomicLong(0);
    /** M1.4-R2 handoff selector: "0" = baseline double-copy, "A" = critical-array. */
    private static volatile String HANDOFF_MODE =
            System.getProperty("minecraftrust.m1.handoff", "0");
    public static String getHandoffMode() { return HANDOFF_MODE; }
    public static void setHandoffMode(String m) { HANDOFF_MODE = m; }

    /** M1G §2 (operator directive 2026-09-19): packets rejected by the cheap
     *  pre-staging size bound BEFORE staging / predictOutputLen run. */
    public static final AtomicLong M1_NATIVE_PREGATE_SKIPPED = new AtomicLong(0);
    /** M1G §2: cumulative cost of the pre-staging bound itself. */
    public static final AtomicLong M1_PREGATE_NS = new AtomicLong(0);

    /** Probe-only: run the shared packet-shell population phase in isolation. */
    public static boolean finishForProbe(Object packet, Chunk chunkIn, int filter,
                                         ExtendedBlockStorage[] sections, byte[] payload) throws Exception {
        return finishPacketPopulation(packet, chunkIn, filter, filter == 65535, sections, payload);
    }
    public static final AtomicLong M1_SHADOW_CONSECUTIVE_MATCHES = new AtomicLong(0);
    public static final AtomicLong M1_SHADOW_MISMATCHES = new AtomicLong(0);
    public static final AtomicLong M1_FALLBACKS = new AtomicLong(0);
    public static final AtomicLong M1_FOAMFIX_ADAPTER_PACKETS = new AtomicLong(0);
    public static final AtomicLong M1_FOAMFIX_FALLBACK_PACKETS = new AtomicLong(0);
    public static final AtomicLong M1_FOAMFIX_NATIVE_PACKETS = new AtomicLong(0);
    public static final AtomicLong M1_PHOSPHOR_NATIVE_PACKETS = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_ERRORS = new AtomicLong(0);
    public static final AtomicLong M1_NATIVE_PANICS = new AtomicLong(0);
    public static final AtomicLong M1_TRANSFORMER_CONFLICTS = new AtomicLong(0);

    // Timing accumulators (nanoseconds). M1_JNI_RUST_NS is the full crossing
    // window incl. Rust work; pure-transition share comes ONLY from jniNoop
    // calibration samples — never a constant.
    public static final AtomicLong M1_NETTY_BUFFER_ACQUIRE_NS = new AtomicLong(0);
    public static final AtomicLong M1_STAGE_NS = new AtomicLong(0);
    public static final AtomicLong M1_JNI_RUST_NS = new AtomicLong(0);
    public static final AtomicLong M1_JNI_CALIBRATION_NS = new AtomicLong(0);
    public static final AtomicLong M1_JNI_CALIBRATION_SAMPLES = new AtomicLong(0);
    public static final AtomicLong M1_HANDOFF_NS = new AtomicLong(0);
    public static final AtomicLong M1_TOTAL_NATIVE_NS = new AtomicLong(0);
    public static final AtomicLong M1_COMPARE_NS = new AtomicLong(0);
    public static final AtomicLong M1_TE_TAG_NS = new AtomicLong(0);

    // Volume accumulators
    public static final AtomicLong M1_BYTES_STAGED = new AtomicLong(0);
    public static final AtomicLong M1_BYTES_OUTPUT = new AtomicLong(0);

    // ------------------------------------------------------------------
    // Thread-local off-heap staging buffer (256 KB capacity)
    // ------------------------------------------------------------------
    private static final ThreadLocal<ByteBuffer> THREAD_LOCAL_STAGING = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(MAX_STAGING_CAPACITY));

    // Reflection fields cached for extraction
    private static Field fieldBits;
    private static Field fieldPalette;
    private static Field fieldRegPalette;
    private static Field fieldStorage;
    private static Field fieldLinearArraySize;
    private static Field fieldLinearStates;
    private static Field fieldHashMapMap;

    // SPacketChunkData reflection fields
    private static Field packetChunkX;
    private static Field packetChunkZ;
    private static Field packetMask;
    private static Field packetBuffer;
    private static Field packetTEs;
    private static Field packetFull;

    static {
        try {
            System.loadLibrary("rustcraft_ffi");
            nativeLoaded = true;
        } catch (Throwable t) {
            nativeLoaded = false;
        }

        try {
            fieldBits = BlockStateContainer.class.getDeclaredField("field_186024_e");
            fieldPalette = BlockStateContainer.class.getDeclaredField("field_186022_c");
            fieldRegPalette = BlockStateContainer.class.getDeclaredField("field_186023_d");
            fieldStorage = BlockStateContainer.class.getDeclaredField("field_186021_b");
            fieldBits.setAccessible(true);
            fieldPalette.setAccessible(true);
            fieldRegPalette.setAccessible(true);
            fieldStorage.setAccessible(true);

            for (Field f : BlockStatePaletteLinear.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == int.class) fieldLinearArraySize = f;
                else if (f.getType() == IBlockState[].class) fieldLinearStates = f;
            }
            for (Field f : BlockStatePaletteHashMap.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (IntIdentityHashBiMap.class.isAssignableFrom(f.getType())) fieldHashMapMap = f;
            }
        } catch (Throwable t) {
            fieldBits = null; // force extractor ineligibility on unknown layout
        }

        try {
            packetChunkX = SPacketChunkData.class.getDeclaredField("field_149284_a");
            packetChunkZ = SPacketChunkData.class.getDeclaredField("field_149282_b");
            packetMask = SPacketChunkData.class.getDeclaredField("field_186948_c");
            packetBuffer = SPacketChunkData.class.getDeclaredField("field_186949_d");
            packetTEs = SPacketChunkData.class.getDeclaredField("field_189557_e");
            packetFull = SPacketChunkData.class.getDeclaredField("field_149279_g");
            packetChunkX.setAccessible(true);
            packetChunkZ.setAccessible(true);
            packetMask.setAccessible(true);
            packetBuffer.setAccessible(true);
            packetTEs.setAccessible(true);
            packetFull.setAccessible(true);
        } catch (Throwable t) {
            packetBuffer = null; // force hook ineligibility
        }

        System.out.println("[RustCraft] M1 native chunk packet mode: " + runtimeMode);
    }

    public static boolean isNativeLoaded() { return nativeLoaded; }

    public static String getRuntimeMode() { return runtimeMode; }

    public static synchronized void setRuntimeMode(String mode) {
        if ("OFF".equalsIgnoreCase(mode) || "SHADOW".equalsIgnoreCase(mode)
                || "ON_EXPERIMENTAL".equalsIgnoreCase(mode)) {
            runtimeMode = mode.toUpperCase();
            System.setProperty("minecraftrust.native_chunk_packet", runtimeMode);
            System.out.println("[RustCraft] M1 native chunk packet mode set to: " + runtimeMode);
        } else {
            System.err.println("[RustCraft] Invalid runtime mode: " + mode);
        }
    }

    // ------------------------------------------------------------------
    // Native entry points
    // ------------------------------------------------------------------
    public static native int encodeSections(
            long stagingBufAddress, int stagingBufLen,
            long outputBufAddress, int outputCapacity);

    /** Zero-work JNI crossing: measured round-trip = pure transition cost. */
    public static native int jniNoop();

    /** M1.4-R2 §5: exact wire-size prediction from staging (pure scan, no writes). */
    public static native int predictOutputLen(long stagingBufAddress, int stagingBufLen);

    /** M1.4-R2 §4 debug: probe JNIEnv table slot with GetVersion(expected 0x00010008). */
    public static native int jniProbeVersion(int slot);

    /** M1.4-R2 HANDOFF-A: encode directly into the packet's final heap byte[]
     *  via GetPrimitiveArrayCritical. Array length MUST equal predicted size. */
    public static native int encodeSectionsIntoArray(
            long stagingBufAddress, int stagingBufLen, byte[] outputArray);

    public static native int testForcedPanic();

    /** Test-only Rust export: fill raw native memory [addr, addr+len) with pattern.
     *  Used by NettyAddressRegressionTest — never call from production paths. */
    public static native int fillPattern(long addr, long len, byte pattern);

    /**
     * JNI transition calibration: median round-trip of jniNoop over samples.
     * Value is MEASURED on this JVM/hardware; never hardcoded.
     */
    public static double calibrateJniOverheadNs(int samples) {
        if (!nativeLoaded) return -1;
        for (int i = 0; i < Math.min(1000, samples); i++) jniNoop(); // warmup
        long[] t = new long[samples];
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            jniNoop();
            long t1 = System.nanoTime();
            t[i] = t1 - t0;
        }
        java.util.Arrays.sort(t);
        long median = t[samples / 2];
        M1_JNI_CALIBRATION_NS.set(median);
        M1_JNI_CALIBRATION_SAMPLES.set(samples);
        return median;
    }

    // ------------------------------------------------------------------
    // CoreMod hook. Called at entry of SPacketChunkData.<init>(Chunk, int)
    // AFTER super(). Descriptor uses Object so the injected INVOKESTATIC is
    // identical in notch/SRG/MCP environments.
    // Returns true  -> packet fully populated; constructor must return.
    // Returns false -> constructor body continues (pure Java path).
    // ------------------------------------------------------------------
    public static boolean populatePacket(Object packet, Object chunkObj, int changedSectionFilter) {
        if (!(packet instanceof SPacketChunkData) || !(chunkObj instanceof Chunk)) {
            return false; // identity guard: never touch unknown classes
        }
        String mode = runtimeMode;
        Chunk chunkIn = (Chunk) chunkObj;
        try { com.rustcraft.bridge.M4PacketCompare.onPacketConstructing(packet, chunkIn); } catch (Throwable ignore) { }

        // M4.3 native-state authoritative source (isolated from the M1 staging
        // path): when enabled, eligible packets take their payload from the
        // synchronized NativeChunk registry; ANY miss falls back to the
        // untouched Java constructor. Java keeps the shell + TE tags.
        if (com.rustcraft.bridge.M4NativeStatePayload.enabled()) {
            byte[] payload = com.rustcraft.bridge.M4NativeStatePayload.tryEncode(chunkIn, changedSectionFilter);
            if (payload != null) {
                try {
                    boolean full = changedSectionFilter == 65535;
                    return finishPacketPopulation(packet, chunkIn, changedSectionFilter, full,
                            chunkIn.func_76587_i(), payload);
                } catch (Throwable t) {
                    M1_NATIVE_FALLBACKS.incrementAndGet();
                    M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
                    return false; // Java fallback — never a half-populated packet
                }
            }
            M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
            return false;
        }

        if (!nativeLoaded || "OFF".equalsIgnoreCase(mode)) {
            M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
            return false;
        }

        if ("SHADOW".equalsIgnoreCase(mode)) {
            M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
            try {
                runShadowOnPacket(chunkIn, changedSectionFilter);
            } catch (Throwable t) {
                M1_FALLBACKS.incrementAndGet(); // shadow must never break Java path
            }
            return false; // Java path always builds + transmits
        }

        if ("ON_EXPERIMENTAL".equalsIgnoreCase(mode)) {
            M1_NATIVE_ELIGIBLE.incrementAndGet();
            if (!isEligible(chunkIn, changedSectionFilter)) {
                M1_NATIVE_INELIGIBLE.incrementAndGet();
                M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
                return false;
            }
            try {
                boolean ok = executeNativePopulation(packet, chunkIn, changedSectionFilter);
                if (ok) {
                    M1_NATIVE_PACKETS_TRANSMITTED.incrementAndGet();
                    return true;
                }
            } catch (Throwable t) {
                M1_NATIVE_PANICS.incrementAndGet();
            }
            M1_NATIVE_FALLBACKS.incrementAndGet();
            M1_JAVA_PACKETS_TRANSMITTED.incrementAndGet();
            return false; // exact-packet Java fallback; constructor body runs
        }

        return false;
    }

    /** M1.4 §11: per-packet eligibility. Any failure -> Java fallback. */
    private static boolean isEligible(Chunk chunkIn, int filter) {
        if (fieldBits == null || packetBuffer == null) {
            M1_NATIVE_FALLBACK_SCHEMA.incrementAndGet();
            return false; // reflection surface unavailable (layout unknown)
        }
        ExtendedBlockStorage[] sections = chunkIn.func_76587_i();
        if (sections == null || sections.length != 16) {
            M1_NATIVE_FALLBACK_EXTRACTOR.incrementAndGet();
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (sections[i] == Chunk.field_186036_a) continue;
            // Vanilla 1.12.2 skips null sections silently (effMask drops the bit);
            // verified empirically via ProbeNullSection: ctor does NOT throw on null.
            if (sections[i] == null) continue;
            if ((filter & (1 << i)) == 0) continue;
            ExtendedBlockStorage ebs = sections[i];
            BlockStateContainer bsc = ebs.func_186049_g();
            if (bsc == null || bsc.getClass() != BlockStateContainer.class) {
                // Non-vanilla container (e.g. FoamFix deduplicated): classify per §6.
                M1_NATIVE_FALLBACK_EXTRACTOR.incrementAndGet();
                M1_TRANSFORMER_CONFLICTS.incrementAndGet();
                return false;
            }
            if (ebs.func_76661_k() == null) {
                M1_NATIVE_FALLBACK_EXTRACTOR.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    /** FoamFix detection retained for metrics + shadow analysis. */
    public static boolean isFoamFixActive(ExtendedBlockStorage[] sections) {
        for (ExtendedBlockStorage ebs : sections) {
            if (ebs != Chunk.field_186036_a && ebs.func_186049_g() != null) {
                String cls = ebs.func_186049_g().getClass().getName();
                if (cls.contains("foamfix") || cls.contains("Deduplicated")) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // SHADOW: compute native independently, byte-compare vs Java reference,
    // release. Java payload ALWAYS transmitted. (M1.3 proven core.)
    // ------------------------------------------------------------------
    private static void runShadowOnPacket(Chunk chunkIn, int filter) {
        boolean fullChunk = (filter == 65535);
        boolean skyLight = true;
        try {
            skyLight = chunkIn.func_177412_p() != null
                    && chunkIn.func_177412_p().field_73011_w != null
                    && chunkIn.func_177412_p().field_73011_w.func_191066_m();
        } catch (Throwable ignored) {}
        ExtendedBlockStorage[] sections = chunkIn.func_76587_i();
        byte[] biomes = fullChunk ? chunkIn.func_76605_m() : null;

        ByteBuf javaPayload = null;
        ByteBuf directBuf = null;
        try {
            javaPayload = encodeJavaReference(sections, filter, fullChunk, skyLight, biomes);

            ByteBuffer staging = THREAD_LOCAL_STAGING.get();
            staging.clear();
            long tS0 = System.nanoTime();
            int stagedLen = populateStagingBuffer(staging, sections, filter, fullChunk, skyLight, biomes);
            long tS1 = System.nanoTime();
            M1_STAGE_NS.addAndGet(tS1 - tS0);
            M1_BYTES_STAGED.addAndGet(stagedLen);

            long stagingAddr = getDirectBufferAddress(staging);

            long tA0 = System.nanoTime();
            directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(
                    Math.max(65536, stagedLen + 1024));
            long tA1 = System.nanoTime();
            M1_NETTY_BUFFER_ACQUIRE_NS.addAndGet(tA1 - tA0);

            long outAddr = getNettyBufferAddress(directBuf);

            long tJ0 = System.nanoTime();
            int written = encodeSections(stagingAddr, stagedLen, outAddr, directBuf.capacity());
            long tJ1 = System.nanoTime();
            M1_JNI_RUST_NS.addAndGet(tJ1 - tJ0);

            if (written < 0) {
                M1_NATIVE_ERRORS.incrementAndGet();
                M1_FALLBACKS.incrementAndGet();
                return;
            }

            long tH0 = System.nanoTime();
            directBuf.writerIndex(written);
            long tH1 = System.nanoTime();
            M1_HANDOFF_NS.addAndGet(tH1 - tH0);
            M1_BYTES_OUTPUT.addAndGet(written);

            long tC0 = System.nanoTime();
            boolean match = comparePayloads(chunkIn, filter, javaPayload, directBuf);
            long tC1 = System.nanoTime();
            M1_COMPARE_NS.addAndGet(tC1 - tC0);
            M1_TOTAL_NATIVE_NS.addAndGet((tS1 - tS0) + (tA1 - tA0) + (tJ1 - tJ0) + (tH1 - tH0));

            if (match) {
                M1_SHADOW_PACKETS.incrementAndGet();
                M1_SHADOW_CONSECUTIVE_MATCHES.incrementAndGet();
            } else {
                M1_SHADOW_MISMATCHES.incrementAndGet();
                M1_SHADOW_CONSECUTIVE_MATCHES.set(0);
            }
        } finally {
            if (directBuf != null) directBuf.release();
            if (javaPayload != null) javaPayload.release();
        }
    }

    // ------------------------------------------------------------------
    // ON_EXPERIMENTAL: build packet natively; heap byte[] copy; direct
    // buffer released before return. TE getUpdateTag stays 100% Java.
    // ------------------------------------------------------------------
    private static boolean executeNativePopulation(
            Object packet, Chunk chunkIn, int filter) throws Exception {

        boolean fullChunk = (filter == 65535);
        boolean skyLight = true;
        try {
            skyLight = chunkIn.func_177412_p() != null
                    && chunkIn.func_177412_p().field_73011_w != null
                    && chunkIn.func_177412_p().field_73011_w.func_191066_m();
        } catch (Throwable ignored) {}
        ExtendedBlockStorage[] sections = chunkIn.func_76587_i();
        byte[] biomes = fullChunk ? chunkIn.func_76605_m() : null;

        // -----------------------------------------------------------------
        // M1G §2: cheap pre-staging size gate (small-packet selection cost).
        // Computes a provably-safe UPPER bound on the final wire size from
        // O(1)-per-section Java metadata only: palette bits (1 field read),
        // storage word count (array length, no copy), light-array presence.
        // If upperBound < M1_NATIVE_MIN_WIRE_BYTES the packet is GUARANTEED
        // below the eligibility threshold, so staging and the native size
        // prediction are skipped and the constructor body runs the Java path.
        // Routing decision only — byte semantics identical by construction;
        // any surprise (TileEntities present, unknown layout, nulls, missing
        // reflection surface) returns Long.MAX_VALUE and falls through to the
        // UNCHANGED staging + predictOutputLen path.
        // -----------------------------------------------------------------
        long preGateT0 = System.nanoTime();
        long wireUpper = wireUpperBound(chunkIn, sections, filter, fullChunk, skyLight, biomes);
        M1_PREGATE_NS.addAndGet(System.nanoTime() - preGateT0);
        if (wireUpper < M1_NATIVE_MIN_WIRE_BYTES) {
            M1_NATIVE_PREGATE_SKIPPED.incrementAndGet();
            return false; // caller falls back to vanilla Java path
        }

        ByteBuffer staging = THREAD_LOCAL_STAGING.get();
        staging.clear();

        long tS0 = System.nanoTime();
        int stagedLen;
        try {
            stagedLen = populateStagingBuffer(staging, sections, filter, fullChunk, skyLight, biomes);
        } catch (Throwable t) {
            M1_NATIVE_FALLBACK_EXTRACTOR.incrementAndGet();
            throw t;
        }
        long tS1 = System.nanoTime();
        M1_STAGE_NS.addAndGet(tS1 - tS0);
        M1_BYTES_STAGED.addAndGet(stagedLen);

        long stagingAddr = getDirectBufferAddress(staging);
        String handoff = HANDOFF_MODE; // "0" baseline double-copy, "A" critical-array

        if ("A".equals(handoff)) {
            // -----------------------------------------------------------------
            // M1.4-R2 HANDOFF-A: exact-size heap byte[] + GetPrimitiveArrayCritical.
            // Path: staging -> JNI(critical) -> Rust writes packet byte[] directly.
            // Removes pooled direct buffer AND the intermediate heap copy.
            // -----------------------------------------------------------------
            long tP0 = System.nanoTime();
            int predicted = predictOutputLen(stagingAddr, stagedLen);
            long tP1 = System.nanoTime();
            M1_PAYLOAD_PREDICT_NS.addAndGet(tP1 - tP0);
            if (predicted < 0) {
                M1_NATIVE_FALLBACK_ENCODE.incrementAndGet();
                M1_NATIVE_ERRORS.incrementAndGet();
                return false;
            }
            if (predicted < M1_NATIVE_MIN_WIRE_BYTES) {
                // §11 eligibility: tiny payloads lose to Java ctor (measured,
                // canonical reuse + boundary deep-n). Cheap deterministic gate.
                M1_NATIVE_SKIPPED_SMALL.incrementAndGet();
                return false; // caller falls back to vanilla Java path
            }
            long tA0 = System.nanoTime();
            byte[] payload = new byte[predicted]; // exact size (§5): no truncation
            long tA1 = System.nanoTime();
            M1_ALLOC_NS.addAndGet(tA1 - tA0);

            long tJ0 = System.nanoTime();
            int written = encodeSectionsIntoArray(stagingAddr, stagedLen, payload);
            long tJ1 = System.nanoTime();
            M1_JNI_RUST_NS.addAndGet(tJ1 - tJ0);

            if (written < 0) {
                M1_NATIVE_FALLBACK_ENCODE.incrementAndGet();
                M1_NATIVE_ERRORS.incrementAndGet();
                return false;
            }
            if (written != predicted) { // predictor must be exact — else fallback
                M1_NATIVE_FALLBACK_ENCODE.incrementAndGet();
                M1_NATIVE_ERRORS.incrementAndGet();
                return false;
            }
            M1_BYTES_OUTPUT.addAndGet(written);
            M1_NATIVE_BYTES_TRANSMITTED.addAndGet(written);
            M1_TOTAL_NATIVE_NS.addAndGet((tS1 - tS0) + (tA1 - tA0) + (tJ1 - tJ0));
            return finishPacketPopulation(packet, chunkIn, filter, fullChunk, sections, payload);
        }

        long tA0 = System.nanoTime();
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(
                Math.max(65536, stagedLen + 1024));
        long tA1 = System.nanoTime();
        M1_NETTY_BUFFER_ACQUIRE_NS.addAndGet(tA1 - tA0);

        try {
            long outAddr = getNettyBufferAddress(directBuf);

            long tJ0 = System.nanoTime();
            int written = encodeSections(stagingAddr, stagedLen, outAddr, directBuf.capacity());
            long tJ1 = System.nanoTime();
            M1_JNI_RUST_NS.addAndGet(tJ1 - tJ0);

            if (written < 0) {
                M1_NATIVE_FALLBACK_ENCODE.incrementAndGet();
                M1_NATIVE_ERRORS.incrementAndGet();
                return false;
            }

            long tH0 = System.nanoTime();
            byte[] payload = new byte[written];
            directBuf.getBytes(0, payload);
            long tH1 = System.nanoTime();
            M1_HANDOFF_NS.addAndGet(tH1 - tH0);
            M1_BYTES_OUTPUT.addAndGet(written);
            M1_NATIVE_BYTES_TRANSMITTED.addAndGet(written);
            M1_TOTAL_NATIVE_NS.addAndGet((tS1 - tS0) + (tA1 - tA0) + (tJ1 - tJ0) + (tH1 - tH0));

            return finishPacketPopulation(packet, chunkIn, filter, fullChunk, sections, payload);
        } finally {
            directBuf.release(); // refCnt 1 -> 0 before any external visibility
        }
    }

    /** Shared packet-shell population (identical semantics for every handoff):
     *  coordinate/mask/fullChunk fields, payload field, TileEntity update tags. */
    private static boolean finishPacketPopulation(
            Object packet, Chunk chunkIn, int filter, boolean fullChunk,
            ExtendedBlockStorage[] sections, byte[] payload) throws Exception {
        packetChunkX.setInt(packet, chunkIn.field_76635_g);
        packetChunkZ.setInt(packet, chunkIn.field_76647_h);
        packetFull.setBoolean(packet, fullChunk);
        packetBuffer.set(packet, payload);
        packetMask.setInt(packet, computeEffectiveMask(sections, filter, fullChunk));

        long tT0 = System.nanoTime();
        List<NBTTagCompound> teList = new ArrayList<>();
        for (Map.Entry<BlockPos, TileEntity> e : chunkIn.func_177434_r().entrySet()) {
            BlockPos pos = e.getKey();
            int secIdx = pos.func_177956_o() >> 4;
            if (fullChunk || (filter & (1 << secIdx)) != 0) {
                teList.add(e.getValue().func_189517_E_());
            }
        }
        long tT1 = System.nanoTime();
        M1_TE_TAG_NS.addAndGet(tT1 - tT0);
        packetTEs.set(packet, teList);
        return true;
    }

    // ------------------------------------------------------------------
    // Byte comparison (M1.3 proven; M1.4-R §9 full diagnostics)
    // ------------------------------------------------------------------
    private static boolean comparePayloads(Chunk chunkIn, int requestedMask,
                                           ByteBuf expected, ByteBuf actual) {
        boolean fullChunk = (requestedMask == 65535);
        int expLen = expected.readableBytes();
        int actLen = actual.readableBytes();
        if (expLen != actLen) {
            logMismatchDiagnostic(chunkIn, requestedMask, expected, actual, -1);
            return false;
        }
        int rE = expected.readerIndex();
        int rA = actual.readerIndex();
        for (int i = 0; i < expLen; i++) {
            if (expected.getByte(rE + i) != actual.getByte(rA + i)) {
                logMismatchDiagnostic(chunkIn, requestedMask, expected, actual, i);
                return false;
            }
        }
        return true;
    }

    /** M1.4-R §9: full mismatch diagnostics — chunk coords, dimension, masks,
     *  sentinel/empty section map, palette mode, lengths, first mismatch byte,
     *  hashes, replay hint. */
    private static void logMismatchDiagnostic(Chunk chunkIn, int requestedMask,
                                              ByteBuf expected, ByteBuf actual, int offset) {
        boolean fullChunk = (requestedMask == 65535);
        System.err.println("==================================================");
        System.err.println("  [SHADOW MISMATCH DETECTED] " + new java.util.Date());
        System.err.println(String.format("  Chunk: (%d, %d)",
                chunkIn.field_76635_g, chunkIn.field_76647_h));
        try {
            String dim = (chunkIn.func_177412_p() != null
                    && chunkIn.func_177412_p().field_73011_w != null)
                    ? String.valueOf(chunkIn.func_177412_p().field_73011_w.func_186058_p())
                    : "unknown(null world/provider)";
            System.err.println("  Dimension: " + dim);
        } catch (Throwable t) {
            System.err.println("  Dimension: unavailable (" + t + ")");
        }
        System.err.println(String.format("  Requested mask: 0x%04X | fullChunk: %b",
                requestedMask, fullChunk));

        int javaMask = 0, nativeMask = 0;
        StringBuilder sentinelMap = new StringBuilder();
        try {
            ExtendedBlockStorage[] sections = chunkIn.func_76587_i();
            for (int i = 0; i < 16; i++) {
                ExtendedBlockStorage ebs = sections[i];
                boolean sentinel = (ebs == Chunk.field_186036_a);
                boolean empty = sentinel || ebs.func_76663_a();
                if (ebs != Chunk.field_186036_a && (!fullChunk || !ebs.func_76663_a())
                        && (requestedMask & (1 << i)) != 0) javaMask |= (1 << i);
                sentinelMap.append(sentinel ? "S" : (empty ? "e" : "x"));
            }
            // native effective mask = what staging actually carries
            nativeMask = computeEffectiveMask(sections, requestedMask, fullChunk);
        } catch (Throwable t) {
            sentinelMap.append("(unavailable)");
        }
        System.err.println(String.format("  Effective Java mask: 0x%04X | Effective native mask: 0x%04X",
                javaMask, nativeMask));
        System.err.println("  Section map [0..15] S=sentinel e=empty x=nonempty: " + sentinelMap);
        try {
            int firstBits = -1;
            for (ExtendedBlockStorage ebs : chunkIn.func_76587_i()) {
                if (ebs != Chunk.field_186036_a) {
                    firstBits = fieldBits.getInt(ebs.func_186049_g());
                    break;
                }
            }
            System.err.println("  Palette mode (first non-sentinel bitsPerBlock): "
                    + (firstBits < 0 ? "n/a (all sentinel)" : (firstBits < 9 ? "linear/hash palette" : "global")));
        } catch (Throwable ignored) {}
        System.err.println(String.format("  Expected Length: %d | Actual Length: %d",
                expected.readableBytes(), actual.readableBytes()));
        if (offset >= 0) {
            System.err.println(String.format(
                    "  First Mismatch at offset %d: expected=0x%02X, actual=0x%02X",
                    offset, expected.getByte(expected.readerIndex() + offset),
                    actual.getByte(actual.readerIndex() + offset)));
        }
        System.err.println(String.format("  Expected CRC32: %08X | Actual CRC32: %08X",
                crc32Of(expected), crc32Of(actual)));
        System.err.println("  Replay: re-run harness with same chunk coords/mask/seed; see M14ValidationHarness");
        System.err.println("==================================================");
    }

    private static long crc32Of(ByteBuf buf) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        int r = buf.readerIndex(), len = buf.readableBytes();
        for (int i = 0; i < len; i++) crc.update(buf.getByte(r + i));
        return crc.getValue();
    }

    // ------------------------------------------------------------------
    // INPUT-A staging buffer population (M1.3 proven)
    // ------------------------------------------------------------------
    /**
     * Vanilla SPacketChunkData.extractChunkData exact section-write condition:
     *   ebs != Chunk.field_186036_a && (!fullChunk || !ebs.isEmpty()) && (filter & 1<<i) != 0
     * The WIRE mask (availableSections) is the mask of sections ACTUALLY written,
     * not the raw filter. Schema header must carry the effective mask.
     */
    private static boolean vanillaWritesSection(ExtendedBlockStorage ebs, int i,
                                                int filter, boolean fullChunk) {
        return ebs != Chunk.field_186036_a
                && (!fullChunk || !ebs.func_76663_a())
                && (filter & (1 << i)) != 0;
    }

    public static int computeEffectiveMask(ExtendedBlockStorage[] sections,
                                           int filter, boolean fullChunk) {
        int m = 0;
        for (int i = 0; i < 16; i++) {
            if (vanillaWritesSection(sections[i], i, filter, fullChunk)) m |= (1 << i);
        }
        return m;
    }

    public static int populateStagingBuffer(
            ByteBuffer staging,
            ExtendedBlockStorage[] sections,
            int primaryBitMask,
            boolean fullChunk,
            boolean skyLightPresent,
            byte[] biomes
    ) {
        // Effective wire mask = sections actually written (vanilla semantics).
        // Rust writes THIS mask to the wire; section_count must equal popcount.
        int effectiveMask = computeEffectiveMask(sections, primaryBitMask, fullChunk);
        int sectionCount = Integer.bitCount(effectiveMask);

        staging.putShort(SCHEMA_VERSION);
        staging.putShort((short) effectiveMask);
        byte flags = 0;
        if (fullChunk) flags |= 0x01;
        if (skyLightPresent) flags |= 0x02;
        staging.put(flags);
        staging.put((byte) sectionCount);

        for (int i = 0; i < 16; i++) {
            ExtendedBlockStorage ebs = sections[i];
            if (vanillaWritesSection(ebs, i, primaryBitMask, fullChunk)) {
                BlockStateContainer bsc = ebs.func_186049_g();
                staging.put((byte) i);

                String clsName = bsc.getClass().getName();
                if (clsName.contains("foamfix") || clsName.contains("Deduplicated")) {
                    M1_FOAMFIX_ADAPTER_PACKETS.incrementAndGet();
                }
                extractVanillaSection(staging, bsc); // standard layout or throw -> fallback

                staging.put(ebs.func_76661_k().func_177481_a());
                if (skyLightPresent && ebs.func_76671_l() != null) {
                    staging.put(ebs.func_76671_l().func_177481_a());
                }
            }
        }

        if (fullChunk && biomes != null) {
            staging.put(biomes);
        }
        return staging.position();
    }

    /**
     * M1G §2: safe upper bound on SPacketChunkData wire size, metadata-only.
     * Per written section the bound covers: blockCount short (2), bits byte
     * (1), palette (varint count + entries as <=5B varints; entries <= 2^bits
     * for indirect palettes, empty for global), long-array length varint (<=5),
     * storage words (8 * exact word count), light arrays (2048 when present),
     * plus per-section slack. Header/biome allowance added globally. This can
     * only OVER-estimate, so a packet skipped here would also be skipped by
     * the exact predictOutputLen gate; any bound error costs routing
     * efficiency only, never correctness.
     */
    private static long wireUpperBound(Chunk chunkIn, ExtendedBlockStorage[] sections,
                                       int filter, boolean fullChunk, boolean skyLight,
                                       byte[] biomes) {
        try {
            if (fieldBits == null || fieldStorage == null) return Long.MAX_VALUE;
            // TileEntity getUpdateTag payloads are unbounded and not cheaply
            // predictable: never pre-reject chunks carrying TileEntities.
            if (!chunkIn.func_177434_r().isEmpty()) return Long.MAX_VALUE;
            long ub = 32; // chunkX/Z ints, mask varint, header allowance
            for (int i = 0; i < 16; i++) {
                if (!vanillaWritesSection(sections[i], i, filter, fullChunk)) continue;
                ExtendedBlockStorage ebs = sections[i];
                if (ebs == null) continue; // vanilla skips null sections here too
                BlockStateContainer bsc = ebs.func_186049_g();
                if (bsc == null) return Long.MAX_VALUE;
                int bits = fieldBits.getInt(bsc);
                BitArray storage = (BitArray) fieldStorage.get(bsc);
                if (storage == null) return Long.MAX_VALUE;
                long words = storage.func_188143_a().length;
                long paletteU = (bits < 9)
                        ? (5 + (1L << Math.min(bits, 8)) * 5L + 5)
                        : 1; // global palette serializes an empty list
                ub += 2 + 1 + paletteU + 5 + 8L * words + 64;
                if (ebs.func_76661_k() != null) ub += 2048;
                if (skyLight && ebs.func_76671_l() != null) ub += 2048;
            }
            if (fullChunk && biomes != null) ub += 256;
            return ub;
        } catch (Throwable t) {
            return Long.MAX_VALUE; // any surprise: unchanged staging+predict path
        }
    }

    private static void extractVanillaSection(ByteBuffer staging, BlockStateContainer bsc) {
        try {
            int bits = fieldBits.getInt(bsc);
            staging.put((byte) bits);

            IBlockStatePalette palette = (IBlockStatePalette) fieldPalette.get(bsc);
            if (bits < 9) {
                if (palette instanceof BlockStatePaletteLinear
                        && fieldLinearArraySize != null && fieldLinearStates != null) {
                    int count = fieldLinearArraySize.getInt(palette);
                    IBlockState[] states = (IBlockState[]) fieldLinearStates.get(palette);
                    staging.putShort((short) count);
                    for (int p = 0; p < count; p++) {
                        staging.putInt(Block.field_176229_d.func_148747_b(states[p]));
                    }
                } else if (palette instanceof BlockStatePaletteHashMap && fieldHashMapMap != null) {
                    IntIdentityHashBiMap<IBlockState> map =
                            (IntIdentityHashBiMap<IBlockState>) fieldHashMapMap.get(palette);
                    int count = map.func_186810_b();
                    staging.putShort((short) count);
                    for (int p = 0; p < count; p++) {
                        staging.putInt(Block.field_176229_d.func_148747_b(map.func_186813_a(p)));
                    }
                } else {
                    throw new RuntimeException("Unknown palette layout: "
                            + (palette == null ? "null" : palette.getClass().getName()));
                }
            } else {
                staging.putShort((short) 0); // global palette: no entries
            }

            BitArray storage = (BitArray) fieldStorage.get(bsc);
            long[] words = storage.func_188143_a();
            staging.putShort((short) words.length);
            for (long w : words) staging.putLong(w);
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException("Section extraction failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Java reference encoder. Canonical apples-to-apples boundary: entry at
    // call, exit at return. Includes output buffer acquisition + PacketBuffer
    // wrapper + BlockStateContainer.write + lighting + biome copy — the same
    // work items as the native path (staging, acquire, JNI+Rust, handoff).
    // Excludes TE getUpdateTag and packet shell (identically excluded on both).
    // ------------------------------------------------------------------
    public static ByteBuf encodeJavaReference(
            ExtendedBlockStorage[] sections,
            int primaryBitMask,
            boolean fullChunk,
            boolean skyLightPresent,
            byte[] biomes
    ) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(65536);
        PacketBuffer pb = new PacketBuffer(buf);

        // TRUE VANILLA REFERENCE — inlined independently from native helpers.
        // Mirrors SPacketChunkData.<init> -> Chunk.extractChunkData semantics
        // (verified from SRG jar bytecode). Deliberately does NOT call
        // vanillaWritesSection(): the reference must be an independent
        // implementation so a semantic bug in the native helper cannot be
        // masked by both sides sharing it (M1.4-R §6).
        for (int i = 0; i < 16; i++) {
            ExtendedBlockStorage ebs = sections[i];
            if (ebs != Chunk.field_186036_a
                    && (!fullChunk || !ebs.func_76663_a())
                    && (primaryBitMask & (1 << i)) != 0) {
                ebs.func_186049_g().func_186009_b(pb);
                pb.writeBytes(ebs.func_76661_k().func_177481_a());
                if (skyLightPresent && ebs.func_76671_l() != null) {
                    pb.writeBytes(ebs.func_76671_l().func_177481_a());
                }
            }
        }
        if (fullChunk && biomes != null) {
            pb.writeBytes(biomes);
        }
        return buf;
    }

    public static long getDirectBufferAddress(ByteBuffer directBuffer) {
        try {
            Method m = directBuffer.getClass().getMethod("address");
            m.setAccessible(true);
            return (Long) m.invoke(directBuffer);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Probe/test-only accessor to the thread-local staging buffer. */
    public static ByteBuffer getStagingForProbe() {
        return THREAD_LOCAL_STAGING.get();
    }

    /** Probe/test-only: bridge's cached BlockStateContainer reflection fields
     *  [bits, palette, storage] — same instances the extractor uses. */
    public static java.lang.reflect.Field[] getCachedFieldsForProbe() {
        return new java.lang.reflect.Field[]{fieldBits, fieldPalette, fieldStorage};
    }

    /**
     * Exact data address of a Netty ByteBuf for JNI writes.
     *
     * CRITICAL (bug found 2026-09-18, M1.4-R): computing this via
     * {@code buf.internalNioBuffer(0, cap).address()} is WRONG for pooled
     * buffers — the internal NIO view carries the ARENA SLAB BASE address,
     * not the buffer's own base+offset. When another pooled buffer occupies
     * slab base, native code writes the wrong buffer (observed: shadow-mode
     * payload read back as zeros while javaRef was silently overwritten with
     * identical bytes). {@code hasMemoryAddress()}/{@code memoryAddress()} is
     * the public Netty API for the exact per-buffer address.
     */
    public static long getNettyBufferAddress(io.netty.buffer.ByteBuf buf) {
        if (buf == null) return 0;
        try {
            if (buf.hasMemoryAddress()) return buf.memoryAddress();
        } catch (Throwable ignored) {}
        // non-unsafe allocators: NIO view address is correct for these
        return getDirectBufferAddress(buf.internalNioBuffer(0, buf.capacity()));
    }

    /** M1.4 §29 dump helper. */
    public static String dumpMetrics() {
        return "m1_native_packets_transmitted=" + M1_NATIVE_PACKETS_TRANSMITTED.get()
                + "\nm1_java_packets_transmitted=" + M1_JAVA_PACKETS_TRANSMITTED.get()
                + "\nm1_native_eligible=" + M1_NATIVE_ELIGIBLE.get()
                + "\nm1_native_ineligible=" + M1_NATIVE_INELIGIBLE.get()
                + "\nm1_native_fallbacks=" + M1_NATIVE_FALLBACKS.get()
                + "\nm1_native_fallback_schema=" + M1_NATIVE_FALLBACK_SCHEMA.get()
                + "\nm1_native_fallback_extractor=" + M1_NATIVE_FALLBACK_EXTRACTOR.get()
                + "\nm1_native_fallback_buffer=" + M1_NATIVE_FALLBACK_BUFFER.get()
                + "\nm1_native_fallback_encode=" + M1_NATIVE_FALLBACK_ENCODE.get()
                + "\nm1_native_bytes_transmitted=" + M1_NATIVE_BYTES_TRANSMITTED.get()
                + "\nm1_native_skipped_small=" + M1_NATIVE_SKIPPED_SMALL.get()
                + "\nm1_native_pregate_skipped=" + M1_NATIVE_PREGATE_SKIPPED.get()
                + "\nm1_pregate_ns=" + M1_PREGATE_NS.get()
                + "\nm1_shadow_packets=" + M1_SHADOW_PACKETS.get()
                + "\nm1_shadow_matches=" + M1_SHADOW_CONSECUTIVE_MATCHES.get()
                + "\nm1_shadow_mismatches=" + M1_SHADOW_MISMATCHES.get()
                + "\nm1_transformer_conflicts=" + M1_TRANSFORMER_CONFLICTS.get()
                + "\nm1_foamfix_adapter_packets=" + M1_FOAMFIX_ADAPTER_PACKETS.get()
                + "\nm1_foamfix_native_packets=" + M1_FOAMFIX_NATIVE_PACKETS.get()
                + "\nm1_phosphor_native_packets=" + M1_PHOSPHOR_NATIVE_PACKETS.get()
                + "\nm1_netty_buffer_acquire_ns=" + M1_NETTY_BUFFER_ACQUIRE_NS.get()
                + "\nm1_stage_ns=" + M1_STAGE_NS.get()
                + "\nm1_jni_rust_ns=" + M1_JNI_RUST_NS.get()
                + "\nm1_jni_calibration_ns=" + M1_JNI_CALIBRATION_NS.get()
                + "\nm1_jni_calibration_samples=" + M1_JNI_CALIBRATION_SAMPLES.get()
                + "\nm1_handoff_ns=" + M1_HANDOFF_NS.get()
                + "\nm1_total_native_ns=" + M1_TOTAL_NATIVE_NS.get()
                + "\nm1_te_tag_ns=" + M1_TE_TAG_NS.get()
                + "\nm1_bytes_staged=" + M1_BYTES_STAGED.get()
                + "\nm1_bytes_output=" + M1_BYTES_OUTPUT.get();
    }
}
