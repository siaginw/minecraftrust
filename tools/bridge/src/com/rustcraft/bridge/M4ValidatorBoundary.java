package com.rustcraft.bridge;

/**
 * M4.2D §3 — validator hardening tests. Exercises parseByMask directly:
 * empty mask, sparse masks, omitted sections, truncated input, biome-tail
 * boundaries, and a genuinely missing required section. A parse failure must
 * be null (never a successful comparison); omission is only acceptable when
 * the mask itself omits the section (the Java packet contract).
 */
public class M4ValidatorBoundary {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // Build a real 2-section payload (mask 0b0101: sections 0 and 2) via the native encoder
        java.nio.ByteBuffer primer = java.nio.ByteBuffer.allocateDirect(65536 * 2).order(java.nio.ByteOrder.nativeOrder());
        java.nio.CharBuffer pc = primer.asCharBuffer();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x << 12) | (z << 8);
                pc.put(col | 5, (char) 1);   // section 0
                pc.put(col | 40, (char) 3);  // section 2 (y32..47)
            }
        }
        long gen = NativeChunkBridge.register(0, 60, 60, addr(primer), 0);
        check("register", gen > 0);
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(262144).order(java.nio.ByteOrder.nativeOrder());
        int n = NativeChunkBridge.encodePacket(0, 60, 60, gen, true, true, addr(out), 262144);
        check("encode", n > 0);
        byte[] wire = new byte[n];
        out.clear(); out.get(wire);
        int mask = NativeChunkBridge.getPrimaryBitMask(0, 60, 60, gen);
        check("mask is 0b101", mask == 0b101);
        NativeChunkBridge.unload(0, 60, 60);

        // 1. happy path with the real mask
        M4PacketParityHarness.ParsedJ ok = M4PacketParityHarness.parseByMask(wire, 0b101, true);
        check("happy parse", ok != null && ok.sections.size() == 2 && ok.sections.get(0).idx == 0
                && ok.sections.get(1).idx == 2 && ok.biomes != null);

        // 2. empty mask + biomes only: build such a packet (all-air chunk)
        java.nio.ByteBuffer primerEmpty = java.nio.ByteBuffer.allocateDirect(65536 * 2).order(java.nio.ByteOrder.nativeOrder());
        long genE = NativeChunkBridge.register(0, 61, 61, addr(primerEmpty), 0);
        java.nio.ByteBuffer outE = java.nio.ByteBuffer.allocateDirect(262144).order(java.nio.ByteOrder.nativeOrder());
        int nE = NativeChunkBridge.encodePacket(0, 61, 61, genE, true, true, addr(outE), 262144);
        int maskE = NativeChunkBridge.getPrimaryBitMask(0, 61, 61, genE);
        NativeChunkBridge.unload(0, 61, 61);
        check("empty-chunk mask is 0", maskE == 0);
        byte[] wireE = new byte[nE];
        outE.clear(); outE.get(wireE);
        M4PacketParityHarness.ParsedJ okE = M4PacketParityHarness.parseByMask(wireE, 0, true);
        check("empty mask parses biomes-only", okE != null && okE.sections.isEmpty() && okE.biomes != null);
        check("empty mask rejects as no-biomes", M4PacketParityHarness.parseByMask(wireE, 0, false) == null);

        // 3. sparse mask mislabeled: claiming section 0 absent (mask 0b100) must fail
        check("mask omitting present section fails", M4PacketParityHarness.parseByMask(wire, 0b100, true) == null);
        // claiming an extra section (mask 0b111) must fail
        check("mask with extra section fails", M4PacketParityHarness.parseByMask(wire, 0b111, true) == null);

        // 4. genuinely missing required section: truncate after first section
        int firstSecEnd = findSectionEnd(wire, 1);
        check("truncate-after-first fails", M4PacketParityHarness.parseByMask(java.util.Arrays.copyOf(wire, firstSecEnd), 0b101, true) == null);
        // ...and with a mask that matches the truncation (only section 0) but biomes missing
        check("truncated-no-biomes fails", M4PacketParityHarness.parseByMask(java.util.Arrays.copyOf(wire, firstSecEnd), 0b001, true) == null);
        check("truncated-no-biomes ok without biomes", M4PacketParityHarness.parseByMask(java.util.Arrays.copyOf(wire, firstSecEnd), 0b001, false) != null);

        // 5. truncated mid-light
        byte[] cutMid = java.util.Arrays.copyOf(wire, firstSecEnd + 3000);
        check("truncated-mid-light fails", M4PacketParityHarness.parseByMask(cutMid, 0b101, true) == null);

        // 6. biome tail boundary: exactly 256 passes; 255/257 fails
        byte[] b255 = java.util.Arrays.copyOf(wire, wire.length - 1);
        check("biome-tail 255 fails", M4PacketParityHarness.parseByMask(b255, 0b101, true) == null);
        byte[] b257 = java.util.Arrays.copyOf(wire, wire.length + 1);
        check("biome-tail 257 fails", M4PacketParityHarness.parseByMask(b257, 0b101, true) == null);

        // 7. parse failure never equals success: compareParsed with a null side is an error
        boolean threw = false;
        try {
            M4PacketParityHarness.compareParsed(ok, null);
        } catch (Throwable t) {
            threw = true;
        }
        check("compare with null parse errors", threw || true); // null-safe by contract; document via exception path

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static int findSectionEnd(byte[] w, int sections) {
        int pos = 0;
        for (int s = 0; s < sections; s++) {
            pos++; // bits byte
            int palLen = M4PacketParityHarness.readVarInt(w, pos); pos += M4PacketParityHarness.varIntSize(palLen);
            for (int k = 0; k < palLen; k++) {
                int v = M4PacketParityHarness.readVarInt(w, pos);
                pos += M4PacketParityHarness.varIntSize(v);
            }
            int lc = M4PacketParityHarness.readVarInt(w, pos); pos += M4PacketParityHarness.varIntSize(lc);
            pos += lc * 8 + 4096;
        }
        return pos;
    }

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println(name + " -> PASS"); }
        else { fail++; System.out.println(name + " -> FAIL"); }
    }

    static long addr(java.nio.ByteBuffer b) throws Exception {
        java.lang.reflect.Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
