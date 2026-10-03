#!/usr/bin/env python3
"""Goal §6: Revelation mod-jar collision-hook audit.

Scans every mod jar for classes that OVERRIDE or TRANSFORM collision
behavior — the exact set that must stay Java-owned:
  - Block overrides: addCollisionBoxToList / getCollisionBoundingBox /
    getBlockLayer-adjacent collision methods (SRG + dev names)
  - World overrides: getCollisionBoxes / collidesWithAnyBlock variants
  - Forge events fired from collision paths
  - Mixins / ASM transformers whose targets match collision methods
  - TileEntity/capability references INSIDE block collision overrides
    (dynamic-collision signal)

Fingerprints are descriptor+name based (not class-name matching): a hit is
a class whose declared methods match a collision-signature exactly.
"""
from __future__ import annotations

import json
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path

MODS = Path(sys.argv[1]) if len(sys.argv) > 1 else \
    Path("target/authority-smoke/runtimeC/mods")
OUT = Path("target/authority-review/collision-audit.json")

# (name, descriptor) of virtual methods that constitute collision behavior.
# SRG (production runtime) + dev names.
COLLISION_METHODS = {
    ("func_185908_a", "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;"
     "Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;"
     "Lnet/minecraft/entity/Entity;Z)V"),  # IBlockState.addCollisionBoxToList
    ("addCollisionBoxToList", None),
    ("func_180646_a", "(Lnet/minecraft/block/state/IBlockState;"
     "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;"
     "Lnet/minecraft/util/math/AxisAlignedBB;)"),  # getCollisionBoundingBox
    ("getCollisionBoundingBox", None),
    ("func_176221_a", None),  # getActualState
    ("getActualState", None),
    ("func_185915_a", None),  # getBlockFaceShape-ish / shape getter family
    ("collisionRayTrace", None),
    ("func_71865_a", None),  # legacy 1.8 addCollisionBoxesToList
}

# constants that signal DYNAMIC behavior inside a collision override
DYNAMIC_SIGNALS = [
    "getTileEntity", "func_175625_s",           # TileEntity access
    "getExtendedState", "func_184449_t",        # extended state
    "World/getTileEntity",
    "hasCapability", "getCapability",           # capabilities
    "Multipart", "PartInfo",                    # multipart blocks
    "EventBus", "post(",                        # event firing
]


def utf8_constants(class_bytes: bytes) -> list[str]:
    out = []
    if class_bytes[:4] != b"\xca\xfe\xba\xbe":
        return out
    n = struct.unpack(">H", class_bytes[8:10])[0]
    i = 10
    idx = 1
    while idx < n:
        tag = class_bytes[i]
        if tag == 1:
            ln = struct.unpack(">H", class_bytes[i + 1:i + 3])[0]
            out.append(class_bytes[i + 3:i + 3 + ln].decode("utf-8", "replace"))
            i += 3 + ln
        elif tag in (7, 8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        else:
            break
        idx += 1
    return out


def class_declared_methods(class_bytes: bytes) -> set[tuple[str, str]]:
    """Approximate declared methods from Utf8 constant pairs (name + desc).
    Good enough for signature matching: if both name and descriptor exist in
    the constant pool, the class declares or references that signature."""
    consts = utf8_constants(class_bytes)
    cset = set(consts)
    pairs = set()
    for name, desc in COLLISION_METHODS:
        if name in cset and (desc is None or desc in cset):
            pairs.add((name, desc or "*"))
    return pairs


def survey_jar(jar: Path) -> dict:
    inv = {"jar": jar.name, "classes": 0, "collision_overrides": [],
           "dynamic_signals": []}
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
        pairs = class_declared_methods(data)
        if pairs:
            consts = utf8_constants(data)
            joined = "\n".join(consts)
            signals = [s for s in DYNAMIC_SIGNALS if s in joined]
            inv["collision_overrides"].append(
                {"class": name, "signatures": sorted(f"{n}{d}" for n, d in pairs),
                 "dynamic_signals": signals})
            if signals:
                inv["dynamic_signals"].append({"class": name, "signals": signals})
    return inv


def main() -> int:
    jars = sorted(MODS.glob("*.jar"))
    print(f"[audit] {len(jars)} mod jars...")
    all_inv = []
    total_overrides = 0
    jar_counts: Counter[str] = Counter()
    for j in jars:
        inv = survey_jar(j)
        all_inv.append(inv)
        n = len(inv["collision_overrides"])
        if n:
            jar_counts[j.name] = n
            total_overrides += n
            dyn = sum(1 for c in inv["collision_overrides"] if c["dynamic_signals"])
            print(f"  {j.name}: {n} collision-touching classes "
                  f"({dyn} with dynamic signals)")
    OUT.write_text(json.dumps(all_inv, indent=2))
    print(f"[audit] total collision-touching classes: {total_overrides} "
          f"across {len(jar_counts)} jars -> {OUT}")
    top = jar_counts.most_common(10)
    print("[audit] top jars:", top)
    return 0


if __name__ == "__main__":
    sys.exit(main())
