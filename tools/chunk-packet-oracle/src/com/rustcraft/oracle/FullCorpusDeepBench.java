package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** M1.4-R2 §3/§4 final evidence: full corpus, deep warmup (500) + deep n
 *  (2000), isolated per-scope passes, reused buffers, byte-equality checked
 *  per fixture. Corrected methodology only. */
public final class FullCorpusDeepBench {

    static final int WARM = 500, SAMPLES = 2000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        String out = args.length > 0 ? args[0] : "machine/raw/M14R2-fullcorpus-deep.csv";
        long sink = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.println("scope,impl,fixture,mask_hex,sections,full_chunk,trial,elapsed_ns,payload_bytes");
            // det families
            for (int i = 0; i < 8; i++) {
                M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(i);
                sink += bench(pw, fx, "det" + i);
            }
            // section-count sweeps (same builders as canonical benchmark)
            for (int want = 1; want <= 8; want *= 2) {
                java.util.Random rng = new java.util.Random(9000 + want);
                for (int k = 0; k < 4; k++) {
                    M14RParityHarness.Fixture fx = M14RParityHarness.buildFuzz(rng, k);
                    sink += bench(pw, fx, "sweep" + want + "_" + k);
                }
            }
        }
        System.err.println("[FullCorpusDeepBench] sink=" + sink + " -> " + out);
    }

    static long bench(PrintWriter pw, M14RParityHarness.Fixture fx, String name) throws Exception {
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        int mask = fx.mask;
        String row = "0x" + Integer.toHexString(mask) + "," + Integer.bitCount(mask) + "," + fx.fullChunk;
        long sink = 0;

        // ---- Java pass ----
        NativeChunkPacket.setRuntimeMode("OFF");
        ByteBuf cBuf = Unpooled.buffer(262144);
        PacketBuffer cPb = new PacketBuffer(cBuf);
        for (int i = 0; i < WARM; i++) {
            SPacketChunkData p = new SPacketChunkData(ch, mask);
            cBuf.clear(); cPb = new PacketBuffer(cBuf);
            p.func_148840_b(cPb);
            sink += cBuf.readableBytes();
        }
        byte[] ref = null; int refLen = -1;
        for (int t = 0; t < SAMPLES; t++) {
            long t0 = System.nanoTime();
            SPacketChunkData p = new SPacketChunkData(ch, mask);
            cBuf.clear(); cPb = new PacketBuffer(cBuf);
            p.func_148840_b(cPb);
            long el = System.nanoTime() - t0;
            int len = cBuf.readableBytes();
            pw.println("SCOPE-C,JAVA," + name + "," + row + "," + t + "," + el + "," + len);
            sink += len;
            if (ref == null) { ref = new byte[len]; cBuf.getBytes(0, ref); refLen = len; }
        }
        cBuf.release();

        // ---- Native pass (HANDOFF-A, gate bypassed for measurement: force native) ----
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");
        int gateSave = NativeChunkPacket.M1_NATIVE_MIN_WIRE_BYTES;
        NativeChunkPacket.setMinWireBytes(0); // measure ALL fixtures; rule applied offline
        ByteBuf nBuf = Unpooled.buffer(262144);
        PacketBuffer nPb = new PacketBuffer(nBuf);
        for (int i = 0; i < WARM; i++) {
            SPacketChunkData p = new SPacketChunkData(ch, 0);
            NativeChunkPacket.populatePacket(p, ch, mask);
            nBuf.clear(); nPb = new PacketBuffer(nBuf);
            p.func_148840_b(nPb);
            sink += nBuf.readableBytes();
        }
        for (int t = 0; t < SAMPLES; t++) {
            long t0 = System.nanoTime();
            SPacketChunkData p = new SPacketChunkData(ch, 0);
            boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
            nBuf.clear(); nPb = new PacketBuffer(nBuf);
            p.func_148840_b(nPb);
            long el = System.nanoTime() - t0;
            int len = nBuf.readableBytes();
            if (!ok) throw new AssertionError(name + ": populatePacket=false (forced native)");
            if (t % 100 == 0) {
                byte[] nb = new byte[len];
                nBuf.getBytes(0, nb);
                if (len != refLen || !Arrays.equals(ref, nb))
                    throw new AssertionError(name + ": payload mismatch len " + len + " vs " + refLen);
            }
            pw.println("SCOPE-C-N-HA,NATIVE," + name + "," + row + "," + t + "," + el + "," + len);
            sink += len;
        }
        nBuf.release();
        NativeChunkPacket.setMinWireBytes(gateSave);
        NativeChunkPacket.setRuntimeMode("OFF");
        return sink;
    }
}
