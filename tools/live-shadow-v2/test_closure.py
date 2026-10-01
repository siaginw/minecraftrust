"""Controls for the closure evaluator: every boundary in the goal's
section 21, as machine checks. The evaluator has no prose latitude; these
controls prove it."""
import unittest

import closure
from closure import ClosureInput, evaluate


def totals(**overrides):
    """A fully-closed baseline; each control mutates one criterion."""
    base = dict(
        compare_pass=2000, compare_mismatch=0, unexplained_mismatch=0,
        dropped=0, excluded=0, disqualified=0, infra_failure=0,
        integrity_threatening_infra=0,
        io_origin_comparisons=200, distinct_incarnations=300,
        reload_cycles=20, observed_events=2000, production_authority=False)
    base.update(overrides)
    return ClosureInput(**base)


class ClosureControls(unittest.TestCase):
    def test_1999_comparisons_not_closed(self):
        result = evaluate(totals(compare_pass=1999))
        self.assertEqual(result["verdict"], "NOT_CLOSED")
        self.assertIn("denominator", result["unmet"])

    def test_2000_all_criteria_potentially_closed(self):
        result = evaluate(totals())
        self.assertEqual(result["verdict"], "CLOSED")
        self.assertEqual(result["state"], "LIVE_SHADOW_CLOSED")

    def test_one_unexplained_mismatch_not_closed(self):
        result = evaluate(totals(compare_pass=1999, compare_mismatch=1,
                                 unexplained_mismatch=1))
        self.assertEqual(result["verdict"], "NOT_CLOSED")
        self.assertIn("unexplained_mismatch", result["unmet"])

    def test_explained_mismatch_still_counts_in_denominator(self):
        # A mismatch with an explanation remains a comparison; the
        # denominator rule is COMPARE_PASS + COMPARE_MISMATCH.
        result = evaluate(totals(compare_pass=1999, compare_mismatch=1,
                                 unexplained_mismatch=0))
        self.assertEqual(result["criteria"]["denominator"]["value"], 2000)
        self.assertEqual(result["verdict"], "CLOSED")

    def test_drop_exactly_10_percent_allowed(self):
        result = evaluate(totals(dropped=200, observed_events=2000))
        self.assertTrue(result["criteria"]["drop_rate"]["met"])
        self.assertEqual(result["verdict"], "CLOSED")

    def test_drop_above_10_percent_not_closed(self):
        result = evaluate(totals(dropped=201, observed_events=2000))
        self.assertFalse(result["criteria"]["drop_rate"]["met"])
        self.assertIn("drop_rate", result["unmet"])

    def test_exclusion_exactly_60_percent_allowed(self):
        result = evaluate(totals(excluded=1200, observed_events=2000))
        self.assertTrue(result["criteria"]["exclusion_rate"]["met"])
        self.assertEqual(result["verdict"], "CLOSED")

    def test_exclusion_above_60_percent_not_closed(self):
        result = evaluate(totals(excluded=1201, observed_events=2000))
        self.assertIn("exclusion_rate", result["unmet"])

    def test_199_io_origin_not_closed(self):
        result = evaluate(totals(io_origin_comparisons=199))
        self.assertIn("io_origin_comparisons", result["unmet"])

    def test_299_incarnations_not_closed(self):
        result = evaluate(totals(distinct_incarnations=299))
        self.assertIn("distinct_incarnations", result["unmet"])

    def test_19_reloads_not_closed(self):
        result = evaluate(totals(reload_cycles=19))
        self.assertIn("reload_cycles", result["unmet"])

    def test_production_authority_true_refused(self):
        result = evaluate(totals(production_authority=True))
        self.assertEqual(result["verdict"], "REFUSED_PRODUCTION_AUTHORITY")
        self.assertEqual(result["production_authority"], False)

    def test_empty_denominator_refused(self):
        result = evaluate(totals(compare_pass=0, compare_mismatch=0,
                                 observed_events=100, dropped=100))
        self.assertEqual(result["verdict"], "REFUSED_EMPTY_DENOMINATOR")

    def test_integrity_threatening_infra_not_closed(self):
        result = evaluate(totals(infra_failure=1,
                                 integrity_threatening_infra=1))
        self.assertEqual(result["verdict"], "NOT_CLOSED")
        self.assertIn("integrity_threatening_infra", result["unmet"])

    def test_closed_state_never_reaches_authority(self):
        result = evaluate(totals())
        self.assertNotIn("AUTHORITY_ELIGIBLE", result["state"])
        self.assertNotIn("AUTHORITY_APPROVED", result["state"])

    def test_constants_are_the_predeclared_numbers(self):
        self.assertEqual(closure.MIN_DENOMINATOR, 2000)
        self.assertEqual(closure.MAX_DROP_RATE, 0.10)
        self.assertEqual(closure.MAX_EXCLUSION_RATE, 0.60)
        self.assertEqual(closure.MIN_IO_ORIGIN, 200)
        self.assertEqual(closure.MIN_INCARNATIONS, 300)
        self.assertEqual(closure.MIN_RELOADS, 20)

    def test_reload_cycles_counted_by_coordinate_multi_incarnation(self):
        import tempfile
        import json
        from pathlib import Path
        from run_closure_campaign import session_metrics
        with tempfile.TemporaryDirectory() as tmpdir:
            tmppath = Path(tmpdir)
            events_file = tmppath / "live-shadow-events.jsonl"
            rows = [
                {"worldId": 1, "chunkId": 100, "chunkX": 10, "chunkZ": 20, "incarnation": 1, "ioAdopted": False},
                {"worldId": 1, "chunkId": 101, "chunkX": 11, "chunkZ": 20, "incarnation": 1, "ioAdopted": False},
                {"worldId": 1, "chunkId": 102, "chunkX": 10, "chunkZ": 20, "incarnation": 2, "ioAdopted": True},
            ]
            events_file.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
            m = session_metrics(tmppath)
            self.assertEqual(m["reload_cycles"], 1)
            self.assertEqual(m["identities"], 3)
            self.assertEqual(m["io_origin_comparisons"], 1)


if __name__ == "__main__":
    unittest.main()
