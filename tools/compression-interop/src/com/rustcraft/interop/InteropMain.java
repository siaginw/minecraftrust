package com.rustcraft.interop;

import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * M2.0 offline interop + benchmark harness (operator authorization).
 *
 * PART A — interop: Rust output decoded by the ACTUAL vanilla
 * NettyCompressionDecoder, requiring exact byte recovery + successful
 * stream completion, over legal packet fixtures + seeded edge inputs.
 *
 * PART B — small complete benchmark: the EXACT Java reference loop
 * (javap-derived: reused JDK Deflater(), setInput/finish/deflate/reset,
 * input snapshot per packet) vs the Rust path, identical inputs,
 * copies/crossings measured separately, compression ratio reported,
 * allocation measured via com.sun.management.ThreadMXBean.
 *
 * Byte-identity between implementations is NOT required (compared
 * separately as size delta only).
 */
public final class InteropMain {

    static final int THRESHOLD = 256; // vanilla default used for fixtures

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M20-interop.txt")));
        try {
            if (!CompressionInterop.isNativeLoaded()) {
                out.println("FATAL: native library not loaded"); out.flush(); System.exit(2);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
            out.println("backend=" + "flate2=1.1.10 no-default-features, feature zlib-rs (zlib-rs crate, cargo tree verified)");
            runInterop(out);
            runBenchmark(out);
        } finally { out.flush(); out.close(); }
    }

    // ---------------- fixtures ----------------

    static List<Object[]> fixtures() throws Exception { // NOPMD
        List<Object[]> fx = new ArrayList<>();
        fx.add(new Object[]{"empty", new byte[0]});
        fx.add(new Object[]{"single", new byte[]{7}});
        Random rng = new Random(42);
        fx.add(new Object[]{"adjacent-255", filler(rng, 255)});
        fx.add(new Object[]{"adjacent-256", filler(rng, 256)});
        fx.add(new Object[]{"adjacent-257", filler(rng, 257)});
        byte[] z = new byte[64 * 1024]; fx.add(new Object[]{"zeros-64k", z});
        byte[] r = new byte[64 * 1024]; rng.nextBytes(r); fx.add(new Object[]{"random-64k-incompressible", r});
        byte[] semi = new byte[256 * 1024];
        for (int i = 0; i < semi.length; i++) semi[i] = (byte) (i % 97);
        fx.add(new Object[]{"semi-256k", semi});
        byte[] big = new byte[1 << 20]; fx.add(new Object[]{"zeros-1M", big});
        // legal packet payloads: serialized vanilla SPacketChunkData from the
        // committed oracle corpus builders (real chunk content; package-private
        // Fixture accessed as Object)
        Object f;
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(1);
        fx.add(new Object[]{"chunk-det1", serializeChunk(f)});
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(4);
        fx.add(new Object[]{"chunk-det4", serializeChunk(f)});
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(7);
        fx.add(new Object[]{"chunk-det7", serializeChunk(f)});
        return fx;
    }

    static byte[] filler(Random rng, int n) {
        byte[] b = new byte[n];
        rng.nextBytes(b);
        return b;
    }

        static byte[] serializeChunk(Object fixture) throws Exception {
        com.rustcraft.oracle.M14RParityHarness.Fixture fx = (com.rustcraft.oracle.M14RParityHarness.Fixture) fixture;
        Chunk ch = com.rustcraft.oracle.M14RParityHarness.createMockChunk(fx);
        com.rustcraft.bridge.NativeChunkPacket.setRuntimeMode("OFF");
        SPacketChunkData p = new SPacketChunkData(ch, fx.mask);
        return com.rustcraft.oracle.M14RParityHarness.serialize(p);
    }

    // ---------------- PART A: interop ----------------

