"""Machine-readable campaign receipts (goal §16)."""
from __future__ import annotations

import json
import time
from pathlib import Path

SCHEMA_VERSION = 1

REQUIRED_FIELDS = [
    "schema_version", "campaign_name", "target", "mode", "test_tier",
    "git_sha", "started_at", "wall_time_s", "hard_timeout_s", "stability_s",
    "evidence_targets", "evidence_observed", "probe_result", "restart_result",
    "exit_reason", "verdict", "failure_reasons", "artifact_paths",
]

EXIT_REASONS = [
    "EVIDENCE_COMPLETE", "TIMEOUT", "BOOT_FAILURE", "SERVER_EXIT",
    "PROBE_FAILURE", "ASSERTION_FAILURE", "USER_INTERRUPT", "TOOLING_ERROR",
]


def write_receipt(out_dir: Path, **fields) -> Path:
    """Write receipt.json with the standard schema; missing fields are None.
    Returns the receipt path."""
    receipt = {k: None for k in REQUIRED_FIELDS}
    receipt.update({
        "schema_version": SCHEMA_VERSION,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    })
    for k, v in fields.items():
        receipt[k] = v
    if receipt["exit_reason"] not in EXIT_REASONS and receipt["exit_reason"]:
        raise ValueError(f"unknown exit_reason {receipt['exit_reason']}")
    out = Path(out_dir) / "receipt.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(receipt, indent=2, sort_keys=True,
                              default=str) + "\n", encoding="utf-8")
    return out


def human_summary(campaign: str, tier: str, verdict: str, exit_reason: str,
                  wall_time_s: float, hard_timeout_s: float,
                  evidence_lines: list[str], stability_s: float,
                  receipt_path: Path) -> str:
    """Goal §17: standard human summary — no log hunting for the result."""
    lines = [
        "=" * 60,
        f"CAMPAIGN: {campaign}",
        f"TIER: {tier.upper()}",
        f"RESULT: {verdict}",
        f"EXIT: {exit_reason}",
        f"TIME: {wall_time_s:.1f}s / {hard_timeout_s:.0f}s ceiling",
        "",
        "EVIDENCE",
    ]
    lines += [f"  {line}" for line in evidence_lines]
    lines += [
        "",
        f"STABILITY: {stability_s:.0f}s",
        "",
        f"RECEIPT: {receipt_path}",
        "=" * 60,
    ]
    return "\n".join(lines)
