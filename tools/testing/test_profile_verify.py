#!/usr/bin/env python3
"""Negative controls for the generic profile verifier.

Each control mutates ONE aspect of the accepted Revelation profile document
(or its referenced runtime) and requires the verifier to reject it. A green
verifier that accepts any mutated profile is itself a FAIL.
Run:  python -m unittest tools.testing.test_profile_verify -v
"""
from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / "tools/live-capture/revelation-profile.json"
VERIFY = ROOT / "tools/testing/profile_verify.py"
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")


def run_verifier(profile_doc: dict, tmp: Path, name: str) -> tuple[int, str]:
    path = tmp / ("profile-%s.json" % name)
    path.write_text(json.dumps(profile_doc, indent=1))
    result = subprocess.run([sys.executable, "-B", str(VERIFY), "--profile", str(path)],
                            capture_output=True, text=True)
    return result.returncode, result.stdout


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class NegativeControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.original = json.loads(PROFILE.read_text())

    def test_00_positive_control_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_verifier(self.original, Path(tmp), "positive")
            self.assertEqual(code, 0, out)
            self.assertIn("PROFILE VERIFY: PASS", out)

    def mutated(self, apply_mutation, name: str) -> tuple[int, str]:
        doc = copy.deepcopy(self.original)
        apply_mutation(doc)
        with tempfile.TemporaryDirectory() as tmp:
            return run_verifier(doc, Path(tmp), name)

    def test_01_wrong_forge_build_hash_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["runtime_pins"]["artifacts"][
                "forge-1.12.2-14.23.5.2846-universal.jar"]["sha256"] = "0" * 64
        code, out = self.mutated(mutate, "forge-hash")
        self.assertNotEqual(code, 0)
        self.assertIn("artifact:forge-1.12.2-14.23.5.2846-universal.jar", out)

    def test_02_wrong_forge_build_label_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["expected_runtime"]["forge_build"] = "2860"
        code, out = self.mutated(mutate, "forge-label")
        self.assertNotEqual(code, 0)
        self.assertIn("expected_runtime.forge_build", out)

    def test_03_altered_transformed_class_hash_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            site = doc["hook_matrix"]["sites"][0]
            site["rev_sha256"] = "f" * 64
        code, out = self.mutated(mutate, "class-hash")
        self.assertNotEqual(code, 0)
        self.assertIn("observed", out)

    def test_04_missing_hook_target_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["hook_matrix"]["sites"].pop()
        code, out = self.mutated(mutate, "missing-hook")
        self.assertNotEqual(code, 0)
        self.assertIn("hook_matrix.count", out)

    def test_05_changed_descriptor_classification_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["hook_matrix"]["sites"][3]["classification"] = "DESCRIPTOR_CHANGED"
        code, out = self.mutated(mutate, "descriptor")
        self.assertNotEqual(code, 0)
        self.assertIn("DESCRIPTOR_CHANGED", out)

    def test_06_unexpected_transformer_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["expected_transformer_chain"].append("evil.mod.FakeTransformer")
            doc["probe_receipt"]["transformers"] = doc["expected_transformer_chain"]
        code, out = self.mutated(mutate, "transformer")
        self.assertNotEqual(code, 0)
        self.assertIn("transformer_chain", out)

    def test_07_missing_coremod_entry_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["expected_coremod_plugins"].pop()
            doc["probe_receipt"]["registered_coremod_plugins"] = \
                doc["expected_coremod_plugins"]
        code, out = self.mutated(mutate, "coremod")
        self.assertNotEqual(code, 0)
        self.assertIn("coremod_inventory", out)

    def test_08_unrecognized_coremod_entry_rejected(self) -> None:
        def mutate(doc: dict) -> None:
            doc["expected_coremod_plugins"].append(
                {"class": "evil.mod.EvilPlugin", "location": "mods/evil.jar"})
            doc["probe_receipt"]["registered_coremod_plugins"] = \
                doc["expected_coremod_plugins"]
        code, out = self.mutated(mutate, "coremod-unknown")
        self.assertNotEqual(code, 0)
        self.assertIn("coremod_inventory", out)

    def test_09_runtime_artifact_substitution_rejected(self) -> None:
        """A substituted mod jar on disk must be caught by hash recompute."""
        real = json.loads(PROFILE.read_text())["runtime_pins"]["mods"]
        first = sorted(real.keys())[0]
        actual = sha256_of(RT / first)
        self.assertEqual(actual, real[first]["sha256"],
                         "runtime mod jar was substituted outside the test")


if __name__ == "__main__":
    unittest.main()
