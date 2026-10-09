#!/usr/bin/env python3
"""M1I analyzer: matched OFF vs ON whole-server comparison from instrumented
Revelation/SevTech runs.

Inputs per run label L (pack C|D):
  machine/target<P>/server/m1-metrics.txt     final cumulative metrics
  machine/target<P>/server/gc-<L>.log         GC + safepoint log
  machine/raw/M1I-<L>-<phase>.mspt            cumulative MSPT histograms
  machine/raw/M1I-<L>.timeline                phase wall-clock boundaries
  machine/targetA/client-<L>-burst.jsonl      burst bot stats
  machine/targetA/client-<L>-walk.jsonl       walk bot stats

Output: machine/raw/M1I-summary-<pack>.csv + printed matched comparison.
"""
import csv
import glob
import os
import re
import sys
from collections import defaultdict

BUCKET_MS = 0.5


def hist_from_file(path):
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("mspt_hist="):
            return [int(x) for x in line.strip().split("=", 1)[1].split(",")]
    return None


def stats_from_hist(h):
    n = sum(h)
    if n == 0:
        return None

    def pct(p):
        target = p * n
        cum = 0
        for i, c in enumerate(h):
            cum += c
            if cum >= target:
                # linear interpolation inside bucket i (width 0.5ms)
                prev = cum - c
                frac = (target - prev) / c if c else 0
                return (i + frac) * BUCKET_MS
        return len(h) * BUCKET_MS

    over50 = sum(h[100:])
    return {
        "n": n,
        "p50": pct(0.50), "p95": pct(0.95), "p99": pct(0.99),
        "max": max(i for i, c in enumerate(h) if c) * BUCKET_MS + BUCKET_MS,
        "mean": sum(i * c for i, c in enumerate(h)) * BUCKET_MS / n,
        "over50": over50,
    }


def sub_hist(a, b):
    return [x - y for x, y in zip(a, b)]


def m1_metrics_from_log(runlog):
    """Parse the LAST metrics block of a run log (shutdown dump)."""
    d = {}
    try:
        for line in open(runlog, encoding="utf-8", errors="replace"):
            m = re.search(r"(?<![\w.])(m1_[a-z_]+|transformer\.transformCount)=(\S+)", line)
            if m:
                d[m.group(1)] = m.group(2)
    except OSError:
        pass
    return d


def parse_gc(path):
    """Return list of (sec-of-day, kind, pause_ms, young_mb_freed, 0.0).
    Matches Parallel-GC lines like:
    2026-09-19T22:10:40.677-0500: 5.096: [GC (Metadata GC Threshold) [PSYoungGen: 534774K->72741K(1835008K)] 534774K->72837K(6029312K), 0.0709056 secs] ...
    """
    events = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3}).*?\[GC \(([^)]+)\) \[PSYoungGen: (\d+K)->(\d+K)\(\d+K\)\].*?, (\d+\.\d+) secs\]", line)
        if m:
            sod = (int(m.group(2)) * 3600 + int(m.group(3)) * 60 + int(m.group(4))) + int(m.group(5)) / 1000.0
            def mb(x):
                return int(x[:-1]) / 1024.0
            kind = "young" if "Full" not in m.group(6) else "full"
            events.append((sod, kind, float(m.group(9)) * 1000, mb(m.group(7)) - mb(m.group(8)), 0.0))
            continue
        m2 = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3}).*?\[Full GC \(([^)]+)\).*?, (\d+\.\d+) secs\]", line)
        if m2:
            sod = (int(m2.group(2)) * 3600 + int(m2.group(3)) * 60 + int(m2.group(4))) + int(m2.group(5)) / 1000.0
            events.append((sod, "full", float(m2.group(7)) * 1000, 0.0, 0.0))
    return events


def phase_windows(timeline):
    lines = [l.split() for l in open(timeline) if l.strip()]
    return [(l[0], l[1]) for l in lines]


