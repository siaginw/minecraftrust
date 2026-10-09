#!/usr/bin/env python3
"""Post-optimization profile rebase extractor: reads one campaign run and
emits the component-cost table used to rank current hotspots (goal
RUST_ENGINE_POST_OPTIMIZATION_PROFILE_REBASED).

Sources per run: region-metrics.txt (hook counters, audit telemetry),
server/m1-metrics-final.txt (MSPT histogram + per-subsystem dumps).
All times are RUN-CUMULATIVE unless marked per-op.
"""
import re
import sys
from pathlib import Path

NS_FIELDS = [
    # (counter, nanoseconds, what)
    ("JNI_NANOS", "rust light kernel (runZeroStage total)"),
    ("COMMIT_NANOS", "Java mirror commit (setLightFor loop)"),
    ("REGISTER_NANOS", "chunk registration + full sync"),
    ("STAGE_NANOS", "staged-path staging (diagnostic)"),
]


def hist_pct(hist, q):
    total = sum(hist)
    if total == 0:
        return float("nan")
    acc = 0
    for i, c in enumerate(hist):
        acc += c
        if acc >= total * q:
            return (i * 0.5) if 0 < i < 200 else (0.25 if i == 0 else 100.0)
    return 100.0


def main(run_dir):
    run = Path(run_dir)
    rm = run / "region-metrics.txt"
    m1 = None
    for name in ("server/m1-metrics-final.txt",):
        f = run / name
        if f.is_file():
            m1 = f
    counters = {}
    if rm.is_file():
        for line in rm.read_text(encoding="utf-8", errors="replace").splitlines():
            k, _, v = line.partition("=")
            counters[k] = v

    print(f"== {run.name} ==")
    if m1:
        t = m1.read_text(encoding="utf-8", errors="replace")
        mh = re.search(r"mspt_hist=([0-9,]+)", t)
        mm = re.search(
            r"mspt_n=(\d+) mspt_max_ms=([\d.]+) mspt_mean_ms=([\d.]+)"
            r" mspt_deadline_over_50ms=(\d+)", t)
        if mh and mm:
            hist = [int(x) for x in mh.group(1).rstrip(",").split(",") if x]
            print(f"mspt: n={mm.group(1)} mean={float(mm.group(3)):.2f}ms "
                  f"p50={hist_pct(hist,0.50):.1f} p95={hist_pct(hist,0.95):.1f} "
                  f"p99={hist_pct(hist,0.99):.1f} max={float(mm.group(2)):.0f}ms "
                  f">50ms={mm.group(4)} >100ms={sum(hist[200:])}")
    # component cumulative times (ms)
    rows = []
    for field, what in NS_FIELDS:
        v = counters.get(field)
        if v is not None:
            rows.append((float(v) / 1e6, what))
    rows.sort(reverse=True)
    for ms, what in rows:
        print(f"  {ms:10.1f} ms  {what}")
    # volume counters that rank copy/JNI pressure
    keys = [
        "zeroStageJobs", "lightAuthCommitted", "mirrorStateSets",
        "HOOK_BLOCK_SETS", "HOOK_LIGHT_SETS", "HOOK_STORAGE_REPLACED",
        "SECTIONS_REFRESHED", "SECTIONS_VALIDATED",
        "SECTIONS_VALIDATED_ALLAIR_ABSENT", "FULL_SYNCS", "REGISTERS",
        "BIOMES_PUSHED", "regionWrite.rustOk", "regionRead.readSuccess",
        "lightAuthFallbacks", "worldRegistryMissing",
    ]
    print("  counters: " + " ".join(
        f"{k}={counters.get(k, '?')}" for k in keys))
    # copy-volume estimate: sections refreshed x 20KiB (16KiB state u32 +
    # 2x2KiB light), plus primer registration 256KiB/chunk
    sec = float(counters.get("SECTIONS_REFRESHED") or 0)
    skipped = float(counters.get("SYNC_SKIPPED") or 0)
    committed = float(counters.get("lightAuthCommitted") or 0)
    # OPT-SYNC-001: registerShell stages ZERO primer bytes and skipped
    # sections stage nothing — only refreshed sections cross the seam
    if sec or skipped or committed:
        print(f"  staged-bytes ~{sec * 20480 / 1e6:.1f} MB "
              f"(sections refreshed {sec:.0f} x 20KiB; skipped {skipped:.0f}; "
              f"primers 0 — registerShell); "
              f"mirror-diff records {committed:.0f} x 16B")


if __name__ == "__main__":
    for d in sys.argv[1:]:
        main(d)
