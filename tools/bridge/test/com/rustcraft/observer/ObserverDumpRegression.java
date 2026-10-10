package com.rustcraft.observer;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retro 2026-10-09 (FS-002): the standalone validation of the alloc-sites
 * dump ran with an EMPTY map — it only exercised the header path, and the
 * populated-path AIOOBE (a long[2] creator the edit silently missed) cost
 * two diagnostic boots to discover. This test runs a POPULATED aggregation
 * through dumpSites and parses every field back: bytes, count, firstMs,
 * lastMs, key, sorted by bytes descending, plus the empty-map header path.
 */
public final class ObserverDumpRegression {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [ok]   " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[observer-dump-regression] start");
        File dir = Files.createTempDirectory("obsdump").toFile();
        try {
            // 1. POPULATED path (the case the empty-only validation missed)
            ConcurrentHashMap<String, long[]> sites =
                    new ConcurrentHashMap<>();
            sites.put("siteA || -", new long[] {5_000_000L, 40, 111L, 222L});
            sites.put("siteB || com.rustcraft.X.y(X.java:1)",
                    new long[] {50_000_000L, 400, 111L, 333L});
            sites.put("siteC || -", new long[] {500_000L, 4, 111L, 444L});
            ObserverMain.dumpSites(dir, sites, false);
            File out = new File(dir, "observer-alloc-sites.txt");
            check(out.isFile(), "populated dump wrote the file");
            java.util.List<String> lines = Files.readAllLines(out.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(lines.get(0).startsWith("# total attributed bytes: 55500000"),
                    "header totals the bytes: " + lines.get(0));
            check(lines.size() == 4, "header + 3 site lines (got "
                    + lines.size() + ")");
            String[] f = lines.get(1).split("\t", 4);
            check(f[3].contains("siteB"), "largest-byte site sorts first");
            check(f[0].equals("50000000") && f[1].equals("400")
                    && f[2].equals("111") && f[3].contains("333"),
                    "all five fields parse back (bytes,count,firstMs,"
                    + "lastMs,key): " + lines.get(1));
            check(!new File(dir, "observer-alloc-sites.tmp").isFile(),
                    "no .tmp left behind");

            // 2. EMPTY path (header-only; the previously-tested case)
            ObserverMain.dumpSites(dir, new ConcurrentHashMap<>(), true);
            lines = Files.readAllLines(out.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(lines.size() == 1 && lines.get(0).contains("; final"),
                    "empty final dump is header-only with final marker");

            if (failures > 0) {
                System.out.println("[observer-dump-regression] FAILED ("
                        + failures + ")");
                System.exit(1);
            }
            System.out.println("[observer-dump-regression] PASS (all checks)");
        } finally {
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) f.delete();
            }
            dir.delete();
        }
    }
}
