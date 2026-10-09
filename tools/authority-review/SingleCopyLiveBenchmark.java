package com.rustcraft.authority;

import com.rustcraft.bridge.PacketEncodeResultV2;
import com.rustcraft.bridge.SingleCopyChunkBody;
import com.rustcraft.bridge.SingleCopyPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Live-path benchmark: server-thread packet emission cost for
 *
 *   A. LEGACY_HEAP       - vanilla 1.12.2 shape: native payload -> Java heap byte[]
 *                          -> PacketBuffer.writeBytes into a pooled direct buffer (2 copies)
 *   B. POOLED_TWO_COPY   - b3df84c shipped path: native payload -> pooled direct ByteBuf
 *                          -> PacketBuffer.writeBytes into the packet buffer (2 copies)
 *   C. SINGLE_COPY_LIVE  - the NEW live classes (SingleCopyChunkBody.build, exactly what
 *                          the live authority admission runs): measure + header + ONE
 *                          native write into the final buffer + TE trailer (1 copy)
 *
 * The corpus is four genuinely different seeded chunks; the wire length is
 * MEASURED per chunk (never a hardcoded size), so each bucket is honest.
 * 3 runs x 10,000 iterations per mode per bucket; warmup excluded.
 */
public class SingleCopyLiveBenchmark {

    private static final int WARMUP = 2_000;
    private static final int ITERS = 10_000;
    private static final int RUNS = 3;
    private static final int CAPACITY = 262144;

    private static int packetId = 32; // verified against EnumConnectionState when available

    public static void main(String[] args) throws Exception {
        System.out.println("=== RustCraft Live Single-Copy vs b3df84c Pooled vs Legacy Heap ===");
        loadNativeLibrary();
        packetId = resolvePacketId();

        String[] bucketNames = {"Small", "Median", "p95", "Max"};
        int[][] corpus = new int[][] {
                {30001, 2},   // ~12.4 KB bucket
                {30002, 5},   // ~32.8 KB bucket
                {30003, 8},   // ~49.5 KB bucket
                {30004, 13},  // ~82.5 KB bucket
        };

        // Seed one chunk per bucket and measure its true wire length.
        long[] gens = new long[corpus.length];
        int[] wireLens = new int[corpus.length];
        for (int i = 0; i < corpus.length; i++) {
            gens[i] = seedChunk(corpus[i][0], 30010 + i, corpus[i][1]);
            if (gens[i] <= 0) {
                throw new IllegalStateException("seed failed for bucket " + bucketNames[i]);
            }
            long packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2Measure(
                    0, corpus[i][0], 30010 + i, gens[i], (byte) 1, (byte) 1);
            PacketEncodeResultV2 m = PacketEncodeResultV2.decode(packed);
            if (!m.isSuccess()) {
                throw new IllegalStateException("measure failed for bucket " + bucketNames[i]);
            }
            wireLens[i] = m.bytesWritten();
        }
        System.out.println("corpus wire lengths (measured): "
                + Arrays.toString(wireLens) + " bytes");

        StringBuilder report = new StringBuilder();
        for (int i = 0; i < corpus.length; i++) {
            String bucket = String.format("%s (%.1f KB)", bucketNames[i], wireLens[i] / 1024.0);
            report.append(String.format("%n== bucket %s: wire=%d bytes ==========%n", bucket, wireLens[i]));
            int cx = corpus[i][0], cz = 30010 + i;
            long gen = gens[i];

            for (int run = 0; run < RUNS; run++) {
                long[] a = runLegacyHeap(cx, cz, gen, wireLens[i]);
                long[] b = runPooledTwoCopy(cx, cz, gen, wireLens[i]);
                long[] c = runSingleCopyLive(cx, cz, gen);
                report.append(String.format(
                        "run %d | A legacy   mean=%8.0f p50=%8.0f p95=%8.0f p99=%8.0f sd=%7.0f ns%n",
                        run, stats(a, 0), stats(a, 1), stats(a, 2), stats(a, 3), stats(a, 4)));
                report.append(String.format(
                        "run %d | B pooled    mean=%8.0f p50=%8.0f p95=%8.0f p99=%8.0f sd=%7.0f ns%n",
                        run, stats(b, 0), stats(b, 1), stats(b, 2), stats(b, 3), stats(b, 4)));
                report.append(String.format(
                        "run %d | C single    mean=%8.0f p50=%8.0f p95=%8.0f p99=%8.0f sd=%7.0f ns%n",
                        run, stats(c, 0), stats(c, 1), stats(c, 2), stats(c, 3), stats(c, 4)));
            }
        }
        System.out.println(report);

        System.out.println("SingleCopyPipeline telemetry after benchmark: "
                + SingleCopyPipeline.telemetrySummary());

        for (int i = 0; i < corpus.length; i++) {
            com.rustcraft.bridge.NativeChunkBridge.unload(0, corpus[i][0], 30010 + i);
        }
    }

