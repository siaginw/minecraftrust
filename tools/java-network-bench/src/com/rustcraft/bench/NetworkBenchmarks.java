package com.rustcraft.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class NetworkBenchmarks {

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b(); // register blocks/items
        System.out.println("=== P0-5 Network Benchmarks Start ===");

        benchVarInt();
        benchFraming();
        benchPacketBuffer();
        benchCompression();
        benchEncryption();
        benchRSA();

        System.out.println("=== P0-5 Network Benchmarks Complete ===");
    }

    static void benchVarInt() {
        System.out.println("\n--- [BENCH] VarInt Encode / Decode (Batched 1000 ops/sample) ---");
        int[] samples = new int[]{0, 127, 128, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE, -1};
        PacketBuffer buf = new PacketBuffer(Unpooled.directBuffer(16384));

        // Warmup
        for (int i = 0; i < 50000; i++) {
            for (int v : samples) {
                buf.clear();
                buf.func_150787_b(v);
                buf.func_150792_a();
            }
        }

        int batches = 5000;
        int batchSize = 1000;
        double[] encPerOp = new double[batches];
        double[] decPerOp = new double[batches];

        for (int b = 0; b < batches; b++) {
            buf.clear();
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                buf.func_150787_b(samples[k % samples.length]);
            }
            long tEnc = System.nanoTime() - t0;
            encPerOp[b] = (double)tEnc / batchSize;

            long t1 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                buf.func_150792_a();
            }
            long tDec = System.nanoTime() - t1;
            decPerOp[b] = (double)tDec / batchSize;
        }

        printDoubleStats("VarInt Encode (per op)", encPerOp);
        printDoubleStats("VarInt Decode (per op)", decPerOp);
        buf.release();
    }

    static void benchFraming() {
        System.out.println("\n--- [BENCH] VarInt21 Framing Prepend / Split (Batched 1000 ops/sample) ---");
        byte[] payload = new byte[256];
        new Random(42).nextBytes(payload);
        ByteBuf out = Unpooled.directBuffer(512 * 1000);

        // Warmup
        for (int i = 0; i < 20000; i++) {
            out.clear();
            writeVarint(out, payload.length);
            out.writeBytes(payload);
            readVarint(out);
        }

        int batches = 3000;
        int batchSize = 500;
        double[] prepPerOp = new double[batches];
        double[] splitPerOp = new double[batches];

        for (int b = 0; b < batches; b++) {
            out.clear();
            long t0 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                writeVarint(out, payload.length);
                out.writeBytes(payload);
            }
            long tPrep = System.nanoTime() - t0;
            prepPerOp[b] = (double)tPrep / batchSize;

            long t1 = System.nanoTime();
            for (int k = 0; k < batchSize; k++) {
                int len = readVarint(out);
                ByteBuf frame = out.readSlice(len);
            }
            long tSplit = System.nanoTime() - t1;
            splitPerOp[b] = (double)tSplit / batchSize;
        }

        printDoubleStats("Frame Prepend (per op)", prepPerOp);
        printDoubleStats("Frame Split (per op)", splitPerOp);
        out.release();
    }

    static void printDoubleStats(String name, double[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        double p50 = times[times.length / 2];
        double p95 = times[(int)(times.length * 0.95)];
        double p99 = times[(int)(times.length * 0.99)];
        double max = times[times.length - 1];
        System.out.printf("%-30s | Mean: %6.2f ns | p50: %6.2f ns | p95: %6.2f ns | p99: %6.2f ns | Max: %7.2f ns (Batches=%d)%n",
                name, mean, p50, p95, p99, max, times.length);
    }

    static void benchPacketBuffer() {
        System.out.println("\n--- [BENCH] PacketBuffer Serialization Primitives ---");
        PacketBuffer buf = new PacketBuffer(Unpooled.directBuffer(1024));

        benchPBPrimitive("String (16 chars)", () -> {
            buf.clear();
            buf.func_180714_a("TestPlayerName16");
            buf.func_150789_c(32767);
        });

        UUID testUuid = UUID.randomUUID();
        benchPBPrimitive("UUID", () -> {
            buf.clear();
            buf.func_179252_a(testUuid);
            buf.func_179253_g();
        });

        BlockPos pos = new BlockPos(100, 64, -200);
        benchPBPrimitive("BlockPos", () -> {
            buf.clear();
            buf.func_179255_a(pos);
            buf.func_179259_c();
        });

        NBTTagCompound tag = new NBTTagCompound();
        tag.func_74778_a("id", "minecraft:chest");
        tag.func_74768_a("x", 100);
        tag.func_74768_a("y", 64);
        tag.func_74768_a("z", -200);
        benchPBPrimitive("NBT Compound", () -> {
            buf.clear();
            buf.func_150786_a(tag);
            try { buf.func_150793_b(); } catch (Exception e) {}
        });

        ItemStack stack = new ItemStack(Blocks.field_150348_b, 64); // Stone
        benchPBPrimitive("ItemStack", () -> {
            buf.clear();
            buf.func_150788_a(stack);
            try { buf.func_150791_c(); } catch (Exception e) {}
        });

        ResourceLocation rl = new ResourceLocation("minecraft", "iron_sword");
        benchPBPrimitive("ResourceLocation", () -> {
            buf.clear();
            buf.func_192572_a(rl);
            buf.func_192575_l();
        });

        buf.release();
    }

    static void benchPBPrimitive(String name, Runnable task) {
        // warmup
        for (int i = 0; i < 20000; i++) task.run();

        int iters = 50000;
        long[] times = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            task.run();
            times[i] = System.nanoTime() - t0;
        }
        printStats(name, times);
    }

    static void benchCompression() {
        System.out.println("\n--- [BENCH] Network Compression (JDK Deflater / Inflater) ---");
        int[] sizes = new int[]{64, 256, 1024, 8192, 32768, 64512};
        Deflater deflater = new Deflater();
        Inflater inflater = new Inflater();
        byte[] outBuf = new byte[131072];

        for (int sz : sizes) {
            byte[] input = new byte[sz];
            // typical packet-like entropy (mix of repetitive and varying bytes)
            for (int i = 0; i < sz; i++) input[i] = (byte)(i % 16 == 0 ? i : 0);

            // Warmup
            for (int i = 0; i < 2000; i++) {
                deflater.reset();
                deflater.setInput(input);
                deflater.finish();
                int csz = deflater.deflate(outBuf, 0, outBuf.length, Deflater.SYNC_FLUSH);

                inflater.reset();
                inflater.setInput(outBuf, 0, csz);
                try { inflater.inflate(new byte[sz]); } catch (Exception e) {}
            }

            int iters = 5000;
            long[] compTimes = new long[iters];
            long[] decompTimes = new long[iters];
            int finalCompSize = 0;

            for (int i = 0; i < iters; i++) {
                deflater.reset();
                deflater.setInput(input);
                deflater.finish();
                long t0 = System.nanoTime();
                int csz = deflater.deflate(outBuf, 0, outBuf.length, Deflater.SYNC_FLUSH);
                compTimes[i] = System.nanoTime() - t0;
                finalCompSize = csz;

                inflater.reset();
                inflater.setInput(outBuf, 0, csz);
                byte[] target = new byte[sz];
                long t1 = System.nanoTime();
                try { inflater.inflate(target); } catch (Exception e) {}
                decompTimes[i] = System.nanoTime() - t1;
            }

            double ratio = (double)finalCompSize / sz;
            System.out.printf("Payload %5d B (Ratio: %.2f) -> Compress:", sz, ratio);
            printStatsInline(compTimes);
            System.out.print(" | Decompress:");
            printStatsInline(decompTimes);
            System.out.println();
        }
        deflater.end();
        inflater.end();
    }

    static void benchEncryption() throws Exception {
        System.out.println("\n--- [BENCH] Network Encryption (AES/CFB8/NoPadding) ---");
        byte[] keyBytes = new byte[16];
        new Random(42).nextBytes(keyBytes);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec iv = new IvParameterSpec(keyBytes);

        Cipher enc = Cipher.getInstance("AES/CFB8/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, key, iv);

        Cipher dec = Cipher.getInstance("AES/CFB8/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, key, iv);

        int[] sizes = new int[]{64, 256, 1024, 8192, 32768};
        byte[] out = new byte[65536];

        for (int sz : sizes) {
            byte[] in = new byte[sz];
            new Random(123).nextBytes(in);

            // Warmup
            for (int i = 0; i < 2000; i++) {
                enc.update(in, 0, sz, out, 0);
                dec.update(out, 0, sz, in, 0);
            }

            int iters = 10000;
            long[] encTimes = new long[iters];
            long[] decTimes = new long[iters];

            for (int i = 0; i < iters; i++) {
                long t0 = System.nanoTime();
                enc.update(in, 0, sz, out, 0);
                encTimes[i] = System.nanoTime() - t0;

                long t1 = System.nanoTime();
                dec.update(out, 0, sz, in, 0);
                decTimes[i] = System.nanoTime() - t1;
            }

            double mb = (double)sz * iters / (1024 * 1024);
            double totalSecEnc = Arrays.stream(encTimes).sum() / 1e9;
            double throughputEnc = mb / totalSecEnc;

            System.out.printf("Payload %5d B -> Encrypt (%.1f MB/s):", sz, throughputEnc);
            printStatsInline(encTimes);
            System.out.print(" | Decrypt:");
            printStatsInline(decTimes);
            System.out.println();
        }
    }

    static void benchRSA() throws Exception {
        System.out.println("\n--- [BENCH] Login RSA 1024-bit Handshake Cost ---");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);

        int iters = 100;
        long[] genTimes = new long[iters];
        long[] decTimes = new long[iters];

        byte[] secretKey = new byte[16];
        new Random(42).nextBytes(secretKey);

        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            KeyPair kp = kpg.generateKeyPair();
            genTimes[i] = System.nanoTime() - t0;

            Cipher rsaEnc = Cipher.getInstance("RSA");
            rsaEnc.init(Cipher.ENCRYPT_MODE, kp.getPublic());
            byte[] encryptedSecret = rsaEnc.doFinal(secretKey);

            Cipher rsaDec = Cipher.getInstance("RSA");
            rsaDec.init(Cipher.DECRYPT_MODE, kp.getPrivate());
            long t1 = System.nanoTime();
            byte[] recovered = rsaDec.doFinal(encryptedSecret);
            decTimes[i] = System.nanoTime() - t1;
        }

        printStats("RSA 1024 KeyGen", genTimes);
        printStats("RSA 1024 Decrypt (Server login step)", decTimes);
    }

    // Helpers
    static void writeVarint(ByteBuf out, int value) {
        while ((value & -128) != 0) {
            out.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarint(ByteBuf in) {
        int i = 0;
        int j = 0;
        byte b0;
        do {
            b0 = in.readByte();
            i |= (b0 & 127) << j++ * 7;
            if (j > 5) throw new RuntimeException("VarInt too big");
        } while ((b0 & 128) == 128);
        return i;
    }

    static void printStats(String name, long[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        long p50 = times[times.length / 2];
        long p95 = times[(int)(times.length * 0.95)];
        long p99 = times[(int)(times.length * 0.99)];
        long max = times[times.length - 1];
        System.out.printf("%-30s | Mean: %6.1f ns | p50: %5d ns | p95: %5d ns | p99: %5d ns | Max: %7d ns (N=%d)%n",
                name, mean, p50, p95, p99, max, times.length);
    }

    static void printStatsInline(long[] times) {
        Arrays.sort(times);
        double mean = Arrays.stream(times).average().orElse(0);
        long p50 = times[times.length / 2];
        long p95 = times[(int)(times.length * 0.95)];
        long p99 = times[(int)(times.length * 0.99)];
        System.out.printf(" Mean: %5.1f ns, p50: %4d ns, p95: %4d ns, p99: %4d ns", mean, p50, p95, p99);
    }
}
