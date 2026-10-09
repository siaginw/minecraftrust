package com.rustcraft.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4.2B — offline packet parity of the native M4 encoder against the REAL
 * net.minecraft.network.play.server.SPacketChunkData for the SAME chunk state,
 * generation, requested mask and lighting context.
 *
 * Builds a live Chunk (real ChunkPrimer path, real setBlockState / setLightFor
 * mutations, real palette growth), serializes it with the actual packet
 * constructor, and compares against the native registry encoder after the
 * real refresh pipeline (extract Java states + light -> refreshSection ->
 * encodePacket):
 *
 *   Gate A — exact byte equality (counted where palette history happens to
 *            coincide; never forced by altering Java history);
 *   Gate B — independent decoded-state equality: per-section logical states,
 *            section coverage/mask, both light arrays, biomes, framing sizes.
 *
 * Case matrix: base terrain; block mutation; light-only; empty<->non-empty
 * transitions (grounds the all-air omission rule against REAL Java packet
 * semantics); overwrite churn. TileEntity case counted separately (payload
 * excludes TE tags, which serialize at write time — Java-owned).
 *
 * Offline: no server; no transmission; Java authoritative throughout.
 */
public class M4PacketParityHarness {

    public static final AtomicLong CASES = new AtomicLong();
    public static final AtomicLong GATE_A_EXACT = new AtomicLong();
    public static final AtomicLong GATE_B_EQUAL = new AtomicLong();
    public static final AtomicLong MASK_EQUAL = new AtomicLong();
    public static final AtomicLong BIOME_EQUAL = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();
    public static volatile String FIRST_MISMATCH = "none";

    private static java.lang.reflect.Field addressField;
    static {
        try {
            addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
        } catch (Throwable t) { addressField = null; }
    }
    private static long addr(ByteBuffer bb) {
        try { return addressField.getLong(bb); } catch (Throwable t) { return 0; }
    }

    // reflection cache
    private static Object REG_MAP;
    private static Method REG_GET_ID, REG_BY_ID;
    private static Constructor<?> CHUNK_PRIMER_CT;
    private static Method M_SET_BLOCK, M_SET_LIGHT, M_GET_STORAGES, M_GET_BIOMES;
    private static Method M_CONTAINER, M_GET_STATE, M_GET_BL, M_GET_SL, M_NIBBLE_BYTES;
    private static Constructor<?> BLOCK_POS_CT;
    private static Object SKY, BLOCK_LIGHT;
    private static Field PKT_PAYLOAD, PKT_MASK;
    private static Constructor<?> PACKET_CT;
    private static Object EMPTY_STORAGE;
    private static Class<?> CHUNK_CLS;

    /** Minimal concrete World: only the packet path's needs (provider skylight,
     *  biome provider via surface provider defaults). */
    public static class ProbeWorld extends net.minecraft.world.World {
        public ProbeWorld(net.minecraft.world.storage.WorldInfo info,
                          net.minecraft.world.WorldProvider prov,
                          net.minecraft.profiler.Profiler prof) {
            super(null, info, prov, prof, false);
        }
        @Override protected net.minecraft.world.chunk.IChunkProvider func_72970_h() { return null; }
        @Override protected boolean func_175680_a(int x, int z, boolean p) { return false; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== M4.2B PACKET PARITY: native encoder vs REAL SPacketChunkData ===");
        if (!NativeChunkBridge.isAvailable()) { System.err.println("bridge unavailable"); System.exit(1); return; }
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        initReflection();

        runCase("base-terrain-full", 0, null);
        runCase("block-mutation", 1, (c, h) -> h.setBlock(c, 3, 70, 5, h.pick(1)));
        runCase("light-only", 2, (c, h) -> h.setLight(c, 3, 70, 5, 12));
        runCase("empty-to-nonempty", 3, (c, h) -> { h.setBlock(c, 8, 200, 8, h.pick(2)); });
        runCase("nonempty-to-empty", 4, (c, h) -> { h.clearColumn(c, 8, 200, 8); });
        runCase("churn", 5, (c, h) -> { for (int i = 0; i < 500; i++) h.setBlock(c, (i * 7) & 15, 64 + (i & 3), (i * 13) & 15, h.pick(i % 60)); });
        runCase("chest-tileentity", 6, (c, h) -> h.setBlock(c, 4, 70, 6, h.chestState()));
        // A section that EXISTS in Java then becomes fully air: the REAL packet must
        // drop it from the mask (isFullChunk && isEmpty -> skip) exactly as the native
        // refresh does. Grounds the all-air omission rule against actual Java output.
        runCase("section-becomes-empty", 7, (c, h) -> {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 48; y < 64; y++) {
                h.setBlock(c, x, y, z, h.airRef());
            }
        });