    // ------------------------------------------------------------------
    // A. legacy heap: encode -> scratch -> heap byte[] -> PacketBuffer writes
    // ------------------------------------------------------------------
    private static long[] runLegacyHeap(int cx, int cz, long gen, int wireLen) {
        ByteBuffer scratch = ByteBuffer.allocateDirect(CAPACITY).order(ByteOrder.nativeOrder());
        long scratchAddr = addressOf(scratch);
        for (int i = 0; i < WARMUP; i++) {
            emitLegacyHeap(cx, cz, gen, scratch, scratchAddr);
        }
        long[] times = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            emitLegacyHeap(cx, cz, gen, scratch, scratchAddr);
            times[i] = System.nanoTime() - t0;
        }
        return times;
    }

    private static void emitLegacyHeap(int cx, int cz, long gen, ByteBuffer scratch, long scratchAddr) {
        long packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                0, cx, cz, gen, (byte) 1, (byte) 1, scratchAddr, CAPACITY);
        PacketEncodeResultV2 r = PacketEncodeResultV2.decode(packed);
        if (!r.isSuccess()) throw new IllegalStateException("legacy encode failed");
        byte[] heapPayload = new byte[r.bytesWritten()];
        scratch.position(0);
        scratch.get(heapPayload);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(1024 + heapPayload.length);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeBoolean(true);
        writeVarInt(out, r.emittedMask());
        writeVarInt(out, heapPayload.length);
        out.writeBytes(heapPayload);
        writeVarInt(out, 0);
        out.release();
    }

    // ------------------------------------------------------------------
    // B. b3df84c pooled two-copy: encode -> pooled directBuf -> PacketBuffer
    // ------------------------------------------------------------------
    private static long[] runPooledTwoCopy(int cx, int cz, long gen, int wireLen) {
        for (int i = 0; i < WARMUP; i++) {
            emitPooledTwoCopy(cx, cz, gen);
        }
        long[] times = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            emitPooledTwoCopy(cx, cz, gen);
            times[i] = System.nanoTime() - t0;
        }
        return times;
    }

    private static void emitPooledTwoCopy(int cx, int cz, long gen) {
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(CAPACITY);
        long packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                0, cx, cz, gen, (byte) 1, (byte) 1, directBuf.memoryAddress(), CAPACITY);
        PacketEncodeResultV2 r = PacketEncodeResultV2.decode(packed);
        if (!r.isSuccess()) {
            directBuf.release();
            throw new IllegalStateException("pooled encode failed");
        }
        directBuf.writerIndex(r.bytesWritten());
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(1024 + r.bytesWritten());
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeBoolean(true);
        writeVarInt(out, r.emittedMask());
        writeVarInt(out, directBuf.readableBytes());
        out.writeBytes(directBuf); // copy #2 (b3df84c live path)
        writeVarInt(out, 0);
        out.release();
        directBuf.release();
    }

    // ------------------------------------------------------------------
    // C. NEW live single-copy: the exact live admission classes
    // ------------------------------------------------------------------
    private static long[] runSingleCopyLive(int cx, int cz, long gen) {
        for (int i = 0; i < WARMUP; i++) {
            emitSingleCopyLive(cx, cz, gen);
        }
        long[] times = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            emitSingleCopyLive(cx, cz, gen);
            times[i] = System.nanoTime() - t0;
        }
        return times;
    }

    private static void emitSingleCopyLive(int cx, int cz, long gen) {
        Object packet = new Object(); // registry key (identity)
        SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                packet, 0, cx, cz, gen, true, true, packetId);
        if (ticket == null) {
            throw new IllegalStateException("single-copy build failed");
        }
        // The live path releases via promise+quiescence after the write; the
        // server-thread scope here ends at the complete body, so release now.
        ticket.invalidate();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static double stats(long[] times, int what) {
        long[] copy = times.clone();
        Arrays.sort(copy);
        int n = copy.length;
        switch (what) {
            case 0: {
                double sum = 0;
                for (long t : copy) sum += t;
                return sum / n;
            }
            case 1: return copy[(int) (n * 0.50)];
            case 2: return copy[(int) (n * 0.95)];
            case 3: return copy[(int) (n * 0.99)];
            default: {
                double mean = stats(copy, 0);
                double var = 0;
                for (long t : copy) var += (t - mean) * (t - mean);
                return Math.sqrt(var / n);
            }
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int resolvePacketId() {
        try {
            Object packet = new net.minecraft.network.play.server.SPacketChunkData();
            Class<?> stateCls = Class.forName("net.minecraft.network.EnumConnectionState");
            Object play = stateCls.getField("PLAY").get(null);
            Object dir = Class.forName("net.minecraft.network.EnumPacketDirection")
                    .getField("CLIENTBOUND").get(null);
            java.lang.reflect.Method m = stateCls.getMethod("func_179246_a",
                    Class.forName("net.minecraft.network.EnumPacketDirection"),
                    Class.forName("net.minecraft.network.Packet"));
            return (Integer) m.invoke(play, dir, packet);
        } catch (Throwable t) {
            System.out.println("[warn] packet id lookup unavailable, using 32: " + t);
            return 32;
        }
    }

    private static long seedChunk(int cx, int cz, int sections) {
        byte[] transport = buildTransport(cx, cz, sections);
        ByteBuffer in = ByteBuffer.allocateDirect(transport.length).order(ByteOrder.nativeOrder());
        in.put(transport);
        return com.rustcraft.bridge.NativeChunkBridge.seedFromTransport(
                addressOf(in), transport.length);
    }

    private static byte[] buildTransport(int cx, int cz, int sections) {
        ByteBuffer buf = ByteBuffer.allocate(262144).order(ByteOrder.BIG_ENDIAN);
        buf.put("RCSNAP02".getBytes());
        buf.putShort((short) 2);
        buf.put((byte) 3);
        buf.put((byte) 1);
        buf.put((byte) 18);
        buf.put((byte) 1);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putInt(cx);
        buf.putInt(cz);
        buf.putLong(1L);
        buf.putShort((short) 0xffff);
        buf.putShort((short) ((1 << sections) - 1));
        for (int i = 0; i < 7; i++) buf.putLong(1L);
        buf.put(new byte[32]);
        buf.putShort((short) sections);
        buf.putInt(157010);
        buf.put((byte) 18);
        for (int y = 0; y < sections; y++) {
            buf.put((byte) y);
            buf.put((byte) 0);
            buf.putShort((short) 4096);
            buf.putShort((short) 4);
            buf.putShort((short) 1).putShort((short) 3).putShort((short) 2).putShort((short) 4);
            buf.put((byte) 4);
            buf.putShort((short) 256);
            for (int w = 0; w < 256; w++) buf.putLong(0x0123012301230123L);
            buf.put(new byte[2048]);
            buf.put(new byte[2048]);
        }
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 4);
        buf.put(biomes);
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static long addressOf(ByteBuffer buf) {
        try {
            java.lang.reflect.Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(buf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void loadNativeLibrary() {
        java.nio.file.Path[] candidates = new java.nio.file.Path[] {
                java.nio.file.Paths.get("target/release/rustcraft_ffi.dll"),
                java.nio.file.Paths.get("rustcraft_ffi.dll"),
                java.nio.file.Paths.get("c:/rustcraft/target/release/rustcraft_ffi.dll"),
        };
        for (java.nio.file.Path p : candidates) {
            if (java.nio.file.Files.exists(p)) {
                System.load(p.toAbsolutePath().toString());
                return;
            }
        }
        throw new IllegalStateException("rustcraft_ffi.dll not found");
    }
}
