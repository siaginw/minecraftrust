"""Actual CLI failure-contract controls; runtime qualification tests live beside the engine.

Historical ten-control Revelation replay is pinned by the explicit H1 historical
harness. These controls cannot pass just because the verifier crashed.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / "tools/testing/profile_verify.py"


class ProfileVerifyCliControls(unittest.TestCase):
    def setUp(self):
        base = ROOT / "target/qualification-cli-controls"
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=base)
        self.path = Path(self.temporary.name).resolve()
        self.assertTrue(self.path.is_relative_to(base.resolve()))
        self.manifest = self.path / "runtime.json"
        self.profile = self.path / "profile.json"
        self.manifest.write_text(json.dumps({
            "schema": "RUSTCRAFT_RUNTIME_MANIFEST_V2", "runtime_root": str(self.path),
            "inventories": {}, "collector": {}, "identity_tool": {},
        }), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def run_cli(self, expected, requested="OFFLINE_QUALIFIED", expected_maturity=None):
        result = subprocess.run([
            sys.executable, "-B", str(VERIFY), "--manifest", str(self.manifest),
            "--profile", str(self.profile), "--output-root", str(self.path / "runs"),
            "--requested", requested,
        ], cwd=ROOT, capture_output=True, text=True, timeout=30)
        self.assertEqual({"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[expected], result.returncode,
                         result.stdout + result.stderr)
        self.assertEqual("", result.stderr, "unexpected traceback/error-only failure")
        self.assertEqual(1, len(result.stdout.splitlines()))
        summary = json.loads(result.stdout)
        self.assertEqual(expected, summary["status"])
        self.assertIs(summary["production_authority"], False)
        cert = Path(summary["certificate"]).resolve()
        self.assertTrue(cert.is_relative_to(self.path / "runs"))
        value = json.loads(cert.read_text())
        self.assertEqual(expected, value["status"])
        self.assertIs(value["production_authority"], False)
        self.assertEqual(expected_maturity, value["maturity"])
        self.assertTrue(value["evidence"], "a failure needs an inspectable evidence reason")
        return value

    def integration_fixture(self, **kwargs):
        from tools.testing.test_qualification_engine import EngineFixture
        fixture = EngineFixture(self.path / ("fixture-" + str(len(list(self.path.iterdir())))), **kwargs)
        self.manifest, self.profile = fixture.manifest_path, fixture.profile_path
        return fixture

    def test_real_java_cli_qualifies_both_raw_and_v2_scoped_fixtures(self):
        for mode in ("RAW_SHA256", "CANONICAL_ID_V2"):
            with self.subTest(mode=mode):
                self.integration_fixture(identity_mode=mode)
                self.run_cli("PASS", expected_maturity="OFFLINE_QUALIFIED")

    def test_actual_collector_failure_preserves_stderr(self):
        self.integration_fixture(mode="nonzero")
        cert = self.run_cli("FAIL")
        logs = json.loads((Path(cert["context"]["output_directory"]) / "process-log.json").read_text())
        collector = next(row for row in logs if row["label"] == "collector")
        self.assertEqual(7, collector["returncode"])
        self.assertIn("intentional stderr", collector["stderr"])

    def test_offline_cli_cannot_claim_live_qualification(self):
        self.integration_fixture()
        self.run_cli("INCOMPLETE", "LIVE_QUALIFIED", "OFFLINE_QUALIFIED")

    def test_missing_profile_is_incomplete_not_exception(self):
        self.run_cli("INCOMPLETE")

    def test_missing_runtime_manifest_is_incomplete(self):
        self.manifest.unlink()
        self.profile.write_text("{}", encoding="utf-8")
        self.run_cli("INCOMPLETE")

    def test_malformed_json_is_structured_failure(self):
        self.profile.write_text('{"schema":', encoding="utf-8")
        self.run_cli("FAIL")

    def test_duplicate_json_keys_are_rejected(self):
        self.profile.write_text('{"schema":"x","schema":"y"}', encoding="utf-8")
        cert = self.run_cli("FAIL")
        definition = next(row for row in cert["evidence"] if row["id"] == "definitions")
        self.assertIn("duplicate", definition["detail"].lower())

    def test_nonfinite_json_is_rejected(self):
        self.profile.write_text('{"schema":NaN}', encoding="utf-8")
        cert = self.run_cli("FAIL")
        definition = next(row for row in cert["evidence"] if row["id"] == "definitions")
        self.assertIn("nonfinite", definition["detail"].lower())

    def test_wrong_root_types_are_failures_with_receipts(self):
        for value in ([], None, "profile", 42):
            with self.subTest(value=value):
                self.profile.write_text(json.dumps(value), encoding="utf-8")
                self.run_cli("FAIL")

    def test_embedded_v1_success_cannot_substitute_for_observation(self):
        self.profile.write_text(json.dumps({
            "schema_version": 1, "identity_mode": "CANONICAL", "all_required_qualified": True,
            "probe_receipt": {"status": "PASS", "transformed_classes": {"fake.Class": "a" * 64}},
            "production_authority": False,
        }), encoding="utf-8")
        self.run_cli("FAIL")

    def test_authority_request_does_not_open_a_gate(self):
        cert = self.run_cli("INCOMPLETE", "AUTHORITY_AUTHORIZED")
        stage = next(row for row in cert["stages"] if row["maturity"] == "AUTHORITY_AUTHORIZED")
        self.assertIn("PRODUCTION_AUTHORITY_DISABLED", stage["blocked_by"])


if __name__ == "__main__":
    unittest.main()
