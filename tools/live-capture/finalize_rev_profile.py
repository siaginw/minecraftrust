#!/usr/bin/env python3
"""Finalizes the Revelation offline profile data: merges the pinned runtime
manifest, the offline transformation probe receipt, and the derived hook
matrix into one profile artifact (tools/live-capture/revelation-profile.json)
that the independent verifier consumes. All Revelation semantics live HERE
(profile data), never in the verifier code.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PINS = ROOT / "tools/live-capture/revelation-runtime-pins.json"
PROBE = ROOT / "target/rev-probe/qualification.json"
MATRIX = ROOT / "target/rev-probe/hook-matrix.json"
OUT = ROOT / "tools/live-capture/revelation-profile.json"


def main() -> int:
    pins = json.loads(PINS.read_text())
    probe = json.loads(PROBE.read_text())
    matrix = json.loads(MATRIX.read_text())

    forge_build = "%s.%s.%s.%s" % (probe.get("forge_major"), probe.get("forge_minor"),
                                   probe.get("forge_rev"), probe.get("forge_build"))
    profile = {
        "schema_version": 1,
        "kind": "REVELATION_OFFLINE_TRANSFORMATION_PROFILE",
        "target": "FTB Revelation 3.4.0 dedicated server",
        "forge_build": forge_build,
        "minecraft_version": probe.get("forge_mccversion"),
        "runtime_pins": pins,
        "expected_runtime": {
            "forge_major": probe.get("forge_major"),
            "forge_minor": probe.get("forge_minor"),
            "forge_rev": probe.get("forge_rev"),
            "forge_build": probe.get("forge_build"),
            "forge_mccversion": probe.get("forge_mccversion"),
            "forge_mcpversion": probe.get("forge_mcpversion"),
            "java_runtime_version": probe.get("java_runtime_version"),
        },
        "expected_transformer_chain": probe.get("transformers"),
        "expected_coremod_plugins": probe.get("registered_coremod_plugins"),
        "expected_transformed_classes": probe.get("transformed_classes"),
        "transformed_dump_dir": "target/rev-probe/transformed",
        "expected_transformer_chain_sha256":
            __import__("hashlib").sha256(json.dumps(probe.get("transformers")).encode()).hexdigest(),
        "expected_coremod_plugins_sha256":
            __import__("hashlib").sha256(json.dumps(probe.get("registered_coremod_plugins")).encode()).hexdigest(),
        "probe_receipt": {
            "transformers": probe.get("transformers"),
            "registered_coremod_plugins": probe.get("registered_coremod_plugins"),
            "transformed_classes": probe.get("transformed_classes"),
            "required_class_missing": probe.get("required_class_missing"),
        },
        "hook_matrix": matrix,
        "lifecycle_phase": "DEFERRED_TO_DEDICATED_SERVER_QUALIFICATION",
        "production_authority": False,
    }
    OUT.write_text(json.dumps(profile, indent=1) + "\n")
    print("forge_build:", forge_build, "| mc:", profile["minecraft_version"])
    print("transformer chain:", len(profile["expected_transformer_chain"]))
    print("coremod plugins:", len(profile["expected_coremod_plugins"]))
    print("transformed classes pinned:", len(profile["expected_transformed_classes"]))
    print("hook matrix counts:", matrix["counts"])
    print("profile written:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