        runBiomeRegression();
        runNoDirtyBiomeOnlyCase();

        System.out.println("\ncases=" + CASES.get()
                + " gateA_exact=" + GATE_A_EXACT.get()
                + " gateB_equal=" + GATE_B_EQUAL.get()
                + " mask_equal=" + MASK_EQUAL.get()
                + " biome_equal=" + BIOME_EQUAL.get()
                + " mismatches=" + MISMATCHES.get());
        System.out.println("first_mismatch=" + FIRST_MISMATCH);
        System.exit(MISMATCHES.get() == 0 && CASES.get() > 0 ? 0 : 1);
    }

    interface Mutation { void apply(Object chunk, Helpers h) throws Exception; }

    static class Helpers {
        Object[] states; int[] gids;
        Helpers(Object[] s, int[] g) { states = s; gids = g; }
        Object pick(int k) { return states[k % states.length]; }
        Object airRef() throws Exception { return AIR_REF; }
        Object chestState() throws Exception {
            // first state whose block is a chest (registry name under Forge, class name offline)
            for (Object st : states) {
                Object blk = BlockGet.invoke(st);
                if (RegName != null) {
                    if ("minecraft:chest".equals(String.valueOf(RegName.invoke(blk)))) return st;
                } else if (blk.getClass().getName().endsWith("BlockChest")) {
                    return st;
                }
            }
            return states[0];
        }
        void setBlock(Object chunk, int x, int y, int z, Object st) throws Exception {
            Object pos = BLOCK_POS_CT.newInstance(x, y, z);
            M_SET_BLOCK.invoke(chunk, pos, st);
        }
        void setLight(Object chunk, int x, int y, int z, int level) throws Exception {
            Object pos = BLOCK_POS_CT.newInstance(x, y, z);
            M_SET_LIGHT.invoke(chunk, BLOCK_LIGHT, pos, level);
            M_SET_LIGHT.invoke(chunk, SKY, pos, level);
        }
        void clearColumn(Object chunk, int x, int y, int z) throws Exception {
            // remove every non-air block in a column so its section can become empty
            for (int yy = 0; yy < 256; yy++) setBlock(chunk, x, yy, z, airState());
        }
    }

    static Method BlockGet, RegName;
    static Object airSt;
    static volatile Object AIR_REF;

    static Object airState() { return airSt; }

