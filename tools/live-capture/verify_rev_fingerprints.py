#!/usr/bin/env python3
"""Validates the BCI-fingerprinted hook anchors against the Revelation final
transformed bytes, mirroring LiveHookSupport.verifyAnchorsInOrder semantics
(in-order fragment scan; opcode + owner/name containment).

Parses javap -p -c output of the rev dump into the fragment vocabulary and
reports, per fingerprinted hook, whether every anchor exists in order. For
failing sites, prints the observed instruction stream so the rev profile can
carry a re-derived fingerprint.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVAP = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javap.exe")
DUMP = ROOT / "target/rev-probe/transformed"
MANIFEST = json.loads((ROOT / "tools/live-capture/required-live-writer-hooks.json").read_text())


def fragmentize(dump: Path, cls: str) -> list[str]:
    file = dump / (cls.replace(".", "/") + ".class")
    result = subprocess.run([str(JAVAP), "-p", "-c", "-cp", str(dump), cls],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("javap failed for " + cls + ": " + result.stderr[:200])
    fragments = []
    for line in result.stdout.splitlines():
        stripped = line.strip()
        match = re.match(r"\d+:\s+(\S+)\s*(.*)$", stripped)
        if not match:
            continue
        opcode, rest = match.group(1), match.group(2)
        # mirror describe(): method calls -> "opcode owner.name"
        method = re.match(r"#\S+\s*//\s+Method\s+(\S+)", rest)
        field = re.match(r"#\S+\s*//\s+Field\s+(\S+)", rest)
        iface = re.match(r"#\S+.*?//\s+InterfaceMethod\s+(\S+)", rest)
        hit = method or iface
        if hit:
            owner_call = hit.group(1)  # javap already renders "<init>" quoted
            fragments.append(opcode + " " + owner_call)
        elif field:
            fragments.append(opcode + " " + field.group(1))
        elif opcode in ("aload_0", "aload_1", "aload_2", "aload_3"):
            fragments.append(opcode)
        elif opcode == "aload":
            num = re.match(r"aload\s+(\d+)", rest)
            fragments.append("aload " + (num.group(1) if num else "?"))
        else:
            fragments.append(opcode)
    return fragments


def scan_in_order(fragments: list[str], assertions: list) -> list[dict]:
    """Mirrors verifyAnchorsInOrder + fragmentMatches."""
    results, cursor = [], 0
    for assertion in assertions:
        expected = assertion["expect"]
        space = expected.find(" ")
        e_opcode = expected if space < 0 else expected[:space]
        e_rest = "" if space < 0 else expected[space + 1:]
        found = None
        while cursor < len(fragments):
            observed = fragments[cursor]
            cursor += 1
            o_space = observed.find(" ")
            o_opcode = observed if o_space < 0 else observed[:o_space]
            o_rest = "" if o_space < 0 else observed[o_space + 1:]
            if o_opcode == e_opcode and (not e_rest or e_rest in o_rest):
                found = assertion["bci"]
                break
        results.append({"bci": assertion["bci"], "expect": expected,
                        "found": found is not None})
    return results


def main() -> int:
    failures = 0
    validation = []
    for hook in MANIFEST["required_hooks"]:
        fp = hook["fingerprint"]
        if fp.get("kind") != "BCI_ASSERTIONS":
            continue
        fragments = fragmentize(DUMP, hook["class"])
        results = scan_in_order(fragments, fp["assertions"])
        ok = all(r["found"] for r in results)
        validation.append({"id": hook["id"], "class": hook["class"],
                           "method": hook["method"], "anchors_ok": ok,
                           "anchors": results})
        status = "OK " if ok else "FAIL"
        print(status, hook["id"], hook["class"].split(".")[-1] + "." + hook["method"])
        for r in results:
            if not r["found"]:
                failures += 1
                print("     missing anchor @", r["bci"], r["expect"])
    out = ROOT / "target/rev-probe/anchor-validation.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"hook_count_fingerprinted": len(validation),
                               "anchor_failures": failures,
                               "sites": validation}, indent=1))
    print("anchor failures:", failures, "| written:", out)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
