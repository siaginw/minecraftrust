package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * M-Checkpoint2 §1/§5 — smoke benchmark + component decomposition for the
 * packet→compression path on IMMUTABLE fixtures (no world state, no live path).
 *
 * JAVA_REFERENCE : real SPacketChunkData → JDK Deflater (vanilla-style).
 * CURRENT_RUST   : CompressionCtx.compress(byte[]) (current integration glue).
 *
 * Measures separately: plaintext prep, compress call, output availability.
 * Consumes results; alternates arms; prints p50/p95/p99 and per-arm byte counts.
 */
public class M52CompressionBench {

    static final int N = 2000, WARM = 300;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        // Fixture: a real synced chunk (immutable input for this bench)
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(600, 60);
        Object chunk = r[0];
        NativeChunkBridge.register(0, 600, 60, addr((ByteBuffer) r[1]), 0L);
        M4Coherency.refreshChunkNow(chunk);
        Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
        byte[] plaintext = (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
        int mask = M4PacketParityHarness.pktMaskField().getInt(pkt);
        System.out.printf("fixture: javaPayload=%d B mask=%d (IMMUTABLE INPUT)%n", plaintext.length, mask);

        CompressionCtx ctx = new CompressionCtx();
        boolean live = ctx.ensureCreated();
        System.out.println("ctx live=" + live);

        // ---- smoke: correctness parity first ----
        byte[] jr = javaCompress(plaintext);
        byte[] rr = ctx.compress(plaintext);
        byte[] jInf = inflate(jr, plaintext.length);
        byte[] rInf = inflate(rr, plaintext.length);
        System.out.printf("parity: java=%d B rust=%d B inflate-equal=%s%n",
                jr != null ? jr.length : -1, rr != null ? rr.length : -1,
                (jInf != null && rInf != null && Arrays.equals(jInf, rInf)));

        // ---- component decomposition over the CURRENT rust glue ----
        // (allocateDirect cost inferred by benching compress vs a pre-staged variant below)
        long[] tPrep = new long[N], tComp = new long[N];
        byte[][] sink = new byte[1][];
        for (int w = 0; w < WARM; w++) sink[0] = ctx.compress(plaintext);
        for (int i = 0; i < N; i++) {
            long a = System.nanoTime();
            ByteBuffer in = ByteBuffer.allocateDirect(Math.max(1, plaintext.length));
            in.put(plaintext).flip();
            ByteBuffer out = ByteBuffer.allocateDirect((int) CompressionCtx.maxOutputLen(plaintext.length));
            long b = System.nanoTime();
            sink[0] = ctx.compress(plaintext); // full glue (alloc + JNI + copy-out)
            long c = System.nanoTime();
            tPrep[i] = b - a;   // direct-buffer alloc cost (the §1 suspect)
            tComp[i] = c - b;   // full glue cost
            if (sink[0] == null) throw new IllegalStateException("compress null");
        }
        stats("glue: 2x allocateDirect (input+output)", tPrep);
        stats("glue: full ctx.compress call", tComp);

        // ---- JAVA vs CURRENT_RUST complete-path arms (alternated) ----
        long[] tj = new long[N], tr = new long[N];
        Deflater def = new Deflater();
        for (int w = 0; w < WARM; w++) { javaCompress(plaintext, def); ctx.compress(plaintext); }
        for (int i = 0; i < N; i++) {
            if ((i & 1) == 0) {
                long a = System.nanoTime(); byte[] x = javaCompress(plaintext, def); tj[i] = System.nanoTime() - a;
                long b = System.nanoTime(); byte[] y = ctx.compressBaseline(plaintext); tr[i] = System.nanoTime() - b;
                if (x == null || y == null) throw new IllegalStateException();
            } else {
                long b = System.nanoTime(); byte[] y = ctx.compressBaseline(plaintext); tr[i] = System.nanoTime() - b;
                long a = System.nanoTime(); byte[] x = javaCompress(plaintext, def); tj[i] = System.nanoTime() - a;
                if (x == null || y == null) throw new IllegalStateException();
            }
        }
        // RUST_OPTIMIZED arm (fresh timings; scratch already warm from arms above)
        long[] to = new long[N];
        for (int w = 0; w < WARM; w++) ctx.compress(plaintext);
        for (int i = 0; i < N; i++) {
            long b = System.nanoTime(); byte[] y = ctx.compress(plaintext); to[i] = System.nanoTime() - b;
            if (y == null) throw new IllegalStateException();
        }
        stats("JAVA_REFERENCE  (payload-in-hand -> JDK deflate -> heap result)", tj);
        stats("CURRENT_RUST    (payload-in-hand -> compressBaseline -> heap result)", tr);
        stats("RUST_OPTIMIZED  (payload-in-hand -> ctx.compress -> heap result)", to);
        double om = mean(to), rmL = mean(tr), jmL = mean(tj);
        System.out.printf("optimized vs baseline-rust: %.3fx (base %.2f us, opt %.2f us); vs java: %.3fx%n",
                om / rmL, rmL / 1000.0, om / 1000.0, om / jmL);
        System.out.printf("alloc-metrics: scratchAllocEvents=%d growEvents=%d retainedApprox=%d B%n",
                CompressionCtx.SCRATCH_ALLOC_EVENTS.get(), CompressionCtx.SCRATCH_GROW_EVENTS.get(),
                CompressionCtx.SCRATCH_RETAINED_BYTES_APPROX);
        // parity re-check on optimized path
        byte[] o2 = ctx.compress(plaintext);
        byte[] oInf = inflate(o2, plaintext.length);
        System.out.println("optimized-parity: inflate-equal=" + (oInf != null && Arrays.equals(oInf, plaintext)));
        double jm = mean(tj), rm = mean(tr);
        System.out.printf("complete-path ratio (rust/java mean): %.3fx  (java %.2f us, rust %.2f us)%n",
                rm / jm, rm / 1000.0, jm / 1000.0);
        System.out.println("IMMUTABLE-INPUT COMPONENT MEASUREMENT ONLY; live capture/refresh/cache economics excluded and blocked.");
        ctx.free();
    }

    static byte[] javaCompress(byte[] p) { return javaCompress(p, new Deflater()); }

    static byte[] javaCompress(byte[] p, Deflater def) {
        def.setInput(p); def.finish();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(p.length / 2 + 256);
        byte[] buf = new byte[8192];
        while (!def.finished()) { int n = def.deflate(buf); if (n > 0) bos.write(buf, 0, n); }
        def.reset();
        return bos.toByteArray();
    }

    static byte[] inflate(byte[] data, int expected) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(data);
            byte[] out = new byte[expected + 64];
            int n = inf.inflate(out);
            inf.end();
            return n == expected ? Arrays.copyOf(out, n) : null;
        } catch (Throwable t) { return null; }
    }

    static void stats(String name, long[] t) {
        long[] s = t.clone(); Arrays.sort(s);
        long sum = 0; for (long v : s) sum += v;
        System.out.printf("%-55s n=%d mean=%8.2fus p50=%8.2f p95=%8.2f p99=%8.2f%n",
                name, s.length, sum / (double) s.length / 1000.0,
                s[s.length / 2] / 1000.0, s[(int) (s.length * 0.95)] / 1000.0,
                s[(int) (s.length * 0.99)] / 1000.0);
    }

    static double mean(long[] t) { long s = 0; for (long v : t) s += v; return s / (double) t.length; }

    static long addr(ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
