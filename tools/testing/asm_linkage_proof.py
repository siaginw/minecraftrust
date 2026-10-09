"""Runtime linkage proof for the two pinned ASM builds.

    python -B tools/testing/asm_linkage_proof.py --revelation-root <dir> \\
        --clean-root <dir> --identity-clean <dir> --identity-revelation <dir> \\
        --classes <dir> --java <java.exe> --out <json>

`asm_abi.py` proves the two jars DECLARE the same symbols. That is a static
claim, and a static claim cannot rule out a link failure, a missing transitive
dependency, or a build that links and then behaves differently. This proof runs
the real thing: the same identity tool, built once per target against that
target's own pinned ASM, is executed on the same real classfiles with that
target's ASM as the ONLY ASM on the classpath, and the receipts must agree
byte for byte.

It also proves the negative that the whole exercise depends on. The reason this
work exists is that a Clean Forge jar was never allowed to stand in for
Revelation's. So the Revelation classpath is enumerated from the runtime tree
itself, and the run refuses to report anything at all if an asm-debug-all jar
is present in the Revelation tree -- including one that was merely downloaded
and never loaded, because a jar that is on disk is a jar someone can put on a
classpath.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import List

SCHEMA = "RUSTCRAFT_ASM_RUNTIME_LINKAGE_V1"

#: The jar Clean Forge pins. Its mere presence in the Revelation tree is the
#: failure this module exists to make impossible to miss.
FORBIDDEN_IN_REVELATION = ("asm-debug-all", "asm-debug", "asm-groovy-debug")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def inventory(root: Path) -> dict:
    """Every ASM jar physically present in a runtime tree.

    Scoped to the library path the runtimes actually resolve ASM from, because
    a modpack legitimately contains jars with "asm" in their name that have
    nothing to do with the JVM's ASM. Those are reported separately rather than
    folded in: a name is not a classpath.
    """
    library, other = [], []
    for path in sorted(root.rglob("*.jar")):
        text = path.as_posix().lower()
        record = {"path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size}
        if "/org/ow2/asm/" in text:
            library.append(record)
        elif "asm" in path.name.lower():
            other.append(record)
    return {"root": str(root), "asm_jars": library, "other_asm_named_jars": other}


def run_identity(java: str, classes: Path, asm: Path, target: Path) -> List[dict]:
    """Run the identity tool on real classfiles, with exactly one ASM jar.

    The classpath is the tool's own classes plus the one pinned ASM and nothing
    else, so any symbol it resolves came out of the target's own build.
    """
    process = subprocess.run(
        [str(java), "-cp", os.pathsep.join((str(classes), str(asm))),
         "com.rustcraft.coremod.CanonicalClassIdentityV2", str(target)],
        capture_output=True, text=True)
    if process.returncode != 0:
        raise AssertionError(f"identity tool failed on {target} against {asm}: "
                             + process.stdout + process.stderr)
    return [json.loads(line) for line in process.stdout.splitlines() if line.strip()]


def compare(clean_tool: Path, revelation_tool: Path, asm_clean: Path, asm_rev: Path,
            java: str, samples: List[Path]) -> dict:
    rows = []
    for path in samples:
        clean = run_identity(java, clean_tool, asm_clean, path)
        revelation = run_identity(java, revelation_tool, asm_rev, path)
        # The receipts must be equal in every field, including the classfile
        # digests: a different reading of the bytes would be the one thing a
        # generic-erasure build could plausibly get wrong.
        rows.append({"classfile": str(path), "clean": clean, "revelation": revelation,
                     "identical": clean == revelation})
    return {"samples": rows,
            "identical": all(row["identical"] for row in rows),
            "classes_compared": len(rows)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("--revelation-root", "--clean-root", "--identity-clean",
                 "--identity-revelation", "--classes", "--out"):
        parser.add_argument(flag, type=Path, required=True)
    parser.add_argument("--clean-asm", type=Path, required=True)
    parser.add_argument("--revelation-asm", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--limit", type=int, default=12)
    args = parser.parse_args()

    revelation = inventory(args.revelation_root)
    clean = inventory(args.clean_root)
    intruders = [jar for jar in revelation["asm_jars"]
                 if any(marker in jar["path"].lower() for marker in FORBIDDEN_IN_REVELATION)]
    if intruders:
        # Refusing to report is the point. A verdict computed on a classpath
        # that quietly contained the other target's ASM would be worthless.
        sys.stderr.write("a Clean Forge ASM jar is present in the Revelation runtime tree:\n")
        for jar in intruders:
            sys.stderr.write("  " + jar["path"] + "\n")
        return 2
    if len({jar["sha256"] for jar in revelation["asm_jars"]}) != 1:
        # Two different ASM builds anywhere in the Revelation tree means the
        # one the server resolves is a question of classpath order, not a fact.
        sys.stderr.write("the Revelation tree holds more than one distinct ASM build:\n")
        for jar in revelation["asm_jars"]:
            sys.stderr.write("  " + jar["path"] + "\n")
        return 2
    if revelation["asm_jars"][0]["sha256"] != sha256(args.revelation_asm):
        sys.stderr.write("the ASM in the Revelation tree is not the jar the pins name:\n")
        sys.stderr.write("  tree: " + revelation["asm_jars"][0]["path"] + "\n")
        sys.stderr.write("  pins: " + str(args.revelation_asm) + "\n")
        return 2

    samples = sorted(args.classes.rglob("*.class"))[:args.limit]
    if not samples:
        sys.stderr.write(f"no classfiles to compare under {args.classes}\n")
        return 2

    linkage = compare(args.identity_clean, args.identity_revelation, args.clean_asm,
                      args.revelation_asm, args.java, samples)
    report = {
        "schema": SCHEMA,
        "revelation_runtime": revelation,
        "clean_runtime": clean,
        "clean_asm_jars_in_revelation_tree": 0,
        "identity_tool_built_against_clean": str(args.identity_clean),
        "identity_tool_built_against_revelation": str(args.identity_revelation),
        "linkage": linkage,
        "verdict": ("RUNTIME_LINKAGE_EQUIVALENT" if linkage["identical"]
                    else "REQUIRES_ULTRA_REVIEW_ASM_ABI"),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"],
                      "asm_jars_in_revelation_tree": [j["path"] for j in revelation["asm_jars"]],
                      "other_asm_named_jars_in_revelation_tree":
                          [j["path"] for j in revelation["other_asm_named_jars"]],
                      "clean_asm_jars_in_revelation_tree": 0,
                      "classes_compared": linkage["classes_compared"],
                      "receipts_identical": linkage["identical"]}, indent=2))
    return 0 if report["verdict"] == "RUNTIME_LINKAGE_EQUIVALENT" else 1


if __name__ == "__main__":
    raise SystemExit(main())
