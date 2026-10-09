package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * M5 §5 — offline FULL-PIPELINE oracle.
 *
 * JAVA reference: real SPacketChunkData -> JDK Deflater (vanilla level 6/256KB? —
 * vanilla uses new Deflater(6? no: level from server network compression threshold
 * config; vanilla uses Deflater.DEFAULT_LEVEL=-1... vanilla NetworkManager uses
 * new Deflater(); i.e. default level 6) -> raw deflate bytes.
 *
 * RUST combined: NativeChunk -> Rust Protocol-340 encode -> M2-C native
 * compressor (CompressionCtx) -> bytes.
 *
 * Both sides then INFLATE + independent Protocol-340 decode + semantic compare
 * (mask, states, light, biomes, framing tail). Byte identity after compression
 * NOT required. Also times path A vs path B for the §10 measurement.
 */
public class M5PipelineOracle {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("minecraftrust.m1.native_state", "OFF");
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        int N = 24;
        Object[] chunks = new Object[N];
        long[] gens = new long[N];
        byte[][] javaPayloads = new byte[N][];
        int[] masks = new int[N];
        for (int i = 0; i < N; i++) {
            Object[] r = M4PacketParityHarness.baseChunkAndPrimer(500 + i, 50);
            chunks[i] = r[0];
            gens[i] = NativeChunkBridge.register(0, 500 + i, 50, addr((ByteBuffer) r[1]), 0L);
            M4Coherency.refreshChunkNow(r[0]); // sync (blocks+light+biomes)
            Object pkt = M4PacketParityHarness.packetCtor().newInstance(r[0], 65535);
            javaPayloads[i] = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
            masks[i] = M4PacketParityHarness.pktMaskField().getInt(pkt);
        }

        // ---- Rust combined path: encode + compress ----
        CompressionCtx ctx = new CompressionCtx(); // lazy native ctx, vanilla-equivalent default
        check("ctx created", ctx.ensureCreated(), null);
        byte[][] rustPayloads = new byte[N][];
        byte[][] rustCompressed = new byte[N][];
        for (int i = 0; i < N; i++) {
            ByteBuffer out = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
            int n = NativeChunkBridge.encodePacket(0, 500 + i, 50, gens[i], true, true,
                    addr(out), 262144);
            check("rust encode " + i, n > 0, "n=" + n);
            byte[] p = new byte[n];
            out.clear(); out.get(p);
            rustPayloads[i] = p;
            rustCompressed[i] = ctx.compress(p);
            check("rust compress " + i, rustCompressed[i] != null && rustCompressed[i].length > 0, null);
        }

        // ---- Java reference path: JDK deflater (vanilla-style) ----
        byte[][] javaCompressed = new byte[N][];
        Deflater def = new Deflater(); // default level, vanilla NetworkManager style
        for (int i = 0; i < N; i++) {
            def.setInput(javaPayloads[i]);
            def.finish();
            ByteBuffer o = ByteBuffer.allocate(javaPayloads[i].length / 2 + 256);
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n > 0) { o.put(buf, 0, n); }
            }
            def.reset();
            byte[] jc = new byte[o.position()];
            o.position(0); o.get(jc);
            javaCompressed[i] = jc;
        }

        // ---- inflate + decode + semantic compare both ----
        long jcBytes = 0, rcBytes = 0, plBytes = 0;
        for (int i = 0; i < N; i++) {
            byte[] jInf = inflate(javaCompressed[i], javaPayloads[i].length);
            byte[] rInf = inflate(rustCompressed[i], rustPayloads[i].length);
            check("inflate java " + i, jInf != null, null);
            check("inflate rust " + i, rInf != null, null);
            M4PacketParityHarness.ParsedJ Jraw = M4PacketParityHarness.parseByMask(javaPayloads[i], masks[i], true);
            if (Jraw == null) { check("raw java parse " + i, false, "RAW ALSO FAILS"); continue; }
            M4PacketParityHarness.ParsedJ J = M4PacketParityHarness.parseByMask(jInf, masks[i], true);
            M4PacketParityHarness.ParsedJ R = M4PacketParityHarness.parseByMask(rInf, masks[i], true);
            String verdict = (J == null || R == null) ? "parse failure"
                    : M4PacketParityHarness.compareParsed(J, R);
            check("semantic equality after compression " + i, verdict == null,
                    verdict + " mask=" + masks[i] + " jLen=" + jInf.length + " rLen=" + rInf.length
                    + " jHead=" + jInf[0] + " rHead=" + rInf[0]);
            jcBytes += javaCompressed[i].length;
            rcBytes += rustCompressed[i].length;
            plBytes += javaPayloads[i].length;
        }
        System.out.printf("sizes: payload=%d javaDefl=%d (%.1f%%) rustDefl=%d (%.1f%%)%n",
                plBytes, jcBytes, 100.0 * jcBytes / plBytes, rcBytes, 100.0 * rcBytes / plBytes);

        // ---- §10 timing: A (java encode+deflate) vs B (rust encode+compress) ----
        for (int w = 0; w < 200; w++) {
            for (int i = 0; i < N; i++) {
                def.setInput(javaPayloads[i]); def.finish();
                byte[] tb = new byte[8192];
                while (!def.finished()) def.deflate(tb);
                def.reset();
                ByteBuffer out = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
                NativeChunkBridge.encodePacket(0, 500 + i, 50, gens[i], true, true, addr(out), 262144);
                ctx.compress(rustPayloads[i]);
            }
        }
        long[] tA = new long[N * 200], tB = new long[N * 200];
        int k = 0;
        for (int it = 0; it < 200; it++) {
            for (int i = 0; i < N; i++) {
                long a0 = System.nanoTime();
                Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunks[i], 65535);
                def.setInput(javaPayloads[i]); def.finish();
                byte[] tb = new byte[65536];
                while (!def.finished()) def.deflate(tb);
                def.reset();
                tA[k] = System.nanoTime() - a0;
                long b0 = System.nanoTime();
                ByteBuffer out = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
                NativeChunkBridge.encodePacket(0, 500 + i, 50, gens[i], true, true, addr(out), 262144);
                ctx.compress(rustPayloads[i]);
                tB[k] = System.nanoTime() - b0;
                k++;
            }
        }
        stats("A java-encode+deflate", tA);
        stats("B rust-encode+compress", tB);

        ctx.free();
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) pass++; else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    static void stats(String name, long[] t) {
        long[] s = t.clone(); java.util.Arrays.sort(s);
        long sum = 0; for (long v : s) sum += v;
        System.out.printf("%s: n=%d mean=%.2fus p50=%.2f p95=%.2f p99=%.2f%n",
                name, s.length, sum / (double) s.length / 1000.0,
                s[s.length / 2] / 1000.0, s[(int) (s.length * 0.95)] / 1000.0,
                s[(int) (s.length * 0.99)] / 1000.0);
    }

    static byte[] inflate(byte[] data, int expected) {
        try {
            Inflater inf = new Inflater(); // zlib (vanilla PacketBuffer.writeCompressed)
            inf.setInput(data);
            byte[] out = new byte[expected + 64];
            int n = inf.inflate(out);
            inf.end();
            return n == expected ? java.util.Arrays.copyOf(out, n) : null;
        } catch (DataFormatException e) {
            return null;
        }
    }

    static long addr(ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
