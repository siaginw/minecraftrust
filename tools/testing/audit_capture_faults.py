#!/usr/bin/env python3
"""Focused Java/Python guard faults in disposable copies, not cargo-mutants.

This command never changes repository sources and never launches Forge/server
code. Every selected fault has an exact source-anchor precondition. Baselines,
compiler diagnostics and caught/surviving outcomes are retained under --output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CAPTURE = Path("tools/bridge/src/com/rustcraft/bridge/capture")
JAVA_TEST = Path("tools/native-chunk-jni-tests/src/com/rustcraft/bridge/capture/SnapshotCaptureTest.java")
PYTHON = Path("tools/testing")

JAVA_FAULTS = [
    ("canonical-thread", "SnapshotCapture.java", "if (Thread.currentThread() != context.canonicalServerThread)", "if (false)", 0),
    ("complete-writer-inventory", "SnapshotCapture.java", "if (!context.completeKnownWriterInventory)", "if (false)", 0),
    ("unknown-writer", "SnapshotCapture.java", "if (writer == WriterClass.UNKNOWN)", "if (false)", 0),
    ("async-writer", "SnapshotCapture.java", "if (writer == WriterClass.ASYNC_UNCOORDINATED)", "if (false)", 0),
    ("observation-is-not-exclusion", "SnapshotCapture.java", "if (writer == WriterClass.DIRECT_BUT_OBSERVABLE)", "if (false)", 0),
    ("lease-required", "SnapshotCapture.java", "if (context.lease == null || !context.lease.covers(domain))", "if (false)", 0),
    ("initial-incarnation", "SnapshotCapture.java", "if (begin.incarnation != context.expectedIncarnation || begin.generation != context.expectedGeneration)", "if (false)", 0),
    ("tile-entity-policy", "SnapshotCapture.java", "if (syntheticTeEffects != null && context.tileEntityPolicy == TileEntityPolicy.UNQUALIFIED)", "if (false)", 0),
    ("post-te-revalidation", "SnapshotCapture.java", "baseline.validate(end);", "/* removed by focused fault audit */", 1),
]
PYTHON_FAULTS = [
    ("exact-consumption", "if reader.offset != len(data):", "if False:", None),
    ("word-count", "if word_count != (4096 * bits + 63) // 64:", "if False:", None),
    ("selected-section", "if not emitted_mask & (1 << y):", "if False:", None),
    ("redundant-final-count", "if len(sections) != emitted_mask.bit_count():", "if False:",
     "EQUIVALENT: the fixed 0..15 loop appends exactly once for each selected valid mask bit; prior exceptions return no packet."),
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_at(text, old, new, occurrence):
    pieces = text.split(old)
    expected = 2 if old == "baseline.validate(end);" else 1
    if len(pieces) - 1 != expected:
        raise RuntimeError(f"source drift: expected {expected} occurrences of {old!r}")
    before = old.join(pieces[:occurrence + 1])
    after = old.join(pieces[occurrence + 1:])
    return before + new + after


def command(args, cwd, log):
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    try:
        result = subprocess.run(args, cwd=cwd, env=environment, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=60, text=True,
                                encoding="utf-8", errors="replace")
        log.write_text(result.stdout, encoding="utf-8")
        return result.returncode
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b""
        if isinstance(output, bytes):
            output = output.decode("utf-8", "replace")
        log.write_text(output + "\nAUDIT_TIMEOUT\n", encoding="utf-8")
        return 124


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    java = args.java_home / "bin/java.exe"
    javac = args.java_home / "bin/javac.exe"
    if not java.is_file() or not javac.is_file():
        raise RuntimeError("exact Java toolchain missing")
    sources = sorted((ROOT / CAPTURE).glob("*.java")) + [ROOT / JAVA_TEST]
    python_sources = [ROOT / PYTHON / name for name in
                      ("packet_decoder.py", "test_packet_decoder.py", "fixture_replay.py", "generate_synthetic_fixtures.py")]
    original = {str(path.relative_to(ROOT)): digest(path) for path in sources + python_sources}
    outcomes = []
    java_cases = [("baseline", None, None, None, 0)] + JAVA_FAULTS
    for name, filename, old, new, occurrence in java_cases:
        case = destination / ("java-" + name)
        case.mkdir()
        classes = case / "classes"
        classes.mkdir()
        copied = []
        for path in sources:
            copy = case / path.name
            text = path.read_text(encoding="utf-8")
            if path.name == filename:
                text = replace_at(text, old, new, occurrence)
            copy.write_text(text, encoding="utf-8")
            copied.append(str(copy))
        built = command([str(javac), "-source", "8", "-target", "8", "-d", str(classes)] + copied,
                        case, case / "compile.log")
        code = None if built else command([str(java), "-cp", str(classes),
                           "com.rustcraft.bridge.capture.SnapshotCaptureTest"], case, case / "test.log")
        outcome = ("NEEDS_INVESTIGATION" if built == 124 or code == 124 else
                   "UNBUILDABLE" if built else "CAUGHT" if code else "SURVIVED")
        if name == "baseline":
            if built or code:
                raise RuntimeError("Java baseline failed; no mutation conclusion valid")
            outcome = "BASELINE_PASS"
        outcomes.append({"language": "Java", "name": name, "outcome": outcome,
                         "compileExit": built, "testExit": code})
        print("Java", name, outcome, flush=True)
    python_cases = [("baseline", None, None, None)] + PYTHON_FAULTS
    for name, old, new, equivalent in python_cases:
        case = destination / ("python-" + name)
        case.mkdir()
        for path in python_sources:
            text = path.read_text(encoding="utf-8")
            if path.name == "packet_decoder.py" and old is not None:
                text = replace_at(text, old, new, 0)
            (case / path.name).write_text(text, encoding="utf-8")
        code = command([sys.executable, "-B", "-m", "unittest", "test_packet_decoder.DecoderTests"],
                       case, case / "test.log")
        outcome = "NEEDS_INVESTIGATION" if code == 124 else "CAUGHT" if code else "SURVIVED"
        if name == "baseline":
            if code:
                raise RuntimeError("Python baseline failed; no mutation conclusion valid")
            outcome = "BASELINE_PASS"
        elif not code and equivalent:
            outcome = "EQUIVALENT"
        outcomes.append({"language": "Python", "name": name, "outcome": outcome,
                         "testExit": code, "equivalenceArgument": equivalent if outcome == "EQUIVALENT" else None})
        print("Python", name, outcome, flush=True)
    after = {path: digest(ROOT / path) for path in original}
    if original != after:
        raise RuntimeError("repository source changed during audit; retain receipt and rerun")
    report = {"tool": "project-owned focused fault injection; NOT cargo-mutants",
              "sources": original, "auditScriptSHA256": digest(Path(__file__).resolve()),
              "javaToolchain": str(args.java_home), "javaSHA256": digest(java),
              "javacSHA256": digest(javac),
              "outcomes": outcomes}
    (destination / "receipt.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if any(item["outcome"] in ("SURVIVED", "UNBUILDABLE", "NEEDS_INVESTIGATION") for item in outcomes):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
