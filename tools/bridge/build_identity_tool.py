"""Build the CANONICAL_ID_V2 identity tool against one explicitly pinned ASM jar.

    python -B tools/bridge/build_identity_tool.py --out <dir> --asm <jar> [--java-home <jdk8>]

The identity tool is the oracle the engine recomputes exact identities with, so
which ASM it was compiled against is part of its provenance and must never be
implicit. This script takes the jar as an argument and records the jar's hash
next to the output, so building the same sources against two different pinned
ASM jars is a first-class, reproducible operation rather than an accident.

Only sources that genuinely need ASM are listed. The rest of
`tools/bridge/src` pulls in net.minecraft/net.minecraftforge and cannot be built
outside a real game launch; the session-identity sources here are the ones the
engine, the collector and the Java gate all share.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
SRC = HERE / "src/com/rustcraft/coremod"

#: Sources the identity tool is built from. CanonicalClassIdentityV2 is the
#: parser; the other four are the session-bound admission path it feeds.
SOURCES = (
    SRC / "CanonicalClassIdentityV2.java",
    SRC / "SessionBoundIdentityCertificate.java",
    SRC / "LiveWriterPlan.java",
    SRC / "LiveHookSupport.java",
    SRC.parent / "qualification/SameProcessAcquisition.java",
)


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--asm", required=True, type=Path)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA8_HOME", "javac")))
    args = parser.parse_args()

    javac = args.java_home / "bin" / "javac.exe" if args.java_home.is_dir() else Path(args.java_home)
    asm = args.asm.resolve(strict=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.rglob("*.class"):
        stale.unlink()
    process = subprocess.run(
        [str(javac), "-g", "-cp", str(asm), "-d", str(out), *(str(s) for s in SOURCES)],
        capture_output=True, text=True)
    sys.stderr.write(process.stderr)
    if process.returncode != 0:
        return process.returncode
    produced = out / "com/rustcraft/coremod/CanonicalClassIdentityV2.class"
    if not produced.is_file():
        sys.stderr.write("javac reported success but produced no CanonicalClassIdentityV2.class\n")
        return 1
    receipt = {
        "schema": "RUSTCRAFT_IDENTITY_TOOL_BUILD_V1",
        "java_home": str(javac.parent.parent),
        "asm_jar": str(asm),
        "asm_sha256": sha(asm),
        "sources": {str(s): sha(s) for s in SOURCES},
        "classes": {str(p.relative_to(out).as_posix()): sha(p) for p in sorted(out.rglob("*.class"))},
    }
    (out / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(produced)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
