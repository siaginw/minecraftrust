package com.rustcraft.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Java Deflater baseline on the captured compression corpus: the REAL
 * vanilla NettyCompressionEncoder (reused Deflater, JDK default level 6 —
 * exactly the production wire path), one channel per run (production
 * semantics). Mirrors the Rust corpus_bench bucket/metric structure so the
 * numbers are directly comparable: MB/s, per-packet mean/p50/p95/p99 ns,
 * compressed size and ratio per bucket.
 */
public class CompressionCorpusJavaBench {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CompressionCorpusJavaBench <corpus.bin> [warmup=1] [passes=3]");
            System.exit(2);
        }
        byte[] corpus = Files.readAllBytes(Path.of(args[0]));
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int passes = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        List<byte[]> bodies = new ArrayList<>();
        int off = 0;
        while (off + 4 <= corpus.length) {
            int len = ((corpus[off] & 0xFF) << 24) | ((corpus[off + 1] & 0xFF) << 16)
                    | ((corpus[off + 2] & 0xFF) << 8) | (corpus[off + 3] & 0xFF);
            off += 4;
            if (off + len > corpus.length) {
                break;
            }
            bodies.add(Arrays.copyOfRange(corpus, off, off + len));
            off += len;
        }
        System.out.println("corpus: " + bodies.size() + " packets, " + corpus.length + " bytes total");

        // size buckets
        List<Object[]> buckets = new ArrayList<>();
        buckets.add(new Object[]{"t0: 256-1023", idxBySize(bodies, 256, 1024)});
        buckets.add(new Object[]{"t1: 1k-4k", idxBySize(bodies, 1024, 4096)});
        buckets.add(new Object[]{"t2: 4k-16k", idxBySize(bodies, 4096, 16384)});
        buckets.add(new Object[]{"t3: 16k-64k", idxBySize(bodies, 16384, 65536)});
        buckets.add(new Object[]{"t4: 64k+", idxBySize(bodies, 65536, Integer.MAX_VALUE)});
        // entropy buckets via the JDK level-6 probe
        List<Integer> e0 = new ArrayList<>(), e1 = new ArrayList<>(), e2 = new ArrayList<>();
        {
            net.minecraft.network.NettyCompressionEncoder probe =
                    new net.minecraft.network.NettyCompressionEncoder(256);
            EmbeddedChannel pch = new EmbeddedChannel(probe);
            for (int i = 0; i < bodies.size(); i++) {
                byte[] b = bodies.get(i);
                if (b.length < 256) continue;
                byte[] out = encode(pch, b);
                double ratio = (double) out.length / b.length;
                (ratio < 0.30 ? e0 : ratio <= 0.80 ? e1 : e2).add(i);
            }
            pch.finishAndReleaseAll();
        }
        List<Object[]> entropy = new ArrayList<>();
        entropy.add(new Object[]{"e0: ratio<0.30", e0});
        entropy.add(new Object[]{"e1: 0.30-0.80", e1});
        entropy.add(new Object[]{"e2: >0.80", e2});

        runSet("size buckets", buckets, bodies, warmup, passes);
        runSet("entropy buckets", entropy, bodies, warmup, passes);
    }

    @SuppressWarnings("unchecked")
    private static void runSet(String setName, List<Object[]> sets, List<byte[]> bodies,
                               int warmup, int passes) {
        System.out.println("\n== " + setName + " ==");
        for (Object[] entry : sets) {
            String name = (String) entry[0];
            List<Integer> idx = (List<Integer>) entry[1];
            if (idx.isEmpty()) continue;
            // fresh encoder per bucket (one Deflater, reused per packet)
            net.minecraft.network.NettyCompressionEncoder enc =
                    new net.minecraft.network.NettyCompressionEncoder(256);
            EmbeddedChannel ch = new EmbeddedChannel(enc);
            long[] times = new long[idx.size()];
            long inTotal = 0, outTotal = 0;
            for (int w = 0; w < warmup; w++) {
                for (int i : idx) encode(ch, bodies.get(i));
            }
            long[] best = null;
            long bestMean = Long.MAX_VALUE;
            for (int p = 0; p < passes; p++) {
                long[] t = new long[idx.size()];
                long inT = 0, outT = 0;
                for (int k = 0; k < idx.size(); k++) {
                    byte[] body = bodies.get(idx.get(k));
                    long t0 = System.nanoTime();
                    byte[] out = encode(ch, body);
                    t[k] = System.nanoTime() - t0;
                    inT += body.length;
                    outT += out.length;
                }
                long mean = 0;
                for (long v : t) mean += v;
                mean /= Math.max(1, t.length);
                if (mean < bestMean) { bestMean = mean; best = t; bestIn = inT; bestOut = outT; }
                inTotal = bestIn; outTotal = bestOut;
            }
            long[] b = best;
            Arrays.sort(b);
            int n = b.length;
            double mean = Arrays.stream(b).average().orElse(0);
            long inB = inTotal / passes, outB = outTotal / passes;
            System.out.println(String.format(
                    "%-12s %-16s n=%-6d in=%-10d out=%-10d ratio=%.4f mean=%-9.0f p50=%-9.0f p95=%-9.0f p99=%-9.0f MB/s=%.1f",
                    name, "java-defl L6", n, inB, outB,
                    (double) outB / Math.max(1, inB), mean,
                    (double) b[n / 2],
                    (double) b[(int) (n * 0.95) % n],
                    (double) b[(int) (n * 0.99) % n],
                    mean > 0 ? (inB / mean) * 1000.0 : 0.0));
            ch.finishAndReleaseAll();
        }
    }

    private static long bestIn, bestOut;

    private static List<Integer> idxBySize(List<byte[]> bodies, int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            int len = bodies.get(i).length;
            if (len >= lo && len < hi) out.add(i);
        }
        return out;
    }

    /** Encode one body through the REAL NettyCompressionEncoder; returns the framed output. */
    private static byte[] encode(EmbeddedChannel ch, byte[] body) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(body.length + 16);
        buf.writeBytes(body);
        ch.writeOutbound(buf);
        ByteBuf frame = ch.readOutbound();
        byte[] out = new byte[frame.readableBytes()];
        frame.readBytes(out);
        frame.release();
        return out;
    }
}