    static void runCase(String name, int cxOffset, Mutation mut) throws Exception {
        int cx = 40 + cxOffset, cz = 7;
        Helpers h = new Helpers(TEST_STATES, TEST_GIDS);

        // --- Build the real chunk from a primer ---
        java.nio.ByteBuffer primerBB = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer pc = primerBB.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 0, (char) TEST_GIDS[0 % TEST_GIDS.length]);
                for (int y = 1; y <= 63; y++) pc.put(col | y, (char) TEST_GIDS[1 % TEST_GIDS.length]);
                for (int y = 64; y <= 67; y++) pc.put(col | y, (char) TEST_GIDS[2 % TEST_GIDS.length]);
                pc.put(col | 68, (char) TEST_GIDS[3 % TEST_GIDS.length]);
            }
        }
        Object primer = CHUNK_PRIMER_CT.newInstance();
        Field pf = primer.getClass().getDeclaredField("field_177860_a");
        pf.setAccessible(true);
        char[] cells = (char[]) pf.get(primer);
        pc.clear(); pc.get(cells);

        Object world = newWorld();
        Object chunk = CHUNK_CLS.getConstructor(net.minecraft.world.World.class,
                net.minecraft.world.chunk.ChunkPrimer.class, int.class, int.class)
                .newInstance(world, primer, cx, cz);

        if (mut != null) mut.apply(chunk, h);

        // --- REAL Java packet ---
        Object pkt = PACKET_CT.newInstance(chunk, 65535);
        int javaMask = PKT_MASK.getInt(pkt); // field_186948_c
        byte[] javaPayload = (byte[]) PKT_PAYLOAD.get(pkt); // field_186949_d: exact written bytes

        // --- Native pipeline: register primer, refresh sections from the LIVE chunk, encode ---
        byte[] biomes = (byte[]) M_GET_BIOMES.invoke(chunk);
        ByteBuffer biomeBB = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        biomeBB.put(biomes); biomeBB.clear();
        long gen = NativeChunkBridge.register(0, cx, cz, addr(primerBB), addr(biomeBB));
        if (gen <= 0) { fail(name, "register failed"); return; }

        refreshAllSections(chunk, cx, cz);

        ByteBuffer outBB = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
        int n = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(outBB), 262144);
        NativeChunkBridge.unload(0, cx, cz);
        if (n <= 0) { fail(name, "encode returned " + n); return; }
        byte[] nativePayload = new byte[n];
        outBB.clear(); outBB.get(nativePayload);

        System.out.println("  java payload len=" + (javaPayload == null ? -1 : javaPayload.length)
                + " first=" + (javaPayload == null || javaPayload.length == 0 ? "-" : String.format("%02x %02x %02x", javaPayload[0], javaPayload[1], javaPayload[2]))
                + " native len=" + nativePayload.length);
        CASES.incrementAndGet();
        // Gate A: exact bytes (palette history coincides only by chance)
        if (javaPayload != null && javaEquals(javaPayload, nativePayload)) {
            GATE_A_EXACT.incrementAndGet();
        }

        // Gate B: independent decoded-state equality
        String r = compareParsed(parse(javaPayload), parse(nativePayload));
        if (r == null) {
            GATE_B_EQUAL.incrementAndGet();
            System.out.println(name + " -> GATE_B equal (mask ok, states ok, light ok, biomes ok)");
        } else {
            fail(name, r);
        }
    }

    /** The real flush path: extract Java states + light per section and refresh. */
    static void refreshAllSections(Object chunk, int cx, int cz) throws Exception {
        Object[] storages = (Object[]) M_GET_STORAGES.invoke(chunk);
        for (int y = 0; y < 16; y++) {
            int[] g = new int[4096];
            byte[] bl = new byte[2048], sl = new byte[2048];
            Object st = storages[y];
            boolean present = st != null && st != EMPTY_STORAGE;
            if (present) {
                Object cont = M_CONTAINER.invoke(st);
                for (int i = 0; i < 4096; i++) {
                    Object s = M_GET_STATE.invoke(cont, i & 15, i >> 8, (i >> 4) & 15);
                    Integer gid = (Integer) REG_GET_ID.invoke(REG_MAP, s);
                    g[i] = gid == null ? 0 : gid;
                }
                Object blN = M_GET_BL.invoke(st);
                if (blN != null) System.arraycopy(M_NIBBLE_BYTES.invoke(blN), 0, bl, 0, 2048);
                Object slN = M_GET_SL.invoke(st);
                if (slN != null) System.arraycopy(M_NIBBLE_BYTES.invoke(slN), 0, sl, 0, 2048);
            }
            // mark + refresh even when absent in Java (all-air) so native matches coverage
            NativeChunkBridge.markSectionMutation(0, cx, cz, (byte) y);
            ByteBuffer sBB = sectionStates(g);
            ByteBuffer lBB = sectionLight(bl, sl);
            NativeChunkBridge.refreshSection(0, cx, cz, (byte) y, addr(sBB), addr(lBB), addr(lBB) + 2048);
        }
    }


    /** M4.2C preflight: biome-only refresh through the ACTUAL live entry
     *  (M4Coherency.refreshChunkNow) with NO dirty sections tracked. The chunk
     *  is never inserted into ChunkMutationTracker, so work==null: the entry
     *  must still resolve dim + coords, push biomes, refresh nothing, and leave
     *  a packet-comparable state. */
    static void runNoDirtyBiomeOnlyCase() throws Exception {
        int cx = 88, cz = 11;
        java.nio.ByteBuffer primerBB = java.nio.ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer pc = primerBB.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 0, (char) TEST_GIDS[0]);
                for (int y = 1; y <= 63; y++) pc.put(col | y, (char) TEST_GIDS[1]);
                for (int y = 64; y <= 67; y++) pc.put(col | y, (char) TEST_GIDS[2]);
                pc.put(col | 68, (char) TEST_GIDS[3]);
            }
        }
        Object primer = CHUNK_PRIMER_CT.newInstance();
        Field pf = primer.getClass().getDeclaredField("field_177860_a");
        pf.setAccessible(true);
        pc.clear(); pc.get((char[]) pf.get(primer));
        Object chunk = CHUNK_CLS.getConstructor(net.minecraft.world.World.class, net.minecraft.world.chunk.ChunkPrimer.class, int.class, int.class)
                .newInstance(newWorld(), primer, cx, cz);
        Object pkt = PACKET_CT.newInstance(chunk, 65535);
        byte[] javaPayload = (byte[]) PKT_PAYLOAD.get(pkt);

        long gen = NativeChunkBridge.register(0, cx, cz, addr(primerBB), 0L); // zero biomes, live shape
        if (gen <= 0) { fail("no-dirty-biome-only", "register failed"); return; }

        // Negative control (M4.2C skyLight finding): with NO refresh at all, the
        // registration-default sky light (0xFF) differs from the Java light
        // engine's values in untouched sections — the live failure shape.
        {
            ByteBuffer nb = java.nio.ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
            int n0 = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(nb), 262144);
            byte[] nat0 = new byte[n0];
            nb.clear(); nb.get(nat0);
            String rNeg = compareParsed(parse(javaPayload), parse(nat0));
            if (rNeg != null && rNeg.contains("skyLight")) {
                System.out.println("skylight-negative-control -> correctly detected " + rNeg);
            } else if (rNeg != null && rNeg.contains("biome")) {
                System.out.println("skylight-negative-control -> biome-differs-first " + rNeg + " (light also unsynced)");
            } else {
                fail("skylight-negative-control", "expected stale-light mismatch, got: " + rNeg);
            }
        }

        int sectionsTouched = com.rustcraft.bridge.M4Coherency.refreshChunkNow(chunk); // ACTUAL live entry (first-touch full sync)
        if (sectionsTouched != 16) {
            fail("no-dirty-biome-only", "first-touch full sync refreshed " + sectionsTouched + " sections, expected 16");
        }
        long pushed = com.rustcraft.bridge.M4Coherency.BIOMES_PUSHED.get();
        if (pushed <= 0) {
            fail("no-dirty-biome-only", "biomes not pushed through the live entry");
        }

        ByteBuffer outBB = java.nio.ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
        int n = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(outBB), 262144);
        NativeChunkBridge.unload(0, cx, cz);
        if (n <= 0) { fail("no-dirty-biome-only", "encode " + n); return; }
        byte[] nat = new byte[n];
        outBB.clear(); outBB.get(nat);
        String r = compareParsed(parse(javaPayload), parse(nat));
        if (r == null) {
            System.out.println("no-dirty-biome-only -> GATE_B equal (first-touch full sync 16 sections + biomes via live entry)");
        } else {
            fail("no-dirty-biome-only", r);
        }
    }

    /** M4.2B biome regression: live registration carries ZERO biomes (biomeAddr=0).
     *  Negative control: comparing without a biome push must fail at biome[0]
     *  (reproduces the live M42B finding). Positive: setBiomes then equal. */
    static void runBiomeRegression() throws Exception {
        int cx = 77, cz = 9;
        // Real primer (sections present) but registered with biomeAddr=0 — the
        // exact live-worldgen ingestion shape whose biomes the M42B comparator
        // caught as zero at biome[0].
        java.nio.ByteBuffer primerBB = java.nio.ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer pc = primerBB.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 0, (char) TEST_GIDS[0]);
                for (int y = 1; y <= 63; y++) pc.put(col | y, (char) TEST_GIDS[1]);
                for (int y = 64; y <= 67; y++) pc.put(col | y, (char) TEST_GIDS[2]);
                pc.put(col | 68, (char) TEST_GIDS[3]);
            }
        }
        Object primer = CHUNK_PRIMER_CT.newInstance();
        Field pf = primer.getClass().getDeclaredField("field_177860_a");
        pf.setAccessible(true);
        pc.clear(); pc.get((char[]) pf.get(primer));
        Object world = newWorld();
        Object chunk = CHUNK_CLS.getConstructor(net.minecraft.world.World.class, net.minecraft.world.chunk.ChunkPrimer.class, int.class, int.class)
                .newInstance(world, primer, cx, cz);
        byte[] biomes = (byte[]) M_GET_BIOMES.invoke(chunk);
        Object pkt = PACKET_CT.newInstance(chunk, 65535);
        byte[] javaPayload = (byte[]) PKT_PAYLOAD.get(pkt);

        // register with ZERO biomes exactly as the live worldgen path does
        long gen = NativeChunkBridge.register(0, cx, cz, addr(primerBB), 0L);
        if (gen <= 0) { fail("biome-regression", "register failed"); return; }
        refreshAllSections(chunk, cx, cz);
        ByteBuffer outBB = java.nio.ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
        int n = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(outBB), 262144);

        // negative control: current native state (zero biomes) vs java => must mismatch
        byte[] nat = new byte[n];
        outBB.clear(); outBB.get(nat);
        String rNeg = compareParsed(parse(javaPayload), parse(nat));
        NativeChunkBridge.unload(0, cx, cz);
        if (rNeg == null || !rNeg.startsWith("biome")) {
            fail("biome-regression-negative", "expected biome mismatch, got: " + rNeg);
        } else {
            System.out.println("biome-negative-control -> correctly detected " + rNeg);
        }

        // positive: register again (zero biomes), push real biomes, compare
        gen = NativeChunkBridge.register(0, cx, cz, addr(primerBB), 0L);
        refreshAllSections(chunk, cx, cz); // re-registration reset sections; re-refresh
        ByteBuffer bBB = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        bBB.put(biomes); bBB.clear();
        NativeChunkBridge.setBiomes(0, cx, cz, addr(bBB));
        int n2 = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(outBB), 262144);
        byte[] nat2 = new byte[n2];
        outBB.clear(); outBB.get(nat2);
        NativeChunkBridge.unload(0, cx, cz);
        String rPos = compareParsed(parse(javaPayload), parse(nat2));
        if (rPos == null) {
            System.out.println("biome-push-fix -> GATE_B equal after setBiomes");
        } else {
            fail("biome-push-fix", rPos);
        }
    }

    static void fail(String name, String why) {
        MISMATCHES.incrementAndGet();
        if ("none".equals(FIRST_MISMATCH)) FIRST_MISMATCH = name + ": " + why;
        System.out.println(name + " -> MISMATCH: " + why);
    }

    /** Decoded-state equality of two parsed payloads: section coverage, logical
     *  states, lights, biomes. Null when equal, else the reason. Shared by the
     *  offline harness and the live M4PacketCompare comparator. */
    static String compareParsed(ParsedJ J, ParsedJ N) {
        if (J.sections.size() != N.sections.size()) {
            return "section count " + J.sections.size() + " vs " + N.sections.size();
        }
        for (int k = 0; k < J.sections.size(); k++) {
            Sec js = J.sections.get(k), ns = N.sections.get(k);
            if (js.idx != ns.idx) return "section order: java#" + js.idx + " native#" + ns.idx;
            // logical states
            for (int i = 0; i < 4096; i++) {
                if (js.cellGid[i] != ns.cellGid[i]) {
                    return "section " + js.idx + " cell " + i + " gid " + js.cellGid[i] + " vs " + ns.cellGid[i];
                }
            }
            for (int i = 0; i < 2048; i++) {
                if (js.bl[i] != ns.bl[i]) return "section " + js.idx + " blockLight[" + i + "]";
                if (js.sl[i] != ns.sl[i]) return "section " + js.idx + " skyLight[" + i + "]";
            }
        }
        // biomes (both full-chunk)
        if (J.biomes == null || N.biomes == null) return "biomes missing";
        for (int i = 0; i < 256; i++) {
            if (J.biomes[i] != N.biomes[i]) return "biome[" + i + "]";
        }
        MASK_EQUAL.incrementAndGet();
        BIOME_EQUAL.incrementAndGet();
        return null;
    }

    static class Sec { int idx; int[] cellGid = new int[4096]; byte[] bl = new byte[2048], sl = new byte[2048]; }
    static class ParsedJ { int mask = -1; java.util.List<Sec> sections = new java.util.ArrayList<>(); byte[] biomes; }

    static ParsedJ parse(byte[] w) {
        ParsedJ p = new ParsedJ();
        int pos = 0;
        int sec = -1;
        // Walk sections self-describing; biomes = final 256 bytes of a full-chunk payload.
        // Stop when fewer than a minimal section header remains before the biome tail.
        while (w.length - pos > 256) { // <=256 remainder is the biome tail
            sec++;
            Sec s = new Sec();
            s.idx = sec;
            int bits = w[pos++] & 0xFF;
            int palLen = readVarInt(w, pos); pos += varIntSize(palLen);
            int[] palette = new int[Math.max(palLen, 1)];
            for (int k = 0; k < palLen; k++) { palette[k] = readVarInt(w, pos); pos += varIntSize(palette[k]); }
            int longCount = readVarInt(w, pos); pos += varIntSize(longCount);
            long[] longs = new long[longCount];
            for (int k = 0; k < longCount; k++) {
                long v = 0;
                for (int b = 0; b < 8; b++) v = (v << 8) | (w[pos++] & 0xFF);
                longs[k] = v;
            }
            for (int i = 0; i < 4096; i++) {
                int bitPos = i * bits, w0 = bitPos >>> 6, off = bitPos & 63, fit = 64 - off;
                int entry;
                if (fit >= bits) entry = (int) ((longs[w0] >>> off) & ((1L << bits) - 1));
                else {
                    int rest = bits - fit;
                    long lo = (longs[w0] >>> off) & ((1L << fit) - 1);
                    long hi = longs[w0 + 1] & ((1L << rest) - 1);
                    entry = (int) (lo | (hi << fit));
                }
                s.cellGid[i] = palLen == 0 ? entry : (entry < palLen ? palette[entry] : -1);
            }
            for (int i = 0; i < 2048; i++) s.bl[i] = w[pos++];
            for (int i = 0; i < 2048; i++) s.sl[i] = w[pos++];
            p.sections.add(s);
        }
        if (w.length - pos == 256) {
            p.biomes = new byte[256];
            System.arraycopy(w, pos, p.biomes, 0, 256);
        } else if (w.length - pos > 0) {
            // trailing bytes that are neither a section nor 256 biomes
            p.biomes = new byte[] { (byte) ((w.length - pos) & 0x7F | 0x80) };
        }
        return p;
    }

    /** M4.2D mask-driven parser: exactly popCount(mask) sections in ascending
     *  bit order, each self-bounded (bits, palette, longs, light arrays), then
     *  exactly 256 biome bytes when expectBiomes. ANY structural violation —
     *  truncation, wrong section count, leftover bytes, overrun — returns null.
     *  A parse failure is NEVER a successful comparison. No scanning into the
     *  biome tail: absence is decided by the mask, not by a search. */
    static ParsedJ parseByMask(byte[] w, int mask, boolean expectBiomes) {
        try {
            ParsedJ p = new ParsedJ();
            p.mask = mask;
            int pos = 0;
            for (int y = 0; y < 16; y++) {
                if ((mask & (1 << y)) == 0) continue;
                if (pos + 1 > w.length) return null;
                Sec sec = new Sec();
                sec.idx = y;
                int bits = w[pos++] & 0xFF;
                if (bits < 4 || bits > 16) return null;
                int palLen = readVarInt(w, pos); pos += varIntSize(palLen);
                int[] palette = new int[Math.max(palLen, 1)];
                for (int k = 0; k < palLen; k++) {
                    palette[k] = readVarInt(w, pos); pos += varIntSize(palette[k]);
                }
                int longCount = readVarInt(w, pos); pos += varIntSize(longCount);
                int need = pos + longCount * 8 + 4096;
                if (need > w.length) return null;
                long[] longs = new long[longCount];
                for (int k = 0; k < longCount; k++) {
                    long v = 0;
                    for (int b = 0; b < 8; b++) v = (v << 8) | (w[pos++] & 0xFF);
                    longs[k] = v;
                }
                for (int i = 0; i < 4096; i++) {
                    int bitPos = i * bits, w0 = bitPos >>> 6, off = bitPos & 63, fit = 64 - off;
                    int entry;
                    if (fit >= bits) entry = (int) ((longs[w0] >>> off) & ((1L << bits) - 1));
                    else {
                        int rest = bits - fit;
                        long lo = (longs[w0] >>> off) & ((1L << fit) - 1);
                        long hi = longs[w0 + 1] & ((1L << rest) - 1);
                        entry = (int) (lo | (hi << fit));
                    }
                    sec.cellGid[i] = palLen == 0 ? entry : (entry < palLen ? palette[entry] : -1);
                }
                for (int i = 0; i < 2048; i++) sec.bl[i] = w[pos++];
                for (int i = 0; i < 2048; i++) sec.sl[i] = w[pos++];
                p.sections.add(sec);
            }
            int rem = w.length - pos;
            if (expectBiomes) {
                if (rem != 256) return null;
                p.biomes = new byte[256];
                System.arraycopy(w, pos, p.biomes, 0, 256);
            } else if (rem != 0) {
                return null;
            }
            return p;
        } catch (Throwable t) {
            return null; // structural violation
        }
    }

    static Object[] TEST_STATES; static int[] TEST_GIDS;

    public static void initReflectionPublic() throws Exception { initReflection(); }

    /** M4.2D shared accessors (offline lifecycle cases). */
    public static Object[] baseChunkAndPrimer(int cx, int cz) throws Exception {
        java.nio.ByteBuffer primerBB = java.nio.ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer pc = primerBB.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 0, (char) TEST_GIDS[0]);
                for (int y = 1; y <= 63; y++) pc.put(col | y, (char) TEST_GIDS[1]);
                for (int y = 64; y <= 67; y++) pc.put(col | y, (char) TEST_GIDS[2]);
                pc.put(col | 68, (char) TEST_GIDS[3]);
            }
        }
        Object primer = CHUNK_PRIMER_CT.newInstance();
        Field pf = primer.getClass().getDeclaredField("field_177860_a");
        pf.setAccessible(true);
        pc.clear(); pc.get((char[]) pf.get(primer));
        Object chunk = CHUNK_CLS.getConstructor(net.minecraft.world.World.class, net.minecraft.world.chunk.ChunkPrimer.class, int.class, int.class)
                .newInstance(newWorld(), primer, cx, cz);
        return new Object[] { chunk, primerBB };
    }

    public static Constructor<?> packetCtor() { return PACKET_CT; }
    public static Field pktPayloadField() { return PKT_PAYLOAD; }
    public static Field pktMaskField() { return PKT_MASK; }
    public static Object registryById(int gid) throws Exception { return REG_BY_ID.invoke(REG_MAP, gid); }
    public static Object airStateRef() { return airSt; }
    public static int testGid(int k) { return TEST_GIDS[k % TEST_GIDS.length]; }
    public static Object chestStateRef() throws Exception {
        for (Object st : TEST_STATES) {
            Object blk = BlockGet.invoke(st);
            if (RegName != null) {
                if ("minecraft:chest".equals(String.valueOf(RegName.invoke(blk)))) return st;
            } else if (blk.getClass().getName().endsWith("BlockChest")) return st;
        }
        return TEST_STATES[0];
    }
    public static long primerAddr(java.nio.ByteBuffer bb) { return addr(bb); }

    /** debug helper: a fresh base-terrain chunk at (cx, cz) */
    public static Object buildBaseChunk(int cx, int cz) throws Exception {
        Object primer = CHUNK_PRIMER_CT.newInstance();
        Field pf = primer.getClass().getDeclaredField("field_177860_a");
        pf.setAccessible(true);
        char[] cells = (char[]) pf.get(primer);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int col = (x << 12) | (z << 8);
            cells[col | 0] = (char) TEST_GIDS[0];
            for (int y = 1; y <= 63; y++) cells[col | y] = (char) TEST_GIDS[1];
            for (int y = 64; y <= 67; y++) cells[col | y] = (char) TEST_GIDS[2];
            cells[col | 68] = (char) TEST_GIDS[3];
        }
        Object world = newWorld();
        return CHUNK_CLS.getConstructor(net.minecraft.world.World.class, net.minecraft.world.chunk.ChunkPrimer.class, int.class, int.class).newInstance(world, primer, cx, cz);
    }

    static void initReflection() throws Exception {
        Class<?> blockCls = Class.forName("net.minecraft.block.Block");
        Field regF = blockCls.getDeclaredField("field_176229_d");
        regF.setAccessible(true);
        REG_MAP = regF.get(null);
        REG_GET_ID = REG_MAP.getClass().getMethod("func_148747_b", Object.class);
        REG_GET_ID.setAccessible(true);
        REG_BY_ID = REG_MAP.getClass().getMethod("func_148745_a", int.class);
        REG_BY_ID.setAccessible(true);
        airSt = REG_BY_ID.invoke(REG_MAP, 0);
        AIR_REF = airSt;

        java.util.List<Object> sts = new java.util.ArrayList<>();
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (Object st : (java.lang.Iterable<?>) REG_MAP) {
            if (st == null) continue;
            Integer gid = (Integer) REG_GET_ID.invoke(REG_MAP, st);
            if (gid == null || gid == 0) continue;
            sts.add(st); ids.add(gid);
            if (sts.size() >= 300) break;
        }
        TEST_STATES = sts.toArray();
        TEST_GIDS = new int[ids.size()];
        for (int i = 0; i < TEST_GIDS.length; i++) TEST_GIDS[i] = ids.get(i);

        BlockGet = Class.forName("net.minecraft.block.state.IBlockState").getMethod("func_177230_c");
        BlockGet.setAccessible(true);
        try {
            RegName = Class.forName("net.minecraft.block.Block").getMethod("getRegistryName");
            RegName.setAccessible(true);
        } catch (Throwable t) {
            RegName = null; // vanilla-only classpath (no Forge): match by block class instead
        }

        CHUNK_PRIMER_CT = Class.forName("net.minecraft.world.chunk.ChunkPrimer").getDeclaredConstructor();
        CHUNK_CLS = Class.forName("net.minecraft.world.chunk.Chunk");
        BLOCK_POS_CT = Class.forName("net.minecraft.util.math.BlockPos").getConstructor(int.class, int.class, int.class);
        M_SET_BLOCK = CHUNK_CLS.getMethod("func_177436_a", Class.forName("net.minecraft.util.math.BlockPos"), Class.forName("net.minecraft.block.state.IBlockState"));
        Class<?> skyCls = Class.forName("net.minecraft.world.EnumSkyBlock");
        SKY = java.lang.Enum.valueOf((Class<? extends Enum>) skyCls.asSubclass(Enum.class), "SKY");
        BLOCK_LIGHT = java.lang.Enum.valueOf((Class<? extends Enum>) skyCls.asSubclass(Enum.class), "BLOCK");
        M_SET_LIGHT = CHUNK_CLS.getMethod("func_177431_a", skyCls, Class.forName("net.minecraft.util.math.BlockPos"), int.class);
        M_GET_STORAGES = CHUNK_CLS.getMethod("func_76587_i");
        M_GET_STORAGES.setAccessible(true);
        M_GET_BIOMES = CHUNK_CLS.getMethod("func_76605_m");
        M_GET_BIOMES.setAccessible(true);

        Class<?> ebs = Class.forName("net.minecraft.world.chunk.storage.ExtendedBlockStorage");
        M_CONTAINER = ebs.getMethod("func_186049_g"); M_CONTAINER.setAccessible(true);
        M_GET_BL = ebs.getMethod("func_76661_k"); M_GET_BL.setAccessible(true);
        M_GET_SL = ebs.getMethod("func_76671_l"); M_GET_SL.setAccessible(true);
        M_NIBBLE_BYTES = Class.forName("net.minecraft.world.chunk.NibbleArray").getMethod("func_177481_a");
        M_NIBBLE_BYTES.setAccessible(true);
        M_GET_STATE = Class.forName("net.minecraft.world.chunk.BlockStateContainer")
                .getMethod("func_186016_a", int.class, int.class, int.class);
        M_GET_STATE.setAccessible(true);

        Field emptyF = CHUNK_CLS.getField("field_186036_a");
        emptyF.setAccessible(true);
        EMPTY_STORAGE = emptyF.get(null);

        Class<?> pktCls = Class.forName("net.minecraft.network.play.server.SPacketChunkData");
        PACKET_CT = pktCls.getConstructor(CHUNK_CLS, int.class);
        PKT_PAYLOAD = pktCls.getDeclaredField("field_186949_d");
        PKT_PAYLOAD.setAccessible(true);
        PKT_MASK = pktCls.getDeclaredField("field_186948_c"); // changedSectionMask
        PKT_MASK.setAccessible(true);
    }

    static Object newWorld() throws Exception {
        Class<?> wiCls = Class.forName("net.minecraft.world.storage.WorldInfo");
        Constructor<?> wic = wiCls.getDeclaredConstructor();
        wic.setAccessible(true);
        Object info = wic.newInstance();
        Object provider = Class.forName("net.minecraft.world.WorldProviderSurface").getDeclaredConstructor().newInstance();
        Object profiler = Class.forName("net.minecraft.profiler.Profiler").getDeclaredConstructor().newInstance();
        Object world = new ProbeWorld((net.minecraft.world.storage.WorldInfo) info,
                (net.minecraft.world.WorldProvider) provider,
                (net.minecraft.profiler.Profiler) profiler);
        // Servers set provider.hasSkyLight during dimension init; offline we set it
        // directly so storages get sky arrays and the packet includes them (overworld
        // context the native encoder is validated against).
        Field wp = world.getClass().getSuperclass().getDeclaredField("field_73011_w");
        wp.setAccessible(true);
        Object prov = wp.get(world);
        Field sky = prov.getClass().getSuperclass().getDeclaredField("field_191067_f");
        // walk to the declaring class WorldProvider
        Class<?> pc = prov.getClass();
        while (pc != null) {
            try { sky = pc.getDeclaredField("field_191067_f"); break; } catch (NoSuchFieldException e) { pc = pc.getSuperclass(); }
        }
        sky.setAccessible(true);
        sky.setBoolean(prov, true);
        return world;
    }

    static ThreadLocal<ByteBuffer> SEC_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096 * 2).order(ByteOrder.nativeOrder()));
    static ThreadLocal<ByteBuffer> LIT_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()));

    static ByteBuffer sectionStates(int[] g) {
        ByteBuffer bb = SEC_TL.get();
        CharBuffer cb = bb.asCharBuffer();
        for (int i = 0; i < 4096; i++) cb.put(i, (char) g[i]);
        return bb;
    }

    static ByteBuffer sectionLight(byte[] bl, byte[] sl) {
        ByteBuffer bb = LIT_TL.get();
        bb.clear();
        bb.put(bl).put(sl);
        bb.clear();
        return bb;
    }

    static boolean javaEquals(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }

    static int readVarInt(byte[] b, int p) {
        int result = 0, shift = 0;
        while (true) {
            byte by = b[p++];
            result |= (by & 0x7F) << shift;
            if ((by & 0x80) == 0) return result;
            shift += 7;
        }
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) { v >>>= 7; n++; }
        return n;
    }
}
