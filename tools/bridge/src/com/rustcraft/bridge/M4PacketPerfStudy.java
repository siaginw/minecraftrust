package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * M4.3 §11 — MATCHED performance study, same packet corpus, CURRENT build:
 *
 *   A. CURRENT JAVA:   Chunk -> SPacketChunkData(chunk, 65535) constructor
 *                      (section extraction + palette serialization + TE tags).
 *   B. OLD M1 STAGING: Java extraction into staging buffer -> native
 *                      predict + encode into packet array (the M1.4-R2 handoff-A
 *                      path), driven through NativeChunkPacket's own private
 *                      machinery by reflection.
 *   C. M1_NATIVE:      valid synchronized NativeChunk -> Rust encode -> payload.
 *
 * Reports p50/p95/p99/mean us per packet, MB/s, payload bytes; JNI crossings and
 * allocation counts are structural (documented constants of each path).
 */
public class M4PacketPerfStudy {

    static final int CHUNKS = 32;
    static final int ITERS_PER_CHUNK = 200;
    static final int WARMUP = 50;

    public static void main(String[] args) throws Exception {
        System.setProperty("minecraftrust.m1.native_state", "OFF"); // isolate measurement
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        // Corpus: CHUNKS real chunks, each registered + synced (so C is valid)
        Object[] chunks = new Object[CHUNKS];
        long[] gens = new long[CHUNKS];
        long totalPayloadBytes = 0;
        for (int i = 0; i < CHUNKS; i++) {
            Object[] r = M4PacketParityHarness.baseChunkAndPrimer(200 + i, 20);
            chunks[i] = r[0];
            gens[i] = NativeChunkBridge.register(0, 200 + i, 20, addr((ByteBuffer) r[1]), 0L);
            M4Coherency.refreshChunkNow(r[0]);
            Object pkt = M4PacketParityHarness.packetCtor().newInstance(r[0], 65535);
            totalPayloadBytes += ((byte[]) M4PacketParityHarness.pktPayloadField().get(pkt)).length;
        }
        double meanBytes = totalPayloadBytes / (double) CHUNKS;
        System.out.printf("corpus: %d chunks, mean java payload %.0f B%n", CHUNKS, meanBytes);

        long[] a = measureA(chunks);
        long[] c = measureC(chunks, gens);
        long[] b = measureB(chunks); // may be null if reflection surface unavailable

        report("A current-java-ctor", a, meanBytes);
        report("B old-m1-staging", b, meanBytes);
        report("C native-state-encode", c, meanBytes);
    }

    static void report(String name, long[] t, double bytes) {
        if (t == null) { System.out.println(name + ": N/A (path unavailable offline)"); return; }
        long[] s = t.clone(); Arrays.sort(s);
        long sum = 0; for (long v : s) sum += v;
        double mean = sum / (double) s.length / 1000.0;
        double p50 = s[(int) (s.length * 0.50)] / 1000.0;
        double p95 = s[(int) (s.length * 0.95)] / 1000.0;
        double p99 = s[(int) (s.length * 0.99)] / 1000.0;
        System.out.printf("%s: n=%d mean=%.2fus p50=%.2f p95=%.2f p99=%.2f | %.1f MB/s | %.1f us/KB%n",
                name, s.length, mean, p50, p95, p99, bytes / mean / 1024.0, mean / Math.max(1, bytes / 1024.0));
    }

    static long[] measureA(Object[] chunks) throws Exception {
        java.lang.reflect.Constructor<?> ct = M4PacketParityHarness.packetCtor();
        for (int w = 0; w < WARMUP; w++) for (Object c : chunks) ct.newInstance(c, 65535);
        long[] out = new long[CHUNKS * ITERS_PER_CHUNK];
        int k = 0;
        for (int it = 0; it < ITERS_PER_CHUNK; it++) {
            for (Object c : chunks) {
                long t0 = System.nanoTime();
                ct.newInstance(c, 65535);
                out[k++] = System.nanoTime() - t0;
            }
        }
        return out;
    }

