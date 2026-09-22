package com.rustcraft.bench;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class JniBench {
    static {
        File dll = new File("tools/jni-bench/jni_bench.dll");
        if (!dll.exists()) {
            throw new RuntimeException("DLL not found: " + dll.getAbsolutePath());
        }
        System.load(dll.getAbsolutePath());
    }

    public static native long noopCall();
    public static native long benchByteArrayRegion(byte[] arr, int len);
    public static native long benchByteArrayCritical(byte[] arr, int len);
    public static native long benchDirectByteBuffer(ByteBuffer buf, int len);
    public static native ByteBuffer benchRustAllocDirectBuffer(int size);
    public static native long benchPersistentHandle(long handle, int offset, int len);
    public static native long getNativeHandle();

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Corrected JNI Microbenchmark Suite v2 (JDK 8 / x86_64 native)...");
        System.out.println("Methodology: Batched samples to eliminate 100ns timer quantization, full payload checksum touch.");

        // Warmup JIT
        for (int i = 0; i < 100000; i++) {
            noopCall();
        }

        int[] sizes = {
            2 * 1024,
            8 * 1024,
            32 * 1024,
            64 * 1024,
            256 * 1024,
            1024 * 1024
        };

        StringBuilder yaml = new StringBuilder();
        yaml.append("environment:\n");
        yaml.append("  jvm: \"Java 8 Temurin (jdk-8.0.504.1-hotspot)\"\n");
        yaml.append("  arch: \"x86_64\"\n");
        yaml.append("  os: \"Windows 11\"\n");
        yaml.append("  compiler: \"rustc 1.94.0 -O\"\n");
        yaml.append("  methodology: \"batched inner loops (10-1000 iters per sample) to defeat 100ns timer floor, full memory scan\"\n");
        yaml.append("baseline_overhead:\n");

        // Measure noop call overhead with batching (batch size 1000)
        int noopBatches = 1000;
        int noopBatchSize = 1000;
        double[] noopPerCallNs = new double[noopBatches];
        for (int i = 0; i < noopBatches; i++) {
            long t0 = System.nanoTime();
            long s = 0;
            for (int k = 0; k < noopBatchSize; k++) {
                s += noopCall();
            }
            long t1 = System.nanoTime();
            noopPerCallNs[i] = (double)(t1 - t0) / noopBatchSize;
        }
        Arrays.sort(noopPerCallNs);
        double noopMean = calcMean(noopPerCallNs);
        yaml.append("  noop_call_ns:\n");
        yaml.append(String.format("    mean: %.2f\n", noopMean));
        yaml.append(String.format("    p50: %.2f\n", noopPerCallNs[(int)(noopBatches * 0.50)]));
        yaml.append(String.format("    p95: %.2f\n", noopPerCallNs[(int)(noopBatches * 0.95)]));
        yaml.append(String.format("    p99: %.2f\n", noopPerCallNs[(int)(noopBatches * 0.99)]));
        yaml.append(String.format("    min: %.2f\n", noopPerCallNs[0]));
        yaml.append(String.format("    max: %.2f\n", noopPerCallNs[noopBatches - 1]));

        System.out.printf("JNI No-op baseline call overhead: mean=%.2f ns, p50=%.2f ns, p99=%.2f ns\n",
                noopMean, noopPerCallNs[(int)(noopBatches * 0.50)], noopPerCallNs[(int)(noopBatches * 0.99)]);

        long nativeHandle = getNativeHandle();

        yaml.append("mechanisms:\n");

        String[] mechNames = {
            "A_byte_array_region_copy",
            "B_byte_array_critical_pin",
            "C_direct_byte_buffer",
            "D_rust_alloc_direct_buffer",
            "E_persistent_native_handle"
        };

        for (String mech : mechNames) {
            yaml.append("  ").append(mech).append(":\n");
            System.out.println("\nBenchmarking mechanism: " + mech);

            for (int size : sizes) {
                int batchSize;
                int samples;
                if (size <= 8 * 1024) {
                    batchSize = 200;
                    samples = 200;
                } else if (size <= 64 * 1024) {
                    batchSize = 50;
                    samples = 150;
                } else if (size <= 256 * 1024) {
                    batchSize = 10;
                    samples = 100;
                } else {
                    batchSize = 5;
                    samples = 50;
                }

                double[] perCallNs = new double[samples];

                byte[] javaArray = new byte[size];
                Arrays.fill(javaArray, (byte) 0x5A);
                ByteBuffer directBuf = ByteBuffer.allocateDirect(size);
                for (int b = 0; b < size; b++) directBuf.put((byte) 0x5A);
                directBuf.flip();

                // Warmup
                for (int w = 0; w < 30; w++) {
                    runBatch(mech, javaArray, directBuf, nativeHandle, size, 5);
                }

                // Measurement
                for (int i = 0; i < samples; i++) {
                    long t0 = System.nanoTime();
                    long s = runBatch(mech, javaArray, directBuf, nativeHandle, size, batchSize);
                    long t1 = System.nanoTime();
                    if (s == 123456789L) System.out.print(""); // Prevent dead-code elimination
                    perCallNs[i] = (double)(t1 - t0) / batchSize;
                }

                Arrays.sort(perCallNs);
                double meanNs = calcMean(perCallNs);
                double p50Ns = perCallNs[(int)(samples * 0.50)];
                double p95Ns = perCallNs[(int)(samples * 0.95)];
                double p99Ns = perCallNs[(int)(samples * 0.99)];
                double minNs = perCallNs[0];
                double maxNs = perCallNs[samples - 1];

                double throughputMBs = (double) size / (meanNs / 1e9) / (1024.0 * 1024.0);

                yaml.append(String.format("    size_%d_kib:\n", size / 1024));
                yaml.append(String.format("      payload_bytes: %d\n", size));
                yaml.append(String.format("      batch_size: %d\n", batchSize));
                yaml.append(String.format("      samples: %d\n", samples));
                yaml.append(String.format("      latency_ns:\n"));
                yaml.append(String.format("        mean: %.2f\n", meanNs));
                yaml.append(String.format("        p50: %.2f\n", p50Ns));
                yaml.append(String.format("        p95: %.2f\n", p95Ns));
                yaml.append(String.format("        p99: %.2f\n", p99Ns));
                yaml.append(String.format("        min: %.2f\n", minNs));
                yaml.append(String.format("        max: %.2f\n", maxNs));
                yaml.append(String.format("      throughput_mb_s: %.2f\n", throughputMBs));

                System.out.printf("  %4d KiB: mean=%.2f ns (%.3f µs), p50=%.2f ns, p95=%.2f ns, throughput=%.2f MB/s\n",
                        size / 1024, meanNs, meanNs / 1000.0, p50Ns, p95Ns, throughputMBs);
            }
        }

        File outDir = new File("benchmarks/ffi/p0-4-v2");
        outDir.mkdirs();
        File outFile = new File(outDir, "jni_microbenchmarks.yaml");
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(yaml.toString());
        }
        System.out.println("\nResults written to: " + outFile.getAbsolutePath());
    }

    private static long runBatch(String mech, byte[] arr, ByteBuffer buf, long handle, int size, int iters) {
        long sum = 0;
        if ("A_byte_array_region_copy".equals(mech)) {
            for (int i = 0; i < iters; i++) {
                sum += benchByteArrayRegion(arr, size);
            }
        } else if ("B_byte_array_critical_pin".equals(mech)) {
            for (int i = 0; i < iters; i++) {
                sum += benchByteArrayCritical(arr, size);
            }
        } else if ("C_direct_byte_buffer".equals(mech)) {
            for (int i = 0; i < iters; i++) {
                sum += benchDirectByteBuffer(buf, size);
            }
        } else if ("D_rust_alloc_direct_buffer".equals(mech)) {
            for (int i = 0; i < iters; i++) {
                ByteBuffer b = benchRustAllocDirectBuffer(size);
                sum += b.capacity();
            }
        } else if ("E_persistent_native_handle".equals(mech)) {
            for (int i = 0; i < iters; i++) {
                sum += benchPersistentHandle(handle, 0, size);
            }
        }
        return sum;
    }

    private static double calcMean(double[] arr) {
        double sum = 0;
        for (double d : arr) sum += d;
        return sum / arr.length;
    }
}
