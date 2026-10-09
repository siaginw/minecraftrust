package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;

/** M1.4-R2: isolate the residual canonical-scope gap. Times each component of
 *  SCOPE-C-N separately (packet shell, populatePacket, serialize, buffer alloc)
 *  plus the same for Java SCOPE-C. */
public final class ResidualGapIsolation {

    static final int WARM = 2000, TRIALS = 8000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        long sink = 0;
        for (int f : new int[]{1, 4}) {
            M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(f);
            Chunk ch = M14RParityHarness.createMockChunk(fx);
            int mask = fx.mask;

            NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
            NativeChunkPacket.setHandoffMode("A");
            for (int i = 0; i < WARM; i++) {
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                NativeChunkPacket.populatePacket(p, ch, mask);
                ByteBuf b = Unpooled.buffer(262144);
                p.func_148840_b(new PacketBuffer(b));
                sink += b.readableBytes();
                b.release();
            }
            long bShell = Long.MAX_VALUE, bPop = Long.MAX_VALUE, bSer = Long.MAX_VALUE, bAlloc = Long.MAX_VALUE, bAll = Long.MAX_VALUE;
            long[] all = new long[TRIALS];
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                long t1 = System.nanoTime();
                boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
                long t2 = System.nanoTime();
                ByteBuf b = Unpooled.buffer(262144);
                PacketBuffer pb = new PacketBuffer(b);
                long t3 = System.nanoTime();
                p.func_148840_b(pb);
                long t4 = System.nanoTime();
                sink += b.readableBytes() + (ok ? 1 : 0);
                b.release();
                all[i] = t4 - t0;
                if (t1 - t0 < bShell) bShell = t1 - t0;
                if (t2 - t1 < bPop) bPop = t2 - t1;
                if (t3 - t2 < bAlloc) bAlloc = t3 - t2;
                if (t4 - t3 < bSer) bSer = t4 - t3;
                if (t4 - t0 < bAll) bAll = t4 - t0;
            }
            java.util.Arrays.sort(all);
            // Java full path for reference
            NativeChunkPacket.setRuntimeMode("OFF");
            long jBest = Long.MAX_VALUE; long[] jAll = new long[TRIALS];
            for (int i = 0; i < TRIALS; i++) {
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, mask);
                ByteBuf b = Unpooled.buffer(262144);
                p.func_148840_b(new PacketBuffer(b));
                long t1 = System.nanoTime();
                sink += b.readableBytes();
                b.release();
                jAll[i] = t1 - t0;
                if (t1 - t0 < jBest) jBest = t1 - t0;
            }
            java.util.Arrays.sort(jAll);
            System.err.println(String.format(
                "GAP f=%d NATIVE-HA best: shell=%.2f populate=%.2f alloc=%.2f serialize=%.2f | whole best=%.2f p50=%.2f || JAVA best=%.2f p50=%.2f",
                f, bShell/1e3, bPop/1e3, bAlloc/1e3, bSer/1e3, bAll/1e3, all[TRIALS/2]/1e3, jBest/1e3, jAll[TRIALS/2]/1e3));
        }
        System.err.println("sink=" + sink);
    }
}