def sod(ts):
    h, m, s = ts.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else "C"
    srv = f"machine/target{pack}/server"
    labels = sorted({os.path.basename(p)[len("M1I-"):-len(".timeline")]
                     for p in glob.glob(f"machine/raw/M1I-*.timeline")}
                    if False else
                    {os.path.basename(p)[len("M1I-"):-len(".timeline")]
                     for p in glob.glob("machine/raw/M1I-*.timeline")
                     if os.path.basename(p)[len("M1I-"):-len(".timeline")].startswith(("i-c" if pack == "C" else "i-d"))})
    runs = []
    for L in labels:
        if not os.path.exists(f"machine/target{pack}/server/gc-{L}.log"):
            continue
        tl = f"machine/raw/M1I-{L}.timeline"
        phases = phase_windows(tl)
        hists = {}
        for ph, _ in phases:
            hists[ph] = hist_from_file(f"machine/raw/M1I-{L}-{ph}.mspt")
        gc = parse_gc(f"{srv}/gc-{L}.log")
        m1 = m1_metrics_from_log(f"{srv}/run-{L}.log")
        last_snap = None
        for ph, _ in phases:
            if ph.endswith("_end"):
                last_snap = ph
        run = {"label": L, "phases": phases, "hists": hists, "gc": gc, "m1": m1,
               "final_hist": hist_from_file(f"machine/raw/M1I-{L}-{last_snap}.mspt") if last_snap else None}
        runs.append(run)

    # whole-run stats + per-phase stats via histogram subtraction
    for run in runs:
        print(f"\n== {run['label']} (whole run)")
        whole = stats_from_hist(run["final_hist"]) if run["final_hist"] else None
        if whole:
            print(f"  MSPT n={whole['n']} p50={whole['p50']:.1f} p95={whole['p95']:.1f} "
                  f"p99={whole['p99']:.1f} max={whole['max']:.0f} mean={whole['mean']:.1f} over50ms={whole['over50']}")
        for i in range(len(run["phases"]) - 1):
            # window runs FROM boundary i TO boundary i+1; its workload label
            # is the phase that ENDS at boundary i+1 (e.g. boot_done->idle_end = idle)
            ph, ph2 = run["phases"][i][0], run["phases"][i + 1][0]
            wl = ph2.replace("_end", "")
            a, b = run["hists"].get(ph), run["hists"].get(ph2)
            if a and b:
                st = stats_from_hist(sub_hist(b, a))
                if st and st["n"] > 0:
                    t0 = sod(run["phases"][i][1]); t1 = sod(run["phases"][i + 1][1])
                    dur = max(1, t1 - t0)
                    gcev = [e for e in run["gc"] if t0 <= e[0] < t1]
                    young = [e for e in gcev if e[1] == "young"]
                    fulls = [e for e in gcev if e[1] == "full"]
                    alloc_mb = sum(e[3] - e[4] for e in young if e[3] > e[4])
                    pause = sum(e[2] for e in gcev)
                    print(f"  [{wl:>12}] n={st['n']:6d} p50={st['p50']:5.1f} p95={st['p95']:5.1f} "
                          f"p99={st['p99']:5.1f} max={st['max']:5.0f} over50={st['over50']:3d} | "
                          f"yGC={len(young):3d} fullGC={len(fulls)} pauseMs={pause:7.0f} "
                          f"allocMB={alloc_mb:8.0f} ({alloc_mb/dur:6.1f} MB/s)")
        m = run["m1"]
        print(f"  m1: native={m.get('m1_native_packets_transmitted')} java={m.get('m1_java_packets_transmitted')} "
              f"eligible={m.get('m1_native_eligible')} ineligible={m.get('m1_native_ineligible')} "
              f"fallbacks={m.get('m1_native_fallbacks')} pregate={m.get('m1_native_pregate_skipped')}")


if __name__ == "__main__":
    main()
