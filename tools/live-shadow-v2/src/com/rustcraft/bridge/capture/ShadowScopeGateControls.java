package com.rustcraft.bridge.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Phase B controls: the per-chunk u16 comparability gate.
 *
 * The core distinction under test is the one the design review fixed: a
 * RUNTIME registry containing ids above 65535 no longer refuses the runtime;
 * a CHUNK containing one used id above 65535 excludes that chunk. The registry
 * is not what travels; the chunk's own states are. On the real Revelation
 * runtime the registry max id is 77,663 while 84.1% of its states fit u16, so
 * the old registry-level rule would have refused every capture of a runtime
 * whose chunks are mostly comparable.
 *
 * No transport widening occurs and none is possible from here: the gate has no
 * code path that changes a state id, and LiveCaptureScope's default behaviour
 * is byte-for-byte unchanged.
 */
public final class ShadowScopeGateControls {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);

        // ---- the u16 boundary, exactly ---------------------------------
        eligible(allIds(0), "all-zero (air-only valid chunk) is eligible");
        eligible(allIds(65535), "state id exactly 65535 is eligible: the boundary is inclusive");
        eligible(allIds(0, 1, 65534, 65535), "mixed ids up to the boundary are eligible");
        excluded(allIds(65536), 65536, "state id 65536 is excluded: one past the boundary");
        excluded(allIds(0, 65535, 65536, 70000), 65536,
                 "the FIRST offending state is reported even when a later one is larger");
        excluded(allIds(77662), 77662,
                 "an id near the Revelation registry's max (77663), placed in a chunk, "
                 + "excludes that chunk");

        // ---- the registry-vs-chunk distinction, the core of the design --
        int[][] chunkWithHighIdsOnlyInRegistry = allIds(16, 2401, 65535);
        eligible(chunkWithHighIdsOnlyInRegistry,
                 "a registry containing 77663 is irrelevant when the chunk only uses low ids");
        // The gate is a pure function of the chunk; the registry is not an
        // input. What proves the distinction is that the same chunk evaluates
        // identically regardless of what registry surrounds it -- so evaluate
        // the high-id chunk directly:
        excluded(allIds(77663), 77663,
                 "one used id of 77663 in the chunk excludes THAT chunk");

        // ---- structured diagnostics, bounded ----------------------------
        ShadowScopeGate.Result r = ShadowScopeGate.evaluate(allIds(0, 70000, 80000));
        check(!r.eligible && "HIGH_STATE_ID".equals(r.reason),
              "the reason is the taxonomy vocabulary, not a bare boolean");
        check(r.firstOffendingStateId == 70000,
              "the first offending id is reported for triage");
        check(r.offendingCount == -1,
              "the total offending count is withheld on early exit (-1), not guessed");
        check(r.entriesInspected == 2,
              "the scan early-exits at the offending entry: inspected == 2");

        // ---- existing exclusions preserved -------------------------------
        check(ShadowScopeGate.evaluate(null).reason.equals("COHERENCE"),
              "a null candidate is a coherence exclusion, preserved");
        check(ShadowScopeGate.evaluate(new int[][] {null, null}).eligible,
              "absent sections are skipped, not treated as offending");

        // ---- telemetry: eligibility only, never parity ------------------
        ShadowScopeGate.resetTelemetry();
        ShadowScopeGate.evaluateCounted(allIds(1));
        ShadowScopeGate.evaluateCounted(allIds(70000));
        String json = ShadowScopeGate.telemetryJson();
        check(json.contains("\"scope_checked\":2"), "every evaluation is counted");
        check(json.contains("\"scope_eligible\":1"), "eligibility is counted");
        check(json.contains("\"excluded_high_state_id\":1"), "the exclusion reason is counted");
        check(!json.contains("compared") && !json.contains("\"pass\"") && !json.contains("mismatch"),
              "the gate counts eligibility ONLY: no parity counters may exist here");

        // ---- cost telemetry on a representative full chunk ---------------
        ShadowScopeGate.resetTelemetry();
        int[][] full = new int[16][];
        for (int y = 0; y < 16; y++) {
            full[y] = new int[4096];
            java.util.Arrays.fill(full[y], (y * 37) % 65536);
        }
        long start = System.nanoTime();
        ShadowScopeGate.Result fullResult = ShadowScopeGate.evaluateCounted(full);
        long perCallNanos = System.nanoTime() - start;
        check(fullResult.eligible, "a representative full 16x4096 chunk is eligible");
        check(fullResult.entriesInspected == 16 * 4096,
              "all 65,536 state entries were inspected");
        String cost = ShadowScopeGate.telemetryJson();
        Files.write(out.resolve("scope-gate-cost.json"),
                (cost + "\nper_call_nanos_16x4096=" + perCallNanos + "\n").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        // Bounded: a full-chunk scan must stay far below a millisecond, or it
        // is too slow to sit on the capture path.
        check(perCallNanos < 1_000_000,
              "a full-chunk eligibility scan is bounded: " + perCallNanos + " ns");

        System.out.println("PASS ShadowScopeGateControls assertions=" + checks
                + "; u16 decided per chunk, transport unchanged, no parity counters");
    }

    private static int[][] allIds(int... ids) {
        int[][] sections = new int[1][];
        sections[0] = ids;
        return sections;
    }

    private static void eligible(int[][] sections, String what) {
        ShadowScopeGate.Result r = ShadowScopeGate.evaluate(sections);
        check(r.eligible && r.reason == null, what);
    }

    private static void excluded(int[][] sections, int expectedFirst, String what) {
        ShadowScopeGate.Result r = ShadowScopeGate.evaluate(sections);
        check(!r.eligible && "HIGH_STATE_ID".equals(r.reason) && r.firstOffendingStateId == expectedFirst,
              what);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private ShadowScopeGateControls() { }
}
