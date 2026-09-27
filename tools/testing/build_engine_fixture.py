"""Build the synthetic engine fixture classfiles used by the engine controls.

The engine integration suite refuses to run on a class it did not observe, so it
takes the fixture as an explicit input (RUSTCRAFT_TEST_CLASS_FIXTURE) rather than
compiling one itself. This script is the reproducible way to produce that input
from the committed source, using the pinned Java 8 toolchain.

    python -B tools/testing/build_engine_fixture.py --out <dir>

The emitted classfile is then pinned into the environment alongside
RUSTCRAFT_TEST_JAVA, RUSTCRAFT_TEST_V2_CLASSES and RUSTCRAFT_TEST_ASM. Nothing
here is game code; see tools/testing/fixtures/example/Fixture.java.

`--session-out` additionally builds the session-bound pair: a PRE-writer class
and a separately compiled POST-writer class with the same binary name, carrying a
real `MixinMerged.sessionId` so `identifySessionBound` has something to mask.
Those land in `<dir>/pre` and `<dir>/post` and are pinned into the environment as
RUSTCRAFT_TEST_SESSION_PRE_FIXTURE and RUSTCRAFT_TEST_SESSION_POST_FIXTURE.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "fixtures/example/Fixture.java"
MIXIN_STUB = HERE / "fixtures/session/org/spongepowered/asm/mixin/transformer/meta/MixinMerged.java"
SESSION_SOURCES = {
    "pre": HERE / "fixtures/session/example/SessionFixture.java",
    "post": HERE / "fixtures/session-post/example/SessionFixture.java",
}


def build(java: str, out: Path, source: Path, expected: str, with_stub: bool = False) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    sources = [str(source)] + ([str(MIXIN_STUB)] if with_stub else [])
    process = subprocess.run([java, "-g", "-d", str(out), *sources],
                             capture_output=True, text=True)
    if process.returncode != 0:
        sys.stderr.write(process.stdout + process.stderr)
        raise SystemExit(process.returncode)
    produced = out / expected
    if not produced.is_file():
        sys.stderr.write(f"javac reported success but produced no {expected}\n")
        raise SystemExit(1)
    return produced


def report(produced: Path) -> None:
    print(produced)
    print(hashlib.sha256(produced.read_bytes()).hexdigest())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="directory to write Fixture.class into")
    parser.add_argument("--java", default="javac", help="javac from the pinned Java 8 toolchain")
    parser.add_argument("--session-out", help="directory for the PRE/POST session-bound pair")
    args = parser.parse_args()
    report(build(args.java, Path(args.out), SOURCE, "example/Fixture.class"))
    if args.session_out:
        root = Path(args.session_out)
        for stage, source in sorted(SESSION_SOURCES.items()):
            report(build(args.java, root / stage, source, "example/SessionFixture.class", with_stub=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
