package com.rustcraft.bench;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

public class JavaNbtBench {
    public static void main(String[] args) throws Exception {
        System.out.println("Java Reference NBT Benchmark (JDK 8 / CompressedStreamTools)...");

        File corpusFile = new File("benchmarks/nbt/p0-4/test_corpus.bin");
        if (!corpusFile.exists()) {
            System.err.println("Corpus not found: " + corpusFile.getAbsolutePath());
            return;
        }

        List<byte[]> chunks = new ArrayList<byte[]>();
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(corpusFile)))) {
            int count = dis.readInt();
            System.out.println("Loading " + count + " reference chunks from corpus...");
            for (int i = 0; i < count; i++) {
                int len = dis.readInt();
                byte[] b = new byte[len];
                dis.readFully(b);
                chunks.add(b);
            }
        }

        int n = Math.min(chunks.size(), 847); // Standard benchmark sample size
        System.out.println("Testing on " + n + " chunks...");

        // 1. Warmup
        for (int i = 0; i < 100; i++) {
            byte[] raw = chunks.get(i % n);
            NBTTagCompound tag = CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(raw)));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CompressedStreamTools.func_74800_a(tag, new DataOutputStream(baos));
        }

        // 2. Parse Benchmark
        long[] parseTimes = new long[n];
        List<NBTTagCompound> parsedTags = new ArrayList<NBTTagCompound>(n);
        long totalBytes = 0;

        for (int i = 0; i < n; i++) {
            byte[] raw = chunks.get(i);
            totalBytes += raw.length;
            long t0 = System.nanoTime();
            NBTTagCompound tag = CompressedStreamTools.func_74794_a(new DataInputStream(new ByteArrayInputStream(raw)));
            long t1 = System.nanoTime();
            parseTimes[i] = t1 - t0;
            parsedTags.add(tag);
        }

        // 3. Serialize Benchmark
        long[] serializeTimes = new long[n];
        long totalSerializedBytes = 0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);

        for (int i = 0; i < n; i++) {
            NBTTagCompound tag = parsedTags.get(i);
            baos.reset();
            DataOutputStream dos = new DataOutputStream(baos);
            long t0 = System.nanoTime();
            CompressedStreamTools.func_74800_a(tag, dos);
            long t1 = System.nanoTime();
            serializeTimes[i] = t1 - t0;
            totalSerializedBytes += baos.size();
        }

        Arrays.sort(parseTimes);
        Arrays.sort(serializeTimes);

        double parseMeanMs = calcMean(parseTimes) / 1e6;
        double parseP50Ms = parseTimes[(int)(n * 0.50)] / 1e6;
        double parseP95Ms = parseTimes[(int)(n * 0.95)] / 1e6;
        double parseP99Ms = parseTimes[(int)(n * 0.99)] / 1e6;
        double parseMaxMs = parseTimes[n - 1] / 1e6;

        double serMeanMs = calcMean(serializeTimes) / 1e6;
        double serP50Ms = serializeTimes[(int)(n * 0.50)] / 1e6;
        double serP95Ms = serializeTimes[(int)(n * 0.95)] / 1e6;
        double serP99Ms = serializeTimes[(int)(n * 0.99)] / 1e6;
        double serMaxMs = serializeTimes[n - 1] / 1e6;

        double totalMb = (double) totalBytes / (1024.0 * 1024.0);
        double totalParseSec = (calcSum(parseTimes) / 1e9);
        double parseThroughput = totalMb / totalParseSec;

        double totalSerMb = (double) totalSerializedBytes / (1024.0 * 1024.0);
        double totalSerSec = (calcSum(serializeTimes) / 1e9);
        double serThroughput = totalSerMb / totalSerSec;

        System.out.println("\n=== Java Reference Deserializer Results ===");
        System.out.printf("Parse Latency: Mean=%.4f ms, p50=%.4f ms, p95=%.4f ms, p99=%.4f ms, Max=%.4f ms\n",
                parseMeanMs, parseP50Ms, parseP95Ms, parseP99Ms, parseMaxMs);
        System.out.printf("Parse Throughput: %.2f MB/s\n", parseThroughput);

        System.out.printf("Serialize Latency: Mean=%.4f ms, p50=%.4f ms, p95=%.4f ms, p99=%.4f ms, Max=%.4f ms\n",
                serMeanMs, serP50Ms, serP95Ms, serP99Ms, serMaxMs);
        System.out.printf("Serialize Throughput: %.2f MB/s\n", serThroughput);

        // Write YAML report
        File outYaml = new File("benchmarks/nbt/p0-4/java_reference_benchmark.yaml");
        try (FileWriter fw = new FileWriter(outYaml)) {
            fw.write("java_reference_codec:\n");
            fw.write("  implementation: \"net.minecraft.nbt.CompressedStreamTools (Java 8)\"\n");
            fw.write(String.format("  sample_chunks: %d\n", n));
            fw.write(String.format("  total_uncompressed_mb: %.2f\n", totalMb));
            fw.write("  parse_ms:\n");
            fw.write(String.format("    mean: %.4f\n", parseMeanMs));
            fw.write(String.format("    p50: %.4f\n", parseP50Ms));
            fw.write(String.format("    p95: %.4f\n", parseP95Ms));
            fw.write(String.format("    p99: %.4f\n", parseP99Ms));
            fw.write(String.format("    max: %.4f\n", parseMaxMs));
            fw.write(String.format("  parse_throughput_mb_s: %.2f\n", parseThroughput));
            fw.write("  serialize_ms:\n");
            fw.write(String.format("    mean: %.4f\n", serMeanMs));
            fw.write(String.format("    p50: %.4f\n", serP50Ms));
            fw.write(String.format("    p95: %.4f\n", serP95Ms));
            fw.write(String.format("    p99: %.4f\n", serP99Ms));
            fw.write(String.format("    max: %.4f\n", serMaxMs));
            fw.write(String.format("  serialize_throughput_mb_s: %.2f\n", serThroughput));
        }
        System.out.println("Written YAML to: " + outYaml.getAbsolutePath());
    }

    private static double calcMean(long[] array) {
        return (double) calcSum(array) / array.length;
    }

    private static long calcSum(long[] array) {
        long sum = 0;
        for (long v : array) sum += v;
        return sum;
    }
}
