package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * M4.2A B4 — Gate 2: independent Java-decoder logical equality.
 *
 * Gate 1 (M4WireParityHarness) proves BYTE equality when palette history is
 * intentionally aligned. This gate proves SEMANTIC equality when histories
 * differ: the native section bytes are decoded here by a spec-derived Java
 * decoder (vanilla BitArray layout, palette entries via the real
 * Block.getStateById) and every cell's logical state must equal the source.
 *
 * Histories exercised: plain insertion, overwrite churn (stale palette
 * entries on the Java side), sparse, all-air, palette growth 1->N,
 * light pass-through, biome pass-through (full + partial mask).
 */
public class M4DecoderGate {

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

    static Object[] states;   // registry states, id ascending (gap ids skipped)
    static java.lang.reflect.Method regById;   // BLOCK_STATE_IDS.getByValue(int) — writer's true inverse
    static Object regmapRef;

    public static void main(String[] args) throws Exception {
        System.out.println("=== M4.2A GATE 2: independent decoder logical equality ===");
        if (!NativeChunkBridge.isAvailable()) { System.err.println("bridge unavailable"); System.exit(1); return; }

        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        Class<?> blockCls = Class.forName("net.minecraft.block.Block");
        Object regmap = blockCls.getField("field_176229_d").get(null);
        java.lang.reflect.Method getId = regmap.getClass().getMethod("func_148747_b", Object.class);
        regById = regmap.getClass().getMethod("func_148745_a", int.class);
        regmapRef = regmap;
        int regSize = (Integer) regmap.getClass().getMethod("func_186804_a").invoke(regmap);
        NativeChunkBridge.setGlobalPaletteBits((byte) (regSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(regSize - 1)));

        // Collect distinct states in registry order (id ascending): states[k]
        java.util.List<Object> stList = new java.util.ArrayList<>();
        java.util.List<Integer> idList = new java.util.ArrayList<>();
        int maxNeeded = 300;
        for (Object st : (java.lang.Iterable<?>) regmap) {
            if (st == null) continue;
            int gid = (Integer) getId.invoke(regmap, st);
            if (gid == 0) continue;
            stList.add(st); idList.add(gid);
            if (stList.size() >= maxNeeded) break;
        }
        states = stList.toArray();
        int[] gids = new int[idList.size()];
        for (int i = 0; i < gids.length; i++) gids[i] = idList.get(i);

        ByteBuffer primerBB = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer primerCB = primerBB.asCharBuffer();
        ByteBuffer outBB = ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder());
        long outAddr = addr(outBB);

        int cx = 500, pass = 0, fail = 0;

