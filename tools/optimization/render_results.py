#!/usr/bin/env python3
"""§34 bench-result table generator + §33 research-receipt writer.

Reads bake-off output lines (candidate runs against ONE corpus), renders
the canonical comparison table (markdown + JSON), and writes the
machine-readable research receipt under target/optimization-research/.

Usage:
  python tools/optimization/render_results.py <opt-id> < <bakeoff.log>
  python tools/optimization/render_results.py OPT-LIGHT-003 < run.log
"""
import datetime
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LINE_RE = re.compile(
    r"\[bakeoff\] (?P<name>\S+)\s+mean_ns=(?P<mean>[\d.]+) "
    r"p50_ns=(?P<p50>\d+) p95_ns=(?P<p95>\d+) p99_ns=(?P<p99>\d+) "
    r"digest=(?P<digest>[0-9a-f]+)(?: hits=(?P<hits>\d+)/(?P<lookups>\d+) "
    r"\((?P<hitpct>[\d.]+)%\))?")
CORPUS_RE = re.compile(r"\[bakeoff\] corpus: chunks=(\d+) jobs=(\d+)")
LOCALITY_RE = re.compile(
    r"\[bakeoff\] locality: same-section=([\d.]+)% same-chunk=([\d.]+)% "
    r"other-chunk=([\d.]+)% unique-chunks/job-set=(\d+)")


def main(opt_id, stream):
    cands = []
    corpus = None
    locality = None
    for line in stream:
        m = LINE_RE.search(line)
        if m:
            cands.append(m.groupdict())
            continue
        m = CORPUS_RE.search(line)
        if m:
            corpus = {"chunks": int(m.group(1)), "jobs": int(m.group(2))}
            continue
        m = LOCALITY_RE.search(line)
        if m:
            locality = {
                "same_section_pct": float(m.group(1)),
                "same_chunk_pct": float(m.group(2)),
                "other_chunk_pct": float(m.group(3)),
                "unique_chunks": int(m.group(4)),
            }
    if not cands:
        print("no candidate lines found", file=sys.stderr)
        return 1
    digests = {c["digest"] for c in cands}
    base = next((c for c in cands if c["name"].startswith("A-")), cands[0])
    base_mean = float(base["mean"])

    print(f"| Candidate | mean | p50 | p95 | p99 | Δmean vs {base['name']} "
          f"| hit% | digest |")
    print("|---|---|---|---|---|---|---|---|")
    for c in cands:
        delta = (float(c["mean"]) / base_mean - 1.0) * 100.0
        hit = c.get("hitpct", "-")
        print(f"| {c['name']} | {float(c['mean'])/1e3:.1f}µs | "
              f"{int(c['p50'])/1e3:.1f}µs | {int(c['p95'])/1e3:.1f}µs | "
              f"{int(c['p99'])/1e3:.1f}µs | {delta:+.1f}% | {hit} | "
              f"{c['digest'][:8]} |")
    verdict = ("SEMANTIC-MISMATCH" if len(digests) > 1
               else f"all-equal {next(iter(digests))[:16]}")
    print(f"\nsemantic equality: {verdict}")

    winner = min(cands, key=lambda c: float(c["mean"]))
    receipt = {
        "schema": 1,
        "opt_id": opt_id,
        "researched_at": datetime.date.today().isoformat(),
        "corpus": corpus,
        "locality": locality,
        "semantic_digest": next(iter(digests)) if len(digests) == 1 else None,
        "candidates": cands,
        "winner": winner["name"],
        "source_runs": [
            "target/authority-review/zeroStage-A-zsa14 (corpus mca origin)"
        ],
    }
    out = ROOT / "target" / "optimization-research" / f"{opt_id}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(receipt, indent=1, sort_keys=True) + "\n",
                   encoding="utf-8")
    print(f"receipt: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "OPT-UNKNOWN",
                  sys.stdin))
