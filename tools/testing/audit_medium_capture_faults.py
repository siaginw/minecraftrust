#!/usr/bin/env python3
"""Bounded Medium Java guard faults in disposable copies; NOT cargo-mutants.

Two plain-Java and six real-Forge faults cover result publication, rejected
accessors, input admission and failed-rewrite poisoning. Forge runs use the
unchanged pinned offline FML runner with fresh JVMs. Assertion failures in the
owned test harness catch faults; missing artifacts, hash failures, timeouts and
compiler failures never count as caught. No server or production packet path runs.
Use a short output root on Windows (for example D:\\rc-medium-audit) because
Java 8 class-cache paths otherwise exceed the legacy Windows path limit.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
CAPTURE = Path("tools/bridge/src/com/rustcraft/bridge/capture")
ORACLE = Path("tools/forge-capture/src/com/rustcraft/oracle/OwnedForgeCapture.java")
JAVA_TEST = Path("tools/native-chunk-jni-tests/src/com/rustcraft/bridge/capture/SnapshotCaptureTest.java")
FAULTS = [
    {"name": "result-publication-shape", "kind": "java", "file": CAPTURE / "CaptureContract.java",
     "old": "if (reason == null || (reason == Reason.ELIGIBLE) != (snapshot != null))",
     "new": "if (false)", "assertion": "empty eligible result"},
    {"name": "rejected-result-accessor", "kind": "java", "file": CAPTURE / "CaptureContract.java",
     "old": 'if (!accepted()) throw new IllegalStateException("Capture rejected: " + reason);',
     "new": "/* fault: rejected Result exposes its null snapshot */",
     "assertion": "Rejected snapshot accessible"},
    {"name": "pair-publication-shape", "kind": "forge", "file": ORACLE,
     "old": "if (rejection == null ? snapshot == null || packet == null || tickRefCounts == null\n"
            "                    : snapshot != null || packet != null || tickRefCounts != null)",
     "new": "if (false)", "assertion": "partial Pair eligible"},
    {"name": "rejected-pair-transport", "kind": "forge", "file": ORACLE,
     "old": 'if (rejection != null) throw new IllegalStateException("Rejected pair has no encodable snapshot: " + rejection);',
     "new": "if (rejection != null) return new byte[0];",
     "assertion": "noncanonical-state rejected transport reached JNI"},
    {"name": "noncanonical-state-admission", "kind": "forge", "file": ORACLE,
     "old": "                        || Block.field_176229_d.func_148745_a(id) != state",
     "new": "                        || false",
     "assertion": "noncanonical-state published partial data:"},
    {"name": "malformed-properties-poison", "kind": "forge", "file": ORACLE,
     "old": 'admissionFailure = "FALLBACK_INVALID_STATE_INPUT"; return false;\n        }\n        return admissionFailure == null;',
     "new": '/* fault: failed property admission leaves the source eligible */ return false;\n        }\n        return admissionFailure == null;',
     "assertion": "malformed-state-properties published partial data:"},
    {"name": "registry-width-admission", "kind": "forge", "file": ORACLE,
     "old": "|| id >= (1 << globalBits)) {", "new": "|| false) {",
     "assertion": "incompatible-registry-width published partial data:"},
    {"name": "failed-rewrite-poison", "kind": "forge", "file": ORACLE,
     "old": 'admissionFailure = "FALLBACK_MUTATION_INCOMPLETE";',
     "new": "/* fault: a partially failed rewrite does not poison the source */",
     "assertion": "failed-scratch-rewrite published partial data:"},
]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def selected_sources():
    paths = list((ROOT / CAPTURE).glob("*.java"))
    paths += list((ROOT / "tools/forge-capture/src").rglob("*.java"))
    paths += [ROOT / JAVA_TEST,
              ROOT / "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
              ROOT / "tools/forge-capture/runtime-pins.json",
              ROOT / "tools/forge-capture/log4j2.xml",
              ROOT / "docs/schemas/chunk-packet-fixture-v1.schema.json",
              ROOT / "docs/schemas/chunk-capture-rejection-v1.schema.json"]
    paths += [ROOT / "tools/testing" / name for name in
              ("forge_runtime.py", "forge_fixture_replay.py", "fixture_replay.py", "packet_decoder.py")]
    return sorted(set(paths))


def command(argv, cwd, log, timeout=240):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "PYTHONPATH"):
        env.pop(key, None)
    try:
        with log.open("wb") as stream:
            result = subprocess.run(list(map(str, argv)), cwd=cwd, env=env,
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        return result.returncode
    except subprocess.TimeoutExpired:
        with log.open("ab") as stream:
            stream.write(b"\nAUDIT_TIMEOUT\n")
        return 124


def make_case(seed, destination, fault=None):
    shutil.copytree(seed, destination)
    if fault is not None:
        path = destination / fault["file"]
        source = path.read_text(encoding="utf-8")
        if source.count(fault["old"]) != 1:
            raise RuntimeError("Exact source anchor changed: " + fault["name"])
        path.write_text(source.replace(fault["old"], fault["new"], 1), encoding="utf-8")
    return destination


def assertion_evidence(log, expected):
    if not log.is_file():
        return None
    text = log.read_text(encoding="utf-8", errors="replace")
    pattern = r"(?:java\.lang\.)?AssertionError: " + re.escape(expected)
    return next((line.strip() for line in text.splitlines() if re.search(pattern, line)), None)


def java_case(project, java_home, fault):
    classes = project / "classes"
    classes.mkdir()
    javac, java = java_home / "bin/javac.exe", java_home / "bin/java.exe"
    sources = sorted((project / CAPTURE).glob("*.java")) + [project / JAVA_TEST]
    built = command([javac, "-proc:none", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                     "-d", classes] + sources, project, project / "compile.log", 60)
    tested = None if built else command([java, "-cp", classes,
                    "com.rustcraft.bridge.capture.SnapshotCaptureTest"], project, project / "test.log", 60)
    evidence = assertion_evidence(project / "test.log", fault["assertion"]) if fault else None
    if built:
        outcome = "TIMEOUT" if built == 124 else "UNBUILDABLE"
    elif tested == 124:
        outcome = "TIMEOUT"
    elif fault is None:
        log = (project / "test.log").read_text(encoding="utf-8", errors="replace")
        outcome = "BASELINE_PASS" if tested == 0 and "PASS SnapshotCaptureTest" in log else "BASELINE_FAIL"
    else:
        outcome = "CAUGHT" if tested != 0 and evidence else "SURVIVED" if tested == 0 else "UNCLASSIFIED_FAILURE"
    return {"name": fault["name"] if fault else "java-baseline", "kind": "java", "outcome": outcome,
            "compileExit": built, "testExit": tested, "assertionEvidence": evidence,
            "logs": [str(project / name) for name in ("compile.log", "test.log")]}


def forge_case(project, java_home, dll, replay, manifest, fault):
    runtime = project / "runtime"
    runtime_code = command([sys.executable, "-B", project / "tools/testing/forge_runtime.py",
            "--output", runtime, "--java-home", java_home, "--dll", dll, "--manifest", manifest],
            project, project / "runner.log")
    receipt_path = runtime / "forge-runtime-result.json"
    receipt = json.loads(receipt_path.read_text(encoding="utf-8")) if receipt_path.is_file() else {}
    test_log = runtime / "oracle-jvm/jvm.log"
    evidence = assertion_evidence(test_log, fault["assertion"]) if fault else None
    replay_code, semantic = None, {}
    # Fresh runtime qualification must have succeeded before a test assertion
    # can be interpreted. A changed helper source is pinned in this copy's own
    # receipt, never compared against a previous root-source hash.
    qualified = "qualification" in receipt and "oracle_compile" in receipt
    if runtime_code == 0 and receipt.get("status") == "PASS":
        replay_code = command([sys.executable, "-B", project / "tools/testing/forge_fixture_replay.py",
                "--runtime-receipt", receipt_path, "--output", project / "fixtures",
                "--native-replay", replay], project, project / "replay.log")
        report = project / "fixtures/results.json"
        if report.is_file():
            semantic = json.loads(report.read_text(encoding="utf-8"))
    if fault is None:
        outcome = "BASELINE_PASS" if runtime_code == 0 and replay_code == 0 and semantic.get("status") == "PASS" else "BASELINE_FAIL"
    elif runtime_code == 124 or replay_code == 124:
        outcome = "TIMEOUT"
    elif runtime_code != 0 and qualified and evidence:
        outcome = "CAUGHT"
    elif runtime_code == 0 and replay_code == 0 and semantic.get("status") == "PASS":
        outcome = "SURVIVED"
    else:
        outcome = "UNCLASSIFIED_FAILURE"
    return {"name": fault["name"] if fault else "forge-baseline", "kind": "forge", "outcome": outcome,
            "runtimeExit": runtime_code, "runtimeStatus": receipt.get("status"),
            "runtimeReason": receipt.get("reason"), "qualifiedBeforeAssertion": qualified,
            "assertionEvidence": evidence, "semanticReplayExit": replay_code,
            "semanticStatus": semantic.get("status"), "semanticReason": semantic.get("reason"),
            "acceptedCount": semantic.get("acceptedCount"), "rejectionCount": semantic.get("rejectionCount"),
            "receipt": str(receipt_path), "testLog": str(test_log)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--dll", type=Path, default=ROOT / "target/release/rustcraft_ffi.dll")
    parser.add_argument("--native-replay", type=Path, default=ROOT / "target/debug/examples/snapshot_replay.exe")
    parser.add_argument("--manifest", type=Path, default=ROOT / ".rustcraft-local/forge-runtime.json")
    parser.add_argument("--only", action="append", choices=[fault["name"] for fault in FAULTS],
                        help="Run selected fault(s), with their complete baseline(s); omit for all eight.")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    required = (args.dll, args.native_replay, args.manifest,
                args.java_home / "bin/java.exe", args.java_home / "bin/javac.exe")
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        write_json(output / "receipt.json", {"status": "INCOMPLETE", "reason": "MISSING_AUDIT_INPUT",
                   "missing": missing, "outcomes": []})
        print("MEDIUM_CAPTURE_FAULT_AUDIT INCOMPLETE MISSING_AUDIT_INPUT", missing, flush=True)
        return 2
    sources = selected_sources()
    original = {str(path.relative_to(ROOT)): digest(path) for path in sources}
    seed = output / "frozen-source"
    for path in sources:
        target = seed / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    inputs = output / "inputs"
    inputs.mkdir()
    qualified = {}
    for name, source in (("dll", args.dll.resolve()), ("replay", args.native_replay.resolve()),
                         ("manifest", args.manifest.resolve())):
        target = inputs / source.name
        shutil.copy2(source, target)
        qualified[name] = {"source": str(source), "frozen": str(target), "sha256": digest(target)}
    java_home = args.java_home.resolve()
    for name in ("java.exe", "javac.exe"):
        qualified[name] = {"path": str(java_home / "bin" / name), "sha256": digest(java_home / "bin" / name)}
    selected = [fault for fault in FAULTS if not args.only or fault["name"] in args.only]
    outcomes = []
    report = {"tool": "project-owned Medium focused Java guard fault audit; NOT cargo-mutants",
              "scope": "OFFLINE_ONLY_NO_SERVER_NO_PRODUCTION_AUTHORITY", "status": "INCOMPLETE",
              "sources": original, "auditScriptSHA256": digest(Path(__file__)), "inputs": qualified,
              "selectedFaults": [{**fault, "file": str(fault["file"])} for fault in selected], "outcomes": outcomes}
    receipt = output / "receipt.json"
    try:
        for kind in ("java", "forge"):
            if not any(fault["kind"] == kind for fault in selected):
                continue
            cases = [None] + [fault for fault in selected if fault["kind"] == kind]
            for index, fault in enumerate(cases):
                name = fault["name"] if fault else kind + "-baseline"
                project = make_case(seed, output / (kind[0] + str(index)), fault)
                result = (java_case(project, java_home, fault) if kind == "java" else
                          forge_case(project, java_home, Path(qualified["dll"]["frozen"]),
                                     Path(qualified["replay"]["frozen"]), Path(qualified["manifest"]["frozen"]), fault))
                outcomes.append(result)
                result["project"] = str(project)
                write_json(receipt, report)
                print(name, result["outcome"], result.get("assertionEvidence") or "", flush=True)
                if fault is None and result["outcome"] != "BASELINE_PASS":
                    raise RuntimeError("Baseline did not pass; this source set has no valid fault conclusions")
        after = {path: digest(ROOT / path) for path in original}
        report["sourceUnchanged"] = after == original
        report["changedSources"] = [path for path in original if original[path] != after[path]]
        unresolved = [row["name"] for row in outcomes if row["outcome"] not in ("BASELINE_PASS", "CAUGHT")]
        report["unresolved"] = unresolved
        report["status"] = "PASS" if not unresolved and report["sourceUnchanged"] else "FAIL"
    except (OSError, ValueError, RuntimeError) as error:
        report.update(status="INCOMPLETE", reason=str(error))
    write_json(receipt, report)
    print("MEDIUM_CAPTURE_FAULT_AUDIT", report["status"], str(receipt), flush=True)
    return 0 if report["status"] == "PASS" else 2 if report["status"] == "INCOMPLETE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