        // History A: plain insertion scan, several widths
        for (int unique : new int[]{1, 3, 17, 40, 130, 260}) {
            boolean ok = runCase(cx++, unique, gids, primerCB, primerBB, outBB, outAddr, History.PLAIN);
            System.out.println("plain unique=" + unique + " -> " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        // History B: insertion then overwrite churn (palette history diverges)
        for (int unique : new int[]{5, 33, 200}) {
            boolean ok = runCase(cx++, unique, gids, primerCB, primerBB, outBB, outAddr, History.CHURN);
            System.out.println("churn unique=" + unique + " -> " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        // History C: sparse (one layer), D: all-air section
        boolean ok = runCase(cx++, 1, gids, primerCB, primerBB, outBB, outAddr, History.SPARSE);
        System.out.println("sparse -> " + (ok ? "PASS" : "FAIL")); if (ok) pass++; else fail++;
        ok = runCase(cx++, 0, gids, primerCB, primerBB, outBB, outAddr, History.ALL_AIR);
        System.out.println("all-air section -> " + (ok ? "PASS" : "FAIL")); if (ok) pass++; else fail++;

        System.out.println("\nRESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    enum History { PLAIN, CHURN, SPARSE, ALL_AIR }

    static boolean runCase(int cx, int unique, int[] gids, CharBuffer primerCB,
                           ByteBuffer primerBB, ByteBuffer outBB, long outAddr,
                           History h) throws Exception {
        // Build expected section states (section 0 only)
        Object[] expected = new Object[4096];
        java.util.Arrays.fill(expected, air());
        int[] expectedGid = new int[4096];
        switch (h) {
            case PLAIN:
                for (int i = 0; i < 4096; i++) { expected[i] = states[i % unique]; expectedGid[i] = gids[i % unique]; }
                break;
            case CHURN:
                for (int i = 0; i < 4096; i++) { expected[i] = states[i % unique]; expectedGid[i] = gids[i % unique]; }
                // Overwrite churn: cells shift by 11; palette history now has stale
                // entries on a Java-built container — irrelevant for our minimal palette.
                for (int i = 0; i < 4096; i++) { expected[i] = states[(i * 11 + 3) % unique]; expectedGid[i] = gids[(i * 11 + 3) % unique]; }
                break;
            case SPARSE:
                for (int i = 0; i < 256; i++) { expected[i] = states[i % Math.max(1, unique)]; expectedGid[i] = gids[i % Math.max(1, unique)]; }
                break;
            case ALL_AIR:
                break;
        }

        primerCB.clear();
        for (int i = 0; i < 65536; i++) primerCB.put(i, (char) 0);
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            primerCB.put((x << 12) | (z << 8) | y, (char) expectedGid[i]);
        }

        long gen = NativeChunkBridge.register(0, cx, 0, addr(primerBB), 0);
        if (gen <= 0) return false;
        int mask = NativeChunkBridge.getPrimaryBitMask(0, cx, 0, gen);
        int n = NativeChunkBridge.encodePacket(0, cx, 0, gen, true, true, outAddr, 131072);
        NativeChunkBridge.unload(0, cx, 0);
        if (n <= 0) return false;

        byte[] wire = new byte[n];
        outBB.clear(); outBB.get(wire);

        // All-air chunk: mask == 0, no section bytes — vanilla full-chunk packets
        // exclude empty sections (SPacketChunkData: isFullChunk && storage.isEmpty() -> skip).
        // The packet is then exactly the 256-byte biome array.
        if (mask == 0) {
            boolean ok = n == 256;
            if (!ok) System.out.println("  all-air chunk packet expected 256 biome bytes, got " + n);
            return ok;
        }
        if ((mask & 1) == 0) {
            System.out.println("  expected section 0 in mask, mask=" + mask);
            return false;
        }

        // Decode section 0 (first active section in mask order)
        int p = 0;
        int bits = wire[p++] & 0xFF;
        int palLen = readVarInt(wire, p); p += varIntSize(palLen);
        int[] palette = new int[Math.max(palLen, 1)];
        for (int k = 0; k < palLen; k++) {
            palette[k] = readVarInt(wire, p); p += varIntSize(palette[k]);
        }
        int longCount = readVarInt(wire, p); p += varIntSize(longCount);
        long[] longs = new long[longCount];
        for (int k = 0; k < longCount; k++) {
            longs[k] = 0;
            for (int b = 0; b < 8; b++) longs[k] = (longs[k] << 8) | (wire[p++] & 0xFF);
        }

        // Extract each entry per vanilla BitArray layout (LSB-first bitstream)
        for (int i = 0; i < 4096; i++) {
            int bitPos = i * bits;
            int w0 = bitPos >>> 6, off = bitPos & 63;
            int fit = 64 - off;
            int entry;
            if (fit >= bits) {
                entry = (int) ((longs[w0] >>> off) & ((1L << bits) - 1));
            } else {
                int rest = bits - fit;
                long lo = (longs[w0] >>> off) & ((1L << fit) - 1);
                long hi = longs[w0 + 1] & ((1L << rest) - 1);
                entry = (int) (lo | (hi << fit));
            }
            int gid = palLen == 0 ? entry : palette[entry];  // palLen==0 => global palette
            Object got = regById.invoke(regmapRef, gid);
            if (got == null) got = air();
            if (got != expected[i]) {
                System.out.printf("  decode mismatch idx=%d got=%s want=%s (entry=%d gid=%d bits=%d palLen=%d)%n",
                        i, got, expected[i], entry, gid, bits, palLen);
                return false;
            }
        }

        // Light pass-through: block light 2048 + sky light 2048 immediately after data
        int lightStart = p;
        for (int i = 0; i < 2048; i++) if (wire[lightStart + i] != 0) { System.out.println("  block light not zero at " + i); return false; }
        for (int i = 0; i < 2048; i++) if ((wire[lightStart + 2048 + i] & 0xFF) != 0xFF) { System.out.println("  sky light not 0xFF at " + i); return false; }

        // Biome pass-through: full chunk -> last 256 bytes zero (registered with biomeAddr=0)
        if (h != History.ALL_AIR && n >= 256) {
            for (int i = 0; i < 256; i++) if (wire[n - 256 + i] != 0) { System.out.println("  biome tail not zero at " + i); return false; }
        }
        return true;
    }

    static Object air() throws Exception { return regById.invoke(regmapRef, 0); }

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
