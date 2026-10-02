#!/usr/bin/env python3
"""Fresh-JFR profiler for the single-copy boundary.

Parses `jfr print --events jdk.ExecutionSample` output and reports EXCLUSIVE
(top-frame) sample attribution for the frames the milestone contract names:
the single-copy handler, NativeChunk encode, NettyPacketEncoder, the
compression encoder, the frame prepender, the socket/channel write, GC
threads, Anvil/NBT I/O and mod code. EventLoop share and whole-process share
are reported SEPARATELY (never conflated). GC counts come from
jdk.GarbageCollection; allocation rates from jdk.ObjectAllocationInNewTLAB /
OutsideTLAB.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

JFR = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\jfr.exe"

CATEGORIES = [
    ("single_copy_handler", lambda f: "singlecopypipeline" in f.lower()
        and ("write" in f or "capture" in f or "ticket" in f or "body" in f)),
    ("nativechunk_encode", lambda f: ("rustcraft_ffi" in f or "nativechunkbridge" in f)
        or ("native" in f and "chunk" in f)),
    ("netty_packet_encoder", lambda f: "nettypacketencoder" in f.lower()),
    ("compression", lambda f: "nettycompressionencoder" in f.lower()
        or "deflater" in f.lower() or "inflater" in f.lower()),
    ("frame_prepender", lambda f: "nettyvarint21frameencoder" in f.lower()),
    ("netty_other", lambda f: "io.netty" in f),
    ("socket_write", lambda f: "socketchannel" in f.lower() or "socketwrite" in f.lower()
        or "socketadapter" in f.lower()),
    ("anvil_nbt", lambda f: "anvilchunkloader" in f.lower() or "nbt" in f.lower()
        or "regionfile" in f.lower() or "compressedstreamtools" in f.lower()),
    ("mods", lambda f: any(m in f.lower() for m in (
        "forestry", "thermalexpansion", "thermalfoundation", "appeng", "buildcraft",
        "immersiveengineering", "tconstruct", "enderio", "railcraft", "twilightforest",
        "botania", "astralsorcery", "chisel", "cofh", "codechicken", "mrtjp", "mcjty",
        "hellfirepvp", "zmaster587", "plasmid", "semper", "gregtech", "ic2"))),
    ("minecraft_other", lambda f: "net.minecraft" in f or "net.minecraftforge" in f
        or "fml" in f.lower()),
    ("java_other", lambda f: "java." in f or "sun." in f or "jdk." in f),
]


def classify(frame: str) -> str:
    lowered = frame.lower()
    for name, test in CATEGORIES:
        if test(lowered):
            return name
    return "other"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jfr", type=Path)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    events = subprocess.run(
        [JFR, "print", "--events", "jdk.ExecutionSample", str(args.jfr)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout

    threads: Counter = Counter()
    top_frames: Counter = Counter()
    current_thread = None
    current_stack: list[str] = []
    for line in events.splitlines():
        line = line.strip()
        if line.startswith("sampledThread ="):
            m = re.search(r'sampledThread = "([^"]*)"', line)
            current_thread = m.group(1) if m else None
        elif line.startswith("stackTrace = [") or line == "stackTrace = [":
            current_stack = []
        elif line.startswith("}") or line == "]":
            if current_thread is not None and current_stack:
                threads[current_thread] += 1
                top_frames[current_stack[0]] += 1
            current_stack = []
        elif line and (line.startswith("net.") or line.startswith("io.")
                       or line.startswith("java.") or line.startswith("sun.")
                       or line.startswith("com.") or line.startswith("jdk.")
                       or line.startswith("org.")):
            current_stack.append(line.split(", line:")[0].strip())

    total = sum(threads.values())
    eventloop_names = [t for t in threads if "Server IO" in t or "EventLoop" in t]
    server_names = [t for t in threads if "Server thread" in t]
    el_total = sum(threads[t] for t in eventloop_names)
    srv_total = sum(threads[t] for t in server_names)

    cat_total: Counter = Counter()
    cat_el: Counter = Counter()
    for frame, count in top_frames.items():
        cat = classify(frame)
        cat_total[cat] += count
    # event-loop exclusive attribution needs per-thread stacks; approximate by
    # re-running a thread-aware pass
    per_thread_cat: dict[str, Counter] = {}
    current_thread = None
    current_stack = []
    for line in events.splitlines():
        line = line.strip()
        if line.startswith("sampledThread ="):
            m = re.search(r'sampledThread = "([^"]*)"', line)
            current_thread = m.group(1) if m else None
        elif line.startswith("stackTrace = ["):
            current_stack = []
        elif line.startswith("}") or line == "]":
            if current_thread is not None and current_stack:
                cat = classify(current_stack[0])
                per_thread_cat.setdefault(current_thread, Counter())[cat] += 1
            current_stack = []
        elif line and (line.startswith("net.") or line.startswith("io.")
                       or line.startswith("java.") or line.startswith("sun.")
                       or line.startswith("com.") or line.startswith("jdk.")
                       or line.startswith("org.")):
            current_stack.append(line.split(", line:")[0].strip())
    for t, counter in per_thread_cat.items():
        if t in eventloop_names:
            for cat, count in counter.items():
                cat_el[cat] += count

    # GC + allocation events
    gc_out = subprocess.run(
        [JFR, "print", "--events", "jdk.GarbageCollection", str(args.jfr)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout
    gc_count = gc_out.count("jdk.GarbageCollection {")
    gc_pause_total = 0.0
    for m in re.finditer(r"gcPause = ([\d.]+)", gc_out):
        gc_pause_total += float(m.group(1))  # typically nanoseconds in jfr print
    alloc_out = subprocess.run(
        [JFR, "print", "--events", "jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB",
         str(args.jfr)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout
    alloc_bytes = 0
    alloc_count = 0
    for m in re.finditer(r"tlabSize = (\d+)", alloc_out):
        alloc_bytes += int(m.group(1))
        alloc_count += 1
    for m in re.finditer(r"allocationSize = (\d+)", alloc_out):
        alloc_bytes += int(m.group(1))
        alloc_count += 1

    report = {
        "total_execution_samples": total,
        "eventloop_threads": {t: threads[t] for t in eventloop_names},
        "eventloop_samples": el_total,
        "server_thread_samples": srv_total,
        "exclusive_by_category_whole_process": {
            cat: cat_total.get(cat, 0) for cat, _ in CATEGORIES
        } | {"other": cat_total.get("other", 0)},
        "exclusive_by_category_eventloop_only": {
            cat: cat_el.get(cat, 0) for cat, _ in CATEGORIES
        } | {"other": cat_el.get("other", 0)},
        "top_frames": {f: c for f, c in top_frames.most_common(15)},
        "gc_collections": gc_count,
        "gc_pause_total_ms_approx": round(gc_pause_total / 1e6, 1),
        "allocation_events": alloc_count,
        "allocation_bytes_total": alloc_bytes,
    }
    text = json.dumps(report, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
