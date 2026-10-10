package com.rustcraft.observer;

import java.util.Arrays;

/**
 * FULL-STACK BENCHMARK §7: focused tests for the observer's tick-identity
 * extraction (run at build, no server). The m1-metrics sampler re-added
 * the whole 100-slot ring every 5s — double-counting under lag and mixing
 * phases; these tests pin the fixed semantics: no recount, no phantom
 * ticks on lag, uninitialized slots skipped, ring wrap handled.
 */
public final class MsptDedupRegression {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [ok]   " + what);
        }
    }

    public static void main(String[] args) {
        System.out.println("[mspt-dedup-regression] start");

        long[] ring = new long[100];

        // 1. normal advance: 5 new ticks (one 250ms interval at 20tps)
        for (int i = 0; i < 5; i++) ring[i] = 5_000_000L + i;
        long[] prev = ring.clone();
        for (int i = 5; i < 10; i++) ring[i] = 6_000_000L + i;
        long[] fresh = ObserverMain.newTicks(prev, ring);
        check(fresh.length == 5, "5 new ticks in a normal interval");
        check(Arrays.equals(fresh, Arrays.copyOfRange(ring, 5, 10)),
                "new-tick values are the freshly written slots");

        // 2. lag pause: ring unchanged => zero new ticks, zero phantoms
        fresh = ObserverMain.newTicks(ring.clone(), ring);
        check(fresh.length == 0, "lag pause counts no phantom ticks");

        // 3. uninitialized slots skipped (boot: ring partially zero)
        long[] bootPrev = new long[100];
        long[] bootCur = new long[100];
        bootCur[0] = 3_000_000L; bootCur[1] = 4_000_000L; // slots 2..99 = 0
        fresh = ObserverMain.newTicks(bootPrev, bootCur);
        check(fresh.length == 2, "uninitialized (0) slots never counted");

        // 4. ring wrap: cursor passes 100 and overwrites old slots
        long[] fullPrev = new long[100];
        long[] fullCur = new long[100];
        for (int i = 0; i < 100; i++) fullPrev[i] = 1_000_000L + i;
        fullCur = fullPrev.clone();
        for (int i = 0; i < 7; i++) fullCur[i] = 9_000_000L + i; // wrapped
        fresh = ObserverMain.newTicks(fullPrev, fullCur);
        check(fresh.length == 7, "ring wrap counts only the 7 overwritten slots");

        // 5. mixed advance + wrap in one interval (prev = post-wrap state
        //    of case 4, so ONLY this interval's changes count)
        long[] prev5 = fullCur.clone();
        for (int i = 100 - 4; i < 100; i++) fullCur[i] = 8_000_000L + i;
        fullCur[0] = 9_500_000L; // wrapped head
        fresh = ObserverMain.newTicks(prev5, fullCur);
        check(fresh.length == 4 + 1,
                "tail advance + wrapped head counted once each ("
                + fresh.length + ")");

        // 6. null/short prev (first sample after attach)
        fresh = ObserverMain.newTicks(null, ring);
        check(fresh.length == countNonZero(ring),
                "null prev counts each initialized slot exactly once");

        // 7. null cur
        check(ObserverMain.newTicks(ring, null).length == 0,
                "null cur ring counts nothing");

        // 8. unchanged value edge: same ns in the same slot is NOT a new
        // tick (System.nanoTime durations never repeat in practice; the
        // approximation is documented in ObserverMain)
        long[] samePrev = { 7_123_456L, 0, 3_000_000L };
        long[] sameCur = samePrev.clone();
        sameCur[2] = 3_000_001L; // only slot 2 changed (by 1ns)
        fresh = ObserverMain.newTicks(samePrev, sameCur);
        check(fresh.length == 1 && fresh[0] == 3_000_001L,
                "only genuinely changed slots count");

        if (failures > 0) {
            System.out.println("[mspt-dedup-regression] FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("[mspt-dedup-regression] PASS (all checks)");
    }

    static int countNonZero(long[] a) {
        int n = 0;
        for (long v : a) if (v > 0) n++;
        return n;
    }
}
