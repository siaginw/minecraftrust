package com.rustcraft.bridge.capture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-chunk shadow comparability gate: the single place the u16 rule lives.
 *
 * <p>The design decision this class implements, fixed in
 * {@code docs/research/V2_LIVE_SHADOW_ARCHITECTURE.md} before any campaign
 * ran:</p>
 *
 * <blockquote>a registry that exceeds u16 is <em>permitted</em>, and a capture
 * whose states all fit u16 is admitted; any state above 65535 rejects that
 * capture as {@code EXCLUDED_HIGH_STATE_ID}.</blockquote>
 *
 * <p>The previous rule rejected the whole RUNTIME when its global registry
 * contained any id above 65535. On the real Revelation runtime the registry
 * max id is 77,663, so that rule would have refused every capture before
 * looking at a single chunk -- while 84.1% of the registry's 35,711 states fit
 * u16. The decision moves from the registry to the chunk because the registry
 * is not what travels: the chunk's own states are.</p>
 *
 * <p>No truncation, no masking, no modulo, no widening, no reinterpretation.
 * The transport width does not change: a chunk containing one state Rust
 * cannot represent is excluded and counted, never quietly damaged into one it
 * can. An exclusion is not a pass and never enters the parity
 * denominator.</p>
 *
 * <p>This gate answers exactly one question: <em>if the shadow pipeline were
 * active, could this chunk enter it?</em> It performs no comparison, calls no
 * Rust code, and touches no live state. It is a pure function of the state
 * ids in the candidate snapshot.</p>
 */
public final class ShadowScopeGate {

    /** The transport contract: states travel as u16. Not a knob. */
    public static final int MAX_STATE_ID = 0xFFFF;

    // ---- structured result -------------------------------------------------

    /** Machine-readable eligibility. Never a bare boolean. */
    public static final class Result {
        public final boolean eligible;
        /** A taxonomy vocabulary reason when not eligible; null when it is. */
        public final String reason;
        /** Bounded diagnostics; never the whole chunk. */
        public final int offendingCount;
        public final int firstOffendingStateId;
        /** Cost telemetry: how many state entries were inspected. */
        public final int entriesInspected;

        private Result(boolean eligible, String reason, int offendingCount,
                int firstOffendingStateId, int entriesInspected) {
            this.eligible = eligible;
            this.reason = reason;
            this.offendingCount = offendingCount;
            this.firstOffendingStateId = firstOffendingStateId;
            this.entriesInspected = entriesInspected;
        }
    }

    /**
     * Evaluates one candidate snapshot's section states.
     *
     * <p>{@code sections} is the same shape the capture pipeline extracts:
     * one {@code int[]} of palette-relative state ids per section, or
     * {@code null} for an absent section. The ids must already be GLOBAL ids
     * (the form the transport carries); a caller passing palette indices would
     * be gating the wrong thing.</p>
     *
     * <p>Early exit on the first offending state: the scan is on the capture
     * path, and once a chunk is excluded, knowing the rest changes nothing.
     * The first offending id is still reported so a mismatch triages without a
     * re-scan; the total count is NOT computed when early-exiting, and is
     * reported as -1 rather than guessed.</p>
     */
    public static Result evaluate(int[][] sections) {
        if (sections == null)
            return new Result(false, "COHERENCE", 0, -1, 0);
        int inspected = 0;
        for (int[] section : sections) {
            if (section == null) continue;
            for (int i = 0; i < section.length; i++) {
                inspected++;
                int state = section[i];
                if (state < 0)
                    // A negative id is not a width problem, it is a broken
                    // candidate: the coherence contract, not the u16 rule.
                    return new Result(false, "COHERENCE", 1, state, inspected);
                if (state > MAX_STATE_ID)
                    return new Result(false, "HIGH_STATE_ID", -1, state, inspected);
            }
        }
        return new Result(true, null, 0, -1, inspected);
    }

    // ---- telemetry ---------------------------------------------------------

    private static final AtomicLong SCOPE_CHECKED = new AtomicLong();
    private static final AtomicLong SCOPE_ELIGIBLE = new AtomicLong();
    private static final AtomicLong EXCLUDED_HIGH_STATE_ID = new AtomicLong();
    private static final AtomicLong EXCLUDED_COHERENCE = new AtomicLong();
    private static final AtomicLong STATES_INSPECTED = new AtomicLong();
    private static final AtomicLong CHECK_NANOS = new AtomicLong();
    private static final AtomicLong WORST_CHECK_NANOS = new AtomicLong();

    /**
     * Evaluates and counts. Counters are per-reason and never folded into one
     * "shadow_failed" bucket: a full queue is healthy backpressure, a high
     * state id is an honest exclusion, and a coherence failure is a
     * correctness finding. Collapsing them is how the difference disappears.
     *
     * <p>Deliberately NO compared/pass/mismatch counters here. This gate
     * decides eligibility only; the parity counters belong to the comparison
     * stage, which does not exist yet.</p>
     */
    public static Result evaluateCounted(int[][] sections) {
        long start = System.nanoTime();
        Result result = evaluate(sections);
        long elapsed = System.nanoTime() - start;
        SCOPE_CHECKED.incrementAndGet();
        STATES_INSPECTED.addAndGet(result.entriesInspected);
        CHECK_NANOS.addAndGet(elapsed);
        for (;;) {
            long worst = WORST_CHECK_NANOS.get();
            if (elapsed <= worst || WORST_CHECK_NANOS.compareAndSet(worst, elapsed)) break;
        }
        if (result.eligible) {
            SCOPE_ELIGIBLE.incrementAndGet();
        } else if ("HIGH_STATE_ID".equals(result.reason)) {
            EXCLUDED_HIGH_STATE_ID.incrementAndGet();
        } else {
            EXCLUDED_COHERENCE.incrementAndGet();
        }
        return result;
    }

    public static String telemetryJson() {
        long checked = SCOPE_CHECKED.get();
        return "{\"scope_checked\":" + checked
                + ",\"scope_eligible\":" + SCOPE_ELIGIBLE.get()
                + ",\"excluded_high_state_id\":" + EXCLUDED_HIGH_STATE_ID.get()
                + ",\"excluded_coherence\":" + EXCLUDED_COHERENCE.get()
                + ",\"states_inspected\":" + STATES_INSPECTED.get()
                + ",\"check_nanos_total\":" + CHECK_NANOS.get()
                + ",\"check_nanos_worst\":" + WORST_CHECK_NANOS.get()
                + ",\"check_nanos_mean\":" + (checked == 0 ? 0 : CHECK_NANOS.get() / checked)
                + "}";
    }

    public static void resetTelemetry() {
        SCOPE_CHECKED.set(0); SCOPE_ELIGIBLE.set(0);
        EXCLUDED_HIGH_STATE_ID.set(0); EXCLUDED_COHERENCE.set(0);
        STATES_INSPECTED.set(0); CHECK_NANOS.set(0); WORST_CHECK_NANOS.set(0);
    }

    private ShadowScopeGate() { throw new AssertionError(); }
}
