package com.rustcraft.bridge;

import java.util.HashSet;
import java.util.Set;

/**
 * §6 regression: the compare-hash cell key must be injective over the full
 * int coordinate domain. The retired packed-long key
 * ((dx+1e6)&lt;&lt;44 ^ (dy+1e6)&lt;&lt;24 ^ (dz+1e6)&lt;&lt;4 ^ v) gave each
 * field 20 bits (bit 4 spill: a dz &gt;= 48576 sets bit 24 = dy's LSB; a
 * dy &gt;= 48576 spills into dx's field; dx overflow truncates at bit 63),
 * so distinct legal Minecraft coordinates collided. Run by
 * build_campaign_jar.py after every build — a hash built on a colliding
 * key is not evidence.
 */
public final class CellKeyRegression {

    private static long oldKey(int dx, int dy, int dz, int v) {
        return ((long) (dx + 1000000) << 44)
                ^ ((long) (dy + 1000000) << 24)
                ^ ((long) (dz + 1000000) << 4)
                ^ v;
    }

    public static void main(String[] args) {
        // live-job domain (platform anchors, y band, all values) plus the
        // spillover coordinates that broke the old packing
        int[] xs = {150, 226, 227, 350, 48575, 48576, 1000000, 30000000,
                -1, -1000000, -30000000};
        int[] ys = {0, 1, 63, 64, 65, 66, 79, 255, 48575, 48576, -1};
        int[] zs = {150, 234, 236, 238, 350, 48575, 48576, 1000000,
                30000000, -1, -1000000, -1000001};
        long total = 0;
        Set<String> keys = new HashSet<>();
        Set<Long> oldKeys = new HashSet<>();
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    for (int v = 0; v <= 15; v++) {
                        total++;
                        keys.add(LightAuthorityHook.cellKey(x, y, z, v));
                        oldKeys.add(oldKey(x, y, z, v));
                    }
                }
            }
        }
        if (keys.size() != total) {
            throw new AssertionError("cellKey collisions: " + keys.size()
                    + " distinct / " + total + " cells");
        }
        System.out.println("[cellkey] injective: " + keys.size() + "/"
                + total + " cells distinct");
        if (oldKeys.size() == total) {
            throw new AssertionError("old packed-long key showed NO "
                    + "collision in a domain that must contain one — the "
                    + "regression domain lost its defect witness");
        }
        System.out.println("[cellkey] old packed key collides as designed: "
                + oldKeys.size() + " distinct / " + total + " cells (defect"
                + " witness present, fix justified)");
        // hash determinism: identical cell sets in different insertion
        // orders must produce identical hashes
        long h1 = hashOf(new int[][]{{1, 2, 3, 4}, {5, 6, 7, 8}});
        long h2 = hashOf(new int[][]{{5, 6, 7, 8}, {1, 2, 3, 4}});
        if (h1 != h2) {
            throw new AssertionError("order-sensitive hash: " + h1 + " != "
                    + h2);
        }
        System.out.println("[cellkey] order-invariant hash OK (h=" + h1
                + ")");
        System.out.println("[cellkey] PASS");
    }

    private static long hashOf(int[][] cells) {
        Set<String> set = new java.util.TreeSet<>();
        for (int[] c : cells) {
            set.add(LightAuthorityHook.cellKey(c[0], c[1], c[2], c[3]));
        }
        long hash = 0;
        for (String k : set) {
            hash = hash * 1000003L + k.hashCode();
        }
        return hash;
    }

    private CellKeyRegression() {
    }
}
