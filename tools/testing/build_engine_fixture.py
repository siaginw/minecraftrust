"""Build the synthetic engine fixture classfile used by the engine controls.

The engine integration suite refuses to run on a class it did not observe, so it
takes the fixture as an explicit input (RUSTCRAFT_TEST_CLASS_FIXTURE) rather than
compiling one itself. This script is the reproducible way to produce that input
from the committed source, using the pinned Java 8 toolchain.

    python -B tools/testing/build_engine_fixture.py --out <dir>

The emitted classfile is then pinned into the environment alongside
RUSTCRAFT_TEST_JAVA, RUSTCRAFT_TEST_V2_CLASSES and RUSTCRAFT_TEST_ASM. Nothing
here is game code; see tools/testing/fixtures/example/Fixture.java.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent / "fixtures/example/Fixture.java"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="directory to write Fixture.class into")
    parser.add_argument("--java", default="javac", help="javac from the pinned Java 8 toolchain")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    process = subprocess.run([args.java, "-g", "-d", str(out), str(SOURCE)],
                             capture_output=True, text=True)
    if process.returncode != 0:
        sys.stderr.write(process.stdout + process.stderr)
        return process.returncode
    produced = out / "example/Fixture.class"
    if not produced.is_file():
        sys.stderr.write("javac reported success but produced no example/Fixture.class\n")
        return 1
    print(produced)
    print(hashlib.sha256(produced.read_bytes()).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
