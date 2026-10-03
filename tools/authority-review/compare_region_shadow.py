#!/usr/bin/env python3
"""Region shadow-write comparator (RUST_REGION_WRITE_AUTHORITY, SHADOW mode).

For every real region file under the server world, finds the Rust mirror
counterpart (sanitized absolute path under the mirror root) and compares,
for every chunk slot present in BOTH files, the DECOMPRESSED record payload
(SHA-256). Length prefixes and timestamps may differ by design (independent
writes at different instants); the payload must be byte-identical.

Usage:
  compare_region_shadow.py <world_region_dir> <mirror_root> [--min N]
Prints REGION_SHADOW_MATCH <compared> <mismatched> <mirror_only> or a
failure line per mismatch, exit 1 on any mismatch.
"""
from __future__ import annotations

import hashlib
import struct
import sys
import zlib
from pathlib import Path

SECTOR = 4096


def read_region(path: Path) -> dict[int, tuple[bytes, int]]:
    """chunk index -> (payload_sha_of_decompressed, raw_len) lazily? Return
    index -> raw record bytes after the type byte."""
    data = path.read_bytes()
    out: dict[int, bytes] = {}
    if len(data) < 8192:
        return out
    for i in range(1024):
        entry = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
        off = (entry >> 8)
        cnt = entry & 0xFF
        if off == 0 or cnt == 0:
            continue
        start = off * SECTOR
        if start + 5 > len(data):
            out[i] = b"<TRUNCATED>"
            continue
        length = struct.unpack(">I", data[start:start + 4])[0]
        typ = data[start + 4]
        blob = data[start + 5:start + 4 + length]
        try:
            if typ == 2:
                out[i] = zlib.decompress(blob)
            elif typ == 1:
                out[i] = zlib.decompress(blob, -15)
            else:
                out[i] = blob  # uncompressed
        except Exception as e:
            out[i] = f"<DECOMPRESS_FAIL {e}>".encode()
    return out


def mirror_path_for(region_file: Path, mirror_root: Path) -> Path:
    safe = str(region_file.resolve()).replace(":", "_").replace("\\", "_").replace("/", "_")
    return mirror_root / safe


def main() -> int:
    world_dir = Path(sys.argv[1])
    mirror_root = Path(sys.argv[2])
    min_required = 0
    if "--min" in sys.argv:
        min_required = int(sys.argv[sys.argv.index("--min") + 1])

    compared = mismatched = mirror_only = 0
    failures: list[str] = []
    # The JVM's File.getAbsolutePath() lowercases the drive letter and may
    # keep "." segments, so match mirror files case-insensitively on a
    # world/region/<name> suffix instead of reconstructing the exact name.
    mirror_files = {p.name.lower(): p for p in mirror_root.glob("*.mca")}
    unmatched_mirrors = set(mirror_files)
    for real in sorted(world_dir.glob("r.*.*.mca")):
        suffix = f"world_region_{real.name.lower()}"
        candidates = [p for low, p in mirror_files.items() if low.endswith(suffix)]
        if not candidates:
            print(f"[miss] no mirror for {real.name}")
            continue
        mirror = candidates[0]
        unmatched_mirrors.discard(mirror.name.lower())
        r = read_region(real)
        m = read_region(mirror)
        for idx, rpayload in r.items():
            if idx not in m:
                continue
            mpayload = m[idx]
            compared += 1
            if hashlib.sha256(rpayload).digest() != hashlib.sha256(mpayload).digest():
                mismatched += 1
                failures.append(f"mismatch {real.name} chunk {idx % 32},{idx // 32}")
        mirror_only = sum(1 for idx in m if idx not in r)
    if unmatched_mirrors:
        print(f"[info] mirrors with no real counterpart: {len(unmatched_mirrors)}")
    for f in failures[:20]:
        print(f"[FAIL] {f}")
    print(f"REGION_SHADOW_MATCH compared={compared} mismatched={mismatched} "
          f"mirror_only={mirror_only}")
    if mismatched or compared < min_required:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
