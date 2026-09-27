"""Two independent Revelation launches compared, class by class.

This is the §8 question answered with measurements rather than an argument: is
the session-INVARIANT identity really launch-invariant, while the exact
identities and the session UUIDs are not?

The static admission policy may name only what survives a relaunch. So for each
class that carries MixinMerged.sessionId provenance, this asserts:

  * the session-invariant identity is IDENTICAL across the two launches, and
  * the exact raw and semantic identities are DIFFERENT, and
  * the session UUIDs are DIFFERENT.

The first is what lets a static policy authorize admission at all. The other two
are what prove the policy is not simply pinning a fingerprint of one launch --
if the exact identity were stable too, a policy could carry it and the whole
split between policy and certificate would be theatre.

A class WITHOUT provenance must behave the opposite way: it has no session UUID
to vary, and its exact identity must be stable across launches, because nothing
about it is process-bound. If such a class moved, the runtime is not
deterministic and no policy built from it could mean anything.

    python -B tools/qualification-v2/rev_invariance_receipt.py \
        --launch-one <identities.json> --launch-two <identities.json> \
        --out <receipt.json>
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launch-one", type=Path, required=True)
    parser.add_argument("--launch-two", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    one = json.loads(args.launch_one.read_text(encoding="utf-8"))
    two = json.loads(args.launch_two.read_text(encoding="utf-8"))
    if one["session_bound_classes"] != two["session_bound_classes"]:
        raise SystemExit("REFUSED: the two launches disagree on WHICH classes carry provenance; "
                         "comparing different sets would prove nothing")

    rows, failures = [], []
    for name in sorted(one["classes"]):
        a, b = one["classes"][name], two["classes"][name]
        if a.get("session_provenance"):
            checks = {
                "session_invariant_identical":
                    a["session_invariant_sha256"] == b["session_invariant_sha256"],
                "raw_sha256_moved": a["raw_sha256"] != b["raw_sha256"],
                "semantic_sha256_moved": a["semantic_sha256"] != b["semantic_sha256"],
                "session_uuid_moved": a["masked_values"] != b["masked_values"],
                "mask_sites_identical": a["masked_locations"] == b["masked_locations"],
                "occurrence_count_identical": a["masked_occurrences"] == b["masked_occurrences"],
            }
        else:
            checks = {
                "no_session_provenance_in_either_launch":
                    not b.get("session_provenance", False),
                "raw_sha256_identical": a["raw_sha256"] == b["raw_sha256"],
                "semantic_sha256_identical": a["semantic_sha256"] == b["semantic_sha256"],
            }
        row = {"class_name": name, "session_provenance": bool(a.get("session_provenance")),
               "checks": checks}
        if a.get("session_provenance"):
            row["session_invariant_sha256"] = a["session_invariant_sha256"]
            row["launch_one_session_uuids"] = a["masked_values"]
            row["launch_two_session_uuids"] = b["masked_values"]
            row["raw_sha256"] = [a["raw_sha256"], b["raw_sha256"]]
        rows.append(row)
        failures += [name + "/" + k for k, v in checks.items() if not v]

    receipt = {
        "schema": "RUSTCRAFT_REVELATION_LAUNCH_INVARIANCE_V1",
        "status": "PASS" if not failures else "FAIL",
        "scope": "TWO INDEPENDENT OFFLINE REVELATION LAUNCHES; transformation qualification only",
        "production_authority": False,
        "launch_one": str(args.launch_one),
        "launch_two": str(args.launch_two),
        "session_bound_classes": one["session_bound_classes"],
        "exact_only_classes": one["exact_only_classes"],
        "rows": rows,
        "failures": failures,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": receipt["status"], "classes": len(rows),
                      "session_bound": len(one["session_bound_classes"]),
                      "failures": failures, "out": str(args.out)}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
