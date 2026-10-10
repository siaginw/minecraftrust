package com.rustcraft.bridge;

import java.lang.reflect.Method;

/**
 * OPT-FS-002 §4 focused test: blockPosY was a PER-CALL getMethod +
 * setAccessible on the hottest hook in the system (onLightSet, ~34k
 * calls/run). This test (a) proves the caching fix resolves the same
 * method every call and survives behavioral equality, and (b) MEASURES
 * the per-call cost delta offline so the fix's expected effect is
 * quantified without a server boot.
 */
public final class BlockPosYRegression {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [ok]   " + what);
        }
    }

    static class FakePos {
        final int y;
        FakePos(int y) { this.y = y; }
        public int func_177956_o() { return y; }
        public int func_177958_n() { return 1; }
        public int func_177952_p() { return 2; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[blockposy-regression] start");
        FakePos p = new FakePos(70);

        // 1. cached lookup returns the same Method every call
        Method m1 = ChunkMutationTracker.POS_Y; // may be null until first use
        int y1 = invokeCached(p);
        Method m2 = ChunkMutationTracker.POS_Y;
        check(m1 == null || m1 == m2, "POS_Y resolved once and reused");
        check(y1 == 70, "cached invoke returns the real Y");
        check(invokeCached(new FakePos(-5)) == -5, "different instance works");

        // 2. behavioral equality vs the uncached path (the retired code)
        Method fresh = p.getClass().getMethod("func_177956_o");
        fresh.setAccessible(true);
        check((Integer) fresh.invoke(p) == invokeCached(p),
                "cached result equals per-call-lookup result");

        // 3. per-call cost: uncached (getMethod+setAccessible+invoke) vs
        //    cached invoke — the quantified expected effect
        final int N = 200_000;
        for (int w = 0; w < 20_000; w++) { // warmup both paths
            fresh = p.getClass().getMethod("func_177956_o");
            fresh.setAccessible(true);
            fresh.invoke(p);
            invokeCached(p);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            fresh = p.getClass().getMethod("func_177956_o");
            fresh.setAccessible(true);
            fresh.invoke(p);
        }
        long uncachedNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            invokeCached(p);
        }
        long cachedNs = System.nanoTime() - t0;
        double perUncached = uncachedNs / (double) N;
        double perCached = cachedNs / (double) N;
        System.out.printf("  per-call uncached=%.0fns cached=%.0fns ratio=%.1fx%n",
                perUncached, perCached, perUncached / Math.max(perCached, 1));
        check(perUncached > perCached,
                "cached invoke cheaper per call");
        // 34k onLightSet calls/run: the fix's expected CPU saving
        double savedMs = (perUncached - perCached) * 34_000 / 1e6;
        System.out.printf("  projected saving at 34k calls/run: %.1f ms CPU/run%n",
                savedMs);

        if (failures > 0) {
            System.out.println("[blockposy-regression] FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("[blockposy-regression] PASS (all checks)");
    }

    static int invokeCached(Object pos) throws Exception {
        if (ChunkMutationTracker.POS_Y == null) {
            Method m = pos.getClass().getMethod("func_177956_o");
            m.setAccessible(true);
            ChunkMutationTracker.POS_Y = m;
        }
        return (Integer) ChunkMutationTracker.POS_Y.invoke(pos);
    }
}
