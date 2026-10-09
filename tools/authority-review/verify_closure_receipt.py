#!/usr/bin/env python3
"""Independent closure verifier and input receipt generator.

Before any packet authority review or bounded experiment, this tool
independently parses and verifies the machine-readable evidence from the
closed live-shadow campaign, ensuring all predeclared closure criteria
are satisfied, that cross-session reconciliation is mathematically exact,
and that production authority is strictly FALSE.

Emits target/authority-review/closure-input-receipt.json.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CAMPAIGN_RECEIPT_PATH = ROOT / "target" / "closure-campaign" / "campaign-receipt.json"
CANONICAL_PROFILE_PATH = ROOT / "tools" / "live-capture" / "revelation-live-shadow-profile.json"
CAMPAIGN_JAR_PATH = ROOT / "target" / "rustcraft-campaign.jar"
OUTPUT_DIR = ROOT / "target" / "authority-review"
OUTPUT_RECEIPT_PATH = OUTPUT_DIR / "closure-input-receipt.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def get_git_commit() -> str:
    res = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(ROOT),
                         capture_output=True, text=True, check=True)
    return res.stdout.strip()


def verify_closure():
    if not CAMPAIGN_RECEIPT_PATH.is_file():
        raise FileNotFoundError(f"Campaign receipt not found at {CAMPAIGN_RECEIPT_PATH}")
    if not CANONICAL_PROFILE_PATH.is_file():
        raise FileNotFoundError(f"Canonical profile not found at {CANONICAL_PROFILE_PATH}")
    if not CAMPAIGN_JAR_PATH.is_file():
        raise FileNotFoundError(f"Campaign jar not found at {CAMPAIGN_JAR_PATH}")

    campaign_data = json.loads(CAMPAIGN_RECEIPT_PATH.read_text(encoding="utf-8"))

    # 1. Authority False Invariant
    if campaign_data.get("production_authority") is not False:
        raise ValueError("FATAL: campaign_receipt.production_authority is not False!")
    if campaign_data.get("closure", {}).get("production_authority") is not False:
        raise ValueError("FATAL: campaign_receipt.closure.production_authority is not False!")

    # 2. Closure Verdict & State
    closure_info = campaign_data.get("closure", {})
    if closure_info.get("verdict") != "CLOSED":
        raise ValueError(f"FATAL: closure verdict is {closure_info.get('verdict')} (expected CLOSED)")
    if closure_info.get("state") != "LIVE_SHADOW_CLOSED":
        raise ValueError(f"FATAL: closure state is {closure_info.get('state')} (expected LIVE_SHADOW_CLOSED)")
    if len(closure_info.get("unmet", [])) != 0:
        raise ValueError(f"FATAL: unmet criteria exist: {closure_info.get('unmet')}")

    # 3. Independent Verification from Raw Session Files
    sessions_data = campaign_data.get("sessions", [])
    if len(sessions_data) < 2:
        raise ValueError(f"FATAL: expected >= 2 sessions, got {len(sessions_data)}")

    recomputed_sessions = []
    total_compare_pass = 0
    total_compare_mismatch = 0
    total_observed = 0
    total_dropped = 0
    total_excluded = 0
    total_disqualified = 0
    total_infra_failure = 0
    total_io_origin = 0
    total_rcnsnap02 = 0
    all_incarnations = set()
    coord_incarnations = {}

    for s_info in sessions_data:
        s_idx = s_info["session"]
        s_dir = ROOT / "target" / "closure-campaign" / f"session-{s_idx}"
        events_path = s_dir / "live-shadow-events.jsonl"
        journal_path = s_dir / "shadow-journal.jsonl"
        receipt_path = s_dir / "live-shadow-receipt.json"

        if not events_path.is_file():
            raise FileNotFoundError(f"Events file missing: {events_path}")
        if not journal_path.is_file():
            raise FileNotFoundError(f"Journal file missing: {journal_path}")
        if not receipt_path.is_file():
            raise FileNotFoundError(f"Receipt file missing: {receipt_path}")

        s_pass = 0
        s_mismatch = 0
        s_dropped = 0
        s_excluded = 0
        s_disqualified = 0
        s_infra = 0
        s_rcnsnap02 = 0

        # Read journal
        for line in journal_path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            entry = json.loads(line)
            outcome = entry.get("outcome")
            if outcome == "COMPARE_PASS":
                s_pass += 1
                if "byteExact" in entry.get("detail", ""):
                    s_rcnsnap02 += 1
            elif outcome == "COMPARE_MISMATCH":
                s_mismatch += 1
            elif outcome == "DROPPED":
                s_dropped += 1
            elif outcome == "EXCLUDED":
                s_excluded += 1
            elif outcome == "DISQUALIFIED":
                s_disqualified += 1
            elif outcome == "INFRA_FAILURE":
                s_infra += 1

        # Read events
        s_io_origin = 0
        s_identities = set()
        s_coords = {}
        for line in events_path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            ev = json.loads(line)
            world_id = ev.get("worldId")
            chunk_id = ev.get("chunkId")
            incarnation = ev.get("incarnation")
            chunk_x = ev.get("chunkX")
            chunk_z = ev.get("chunkZ")
            if ev.get("ioAdopted") is True:
                s_io_origin += 1
            key = (world_id, chunk_id, incarnation)
            s_identities.add(key)
            all_incarnations.add(key)
            c_key = (world_id, chunk_x, chunk_z)
            if incarnation is not None:
                s_coords.setdefault(c_key, set()).add(incarnation)
                coord_incarnations.setdefault(c_key, set()).add(incarnation)

        s_reloads = sum(1 for incs in s_coords.values() if len(incs) > 1)
        s_observed = s_pass + s_mismatch + s_dropped + s_excluded + s_disqualified + s_infra

        # Verify against session receipt
        s_receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if s_receipt.get("gateDisqualified") is True:
            raise ValueError(f"Session {s_idx} was gate disqualified!")
        if s_receipt.get("mismatchSeen") is True:
            raise ValueError(f"Session {s_idx} saw mismatches!")

        # Verify against campaign receipt session entry
        claimed_metrics = s_info["metrics"]
        claimed_outcomes = claimed_metrics["outcomes"]
        assert claimed_outcomes["COMPARE_PASS"] == s_pass, f"S{s_idx} COMPARE_PASS mismatch"
        assert claimed_outcomes["COMPARE_MISMATCH"] == s_mismatch, f"S{s_idx} COMPARE_MISMATCH mismatch"
        assert claimed_outcomes["DROPPED"] == s_dropped, f"S{s_idx} DROPPED mismatch"
        assert claimed_outcomes["EXCLUDED"] == s_excluded, f"S{s_idx} EXCLUDED mismatch"
        assert claimed_metrics["io_origin_comparisons"] == s_io_origin, f"S{s_idx} io_origin mismatch"
        assert claimed_metrics["identities"] == len(s_identities), f"S{s_idx} identities mismatch"
        assert claimed_metrics["reload_cycles"] == s_reloads, f"S{s_idx} reloads mismatch"
        assert claimed_metrics["denominator"] == s_pass + s_mismatch, f"S{s_idx} denominator mismatch"

        total_compare_pass += s_pass
        total_compare_mismatch += s_mismatch
        total_observed += s_observed
        total_dropped += s_dropped
        total_excluded += s_excluded
        total_disqualified += s_disqualified
        total_infra_failure += s_infra
        total_io_origin += s_io_origin
        total_rcnsnap02 += s_rcnsnap02

        recomputed_sessions.append({
            "session": s_idx,
            "process_id": s_info["process_id"],
            "session_id": s_info["session_id"],
            "pass": s_pass,
            "mismatch": s_mismatch,
            "dropped": s_dropped,
            "excluded": s_excluded,
            "observed": s_observed,
            "io_origin": s_io_origin,
            "identities": len(s_identities),
            "reload_cycles": s_reloads,
            "rcnsnap02": s_rcnsnap02
        })

    # 4. Global Reconciliation
    denominator = total_compare_pass + total_compare_mismatch
    assert denominator == campaign_data["denominator"], "Denominator mismatch"
    assert denominator >= 2000, f"Denominator {denominator} < 2000"
    assert total_compare_mismatch == 0, f"Unexplained mismatch {total_compare_mismatch} != 0"

    drop_rate = total_dropped / total_observed if total_observed else 0.0
    assert drop_rate <= 0.10, f"Drop rate {drop_rate:.4f} > 0.10"

    exclusion_rate = total_excluded / total_observed if total_observed else 0.0
    assert exclusion_rate <= 0.60, f"Exclusion rate {exclusion_rate:.4f} > 0.60"

    assert total_io_origin >= 200, f"IO origin {total_io_origin} < 200"
    assert len(all_incarnations) >= 300, f"Distinct incarnations {len(all_incarnations)} < 300"

    total_reload_cycles = sum(1 for incs in coord_incarnations.values() if len(incs) > 1)
    assert total_reload_cycles >= 20, f"Reload cycles {total_reload_cycles} < 20"
    assert total_disqualified == 0, f"Disqualifications {total_disqualified} != 0"
    assert total_infra_failure == 0, f"Infra failure {total_infra_failure} != 0"

    # Hashes
    campaign_receipt_sha = sha256_file(CAMPAIGN_RECEIPT_PATH)
    canonical_profile_sha = sha256_file(CANONICAL_PROFILE_PATH)
    campaign_jar_sha = sha256_file(CAMPAIGN_JAR_PATH)
    commit_sha = get_git_commit()

    # Verify hashes match campaign receipt assertions
    assert campaign_data["canonical_profile_sha256"] == canonical_profile_sha, "Profile hash mismatch"
    assert campaign_data["campaign_jar_sha256"] == campaign_jar_sha, "Campaign jar hash mismatch"

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    input_receipt = {
        "schema": "RUSTCRAFT_AUTHORITY_REVIEW_INPUT_RECEIPT_V1",
        "timestamp_utc": subprocess.run(["git", "log", "-1", "--format=%cI"],
                                        cwd=str(ROOT), capture_output=True, text=True).stdout.strip(),
        "commit_sha": commit_sha,
        "closure_campaign_receipt_sha256": campaign_receipt_sha,
        "canonical_profile_sha256": canonical_profile_sha,
        "campaign_jar_sha256": campaign_jar_sha,
        "closure_verdict": closure_info.get("verdict"),
        "closure_state": closure_info.get("state"),
        "production_authority": False,
        "metrics": {
            "denominator": denominator,
            "compare_pass": total_compare_pass,
            "compare_mismatch": total_compare_mismatch,
            "observed": total_observed,
            "dropped": total_dropped,
            "drop_rate": drop_rate,
            "excluded": total_excluded,
            "exclusion_rate": exclusion_rate,
            "io_origin_comparisons": total_io_origin,
            "distinct_incarnations": len(all_incarnations),
            "reload_cycles": total_reload_cycles,
            "disqualified": total_disqualified,
            "infra_failures": total_infra_failure,
            "rcnsnap02_comparisons": total_rcnsnap02,
        },
        "sessions": recomputed_sessions,
        "gate_status": "READY_FOR_BOUNDED_AUTHORITY_EXPERIMENT"
    }

    OUTPUT_RECEIPT_PATH.write_text(json.dumps(input_receipt, indent=2) + "\n", encoding="utf-8")
    print(f"[OK] Closure independently verified. Receipt emitted to {OUTPUT_RECEIPT_PATH}")
    print(json.dumps(input_receipt["metrics"], indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(verify_closure())
