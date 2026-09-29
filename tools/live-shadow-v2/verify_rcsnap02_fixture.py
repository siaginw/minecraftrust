#!/usr/bin/env python3
"""Cross-language verification of the harvested RCSNAP02 fixture.

Decodes the Java-produced RCSNAP02 transport independently (strict Python
reader mirroring the Rust decoder's rules), compares every reconstructed
logical cell / light plane / biome against the Java-side fixture-cells.txt
reference, and checks the Rust encode of the same transport (via the
snapshot_replay example) against the AUTHORITATIVE Java packet bytes for the
same chunk state.
"""
import json
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def decode_v2(transport: bytes):
    assert transport[:8] == b"RCSNAP02", "magic"
    version = struct.unpack(">H", transport[8:10])[0]
    assert version == 2, version
    flags = transport[10]
    full = bool(flags & 1)
    skylight = bool(flags & 2)
    storage = transport[11]
    source_bits = transport[12]
    scope = transport[13]
    assert transport[14:16] == b"\x00\x00"
    dimension, chunk_x, chunk_z = struct.unpack(">iii", transport[16:28])
    generation = struct.unpack(">Q", transport[28:36])[0]
    requested, accepted = struct.unpack(">HH", transport[36:40])
    event_id, owner, capture = struct.unpack(">QQQ", transport[40:64])
    epoch_start, epoch_end = struct.unpack(">QQ", transport[64:80])
    inc_start, inc_end = struct.unpack(">QQ", transport[80:96])
    provenance = transport[96:128]
    at = 128
    (section_count,) = struct.unpack(">H", transport[at:at + 2]); at += 2
    (registry_size,) = struct.unpack(">I", transport[at:at + 4]); at += 4
    (registry_bits,) = struct.unpack(">B", transport[at:at + 1]); at += 1
    sections = {}
    for _ in range(section_count):
        y, zero = transport[at], transport[at + 1]
        assert zero == 0
        (refcount,) = struct.unpack(">H", transport[at + 2:at + 4])
        (palette_len,) = struct.unpack(">H", transport[at + 4:at + 6])
        assert 1 <= palette_len <= 4096, palette_len
        at += 6
        palette = list(struct.unpack(">%dH" % palette_len, transport[at:at + 2 * palette_len]))
        at += 2 * palette_len
        assert len(set(palette)) == palette_len, "duplicate palette entry"
        bits = transport[at]; at += 1
        assert 1 <= bits <= 12 and (1 << bits) >= palette_len, bits
        (words,) = struct.unpack(">H", transport[at:at + 2]); at += 2
        assert words == (4096 * bits + 63) // 64, words
        word_values = list(struct.unpack(">%dQ" % words, transport[at:at + 8 * words]))
        at += 8 * words
        cells = []
        non_air = 0
        for cell in range(4096):
            position = cell * bits
            word, shift = position // 64, position % 64
            value = word_values[word] >> shift
            if shift + bits > 64:
                value |= word_values[word + 1] << (64 - shift)
            index = value & ((1 << bits) - 1)
            assert index < palette_len, "index out of palette"
            state = palette[index]
            if state != 0:
                non_air += 1
            cells.append(state)
        assert non_air == refcount, (non_air, refcount)
        block_light = transport[at:at + 2048]; at += 2048
        sky_light = transport[at:at + 2048] if skylight else None
        if skylight:
            at += 2048
        sections[y] = {"cells": cells, "block": block_light, "sky": sky_light}
    biomes = transport[at:at + 256] if full else None
    if full:
        at += 256
    assert at == len(transport), "trailing bytes"
    return {"sections": sections, "biomes": biomes, "full": full,
            "skylight": skylight, "registry_size": registry_size,
            "source_bits": source_bits, "accepted_mask": accepted}


def load_reference(cells_path: Path):
    sections = {}
    lines = cells_path.read_text(encoding="utf-8").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("section "):
            y = int(line.split()[1])
            values = [int(v) for v in lines[i + 1].split(",")]
            assert len(values) == 4096
            sections[y] = values
            i += 2
        else:
            i += 1
    return sections


def main():
    fixture_dir = Path(sys.argv[1]).resolve()
    transport = (fixture_dir / "fixture-rcsnap02.bin").read_bytes()
    facts = json.loads((fixture_dir / "fixture-facts.json").read_text(encoding="utf-8"))
    reference = load_reference(fixture_dir / "fixture-cells.txt")

    decoded = decode_v2(transport)
    assert set(decoded["sections"]) == set(reference), "section sets differ"
    mismatches = 0
    for y, cells in reference.items():
        if decoded["sections"][y]["cells"] != cells:
            mismatches += 1
    print("cross-language logical equality: sections=%d mismatches=%d"
          % (len(reference), mismatches))
    print("source registry: %d states / %d bits (telemetry; transport eligible=%s)"
          % (facts["registry_size"], facts["source_required_bits"], facts["eligible_u16"]))
    assert mismatches == 0, "LOGICAL CELL MISMATCH"

    # Rust encode of the same transport, compared against Java's own packet.
    replay = ROOT / "target/debug/examples/snapshot_replay.exe"
    if not replay.is_file():
        print("snapshot_replay not built; building")
        subprocess.run(["cargo", "build", "--locked", "-p", "ffi", "--example",
                        "snapshot_replay"], cwd=ROOT, check=True)
    out_bin = fixture_dir / "rust-output.bin"
    result = subprocess.run([str(replay), str(fixture_dir / "fixture-rcsnap02.bin"),
                             str(out_bin)], cwd=ROOT, capture_output=True, text=True)
    print(result.stdout.strip()[:400])
    rust = out_bin.read_bytes()
    java = (fixture_dir / "fixture-java-packet.bin").read_bytes()
    print("rust=%d bytes java=%d bytes equal=%s"
          % (len(rust), len(java), rust == java))
    if rust != java:
        first = next((i for i in range(min(len(rust), len(java)))
                      if rust[i] != java[i]), None)
        print("first divergence at byte", first)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
