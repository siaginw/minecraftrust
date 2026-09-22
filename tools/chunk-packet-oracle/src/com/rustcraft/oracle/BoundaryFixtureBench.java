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

/** M1.4-R2 §11: deep-n boundary fixtures (loss/win edge cases) — 4000 trials
 *  per fixture per path, separate JVM passes already ensured by scope split. */
public final class BoundaryFixtureBench {

    static final int WARM = 500, SAMPLES = 4000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        int[] ids = new int[args.length];
        for (int i = 0; i < args.length; i++) ids[i] = Integer.parseInt(args[i]);
        if (ids.length == 0) ids = new int[]{1, 5, 7, 32, 39}; // det1 det5 det7 sweep1_0(32) sweep2_0(39)
        String out = System.getProperty("out", "machine/raw/M14R2-boundary.csv");
        long sink = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.println("scope,impl,fixture,mask,sections,full,trial,elapsed_ns,payload_bytes");
            for (int f : ids) {
                M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(f);
                Chunk ch = M14RParityHarness.createMockChunk(fx);
                int mask = fx.mask;
                String name = "f" + f;
                String row = "0x" + Integer.toHexString(mask) + "," + Integer.bitCount(mask) + "," + fx.fullChunk;

                // Java pass
                NativeChunkPacket.setRuntimeMode("OFF");
                ByteBuf cBuf = Unpooled.buffer(262144);
                PacketBuffer cPb = new PacketBuffer(cBuf);
                for (int i = 0; i < WARM; i++) {
                    SPacketChunkData p = new SPacketChunkData(ch, mask);
                    cBuf.clear(); cPb = new PacketBuffer(cBuf);
                    p.func_148840_b(cPb);
                    sink += cBuf.readableBytes();
                }
                long[] j = new long[SAMPLES];
                for (int t = 0; t < SAMPLES; t++) {
                    long t0 = System.nanoTime();
                    SPacketChunkData p = new SPacketChunkData(ch, mask);
                    cBuf.clear(); cPb = new PacketBuffer(cBuf);
                    p.func_148840_b(cPb);
                    j[t] = System.nanoTime() - t0;
                    sink += cBuf.readableBytes();
                }
                cBuf.release();

                // Native pass (HANDOFF-A)
                NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
                NativeChunkPacket.setHandoffMode("A");
                ByteBuf nBuf = Unpooled.buffer(262144);
                PacketBuffer nPb = new PacketBuffer(nBuf);
                byte[] ref = null; int refLen = -1;
                for (int i = 0; i < WARM; i++) {
                    SPacketChunkData p = new SPacketChunkData(ch, 0);
                    NativeChunkPacket.populatePacket(p, ch, mask);
                    nBuf.clear(); nPb = new PacketBuffer(nBuf);
                    p.func_148840_b(nPb);
                    sink += nBuf.readableBytes();
                }
                long[] n = new long[SAMPLES];
                for (int t = 0; t < SAMPLES; t++) {
                    long t0 = System.nanoTime();
                    SPacketChunkData p = new SPacketChunkData(ch, 0);
                    boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
                    nBuf.clear(); nPb = new PacketBuffer(nBuf);
                    p.func_148840_b(nPb);
                    n[t] = System.nanoTime() - t0;
                    sink += nBuf.readableBytes();
                }
                nBuf.release();
                NativeChunkPacket.setRuntimeMode("OFF");

                Arrays.sort(j); Arrays.sort(n);
                System.err.println(String.format(
                    "BOUNDARY f=%d(%s) java p50=%.2f p90=%.2f | native-HA p50=%.2f p90=%.2f | %s",
                    f, name, j[SAMPLES/2]/1e3, j[(int)(SAMPLES*0.9)]/1e3, n[SAMPLES/2]/1e3, n[(int)(SAMPLES*0.9)]/1e3,
                    (n[SAMPLES/2] < j[SAMPLES/2]) ? "NATIVE_WIN" : "JAVA_WIN"));
            }
        }
        System.err.println("sink=" + sink);
    }
}
