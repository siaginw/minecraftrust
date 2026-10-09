package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * M1G §3 (operator directive 2026-09-19): structural + phase diagnosis of the
 * reproducible tail-latency fixtures det5 and det1.
 *
 * DIAGNOSTIC INSTRUMENTATION ONLY — kept separate from acceptance timing
 * (TailLatencyStudy). Reads the bridge's existing per-phase counters
 * (stage / predict / alloc / jni / acquire) as single-threaded deltas so each
 * trial's phases are attributed exactly, logs epoch ms per trial for offline
 * correlation with -verbose:gc / safepoint telemetry, and dumps the fixture's
 * structural characteristics (per written section: bits, words, palette count,
 * light presence; payload; TE count).
 *
 * Usage: DetFixtureDiagnosis <det5|det1> <trials> <A|0> <out.csv>
 */
public final class DetFixtureDiagnosis {

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "det5";
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        String handoff = args.length > 2 ? args[2] : "A";
        String out = args.length > 3 ? args[3] : "machine/raw/M1G-diag-" + which + "-h" + handoff + ".csv";

        Bootstrap.func_151354_b();
        M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(
                "det1".equals(which) ? 1 : 5);
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        int mask = fx.mask;

        // ---- structural dump ----
        System.out.println("== structure " + which + " ==");
        System.out.println("  mask=0x" + Integer.toHexString(mask) + " fullChunk=" + fx.fullChunk
                + " profile=" + fx.profile + " seed=" + fx.seed);
        Field fBits = null, fStorage = null;
        for (Field f : BlockStateContainer.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("field_186024_e")) fBits = f;
            if (f.getName().equals("field_186021_b")) fStorage = f;
        }
        ExtendedBlockStorage[] sections = ch.func_76587_i();
        int written = 0;
        for (int i = 0; i < 16; i++) {
            ExtendedBlockStorage ebs = sections[i];
            boolean sentinel = (ebs == Chunk.field_186036_a);
            boolean writes = ebs != Chunk.field_186036_a
                    && (!fx.fullChunk || !ebs.func_76663_a())
                    && (mask & (1 << i)) != 0;
            if (!writes) {
                System.out.println("  sec " + i + ": " + (sentinel ? "sentinel" : "empty") + " (not written)");
                continue;
            }
            written++;
            BlockStateContainer bsc = ebs.func_186049_g();
            int bits = fBits.getInt(bsc);
            Object storage = fStorage.get(bsc);
            int words = ((net.minecraft.util.BitArray) storage).func_188143_a().length;
            System.out.println("  sec " + i + ": WRITTEN bits=" + bits + " words=" + words
                    + " blockLight=" + (ebs.func_76661_k() != null)
                    + " skyLight=" + (ebs.func_76671_l() != null)
                    + " emptyFlag=" + ebs.func_76663_a());
        }
        System.out.println("  writtenSections=" + written
                + " tileEntities=" + ch.func_177434_r().size());

        // ---- measured runs ----
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode(handoff);
        ByteBuf buf = Unpooled.buffer(262144);
        long sink = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.println("trial,epoch_ms,wall_ms,pop_total_ns,ser_ns,stage_ns,predict_ns,alloc_ns,jni_ns,acquire_ns");
            // warmup
            for (int i = 0; i < 1000; i++) {
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                NativeChunkPacket.populatePacket(p, ch, mask);
                buf.clear(); PacketBuffer pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                sink += buf.readableBytes();
            }
            for (int t = 0; t < trials; t++) {
                long[] s0 = snap();
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                NativeChunkPacket.populatePacket(p, ch, mask);
                long t1 = System.nanoTime();
                buf.clear(); PacketBuffer pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                long t2 = System.nanoTime();
                long[] s1 = snap();
                pw.println(t + "," + (t0 / 1_000_000) + "," + System.currentTimeMillis() + "," + (t1 - t0) + "," + (t2 - t1)
                        + "," + (s1[0] - s0[0]) + "," + (s1[1] - s0[1]) + "," + (s1[2] - s0[2])
                        + "," + (s1[3] - s0[3]) + "," + (s1[4] - s0[4]));
                sink += buf.readableBytes();
            }
        } finally {
            buf.release();
            NativeChunkPacket.setRuntimeMode("OFF");
        }
        System.err.println("[DetFixtureDiagnosis] " + which + " handoff=" + handoff
                + " trials=" + trials + " sink=" + sink + " -> " + out);
    }

    static long[] snap() throws Exception {
        return new long[] {
            counter("M1_STAGE_NS"),
            counter("M1_PAYLOAD_PREDICT_NS"),
            counter("M1_ALLOC_NS"),
            counter("M1_JNI_RUST_NS"),
            counter("M1_NETTY_BUFFER_ACQUIRE_NS"),
        };
    }

    static long counter(String name) throws Exception {
        Field f = NativeChunkPacket.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(null)).get();
    }
}
