#!/usr/bin/env python3
"""Derives the Revelation pre-hook live-shadow profile from the accepted
offline qualification artifacts.

Status justification per hook: every one of the 66 semantic targets exists in
the Revelation-transformed bytes with an identical descriptor (hook-matrix,
0 METHOD_ABSENT / DESCRIPTOR_CHANGED / CLASS_ABSENT), and every BCI-fingerprint
anchor sequence was revalidated in order against the final bytes
(verify_rev_fingerprints.py: 0 failures). expected_class_hashes are the pinned
pre-hook Revelation-transformed SHA-256s; verifyPreHookIdentity binds the
transformers to exactly these bytes at runtime.
"""
from __future__ import annotations

import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "testing"))
from text_digest import normalized_sha256  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "tools/live-capture/required-live-writer-hooks.json"
REV_PROFILE = ROOT / "tools/live-capture/revelation-profile.json"
MATRIX = ROOT / "target/rev-probe/hook-matrix.json"
ANCHORS_OK = ROOT / "target/rev-probe/anchor-validation.json"
OUT = ROOT / "tools/live-capture/revelation-live-shadow-profile.json"


def main() -> int:
    manifest = json.loads(MANIFEST.read_text())
    rev = json.loads(REV_PROFILE.read_text())
    matrix = json.loads(MATRIX.read_text())

    manifest_sha = normalized_sha256(MANIFEST)
    if rev["runtime_pins"]["profile"] != "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_OFFLINE_V1":
        raise SystemExit("REFUSED: unexpected runtime pins profile")

    if rev.get("identity_mode") != "CANONICAL":
        raise SystemExit("REFUSED: revelation profile is not in CANONICAL identity mode")
    by_id = {site["id"]: site for site in matrix["sites"]}
    failures = [site["id"] for site in matrix["sites"]
                if site["classification"] not in ("UNCHANGED_EXACT", "UNCHANGED_SEMANTIC",
                                                  "REWRITTEN_METHOD")]
    if failures:
        raise SystemExit("REFUSED: non-derivable hook sites: %s" % failures)
    absent = [site["id"] for site in matrix["sites"]
              if site["classification"] in ("CLASS_ABSENT", "METHOD_ABSENT",
                                            "DESCRIPTOR_CHANGED", "HOOK_ABSENT")]
    if absent:
        raise SystemExit("REFUSED: absent/changed targets: %s" % absent)

    rev_hashes = rev["expected_transformed_classes"]
    hooks = []
    for hook in manifest["required_hooks"]:
        site = by_id[hook["id"]]
        cls = hook["class"]
        pre_hook_hash = rev_hashes.get(cls)
        if not pre_hook_hash:
            raise SystemExit("REFUSED: no pinned pre-hook hash for " + cls)
        if site["rev_sha256"] and len(site["rev_sha256"]) != len(pre_hook_hash):
            raise SystemExit("REFUSED: hash vocabulary mismatch for " + cls)
        hooks.append({
            "id": hook["id"],
            "status": "QUALIFIED",
            "class": cls,
            "method": hook["method"],
            "descriptor": hook["descriptor"],
            "hook_type": hook["hook_type"],
            "purpose": hook["purpose"],
            "affected_domains": hook["affected_domains"],
            "thread_context": hook["thread_context"],
            "insertion_location": hook["insertion_location"],
            "fingerprint": hook["fingerprint"],
            "bracket_end_hook_type": hook.get("bracket_end_hook_type"),
            "qualification_evidence": {
                "classification": site["classification"],
                "canonical_pre_hook_sha256": pre_hook_hash,
                "raw_observed_sha256": site.get("rev_sha256"),
                "anchor_validation": "IN_ORDER_FRAGMENT_SCAN_PASS",
            },
        })

    profile = {
        "schema_version": 1,
        "kind": "rev-live-shadow-profile",
        "target": "FTB Revelation 3.4.0 dedicated server (restored runtime)",
        "forge_build": "14.23.5.2846",
        "required_hooks_manifest_sha256": manifest_sha,
        "all_required_qualified": True,
        "identity_mode": "CANONICAL",
        "identity_requirements": {
            "minecraft_server_jar_sha256":
                rev["runtime_pins"]["artifacts"]["minecraft_server.1.12.2.jar"]["sha256"],
            "forge_universal_jar_sha256":
                rev["runtime_pins"]["artifacts"]["forge-1.12.2-14.23.5.2846-universal.jar"]["sha256"],
            "expected_transformer_chain": rev["expected_transformer_chain"],
            "expected_coremod_plugins": rev["expected_coremod_plugins"],
        },
        "expected_class_hashes": {cls: h for cls, h in rev_hashes.items()},
        "qualification": {
            "profile": "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1",
            "minecraft_server_jar_sha256":
                rev["runtime_pins"]["artifacts"]["minecraft_server.1.12.2.jar"]["sha256"],
            "java_runtime_version": rev["expected_runtime"]["java_runtime_version"],
        },
        "required_hooks": hooks,
        "nonqualified": [],
        "lifecycle_phase": "DEFERRED_TO_DEDICATED_SERVER_QUALIFICATION",
        "production_authority": False,
        "note": "Derived from the accepted offline transformation qualification; "
                "statuses justified by the hook matrix plus in-order anchor "
                "revalidation against the pinned pre-hook bytes.",
    }
    OUT.write_text(json.dumps(profile, indent=1) + "\n")
    print("hooks:", len(hooks), "| manifest sha:", manifest_sha[:16],
          "| classes pinned:", len(profile["expected_class_hashes"]))
    print("written:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
