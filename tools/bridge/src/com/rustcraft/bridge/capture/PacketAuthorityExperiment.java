package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.PacketEncodeResultV2;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SMALL, EXPLICIT, FAIL-CLOSED Bounded Rust-Authority Experiment.
 *
 * <p>Under strict project governance, Rust is allowed to author SPacketChunkData
 * payload bytes that reach real clients ONLY when ALL of the following gates pass:</p>
 * <ol>
 *   <li>Operator-controlled flag: {@code -Drustcraft.packetAuthorityExperiment=true}</li>
 *   <li>Verified closure input receipt: {@code target/authority-review/closure-input-receipt.json}</li>
 *   <li>V2 real-session admission: active, untainted, non-disqualified session</li>
 *   <li>Exact admitted scope: Overworld (dim 0), full chunk (filter 0xFFFF),
 *       empty TileEntity map, used section logical states &le; 65535, RCSNAP02</li>
 *   <li>Bounded cap: {@code rustSelected < packetAuthorityCap} (default 64)</li>
 * </ol>
 *
 * <p>Without ALL of those, the event routes FAIL-CLOSED to pure Java serialization.
 * One packet, one representation reaches Netty. No double-send, no partial state.
 * Java fallback remains 100% active and untouched.</p>
 */
public final class PacketAuthorityExperiment {

    // --- State Constants ---
    public static final String STATE_LIVE_SHADOW_CLOSED = "LIVE_SHADOW_CLOSED";
    public static final String STATE_AUTHORITY_REVIEWED = "AUTHORITY_REVIEWED";
    public static final String STATE_BOUNDED_AUTHORITY_EXPERIMENT = "BOUNDED_AUTHORITY_EXPERIMENT";
    public static final String STATE_PRODUCTION_AUTHORITY = "PRODUCTION_AUTHORITY";

    /** Production authority is strictly FALSE. Furthest permissible state is BOUNDED_AUTHORITY_EXPERIMENT. */
    public static final boolean PRODUCTION_AUTHORITY = false;

    // --- System Properties ---
    public static final String PROPERTY_EXPERIMENT = "rustcraft.packetAuthorityExperiment";
    public static final String PROPERTY_CAP = "rustcraft.packetAuthorityCap";
    public static final String PROPERTY_RECEIPT = "rustcraft.packetAuthorityReceipt";
    public static final String PROPERTY_RECEIPT_ALT = "rustcraft.authorityInputReceipt";
    public static final String PROPERTY_RECEIPT_OUT = "rustcraft.packetAuthorityReceiptOut";
    public static final String PROPERTY_RECEIPT_OUT_ALT = "rustcraft.authorityReceiptOut";
    public static final String PROPERTY_DIRECT_NETTY = "rustcraft.directNettyExperiment";
    public static final String PROPERTY_DIRECT_NETTY_SHADOW = "rustcraft.directNettyShadow";

    private static volatile boolean experimentEnabled = Boolean.getBoolean(PROPERTY_EXPERIMENT);
    private static volatile long authorityCap = Long.getLong(PROPERTY_CAP, 64).longValue();
    private static volatile boolean directNettyEnabled = Boolean.getBoolean(PROPERTY_DIRECT_NETTY);
    private static volatile boolean directNettyShadow = Boolean.getBoolean(PROPERTY_DIRECT_NETTY_SHADOW);

    // --- Accounting Counters ---
    public static final AtomicLong AUTHORITY_ELIGIBLE = new AtomicLong();
    public static final AtomicLong RUST_SELECTED = new AtomicLong();
    public static final AtomicLong JAVA_SELECTED = new AtomicLong();
    public static final AtomicLong JAVA_FALLBACK = new AtomicLong();
    public static final AtomicLong RUST_ENCODE_FAILURE = new AtomicLong();
    public static final AtomicLong EXCLUDED_HIGH_STATE = new AtomicLong();
    public static final AtomicLong EXCLUDED_TE = new AtomicLong();
    public static final AtomicLong EXCLUDED_FILTER = new AtomicLong();
    public static final AtomicLong EXCLUDED_DIMENSION = new AtomicLong();
    public static final AtomicLong CAP_EXHAUSTED = new AtomicLong();
    public static final AtomicLong FALLBACK_SESSION_UNADMITTED = new AtomicLong();
    public static final AtomicLong FALLBACK_RECEIPT_INVALID = new AtomicLong();
    public static final AtomicLong RETAINED_RUST_SELECTED = new AtomicLong();
    public static final AtomicLong RETAINED_SEEDED = new AtomicLong();
    public static final AtomicLong RETAINED_FAST_PATH = new AtomicLong();
    public static final AtomicLong SNAPSHOT_SEED_PATH = new AtomicLong();
    public static final AtomicLong EPHEMERAL_FALLBACK = new AtomicLong();
    public static final AtomicLong DIRECT_BUFFER_PRESSURE_FALLBACK = new AtomicLong();

    // Direct Netty Counters & Bounded Resource Control
    public static final AtomicLong DIRECT_NETTY_COMMITTED = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_FALLBACKS = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_SHADOW_MATCHES = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_SHADOW_MISMATCHES = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_BUFFERS_ALLOCATED = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_BUFFERS_RELEASED = new AtomicLong();
    public static final AtomicLong DIRECT_NETTY_BYTES_TRANSMITTED = new AtomicLong();
    public static final java.util.concurrent.atomic.AtomicInteger OUTSTANDING_DIRECT_BUFFERS = new java.util.concurrent.atomic.AtomicInteger(0);
    public static final int MAX_OUTSTANDING_DIRECT_BUFFERS = 128; // Strict bounded pool bound

    public static final java.util.Map<Object, io.netty.buffer.ByteBuf> DIRECT_PACKET_BUFFERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Object, Long> PACKET_CREATION_TIMES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PACKET_EXPIRATION_NANOS = 30_000_000_000L; // 30 seconds
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /** Test-only probe of the shared empty shell identity. */
    public static byte[] EMPTY_PAYLOAD_SHELL_PROBE() { return EMPTY_PAYLOAD; }

    /**
     * Packets that were admitted as RUST_SINGLE_COPY (complete pre-compression
     * body registered against the packet). Weakly held so admitted packets
     * never leak; a packet in this set whose single-copy body is gone must
     * NEVER fall into vanilla serialization (its payload field is an empty
     * shell) — it fails the write promise instead. This is the enforcement of
     * the "no malformed empty chunk packet" rule.
     */
    private static final java.util.Map<Object, Boolean> SINGLE_COPY_ADMITTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Single-copy telemetry mirrors (kept here so the experiment receipt shows
    // both the b3df84c direct-netty and the single-copy paths side by side).
    public static final AtomicLong SINGLE_COPY_ADMITTED_COUNT = new AtomicLong();
    public static final AtomicLong SINGLE_COPY_FALLBACKS = new AtomicLong();

    private static final AtomicBoolean RECEIPT_VERIFIED = new AtomicBoolean(false);
    private static final AtomicBoolean RECEIPT_CHECK_ATTEMPTED = new AtomicBoolean(false);
    private static volatile String receiptFailureReason = null;

    // Direct buffers for native encoding
    private static final int BUFFER_CAPACITY = 262144;
    private static final ThreadLocal<ByteBuffer> IN_BUF = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(BUFFER_CAPACITY).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> OUT_BUF = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(BUFFER_CAPACITY).order(ByteOrder.nativeOrder()));