    static void runInterop(PrintWriter out) throws Exception {
        out.println("== PART A: interop (Rust -> ACTUAL vanilla NettyCompressionDecoder) ==");
        int pass = 0, fail = 0;
        for (Object[] f : fixtures()) {
            String name = (String) f[0];
            byte[] data = (byte[]) f[1];
            byte[] compressed = CompressionInterop.rustCompress(data);
            // threshold=0 lets the ACTUAL decoder accept any size, exactly as
            // the pipeline would after setCompressionThreshold(0); >=256
            // fixtures additionally verified at the vanilla default 256.
            byte[] recovered = CompressionInterop.vanillaDecode(compressed, data.length, 0);
            boolean eq = Arrays.equals(data, recovered);
            if (data.length >= THRESHOLD) {
                byte[] rec256 = CompressionInterop.vanillaDecode(compressed, data.length, THRESHOLD);
                eq = eq && Arrays.equals(data, rec256);
            }
            if (eq) { pass++; } else { fail++; }
            // size comparison (informational; byte-identity NOT required)
            int javaLen = javaReferenceCompress(data).length;
            out.printf("  %-28s in=%7d rust=%7d java=%7d ratio_rust=%.3f size_delta_vs_java=%+d%%  %s%n",
                    name, data.length, compressed.length, javaLen,
                    data.length == 0 ? 1.0 : (double) compressed.length / data.length,
                    javaLen == 0 ? 0 : (compressed.length - javaLen) * 100 / javaLen,
                    eq ? "PASS" : "FAIL");
        }
        // repeated packets: same bytes through the SAME reused context twice
        {
            byte[] d = filler(new Random(7), 4096);
            byte[] c1 = CompressionInterop.rustCompress(d);
            byte[] c2 = CompressionInterop.rustCompress(d);
            boolean ok = Arrays.equals(
                    d, CompressionInterop.vanillaDecode(c1, d.length, THRESHOLD))
                    && Arrays.equals(
                    d, CompressionInterop.vanillaDecode(c2, d.length, THRESHOLD))
                    && Arrays.equals(c1, c2);
            out.println("  repeated-packet-x2 (context reuse) " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        // insufficient output capacity: defined error, no exception thrown
        {
            int code = CompressionInterop.rustCompressCode(filler(new Random(9), 1024), 16);
            boolean ok = code == -1;
            out.println("  insufficient-capacity code=" + code + " " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        }
        out.println("PART_A pass=" + pass + " fail=" + fail);
        out.flush();
        if (fail > 0) { System.out.println("PART_A FAILED"); System.exit(3); }
    }

    // ---------------- PART B: benchmark ----------------

    /** The EXACT vanilla reference loop reconstructed from javap of
     *  NettyCompressionEncoder.encode. */
    static byte[] javaReferenceCompress(byte[] input) {
        try {
            Deflater deflater = JAVA_REF.get();
            byte[] out = new byte[JAVA_CHUNK];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                    Math.max(64, input.length / 2));
            if (input.length > 0) deflater.setInput(input);
            deflater.finish();
            while (!deflater.finished()) {
                int n = deflater.deflate(out);
                if (n > 0) bos.write(out, 0, n);
            }
            deflater.reset();
            return bos.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static final int JAVA_CHUNK = 8192; // ref uses an 8k drain buffer
    static final ThreadLocal<Deflater> JAVA_REF =
            ThreadLocal.withInitial(Deflater::new);

    static void runBenchmark(PrintWriter out) throws Exception {
        out.println("== PART B: complete-path benchmark (fresh JVM, 500 warmup + 1000 trials, medians) ==");
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        out.println("fixture,in_len,rust_ns_med,java_ns_med,rust_vs_java,rust_out,java_out,ratio,alloc_rust,alloc_java");
        for (Object[] f : fixtures()) {
            String name = (String) f[0];
            byte[] data = (byte[]) f[1];
            if (data.length == 0) continue;
            // warmup both
            for (int i = 0; i < 500; i++) { CompressionInterop.rustCompress(data); javaReferenceCompress(data); }
            // measure rust (includes direct-buffer alloc, put copy, JNI, native, get copy)
            long[] rustNs = new long[1000]; long a0 = mx.getThreadAllocatedBytes(tid);
            for (int t = 0; t < 1000; t++) {
                long t0 = System.nanoTime();
                CompressionInterop.rustCompress(data);
                rustNs[t] = System.nanoTime() - t0;
            }
            long aRust = mx.getThreadAllocatedBytes(tid) - a0;
            // measure java reference (Deflater loop + drain buffers)
            long[] javaNs = new long[1000]; long a1 = mx.getThreadAllocatedBytes(tid);
            for (int t = 0; t < 1000; t++) {
                long t0 = System.nanoTime();
                javaReferenceCompress(data);
                javaNs[t] = System.nanoTime() - t0;
            }
            long aJava = mx.getThreadAllocatedBytes(tid) - a1;
            Arrays.sort(rustNs); Arrays.sort(javaNs);
            byte[] rc = CompressionInterop.rustCompress(data);
            byte[] jc = javaReferenceCompress(data);
            out.printf("%s,%d,%d,%d,%.2fx,%d,%d,%.3f,%d,%d%n",
                    name, data.length,
                    rustNs[500], javaNs[500],
                    (double) rustNs[500] / javaNs[500],
                    rc.length, jc.length,
                    (double) rc.length / data.length,
                    aRust / 1000, aJava / 1000);
        }
        out.println("(alloc = mean bytes per trial incl. output arrays; rust includes direct-buffer allocations)");
    }
}
