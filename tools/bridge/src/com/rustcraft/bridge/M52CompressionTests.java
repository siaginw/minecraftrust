package com.rustcraft.bridge;

import java.util.Arrays;

/**
 * M-CK2 §4 — independent offline correctness/lifetime tests for the optimized
 * CompressionCtx on IMMUTABLE inputs. No world state; production fail-closed
 * entry untouched.
 */
public class M52CompressionTests {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        sizesAndContent();
        reuseGrowthShrink();
        concurrentIndependentContexts();
        capacityRetryReset();
        useAfterClose();
        retainedAWhileBProduced();
        warmNoAllocPerPacket();
        negativeCorruptionControl();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    static byte[] payload(int len, int mode) {
        byte[] b = new byte[len];
        if (mode == 0) { for (int i = 0; i < len; i++) b[i] = (byte) (i % 7); }          // repetitive
        else if (mode == 1) { new java.util.Random(42).nextBytes(b); }                    // incompressible
        else { Arrays.fill(b, (byte) 5); }                                                // constant
        return b;
    }

    static byte[] inflate(byte[] d, int expected) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(d);
            byte[] out = new byte[expected + 64];
            int n = inf.inflate(out);
            inf.end();
            return n == expected && inf.finished() ? Arrays.copyOf(out, n) : null;
        } catch (Throwable t) { return null; }
    }

    static void sizesAndContent() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        int[][] cases = {{0, 0}, {1, 0}, {255, 0}, {256, 0}, {8192, 0}, {65536, 0}, {31008, 0}, {4096, 1}, {16384, 1}};
        for (int[] c : cases) {
            byte[] p = payload(c[0], c[1]);
            byte[] r = ctx.compress(p);
            byte[] inf = r == null ? null : inflate(r, p.length);
            check("sizes len=" + c[0] + " mode=" + (c[1] == 1 ? "incompressible" : "repetitive"),
                    inf != null && Arrays.equals(inf, p), "comp=" + (r == null ? -1 : r.length));
        }
        ctx.free();
    }

    static void reuseGrowthShrink() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        long a0 = CompressionCtx.SCRATCH_ALLOC_EVENTS.get();
        long g0 = CompressionCtx.SCRATCH_GROW_EVENTS.get();
        byte[] small = payload(1024, 0), big = payload(131072, 0), mid = payload(9000, 0);
        check("reuse: small ok", roundtrip(ctx, small), null);
        check("reuse: grow to big ok", roundtrip(ctx, big), null);
        check("reuse: shrink to mid ok (no re-alloc required)", roundtrip(ctx, mid), null);
        check("reuse: growth events <= 4 (this test)", CompressionCtx.SCRATCH_GROW_EVENTS.get() - g0 <= 4,
                "grow=" + (CompressionCtx.SCRATCH_GROW_EVENTS.get() - g0));
        check("reuse: alloc events bounded (not per-packet)",
                CompressionCtx.SCRATCH_ALLOC_EVENTS.get() - a0 <= 4,
                "allocs=" + (CompressionCtx.SCRATCH_ALLOC_EVENTS.get() - a0));
        ctx.free();
    }

    static boolean roundtrip(CompressionCtx c, byte[] p) {
        byte[] r = c.compress(p);
        byte[] i = r == null ? null : inflate(r, p.length);
        return i != null && Arrays.equals(i, p);
    }

    static void concurrentIndependentContexts() throws Exception {
        final CompressionCtx c1 = new CompressionCtx(); c1.ensureCreated();
        final CompressionCtx c2 = new CompressionCtx(); c2.ensureCreated();
        final byte[] p1 = payload(20000, 0), p2 = payload(33000, 1);
        final boolean[] ok = {true, true};
        Thread t1 = new Thread(() -> { for (int i = 0; i < 500; i++) if (!roundtrip(c1, p1)) { ok[0] = false; return; } });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 500; i++) if (!roundtrip(c2, p2)) { ok[1] = false; return; } });
        t1.start(); t2.start(); t1.join(); t2.join();
        check("concurrent: independent contexts clean", ok[0] && ok[1], null);
        c1.free(); c2.free();
    }

    static void capacityRetryReset() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        byte[] p = payload(5000, 0);
        int code = ctx.compressCode(p, 8); // insufficient output capacity
        check("capacity: insufficient capacity fails (negative code)", code < 0, "code=" + code);
        check("capacity: retry at full capacity succeeds", roundtrip(ctx, p), null);
        check("capacity: reset works after failure", roundtrip(ctx, payload(7000, 0)), null);
        ctx.free();
    }

    static void useAfterClose() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        check("close: works before close", roundtrip(ctx, payload(1000, 0)), null);
        ctx.free();
        byte[] r = ctx.compress(payload(1000, 0));
        check("close: use-after-close returns null (documented)", r == null, null);
        ctx.free(); // double free safe (CAS)
        check("close: double free safe", true, null);
    }

    static void retainedAWhileBProduced() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        byte[] pA = payload(12000, 0), pB = payload(15000, 2);
        byte[] a = ctx.compress(pA);
        byte[] aCopy = a == null ? null : a.clone();
        byte[] b = ctx.compress(pB);
        byte[] infA = a == null ? null : inflate(a, pA.length);
        byte[] infB = b == null ? null : inflate(b, pB.length);
        check("lifetime: A unchanged after B produced on same ctx",
                infA != null && Arrays.equals(infA, pA) && Arrays.equals(a, aCopy), null);
        check("lifetime: B independently correct", infB != null && Arrays.equals(infB, pB), null);
        ctx.free();
    }

    static void warmNoAllocPerPacket() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        byte[] p = payload(31008, 0);
        for (int w = 0; w < 50; w++) ctx.compress(p); // warm to capacity
        long a0 = CompressionCtx.SCRATCH_ALLOC_EVENTS.get();
        for (int i = 0; i < 500; i++) { if (ctx.compress(p) == null) { check("warm: op failed", false, null); return; } }
        check("warm: ZERO allocateDirect events across 500 packets (instrumented)",
                CompressionCtx.SCRATCH_ALLOC_EVENTS.get() - a0 == 0,
                "allocs=" + (CompressionCtx.SCRATCH_ALLOC_EVENTS.get() - a0));
        ctx.free();
    }

    static void negativeCorruptionControl() throws Exception {
        CompressionCtx ctx = new CompressionCtx();
        ctx.ensureCreated();
        byte[] p = payload(5000, 0);
        byte[] r = ctx.compress(p);
        check("negative: baseline roundtrip ok", r != null && Arrays.equals(inflate(r, p.length), p), null);
        if (r != null && r.length > 10) {
            r[r.length / 2] ^= 0x55; // deliberate corruption
            byte[] inf = inflate(r, p.length);
            check("negative: corrupted output FAILS verification", inf == null || !Arrays.equals(inf, p), null);
        }
        ctx.free();
    }
}
