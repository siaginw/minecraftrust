#!/usr/bin/env python3
"""Deterministic negative controls for the live-writer profile verifier.

Every required descriptor must classify as exactly one status, and every defect
class must fail closed. These tests are pure: no JVM, no Forge artifacts, no sleep.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import live_profile


def parsed_world():
    """Synthetic javap-style parsed class that mirrors the audited World writer."""
    instructions = [
        (0, "aload_0", ""),
        (4, "invokevirtual", "# // Method net/minecraft/world/World.func_175700_e:()V"),
        (92, "bastore", ""),
        (131, "invokeinterface", "#,  4  // InterfaceMethod it/unimi/dsi/fastutil/longs/Long2ObjectMap.put:(JLjava/lang/Object;)Ljava/lang/Object;"),
        (149, "putfield", "# // Field ran:Z"),
        (200, "return", ""),
    ]
    return {"net.minecraft.world.World": [
        {"name": "func_180501_a", "descriptor": "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
         "instructions": instructions},
    ]}


def hook(**overrides):
    base = {
        "id": "W01", "class": "net.minecraft.world.World", "method": "func_180501_a",
        "descriptor": "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
        "fingerprint": {"kind": "BCI_ASSERTIONS",
                        "assertions": [{"bci": 92, "expect": "bastore"},
                                       {"bci": 149, "expect": "putfield ran:Z"}]},
    }
    base.update(overrides)
    return base


def expected():
    return {"net.minecraft.world.World": "a" * 64}


def class_files():
    return {"net.minecraft.world.World": "a" * 64}


def vanilla():
    return {"net.minecraft.world.World": "b" * 64}


def run_status(h):
    outcome = live_profile.hook_status(h, class_files(), expected(), vanilla(), parsed_world(), None)
    return outcome["status"], outcome.get("detail", "")


class NegativeControls(unittest.TestCase):
    def test_positive_control_qualifies(self):
        status, _ = run_status(hook())
        self.assertEqual(status, "QUALIFIED")

    def test_wrong_method_name_fails_closed(self):
        status, _ = run_status(hook(method="func_999999_bogus"))
        self.assertEqual(status, "MISSING_METHOD")

    def test_wrong_jvm_descriptor_fails_closed(self):
        status, _ = run_status(hook(descriptor="(II)V"))
        self.assertEqual(status, "DESCRIPTOR_MISMATCH")

    def test_wrong_class_hash_fails_closed(self):
        outcome = live_profile.hook_status(hook(), {"net.minecraft.world.World": "c" * 64},
                                           expected(), vanilla(), parsed_world(), None)
        self.assertEqual(outcome["status"], "HASH_MISMATCH")

    def test_altered_insertion_fingerprint_fails_closed(self):
        status, _ = run_status(hook(fingerprint={"kind": "BCI_ASSERTIONS",
                                                 "assertions": [{"bci": 149, "expect": "putfield other:Z"}]}))
        self.assertEqual(status, "FINGERPRINT_MISMATCH")

    def test_shifted_bci_fingerprint_fails_closed(self):
        status, _ = run_status(hook(fingerprint={"kind": "BCI_ASSERTIONS",
                                                 "assertions": [{"bci": 148, "expect": "putfield ran:Z"}]}))
        self.assertEqual(status, "FINGERPRINT_MISMATCH")

    def test_missing_required_class_fails_closed(self):
        outcome = live_profile.hook_status(hook(), {}, expected(), vanilla(), parsed_world(), None)
        self.assertEqual(outcome["status"], "MISSING_CLASS")

    def test_ambiguous_wildcard_descriptor_fails_closed(self):
        status, _ = run_status(hook(descriptor="*"))
        self.assertEqual(status, "AMBIGUOUS_MATCH")

    def test_wrong_forge_build_fails_closed(self):
        manifest = {"forge_build": "14.23.5.2860",
                    "identity_requirements": {"minecraft_server_jar_sha256": "a" * 64,
                                              "forge_jar_sha256": "b" * 64,
                                              "java_runtime_version": "1.8.0_504-b01",
                                              "qualification_profile": "P",
                                              "runtime_pins_sha256": "c" * 64,
                                              "foundation_implementation": {}}}
        receipt = {"status": "PASS", "production_authority": False,
                   "qualified_inputs": {"artifacts": [
                       {"path": "x/forge-1.12.2-14.23.5.2846.jar", "sha256": "b" * 64},
                       {"path": "x/minecraft_server.1.12.2.jar", "sha256": "a" * 64}],
                       "pins": {"sha256": "c" * 64}},
                   "qualification": {"manifest": ""}}
        qualification = {"profile": "P", "java_runtime_version": "1.8.0_504-b01",
                         "transformers": ["t"], "registry_identity_sha256": "r"}
        failures = live_profile.identity_failures(manifest, receipt,
                                                  {"qualified_transformers": ["t"],
                                                   "registry_identity_sha256": "r"}, qualification)
        self.assertTrue(any("forge build" in failure for failure in failures), failures)

    def test_untransformed_vanilla_class_fails_closed(self):
        outcome = live_profile.hook_status(hook(), {"net.minecraft.world.World": "b" * 64},
                                           expected(), vanilla(), parsed_world(), None)
        self.assertEqual(outcome["status"], "TRANSFORM_ORDER_MISMATCH")

    def test_commitment_drift_fails_closed(self):
        outcome = live_profile.hook_status(hook(), class_files(), {}, vanilla(), parsed_world(),
                                           {"net.minecraft.world.World": "d" * 64})
        self.assertEqual(outcome["status"], "HASH_MISMATCH")

    def test_class_structure_marker_detects_absent_member(self):
        text = "public class World { public void run() { aload; } }"
        results = live_profile.fingerprint_matches(
            {"kind": "CLASS_STRUCTURE", "markers": ["declares func_175583_aK", "run calls func_71217_p"]},
            {"name": "run", "descriptor": None, "instructions": [(0, "nop", "")]}, text)
        self.assertEqual([item["match"] for item in results], [False, False])

    def test_aggregate_requires_every_required_hook(self):
        profile = {"required_hooks": [{"status": "QUALIFIED"}, {"status": "MISSING_METHOD"}]}
        armed = all(hook["status"] == "QUALIFIED" for hook in profile["required_hooks"])
        self.assertFalse(armed)
        profile["required_hooks"][1]["status"] = "QUALIFIED"
        self.assertTrue(all(hook["status"] == "QUALIFIED" for hook in profile["required_hooks"]))


class JavapParserTests(unittest.TestCase):
    SAMPLE = """
