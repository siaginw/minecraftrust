package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4.2B — LIVE packet comparator: for every SPacketChunkData actually built by
 * the server (Java authoritative, Java transmitted), compare the native
 * registry encoder's output for the SAME chunk state, generation, requested
 * mask and lighting context.
 *
 * Snapshot validity first: the chunk's pending mutation work is refreshed
 * synchronously BEFORE encoding; a stale encode (snapshot token rejected,
 * error -3) is counted and NOT compared/published. Unsupported cases stay
 * Java-only and are counted as fallbacks, never silently folded into native
 * comparisons: partial-filter packets, unregistered/invalidated chunks,
 * TileEntity-containing chunks (payload excludes TE tags — Java-owned), and
 * any refresh failure.
 *
 * Gate A: exact bytes (palette history coincidence — counted, never forced).
 * Gate B: independent decoded-state equality (states, section coverage, both
 * lights, biomes) via the M4PacketParityHarness parser.
 *
 * First unexplained mismatch DISABLES the comparator (stop condition).
 * Enabled only when -Dminecraftrust.m4.packet_compare=SHADOW (default off).
 */
public final class M4PacketCompare {

    public static final AtomicLong PACKETS_SEEN = new AtomicLong();
    public static final AtomicLong COMPARED = new AtomicLong();
    public static final AtomicLong GATE_A_EXACT = new AtomicLong();
    public static final AtomicLong GATE_B_EQUAL = new AtomicLong();
    public static final AtomicLong TE_CHUNKS_COMPARED = new AtomicLong();
    public static final AtomicLong FULL_RESEND_POST_MUTATION = new AtomicLong();
    public static final AtomicLong PARTIAL_FILTER_SKIPPED = new AtomicLong();
    public static final AtomicLong NOT_REGISTERED_SKIPPED = new AtomicLong();
    public static final AtomicLong UNSYNCED_SKIPPED = new AtomicLong();
    public static final AtomicLong REFRESH_BEFORE_COMPARE = new AtomicLong();
    public static final AtomicLong REFRESH_FAIL_SKIPPED = new AtomicLong();
    public static final AtomicLong STALE_ENCODE_REJECTED = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();
    public static final AtomicLong COMPARE_EXCEPTIONS = new AtomicLong();
    public static volatile boolean DISABLED = false;
    public static volatile String FIRST_MISMATCH = "none";

    /** chunk coords -> mutations seen before the last full-packet compare for that chunk */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> LAST_SEEN_MUTATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile boolean inited;
    private static Field F_MASK, F_PAYLOAD, F_FULL;
    private static Method M_HAS_TILE_MAP;

    public static final String RUN_ID = "M42C-" + Long.toString(System.currentTimeMillis(), 36);
    private static volatile java.nio.ByteBuffer GEN_INFO_BB;

    private M4PacketCompare() {}

    public static boolean enabled() {
        return "SHADOW".equalsIgnoreCase(System.getProperty("minecraftrust.m4.packet_compare", "OFF"));
    }

    /** Called at constructor entry (from populatePacket) to pin the version. */
    public static final AtomicLong CTOR_ENTRY_SEEN = new AtomicLong();
    public static final AtomicLong FULL_CTOR_SEEN = new AtomicLong();

    public static void onPacketConstructing(Object packet, Object chunk) {
        CTOR_ENTRY_SEEN.incrementAndGet();
        try {
            synchronized (CTOR_VERSION) {
                CTOR_VERSION.put(packet, ChunkMutationTracker.versionOf(chunk));
            }
            // M5.8: immutable INPUT capture at ctor ENTRY — the validated interval
            // includes the original Java construction (endpoint fingerprints alone
            // cannot exclude A->B->A; the capture IS the interval's start state).
            int[][] cap = new int[16][];
            try {
                Object[] stg = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                for (int y = 0; y < 16; y++) {
                    Object st = stg[y];
                    if (st == null) continue;
                    Object cont = st.getClass().getMethod("func_186049_g").invoke(st);
                    int[] ids = new int[4096];
                    if (M4Coherency.fastExtractPublic(cont, ids) != null) cap[y] = ids;
                }
            } catch (Throwable ignore2) { }
            synchronized (CTOR_CAPTURE) {
                CTOR_CAPTURE.put(packet, cap);
            }
        } catch (Throwable ignore) { }
    }

    static final java.util.WeakHashMap<Object, int[][]> CTOR_CAPTURE = new java.util.WeakHashMap<>();
    public static final AtomicLong CAPTURES_MADE = new AtomicLong();

