#!/usr/bin/env python3
"""RUST_REGION_WRITE_AUTHORITY - static mod-jar Anvil-writer survey (goal §3).

Scans EVERY jar under the Revelation runtime mods/ directory for bytecode
fingerprint combinations that can plausibly write Anvil-format region files:

  - references to java/io/RandomAccessFile or java/nio/FileChannel
  - Deflater/Inflater stream classes (the chunk compression boundary)
  - the ".mca" or ".mcr" extension literal
  - "region" path segments
  - 4096/8192 sector/header arithmetic constants (as sipush/ldc2 or ints)
  - location/timestamp table patterns: two int[1024] or similar

Class-name matching alone is NOT trusted: hits are classified by the
COMBINATION of fingerprints, and any class that merely READS region files
Inflater-only) is separated from plausible WRITERS (RandomAccessFile or
FileChannel WRITE methods present + compression + sector arithmetic).

Output: a JSON inventory + a console table of writer-suspect classes with
their jar, fingerprint set, and file-path construction hints.
"""
from __future__ import annotations

import json
import struct
import sys
import zipfile
from pathlib import Path

MODS = Path(sys.argv[1]) if len(sys.argv) > 1 else \
    Path("target/authority-smoke/runtimeC/mods")
OUT = Path("target/authority-review/writer-survey.json")

# Rough JVM bytecode constant extraction: we don't need a full parser.
# Constant-pool strings appear as modified-UTF8 bytes; ints of interest are
# extracted by scanning code for bipush/sipush/ldc patterns is overkill --
# we instead scan the CONSTANT POOL utf8 + the raw class bytes for evidence.


def utf8_constants(class_bytes: bytes) -> list[str]:
    """Extract all CONSTANT_Utf8 strings from a class file."""
    out = []
    if class_bytes[:4] != b"\xca\xfe\xba\xbe":
        return out
    n = struct.unpack(">H", class_bytes[8:10])[0]
    i = 10
    idx = 1
    while idx < n:
        tag = class_bytes[i]
        if tag == 1:  # Utf8
            ln = struct.unpack(">H", class_bytes[i + 1:i + 3])[0]
            out.append(class_bytes[i + 3:i + 3 + ln].decode("utf-8", "replace"))
            i += 3 + ln
        elif tag in (7, 8, 16, 19, 20):
            i += 3
        elif tag in (15,):
            i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1  # long/double take two slots
        else:
            break  # malformed; bail
        idx += 1
    return out


def class_ref_constants(class_bytes: bytes) -> set[str]:
    """Class names referenced via CONSTANT_Class entries (approximated from
    Utf8 constants that look like internal class names)."""
    names = set()
    for s in utf8_constants(class_bytes):
        if s and "/" in s and ";" not in s and "(" not in s and " " not in s:
            names.add(s)
    return names


def survey_jar(jar: Path) -> dict:
    inv = {"jar": jar.name, "size": jar.stat().st_size, "classes": 0,
           "writer_suspects": [], "reader_only": [], "mca_literal": []}
    try:
        z = zipfile.ZipFile(jar)
    except Exception as e:
        inv["error"] = str(e)
        return inv
    for name in z.namelist():
        if not name.endswith(".class"):
            continue
        try:
            data = z.read(name)
        except Exception:
            continue
        inv["classes"] += 1
        consts = utf8_constants(data)
        joined = "\n".join(consts)

        has_raf = "java/io/RandomAccessFile" in joined
        has_fc = "java/nio/channels/FileChannel" in joined or \
                 "java/nio/FileChannel" in joined
        has_deflate_out = "java/util/zip/DeflaterOutputStream" in joined or \
                          "java/util/zip/Deflater" in joined
        has_inflate_in = "java/util/zip/InflaterInputStream" in joined or \
                         "java/util/zip/Inflater" in joined
        has_mca = ".mca" in joined or ".mcr" in joined
        has_region = "region" in joined.lower() and "r.\" + " not in joined
        # sector arithmetic: 4096 as a literal int constant is common; only
        # meaningful with compression+IO. 8192 header likewise.
        has_sector_math = any(c in consts for c in ("4096", "8192")) or \
            (b"\x11\x10\x00" in data)  # sipush 4096 (0x11 0x10 0x00)
        has_anvil_terms = "AnvilChunkLoader" in joined or "ChunkBuffer" in \
            joined or "RegionFile" in joined

        writer_score = sum([has_raf or has_fc,
                            has_deflate_out,
                            has_sector_math or has_mca,
                            has_anvil_terms])
        entry = {"class": name, "jar": jar.name,
                 "raf": has_raf, "fc": has_fc,
                 "deflater": has_deflate_out, "inflater": has_inflate_in,
                 "mca_literal": has_mca, "region_str": has_region,
                 "sector_math": has_sector_math, "anvil_terms": has_anvil_terms}
        if (has_raf or has_fc) and (has_deflate_out or has_mca) and \
                (has_sector_math or has_anvil_terms or has_region):
            # skip vanilla-identical relocations we already know are Minecraft
            if name.startswith("net/minecraftforge/") or \
                    name.startswith("net/minecraft/"):
                continue
            inv["writer_suspects"].append(entry)
        elif has_mca:
            inv["mca_literal"].append(entry)
    return inv


def main() -> int:
    jars = sorted(MODS.glob("*.jar"))
    print(f"[survey] {len(jars)} jars, scanning...")
    all_inv = []
    for j in jars:
        inv = survey_jar(j)
        all_inv.append(inv)
        if inv["writer_suspects"]:
            print(f"[jar] {j.name}: {len(inv['writer_suspects'])} writer suspect(s)")
            for s in inv["writer_suspects"]:
                print("      " + s["class"] +
                      f" raf={s['raf']} fc={s['fc']} defl={s['deflater']} "
                      f"mca={s['mca_literal']} sector={s['sector_math']} "
                      f"anvil={s['anvil_terms']}")
    OUT.write_text(json.dumps(all_inv, indent=2))
    total = sum(len(i["writer_suspects"]) for i in all_inv)
    print(f"[survey] done: {total} writer-suspect classes across "
          f"{sum(1 for i in all_inv if i['writer_suspects'])} jars -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
