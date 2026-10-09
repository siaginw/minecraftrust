"""Adversarial graph controls independent of Forge availability."""
import copy
import unittest

from tools.testing.qualification_certificate import (
    Evidence, EvidenceStatus as S, Maturity as M, digest_json, evaluate,
)


CTX = {"profile_id": "SYNTHETIC", "observation_session": "run-1",
       "identity_schema": "CANONICAL_ID_V2"}


def node(key, dependencies=(), status=S.PASS, value=None):
    return Evidence(key, key, status, digest_json(value or key), dependencies)


class CertificateControls(unittest.TestCase):
    def test_transitive_invalidation_diamond_and_independent_branch(self):
        records = [node("artifact", status=S.FAIL), node("class", ("artifact",)),
                   node("hook", ("class",)), node("writer", ("class",)),
                   node("live", ("hook", "writer")), node("compression")]
        cert = evaluate(records, {M.OBSERVED: ["artifact"],
                                  M.OFFLINE_QUALIFIED: ["hook", "writer"]}, context=CTX)
        rows = {row["id"]: row for row in cert["evidence"]}
        self.assertEqual("FAIL", cert["status"])
        for key in ("class", "hook", "writer", "live"):
            self.assertEqual("INVALIDATED", rows[key]["status"])
        self.assertEqual("PASS", rows["compression"]["status"])

    def test_unrelated_failure_does_not_revoke_independent_operation(self):
        cert = evaluate([node("frame"), node("offline", ("frame",)),
                         node("unsupported-world", status=S.FAIL)],
                        {M.OBSERVED: ["frame"], M.OFFLINE_QUALIFIED: ["offline"]}, context=CTX)
        self.assertEqual("PASS", cert["status"])
        self.assertEqual(M.OFFLINE_QUALIFIED.value, cert["maturity"])

    def test_missing_dependency_never_passes(self):
        cert = evaluate([node("hook", ("missing",))],
                        {M.OBSERVED: ["hook"], M.OFFLINE_QUALIFIED: ["hook"]}, context=CTX)
        self.assertEqual("INCOMPLETE", cert["status"])
        self.assertIsNone(cert["maturity"])
        missing = next(row for row in cert["evidence"] if row["id"] == "missing")
        self.assertEqual("MISSING_EVIDENCE", missing["kind"])
        self.assertEqual("INCOMPLETE", missing["status"])

    def test_dependencies_cannot_change_after_record_construction(self):
        dependencies = ["first"]
        record = node("hook", dependencies)
        dependencies.append("second")
        self.assertEqual(("first",), record.dependencies)
        for invalid in ("first", [""], [1]):
            with self.assertRaises(ValueError):
                node("hook", invalid)

    def test_empty_stages_do_not_vacuously_qualify(self):
        cert = evaluate([], {}, context=CTX)
        self.assertEqual("INCOMPLETE", cert["status"])
        self.assertIsNone(cert["maturity"])

    def test_cannot_skip_offline_stage_to_live(self):
        cert = evaluate([node("observed"), node("live")],
                        {M.OBSERVED: ["observed"], M.LIVE_QUALIFIED: ["live"]},
                        context=CTX, requested=M.LIVE_QUALIFIED)
        self.assertEqual("INCOMPLETE", cert["status"])
        self.assertEqual("OBSERVED", cert["maturity"])

    def test_authority_is_impossible_even_with_all_claims(self):
        cert = evaluate([node("all")], {stage: ["all"] for stage in M},
                        context=CTX, requested=M.AUTHORITY_AUTHORIZED)
        self.assertEqual("INCOMPLETE", cert["status"])
        self.assertFalse(cert["production_authority"])
        self.assertEqual("PERFORMANCE_QUALIFIED", cert["maturity"])

    def test_cycle_and_duplicate_ids_rejected(self):
        for records in ([node("a", ("b",)), node("b", ("a",))],
                        [node("a"), node("a")], [node("a", ("a",))]):
            with self.assertRaises(ValueError):
                evaluate(records, {}, context=CTX)

    def test_pass_needs_observed_digest(self):
        for digest in (None, "expected", "A" * 64):
            with self.assertRaises(ValueError):
                Evidence("x", "x", S.PASS, digest)

    def test_v1_and_missing_session_rejected(self):
        for change in ({"identity_schema": "CANONICAL_ID_V1"},
                       {"observation_session": ""}):
            with self.assertRaises(ValueError):
                evaluate([], {}, context={**CTX, **change})

    def test_graph_input_order_stable_but_observation_change_rebinds(self):
        records = [node("a"), node("b", ("a",))]
        req = {M.OBSERVED: ["a"], M.OFFLINE_QUALIFIED: ["b"]}
        one = evaluate(records, req, context=CTX)
        two = evaluate(reversed(records), req, context=CTX)
        self.assertEqual(one, two)
        changed = evaluate([node("a", value="changed"), records[1]], req, context=CTX)
        self.assertNotEqual(one["certificate_sha256"], changed["certificate_sha256"])
        tampered = copy.deepcopy(one)
        tampered.pop("certificate_sha256")
        tampered["production_authority"] = True
        self.assertNotEqual(one["certificate_sha256"], digest_json(tampered))

    def test_generator_requirements_preserve_failure_closure(self):
        cert = evaluate([node("a", status=S.FAIL)],
                        {M.OBSERVED: iter(["a"]),
                         M.OFFLINE_QUALIFIED: iter(["a"])}, context=CTX)
        self.assertEqual("FAIL", cert["status"])


if __name__ == "__main__":
    unittest.main()