    public static void onPacketBuilt(Object packet, Object chunk, int filter) {
        if (!enabled() || DISABLED) return;
        try {
            PACKETS_SEEN.incrementAndGet(); // counts ctor-RETURN hook invocations (completed constructions reaching the comparator)
            if (!init(chunk)) return;

            if ((filter & 0xFFFF) == 0xFFFF) FULL_CTOR_SEEN.incrementAndGet();
            if ((filter & 0xFFFF) != 0xFFFF) {
                PARTIAL_FILTER_SKIPPED.incrementAndGet(); // unsupported: Java-only
                return;
            }

            long key = chunkKey(chunk);
            int dim = com.rustcraft.bridge.ChunkMutationTracker.DIM_UNKNOWN;
            try {
                dim = dimOf(chunk);
            } catch (Throwable ignore) {}
            int cx = cx(chunk);
            int cz = cz(chunk);
            long gen = NativeChunkBridge.findGeneration(dim, cx, cz);
            boolean hasSky = skylightOf(chunk);
            // M5.1 comparator snapshot-validity guard (same discipline as the
            // transmit guard): capture the version BEFORE extraction/encode and
            // re-read after — a moved version means torn Java state; STALE_SKIP,
            // never a mismatch.
            final int cmpVersionBefore = CTOR_VERSION.containsKey(packet)
                    ? CTOR_VERSION.get(packet) : ChunkMutationTracker.versionOf(chunk);

            M4NativeStatePayload.LAST_NATIVE_PAYLOAD.remove();

            // Snapshot validity: refresh pending work first.
            // Validity contract: never compare an unsynced snapshot. First-touch
            // extraction is too heavy for the packet thread — capture deferred.
            if (!M4Coherency.isFullySynced(chunk)) {
                UNSYNCED_SKIPPED.incrementAndGet();
                if (gen > 0) {
                    Object pktPayload = F_PAYLOAD.get(packet);
                    if (pktPayload instanceof byte[] && ((byte[]) pktPayload).length <= MAX_RECORD_BYTES) {
                        captureDeferred(dim, cx, cz, F_MASK.getInt(packet), filter, hasSky,
                                (byte[]) pktPayload, gen, chunk);
                    } else {
                        DEFERRED_CAPACITY_SKIPPED.incrementAndGet();
                    }
                }
                return;
            }
            COMPARE_IMMEDIATE.incrementAndGet();
            // capture BEFORE the refresh/extraction window (the mutation can
            // land anywhere inside it — refreshChunkNow extracts on this thread)
            final int vBefore = ChunkMutationTracker.versionOf(chunk);
            int refreshed = 0;
            try {
                refreshed = M4Coherency.refreshChunkNow(chunk);
                if (refreshed > 0) REFRESH_BEFORE_COMPARE.addAndGet(refreshed);
            } catch (Throwable t) {
                REFRESH_FAIL_SKIPPED.incrementAndGet();
                return; // fall back: no comparison on unverifiable snapshot
            }

            if (gen <= 0) {
                NOT_REGISTERED_SKIPPED.incrementAndGet();
                return;
            }

            // full-chunk resend after mutation?
            long muts = ChunkMutationTracker.HOOK_BLOCK_SETS.get() + ChunkMutationTracker.HOOK_LIGHT_SETS.get();
            Long prev = LAST_SEEN_MUTATIONS.put(key, muts);
            if (prev != null && !prev.equals(muts)) {
                FULL_RESEND_POST_MUTATION.incrementAndGet();
            }

            int javaMask = F_MASK.getInt(packet);
            byte[] javaPayload = (byte[]) F_PAYLOAD.get(packet);
            if (javaPayload == null) return;

            // M4.3E biome freshness as its own domain (worldgen writes biomes
            // cross-thread with no hook — versions prove nothing about biomes):
            // compare live Java array vs native at packet time; re-push if stale.
            try {
                byte[] live = (byte[]) chunk.getClass().getMethod("func_76605_m").invoke(chunk);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(256)
                        .order(java.nio.ByteOrder.nativeOrder());
                java.lang.reflect.Field af2 = java.nio.Buffer.class.getDeclaredField("address");
                af2.setAccessible(true);
                if (NativeChunkBridge.getBiomes(dim, cx, cz, af2.getLong(bb)) == 1) {
                    byte[] nat = new byte[256];
                    bb.clear(); bb.get(nat);
                    if (!java.util.Arrays.equals(live, nat)) {
                        bb.clear(); bb.put(live); bb.clear();
                        NativeChunkBridge.setBiomes(dim, cx, cz, af2.getLong(bb));
                        BIOME_REPUSHED.incrementAndGet();
                        // javaPayload built microseconds earlier on this thread; if the
                        // live array moved past it, the PACKET is stale (gen-thread race):
                        byte[] pktTail = new byte[256];
                        System.arraycopy(javaPayload, javaPayload.length - 256, pktTail, 0, 256);
                        if (!java.util.Arrays.equals(live, pktTail)) {
                            BIOME_RACE_SKIPPED.incrementAndGet();
                            return; // never compare torn biomes
                        }
                    }
                }
            } catch (Throwable t) {
                WorldgenShadow.recordCoherencyError("comparatorBiomeFreshness: " + t, t);
            }

            // M5.6 BLOCK-STATE freshness domain: fingerprint the Java logical
            // states of the packet's sections BEFORE refresh/encode and AFTER —
            // a move means the Java state changed across the window (e.g. fluid
            // settle): BLOCK_STATE_STALE_SKIP, never a mismatch.
            final int[][] blockBefore;
            synchronized (CTOR_CAPTURE) {
                int[][] entryCap = CTOR_CAPTURE.get(packet);
                if (entryCap != null) {
                    CAPTURES_MADE.incrementAndGet();
                    blockBefore = entryCap;
                } else {
                    blockBefore = new int[16][];
                    try {
                        Object[] stg = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                        for (int y = 0; y < 16; y++) {
                            if ((javaMask & (1 << y)) == 0) continue;
                            Object st = stg[y];
                            if (st == null) continue;
                            Object cont = st.getClass().getMethod("func_186049_g").invoke(st);
                            blockBefore[y] = new int[4096];
                            if (M4Coherency.fastExtractPublic(cont, blockBefore[y]) == null) blockBefore[y] = null;
                        }
                    } catch (Throwable t) { }
                }
            }

            // M5.8-R2: TE/high-ID chunks may be INSPECTED diagnostically, but any
            // state id above u16 is UNSUPPORTED for native conversion — skip with
            // a dedicated counter BEFORE any comparison against narrowed data.
            if (blockBefore != null) {
                for (int y = 0; y < 16; y++) {
                    int[] ids = blockBefore[y];
                    if (ids == null) continue;
                    for (int i = 0; i < 4096; i++) {
                        if ((ids[i] & 0xFFFF0000) != 0) {
                            UNSUPPORTED_HIGH_ID_SKIP.incrementAndGet();
                            return;
                        }
                    }
                }
            }

            // M5.2 comparator light freshness: same domain check as the transmit
            // path — stale light SKIPS the comparison (race), never mismatches.
            try {
                Object[] storages = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                java.lang.reflect.Method gBL = storages.getClass().getComponentType().getMethod("func_76661_k");
                gBL.setAccessible(true);
                java.lang.reflect.Method gSL = storages.getClass().getComponentType().getMethod("func_76671_l");
                gSL.setAccessible(true);
                java.lang.reflect.Method nib = Class.forName("net.minecraft.world.chunk.NibbleArray").getMethod("func_177481_a");
                nib.setAccessible(true);
                java.nio.ByteBuffer lb = java.nio.ByteBuffer.allocateDirect(4096).order(java.nio.ByteOrder.nativeOrder());
                java.lang.reflect.Field af3 = java.nio.Buffer.class.getDeclaredField("address");
                af3.setAccessible(true);
                long laddr = af3.getLong(lb);
                int nMask = F_MASK.getInt(packet);
                for (int y = 0; y < 16; y++) {
                    if ((nMask & (1 << y)) == 0) continue;
                    Object st = storages[y];
                    if (st == null) continue;
                    byte[] jbl = new byte[2048], jsl = new byte[2048];
                    Object blN = gBL.invoke(st);
                    if (blN != null) System.arraycopy(nib.invoke(blN), 0, jbl, 0, 2048);
                    Object slN = gSL.invoke(st);
                    if (slN != null) System.arraycopy(nib.invoke(slN), 0, jsl, 0, 2048);
                    lb.clear();
                    if (NativeChunkBridge.getSectionLight(dim, cx, cz, (byte) y, laddr) != 1) continue;
                    byte[] nat = new byte[4096];
                    lb.clear(); lb.get(nat);
                    if (!java.util.Arrays.equals(jbl, java.util.Arrays.copyOfRange(nat, 0, 2048))
                            || !java.util.Arrays.equals(jsl, java.util.Arrays.copyOfRange(nat, 2048, 4096))) {
                        // M5.5 stability-window comparator: locked coarse refresh
                        // then RE-CHECK within the same window; if light is now
                        // current the comparison completes; if it churned again,
                        // STALE_SKIP (guard never weakened).
                        Object lock = M4Coherency.refreshLockFor(dim, cx, cz);
                        synchronized (lock) {
                            M4Coherency.refreshOneSectionCoarse(chunk, dim, cx, cz, y, st);
                            lb.clear();
                            if (NativeChunkBridge.getSectionLight(dim, cx, cz, (byte) y, laddr) != 1) {
                                LIGHT_CMP_SKIPPED.incrementAndGet();
                                return;
                            }
                            byte[] nat2 = new byte[4096];
                            lb.clear(); lb.get(nat2);
                            if (!java.util.Arrays.equals(jbl, java.util.Arrays.copyOfRange(nat2, 0, 2048))
                                    || !java.util.Arrays.equals(jsl, java.util.Arrays.copyOfRange(nat2, 2048, 4096))) {
                                LIGHT_CMP_SKIPPED.incrementAndGet();
                                return; // still churning: skip
                            }
                            LIGHT_CMP_LOCKED_COMPLETES.incrementAndGet();
                        }
                    }
                }
            } catch (Throwable t) {
                WorldgenShadow.recordCoherencyError("cmpLightFreshness: " + t, t);
            }

            java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(262144).order(java.nio.ByteOrder.nativeOrder());
            int n = NativeChunkBridge.encodePacket(dim, cx, cz, gen, hasSky, true,
                    addressOf(out), out.capacity());
            if (n == -3) { STALE_ENCODE_REJECTED.incrementAndGet(); return; }
            if (n <= 0) { mismatch("encode=" + n + " dim=" + dim + " cx=" + cx + " cz=" + cz); return; }
            byte[] nativePayload = new byte[n];
            out.clear(); out.get(nativePayload);

            boolean te = hasTileEntities(chunk);
            if (GEN_INFO_BB == null) {
                GEN_INFO_BB = java.nio.ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder());
            }
            GEN_INFO_BB.clear();
            NativeChunkBridge.getGenerationInfo(dim, cx, cz, addressOf(GEN_INFO_BB));
            long g1 = GEN_INFO_BB.getLong(8), g2 = GEN_INFO_BB.getLong(16), g3 = GEN_INFO_BB.getLong(24);
            DUMP_CONTEXT = "run=" + RUN_ID + " dim=" + dim + " cx=" + cx + " cz=" + cz
                    + " filter=" + filter + " javaMask=" + javaMask + " skylight=" + hasSky
                    + " genId=" + gen + " mutGen=" + g1 + " snapGen=" + g2 + " dirtyMask=" + g3
                    + " refreshBefore=" + refreshed + " TE=" + te
                    + " javaLen=" + javaPayload.length;
            COMPARED.incrementAndGet();
            if (te) TE_CHUNKS_COMPARED.incrementAndGet();
            if (java.util.Arrays.equals(javaPayload, nativePayload)) {
                GATE_A_EXACT.incrementAndGet();
            }

            // Gate B: decoded-state equality, MASK-DRIVEN (M4.2D): coverage is
            // compared as the masks themselves; both payloads parse under their
            // own mask with explicit boundaries; parse failure is a mismatch.
            int nativeMask = NativeChunkBridge.getPrimaryBitMask(dim, cx, cz, gen);
            if (nativeMask != javaMask) {
                // M5.7: a mask divergence must pass the SAME stability gate before
                // being declared a mismatch — if the Java logical state moved
                // across the window (e.g. a section emptied mid-capture), the
                // before/after fingerprints differ => STALE_SKIP. Only a STABLE
                // Java state with a different native mask is a real mismatch.
                boolean javaMoved = false;
                try {
                    Object[] stg3 = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                    for (int y = 0; y < 16 && !javaMoved; y++) {
                        if (blockBefore[y] == null) continue;
                        Object st = stg3[y];
                        if (st == null) { javaMoved = true; break; }
                        Object cont = st.getClass().getMethod("func_186049_g").invoke(st);
                        int[] now = new int[4096];
                        if (M4Coherency.fastExtractPublic(cont, now) != null
                                && !java.util.Arrays.equals(now, blockBefore[y])) {
                            javaMoved = true;
                        }
                    }
                } catch (Throwable t) { }
                if (javaMoved) {
                    BLOCK_STATE_STALE_SKIP.incrementAndGet();
                    MASK_STALE_SKIPPED.incrementAndGet();
                    return;
                }
                mismatch("mask differs java=" + javaMask + " native=" + nativeMask
                        + " (java state STABLE across window) dim=" + dim + " cx=" + cx + " cz=" + cz);
                return;
            }
            M4PacketParityHarness.ParsedJ J = M4PacketParityHarness.parseByMask(javaPayload, javaMask, true);
            M4PacketParityHarness.ParsedJ N = M4PacketParityHarness.parseByMask(nativePayload, nativeMask, true);
            if (J == null || N == null) {
                mismatch("payload failed mask-driven parse java=" + (J == null) + " native=" + (N == null)
                        + " mask=" + javaMask + " dim=" + dim + " cx=" + cx + " cz=" + cz);
                return;
            }
            if (ChunkMutationTracker.versionOf(chunk) != cmpVersionBefore) {
                STALE_CMP_SKIPPED.incrementAndGet();
                return; // torn read window: never compare mixed state
            }
            // AFTER fingerprint: accept only when the Java logical state was
            // stable across the entire window (refresh + encode included)
            try {
                Object[] stg2 = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
                for (int y = 0; y < 16; y++) {
                    if (blockBefore[y] == null) continue;
                    Object st = stg2[y];
                    if (st == null) continue;
                    Object cont = st.getClass().getMethod("func_186049_g").invoke(st);
                    int[] now = new int[4096];
                    if (M4Coherency.fastExtractPublic(cont, now) != null
                            && !java.util.Arrays.equals(now, blockBefore[y])) {
                        BLOCK_STATE_STALE_SKIP.incrementAndGet();
                        return;
                    }
                }
            } catch (Throwable t) { }
            String r = M4PacketParityHarness.compareParsed(J, N);
            if (r == null) {
                GATE_B_EQUAL.incrementAndGet();
            } else {
                mismatch("dim=" + dim + " cx=" + cx + " cz=" + cz + " mask=" + javaMask + (te ? " TE" : "") + " -> " + r);
            }
        } catch (Throwable t) {
            // never disturb the packet path — but M4.2C 4 forbids swallowing:
            // record with stack, count, and STOP the comparator on the exception.
            COMPARE_EXCEPTIONS.incrementAndGet();
            DISABLED = true;
            FIRST_MISMATCH = "exception: " + t + " @ctx " + DUMP_CONTEXT;
            LAST_DUMP = FIRST_MISMATCH;
            System.err.println("[M42C-COMPARE-STOP-EX] " + FIRST_MISMATCH);
            WorldgenShadow.recordCoherencyError("packetCompare: " + t, t);
        }
    }

    private static void mismatch(String why) {
        MISMATCHES.incrementAndGet();
        DISABLED = true; // stop condition: first unexplained mismatch stops the comparator
        M4NativeStatePayload.disableAuthoritative("comparator mismatch: " + why);
        if ("none".equals(FIRST_MISMATCH)) FIRST_MISMATCH = why;
        LAST_DUMP = why + " | " + DUMP_CONTEXT;
        System.err.println("[M42C-COMPARE-STOP] " + LAST_DUMP);
    }

    /** Full stop-context captured at every comparison (M4.2C 4: run id, coords,
     *  snapshot generations, packet params). */
    static volatile String DUMP_CONTEXT = "n/a";
    public static volatile String LAST_DUMP = "none";

    private static boolean init(Object chunk) throws Exception {
        if (inited) return true;
        Class<?> pktCls = Class.forName("net.minecraft.network.play.server.SPacketChunkData");
        F_MASK = pktCls.getDeclaredField("field_186948_c");
        F_MASK.setAccessible(true);
        F_PAYLOAD = pktCls.getDeclaredField("field_186949_d");
        F_PAYLOAD.setAccessible(true);
        F_FULL = pktCls.getDeclaredField("field_149279_g");
        F_FULL.setAccessible(true);
        Class<?> cCls = chunk.getClass();
        M_HAS_TILE_MAP = cCls.getMethod("func_177434_r");
        M_HAS_TILE_MAP.setAccessible(true);
        inited = true;
        return true;
    }

    private static Field CH_X, CH_Z, CH_WORLD, W_PROV, PROV_SKY;

    private static int cx(Object chunk) throws Exception {
        if (CH_X == null) { CH_X = findField(chunk.getClass(), "field_76635_g"); CH_X.setAccessible(true); }
        return CH_X.getInt(chunk);
    }

    private static int cz(Object chunk) throws Exception {
        if (CH_Z == null) { CH_Z = findField(chunk.getClass(), "field_76647_h"); CH_Z.setAccessible(true); }
        return CH_Z.getInt(chunk);
    }

    private static long chunkKey(Object chunk) throws Exception {
        return ((long) cx(chunk) << 32) | (cz(chunk) & 0xFFFFFFFFL);
    }

    private static int dimOf(Object chunk) throws Exception {
        if (CH_WORLD == null) {
            CH_WORLD = findField(chunk.getClass(), "field_76637_e");
            CH_WORLD.setAccessible(true);
        }
        Object world = CH_WORLD.get(chunk);
        if (world == null) return 0;
        if (W_PROV == null) {
            W_PROV = findField(world.getClass(), "field_73011_w");
            W_PROV.setAccessible(true);
        }
        Object prov = W_PROV.get(world);
        try {
            Method m = prov.getClass().getMethod("getDimension");
            m.setAccessible(true);
            return (Integer) m.invoke(prov);
        } catch (Throwable ignore) { }
        try {
            Method m = prov.getClass().getMethod("func_186058_p");
            m.setAccessible(true);
            Object dt = m.invoke(prov);
            Method mId = dt.getClass().getMethod("func_186068_a");
            mId.setAccessible(true);
            return (Integer) mId.invoke(dt);
        } catch (Throwable ignore) { }
        return 0;
    }

    private static boolean skylightOf(Object chunk) throws Exception {
        Object world = CH_WORLD.get(chunk);
        Object prov = W_PROV.get(world);
        if (PROV_SKY == null) {
            Class<?> c = prov.getClass();
            while (c != null) {
                try { PROV_SKY = c.getDeclaredField("field_191067_f"); break; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            if (PROV_SKY != null) PROV_SKY.setAccessible(true);
        }
        return PROV_SKY != null && PROV_SKY.getBoolean(prov);
    }

    private static boolean hasTileEntities(Object chunk) throws Exception {
        Object m = M_HAS_TILE_MAP.invoke(chunk);
        return m instanceof java.util.Map && !((java.util.Map<?, ?>) m).isEmpty();
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static long addressOf(java.nio.ByteBuffer b) throws Exception {
        if (AF == null) { AF = java.nio.Buffer.class.getDeclaredField("address"); AF.setAccessible(true); }
        return AF.getLong(b);
    }
    private static Field AF;


    // ==================================================================
    // M4.2E deferred comparison. Java packet sends immediately; when the
    // chunk is not yet native-synced we capture a bounded record and compare
    // in the background once sync completes. Never blocks, never resends.
    // ==================================================================

    static final class DeferredRecord {
        final int dim, cx, cz, javaMask, filter, packetVersion;
        final boolean sky;
        final byte[] payload;
        final long nativeGenAtCapture;
        final long capturedAtMs;
        final java.lang.ref.WeakReference<Object> chunk;
        DeferredRecord(int dim, int cx, int cz, int javaMask, int filter, boolean sky,
                       byte[] payload, long nativeGenAtCapture, int packetVersion, Object chunk) {
            this.dim = dim; this.cx = cx; this.cz = cz; this.javaMask = javaMask;
            this.filter = filter; this.sky = sky; this.payload = payload;
            this.nativeGenAtCapture = nativeGenAtCapture; this.packetVersion = packetVersion;
            this.capturedAtMs = System.currentTimeMillis();
            this.chunk = new java.lang.ref.WeakReference<>(chunk);
        }
    }

    static volatile int MAX_PENDING_RECORDS = 1024;
    static volatile long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    static volatile int MAX_RECORD_BYTES = 131072;
    static volatile long EXPIRY_MS = 15000;

    /** test hook (offline suites only) */
    static void setLimitsForTest(int records, long bytes, int perRecord, long expiryMs) {
        MAX_PENDING_RECORDS = records; MAX_TOTAL_BYTES = bytes;
        MAX_RECORD_BYTES = perRecord; EXPIRY_MS = expiryMs;
    }

    static final java.util.ArrayDeque<DeferredRecord> DEFERRED = new java.util.ArrayDeque<>();
    static long deferredBytes = 0;
    static volatile long deferredBytesPeak = 0;

    public static final AtomicLong DEFERRED_ENQUEUED = new AtomicLong();
    public static final AtomicLong DEFERRED_COMPLETED = new AtomicLong();
    public static final AtomicLong DEFERRED_STALE = new AtomicLong();
    public static final AtomicLong DEFERRED_EXPIRED = new AtomicLong();
    public static final AtomicLong DEFERRED_CAPACITY_SKIPPED = new AtomicLong();
    public static final AtomicLong DEFERRED_BYTES_PEAK = new AtomicLong();
    public static final AtomicLong COMPARE_IMMEDIATE = new AtomicLong();
    public static final AtomicLong COMPARE_DEFERRED = new AtomicLong();
    public static final AtomicLong MATCHES_SEMANTIC = new AtomicLong();
    public static final AtomicLong STALE_CMP_SKIPPED = new AtomicLong();
    public static final AtomicLong LIGHT_CMP_SKIPPED = new AtomicLong();
    public static final AtomicLong BLOCK_STATE_STALE_SKIP = new AtomicLong();
    public static final AtomicLong MASK_STALE_SKIPPED = new AtomicLong();
    public static final AtomicLong UNSUPPORTED_HIGH_ID_SKIP = new AtomicLong();
    public static final AtomicLong LIGHT_CMP_LOCKED_COMPLETES = new AtomicLong();
    /** version captured at constructor ENTRY (populatePacket) — validates the
     *  entire construction window, not just the return-hook window. */
    static final java.util.WeakHashMap<Object, Integer> CTOR_VERSION = new java.util.WeakHashMap<>();
    public static final AtomicLong BIOME_REPUSHED = new AtomicLong();
    public static final AtomicLong BIOME_RACE_SKIPPED = new AtomicLong();
    public static final AtomicLong STALE_HANDLE_REJECTIONS = new AtomicLong();

    static final AtomicLong T_ENCODE_NS = new AtomicLong();
    static final AtomicLong T_ENCODE_N = new AtomicLong();
    static final AtomicLong T_COMPARE_NS = new AtomicLong();
    static final AtomicLong T_COMPARE_N = new AtomicLong();
    static final AtomicLong T_QUEUEWAIT_MS = new AtomicLong();
    static final AtomicLong T_QUEUEWAIT_N = new AtomicLong();

    static void captureDeferred(int dim, int cx, int cz, int javaMask, int filter, boolean sky,
                                byte[] payload, long nativeGen, Object chunk) {
        if (payload == null || payload.length > MAX_RECORD_BYTES) {
            DEFERRED_CAPACITY_SKIPPED.incrementAndGet();
            return;
        }
        synchronized (DEFERRED) {
            if (DEFERRED.size() >= MAX_PENDING_RECORDS || deferredBytes + payload.length > MAX_TOTAL_BYTES) {
                DEFERRED_CAPACITY_SKIPPED.incrementAndGet();
                return;
            }
            DEFERRED.add(new DeferredRecord(dim, cx, cz, javaMask, filter, sky, payload,
                    nativeGen, ChunkMutationTracker.versionOf(chunk), chunk));
            deferredBytes += payload.length;
            if (deferredBytes > deferredBytesPeak) {
                deferredBytesPeak = deferredBytes;
                DEFERRED_BYTES_PEAK.set(deferredBytes);
            }
        }
        DEFERRED_ENQUEUED.incrementAndGet();
        M4Coherency.requestSync(chunk);
    }

    /** Background processing (flush thread). Bounded per cycle. */
    public static void processDeferred(int maxPerCycle) {
        long now = System.currentTimeMillis();
        java.util.List<DeferredRecord> keep = new java.util.ArrayList<>();
        int processed = 0;
        DeferredRecord r;
        while (processed < maxPerCycle) {
            synchronized (DEFERRED) {
                r = DEFERRED.pollFirst();
                if (r == null) break;
                deferredBytes -= r.payload.length;
            }
            processed++;
            if (now - r.capturedAtMs > EXPIRY_MS) {
                DEFERRED_EXPIRED.incrementAndGet();
                continue;
            }
            Object chunk = r.chunk.get();
            if (chunk == null || !M4Coherency.isFullySynced(chunk)) {
                keep.add(r);
                continue;
            }
            if (ChunkMutationTracker.versionOf(chunk) != r.packetVersion) {
                DEFERRED_STALE.incrementAndGet();
                continue;
            }
            long gen = NativeChunkBridge.findGeneration(r.dim, r.cx, r.cz);
            if (gen <= 0 || gen != r.nativeGenAtCapture) {
                STALE_HANDLE_REJECTIONS.incrementAndGet();
                continue;
            }
            compareCaptured(r, gen);
        }
        if (!keep.isEmpty()) {
            synchronized (DEFERRED) {
                for (DeferredRecord k : keep) {
                    DEFERRED.addLast(k);
                    deferredBytes += k.payload.length;
                }
            }
        }
    }

    static final java.nio.ByteBuffer DEFERRED_OUT = java.nio.ByteBuffer.allocateDirect(262144)
            .order(java.nio.ByteOrder.nativeOrder());

    /** Exact-byte comparison of a deferred record; semantic equality is the
     *  documented fallback for valid palette-history divergence (Gate B). */
    static void compareCaptured(DeferredRecord r, long gen) {
        try {
            long t0 = System.nanoTime();
            DEFERRED_OUT.clear();
            int n = NativeChunkBridge.encodePacket(r.dim, r.cx, r.cz, gen, r.sky, true,
                    addressOf(DEFERRED_OUT), DEFERRED_OUT.capacity());
            T_ENCODE_NS.addAndGet(System.nanoTime() - t0); T_ENCODE_N.incrementAndGet();
            if (n == -3) { STALE_ENCODE_REJECTED.incrementAndGet(); return; }
            if (n <= 0) { mismatch("deferred encode=" + n + " dim=" + r.dim + " cx=" + r.cx + " cz=" + r.cz); return; }
            byte[] nat = new byte[n];
            DEFERRED_OUT.clear(); DEFERRED_OUT.get(nat);

            long t1 = System.nanoTime();
            boolean exact = java.util.Arrays.equals(r.payload, nat);
            String verdict = null;
            if (!exact) {
                M4PacketParityHarness.ParsedJ J = M4PacketParityHarness.parseByMask(r.payload, r.javaMask, true);
                M4PacketParityHarness.ParsedJ N = M4PacketParityHarness.parseByMask(nat, r.javaMask, true);
                verdict = (J == null || N == null) ? "parse failure"
                        : M4PacketParityHarness.compareParsed(J, N);
            }
            T_COMPARE_NS.addAndGet(System.nanoTime() - t1); T_COMPARE_N.incrementAndGet();
            T_QUEUEWAIT_MS.addAndGet(System.currentTimeMillis() - r.capturedAtMs);
            T_QUEUEWAIT_N.incrementAndGet();

            if (exact) {
                GATE_A_EXACT.incrementAndGet();
                GATE_B_EQUAL.incrementAndGet();
                COMPARE_DEFERRED.incrementAndGet();
                COMPARED.incrementAndGet();
                DEFERRED_COMPLETED.incrementAndGet();
            } else if (verdict == null) {
                MATCHES_SEMANTIC.incrementAndGet();
                GATE_B_EQUAL.incrementAndGet();
                COMPARE_DEFERRED.incrementAndGet();
                COMPARED.incrementAndGet();
                DEFERRED_COMPLETED.incrementAndGet();
            } else {
                preserveMismatch(r, nat, verdict);
                mismatch("deferred semantic mismatch " + verdict + " dim=" + r.dim
                        + " cx=" + r.cx + " cz=" + r.cz + " mask=" + r.javaMask);
            }
        } catch (Throwable t) {
            COMPARE_EXCEPTIONS.incrementAndGet();
            DISABLED = true;
            FIRST_MISMATCH = "deferred exception: " + t;
            WorldgenShadow.recordCoherencyError("deferredCompare: " + t, t);
        }
    }

    /** Preserve everything required on first unexplained mismatch. */
    static void preserveMismatch(DeferredRecord r, byte[] nat, String semanticReason) {
        try {
            int off = -1; byte jb = 0, nb = 0;
            for (int i = 0; i < Math.min(r.payload.length, nat.length); i++) {
                if (r.payload[i] != nat[i]) { off = i; jb = r.payload[i]; nb = nat[i]; break; }
            }
            StringBuilder dump = new StringBuilder();
            dump.append("run=").append(RUN_ID)
                .append(" dim=").append(r.dim).append(" cx=").append(r.cx).append(" cz=").append(r.cz)
                .append(" mask=").append(r.javaMask).append(" filter=").append(r.filter)
                .append(" sky=").append(r.sky)
                .append(" packetVersion=").append(r.packetVersion)
                .append(" nativeGen=").append(r.nativeGenAtCapture)
                .append(" semantic=").append(semanticReason)
                .append(" firstDiffOffset=").append(off)
                .append(" javaByte=").append(String.format("%02x", jb))
                .append(" rustByte=").append(String.format("%02x", nb))
                .append(" javaLen=").append(r.payload.length)
                .append(" rustLen=").append(nat.length);
            LAST_DUMP = dump.toString();
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter("m42e-mismatch.txt", true));
            pw.println(dump);
            pw.flush(); pw.close();
            java.io.FileOutputStream fo = new java.io.FileOutputStream("m42e-mismatch-java.bin", true);
            fo.write(r.payload); fo.close();
            java.io.FileOutputStream fn = new java.io.FileOutputStream("m42e-mismatch-rust.bin", true);
            fn.write(nat); fn.close();
        } catch (Throwable ignore) { }
    }


    // ==================================================================
    // M4.3 — sampled wire verification of AUTHORITATIVE native payloads.
    // The transmitted payload (rust) is captured on the send path; a Java
    // reference packet is built LATER on the flush thread and both are decoded
    // and compared semantically. Never blocks the send; stale temporal
    // snapshots SKIP.
    // ==================================================================

    static final class AuthSample {
        final int dim, cx, cz, version, javaMask;
        final byte[] javaRef;      // real Java-serializer payload (built when QUIESCENT)
        final byte[] rustAuth;     // produced by NativeChunk (authoritative payload)
        final long gen;
        final long capturedAtMs;
        final java.lang.ref.WeakReference<Object> chunk; // present while awaiting reference
        AuthSample(int dim, int cx, int cz, int version, int javaMask,
                   byte[] javaRef, byte[] rustAuth, long gen) {
            this(dim, cx, cz, version, javaMask, javaRef, rustAuth, gen, null);
        }
        AuthSample(int dim, int cx, int cz, int version, int javaMask,
                   byte[] javaRef, byte[] rustAuth, long gen, Object chunkRef) {
            this.dim = dim; this.cx = cx; this.cz = cz; this.version = version;
            this.javaMask = javaMask; this.javaRef = javaRef; this.rustAuth = rustAuth;
            this.gen = gen;
            this.capturedAtMs = System.currentTimeMillis();
            this.chunk = new java.lang.ref.WeakReference<>(chunkRef);
        }
    }

    static final java.util.ArrayDeque<AuthSample> AUTH_SAMPLES = new java.util.ArrayDeque<>();
    static long authBytes = 0;
    static final int AUTH_MAX_RECORDS = 256;
    static final long AUTH_MAX_BYTES = 32L * 1024 * 1024;
    static final long AUTH_EXPIRY_MS = 60000;

    public static final AtomicLong AUTH_VERIFY_ATTEMPTED = new AtomicLong();
    public static final AtomicLong AUTH_VERIFY_COMPLETED = new AtomicLong();
    public static final AtomicLong AUTH_VERIFY_MATCHES = new AtomicLong();
    public static final AtomicLong AUTH_VERIFY_MISMATCHES = new AtomicLong();
    public static final AtomicLong AUTH_VERIFY_STALE_SKIPS = new AtomicLong();
    public static final AtomicLong AUTH_VERIFY_ERRORS = new AtomicLong();

    static final java.util.concurrent.ConcurrentHashMap<Long, AuthSample> PENDING_AUTH =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Record a native-authoritative payload awaiting its Java reference. The
     *  reference is built LATER, off-thread, only once the chunk is QUIESCENT
     *  (version unchanged AND zero pending tracker work): mid-population Java
     *  packet construction hits transient null light arrays vanilla itself
     *  never serializes (the sited nested-ctor NPE root cause). */
    static void recordPendingAuthSample(int dim, int cx, int cz, int version, byte[] rust, long gen) {
        // overload with chunk ref used by the live path
    }

    static void recordPendingAuthSample(Object chunk, int dim, int cx, int cz,
                                        int version, byte[] rust, long gen) {
        if (PENDING_AUTH.size() > 128) return; // bounded
        long key = ((long) dim << 44) ^ ((long) cx << 22) ^ cz;
        PENDING_AUTH.put(key, new AuthSample(dim, cx, cz, version, 0, null, rust, gen, chunk));
    }

    /** Try to complete pending samples: for each, if the chunk is QUIESCENT,
     *  build the Java reference off-thread (safe on stable state) and verify. */
    public static void processAuthSamples(int maxPerCycle) {
        long now = System.currentTimeMillis();
        int processed = 0;
        // 1) attempt QUIESCENT reference builds for pending samples
        if (!PENDING_AUTH.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<Long, AuthSample>> it = PENDING_AUTH.entrySet().iterator();
            while (it.hasNext() && processed < maxPerCycle) {
                AuthSample pending = it.next().getValue();
                processed++;
                Object chunk = pending.chunk.get();
                boolean remove = false;
                if (chunk == null || now - pending.capturedAtMs > AUTH_EXPIRY_MS) {
                    remove = true;
                    AUTH_VERIFY_STALE_SKIPS.incrementAndGet();
                } else if (ChunkMutationTracker.versionOf(chunk) != pending.version) {
                    remove = true;
                    AUTH_VERIFY_STALE_SKIPS.incrementAndGet();
                } else {
                    int[] work = ChunkMutationTracker.peekWork(chunk);
                    boolean quiescent = work == null
                            || ((work[1] | work[2] | work[3] | work[4]) == 0);
                    if (quiescent && M4Coherency.isFullySynced(chunk)) {
                        try {
                            Object ref = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
                            byte[] javaRef = (byte[]) M4PacketParityHarness.pktPayloadField().get(ref);
                            int javaMask = M4PacketParityHarness.pktMaskField().getInt(ref);
                            synchronized (AUTH_SAMPLES) {
                                if (AUTH_SAMPLES.size() < AUTH_MAX_RECORDS) {
                                    AUTH_SAMPLES.add(new AuthSample(pending.dim, pending.cx, pending.cz,
                                            pending.version, javaMask, javaRef, pending.rustAuth, pending.gen));
                                    authBytes += javaRef.length + pending.rustAuth.length;
                                }
                            }
                            remove = true;
                        } catch (Throwable t) {
                            // not quiescent enough after all (transient null mid-read):
                            // leave pending; counted only if it persists past expiry
                            AUTH_VERIFY_ERRORS.incrementAndGet();
                            WorldgenShadow.recordCoherencyError("quiescentRef: " + t, t);
                            remove = now - pending.capturedAtMs > 1000; // give it ~1s of retries
                        }
                    }
                }
                if (remove) it.remove();
            }
        }
        // 2) verify completed pairs by independent decode
        int verified = 0;
        AuthSample a;
        while (verified < maxPerCycle) {
            synchronized (AUTH_SAMPLES) {
                a = AUTH_SAMPLES.pollFirst();
                if (a == null) break;
                authBytes -= a.javaRef.length + a.rustAuth.length;
            }
            verified++;
            AUTH_VERIFY_ATTEMPTED.incrementAndGet();
            try {
                int rustMask = NativeChunkBridge.getPrimaryBitMask(a.dim, a.cx, a.cz, a.gen);
                String reason;
                if (rustMask != a.javaMask) {
                    reason = "mask java=" + a.javaMask + " rust=" + rustMask;
                } else {
                    M4PacketParityHarness.ParsedJ J =
                            M4PacketParityHarness.parseByMask(a.javaRef, a.javaMask, true);
                    M4PacketParityHarness.ParsedJ N =
                            M4PacketParityHarness.parseByMask(a.rustAuth, a.javaMask, true);
                    reason = (J == null || N == null) ? "parse failure"
                            : M4PacketParityHarness.compareParsed(J, N);
                }
                AUTH_VERIFY_COMPLETED.incrementAndGet();
                if (reason == null) {
                    AUTH_VERIFY_MATCHES.incrementAndGet();
                } else {
                    AUTH_VERIFY_MISMATCHES.incrementAndGet();
                    M4NativeStatePayload.disableAuthoritative("independent verify mismatch " + reason
                            + " dim=" + a.dim + " cx=" + a.cx + " cz=" + a.cz);
                    mismatch("M43B-VERIFY " + reason + " dim=" + a.dim + " cx=" + a.cx + " cz=" + a.cz);
                }
            } catch (Throwable t) {
                AUTH_VERIFY_ERRORS.incrementAndGet();
                WorldgenShadow.recordCoherencyError("verifySample: " + t, t);
            }
        }
    }


    // ==================================================================
    // M4.3D — quiescent verification sweep (independent live verification).
    // For a handful of FULLY_SYNCED chunks per flush cycle: if the mutation
    // version is IDENTICAL before and after building the real Java reference
    // packet (normal construction path), the chunk was quiescent during the
    // build — compare that reference against the NativeChunk encode by
    // independent decode. Version moved => SKIP (never compare torn state).
    // ==================================================================
    public static final AtomicLong SWEEP_ATTEMPTED = new AtomicLong();
    public static final AtomicLong SWEEP_COMPLETED = new AtomicLong();
    public static final AtomicLong SWEEP_MATCHES = new AtomicLong();
    public static final AtomicLong SWEEP_MISMATCHES = new AtomicLong();
    public static final AtomicLong SWEEP_VERSION_MOVED = new AtomicLong();
    public static final AtomicLong SWEEP_NOT_READY = new AtomicLong();
    public static final AtomicLong SWEEP_GATE_A_EXACT = new AtomicLong();
    private static long sweepCursor = 0;

    public static void quiescentSweep(int maxPerCycle) {
        try {
            java.util.List<Object> keys = M4Coherency.syncedChunkSnapshot();
            if (keys.isEmpty()) return;
            for (int n = 0; n < maxPerCycle; n++) {
                sweepCursor = (sweepCursor + 1) % keys.size();
                Object chunk = keys.get((int) sweepCursor);
                if (chunk == null) continue;
                if (!M4Coherency.isFullySynced(chunk)) { SWEEP_NOT_READY.incrementAndGet(); continue; }
                int v1 = ChunkMutationTracker.versionOf(chunk);
                if (v1 != M4Coherency.lastSyncedVersion(chunk)) { SWEEP_NOT_READY.incrementAndGet(); continue; }
                SWEEP_ATTEMPTED.incrementAndGet();
                try {
                    long t0 = System.nanoTime();
                    Object ref = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
                    byte[] javaRef = (byte[]) M4PacketParityHarness.pktPayloadField().get(ref);
                    int javaMask = M4PacketParityHarness.pktMaskField().getInt(ref);
                    if (ChunkMutationTracker.versionOf(chunk) != v1) {
                        SWEEP_VERSION_MOVED.incrementAndGet(); // torn window: SKIP
                        continue;
                    }
                    long[] k = ChunkMutationTracker.chunkCoords(chunk);
                    int dim = ChunkMutationTracker.dimOf(chunk);
                    long gen = NativeChunkBridge.findGeneration(dim, (int) k[0], (int) k[1]);
                    if (gen <= 0) { SWEEP_NOT_READY.incrementAndGet(); continue; }
                    boolean sky = M4Coherency.chunkSkylight(chunk);
                    java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(262144)
                            .order(java.nio.ByteOrder.nativeOrder());
                    java.lang.reflect.Field af = java.nio.Buffer.class.getDeclaredField("address");
                    af.setAccessible(true);
                    int nBytes = NativeChunkBridge.encodePacket(dim, (int) k[0], (int) k[1], gen, sky, true,
                            af.getLong(out), out.capacity());
                    if (nBytes == -3) { SWEEP_VERSION_MOVED.incrementAndGet(); continue; }
                    if (nBytes <= 0) { SWEEP_MISMATCHES.incrementAndGet();
                        mismatch("sweep encode=" + nBytes + " dim=" + dim + " cx=" + k[0] + " cz=" + k[1]); return; }
                    byte[] rust = new byte[nBytes];
                    out.clear(); out.get(rust);
                    if (ChunkMutationTracker.versionOf(chunk) != v1) {
                        SWEEP_VERSION_MOVED.incrementAndGet();
                        continue;
                    }
                    T_SWEEP_NS.addAndGet(System.nanoTime() - t0);
                    SWEEP_COMPLETED.incrementAndGet();
                    if (java.util.Arrays.equals(javaRef, rust)) SWEEP_GATE_A_EXACT.incrementAndGet();
                    int rustMask = NativeChunkBridge.getPrimaryBitMask(dim, (int) k[0], (int) k[1], gen);
                    String reason;
                    if (rustMask != javaMask) {
                        reason = "mask java=" + javaMask + " rust=" + rustMask;
                    } else {
                        M4PacketParityHarness.ParsedJ J = M4PacketParityHarness.parseByMask(javaRef, javaMask, true);
                        M4PacketParityHarness.ParsedJ N = M4PacketParityHarness.parseByMask(rust, javaMask, true);
                        reason = (J == null || N == null) ? "parse failure"
                                : M4PacketParityHarness.compareParsed(J, N);
                    }
                    if (reason == null) {
                        SWEEP_MATCHES.incrementAndGet();
                    } else {
                        SWEEP_MISMATCHES.incrementAndGet();
                        mismatch("sweep semantic " + reason + " dim=" + dim + " cx=" + k[0] + " cz=" + k[1]
                                + " mask=" + javaMask + " v=" + v1);
                        return; // stop condition
                    }
                } catch (Throwable t) {
                    SWEEP_VERSION_MOVED.incrementAndGet(); // off-thread ctor on non-quiescent chunk
                    WorldgenShadow.recordCoherencyError("sweep: " + t, t);
                }
            }
        } catch (Throwable t) {
            WorldgenShadow.recordCoherencyError("sweepOuter: " + t, t);
        }
    }

    static final AtomicLong T_SWEEP_NS = new AtomicLong();
    /** M5.3: defunct-path canary — the capture site is disabled, so this must
     *  always read 0; kept so tests can assert the retirement took hold. */
    public static long SAMPLES_ENQUEUED_DEFUNCT() { return AUTH_VERIFY_ATTEMPTED.get() - AUTH_VERIFY_COMPLETED.get()
                - AUTH_VERIFY_STALE_SKIPS.get() - AUTH_VERIFY_ERRORS.get() - AUTH_VERIFY_MISMATCHES.get(); }

    /** M5.7 TEST-ONLY deterministic resend action: constructs a REAL
     *  SPacketChunkData for a ready chunk through the normal constructor (the
     *  same path PlayerChunkMap uses), driving the transformer hooks and the
     *  comparator. Offline-verifiable; LIVE wiring (finding a watching player
     *  and their network context) is deliberately PENDING — this does NOT
     *  send anything, it only exercises construction. */
    public static int testResendConstruct(Object chunk) {
        try {
            Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
            return pkt != null ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String statsLine() {
        return " m42c_packets_seen=" + PACKETS_SEEN.get()
                + " m42c_compared=" + COMPARED.get()
                + " m42c_gate_a_exact=" + GATE_A_EXACT.get()
                + " m42c_gate_b_equal=" + GATE_B_EQUAL.get()
                + " m42c_te_chunks_compared=" + TE_CHUNKS_COMPARED.get()
                + " m42c_full_resend_post_mutation=" + FULL_RESEND_POST_MUTATION.get()
                + " m42c_partial_filter_skipped=" + PARTIAL_FILTER_SKIPPED.get()
                + " m42c_not_registered_skipped=" + NOT_REGISTERED_SKIPPED.get()
                + " m42c_unsynced_skipped=" + UNSYNCED_SKIPPED.get()
                + " m42c_refresh_before_compare=" + REFRESH_BEFORE_COMPARE.get()
                + " m42c_refresh_fail_skipped=" + REFRESH_FAIL_SKIPPED.get()
                + " m42c_stale_encode_rejected=" + STALE_ENCODE_REJECTED.get()
                + " m42c_mismatches=" + MISMATCHES.get()
                + " m42c_disabled=" + DISABLED
                + " m42c_first_mismatch=" + FIRST_MISMATCH
                + " m42c_compare_exceptions=" + COMPARE_EXCEPTIONS.get()
                + " m42c_compare_immediate=" + COMPARE_IMMEDIATE.get()
                + " m42c_compare_deferred=" + COMPARE_DEFERRED.get()
                + " m42c_matches_semantic=" + MATCHES_SEMANTIC.get()
                + " m42c_deferred_enqueued=" + DEFERRED_ENQUEUED.get()
                + " m42c_deferred_completed=" + DEFERRED_COMPLETED.get()
                + " m42c_deferred_stale=" + DEFERRED_STALE.get()
                + " m42c_deferred_expired=" + DEFERRED_EXPIRED.get()
                + " m42c_deferred_capacity_skipped=" + DEFERRED_CAPACITY_SKIPPED.get()
                + " m42c_deferred_bytes_peak=" + DEFERRED_BYTES_PEAK.get()
                + " m42c_deferred_pending=" + (DEFERRED.size())
                + " m42c_stale_handle_rejections=" + STALE_HANDLE_REJECTIONS.get()
                + " m42c_t_encode_ns=" + T_ENCODE_NS.get() + " n=" + T_ENCODE_N.get()
                + " m42c_t_compare_ns=" + T_COMPARE_NS.get() + " n=" + T_COMPARE_N.get()
                + " m42c_t_queuewait_ms=" + T_QUEUEWAIT_MS.get() + " n=" + T_QUEUEWAIT_N.get()
                + " m43_auth_verify_attempted=" + AUTH_VERIFY_ATTEMPTED.get()
                + " m43_auth_verify_completed=" + AUTH_VERIFY_COMPLETED.get()
                + " m43_auth_verify_matches=" + AUTH_VERIFY_MATCHES.get()
                + " m43_auth_verify_mismatches=" + AUTH_VERIFY_MISMATCHES.get()
                + " m43_auth_verify_stale_skips=" + AUTH_VERIFY_STALE_SKIPS.get()
                + " m43_auth_verify_errors=" + AUTH_VERIFY_ERRORS.get()
                + " m43d_sweep_attempted=" + SWEEP_ATTEMPTED.get()
                + " m43d_sweep_completed=" + SWEEP_COMPLETED.get()
                + " m43d_sweep_matches=" + SWEEP_MATCHES.get()
                + " m43d_sweep_mismatches=" + SWEEP_MISMATCHES.get()
                + " m43d_sweep_version_moved=" + SWEEP_VERSION_MOVED.get()
                + " m43d_sweep_not_ready=" + SWEEP_NOT_READY.get()
                + " m43d_sweep_gate_a=" + SWEEP_GATE_A_EXACT.get()
                + " m43e_biome_repushed=" + BIOME_REPUSHED.get()
                + " m43e_biome_race_skipped=" + BIOME_RACE_SKIPPED.get()
                + " m51_cmp_stale_skipped=" + STALE_CMP_SKIPPED.get()
                + " m52_cmp_light_skipped=" + LIGHT_CMP_SKIPPED.get()
                + " m55_cmp_light_locked_completes=" + LIGHT_CMP_LOCKED_COMPLETES.get()
                + " m56_cmp_block_stale_skipped=" + BLOCK_STATE_STALE_SKIP.get()
                + " m57_cmp_mask_stale_skipped=" + MASK_STALE_SKIPPED.get()
                + " m57_ctor_entry_seen=" + CTOR_ENTRY_SEEN.get()
                + " m58r2_unsupported_high_id_skipped=" + UNSUPPORTED_HIGH_ID_SKIP.get()
                + " m57_full_ctor_seen=" + FULL_CTOR_SEEN.get()
                + " m58_captures_used=" + CAPTURES_MADE.get()
                + " m58_resend_accepted=" + M5ResendDriver.ACCEPTED.get()
                + " m58_resend_executed=" + M5ResendDriver.EXECUTED.get()
                + " m58_resend_sent=" + M5ResendDriver.SENT.get()
                + " m58_resend_rejected=" + M5ResendDriver.REJECTED_NO_CONTEXT.get()
                + " m42c_last_dump=" + LAST_DUMP;
    }
}
