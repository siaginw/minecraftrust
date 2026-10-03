#!/usr/bin/env python3
"""Goal §5: classify writer-attribution journals.

Reads one or more writer-attribution.jsonl files (from the §4 agent) and
reports per-owner counts, per-path writer sets, per-thread writers, and the
exact UNKNOWN stacks (each with its caller frames). Exit 1 if any UNKNOWN
bypass write exists whose frames name a non-RegionFile writer.
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


def main() -> int:
    journals = [Path(p) for p in sys.argv[1:]]
    owner = Counter()
    by_path = defaultdict(Counter)
    by_thread = defaultdict(Counter)
    unknown_stacks = Counter()
    events = 0
    for j in journals:
        for line in j.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            events += 1
            o = rec.get("owner", "?")
            owner[o] += 1
            by_path[rec.get("path", "?")][o] += 1
            by_thread[rec.get("thread", "?")][o] += 1
            if o == "UNKNOWN":
                unknown_stacks[rec.get("frames", "?")] += 1

    print(f"[attribution] events={events}")
    for k, v in owner.most_common():
        print(f"  owner {k}: {v}")
    multi = {p: dict(c) for p, c in by_path.items() if len(c) > 1}
    if multi:
        print(f"[attribution] MULTI-WRITER paths: {len(multi)}")
        for p, c in list(multi.items())[:10]:
            print(f"    {p}: {c}")
    unknown_threads = {t: dict(c) for t, c in by_thread.items()
                       if c.get("UNKNOWN")}
    if unknown_threads:
        print(f"[attribution] UNKNOWN-writing threads: "
              f"{list(unknown_threads)}")
    if unknown_stacks:
        print(f"[attribution] UNKNOWN stacks:")
        for s, n in unknown_stacks.most_common(20):
            print(f"    x{n}: {s}")
    print(f"WRITER_ATTRIBUTION events={events} "
          f"unknown={owner.get('UNKNOWN', 0)} "
          f"vanilla={owner.get('VANILLA_REGIONFILE', 0)}")
    if unknown_stacks:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
