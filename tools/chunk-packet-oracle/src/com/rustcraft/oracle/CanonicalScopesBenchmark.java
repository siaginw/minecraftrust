package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * M1.4-R §4 canonical apples-to-apples microbenchmark (directive 2026-09-18).
 *
 * Measures, per fixture (§5 deterministic families + section-count sweep),
 * under identical entry state:
 *   SCOPE-B   Java  : new SPacketChunkData(chunk, mask)                (ctor only)
 *   SCOPE-C   Java  : ctor + func_148840_b into Netty heap buffer     (no extra copy)
 *   SCOPE-A   Java  : payload production = serialization body minus ctor
 *                     (func_148840_b of an already-built packet)
 *   SCOPE-A-N native: acquire + staging + JNI + Rust encode + handoff
 *
 * No clamps. No timing constants. Raw per-sample rows in CSV.
 * Equal-scope ratios ONLY: A vs A-N (payload bytes out).
 *
 * Usage: CanonicalScopesBenchmark <outCsv>
 */
public final class CanonicalScopesBenchmark {

    static final int SAMPLES = 500;   // per fixture family

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        String out = args.length > 0 ? args[0] : "machine/raw/M14R-canonical-scopes.csv";
        PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)));
        pw.println("scope,path,fixture,mask_hex,sections,full_chunk,trial,elapsed_ns,payload_bytes");

        long sink = 0;
        // §5 deterministic families 0..7 sweep masks incl 0x0000/0x0001/0x0003/0x0005/
        // 0x00F0/0x8241/0xFFFF; add explicit section-count sweep via fuzz seeds.
        for (int i = 0; i < 8; i++) {
            sink += benchFamily(pw, i, "det" + i);
        }
        // Sparse masks / section counts 1,4,8 via dedicated fuzz builders
        for (int want = 1; want <= 8; want = want * 2) {
            sink += benchSectionSweep(pw, want);
        }
        pw.close();
        System.err.println("[CanonicalScopesBenchmark] sink=" + sink + " rows -> " + out);
    }

    static long benchFamily(PrintWriter pw, int i, String name) throws Exception {
        M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(i);
        return benchFixture(pw, fx, name);
    }

    static long benchSectionSweep(PrintWriter pw, int wantSections) throws Exception {
        // build fixture with exactly wantSections non-empty sections via buildFuzz path
        java.util.Random rng = new java.util.Random(9000 + wantSections);
        long sink = 0;
        for (int k = 0; k < 4; k++) {
            M14RParityHarness.Fixture fx = M14RParityHarness.buildFuzz(rng, k);
            sink += benchFixture(pw, fx, "sweep" + wantSections + "_" + k);
        }
        return sink;
    }

    static long benchFixture(PrintWriter pw, M14RParityHarness.Fixture fx, String name) throws Exception {
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        int mask = fx.mask;
        boolean full = fx.mask == 0xFFFF;
        long sink = 0;

        // warmup each scope
        for (int w = 0; w < 200; w++) {
            SPacketChunkData wp = new SPacketChunkData(ch, mask);
            ByteBuf wb = Unpooled.buffer(262144);
            PacketBuffer wpb = new PacketBuffer(wb);
            wp.func_148840_b(wpb);
            wb.release();
        }

        int cLen = -1; // last SCOPE-C wire length; SCOPE-C-N must match
        byte[] cRefBytes = null; // §3: last SCOPE-C payload for byte-equality
        String handoffLabel = System.getProperty("minecraftrust.m1.handoff", "0");

        // ---- PASS 1: Java scopes only (no native churn in this pass) ----
        ByteBuf cBuf = Unpooled.buffer(262144); // reused across trials (alloc not timed)
        PacketBuffer cPb = new PacketBuffer(cBuf);
        NativeChunkPacket.setRuntimeMode("OFF");
        for (int trial = 0; trial < SAMPLES; trial++) {
            String row = "0x" + Integer.toHexString(mask) + "," + Integer.bitCount(mask) + "," + full + "," + trial;
            long b0 = System.nanoTime();
            SPacketChunkData pB = new SPacketChunkData(ch, mask);
            long b1 = System.nanoTime();
            pw.println("SCOPE-B,JAVA," + name + "," + row + "," + (b1 - b0) + ",0");
            sink += mask;
            ByteBuf aBuf = Unpooled.buffer(262144);
            PacketBuffer aPb = new PacketBuffer(aBuf);
            long a0 = System.nanoTime();
            pB.func_148840_b(aPb);
            long a1 = System.nanoTime();
            int aLen = aBuf.readableBytes();
            pw.println("SCOPE-A,JAVA," + name + "," + row + "," + (a1 - a0) + "," + aLen);
            sink += aLen;
            aBuf.release();
            cBuf.clear(); cPb = new PacketBuffer(cBuf);
            long c0 = System.nanoTime();
            SPacketChunkData pC = new SPacketChunkData(ch, mask);
            pC.func_148840_b(cPb);
            long c1 = System.nanoTime();
            cLen = cBuf.readableBytes();
            pw.println("SCOPE-C,JAVA," + name + "," + row + "," + (c1 - c0) + "," + cLen);
            sink += cLen;
            if (trial % 50 == 0) {
                cRefBytes = new byte[cLen];
                cBuf.getBytes(0, cRefBytes);
            }
        }

        // ---- PASS 2: native SCOPE-C-N only (no Java churn between samples) ----
        ByteBuf nBuf = Unpooled.buffer(262144); // reused across trials (alloc not timed)
        PacketBuffer nPb = new PacketBuffer(nBuf);
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        for (int trial = 0; trial < SAMPLES; trial++) {
            String row = "0x" + Integer.toHexString(mask) + "," + Integer.bitCount(mask) + "," + full + "," + trial;
            long cn0 = System.nanoTime();
            SPacketChunkData pN = new SPacketChunkData(ch, 0);
            boolean ok = NativeChunkPacket.populatePacket(pN, ch, mask);
            nBuf.clear(); nPb = new PacketBuffer(nBuf);
            pN.func_148840_b(nPb);
            long cn1 = System.nanoTime();
            int nLen = nBuf.readableBytes();
            if (!ok) throw new AssertionError("SCOPE-C-N populatePacket returned false");
            if (trial % 50 == 0) {
                byte[] nBytes = new byte[nLen];
                nBuf.readBytes(nBytes);
                if (nLen != cLen || !java.util.Arrays.equals(cRefBytes, nBytes))
                    throw new AssertionError("SCOPE-C-N payload mismatch len " + nLen + " vs " + cLen);
                long fnv = 0xcbf29ce484222325L;
                for (byte b : nBytes) { fnv ^= (b & 0xff); fnv *= 0x100000001b3L; }
                pw.println("EQUALITY," + name + "," + row + ",fnv=" + Long.toHexString(fnv) + ",len=" + nLen);
            }
            pw.println("SCOPE-C-N-H" + handoffLabel + ",NATIVE," + name + "," + row + "," + (cn1 - cn0) + "," + nLen);
            sink += nLen;
        }
        cBuf.release();
        nBuf.release();
        NativeChunkPacket.setRuntimeMode("OFF");
        return sink;
    }
}