    // SPacketChunkData reflection fields
    private static Field packetChunkX;
    private static Field packetChunkZ;
    private static Field packetMask;
    private static Field packetBuffer;
    private static Field packetTEs;
    private static Field packetFull;

    // ------------------------------------------------------------------
    // Lifecycle correction (single-copy task): the Java-side retained-record
    // map (REGISTERED_CHUNKS, keyed (cx,cz) -> {dim, genId}) is GONE. It could
    // become an independent stale authority after an unload/reload cycle: the
    // native registry allocates a fresh monotonic generation id for a
    // re-registered chunk, but the cached record still carried the dead
    // generation, so every retained encode failed -3 (StaleGeneration) until
    // something rewrote the record, and the record itself survived native
    // unloads forever. The canonical NativeChunk registry is queryable cheaply
    // (NativeChunkBridge.findGeneration = one HashMap lookup in native
    // memory), so it is now the ONLY authority for current generation ids:
    // no cache to invalidate, no stale-handle loops, no old bytes. The
    // retained fast path queries findGeneration at admission time; the
    // seed path seeds and re-queries. Neither holds a Java copy of the id.
    // ------------------------------------------------------------------

    // Dynamic Chunk reflection fields (no compile-time net.minecraft references)
    private static volatile boolean chunkReflectionInitialized = false;
    private static Field chunkFieldX;
    private static Field chunkFieldZ;
    private static Field chunkFieldWorld;
    private static Method chunkMethodTileEntities;
    private static Field chunkFieldTileEntities;

