#!/usr/bin/env python3
"""Minimal stdlib Anvil/region reader for run-world receipt evidence.

Reads a campaign run's server/world region files directly (no server,
no Java): chunk NBT, block id+meta at a coordinate, and TileTicks
(pending scheduled ticks — the M3-A persistence witness).

1.12 chunk NBT facts encoded here (learned the hard way; see
M3A-TICK-AUTHORITY-RECEIPT lifecycle section):
  - TileTicks entries key on i=block registry name (NOT b), t=time,
    p=priority, x/y/z=block coords.
  - ALL water saves as block id 9 with LEVEL meta (0=source, 8=falling)
    — "flowing_water" (id 8) never appears in saved state.
  - The on-disk region reflects the LAST save (on a run, the shutdown
    save) — phase-C save-alls are overwritten by it.
  - Block ids are pre-flattening numerics: 0 air, 1 stone, 8
    flowing_water, 9 water, 12 sand.

CLI:
  python tools/authority-review/anvil_reader.py block <world> <x> <y> <z>
  python tools/authority-review/anvil_reader.py tileticks <world> [--x A B] [--z A B]

Library:
  from anvil_reader import chunk_nbt, block_at, tile_ticks
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
import zlib

ID_NAMES = {0: "air", 1: "stone", 8: "flowing_water", 9: "water",
            12: "sand"}


def nbt_load(b: bytes):
    """Parse one NBT document (compressed-payload-agnostic: pass the
    DECOMPRESSED bytes). Returns the root compound's payload dict."""
    pos = [0]

    def u(fmt, n):
        v = struct.unpack_from(fmt, b, pos[0])[0]
        pos[0] += n
        return v

    def payload(t):
        if t == 1:
            return u(">b", 1)
        if t == 2:
            return u(">h", 2)
        if t == 3:
            return u(">i", 4)
        if t == 4:
            return u(">q", 8)
        if t == 5:
            return u(">f", 4)
        if t == 6:
            return u(">d", 8)
        if t == 7:
            n = u(">i", 4)
            v = b[pos[0]:pos[0] + n]
            pos[0] += n
            return v
        if t == 8:
            n = u(">H", 2)
            v = b[pos[0]:pos[0] + n].decode("utf-8", "replace")
            pos[0] += n
            return v
        if t == 9:
            et = u(">b", 1)
            n = u(">i", 4)
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                t2 = u(">b", 1)
                if t2 == 0:
                    return d
                n = u(">H", 2)
                name = b[pos[0]:pos[0] + n].decode("utf-8", "replace")
                pos[0] += n
                d[name] = payload(t2)
        if t == 11:
            n = u(">i", 4)
            v = struct.unpack_from(">%di" % n, b, pos[0])
            pos[0] += 4 * n
            return v
        raise ValueError("unknown NBT tag %d at byte %d" % (t, pos[0]))

    u(">b", 1)  # root tag id (compound)
    u(">H", 2)  # root name length
    return payload(10)


def _region_files(world: str):
    rd = os.path.join(world, "region")
    for n in sorted(os.listdir(rd)):
        if n.endswith(".mca"):
            yield os.path.join(rd, n), n[:-4]


def _region_chunks(path: str):
    """Yield (cx, cz, decompressed-chunk-bytes) for stored chunks."""
    data = open(path, "rb").read()
    base = os.path.basename(path)
    rx, rz = (int(v) for v in base[2:-4].split("."))
    for ci in range(1024):
        off = struct.unpack(">I", b"\x00" + data[ci * 4:ci * 4 + 3])[0] * 4096
        if off == 0:
            continue
        ln = struct.unpack(">I", data[off:off + 4])[0]
        try:
            raw = zlib.decompress(data[off + 5:off + 4 + ln])
        except zlib.error:
            continue
        yield rx * 32 + ci % 32, rz * 32 + ci // 32, raw


def chunk_nbt(world: str, cx: int, cz: int):
    """Level compound for chunk (cx, cz), or None if absent."""
    path = os.path.join(world, "region",
                        "r.%d.%d.mca" % (cx >> 5, cz >> 5))
    if not os.path.isfile(path):
        return None
    idx = ((cx & 31) + (cz & 31) * 32) * 4
    data = open(path, "rb").read()
    off = struct.unpack(">I", b"\x00" + data[idx:idx + 3])[0] * 4096
    if off == 0:
        return None
    ln = struct.unpack(">I", data[off:off + 4])[0]
    raw = zlib.decompress(data[off + 5:off + 4 + ln])
    return nbt_load(raw)["Level"]


def block_at(world: str, x: int, y: int, z: int):
    """(block_id, meta) at world coords, or None (missing chunk/section)."""
    lvl = chunk_nbt(world, x >> 4, z >> 4)
    if lvl is None:
        return None
    for s in lvl.get("Sections", []):
        if s.get("Y") == (y >> 4):
            bi = (y & 15) * 256 + (z & 15) * 16 + (x & 15)
            blocks = s.get("Blocks")
            if blocks is None:
                return None
            bid = blocks[bi] & 0xFF
            meta = (s["Data"][bi // 2] >> ((bi & 1) * 4)) & 0xF
            return bid, meta
    return None


def tile_ticks(world: str, xr=None, zr=None):
    """All TileTicks entries; optionally restricted to a coordinate box
    (inclusive). Yields (chunk, entry) pairs."""
    for path, _name in _region_files(world):
        for cx, cz, raw in _region_chunks(path):
            if xr and (cx * 16 + 15 < xr[0] or cx * 16 > xr[1]):
                continue
            if zr and (cz * 16 + 15 < zr[0] or cz * 16 > zr[1]):
                continue
            lvl = nbt_load(raw)["Level"]
            tt = lvl.get("TileTicks")
            if not tt:
                continue
            for e in tt:
                if xr and not (xr[0] <= e["x"] <= xr[1]):
                    continue
                if zr and not (zr[0] <= e["z"] <= zr[1]):
                    continue
                yield (cx, cz), e


def _main(argv):
    ap = argparse.ArgumentParser(prog="anvil_reader")
    sub = ap.add_subparsers(dest="cmd", required=True)
    bp = sub.add_parser("block")
    bp.add_argument("world")
    bp.add_argument("x", type=int)
    bp.add_argument("y", type=int)
    bp.add_argument("z", type=int)
    tp = sub.add_parser("tileticks")
    tp.add_argument("world")
    tp.add_argument("--x", nargs=2, type=int, default=None)
    tp.add_argument("--z", nargs=2, type=int, default=None)
    a = ap.parse_args(argv)
    if a.cmd == "block":
        r = block_at(a.world, a.x, a.y, a.z)
        if r is None:
            print("none")
        else:
            print("id=%d meta=%d %s" % (r[0], r[1],
                                        ID_NAMES.get(r[0], "?")))
        return 0
    total = 0
    by_chunk = {}
    for (cx, cz), e in tile_ticks(a.world, a.x, a.z):
        total += 1
        by_chunk[(cx, cz)] = by_chunk.get((cx, cz), 0) + 1
        print("(%d,%d) %s t=%s p=%s @%d,%d,%d"
              % (cx, cz, e.get("i"), e.get("t"), e.get("p"),
                 e.get("x"), e.get("y"), e.get("z")))
    print("TOTAL %d in %d chunks" % (total, len(by_chunk)))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
