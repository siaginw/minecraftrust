"""Run isolated bounded Loom models and require specific negative-control failures."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import tomllib
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect

CASES = {
    "handoff_admission_drain": "ADMISSION_AFTER_FREEZE",
    "owner_and_candidate_publication": "PARTIAL_OWNER_PUBLICATION",
    "worker_revocation": "STALE_RESULT_COMMITTED",
    "worker_cancellation": "STALE_RESULT_COMMITTED",
    "cancellation_checks_origin": "FOREIGN_WORK_CANCELLED_LOCAL_JOB",
    "worker_state_generation": "STALE_RESULT_COMMITTED",
    "worker_registry_epoch": "STALE_RESULT_COMMITTED",
    "worker_ownership_generation": "STALE_RESULT_COMMITTED",
    "async_io_unload_adoption": "STALE_RESULT_COMMITTED",
    "slot_reuse_generation": "STALE_HANDLE_ALIASED_NEW_SLOT",
    "exhausted_generation_retires": "GENERATION_WRAP_ABA",
    "ready_queue_publication": "READY_WITHOUT_PAYLOAD",
    "immutable_snapshot_reclamation": "RECLAIMED_LIVE_SNAPSHOT",
}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inputs():
    paths = [p for p in HERE.rglob("*") if p.is_file()
             and not {"target", "__pycache__"}.intersection(p.relative_to(HERE).parts)]
    paths += [ROOT / p for p in (
        "crates/native-capability/src/lib.rs", "crates/native-state-vnext/src/store.rs",
        "crates/native-state-vnext/src/lib.rs", "crates/rustcraft-core/src/lib.rs",
        "docs/architecture/retained-native-state.md", "docs/architecture/loom-lifecycle-models.md",
        "tools/testing/hardening_guard.py", "machine/architecture-hardening/isolation.json",
        "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java",
        "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java")]
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}


def dependency_hashes(metadata):
    """Compare fetched sources with cached archives verified against Cargo.lock."""
    lock = tomllib.loads((HERE / "Cargo.lock").read_text(encoding="utf-8"))
    pinned = {(p["name"], p["version"]): p.get("checksum") for p in lock["package"]}
    records = {}
    for package in metadata["packages"]:
        if package["source"] is None:
            continue
        directory = Path(package["manifest_path"]).parent
        archive = directory.parents[2] / "cache" / directory.parent.name / (directory.name + ".crate")
        archive_sha = sha(archive)
        if archive_sha != pinned[(package["name"], package["version"])]:
            raise RuntimeError("dependency archive checksum differs from Cargo.lock")
        files = {}
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                if not member.isfile():
                    continue
                prefix = directory.name + "/"
                if not member.name.startswith(prefix):
                    raise RuntimeError("unexpected crate archive root")
                relative = member.name[len(prefix):]
                if Path(relative).is_absolute() or ".." in Path(relative).parts:
                    raise RuntimeError("unexpected crate archive member path")
                expected = hashlib.sha256(tar.extractfile(member).read()).hexdigest()
                actual = sha(directory / relative)
                if actual != expected:
                    raise RuntimeError("dependency file changed: " + str(directory / relative))
                files[relative] = actual
        records[package["name"] + "@" + package["version"]] = {
            "crate_checksum": archive_sha, "file_count": len(files),
            "files_manifest_sha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
            "license": package["license"],
        }
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo", type=Path, default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"))
    args = parser.parse_args()
    if ROOT.resolve() != Path(r"D:\minecraftrust-astra-hardening").resolve():
        parser.error("runner restricted to isolated hardening checkout")
    output = ROOT / "target/architecture-hardening" / ("h7-loom-" + uuid.uuid4().hex[:12])
    output.mkdir(parents=True, exist_ok=False)
    receipt = {
        "schema_version": 1, "kind": "H7_BOUNDED_LOOM_LIFECYCLE_MODELS", "status": "INCOMPLETE",
        "production_authority": False, "runtime_source_loom_instrumented": False,
        "started_utc": datetime.now(timezone.utc).isoformat(), "results": [],
        "bounds": {"threads_including_main": 3, "preemptions": 3, "branches_per_execution": 256,
                   "permutation_cap": None, "loom_duration_cap": None, "test_process_deadline_seconds": 30},
        "source_hashes_before": inputs(), "limitations": [
            "Abstract state machines; no proof of H4/H6 implementation or JVM synchronization.",
            "READY queue and async IO adoption are prospective obligations, not implemented engine features.",
            "Only configured bounded schedules and Loom-supported memory behaviors are checked.",
            "Tool hashes cover listed binaries, not every MSVC linker/SDK/system library or environment input.",
        ],
    }

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    env = dict(os.environ)
    for name in list(env):
        if name.startswith("LOOM_") or name in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER"):
            env.pop(name)

    def run(name, argv, negative_marker=None, timeout=30):
        log = output / (name + ".log")
        start = time.monotonic()
        code = None
        timed_out = False
        with log.open("wb") as stream:
            try:
                code = subprocess.run(list(map(str, argv)), cwd=ROOT, env=env, stdout=stream,
                                      stderr=subprocess.STDOUT, timeout=timeout).returncode
            except subprocess.TimeoutExpired:
                timed_out = True
        raw = log.read_text(encoding="utf-8", errors="replace")
        row = {"name": name, "argv": list(map(str, argv)), "exit_code": code,
               "seconds": time.monotonic() - start, "timed_out": timed_out,
               "log": log.relative_to(ROOT).as_posix(), "log_sha256": sha(log)}
        if negative_marker is None:
            ok = not timed_out and code == 0
        else:
            ok = not timed_out and code == 101 and negative_marker in raw and "0 passed; 1 failed" in raw
            row["expected_invariant_failure"] = negative_marker
        row["status"] = "PASS" if ok else "FAIL"
        receipt["results"].append(row)
        save()
        print(json.dumps(row), flush=True)
        if not ok:
            raise RuntimeError(name + " failed its required outcome; raw log retained")
        return raw, row

    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":
            raise RuntimeError("isolation/production gate check failed")
        rustup = args.cargo.resolve().parent / "rustup.exe"
        resolved, _ = run("resolve-toolchain", [rustup, "which", "rustc"])
        toolchain = Path(resolved.strip()).parent
        cargo = toolchain / "cargo.exe"
        env["PATH"] = str(toolchain) + os.pathsep + env.get("PATH", "")
        env["RUSTC"] = str(toolchain / "rustc.exe")
        env["RUSTDOC"] = str(toolchain / "rustdoc.exe")
        tool_paths = [args.cargo.resolve(), rustup, Path(sys.executable), Path(shutil.which("git")).resolve()]
        tool_paths += [toolchain / name for name in ("cargo.exe", "rustc.exe", "rustdoc.exe", "rustfmt.exe",
                                                    "cargo-fmt.exe", "cargo-clippy.exe", "clippy-driver.exe")]
        receipt["tool_hashes_before"] = {str(p): sha(p) for p in tool_paths}
        manifest = HERE / "Cargo.toml"
        base = ["--locked", "--offline", "--manifest-path", manifest, "--target-dir", output / "cargo"]
        run("rustc-version", [toolchain / "rustc.exe", "-Vv"])
        metadata_text, _ = run("dependency-metadata", [cargo, "metadata", "--locked", "--offline",
                                                      "--manifest-path", manifest, "--format-version", "1"])
        metadata = json.loads(metadata_text)
        receipt["dependency_hashes_before"] = dependency_hashes(metadata)
        run("format", [cargo, "fmt", "--manifest-path", manifest, "--check"])
        run("clippy", [cargo, "clippy", *base, "--all-targets", "--", "-D", "warnings"], timeout=120)
        build, _ = run("build-model-tests", [cargo, "test", *base, "--release", "--test", "lifecycle",
                                             "--no-run", "--message-format=json"], timeout=120)
        binaries = []
        for line in build.splitlines():
            if not line.startswith("{"):
                continue
            record = json.loads(line)
            if record.get("reason") == "compiler-artifact" and record.get("executable") and record["target"]["name"] == "lifecycle":
                binaries.append(Path(record["executable"]))
        if len(binaries) != 1:
            raise RuntimeError("expected exactly one lifecycle test executable")
        binary = binaries[0]
        receipt["test_binary"] = {"path": str(binary), "sha256_before": sha(binary)}
        listed, _ = run("test-inventory", [binary, "--list"])
        expected = set(CASES) | {"broken_" + name for name in CASES} | {"broken_handoff_skips_drain"}
        found = set(re.findall(r"^([a-z_]+): test$", listed, re.MULTILINE))
        if found != expected:
            raise RuntimeError("model inventory differs from the explicit runner inventory")
        for name in CASES:
            raw, row = run(name, [binary, name, "--exact", "--nocapture", "--test-threads=1"])
            match = re.search(r"MODEL_PASS name=" + name + r" executions=(\d+) threads=3 preemptions=3 branches=256", raw)
            if not match or int(match[1]) < 2 or "1 passed; 0 failed" not in raw:
                raise RuntimeError("missing completed, nontrivial model exploration: " + name)
            row["completed_executions"] = int(match[1])
        negatives = {"broken_" + name: marker for name, marker in CASES.items()}
        negatives["broken_handoff_skips_drain"] = "JAVA_NATIVE_WRITERS_OVERLAP"
        for name, marker in negatives.items():
            run(name, [binary, name, "--exact", "--ignored", "--nocapture", "--test-threads=1"], marker)
        receipt["positive_models"] = len(CASES)
        receipt["negative_controls_detected"] = len(negatives)
        receipt["status"] = "PASS_BOUNDED_MODELS_ONLY"
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        try:
            receipt["source_hashes_after"] = inputs()
            receipt["tool_hashes_after"] = {p: sha(p) for p in receipt.get("tool_hashes_before", {})}
            if "metadata" in locals():
                receipt["dependency_hashes_after"] = dependency_hashes(metadata)
            if "test_binary" in receipt:
                receipt["test_binary"]["sha256_after"] = sha(receipt["test_binary"]["path"])
            receipt["isolation_after"] = inspect()
            for category in ("source_hashes", "tool_hashes", "dependency_hashes"):
                if receipt.get(category + "_before") != receipt.get(category + "_after"):
                    raise RuntimeError(category + " drift")
            if receipt["isolation_after"]["status"] != "PASS":
                raise RuntimeError("isolation drift")
            if "test_binary" in receipt and receipt["test_binary"]["sha256_before"] != receipt["test_binary"]["sha256_after"]:
                raise RuntimeError("test executable drift")
        except Exception as error:
            receipt["status"] = "FAIL"
            receipt["finalization_error"] = type(error).__name__ + ": " + str(error)
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat()
        save()
    print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json"),
                      "sha256": sha(output / "receipt.json")}), flush=True)
    return int(receipt["status"] != "PASS_BOUNDED_MODELS_ONLY")


if __name__ == "__main__":
    raise SystemExit(main())
