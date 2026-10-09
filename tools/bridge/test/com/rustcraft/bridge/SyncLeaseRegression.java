package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OPT-SYNC-006 §9 focused tests for the C1 reuse machinery. Runs on every
 * campaign jar build (wired like CellKeyRegression). No Minecraft classes.
 *
 * Covers: slot non-aliasing (verifier independence), re-entrant lease
 * fallback, stale-tail zero-fill on light slots, readback lease contract
 * (position reset, capacity, LITTLE_ENDIAN, written-length = full compare
 * region), exception cleanup, concurrent callers (per-thread scratch),
 * ClassValue runtime-class cache separation, and the packed==native-wrong
 * negative at the lease level (the independent slot must catch it).
 */
public final class SyncLeaseRegression {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [ok]   " + what);
        }
    }

    // runtime-class cache fixtures: same SRG-named member, DIFFERENT classes
    static class FakeEbsTrue { public boolean func_76663_a() { return true; } }
    static class FakeEbsFalse { public boolean func_76663_a() { return false; } }
    static class FakeNoMember { public boolean other() { return true; } }

    public static void main(String[] args) throws Exception {
        System.out.println("[sync-lease-regression] start");

        // 1. slot non-aliasing (§6): gid and dv are DISTINCT storage; a
        // planted wrong value in the packed-decode slot is caught by the
        // independent slot (the dualVerify comparator shape).
        M4Coherency.SyncScratch s = M4Coherency.SCRATCH_TL.get();
        int[] gid = M4Coherency.leaseGid(s);
        int[] dv = M4Coherency.leaseDv(s);
        check(gid != dv, "gid and dv slots are distinct arrays");
        for (int i = 0; i < 4096; i++) { gid[i] = 100; dv[i] = 100; }
        gid[1234] = 65_535; // planted wrong value in the packed-decode witness
        int mismatches = 0;
        for (int i = 0; i < 4096; i++) if (dv[i] != gid[i]) mismatches++;
        check(mismatches == 1 && dv[1234] == 100,
                "independent slot detects the planted packed-slot value (1 cell)");
        M4Coherency.releaseGid(s, gid);
        M4Coherency.releaseDv(s, dv);

        // 2. re-entrancy: acquiring a HELD slot yields a FRESH array
        long fbBefore = M4Coherency.LEASE_REENTRANT_FALLBACK.get();
        int[] g1 = M4Coherency.leaseGid(s);
        int[] g2 = M4Coherency.leaseGid(s);
        check(g1 != g2, "re-entrant gid lease returns a fresh array");
        check(M4Coherency.LEASE_REENTRANT_FALLBACK.get() == fbBefore + 1,
                "re-entrant fallback counted");
        M4Coherency.releaseGid(s, g1);
        int[] g3 = M4Coherency.leaseGid(s);
        check(g3 == g1, "released slot is reusable (same array returned)");
        M4Coherency.releaseGid(s, g3);
        M4Coherency.releaseGid(s, g2);

        // 3. stale-tail: light slots are ZEROED on lease (null-nibble
        // semantics must never see the previous section's bytes)
        byte[] b1 = M4Coherency.leaseBl(s);
        java.util.Arrays.fill(b1, (byte) 0x7F); // poison
        M4Coherency.releaseBl(s, b1);
        byte[] b2 = M4Coherency.leaseBl(s);
        check(b2 == b1, "bl slot reused");
        boolean zeroed = true;
        for (byte x : b2) if (x != 0) { zeroed = false; break; }
        check(zeroed, "bl lease is zeroed after poisoned prior use");
        M4Coherency.releaseBl(s, b2);

        // 4. readback lease contract (§5): capacity, order, position reset,
        //    full-region compare legality
        ByteBuffer rb = M4Coherency.leaseReadback(s);
        check(rb.capacity() == 20480, "readback capacity is 20480");
        check(rb.order() == ByteOrder.LITTLE_ENDIAN, "readback order is LITTLE_ENDIAN");
        rb.putInt(0, 0x0A0B0C0D);
        rb.position(4096); // disturb position
        M4Coherency.releaseReadback(s, rb);
        ByteBuffer rb2 = M4Coherency.leaseReadback(s);
        check(rb2 == rb, "readback slot reused");
        check(rb2.position() == 0 && rb2.limit() == 20480, "readback position/limit reset on lease");
        check(rb2.getInt(0) == 0x0A0B0C0D, "LE int roundtrip at fixed offset");
        check((rb2.get(0) & 0xFF) == 0x0D, "byte 0 is the LE low byte");
        ByteBuffer rb3 = M4Coherency.leaseReadback(s); // held above
        check(rb3 != rb2, "re-entrant readback lease returns a fresh buffer");
        M4Coherency.releaseReadback(s, rb2);
        M4Coherency.releaseReadback(s, rb3);

        // 5. exception cleanup: leases released in finally remain consistent
        try {
            int[] gx = M4Coherency.leaseGid(s);
            try {
                throw new IllegalStateException("boom");
            } finally {
                M4Coherency.releaseGid(s, gx);
            }
        } catch (IllegalStateException expected) { }
        int[] g4 = M4Coherency.leaseGid(s);
        check(g4 != null, "gid slot leaseable after exception cleanup");
        M4Coherency.releaseGid(s, g4);

        // 6. concurrent callers: per-thread scratch, no cross-thread bleed
        final AtomicInteger errs = new AtomicInteger();
        Thread t = new Thread(() -> {
            try {
                M4Coherency.SyncScratch s2 = M4Coherency.SCRATCH_TL.get();
                int[] other = M4Coherency.leaseGid(s2);
                java.util.Arrays.fill(other, 42);
                Thread.sleep(30);
                boolean clean = true;
                for (int x : other) if (x != 42) { clean = false; break; }
                if (!clean) errs.incrementAndGet();
                M4Coherency.releaseGid(s2, other);
            } catch (Exception e) {
                errs.incrementAndGet();
            }
        });
        int[] mine = M4Coherency.leaseGid(s);
        java.util.Arrays.fill(mine, 7);
        t.start();
        t.join();
        boolean mineClean = true;
        for (int x : mine) if (x != 7) { mineClean = false; break; }
        check(errs.get() == 0 && mineClean, "concurrent threads get independent scratch");
        M4Coherency.releaseGid(s, mine);

        // 7. ClassValue runtime-class cache separation (§7)
        Method mTrue = M4Coherency.CV_IS_EMPTY.get(FakeEbsTrue.class);
        Method mFalse = M4Coherency.CV_IS_EMPTY.get(FakeEbsFalse.class);
        Method mNone = M4Coherency.CV_IS_EMPTY.get(FakeNoMember.class);
        check(mTrue != null && mFalse != null && mTrue != mFalse,
                "distinct runtime classes get distinct cached Methods");
        check(mNone == null, "class without the member caches null (fail-open)");
        check((Boolean) mTrue.invoke(new FakeEbsTrue())
                && !(Boolean) mFalse.invoke(new FakeEbsFalse()),
                "cached Methods dispatch to their OWN runtime class");
        check(M4Coherency.CV_IS_EMPTY.get(FakeEbsTrue.class) == mTrue,
                "cache returns the SAME instance on re-hit");

        // 8. retained-memory accounting: readback contributes 20480/thread
        check(M4Coherency.LEASE_RETAINED_BYTES.get() >= 20480,
                "retained direct bytes accounted");

        // 9. ctx immutability shape: op-scoped context cannot be mutated
        M4Coherency.ChunkCtx c1 = new M4Coherency.ChunkCtx(0, 1, 2, 3L);
        check(c1.dim == 0 && c1.cx == 1 && c1.cz == 2 && c1.gen == 3L,
                "ChunkCtx carries its resolved identity");

        if (failures > 0) {
            System.out.println("[sync-lease-regression] FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("[sync-lease-regression] PASS (all checks)");
    }
}
