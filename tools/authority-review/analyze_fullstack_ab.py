#!/usr/bin/env python3
"""FULL-STACK BENCHMARK analysis: per-run phase statistics (observer JSONL
time-joined to phases.json), Arm-B authority witnesses (region-metrics),
and paired comparisons. Usage:

  python analyze_fullstack_ab.py <run_dir> [<run_dir> ...] [--pairs]

Distinctions kept explicit (goal §10): time reduction = (J-R)/J; speedup =
J/R; throughput improvement = (R-J)/J. Sample counts and denominators ship
with every percentile; phases with zero ticks are marked INSUFFICIENT.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def load_json(p):
    try:
        return json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def pct(sorted_vals, q):
    if not sorted_vals:
        return None
    idx = min(len(sorted_vals) - 1, max(0, int(round(q / 100.0
                       * (len(sorted_vals) - 1)))))
    return sorted_vals[idx]


def phase_stats(run_dir: Path):
    phases = load_json(run_dir / "phases.json") or []
    obs_path = run_dir / "server" / "observer-metrics.jsonl"
    samples = []
    if obs_path.is_file():
        for line in obs_path.read_text(encoding="utf-8",
                                       errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                samples.append(json.loads(line))
            except ValueError:
                continue
    # intervals with tick data
    ivals = [s for s in samples if "tick_ns" in s]
    bounds = {}
    for e in phases:
        if e.get("boundary") and e.get("phase") not in bounds:
            bounds[e["phase"]] = {}
        if e.get("boundary"):
            bounds[e["phase"]][e["boundary"]] = e["t"]
    out = {}
    for phase, b in bounds.items():
        if "start" not in b or "end" not in b:
            continue
        t0, t1 = b["start"], b["end"]
        ticks = []
        proc_cpu = srv_cpu = srv_alloc = gc_ms = 0
        gc_n = 0
        heap_max_used = heap_max_com = 0
        n_iv = 0
        for s in ivals:
            if t0 <= s["t"] < t1:
                n_iv += 1
                ticks.extend(s["tick_ns"])
                proc_cpu += s.get("proc_cpu_ns", 0)
                srv_cpu += s.get("srv_cpu_ns", 0)
                srv_alloc += s.get("srv_alloc_b", 0)
                gc_n += s.get("gc_n", 0)
                gc_ms += s.get("gc_ms", 0)
                heap_max_used = max(heap_max_used, s.get("heap_used", 0))
                heap_max_com = max(heap_max_com, s.get("heap_committed", 0))
        ticks_ms = sorted(v / 1e6 for v in ticks)
        st = {
            "intervals": n_iv,
            "ticks": len(ticks_ms),
            "mspt_mean": (sum(ticks_ms) / len(ticks_ms)) if ticks_ms else None,
            "mspt_p50": pct(ticks_ms, 50),
            "mspt_p95": pct(ticks_ms, 95),
            "mspt_p99": pct(ticks_ms, 99),
            "mspt_max": ticks_ms[-1] if ticks_ms else None,
            "over_50": sum(1 for v in ticks_ms if v > 50),
            "over_100": sum(1 for v in ticks_ms if v > 100),
            "proc_cpu_s": proc_cpu / 1e9,
            "srv_cpu_s": srv_cpu / 1e9,
            "srv_alloc_mb": srv_alloc / 1e6,
            "gc_n": gc_n,
            "gc_ms": gc_ms,
            "heap_max_used_mb": heap_max_used / 1e6,
            "heap_max_committed_mb": heap_max_com / 1e6,
            "duration_s": (t1 - t0) / 1000.0,
        }
        if st["ticks"] == 0:
            st["verdict"] = "INSUFFICIENT_VALID_EVIDENCE"
        out[phase] = st
    return out, phases


AUTH_WITNESSES = {
    "world_registry": ("REGISTERS", r"(\d+)"),
    "sections_refreshed": ("SECTIONS_REFRESHED", r"(\d+)"),
    "validation_mismatch": ("SECTIONS_VALIDATION_MISMATCH", r"(\d+)"),
    "dv_diverge": ("DUAL_VERIFY_DIVERGE", r"(\d+)"),
    "light_admitted": ("lightAuthAdmitted", r"(\d+)"),
    "light_committed": ("lightAuthCommitted", r"(\d+)"),
    "light_fallbacks": ("FALLBACKS", r"(\d+)"),
    "mirror_published": ("MIRROR_PUBLISHED_CELLS", r"(\d+)"),
    "mirror_committed": ("COMMITTED_CELLS", r"(\d+)"),
}


def rust_witnesses(run_dir: Path):
    p = run_dir / "region-metrics.txt"
    if not p.is_file():
        return None
    txt = p.read_text(encoding="utf-8", errors="replace")
    out = {}
    for name, (key, pat) in AUTH_WITNESSES.items():
        m = re.search(rf"(?:^|\n){key}={pat}", txt)
        out[name] = int(m.group(1)) if m else None
    # region read/write activity lives in the server log per the campaign's
    # regionRead/regionWrite hook summaries
    log = run_dir / "server.log"
    if log.is_file():
        lt = log.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"regionRead\.hook enabled=\S+ mode=\S+ cap=\S+ "
                      r"readSelected=(\d+) readSuccess=(\d+)", lt)
        if m:
            out["region_reads_selected"] = int(m.group(1))
            out["region_reads_success"] = int(m.group(2))
        mw = re.search(r"regionWrite\.hook enabled=\S+ mode=\S+ cap=\S+ "
                       r"entryCalls=(\d+) rustAdmitted=(\d+) rustOk=(\d+) "
                       r"rustFailed=(\d+) vanillaFallbacks=(\d+)", lt)
        if mw:
            out["region_write_entries"] = int(mw.group(1))
            out["region_write_rust_ok"] = int(mw.group(3))
            out["region_write_rust_failed"] = int(mw.group(4))
            out["region_write_vanilla_fallbacks"] = int(mw.group(5))
    return out


def analyze_run(run_dir: Path):
    rec = load_json(run_dir / "fullstack-run.json") or {}
    stats, phases = phase_stats(run_dir)
    anchor_ev = {}
    for e in phases:
        if "anchor" in e:
            anchor_ev = e
    result = {
        "run": str(run_dir),
        "arm": rec.get("arm"),
        "verdict": rec.get("verdict"),
        "boot_s": next((e.get("boot_s") for e in phases
                        if e.get("phase") == "boot"
                        and e.get("boundary") == "end"), None),
        "probe_evidence": rec.get("probe_evidence"),
        "commands": rec.get("commands"),
        "confirmed_cells": rec.get("confirmed_cells"),
        "expected_cells": rec.get("expected_cells"),
        "anchor": anchor_ev.get("anchor"),
        "phases": stats,
    }
    if rec.get("arm") == "rust":
        result["witnesses"] = rust_witnesses(run_dir)
    return result


def pair_summary(a: dict, b: dict):
    """a=java, b=rust — distinct statistics, never interchanged."""
    out = {"pair": [a["run"], b["run"]]}
    for phase in sorted(set(a["phases"]) & set(b["phases"])):
        ja, rb = a["phases"][phase], b["phases"][phase]
        ent = {}
        for metric in ("duration_s", "mspt_mean", "mspt_p95", "mspt_p99",
                       "proc_cpu_s", "srv_cpu_s", "srv_alloc_mb"):
            jv, rv = ja.get(metric), rb.get(metric)
            if isinstance(jv, (int, float)) and isinstance(rv, (int, float)) \
                    and jv != 0 and metric in ("duration_s", "mspt_mean",
                                               "proc_cpu_s", "srv_cpu_s"):
                ent[metric] = {
                    "java": jv, "rust": rv,
                    "reduction": round((jv - rv) / jv, 4),
                    "speedup": round(jv / rv, 4) if rv else None,
                }
            else:
                ent[metric] = {"java": jv, "rust": rv}
        for metric in ("ticks", "over_50", "over_100", "gc_n", "gc_ms"):
            ent[metric] = {"java": ja.get(metric), "rust": rb.get(metric)}
        out[phase] = ent
    return out


def group_comparison(results, split_at=None):
    """Pooled arms with a spread guard (retro FS-002): when a group's
    internal spread exceeds the mean delta, more runs are needed before
    claiming an effect — print SPREAD_EXCEEDS_DELTA rather than letting a
    favorable mean stand alone (fs-fix1/2 spread 12.2-21.1 MSPT swamped
    every plausible fix effect). split_at=N compares the first N runs
    ("before") against the rest ("after") — the same-arm rust-before/
    rust-after case."""
    import statistics as st
    if split_at is not None and 0 < split_at < len(results):
        arms = {"before": results[:split_at], "after": results[split_at:]}
    else:
        arms = {}
        for r in results:
            arms.setdefault(r.get("arm"), []).append(r)
    names = sorted(arms)
    out = {"groups": {k: len(v) for k, v in arms.items()}}
    if len(names) < 2:
        out["note"] = "group comparison needs two groups (both arms, or " \
                      "--split N for before/after)"
        return out
    ga, gb = names[0], names[1]
    phases = set.intersection(*[set(r["phases"]) for r in results])
    comp = {}
    for phase in sorted(phases):
        for metric in ("mspt_mean", "mspt_p95", "srv_alloc_mb",
                       "proc_cpu_s", "duration_s"):
            jv = [r["phases"][phase].get(metric) for r in arms[ga]]
            rv = [r["phases"][phase].get(metric) for r in arms[gb]]
            jv = [v for v in jv if isinstance(v, (int, float))]
            rv = [v for v in rv if isinstance(v, (int, float))]
            if not jv or not rv:
                continue
            jm, rm = st.mean(jv), st.mean(rv)
            jspread = max(jv) - min(jv)
            rspread = max(rv) - min(rv)
            delta = abs(jm - rm)
            comp[f"{phase}.{metric}"] = {
                ga: [round(v, 3) for v in jv],
                gb: [round(v, 3) for v in rv],
                "mean_reduction": round((jm - rm) / jm, 4) if jm else None,
                "mean_speedup": round(jm / rm, 4) if rm else None,
                "spreads": {ga: round(jspread, 3),
                            gb: round(rspread, 3)},
                "verdict": ("SPREAD_EXCEEDS_DELTA — more runs needed "
                            "before claiming an effect"
                            if max(jspread, rspread) > delta
                            else "delta_exceeds_spread"),
            }
    out["metrics"] = comp
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+", type=Path)
    ap.add_argument("--pairs", action="store_true",
                    help="group consecutive (java, rust) runs into pairs")
    ap.add_argument("--split", type=int, default=None,
                    help="group comparison: first N runs = 'before', rest "
                         "= 'after' (rust-before/rust-after case)")
    args = ap.parse_args()
    results = [analyze_run(r) for r in args.runs]
    print(json.dumps(results, indent=1))
    if args.pairs:
        pairs = []
        for i in range(0, len(results) - 1, 2):
            a, b = results[i], results[i + 1]
            if a.get("arm") == "java" and b.get("arm") == "rust":
                pairs.append(pair_summary(a, b))
            else:
                pairs.append({"error": f"runs {i},{i+1} not java-then-rust"})
        print("=== PAIRS ===")
        print(json.dumps(pairs, indent=1))
    # retro FS-002: pooled group comparison with the spread guard — run
    # even without --pairs when both arms are present; --split forces the
    # before/after grouping (rust-vs-rust)
    arms = {r.get("arm") for r in results}
    if args.split is not None or arms >= {"java", "rust"}:
        print("=== GROUP COMPARISON (spread-guarded) ===")
        print(json.dumps(group_comparison(results, split_at=args.split),
                         indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