    private static synchronized void initChunkReflection(Class<?> chunkClass) {
        if (chunkReflectionInitialized) return;
        try {
            chunkFieldX = findAccessibleField(chunkClass, "field_76635_g", "x");
            chunkFieldZ = findAccessibleField(chunkClass, "field_76647_h", "z");
            chunkFieldWorld = findAccessibleField(chunkClass, "field_76637_e", "world");

            try {
                chunkMethodTileEntities = chunkClass.getMethod("func_177434_r");
            } catch (NoSuchMethodException e) {
                try {
                    chunkMethodTileEntities = chunkClass.getMethod("getTileEntityMap");
                } catch (NoSuchMethodException e2) {
                    chunkFieldTileEntities = findAccessibleField(chunkClass, "field_150816_i", "tileEntityMap");
                }
            }
            chunkReflectionInitialized = true;
        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] initChunkReflection error: " + t);
        }
    }

    private static Field findAccessibleField(Class<?> c, String... names) {
        Class<?> curr = c;
        while (curr != null) {
            for (String name : names) {
                try {
                    Field f = curr.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignore) {}
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    private static int extractWorldDimension(Object chunk) {
        try {
            if (chunkFieldWorld == null) return -999;
            Object world = chunkFieldWorld.get(chunk);
            if (world == null) return -999;
            Field pf = findAccessibleField(world.getClass(), "field_73011_w", "provider");
            if (pf == null) return -999;
            Object provider = pf.get(world);
            if (provider == null) return -999;
            try {
                Method m = provider.getClass().getMethod("getDimension");
                return ((Number) m.invoke(provider)).intValue();
            } catch (Throwable t1) {
                try {
                    Method m1 = provider.getClass().getMethod("func_186058_p");
                    Object dimType = m1.invoke(provider);
                    if (dimType != null) {
                        Method m2 = dimType.getClass().getMethod("func_186068_a");
                        return ((Number) m2.invoke(dimType)).intValue();
                    }
                } catch (Throwable t2) {
                    Field df = findAccessibleField(provider.getClass(), "dimensionId");
                    if (df != null) return df.getInt(provider);
                }
            }
        } catch (Throwable ignore) {}
        return -999;
    }

    private static boolean extractWorldHasSky(Object chunk) {
        try {
            if (chunkFieldWorld == null) return false;
            Object world = chunkFieldWorld.get(chunk);
            if (world == null) return false;
            Field pf = findAccessibleField(world.getClass(), "field_73011_w", "provider");
            if (pf == null) return false;
            Object provider = pf.get(world);
            if (provider == null) return false;
            try {
                Method m = provider.getClass().getMethod("func_191066_m");
                return ((Boolean) m.invoke(provider)).booleanValue();
            } catch (Throwable t1) {
                try {
                    Method m = provider.getClass().getMethod("hasSkyLight");
                    return ((Boolean) m.invoke(provider)).booleanValue();
                } catch (Throwable ignore) {}
            }
            return true;
        } catch (Throwable ignore) {}
        return false;
    }

    private static boolean isChunkTileEntitiesEmpty(Object chunk) {
        try {
            if (chunkMethodTileEntities != null) {
                Map<?, ?> map = (Map<?, ?>) chunkMethodTileEntities.invoke(chunk);
                return map == null || map.isEmpty();
            }
            if (chunkFieldTileEntities != null) {
                Map<?, ?> map = (Map<?, ?>) chunkFieldTileEntities.get(chunk);
                return map == null || map.isEmpty();
            }
        } catch (Throwable ignore) {}
        return false;
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if (experimentEnabled || RUST_SELECTED.get() > 0 || AUTHORITY_ELIGIBLE.get() > 0 ||
                    System.getProperty(PROPERTY_RECEIPT_OUT) != null || System.getProperty(PROPERTY_RECEIPT_OUT_ALT) != null) {
                    writeReceipt();
                }
            }
        }, "rustcraft-authority-experiment-receipt"));
    }

    private PacketAuthorityExperiment() { }

    public static boolean enabled() {
        return experimentEnabled;
    }

    public static long cap() {
        return authorityCap;
    }

    public static void setEnabled(boolean enabled) {
        experimentEnabled = enabled;
    }

    public static void setCap(long cap) {
        authorityCap = cap;
    }

    public static boolean directNettyEnabled() {
        return directNettyEnabled;
    }

    public static void setDirectNettyEnabled(boolean enabled) {
        directNettyEnabled = enabled;
    }

    public static boolean directNettyShadow() {
        return directNettyShadow;
    }

    public static void setDirectNettyShadow(boolean shadow) {
        directNettyShadow = shadow;
    }

    public static void resetForTesting(boolean enabled, long cap) {
        experimentEnabled = enabled;
        authorityCap = cap;
        directNettyEnabled = Boolean.getBoolean(PROPERTY_DIRECT_NETTY);
        directNettyShadow = Boolean.getBoolean(PROPERTY_DIRECT_NETTY_SHADOW);
        AUTHORITY_ELIGIBLE.set(0);
        RUST_SELECTED.set(0);
        JAVA_SELECTED.set(0);
        JAVA_FALLBACK.set(0);
        RUST_ENCODE_FAILURE.set(0);
        EXCLUDED_HIGH_STATE.set(0);
        EXCLUDED_TE.set(0);
        EXCLUDED_FILTER.set(0);
        EXCLUDED_DIMENSION.set(0);
        CAP_EXHAUSTED.set(0);
        FALLBACK_SESSION_UNADMITTED.set(0);
        FALLBACK_RECEIPT_INVALID.set(0);
        RETAINED_RUST_SELECTED.set(0);
        RETAINED_SEEDED.set(0);
        RETAINED_FAST_PATH.set(0);
        SNAPSHOT_SEED_PATH.set(0);
        EPHEMERAL_FALLBACK.set(0);
        DIRECT_BUFFER_PRESSURE_FALLBACK.set(0);
        DIRECT_NETTY_COMMITTED.set(0);
        DIRECT_NETTY_FALLBACKS.set(0);
        DIRECT_NETTY_SHADOW_MATCHES.set(0);
        DIRECT_NETTY_SHADOW_MISMATCHES.set(0);
        DIRECT_NETTY_BUFFERS_ALLOCATED.set(0);
        DIRECT_NETTY_BUFFERS_RELEASED.set(0);
        DIRECT_NETTY_BYTES_TRANSMITTED.set(0);
        OUTSTANDING_DIRECT_BUFFERS.set(0);
        for (io.netty.buffer.ByteBuf b : DIRECT_PACKET_BUFFERS.values()) {
            try { b.release(); } catch (Throwable ignore) {}
        }
        DIRECT_PACKET_BUFFERS.clear();
        PACKET_CREATION_TIMES.clear();
        SINGLE_COPY_ADMITTED.clear();
        com.rustcraft.bridge.SingleCopyPipeline.resetForTesting();
        RECEIPT_VERIFIED.set(false);
        RECEIPT_CHECK_ATTEMPTED.set(false);
        receiptFailureReason = null;
    }

    public static void setReceiptVerifiedForTesting(boolean verified) {
        RECEIPT_CHECK_ATTEMPTED.set(true);
        RECEIPT_VERIFIED.set(verified);
        if (!verified) receiptFailureReason = "test_forced_invalid";
    }

    /**
     * Verify the closure input receipt binding the closure campaign hash,
     * canonical profile hash, campaign jar hash, commit SHA, and closure verdict.
     */
    public static boolean verifyClosureReceipt() {
        if (RECEIPT_CHECK_ATTEMPTED.get()) {
            return RECEIPT_VERIFIED.get();
        }
        RECEIPT_CHECK_ATTEMPTED.set(true);
        try {
            String prop = System.getProperty(PROPERTY_RECEIPT);
            if (prop == null || prop.trim().isEmpty()) {
                prop = System.getProperty(PROPERTY_RECEIPT_ALT);
            }
            File receiptFile;
            if (prop != null && !prop.trim().isEmpty()) {
                receiptFile = new File(prop);
            } else {
                receiptFile = new File("target/authority-review/closure-input-receipt.json");
                if (!receiptFile.exists()) {
                    receiptFile = new File("C:/rustcraft/target/authority-review/closure-input-receipt.json");
                }
            }
            if (!receiptFile.exists()) {
                receiptFailureReason = "closure-input-receipt.json not found at " + receiptFile.getAbsolutePath();
                System.err.println("[RustCraft-Authority] " + receiptFailureReason);
                return false;
            }
            String content = new String(Files.readAllBytes(receiptFile.toPath()), StandardCharsets.UTF_8);
            if (!content.contains("\"closure_verdict\": \"CLOSED\"")
                    && !content.contains("\"closure_verdict\":\"CLOSED\"")) {
                receiptFailureReason = "closure_verdict is not CLOSED";
                System.err.println("[RustCraft-Authority] " + receiptFailureReason);
                return false;
            }
            if (!content.contains("\"closure_state\": \"LIVE_SHADOW_CLOSED\"")
                    && !content.contains("\"closure_state\":\"LIVE_SHADOW_CLOSED\"")) {
                receiptFailureReason = "closure_state is not LIVE_SHADOW_CLOSED";
                System.err.println("[RustCraft-Authority] " + receiptFailureReason);
                return false;
            }
            if (!content.contains("\"production_authority\": false")
                    && !content.contains("\"production_authority\":false")) {
                receiptFailureReason = "production_authority is not false";
                System.err.println("[RustCraft-Authority] " + receiptFailureReason);
                return false;
            }
            RECEIPT_VERIFIED.set(true);
            System.out.println("[RustCraft-Authority] Closure input receipt verified successfully: " + receiptFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            receiptFailureReason = "Receipt verification exception: " + t;
            System.err.println("[RustCraft-Authority] " + receiptFailureReason);
            return false;
        }
    }

    /**
     * Primary entry point called at SPacketChunkData constructor entry right after super().
     *
     * @param tokenObj  AttemptToken returned by LiveWriterHooks.packetCaptureObserve (or null)
     * @param packetObj The SPacketChunkData instance being constructed
     * @param chunkObj  The Chunk instance
     * @param filter    changedSectionFilter
     * @return true if packet was successfully populated with Rust bytes (constructor must RETURN early);
     *         false if packet must be constructed via untouched Java constructor body (fallback).
     */
    public static boolean tryAuthority(Object tokenObj, Object packetObj, Object chunkObj, int filter) {
        if (!experimentEnabled) {
            return false; // Default OFF: untouched Java path
        }

        // Gate 1: Check Cap Early
        if (RUST_SELECTED.get() >= authorityCap) {
            CAP_EXHAUSTED.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Gate 2: Verify Closure Input Receipt
        if (!verifyClosureReceipt()) {
            FALLBACK_RECEIPT_INVALID.incrementAndGet();
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Gate 3: Filter Scope (Full Chunk 0xFFFF only)
        if ((filter & 0xFFFF) != 0xFFFF) {
            EXCLUDED_FILTER.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Gate 4: V2 Session Admission Check
        if (!LiveWriterHooks.sessionEnabled() || Boolean.TRUE.equals(LiveWriterHooks.gateDisqualifiedSafe())) {
            FALLBACK_SESSION_UNADMITTED.incrementAndGet();
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Evict expired direct buffers if any (prevent lingering buffer leaks)
        evictExpiredDirectBuffers();

        // ------------------------------------------------------------------
        // TRUE DIRECT RETAINED PACKET FAST PATH:
        // Qualified, registered, clean NativeChunk -> complete pre-compression
        // Netty ByteBuf (single-copy) or payload-only direct ByteBuf (b3df84c
        // two-copy baseline). Bypasses LivePacketCapture, CaptureDraft,
        // OwnedPacketSnapshot, RCSNAP, IN_BUF, OUT_BUF.
        // ------------------------------------------------------------------
        if (chunkObj != null && (singleCopyEnabled() || directNettyEnabled || directNettyShadow)) {
            initChunkReflection(chunkObj.getClass());
            if (chunkReflectionInitialized && chunkFieldX != null && chunkFieldZ != null) {
                try {
                    int dim = extractWorldDimension(chunkObj);
                    if (dim == 0 && isChunkTileEntitiesEmpty(chunkObj)) {
                        int cx = chunkFieldX.getInt(chunkObj);
                        int cz = chunkFieldZ.getInt(chunkObj);

                        // Canonical generation authority: the native registry
                        // itself, queried at admission time. No Java-side
                        // retained record can go stale anymore.
                        long generationId = com.rustcraft.bridge.NativeChunkBridge
                                .findGeneration(dim, cx, cz);

                        if (generationId > 0) {
                            // Check whether chunk has any pending mutations
                            int[] work = com.rustcraft.bridge.ChunkMutationTracker.peekWork(chunkObj);
                            boolean clean = (work == null || (work[1] == 0 && work[2] == 0 && work[3] == 0 && work[4] == 0));

                            if (clean) {
                                if (singleCopyEnabled()) {
                                    // Freshness gate (M4.2C race, caught by the
                                    // single-copy full-body shadow): registration
                                    // light is a DEFAULT until the chunk's first
                                    // full sync, and the Java light engine's
                                    // post-registration edits leave no work bits -
                                    // a clean work mask does NOT imply synced
                                    // light. Synchronize the native state on this
                                    // thread so the body encodes exactly the state
                                    // the Java packet will read.
                                    try {
                                        // The coherency reflection initializes per-thread;
                                        // the server thread needs it before any refresh.
                                        com.rustcraft.bridge.M4Coherency.initReflectionPublicGate(chunkObj);
                                        com.rustcraft.bridge.M4Coherency.refreshChunkNow(chunkObj);
                                    } catch (Throwable refreshFailure) {
                                        System.err.println("[RustCraft-SingleCopy] retained refresh failed: "
                                                + refreshFailure);
                                        SINGLE_COPY_FALLBACKS.incrementAndGet();
                                        JAVA_FALLBACK.incrementAndGet();
                                        JAVA_SELECTED.incrementAndGet();
                                        return false;
                                    }
                                    return trySingleCopyAdmission(packetObj, chunkObj, dim, cx, cz,
                                            generationId, extractWorldHasSky(chunkObj));
                                }
                                // b3df84c two-copy retained path (preserved for
                                // A/B baselines and regression safety).
                                if (OUTSTANDING_DIRECT_BUFFERS.get() >= MAX_OUTSTANDING_DIRECT_BUFFERS) {
                                    DIRECT_BUFFER_PRESSURE_FALLBACK.incrementAndGet();
                                    JAVA_FALLBACK.incrementAndGet();
                                    JAVA_SELECTED.incrementAndGet();
                                    return false;
                                }

                                ensureDllLoaded();
                                io.netty.buffer.ByteBuf directBuf = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(BUFFER_CAPACITY);
                                long directAddr = directBuf.memoryAddress();
                                boolean hasSky = extractWorldHasSky(chunkObj);

                                long packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                                        0, cx, cz, generationId,
                                        (byte) (hasSky ? 1 : 0), (byte) 1,
                                        directAddr, BUFFER_CAPACITY);

                                PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
                                if (result.isSuccess()) {
                                    long selected;
                                    do {
                                        selected = RUST_SELECTED.get();
                                        if (selected >= authorityCap) {
                                            directBuf.release();
                                            CAP_EXHAUSTED.incrementAndGet();
                                            JAVA_SELECTED.incrementAndGet();
                                            return false;
                                        }
                                    } while (!RUST_SELECTED.compareAndSet(selected, selected + 1));

                                    int rustMask = result.emittedMask();
                                    int rustLen = result.bytesWritten();
                                    directBuf.writerIndex(rustLen);

                                    DIRECT_NETTY_BUFFERS_ALLOCATED.incrementAndGet();
                                    OUTSTANDING_DIRECT_BUFFERS.incrementAndGet();
                                    registerDirectBuffer(packetObj, directBuf);

                                    if (directNettyShadow) {
                                        byte[] rustPayload = new byte[rustLen];
                                        directBuf.getBytes(0, rustPayload);
                                        populatePacketFields(packetObj, cx, cz, rustMask, rustPayload);
                                    } else {
                                        populatePacketFields(packetObj, cx, cz, rustMask, EMPTY_PAYLOAD);
                                    }

                                    RETAINED_FAST_PATH.incrementAndGet();
                                    RETAINED_RUST_SELECTED.incrementAndGet();
                                    AUTHORITY_ELIGIBLE.incrementAndGet();
                                    logAuditEvent(cx, cz, rustMask, rustLen, selected + 1);
                                    return true;
                                } else {
                                    directBuf.release();
                                    RUST_ENCODE_FAILURE.incrementAndGet();
                                    JAVA_FALLBACK.incrementAndGet();
                                    JAVA_SELECTED.incrementAndGet();
                                    return false;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[RustCraft-Authority] Retained fast path reflection error: " + t);
                }
            }
        }

        // ------------------------------------------------------------------
        // SEED OR EPHEMERAL FALLBACK PATH (for un-registered chunks)
        // ------------------------------------------------------------------
        SNAPSHOT_SEED_PATH.incrementAndGet();

        // Gate 5: Token / Scope Evaluation from S02 Capture Admission
        Object effectiveToken = tokenObj;
        if (effectiveToken == null) {
            effectiveToken = LivePacketCapture.begin(packetObj, chunkObj, filter);
        }

        if (effectiveToken == null || !(effectiveToken instanceof LivePacketCapture.AttemptToken)) {
            LivePacketCapture.RejectionReason last = LivePacketCapture.lastRejection();
            System.err.println("[RustCraft-Authority] Scope rejection: " + last);
            if (last == LivePacketCapture.RejectionReason.TE_PRESENT) {
                EXCLUDED_TE.incrementAndGet();
            } else if (last == LivePacketCapture.RejectionReason.EXTENDED_ID) {
                EXCLUDED_HIGH_STATE.incrementAndGet();
            } else if (last == LivePacketCapture.RejectionReason.UNSUPPORTED_WORLD) {
                EXCLUDED_DIMENSION.incrementAndGet();
            } else {
                JAVA_FALLBACK.incrementAndGet();
            }
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        LivePacketCapture.AttemptToken token = (LivePacketCapture.AttemptToken) effectiveToken;
        if (token.consumed) {
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Double-check Cap
        long currentSelected = RUST_SELECTED.get();
        if (currentSelected >= authorityCap) {
            CAP_EXHAUSTED.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Gate 6: Create Owned Capture Snapshot Under Gate
        OwnedPacketSnapshot snapshot;
        try {
            snapshot = token.draft.createOwnedSnapshot();
            if (!token.session.gate.endCapture(token.attempt, null)) {
                token.consumed = true;
                JAVA_FALLBACK.incrementAndGet();
                JAVA_SELECTED.incrementAndGet();
                return false;
            }
            token.consumed = true; // Gate released, snapshot created exactly once
        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] Snapshot creation failed: " + t);
            token.consumed = true;
            try { token.session.gate.endCapture(token.attempt, t); } catch (Throwable ignore) {}
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Gate 7: Native Rust Encode
        try {
            byte[] transport = snapshot.globalPaletteBits > 16
                    ? snapshot.toTransportBytesV2() : snapshot.toTransportBytes();
            if (transport.length > BUFFER_CAPACITY) {
                System.err.println("[RustCraft-Authority] Transport bytes exceed buffer capacity: " + transport.length);
                RUST_ENCODE_FAILURE.incrementAndGet();
                JAVA_FALLBACK.incrementAndGet();
                JAVA_SELECTED.incrementAndGet();
                return false;
            }

            ByteBuffer inBuf = IN_BUF.get();
            inBuf.clear();
            inBuf.put(transport);

            ensureDllLoaded();
            long inAddr = getBufferAddress(inBuf);

            // Retained Rust ChunkState: Check if chunk is registered in native memory
            int dim = snapshot.dimension;
            long genId = com.rustcraft.bridge.NativeChunkBridge.findGeneration(dim, snapshot.chunkX, snapshot.chunkZ);
            if (genId <= 0) {
                // Not yet registered: seed persistent NativeChunk from transport
                genId = com.rustcraft.bridge.NativeChunkBridge.seedFromTransport(inAddr, transport.length);
                if (genId > 0) {
                    RETAINED_SEEDED.incrementAndGet();
                }
            }

            // Single-copy emission: complete pre-compression body built on
            // this thread, one payload movement, consumed by the outbound
            // handler. The seed path's Java capture already happened (the
            // chunk was not registered), but the wire emission is identical
            // to the retained fast path.
            if (singleCopyEnabled() && genId > 0) {
                return trySingleCopyAdmission(packetObj, chunkObj, dim, snapshot.chunkX,
                        snapshot.chunkZ, genId, snapshot.skylight);
            }

            // Direct Netty Emission Path: Encode directly into pooled Netty ByteBuf
            if (directNettyEnabled || directNettyShadow) {
                if (OUTSTANDING_DIRECT_BUFFERS.get() >= MAX_OUTSTANDING_DIRECT_BUFFERS) {
                    DIRECT_BUFFER_PRESSURE_FALLBACK.incrementAndGet();
                    JAVA_FALLBACK.incrementAndGet();
                    JAVA_SELECTED.incrementAndGet();
                    return false;
                }

                io.netty.buffer.ByteBuf directBuf = io.netty.buffer.PooledByteBufAllocator.DEFAULT.directBuffer(BUFFER_CAPACITY);
                long directAddr = directBuf.memoryAddress();

                long packed = -1;
                boolean fromRetained = false;
                if (genId > 0) {
                    packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                            dim, snapshot.chunkX, snapshot.chunkZ, genId,
                            (byte) (snapshot.skylight ? 1 : 0),
                            (byte) (snapshot.fullChunk ? 1 : 0),
                            directAddr, BUFFER_CAPACITY);
                    if (packed > 0 && PacketEncodeResultV2.decode(packed).isSuccess()) {
                        fromRetained = true;
                        RETAINED_RUST_SELECTED.incrementAndGet();
                    }
                }

                if (!fromRetained) {
                    EPHEMERAL_FALLBACK.incrementAndGet();
                    packed = OwnedSnapshotBridge.encodeOwnedV1(inAddr, transport.length, directAddr, BUFFER_CAPACITY);
                }

                PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
                if (!result.isSuccess()) {
                    directBuf.release();
                    System.err.println("[RustCraft-Authority] Rust encode returned non-success status: " + result.failure());
                    RUST_ENCODE_FAILURE.incrementAndGet();
                    JAVA_FALLBACK.incrementAndGet();
                    JAVA_SELECTED.incrementAndGet();
                    return false;
                }

                long selected;
                do {
                    selected = RUST_SELECTED.get();
                    if (selected >= authorityCap) {
                        directBuf.release();
                        CAP_EXHAUSTED.incrementAndGet();
                        JAVA_SELECTED.incrementAndGet();
                        return false;
                    }
                } while (!RUST_SELECTED.compareAndSet(selected, selected + 1));

                int rustMask = result.emittedMask();
                int rustLen = result.bytesWritten();
                directBuf.writerIndex(rustLen);

                DIRECT_NETTY_BUFFERS_ALLOCATED.incrementAndGet();
                OUTSTANDING_DIRECT_BUFFERS.incrementAndGet();
                registerDirectBuffer(packetObj, directBuf);

                if (directNettyShadow) {
                    byte[] rustPayload = new byte[rustLen];
                    directBuf.getBytes(0, rustPayload);
                    populatePacketFields(packetObj, snapshot.chunkX, snapshot.chunkZ, rustMask, rustPayload);
                } else {
                    populatePacketFields(packetObj, snapshot.chunkX, snapshot.chunkZ, rustMask, EMPTY_PAYLOAD);
                }

                AUTHORITY_ELIGIBLE.incrementAndGet();
                logAuditEvent(snapshot.chunkX, snapshot.chunkZ, rustMask, rustLen, selected + 1);
                return true;
            }

            // Legacy non-Direct-Netty path (writes to OUT_BUF -> heap byte[])
            ByteBuffer outBuf = OUT_BUF.get();
            long outAddr = getBufferAddress(outBuf);
            long packed = -1;
            boolean fromRetained = false;
            if (genId > 0) {
                packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                        dim, snapshot.chunkX, snapshot.chunkZ, genId,
                        (byte) (snapshot.skylight ? 1 : 0),
                        (byte) (snapshot.fullChunk ? 1 : 0),
                        outAddr, BUFFER_CAPACITY);
                if (packed > 0 && PacketEncodeResultV2.decode(packed).isSuccess()) {
                    fromRetained = true;
                    RETAINED_RUST_SELECTED.incrementAndGet();
                }
            }

            if (!fromRetained) {
                EPHEMERAL_FALLBACK.incrementAndGet();
                packed = OwnedSnapshotBridge.encodeOwnedV1(inAddr, transport.length, outAddr, BUFFER_CAPACITY);
            }

            PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
            if (!result.isSuccess()) {
                System.err.println("[RustCraft-Authority] Rust encode returned non-success status: " + result.failure());
                RUST_ENCODE_FAILURE.incrementAndGet();
                JAVA_FALLBACK.incrementAndGet();
                JAVA_SELECTED.incrementAndGet();
                return false;
            }

            long selected;
            do {
                selected = RUST_SELECTED.get();
                if (selected >= authorityCap) {
                    CAP_EXHAUSTED.incrementAndGet();
                    JAVA_SELECTED.incrementAndGet();
                    return false;
                }
            } while (!RUST_SELECTED.compareAndSet(selected, selected + 1));

            int rustMask = result.emittedMask();
            int rustLen = result.bytesWritten();

            byte[] rustPayload = new byte[rustLen];
            outBuf.clear();
            outBuf.get(rustPayload);

            boolean populated = populatePacketFields(packetObj, snapshot.chunkX, snapshot.chunkZ, rustMask, rustPayload);
            if (!populated) {
                System.err.println("[RustCraft-Authority] Failed to populate packet fields");
                RUST_SELECTED.decrementAndGet();
                JAVA_FALLBACK.incrementAndGet();
                JAVA_SELECTED.incrementAndGet();
                return false;
            }

            AUTHORITY_ELIGIBLE.incrementAndGet();
            logAuditEvent(snapshot.chunkX, snapshot.chunkZ, rustMask, rustLen, selected + 1);
            return true; // RUST PACKET COMMITTED!

        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] Rust encode exception: " + t);
            RUST_ENCODE_FAILURE.incrementAndGet();
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }
    }

    // ------------------------------------------------------------------
    // SINGLE-COPY ADMISSION (complete pre-compression body)
    // ------------------------------------------------------------------

    public static final String PROPERTY_SINGLE_COPY = "rustcraft.singleCopy";
    public static final String PROPERTY_SINGLE_COPY_SHADOW = "rustcraft.singleCopyShadow";

    private static volatile boolean singleCopyDirect =
            Boolean.getBoolean(PROPERTY_SINGLE_COPY);
    private static volatile boolean singleCopyShadow =
            Boolean.getBoolean(PROPERTY_SINGLE_COPY_SHADOW);

    public static boolean singleCopyDirectEnabled() {
        return singleCopyDirect;
    }

    public static boolean singleCopyShadowEnabled() {
        return singleCopyShadow;
    }

    public static boolean singleCopyEnabled() {
        return singleCopyDirect || singleCopyShadow;
    }

    public static void setSingleCopyModes(boolean direct, boolean shadow) {
        singleCopyDirect = direct;
        singleCopyShadow = shadow;
    }

    /** Test-only: mark a packet as single-copy admitted (weak set). */
    public static void markAdmittedForTest(Object packet) {
        SINGLE_COPY_ADMITTED.put(packet, Boolean.TRUE);
    }

    /**
     * Shared single-copy admission for the retained fast path and the seed
     * path. Builds the complete immutable pre-compression body on THIS thread
     * (one payload movement), registers the ticket, and:
     *  - SHADOW: returns false so the untouched Java constructor body runs and
     *    vanilla transmits; the outbound capture handler byte-compares the
     *    real NettyPacketEncoder output against the body.
     *  - DIRECT: populates the packet shell (EMPTY payload + metadata) and
     *    returns true; the outbound handler consumes the body per write,
     *    bypassing NettyPacketEncoder.
     * Any failure releases the body and falls back to the Java path.
     */
    private static boolean trySingleCopyAdmission(Object packetObj, Object chunkObj,
                                                  int dim, int cx, int cz,
                                                  long generationId, boolean skylight) {
        ensureDllLoaded();

        int packetId;
        try {
            packetId = resolveClientboundPacketId(packetObj);
        } catch (Throwable t) {
            System.err.println("[RustCraft-SingleCopy] packet id lookup failed: " + t);
            SINGLE_COPY_FALLBACKS.incrementAndGet();
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Defensive sweep piggybacks on admission traffic (never the primary
        // lifecycle mechanism).
        com.rustcraft.bridge.SingleCopyPipeline.sweepExpired();

        com.rustcraft.bridge.SingleCopyPipeline.SingleCopyTicket ticket =
                com.rustcraft.bridge.SingleCopyChunkBody.build(
                        packetObj, dim, cx, cz, generationId, skylight, true, packetId);
        if (ticket == null) {
            SINGLE_COPY_FALLBACKS.incrementAndGet();
            JAVA_FALLBACK.incrementAndGet();
            JAVA_SELECTED.incrementAndGet();
            return false;
        }

        // Bounded experiment cap consumed after the body is complete; an
        // over-cap body is released again immediately.
        long selected;
        do {
            selected = RUST_SELECTED.get();
            if (selected >= authorityCap) {
                ticket.invalidate();
                CAP_EXHAUSTED.incrementAndGet();
                JAVA_SELECTED.incrementAndGet();
                return false;
            }
        } while (!RUST_SELECTED.compareAndSet(selected, selected + 1));

        RETAINED_RUST_SELECTED.incrementAndGet();
        AUTHORITY_ELIGIBLE.incrementAndGet();
        SINGLE_COPY_ADMITTED_COUNT.incrementAndGet();
        logAuditEvent(cx, cz, ticket.mask, ticket.payloadBytes, selected + 1);

        if (singleCopyShadow) {
            // Shadow: vanilla constructor + vanilla transmission; the body is
            // compared against the encoder output by the capture handler.
            RETAINED_FAST_PATH.incrementAndGet();
            return false;
        }

        // Direct: the packet shell must never serialize vanilla bytes again.
        populatePacketFields(packetObj, cx, cz, ticket.mask, EMPTY_PAYLOAD);
        SINGLE_COPY_ADMITTED.put(packetObj, Boolean.TRUE);
        RETAINED_FAST_PATH.incrementAndGet();
        return true;
    }

    /** Packet id via the installed EnumConnectionState — the exact lookup
     *  NettyPacketEncoder performs (func_179246_a). Never hardcoded. */
    private static volatile java.lang.reflect.Method PACKET_ID_METHOD;
    private static volatile Object PLAY_STATE;
    private static volatile Object CLIENTBOUND_DIRECTION;

    private static int resolveClientboundPacketId(Object packet) throws Exception {
        java.lang.reflect.Method m = PACKET_ID_METHOD;
        Object play = PLAY_STATE;
        Object dir = CLIENTBOUND_DIRECTION;
        if (m == null) {
            // The bridge loads on the launch classloader; Minecraft classes
            // live on the child transforming loader. The established runtime
            // discovery is the thread's context loader (see LiveWriterHooks).
            ClassLoader runtime = Thread.currentThread().getContextClassLoader();
            if (runtime == null) {
                runtime = PacketAuthorityExperiment.class.getClassLoader();
            }
            Class<?> stateCls = Class.forName("net.minecraft.network.EnumConnectionState", true, runtime);
            play = stateCls.getField("PLAY").get(null);
            Class<?> dirCls = Class.forName("net.minecraft.network.EnumPacketDirection", true, runtime);
            dir = dirCls.getField("CLIENTBOUND").get(null);
            m = stateCls.getMethod("func_179246_a", dirCls,
                    Class.forName("net.minecraft.network.Packet", true, runtime));
            PACKET_ID_METHOD = m;
            PLAY_STATE = play;
            CLIENTBOUND_DIRECTION = dir;
        }
        Object id = m.invoke(play, dir, packet);
        if (id == null) {
            throw new IllegalStateException("packet not registered in PLAY/CLIENTBOUND");
        }
        return (Integer) id;
    }

    /**
     * Called at the entry of SPacketChunkData.func_148840_b (writePacketData).
     *
     * SINGLE-COPY GUARD (checked first): a packet admitted as RUST_SINGLE_COPY
     * carries an EMPTY payload shell — vanilla serialization of it would emit
     * a malformed empty chunk body. When the packet's body is still alive
     * (reachable only when the outbound handler is absent, e.g. install
     * failure), this serves the correct bytes through the b3df84c two-copy
     * emission. When the body is gone, the write PROMISE FAILS via exception —
     * never a malformed empty chunk packet on the wire.
     *
     * LEGACY (b3df84c, unchanged): if this packet has an authoritative payload
     * direct ByteBuf registered (directNetty mode), write the packet headers,
     * transfer the direct bytes into the Netty packet buffer, release it, and
     * return true (skipping the Java writePacketData body).
     *
     * Otherwise: returns false (vanilla Java writePacketData body executes
     * untouched — every plain Java packet).
     */
    public static boolean tryWritePacketDataDirect(Object packet, Object packetBufferObj) {
        // ---- single-copy admitted packets --------------------------------
        if (singleCopyDirect && packet != null && SINGLE_COPY_ADMITTED.containsKey(packet)) {
            com.rustcraft.bridge.SingleCopyPipeline.SingleCopyTicket ticket =
                    com.rustcraft.bridge.SingleCopyPipeline.ticketFor(packet);
            if (ticket == null) {
                // Body already consumed and released (all writes done). A
                // late write must not serialize the empty shell.
                throw new IllegalStateException(
                        "RustCraft single-copy body unavailable for an admitted packet; "
                                + "refusing malformed empty-payload serialization");
            }
            if (!(packetBufferObj instanceof io.netty.buffer.ByteBuf)) {
                throw new IllegalStateException(
                        "RustCraft single-copy fallback expected ByteBuf, got "
                                + packetBufferObj.getClass().getName());
            }
            if (!initPacketFields(packet.getClass())) {
                throw new IllegalStateException(
                        "RustCraft single-copy fallback cannot resolve packet fields");
            }
            io.netty.buffer.ByteBuf pb = (io.netty.buffer.ByteBuf) packetBufferObj;
            io.netty.buffer.ByteBuf view = ticket.body.retainedDuplicate();
            try {
                int cx = packetChunkX.getInt(packet);
                int cz = packetChunkZ.getInt(packet);
                boolean full = packetFull.getBoolean(packet);
                int mask = packetMask.getInt(packet);
                // Payload slice only: the body also carries the packet-id
                // VarInt header and the TE trailer, which the vanilla encoder
                // and this fallback frame themselves.
                io.netty.buffer.ByteBuf payload = view.slice(ticket.payloadOffset, ticket.payloadBytes);
                pb.writeInt(cx);
                pb.writeInt(cz);
                pb.writeBoolean(full);
                writeVarInt(pb, mask);
                writeVarInt(pb, ticket.payloadBytes);
                pb.writeBytes(payload); // the ONE payload copy this fallback owns
                writeVarInt(pb, 0);
            } catch (IllegalAccessException reflectiveFailure) {
                throw new IllegalStateException(
                        "RustCraft single-copy fallback cannot read packet fields", reflectiveFailure);
            } finally {
                view.release();
            }
            com.rustcraft.bridge.SingleCopyPipeline.telemetry()
                    .packetbufferPayloadCopies.incrementAndGet();
            com.rustcraft.bridge.SingleCopyPipeline.telemetry()
                    .fallbackTwoCopyServes.incrementAndGet();
            return true;
        }

        // ---- legacy b3df84c direct-netty emission (unchanged) ------------
        if (!directNettyEnabled && !directNettyShadow) {
            return false;
        }
        if (packet == null || packetBufferObj == null) {
            return false;
        }

        io.netty.buffer.ByteBuf directBuf = DIRECT_PACKET_BUFFERS.remove(packet);
        PACKET_CREATION_TIMES.remove(packet);
        if (directBuf == null) {
            return false;
        }

        try {
            if (!(packetBufferObj instanceof io.netty.buffer.ByteBuf)) {
                System.err.println("[RustCraft-Authority] tryWritePacketDataDirect fallback: packetBufferObj not ByteBuf! "
                        + "obj=" + (packetBufferObj != null ? packetBufferObj.getClass().getName() : "null")
                        + " objCL=" + (packetBufferObj != null ? packetBufferObj.getClass().getClassLoader() : "null")
                        + " objSuper=" + (packetBufferObj != null ? packetBufferObj.getClass().getSuperclass().getName() : "null")
                        + " byteBufCL=" + io.netty.buffer.ByteBuf.class.getClassLoader());
                DIRECT_NETTY_FALLBACKS.incrementAndGet();
                return false;
            }
            if (!initPacketFields(packet.getClass())) {
                System.err.println("[RustCraft-Authority] tryWritePacketDataDirect fallback: initPacketFields failed for "
                        + packet.getClass().getName());
                DIRECT_NETTY_FALLBACKS.incrementAndGet();
                return false;
            }
            io.netty.buffer.ByteBuf pb = (io.netty.buffer.ByteBuf) packetBufferObj;

            if (directNettyShadow) {
                // In shadow mode, compare directBuf bytes with packet.field_186949_d
                try {
                    byte[] javaBuf = (byte[]) packetBuffer.get(packet);
                    if (javaBuf != null && javaBuf.length == directBuf.readableBytes()) {
                        boolean match = true;
                        for (int i = 0; i < javaBuf.length; i++) {
                            if (javaBuf[i] != directBuf.getByte(directBuf.readerIndex() + i)) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            DIRECT_NETTY_SHADOW_MATCHES.incrementAndGet();
                        } else {
                            DIRECT_NETTY_SHADOW_MISMATCHES.incrementAndGet();
                        }
                    } else {
                        DIRECT_NETTY_SHADOW_MISMATCHES.incrementAndGet();
                    }
                } catch (Throwable t) {
                    DIRECT_NETTY_SHADOW_MISMATCHES.incrementAndGet();
                }
                // In shadow mode, release direct buffer and return false so vanilla writePacketData transmits
                return false;
            }

            int cx = packetChunkX.getInt(packet);
            int cz = packetChunkZ.getInt(packet);
            boolean full = packetFull.getBoolean(packet);
            int mask = packetMask.getInt(packet);
            int len = directBuf.readableBytes();

            // Protocol 340 SPacketChunkData body:
            // 1. chunkX (int, 4 bytes BE)
            pb.writeInt(cx);
            // 2. chunkZ (int, 4 bytes BE)
            pb.writeInt(cz);
            // 3. fullChunk (boolean, 1 byte)
            pb.writeBoolean(full);
            // 4. availableSections (VarInt)
            writeVarInt(pb, mask);
            // 5. dataLength (VarInt)
            writeVarInt(pb, len);
            // 6. data bytes (direct to direct transfer inside Netty!)
            pb.writeBytes(directBuf);
            // 7. tileEntities size (VarInt = 0)
            writeVarInt(pb, 0);

            DIRECT_NETTY_COMMITTED.incrementAndGet();
            DIRECT_NETTY_BYTES_TRANSMITTED.addAndGet(len);
            return true; // Completely handled!
        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] tryWritePacketDataDirect error: " + t);
            DIRECT_NETTY_FALLBACKS.incrementAndGet();
            return false;
        } finally {
            try {
                directBuf.release();
                DIRECT_NETTY_BUFFERS_RELEASED.incrementAndGet();
                OUTSTANDING_DIRECT_BUFFERS.decrementAndGet();
            } catch (Throwable ignore) {}
        }
    }

    public static int evictExpiredDirectBuffers() {
        if (PACKET_CREATION_TIMES.isEmpty()) return 0;
        long now = System.nanoTime();
        int evicted = 0;
        for (Map.Entry<Object, Long> entry : PACKET_CREATION_TIMES.entrySet()) {
            if (now - entry.getValue() > PACKET_EXPIRATION_NANOS) {
                Object packet = entry.getKey();
                PACKET_CREATION_TIMES.remove(packet);
                io.netty.buffer.ByteBuf buf = DIRECT_PACKET_BUFFERS.remove(packet);
                if (buf != null) {
                    try {
                        buf.release();
                        DIRECT_NETTY_BUFFERS_RELEASED.incrementAndGet();
                        OUTSTANDING_DIRECT_BUFFERS.decrementAndGet();
                        evicted++;
                    } catch (Throwable ignore) {}
                }
            }
        }
        return evicted;
    }

    private static void registerDirectBuffer(Object packet, io.netty.buffer.ByteBuf directBuf) {
        DIRECT_PACKET_BUFFERS.put(packet, directBuf);
        PACKET_CREATION_TIMES.put(packet, System.nanoTime());
    }

    private static void writeVarInt(io.netty.buffer.ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static void logAuditEvent(int cx, int cz, int mask, int bytes, long selectedCount) {
        System.out.println(String.format(
                "[RUST_AUTHORITY_COMMIT] packet #%d committed to wire | chunk=(%d, %d) mask=0x%04X bytes=%d cap=%d",
                selectedCount, cx, cz, mask, bytes, authorityCap));
    }

    private static long getBufferAddress(ByteBuffer buf) {
        try {
            Field f = Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(buf);
        } catch (Throwable t) {
            try {
                Method m = buf.getClass().getMethod("address");
                m.setAccessible(true);
                return ((Number) m.invoke(buf)).longValue();
            } catch (Throwable t2) {
                throw new RuntimeException("Cannot resolve direct buffer address", t);
            }
        }
    }

    private static final AtomicBoolean DLL_LOADED = new AtomicBoolean(false);

    private static void ensureDllLoaded() {
        if (DLL_LOADED.get()) return;
        synchronized (DLL_LOADED) {
            if (DLL_LOADED.get()) return;
            try {
                String dllPath = System.getProperty("rustcraft.liveShadowDll");
                if (dllPath != null && !dllPath.trim().isEmpty()) {
                    File f = new File(dllPath);
                    if (f.exists()) {
                        System.load(f.getAbsolutePath());
                        DLL_LOADED.set(true);
                        return;
                    }
                }
                File local = new File("rustcraft_ffi.dll");
                if (local.exists()) {
                    System.load(local.getAbsolutePath());
                    DLL_LOADED.set(true);
                    return;
                }
                System.loadLibrary("rustcraft_ffi");
                DLL_LOADED.set(true);
            } catch (Throwable t) {
                System.err.println("[RustCraft-Authority] Could not load rustcraft_ffi: " + t);
            }
        }
    }

    private static Class<?> packetFieldsResolvedFor;

    private static synchronized boolean initPacketFields(Class<?> packetClass) {
        if (packetBuffer != null && packetFieldsResolvedFor != null
                && packetFieldsResolvedFor.isAssignableFrom(packetClass)) {
            return true; // cached fields were resolved on this hierarchy
        }
        try {
            packetChunkX = findField(packetClass, "field_149284_a", "chunkX", "a");
            packetChunkZ = findField(packetClass, "field_149282_b", "chunkZ", "b");
            packetMask = findField(packetClass, "field_186948_c", "availableSections", "extractedSize", "c");
            packetBuffer = findField(packetClass, "field_186949_d", "buffer", "d");
            packetTEs = findField(packetClass, "field_189557_e", "tileEntityTags", "e");
            packetFull = findField(packetClass, "field_149279_g", "fullChunk", "g");
            boolean ok = packetChunkX != null && packetChunkZ != null && packetMask != null
                    && packetBuffer != null && packetTEs != null && packetFull != null;
            if (ok) {
                packetFieldsResolvedFor = packetClass;
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean populatePacketFields(Object packet, int cx, int cz, int rustMask, byte[] rustPayload) {
        if (!initPacketFields(packet.getClass())) return false;
        try {
            packetChunkX.setInt(packet, cx);
            packetChunkZ.setInt(packet, cz);
            packetFull.setBoolean(packet, true);
            packetMask.setInt(packet, rustMask);
            packetBuffer.set(packet, rustPayload);
            packetTEs.set(packet, Collections.emptyList());
            return true;
        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] populatePacketFields exception: " + t);
            return false;
        }
    }

    private static int getChunkCoord(Object chunk, String srg, String mcp) {
        try {
            Field f = findField(chunk.getClass(), srg, mcp);
            if (f != null) return f.getInt(chunk);
        } catch (Throwable ignore) {}
        return 0;
    }

    private static Field findField(Class<?> c, String... candidateNames) {
        while (c != null && c != Object.class) {
            for (String name : candidateNames) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignore) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static synchronized void writeReceipt() {
        String outPathStr = System.getProperty(PROPERTY_RECEIPT_OUT);
        if (outPathStr == null || outPathStr.trim().isEmpty()) {
            outPathStr = System.getProperty(PROPERTY_RECEIPT_OUT_ALT,
                    "target/authority-experiment/authority-experiment-receipt.json");
        }
        Path outPath = Paths.get(outPathStr).toAbsolutePath();
        writeReceipt(outPath);
    }

    public static synchronized void writeReceipt(Path outPath) {
        try {
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Map<String, Object> receipt = new LinkedHashMap<String, Object>();
            receipt.put("schema", "RUSTCRAFT_BOUNDED_AUTHORITY_EXPERIMENT_RECEIPT_V1");
            receipt.put("lifecycle_state", STATE_BOUNDED_AUTHORITY_EXPERIMENT);
            receipt.put("production_authority", false);
            receipt.put("experiment_enabled", experimentEnabled);
            receipt.put("authority_cap", authorityCap);
            receipt.put("receipt_verified", RECEIPT_VERIFIED.get());
            if (receiptFailureReason != null) {
                receipt.put("receipt_failure_reason", receiptFailureReason);
            }
            Map<String, Object> counters = new LinkedHashMap<String, Object>();
            counters.put("authority_eligible", AUTHORITY_ELIGIBLE.get());
            counters.put("rust_selected", RUST_SELECTED.get());
            counters.put("java_selected", JAVA_SELECTED.get());
            counters.put("java_fallback", JAVA_FALLBACK.get());
            counters.put("rust_encode_failure", RUST_ENCODE_FAILURE.get());
            counters.put("excluded_high_state", EXCLUDED_HIGH_STATE.get());
            counters.put("excluded_te", EXCLUDED_TE.get());
            counters.put("excluded_filter", EXCLUDED_FILTER.get());
            counters.put("excluded_dimension", EXCLUDED_DIMENSION.get());
            counters.put("cap_exhausted", CAP_EXHAUSTED.get());
            counters.put("fallback_session_unadmitted", FALLBACK_SESSION_UNADMITTED.get());
            counters.put("fallback_receipt_invalid", FALLBACK_RECEIPT_INVALID.get());
            counters.put("retained_rust_selected", RETAINED_RUST_SELECTED.get());
            counters.put("retained_seeded", RETAINED_SEEDED.get());
            counters.put("retained_fast_path", RETAINED_FAST_PATH.get());
            counters.put("snapshot_seed_path", SNAPSHOT_SEED_PATH.get());
            counters.put("ephemeral_fallback", EPHEMERAL_FALLBACK.get());
            counters.put("direct_buffer_pressure_fallback", DIRECT_BUFFER_PRESSURE_FALLBACK.get());
            counters.put("direct_netty_enabled", directNettyEnabled);
            counters.put("direct_netty_shadow", directNettyShadow);
            counters.put("direct_netty_committed", DIRECT_NETTY_COMMITTED.get());
            counters.put("direct_netty_fallbacks", DIRECT_NETTY_FALLBACKS.get());
            counters.put("direct_netty_shadow_matches", DIRECT_NETTY_SHADOW_MATCHES.get());
            counters.put("direct_netty_shadow_mismatches", DIRECT_NETTY_SHADOW_MISMATCHES.get());
            counters.put("direct_netty_buffers_allocated", DIRECT_NETTY_BUFFERS_ALLOCATED.get());
            counters.put("direct_netty_buffers_released", DIRECT_NETTY_BUFFERS_RELEASED.get());
            counters.put("direct_netty_bytes_transmitted", DIRECT_NETTY_BYTES_TRANSMITTED.get());
            counters.put("outstanding_direct_buffers", OUTSTANDING_DIRECT_BUFFERS.get());
            counters.put("single_copy_admitted", SINGLE_COPY_ADMITTED_COUNT.get());
            counters.put("single_copy_fallbacks", SINGLE_COPY_FALLBACKS.get());
            counters.put("single_copy_direct_mode", singleCopyDirectEnabled());
            counters.put("single_copy_shadow_mode", singleCopyShadowEnabled());
            receipt.put("counters", counters);
            try {
                receipt.put("single_copy_telemetry",
                        com.rustcraft.bridge.SingleCopyPipeline.telemetrySummary());
            } catch (Throwable ignore) {
                // telemetry summary is diagnostic only
            }
            receipt.put("timestamp_millis", System.currentTimeMillis());

            String jsonText = json(receipt);
            Files.write(outPath, jsonText.getBytes(StandardCharsets.UTF_8));
            System.out.println("[RustCraft-Authority] Experiment receipt written to " + outPath);
        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] Failed to write experiment receipt: " + t);
        }
    }

    private static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Object v = entry.getValue();
            sb.append('"').append(entry.getKey()).append("\":");
            if (v instanceof Map) {
                sb.append(json((Map<String, Object>) v));
            } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}
