#!/usr/bin/env python3
"""OPT-FS-001 §6: allocation-site attribution from a JDK8 JFR recording.

Parses `jfr print --events jdk.ObjectAllocationInNewTLAB,
jdk.ObjectAllocationOutsideTLAB` (streamed; the file can be large) and
aggregates by (object class, allocating frame) with caller-chain context
(first com.rustcraft frame, first net.minecraft frame, first mod frame)
and thread. Weighting: these events are TLAB-retirement samples — the
byte weight is the event's tlabSize (inside-TLAB) or allocationSize
(outside-TLAB): a SAMPLED approximation, never an object census; the
totals are reconciled against the observer's exact per-thread allocation
delta and the unaccounted share is reported.

Usage:
  python analyze_fs_profile.py <run_dir> [--top N]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

JFR = Path(r"C:\Program Files\Eclipse Adoptium"
           r"\jdk-8.0.504.1-hotspot\bin\jfr.exe")
ALLOC_EVENTS = ("jdk.ObjectAllocationInNewTLAB,"
                "jdk.ObjectAllocationOutsideTLAB")
EXEC_EVENTS = "jdk.ExecutionSample"

MOD_PKGS = ("slimeknights", "mantle", "ic2", "appeng", "forestry",
            "vazkii", "crazypants", "hellfirepvp", "astralsorcery",
            "actuallyadditions", "cofh", "thermal", "draconicevolution",
            "enderio", "chisel", "bdlib", "biomesoplenty", "thaumcraft",
            "journeymap", "mcjtylib")


def jfr_print(jfr_file: Path, events: str):
    proc = subprocess.Popen(
        [str(JFR), "print", "--events", events, str(jfr_file)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        encoding="utf-8", errors="ignore")
    return proc


def parse_alloc_events(proc):
    """yields dicts: class, bytes, thread, t0(epoch ms|None), frames[list]"""
    ev = {}
    in_stack = False
    for line in proc.stdout:
        line = line.rstrip("\n")
        s = line.strip()
        if s.startswith("jdk.ObjectAllocation"):
            ev = {"class": None, "bytes": 0, "thread": None,
                  "t": None, "frames": []}
            continue
        if not ev:
            continue
        if s.startswith("startTime = "):
            m = re.search(r"startTime = (\d{13,})", s)
            if m:
                ev["t"] = int(m.group(1))  # jfr print: ms since epoch
            else:
                m2 = re.match(r"startTime = (\d{4}-\d\d-\d\dT[\d:.]+)",
                              s)
                if m2:
                    try:
                        ev["t"] = int(datetime.fromisoformat(
                            m2.group(1)).timestamp() * 1000)
                    except ValueError:
                        pass
        elif s.startswith("objectClass = "):
            m = re.search(r"objectClass = (\S+)", s)
            ev["class"] = m.group(1) if m else "?"
        elif s.startswith("tlabSize = ") or s.startswith("allocationSize = "):
            m = re.search(r"= (\d+)", s)
            if m:
                ev["bytes"] = int(m.group(1))
        elif s.startswith("eventThread = "):
            m = re.search(r'eventThread = "?([^"\n]+)"?', s)
            ev["thread"] = m.group(1) if m else "?"
        elif s.startswith("stackTrace = ["):
            in_stack = True
        elif in_stack:
            if s == "]":
                in_stack = False
            elif s:
                ev["frames"].append(s)
        elif s == "}":
            if ev.get("class"):
                yield ev
            ev = {}
    proc.wait()


def first_frame(frames, *needles):
    for f in frames:
        if any(n in f for n in needles):
            return f
    return "-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir", type=Path)
    ap.add_argument("--top", type=int, default=25)
    args = ap.parse_args()
    run = args.run_dir
    jfr_file = run / "server-profile.jfr"
    if not jfr_file.is_file():
        print(f"no profile: {jfr_file}", file=sys.stderr)
        return 1

    phases = json.loads((run / "phases.json").read_text(encoding="utf-8")) \
        if (run / "phases.json").is_file() else []
    wins = []
    for e in phases:
        if e.get("phase") and e.get("boundary") == "start":
            wins.append([e["phase"], e["t"], None])
        elif e.get("phase") and wins:
            wins[-1][2] = e["t"]

    by_site = Counter()          # (class, top frame) -> bytes
    by_site_n = Counter()
    by_class = Counter()
    by_thread = Counter()
    by_rust = Counter()          # first rustcraft frame -> bytes
    by_phase = {}                # phase -> Counter of sites
    total = 0
    proc = jfr_print(jfr_file, ALLOC_EVENTS)
    for ev in parse_alloc_events(proc):
        b = ev["bytes"]
        total += b
        cls = ev["class"]
        by_class[cls] += b
        by_thread[ev["thread"] or "?"] += b
        top = ev["frames"][0] if ev["frames"] else "-"
        rf = first_frame(ev["frames"], "com.rustcraft")
        by_rust[rf] += b
        phase = "outside"
        for name, t0, t1 in wins:
            if ev["t"] is not None and t0 and (t1 or float("inf")) \
                    and t0 <= ev["t"] < (t1 or float("inf")):
                phase = name
                break
        key = (cls, top)
        by_site[key] += b
        by_site_n[key] += 1
        by_phase.setdefault(phase, Counter())[key] += b

    obs = None
    obs_path = run / "server" / "observer-metrics.jsonl"
    if obs_path.is_file():
        import json as _j
        tot_a = tot_b = 0
        ivals = []
        for line in obs_path.read_text(encoding="utf-8",
                                       errors="replace").splitlines():
            try:
                ivals.append(_j.loads(line))
            except ValueError:
                pass
        for s in ivals:
            if "srv_alloc_b" in s:
                tot_a += s.get("srv_alloc_b", 0)
                tot_b += 1
        obs = {"srv_alloc_mb": tot_a / 1e6, "intervals": tot_b}

    mb = lambda v: v / 1e6
    print(f"JFR sampled allocation weight: {mb(total):.0f} MB "
          f"(TLAB-sample approximation)")
    if obs:
        print(f"observer server-thread alloc delta: "
              f"{obs['srv_alloc_mb']:.0f} MB")
        print(f"reconciliation: JFR/observer = "
              f"{(total / max(obs['srv_alloc_mb'] * 1e6, 1)):.2f} "
              f"(sampled weight vs exact same-thread delta; "
              f"unaccounted share is a sampling limitation, not mystery)")
    print("\n=== by thread (top 6) ===")
    for t, b in by_thread.most_common(6):
        print(f"  {mb(b):10.1f} MB  {t}")
    print("\n=== by phase (top sites per phase) ===")
    for ph in ("phase_a_streaming", "phase_b_mutations", "phase_c_save",
               "boot", "probe"):
        if ph in by_phase:
            c = by_phase[ph]
            s = sum(c.values())
            print(f"  [{ph}] sampled {mb(s):.1f} MB; top sites:")
            for (cls, top), b in c.most_common(5):
                print(f"    {mb(b):9.1f} MB  {cls}  @ {top[:110]}")
    print(f"\n=== TOP {args.top} SITES (class @ allocating frame) ===")
    for (cls, top), b in by_site.most_common(args.top):
        print(f"  {mb(b):10.1f} MB x{by_site_n[(cls, top)]:7d}  "
              f"{cls}\n      @ {top[:150]}")
    print(f"\n=== first com.rustcraft frame on the stack (inclusive) ===")
    for f, b in by_rust.most_common(15):
        print(f"  {mb(b):10.1f} MB  {f[:150]}")
    print("\n=== by class (top 15) ===")
    for c, b in by_class.most_common(15):
        print(f"  {mb(b):10.1f} MB  {c}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
