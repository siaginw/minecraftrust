"""Phase A controls: state machine, taxonomy, receipt schema.

Bounded fixtures only. No live campaign is started or simulated -- the whole
point of these controls is that the safety shell is proven BEFORE the shadow
pipeline exists, so that when it exists it cannot be misused by accident.

Run: python -B tools/live-shadow-v2/test_foundation.py
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import capability as cap
import receipt as receipt_mod
from receipt import CampaignReceipt, ReceiptError
from taxonomy import (CAMPAIGN_STOPPING, NOT_PARITY, Outcome, parity_denominator,
                      parity_rate, validate_counts)

CHECKS = 0


class StateMachineControls(unittest.TestCase):

    def test_valid_progression(self):
        """The one permitted path to closure, end to end."""
        c = cap.ShadowCapability("chunk-packet")
        c.offline_qualified("offline PASS")
        c.shadow_eligible("scope review")
        c.shadow_active("campaign opened")
        c.shadow_closed("closure criteria met")
        self.assertEqual(c.state, cap.State.LIVE_SHADOW_CLOSED)
        self.assertEqual(len(c.history), 5)

    def test_illegal_jump_refused(self):
        """No skipping. UNAVAILABLE cannot become ACTIVE."""
        c = cap.ShadowCapability("chunk-packet")
        with self.assertRaises(cap.IllegalTransition):
            c.shadow_active("skipped two states")
        self.assertEqual(c.state, cap.State.UNAVAILABLE,
                         "a refused transition must not move the state")

    def test_offline_qualified_cannot_reach_closure(self):
        c = cap.ShadowCapability("chunk-packet")
        c.offline_qualified("offline PASS")
        with self.assertRaises(cap.IllegalTransition):
            c.shadow_closed("closure without ever opening")

    def test_closure_is_terminal(self):
        """A closed campaign does not reopen. A new campaign is a new campaign."""
        c = cap.ShadowCapability("chunk-packet")
        for step in (c.offline_qualified, c.shadow_eligible, c.shadow_active, c.shadow_closed):
            step("x")
        with self.assertRaises(cap.IllegalTransition):
            c.shadow_active("reopen")

    def test_every_authority_edge_refused_from_every_reachable_state(self):
        """The load-bearing control. Not one reachable state can reach authority."""
        for start in (cap.State.UNAVAILABLE, cap.State.OFFLINE_QUALIFIED,
                      cap.State.LIVE_SHADOW_ELIGIBLE, cap.State.LIVE_SHADOW_ACTIVE,
                      cap.State.LIVE_SHADOW_CLOSED):
            for target in cap.AUTHORITY_STATES:
                c = cap.ShadowCapability("chunk-packet", state=start)
                with self.assertRaises(cap.AuthorityTransitionRefused,
                                       msg="%s -> %s must be refused" % (start, target)):
                    c.transition(target, "should be impossible")
                self.assertEqual(c.state, start)

    def test_no_allowed_edge_reaches_authority(self):
        """Structural, independent of any runtime: inspect the table itself."""
        for (start, target) in cap.ALLOWED:
            self.assertNotIn(target, cap.AUTHORITY_STATES,
                             "the transition table contains an authority edge")

    def test_duplicate_transition_refused_not_idempotent(self):
        """Explicit design choice: a repeat is an error, not a no-op. A caller
        that re-sends a transition is a caller that lost track of state, and
        silently absorbing it would hide that."""
        c = cap.ShadowCapability("chunk-packet")
        c.offline_qualified("q")
        with self.assertRaises(cap.IllegalTransition):
            c.offline_qualified("q again")

    def test_stopped_campaign_returns_to_eligible_not_unavailable(self):
        """A stopped run does not un-qualify the runtime."""
        c = cap.ShadowCapability("chunk-packet")
        c.offline_qualified("q"); c.shadow_eligible("s"); c.shadow_active("go")
        c.transition(cap.State.LIVE_SHADOW_ELIGIBLE, "stopped")
        self.assertEqual(c.state, cap.State.LIVE_SHADOW_ELIGIBLE)

    def test_history_records_every_transition(self):
        c = cap.ShadowCapability("chunk-packet")
        c.offline_qualified("reason-text")
        record = c.history[-1]
        self.assertEqual((record["from"], record["to"]),
                         (cap.State.UNAVAILABLE, cap.State.OFFLINE_QUALIFIED))
        self.assertEqual(record["reason"], "reason-text")
        self.assertFalse(record["authority"])

    def test_unknown_state_refused_at_construction(self):
        with self.assertRaises(ValueError):
            cap.ShadowCapability("x", state="AUTHORITY_APPROVED_SOMEHOW")


class TaxonomyControls(unittest.TestCase):

    def test_denominator_is_pass_plus_mismatch_only(self):
        counts = {o.value: 3 for o in taxonomy_ALL()}
        self.assertEqual(parity_denominator(counts), 6,
                         "pass(3) + mismatch(3), nothing else")

    def test_excluded_never_counts_as_parity(self):
        counts = {o.value: 0 for o in taxonomy_ALL()}
        counts[Outcome.EXCLUDED.value] = 10_000
        counts[Outcome.COMPARE_PASS.value] = 1
        self.assertEqual(parity_denominator(counts), 1)
        self.assertEqual(parity_rate(counts), 1.0,
                         "10,000 exclusions must not dilute the rate")

    def test_dropped_never_counts_as_parity(self):
        counts = {o.value: 0 for o in taxonomy_ALL()}
        counts[Outcome.DROPPED.value] = 999
        counts[Outcome.COMPARE_PASS.value] = 1
        counts[Outcome.COMPARE_MISMATCH.value] = 1
        self.assertEqual(parity_rate(counts), 0.5)

    def test_mismatch_is_in_the_denominator(self):
        """A campaign half-broken by mismatches must not read as perfect."""
        counts = {o.value: 0 for o in taxonomy_ALL()}
        counts[Outcome.COMPARE_PASS.value] = 50
        counts[Outcome.COMPARE_MISMATCH.value] = 50
        self.assertEqual(parity_rate(counts), 0.5)

    def test_empty_campaign_is_none_not_zero(self):
        counts = {o.value: 0 for o in taxonomy_ALL()}
        self.assertIsNone(parity_rate(counts),
                          "no comparisons is not a 0% pass rate")

    def test_every_non_compared_outcome_is_not_parity(self):
        for outcome in NOT_PARITY:
            self.assertNotIn(outcome, receipt_mod.__dict__.get("_COMPARED", ()) or
                             (Outcome.COMPARE_PASS, Outcome.COMPARE_MISMATCH))

    def test_mismatch_and_disqualification_are_campaign_stopping(self):
        self.assertIn(Outcome.COMPARE_MISMATCH, CAMPAIGN_STOPPING)
        self.assertIn(Outcome.DISQUALIFIED, CAMPAIGN_STOPPING)

    def test_unknown_outcome_counter_refused(self):
        with self.assertRaises(ValueError):
            validate_counts({"COMPARE_PASS": 1, "MYSTERY_BUCKET": 1})

    def test_negative_counter_refused(self):
        with self.assertRaises(ValueError):
            validate_counts({Outcome.DROPPED.value: -1})

    def test_counters_exceeding_observed_refused(self):
        """Events cannot appear from nowhere; a receipt that says they did is corrupt."""
        with self.assertRaises(ValueError):
            validate_counts({Outcome.COMPARE_PASS.value: 5, "observed": 3})


class ReceiptControls(unittest.TestCase):

    def _receipt(self):
        return CampaignReceipt("probe-campaign",
                               static={"runtime_manifest_identity": "a" * 64,
                                       "static_recipe_sha256": "b" * 64,
                                       "writer_plan_sha256": "c" * 64,
                                       "dll_sha256": "d" * 64},
                               provenance={"output_dir": "X:/somewhere"})

    def test_round_trip_deterministic(self):
        r = self._receipt()
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "receipt.json"
            h1 = r.write(p)
            h2 = r.write(p)
            self.assertEqual(h1, h2, "identical receipts must serialise identically")
            loaded = CampaignReceipt.load(p)
            self.assertEqual(loaded["canonical_sha256"], r.to_document()["canonical_sha256"])

    def test_hash_stable_across_output_location(self):
        """The canonical block must not move with where the receipt is written."""
        r = self._receipt()
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            h1 = r.write(Path(a) / "one.json")
            h2 = r.write(Path(b) / "a" / "much" / "deeper" / "path.json")
            self.assertEqual(h1, h2)

    def test_static_identity_refuses_a_location_field(self):
        with self.assertRaises(ReceiptError):
            CampaignReceipt("x", static={"runtime_root": "D:/runtime"}, provenance={})

    def test_phase_a_c_counters_are_all_zero_and_that_is_correct(self):
        document = self._receipt().to_document()
        for outcome in taxonomy_ALL():
            self.assertEqual(document["counts"][outcome.value], 0)
        self.assertEqual(document["parity_denominator"], 0)
        self.assertIsNone(document["parity_rate"])

    def test_production_authority_false_enforced_on_serialise(self):
        r = self._receipt()
        r.production_authority = True
        with self.assertRaises(ReceiptError):
            r.to_document()

    def test_production_authority_true_refused_on_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "r.json"
            r = self._receipt()
            r.write(p)
            document = json.loads(p.read_text(encoding="utf-8"))
            document["production_authority"] = True
            p.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaises(ReceiptError):
                CampaignReceipt.load(p)

    def test_closure_without_compared_events_refused(self):
        """The campaign cannot close on a receipt with an empty denominator."""
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "r.json"
            r = self._receipt()
            r.verdict = "CLOSED"
            r.write(p)
            with self.assertRaises(ReceiptError):
                CampaignReceipt.load(p)

    def test_exclusion_requires_a_known_reason(self):
        r = self._receipt()
        with self.assertRaises(ReceiptError):
            r.observe(Outcome.EXCLUDED)
        with self.assertRaises(ReceiptError):
            r.observe(Outcome.EXCLUDED, reason="MADE_UP_REASON")

    def test_recomputed_denominator_must_match_on_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "r.json"
            r = self._receipt()
            r.write(p)
            document = json.loads(p.read_text(encoding="utf-8"))
            document["parity_denominator"] = 7  # drifted aggregate
            p.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaises(ReceiptError):
                CampaignReceipt.load(p)

    def test_observing_events_maintains_the_invariant(self):
        r = self._receipt()
        r.observe(Outcome.EXCLUDED, reason="HIGH_STATE_ID")
        r.observe(Outcome.COMPARE_PASS)
        r.observe(Outcome.DROPPED)
        self.assertEqual(r.counts["observed"], 3)
        self.assertEqual(r.parity(), 1)
        self.assertEqual(r.scope_reasons["HIGH_STATE_ID"], 1)


def taxonomy_ALL():
    from taxonomy import ALL
    return ALL


if __name__ == "__main__":
    unittest.main(verbosity=1)