    static long[] measureC(Object[] chunks, long[] gens) throws Exception {
        ByteBuffer bb = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
        long a = addr(bb);
        for (int w = 0; w < WARMUP; w++)
            for (int i = 0; i < CHUNKS; i++)
                NativeChunkBridge.encodePacket(0, 200 + i, 20, gens[i], true, true, a, 262144);
        long[] out = new long[CHUNKS * ITERS_PER_CHUNK];
        int k = 0;
        for (int it = 0; it < ITERS_PER_CHUNK; it++) {
            for (int i = 0; i < CHUNKS; i++) {
                long t0 = System.nanoTime();
                int n = NativeChunkBridge.encodePacket(0, 200 + i, 20, gens[i], true, true, a, 262144);
                out[k++] = System.nanoTime() - t0;
                if (n <= 0) throw new IllegalStateException("encode " + n);
            }
        }
        return out;
    }

    /** Old M1 staging path via NativeChunkPacket's private machinery. */
    static long[] measureB(Object[] chunks) {
        try {
            Class<?> c = Class.forName("com.rustcraft.bridge.NativeChunkPacket");
            Method stage = c.getDeclaredMethod("populateStagingBuffer", ByteBuffer.class,
                    Class.forName("[Lnet.minecraft.world.chunk.storage.ExtendedBlockStorage;"),
                    int.class, boolean.class, boolean.class, byte[].class);
            stage.setAccessible(true);
            Method predict = c.getDeclaredMethod("predictOutputLen", long.class, int.class);
            predict.setAccessible(true);
            Method encode = c.getDeclaredMethod("encodeSectionsIntoArray", long.class, int.class, byte[].class);
            encode.setAccessible(true);
            Method getAddr = c.getDeclaredMethod("getDirectBufferAddress", java.nio.ByteBuffer.class);
            getAddr.setAccessible(true);
            Method getStorages = Class.forName("net.minecraft.world.chunk.Chunk").getMethod("func_76587_i");
            Method getBiomes = Class.forName("net.minecraft.world.chunk.Chunk").getMethod("func_76605_m");

            ByteBuffer staging = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
            long sAddr = (Long) getAddr.invoke(null, staging);

            Object[][] storages = new Object[CHUNKS][];
            byte[][] biomesArr = new byte[CHUNKS][];
            for (int i = 0; i < CHUNKS; i++) {
                storages[i] = (Object[]) getStorages.invoke(chunks[i]);
                biomesArr[i] = (byte[]) getBiomes.invoke(chunks[i]);
            }

            for (int w = 0; w < WARMUP; w++) {
                for (int i = 0; i < CHUNKS; i++) {
                    staging.clear();
                    int len = (Integer) stage.invoke(null, staging, storages[i], 65535, true, true, biomesArr[i]);
                    int predicted = (Integer) predict.invoke(null, sAddr, len);
                    if (predicted > 0) {
                        byte[] p = new byte[predicted];
                        encode.invoke(null, sAddr, len, p);
                    }
                }
            }
            long[] out = new long[CHUNKS * ITERS_PER_CHUNK];
            int k = 0;
            for (int it = 0; it < ITERS_PER_CHUNK; it++) {
                for (int i = 0; i < CHUNKS; i++) {
                    long t0 = System.nanoTime();
                    staging.clear();
                    int len = (Integer) stage.invoke(null, staging, storages[i], 65535, true, true, biomesArr[i]);
                    int predicted = (Integer) predict.invoke(null, sAddr, len);
                    if (predicted <= 0) throw new IllegalStateException("predict " + predicted);
                    byte[] p = new byte[predicted];
                    int written = (Integer) encode.invoke(null, sAddr, len, p);
                    out[k++] = System.nanoTime() - t0;
                    if (written != predicted) throw new IllegalStateException("written " + written);
                }
            }
            return out;
        } catch (Throwable t) {
            System.out.println("B unavailable: " + t);
            return null;
        }
    }

    static long addr(java.nio.ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