public class Sample
{
  public boolean func_180501_a(net.minecraft.util.math.BlockPos, int);
    descriptor: (Lnet/minecraft/util/math/BlockPos;I)Z
    Code:
       0: aload_0
       4: invokevirtual #10                 // Method func_189555_a:()I
      78: putfield      #12                 // Field field_186948_c:I
      92: bastore
     200: return
}
"""

    def test_declaration_descriptor_and_instructions_parse(self):
        parsed = live_profile.parse_javap(self.SAMPLE)
        methods = live_profile.class_methods(parsed)
        self.assertEqual(len(methods), 1)
        self.assertEqual(methods[0]["name"], "func_180501_a")
        self.assertEqual(methods[0]["descriptor"], "(Lnet/minecraft/util/math/BlockPos;I)Z")
        self.assertEqual(live_profile.instruction_at(methods[0]["instructions"], 78),
                         ("putfield", "# // Field field_186948_c:I"))
        self.assertIsNone(live_profile.instruction_at(methods[0]["instructions"], 79))

    def test_instruction_matching_distinguishes_return_family(self):
        parsed = live_profile.parse_javap(self.SAMPLE)
        method = live_profile.class_methods(parsed)[0]
        results = live_profile.fingerprint_matches(
            {"kind": "BCI_ASSERTIONS", "assertions": [{"bci": 200, "expect": "return"},
                                                      {"bci": 4, "expect": "invokevirtual func_189555_a"}]},
            method, "")
        self.assertEqual([item["match"] for item in results], [True, True])


if __name__ == "__main__":
    unittest.main(verbosity=2)
