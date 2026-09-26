#!/usr/bin/env python3
"""Compares the accepted Clean Forge 66-site writer matrix against the
Revolution-transformed (Revelation) final class definitions.

Inputs:
  tools/live-capture/required-live-writer-hooks.json  (66 sites)
  tools/live-capture/live-shadow-profile.json         (clean expected_class_hashes)
  target/rustcraft-tests/runs/<latest>/baseline/qualification/qualification.json
      (clean post-FML pre-hook transformed inventory + dump dir)
  target/rev-probe/qualification.json                 (rev inventory)
  target/rev-probe/transformed/                       (rev final class bytes)

Per hook site classification:
  UNCHANGED_EXACT                class hash identical in both runtimes
  UNCHANGED_SEMANTIC             class hash differs, target method bytecode identical
  REWRITTEN_METHOD               target method bytecode differs (diff recorded)
  METHOD_ABSENT                  target method/descriptor missing in Revelation
  CLASS_ABSENT                   class never observed in the Revelation probe
Output: target/rev-probe/hook-matrix.json (+ console summary)
"""
from __future__ import annotations

import glob
import json
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVAP = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javap.exe")

HOOKS = json.loads((ROOT / "tools/live-capture/required-live-writer-hooks.json").read_text())["required_hooks"]
PROFILE = json.loads((ROOT / "tools/live-capture/live-shadow-profile.json").read_text())
CLEAN_HASHES = PROFILE["expected_class_hashes"]

RUNS = sorted(glob.glob(str(ROOT / "target/rustcraft-tests/runs/*-live-transformer-*")))
CLEAN_RECEIPT = json.loads((Path(RUNS[-1]) / "baseline/qualification/qualification.json").read_text())
CLEAN_DUMP = Path(RUNS[-1]) / "baseline/qualification/transformed"

REV_RECEIPT = json.loads((ROOT / "target/rev-probe/qualification.json").read_text())
REV_HASHES = REV_RECEIPT["transformed_classes"]
REV_DUMP = ROOT / "target/rev-probe/transformed"

METHOD_RE = re.compile(r"^\s+\S.*?;\s*$|^\s+\S.*?\)\s*;?\s*$")


def javap_dump(dump: Path, class_name: str) -> str | None:
    file = dump / (class_name.replace(".", "/") + ".class")
    if not file.exists():
        return None
    result = subprocess.run([str(JAVAP), "-p", "-c", str(file)],
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def extract_method(text: str, method: str, descriptor: str,
                   class_name: str = "") -> str | None:
    """Extracts one method's disassembled body. Constructors are printed by
    javap with the fully-qualified class name in place of the method name."""
    lines = text.splitlines()
    search_name = class_name.split(".")[-1] if method == "<init>" else method
    start = None
    for index, line in enumerate(lines):
        if re.search(r"\b" + re.escape(search_name) + r"\s*\(", line) and descriptor[:2] in line.replace(" ", ""):
            start = index
            break
    if start is None:
        # fall back: match name only (javap may render descriptors differently)
        for index, line in enumerate(lines):
            if re.search(r"\b" + re.escape(search_name) + r"\s*\(", line):
                start = index
                break
    if start is None:
        return None
    body = []
    depth = 0
    begun = False
    for line in lines[start:]:
        if not begun and "Code:" in line:
            begun = True
            continue
        if begun:
            stripped = line.strip()
            if stripped.startswith("}") or (stripped and not stripped[0].isdigit()
                                            and not stripped.startswith("//")
                                            and body and "Code:" not in line):
                break
            body.append(line)
    return "\n".join(body) if body else None


def normalize(body: str) -> list[str]:
    """Instruction tuples without BCI offsets and constant-pool indexes."""
    out = []
    for line in body.splitlines():
        match = re.match(r"\s*\d+:\s+(.*)$", line)
        if match:
            instruction = re.sub(r"#\d+", "#", match.group(1))
            out.append(instruction.strip())
    return out


def main() -> int:
    matrix = []
    counts: dict[str, int] = {}
    for hook in HOOKS:
        cls = hook["class"]
        clean_hash = CLEAN_HASHES.get(cls)
        clean_full = CLEAN_RECEIPT["transformed_classes"].get(cls)
        rev_hash = REV_HASHES.get(cls)
        entry = {"id": hook["id"], "class": cls, "method": hook["method"],
                 "descriptor": hook["descriptor"]}
        if rev_hash is None:
            entry["classification"] = "CLASS_ABSENT"
        elif clean_full == rev_hash:
            entry["classification"] = "UNCHANGED_EXACT"
            entry["clean_sha256"] = clean_full
            entry["rev_sha256"] = rev_hash
        else:
            entry["clean_sha256"] = clean_full
            entry["rev_sha256"] = rev_hash
            clean_body = extract_method(javap_dump(CLEAN_DUMP, cls) or "",
                                        hook["method"], hook["descriptor"], cls)
            rev_body = extract_method(javap_dump(REV_DUMP, cls) or "",
                                      hook["method"], hook["descriptor"], cls)
            if rev_body is None:
                entry["classification"] = "METHOD_ABSENT"
            elif clean_body is None:
                entry["classification"] = "CLEAN_METHOD_NOT_FOUND"
            elif normalize(clean_body) == normalize(rev_body):
                entry["classification"] = "UNCHANGED_SEMANTIC"
            else:
                entry["classification"] = "REWRITTEN_METHOD"
                entry["clean_instructions"] = len(normalize(clean_body))
                entry["rev_instructions"] = len(normalize(rev_body))
        counts[entry["classification"]] = counts.get(entry["classification"], 0) + 1
        matrix.append(entry)
    out = ROOT / "target/rev-probe/hook-matrix.json"
    out.write_text(json.dumps({"clean_forge_forge_build": PROFILE["forge_build"],
                               "hook_count": len(HOOKS),
                               "counts": counts,
                               "sites": matrix}, indent=1))
    print(json.dumps(counts, indent=1))
    for entry in matrix:
        if entry["classification"] not in ("UNCHANGED_EXACT", "UNCHANGED_SEMANTIC"):
            print(entry["classification"], entry["id"], entry["class"].split(".")[-1],
                  entry["method"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
