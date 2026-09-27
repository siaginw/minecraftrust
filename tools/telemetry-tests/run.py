"""Reproduce H3 telemetry and existing JNI regressions in the isolated checkout.

No Forge artifacts, dependencies, or production gates are changed. Raw outputs
and source/build hash bindings are retained under target/. Formatting and strict
legacy-FFI lint debt are reported independently from functional test outcomes.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import tomllib
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", Path(__file__),
             ROOT / "tools/testing/hardening_guard.py", ROOT / "tools/testing/rust_property_support.rs"]
    workspace = tomllib.loads((ROOT / "Cargo.toml").read_text(encoding="utf-8"))["workspace"]
    for member in workspace["members"]:
        # This manifest uses exact member paths; reject a future wildcard rather
        # than silently hash an incomplete or unrelated crate set.
        if any(token in member for token in ("*", "?", "[")):
            raise ValueError("workspace globs require Cargo metadata resolution")
        paths += sorted((ROOT / member).rglob("*.rs"))
        paths.append(ROOT / member / "Cargo.toml")
    for base in ("tools/telemetry-tests/src", "tools/bridge/src",
                 "tools/native-chunk-jni-tests/src"):
        paths += sorted((ROOT / base).rglob("*.java"))
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--cargo", type=Path, default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if ROOT.resolve() != Path(r"D:\minecraftrust-astra-hardening").resolve():
        parser.error("this runner is restricted to the isolated hardening checkout")
    output = (args.output or ROOT / "target/architecture-hardening" /
              ("h3-telemetry-" + uuid.uuid4().hex[:10])).resolve()
    if not output.is_relative_to((ROOT / "target").resolve()):
        parser.error("output must remain under isolated target/")
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    java = args.java_home.resolve() / "bin/java.exe"
    javac = args.java_home.resolve() / "bin/javac.exe"
    dll = ROOT / "target/release/rustcraft_ffi.dll"
    receipt = {"schema_version": 1, "kind": "H3_TELEMETRY_V2_REGRESSIONS",
               "started_utc": datetime.now(timezone.utc).isoformat(),
               "production_authority": False, "results": [],
               "source_hashes_before": sources(),
               "dll_before_build_sha256": digest(dll) if dll.exists() else None,
               "tools": {str(path): digest(path) for path in (java, javac, args.cargo)},
               "limits": ["Native-body timing excludes the JNI crossing.",
                          "Functional fixtures are not performance measurements.",
                          "Formatting/lint debt is not converted into a test pass.",
                          "Uninstrumented exports and unknown fields remain in the coverage report."]}

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, argv, required=True, input_bytes=None):
        argv = list(map(str, argv))
        started = time.monotonic()
        log = output / (name + ".log")
        with log.open("wb") as stream:
            code = subprocess.run(argv, cwd=ROOT, env=env, input=input_bytes,
                                  stdout=stream, stderr=subprocess.STDOUT).returncode
        row = {"name": name, "argv": argv, "exit_code": code, "required": required,
               "seconds": time.monotonic() - started,
               "log": log.relative_to(ROOT).as_posix(), "log_sha256": digest(log)}
        receipt["results"].append(row)
        save()
        print(json.dumps(row), flush=True)
        if code and required:
            raise RuntimeError(name + " failed; see " + str(log))
        return log

    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":
            raise RuntimeError("isolation/gate guard failed")
        run("java-version", [java, "-version"])
        run("cargo-version", [args.cargo, "--version"])
        rustfmt = args.cargo.parent / "rustfmt.exe"
        run("format-changed-files", [rustfmt, "--edition", "2021", "--config", "skip_children=true", "--check",
            "crates/metrics/src/lib.rs", "crates/ffi/tests/telemetry_contract.rs",
            *["crates/ffi/src/" + name + ".rs" for name in
              ("lib", "native_chunk", "packet_encode_v2", "owned_snapshot", "spawn", "wnoise")]])
        run("format-workspace", [args.cargo, "fmt", "--check"], required=False)
        run("clippy-metrics-strict", [args.cargo, "clippy", "--locked", "-p", "metrics",
                                     "--all-targets", "--", "-D", "warnings"])
        run("clippy-ffi-strict", [args.cargo, "clippy", "--locked", "-p", "ffi",
                                 "--all-targets", "--no-deps", "--", "-D", "warnings"], required=False)
        legacy_lints = ["clippy::missing_safety_doc", "clippy::unnecessary_cast",
                        "clippy::manual_unwrap_or", "non_snake_case",
                        "clippy::items_after_test_module"]
        receipt["legacy_ffi_lint_exceptions"] = legacy_lints
        run("clippy-ffi-baseline-exceptions", [args.cargo, "clippy", "--locked", "-p", "ffi",
            "--all-targets", "--no-deps", "--", "-D", "warnings",
            *[part for lint in legacy_lints for part in ("-A", lint)]])
        for name, options in (
            ("metrics", ["-p", "metrics"]),
            ("telemetry-contract", ["-p", "ffi", "--test", "telemetry_contract"]),
            ("native-chunk", ["-p", "native-chunk"]),
            ("workspace-lib", ["--workspace", "--lib"]),
            ("protocol", ["-p", "protocol"]),
        ):
            run(name, [args.cargo, "test", "--locked", *options])
        run("release-ffi", [args.cargo, "build", "--release", "--locked", "-p", "ffi"])
        receipt["dll_after_build_sha256"] = digest(dll)
        save()

        classes = output / "classes"
        classes.mkdir()
        java_sources = sorted((ROOT / "tools/telemetry-tests/src").rglob("*.java"))
        bridge = ROOT / "tools/bridge/src/com/rustcraft/bridge"
        java_sources += [bridge / "NativeChunkBridge.java", bridge / "PacketEncodeResultV2.java"]
        capture = bridge / "capture"
        java_sources += [capture / (name + ".java") for name in (
            "CaptureContract", "CaptureSource", "SnapshotCapture",
            "OwnedPacketSnapshot", "SyntheticCaptureSource", "OwnedSnapshotBridge")]
        fixtures = ROOT / "tools/native-chunk-jni-tests/src/com/rustcraft/bridge"
        java_sources += [fixtures / "NativeChunkJniV2Test.java", fixtures / "PacketEncodeResultV2Test.java",
                         fixtures / "capture/OwnedSnapshotJniTest.java", fixtures / "capture/SnapshotCaptureTest.java"]
        run("java-compile", [javac, "-proc:none", "-encoding", "UTF-8", "-source", "8",
                             "-target", "8", "-d", classes, *java_sources])
        for suite in ("TelemetryJniTest", "NativeChunkJniV2Test", "capture.OwnedSnapshotJniTest",
                      "PacketEncodeResultV2Test", "capture.SnapshotCaptureTest"):
            log = run(suite.replace(".", "-"), [java, "-Djava.library.path=" + str(dll.parent), "-cp", classes,
                       "com.rustcraft.bridge." + suite, dll])
            text = log.read_text(encoding="utf-8")
            if not re.search(r"^PASS " + re.escape(suite.split(".")[-1]) + r" .*checks=[1-9][0-9]*", text, re.M):
                raise RuntimeError(suite + " lacks a nonempty passing result")
        receipt["status"] = "PASS_WITH_RECORDED_LEGACY_CHECK_FAILURES" if any(
            row["exit_code"] for row in receipt["results"]) else "PASS"
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        receipt["source_hashes_after"] = sources()
        receipt["dll_after_tests_sha256"] = digest(dll) if dll.exists() else None
        receipt["isolation_after"] = inspect()
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat()
        if (receipt["source_hashes_before"] != receipt["source_hashes_after"] or
                receipt.get("dll_after_build_sha256") != receipt["dll_after_tests_sha256"] or
                receipt["isolation_after"]["status"] != "PASS"):
            receipt["status"] = "FAIL"
            receipt["source_build_or_isolation_drift"] = True
        save()
        print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json")}), flush=True)
    return int(receipt["status"] == "FAIL")


if __name__ == "__main__":
    raise SystemExit(main())
