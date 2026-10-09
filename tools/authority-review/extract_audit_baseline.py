#!/usr/bin/env python3
"""Audit baseline extractor: reads one campaign run directory and prints
MSPT percentiles (from the m1-metrics 0.5ms histogram), GC/heap/CPU
(region-metrics audit telemetry), and key counters — one canonical line
per run for RUST_ENGINE_BENCHMARK_BASELINE.md.

Histogram: 201 buckets, 0.5ms each — bucket i covers
[i*0.5-0.25, (i+1)*0.5-0.25) ms with [0] = <0.25ms and [200] = >=100ms
(per RustCraftCoreMod). Percentiles computed from bucket midpoints —
0.5ms resolution, honest for MSPT.
"""
import re
import sys
from pathlib import Path


def pct_from_hist(hist, q):
    total = sum(hist)
    if total == 0:
        return float("nan")
    target = total * q
    acc = 0
    for i, c in enumerate(hist):
        acc += c
        if acc >= target:
            return (0.25 if i == 0 else 100.0 if i == 200 else i * 0.5 + 0.25) \
                if i in (0, 200) else i * 0.5
    return 100.0


def main(run_dir):
    run = Path(run_dir)
    # final m1 metrics (falls back to periodic file)
    for name in ("server/m1-metrics-final.txt", "server\\m1-metrics-final.txt"):
        f = run / name
        if f.is_file():
            break
    else:
        f = next(run.glob("server/*m1-metrics*.txt"), None)
    hist, meta = None, {}
    if f and f.is_file():
        text = f.read_text(encoding="utf-8", errors="replace")
        mh = re.search(r"mspt_hist=([0-9,]+)", text)
        if mh:
            hist = [int(x) for x in mh.group(1).rstrip(",").split(",") if x]
        mm = re.search(
            r"mspt_n=(\d+) mspt_max_ms=([\d.]+) mspt_mean_ms=([\d.]+)"
            r" mspt_deadline_over_50ms=(\d+)", text)
        if mm:
            meta = dict(n=int(mm.group(1)), max_ms=float(mm.group(2)),
                        mean_ms=float(mm.group(3)),
                        over50=int(mm.group(4)))
    rm = run / "region-metrics.txt"
    tele = {}
    if rm.is_file():
        t = rm.read_text(encoding="utf-8", errors="replace")
        for k in ("gcCount", "gcTimeMs", "heapUsedMb", "procCpuAvg",
                  "procCpuMax", "lightAuthCommitted", "mirrorStateSets",
                  "zeroStageJobs", "worldRegistryMissing"):
            m = re.search(r"^" + k + r"=(.+)$", t, re.M)
            if m:
                tele[k] = m.group(1)
    over100 = sum(hist[200:]) if hist else 0
    p50 = pct_from_hist(hist, 0.50) if hist else float("nan")
    p95 = pct_from_hist(hist, 0.95) if hist else float("nan")
    p99 = pct_from_hist(hist, 0.99) if hist else float("nan")
    print(f"{run.name}: ticks={meta.get('n','?')} mean={meta.get('mean_ms','?'):.2f}ms"
          if isinstance(meta.get("mean_ms"), float) else f"{run.name}: no-mspt")
    print(f"  mspt mean/p50/p95/p99/max = {meta.get('mean_ms','?')}/"
          f"{p50:.1f}/{p95:.1f}/{p99:.1f}/{meta.get('max_ms','?')}"
          if hist else "  mspt: ABSENT")
    print(f"  >50ms={meta.get('over50','?')} >100ms={over100} "
          f"gc={tele.get('gcCount','?')}/{tele.get('gcTimeMs','?')}ms "
          f"heap={tele.get('heapUsedMb','?')}MB cpu={tele.get('procCpuAvg','?')}"
          f"/{tele.get('procCpuMax','?')}")
    print(f"  committed={tele.get('lightAuthCommitted','?')} "
          f"mirror={tele.get('mirrorStateSets','?')} "
          f"jobs={tele.get('zeroStageJobs','?')} "
          f"regMissing={tele.get('worldRegistryMissing','?')}")


if __name__ == "__main__":
    for d in sys.argv[1:]:
        main(d)
