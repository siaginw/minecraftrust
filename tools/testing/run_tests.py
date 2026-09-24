#!/usr/bin/env python3
"""Bounded test lanes. Only compilation is cached; every Java test gets a new JVM."""
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
import uuid

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_ROOT = ROOT / "target" / "rustcraft-tests"
RUST_PUBLIC = [
    ("native-chunk", ["cargo", "test", "--locked", "-p", "native-chunk"]),
    ("workspace-lib", ["cargo", "test", "--workspace", "--lib", "--locked"]),
    ("protocol", ["cargo", "test", "--locked", "-p", "protocol"]),
    ("ffi-release", ["cargo", "build", "--release", "--locked", "-p", "ffi"]),
    ("packet-encode-v2", ["cargo", "test", "--locked", "-p", "ffi", "--test", "packet_encode_v2"]),
    ("packet-encode-contract", ["cargo", "test", "--locked", "-p", "native-chunk", "--test", "packet_encode_contract"]),
    ("packet-encode-v2-unit", ["cargo", "test", "--locked", "-p", "ffi", "--lib", "packet_encode_v2"]),
    ("packet-snapshot", ["cargo", "test", "--locked", "-p", "native-chunk", "--test", "packet_snapshot"]),
    ("snapshot-replay-build", ["cargo", "build", "--locked", "-p", "ffi", "--example", "snapshot_replay"]),
]
RUST_PROPERTY = [
    ("property-contract", ["cargo", "test", "--locked", "-p", "native-chunk", "--test", "property_contract"]),
    ("property-v2", ["cargo", "test", "--locked", "-p", "ffi", "--lib", "packet_encode_v2::properties"]),
]
JAVA_SOURCES = [
    "tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java",
    "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
    "tools/native-chunk-jni-tests/src/com/rustcraft/bridge/PacketEncodeResultV2Test.java",
    "tools/native-chunk-jni-tests/src/com/rustcraft/bridge/NativeChunkJniV2Test.java",
]
JAVA_SOURCES += [path.relative_to(ROOT).as_posix() for path in sorted((ROOT / "tools/bridge/src/com/rustcraft/bridge/capture").glob("*.java"))]
JAVA_SOURCES += [
    "tools/native-chunk-jni-tests/src/com/rustcraft/bridge/capture/SnapshotCaptureTest.java",
    "tools/native-chunk-jni-tests/src/com/rustcraft/bridge/capture/OwnedSnapshotJniTest.java",
]
JAVA_MAINS = ["com.rustcraft.bridge.PacketEncodeResultV2Test", "com.rustcraft.bridge.NativeChunkJniV2Test",
              "com.rustcraft.bridge.capture.SnapshotCaptureTest", "com.rustcraft.bridge.capture.OwnedSnapshotJniTest"]
COMPILER_OPTIONS = ["-encoding", "UTF-8", "-source", "8", "-target", "8"]
EVIDENCE_TESTS = [
    "TestProvenanceValidation.test_broken_yaml_rejected",
    "TestProvenanceValidation.test_duplicate_keys_rejected",
    "TestProvenanceValidation.test_pending_commit_rejected",
    "TestProvenanceValidation.test_awaiting_status_rejected",
    "TestLiveRegistry.test_broken_fixture_file_rejected",
]
EXCLUSIONS = [
    {"scope": "cargo test --workspace (all targets)", "reason": "MISSING_PUBLIC_SOURCE",
     "detail": "crates/nbt/src/bin/bench.rs and oracle_cli.rs are declared but absent; workspace --lib plus available integration targets are explicit."},
    {"scope": "test_live_registry_clean / test_valid_minimal_passes_schema / test_measured_requires_sha256 / test_hash_mismatch_rejected / strict evidence audit",
     "reason": "MISSING_HISTORICAL_ARTIFACT",
     "detail": "Historical raw evidence is absent. Missing-hash and hash-mismatch tests require absent machine/raw/M14R-cargo-test.txt and stop at RAW_DATA_MISSING. These are not green checks; five artifact-independent checker regressions are selected explicitly."},
    {"scope": "Minecraft/Forge Java oracles, world/heightmap/bedrock and socket/live tests",
     "reason": "EXTERNAL_OR_LIVE_SCOPE",
     "detail": "Require unavailable jars, worlds or running servers; not public-checkout correctness coverage."},
    {"scope": "benchmarks", "reason": "SEPARATE_LANE", "detail": "No benchmark is run by a correctness lane."},
]


def source_snapshot():
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock"]
    paths.extend(path for path in (ROOT / "crates").rglob("*") if path.is_file() and path.suffix in (".rs", ".toml"))
    paths.extend(ROOT / source for source in JAVA_SOURCES)
    paths.extend((ROOT / "tools").rglob("*.java"))
    paths.extend((ROOT / "tools/testing").glob("*.py"))
    paths.extend((ROOT / "tools/testing").glob("*.rs"))
    paths.extend(path for path in (ROOT / "tests/fixtures/issue1-capture").rglob("*") if path.is_file())
    paths.extend(path for path in (ROOT / "tests/fixtures/issue1-properties").rglob("*") if path.is_file())
    paths.extend((ROOT / "docs/schemas").glob("*.json"))
    paths.extend([ROOT / "tools/run-rustcraft-tests.ps1", Path(__file__), ROOT / "tools/testing/test_runner.py",
                  ROOT / "tools/tests/test_evidence_integrity.py", ROOT / "tools/verify_evidence_integrity.py",
                  ROOT / "tools/tests/fixtures/broken-provenance-2026-09-18.yaml"])
    return [{"path": path.relative_to(ROOT).as_posix(), "sha256": file_sha256(path)}
            for path in sorted(set(paths)) if path.is_file()]


def oracle_rows_pass(text, expected):
    """Legacy Forge mains print failed assertions but do not set an exit code."""
    rows = re.findall(r"^TEST (\d+) \[[^\r\n]+?\]: (PASS|FAIL)\b", text, re.MULTILINE)
    return rows == [(str(number), "PASS") for number in range(1, expected + 1)]


class MissingPrerequisite(Exception):
    pass


def canonical_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def path_identity(path):
    """Hash classpath directories by ordered relative paths AND file contents."""
    path = Path(path).resolve(strict=True)
    if path.is_file():
        return {"path": str(path), "sha256": file_sha256(path)}
    files = [{"path": child.relative_to(path).as_posix(), "sha256": file_sha256(child)}
             for child in sorted(path.rglob("*")) if child.is_file()]
    return {"path": str(path), "files": files, "sha256": hashlib.sha256(canonical_bytes(files)).hexdigest()}


def java_environment():
    return {name: os.environ.get(name) for name in ("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")}


def java_cache_identity(sources, toolchain, classpath, dll, compiler_options, launch_options, invocations, environment=None):
    """Order is significant for sources, classpath, options and launched mains."""
    manifest = {
        "schema_version": 1,
        "sources": [path_identity(path) for path in sources],
        "toolchain": toolchain,
        "classpath": [path_identity(path) for path in classpath],
        "jni_library": path_identity(dll) if dll else None,
        "compiler_options": list(compiler_options),
        "launch_options": list(launch_options),
        "invocations": invocations,
        "inherited_java_environment": environment or {},
    }
    return hashlib.sha256(canonical_bytes(manifest)).hexdigest(), manifest


def lane_inventory(lane):
    inventory = []
    if lane == "public":
        inventory.extend({"id": name, "argv": args} for name, args in RUST_PUBLIC)
        inventory.append({"id": "runner-regressions", "argv": [sys.executable, "-B", "tools/testing/test_runner.py"]})
        inventory.append({"id": "evidence-checker-regressions", "argv": [sys.executable, "-B", "-m", "unittest"] +
                          ["tools.tests.test_evidence_integrity." + test for test in EVIDENCE_TESTS]})
    elif lane == "java-jni":
        inventory.append({"id": "ffi-release", "argv": RUST_PUBLIC[3][1]})
    elif lane == "property":
        inventory.extend({"id": name, "argv": args, "minimum_test_count": 1} for name, args in RUST_PROPERTY)
    elif lane == "fixture":
        inventory.extend({"id": name, "argv": args} for name, args in (RUST_PUBLIC[5], RUST_PUBLIC[4], RUST_PUBLIC[8]))
    if lane in ("public", "fixture", "decoder"):
        inventory.append({"id": "decoder-regressions", "argv": [sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/testing", "-p", "test_packet_decoder.py", "-v"]})
    if lane in ("public", "fixture"):
        executable = ROOT / "target/debug/examples" / ("snapshot_replay.exe" if os.name == "nt" else "snapshot_replay")
        inventory.append({"id": "synthetic-fixture-replay", "argv": [sys.executable, "-B", "tools/testing/fixture_replay.py", "--native-replay", str(executable)], "capture_kind": "SYNTHETIC", "requires_success": "snapshot-replay-build"})
    if lane in ("public", "java-jni"):
        inventory.append({"id": "java-v2", "sources": JAVA_SOURCES, "mains": JAVA_MAINS, "fresh_jvm_per_main": True})
    elif lane == "forge":
        inventory.append({"id": "forge-oracles", "mains": ["com.rustcraft.bench.EventOracle", "RegistryOracle", "CapabilityOracle"], "requires": "Ordered, hashed Forge classpath manifest"})
    elif lane == "benchmark":
        inventory.append({"id": "forge-benchmark", "mains": ["ForgeBenchmarks"], "requires": "Ordered, hashed Forge classpath manifest", "correctness_claim": False})
    elif lane == "modpack":
        inventory.append({"id": "modpack-preflight", "requires": "Hashed modpack artifact manifest", "execution": "No qualified offline modpack replay harness yet; no live campaign"})
    return inventory


class Runner:
    def __init__(self, args):
        self.args = args
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        self.output = OUTPUT_ROOT / "runs" / (stamp + "-" + args.lane + "-" + uuid.uuid4().hex[:8])
        self.output.mkdir(parents=True)
        self.results = []
        self.started = datetime.now(timezone.utc).isoformat()
        self.sources = source_snapshot()

    def record(self, name, status, **details):
        result = {"id": name, "status": status, **details}
        self.results.append(result)
        print("[{}] {}{}".format(status, name, ": " + details["reason"] if "reason" in details else ""), flush=True)
        return result

    def command(self, name, argv, cwd=ROOT, expected_oracle_rows=None, minimum_test_count=None):
        argv = [str(arg) for arg in argv]
        log = self.output / (name + ".log")
        print("[RUN] " + subprocess.list2cmdline(argv), flush=True)
        started = time.monotonic()
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", CARGO_TARGET_DIR=str(ROOT / "target"))
        env["PROPTEST_CASES"] = str(2048 if self.args.lane == "property" and getattr(self.args, "stress", False) else 64)
        try:
            with log.open("w", encoding="utf-8") as stream:
                with subprocess.Popen(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as process:
                    for line in process.stdout:
                        stream.write(line)
                        print(line, end="", flush=True)
                    returncode = process.wait()
            output = log.read_text(encoding="utf-8")
            rows_ok = expected_oracle_rows is None or oracle_rows_pass(output, expected_oracle_rows)
            count = sum(int(value) for value in re.findall(r"test result: ok\. (\d+) passed;", output))
            count_ok = minimum_test_count is None or count >= minimum_test_count
            return self.record(name, "PASS" if returncode == 0 and rows_ok and count_ok else "FAIL", argv=argv, cwd=str(cwd),
                               exit_code=returncode, seconds=round(time.monotonic() - started, 3), log=str(log),
                               minimum_test_count=minimum_test_count, observed_rust_test_count=count,
                               proptest_cases=int(env["PROPTEST_CASES"]))
        except OSError as error:
            return self.record(name, "NOT_RUN", reason="MISSING_TOOL", detail=str(error), argv=argv, cwd=str(cwd))

    def java_toolchain(self):
        configured = self.args.java_home or os.environ.get("JAVA8_HOME") or os.environ.get("JAVA_HOME")
        if not configured:
            raise MissingPrerequisite("Set JAVA8_HOME or JAVA_HOME to JDK 8u504 (or pass -JavaHome).")
        home = Path(configured).resolve()
        suffix = ".exe" if os.name == "nt" else ""
        tools = {}
        for name in ("javac", "java"):
            executable = home / "bin" / (name + suffix)
            if not executable.is_file():
                raise MissingPrerequisite("Missing tool: " + str(executable))
            result = subprocess.run([str(executable), "-version"], capture_output=True, text=True)
            version = (result.stdout + result.stderr).strip()
            if result.returncode or not re.search(r"\b1\.8\.0_504\b", version):
                raise MissingPrerequisite("JDK 8u504 required; {} reports {}".format(executable, version))
            tools[name] = {**path_identity(executable), "version": version}
        # javac.exe is only a launcher; bind the compiler/runtime archives too.
        tools["archives"] = [path_identity(home / entry) for entry in ("lib/tools.jar", "jre/lib/rt.jar")]
        return tools

    def java(self, name, sources, mains, classpath=(), dll=None):
        try:
            toolchain = self.java_toolchain()
            sources = [ROOT / source for source in sources]
            launch = ["-ea"] + (["-Djava.library.path=" + str(dll.parent)] if dll else [])
            invocations = [{"main": main, "args": args} for main, args in mains]
            key, identity = java_cache_identity(sources, toolchain, classpath, dll, COMPILER_OPTIONS, launch, invocations, java_environment())
        except (MissingPrerequisite, FileNotFoundError) as error:
            self.record(name, "NOT_RUN", reason="MISSING_PREREQUISITE", detail=str(error))
            return
        cache = OUTPUT_ROOT / "java-cache"
        cache.mkdir(parents=True, exist_ok=True)
        pointer = cache / (key + ".json")
        classes = None
        if pointer.is_file():
            try:
                saved = json.loads(pointer.read_text(encoding="utf-8"))
                candidate = Path(saved["classes"])
                # A cache record never authorizes loading classes outside our cache.
                candidate.resolve().relative_to(cache.resolve())
                if saved["identity"] == identity and path_identity(candidate) == saved["class_files"]:
                    classes = candidate
            except (ValueError, KeyError, OSError):
                pass
        cache_hit = classes is not None

        def inputs_unchanged():
            try:
                current_key, _ = java_cache_identity(sources, self.java_toolchain(), classpath, dll, COMPILER_OPTIONS, launch, invocations, java_environment())
                unchanged = current_key == key
            except (MissingPrerequisite, OSError):
                unchanged = False
            if not unchanged:
                self.record(name + "-input-stability", "FAIL", reason="JAVA_INPUT_CHANGED_DURING_RUN")
            return unchanged

        if classes is None:
            classes = cache / (key + "-" + uuid.uuid4().hex[:8]) / "classes"
            classes.mkdir(parents=True)
            command = [toolchain["javac"]["path"], *COMPILER_OPTIONS, "-classpath", os.pathsep.join(map(str, classpath)), "-d", str(classes), *map(str, sources)]
            if self.command(name + "-javac", command, self.output)["status"] != "PASS":
                return
            if not inputs_unchanged():
                return
            entry = {"identity": identity, "classes": str(classes), "class_files": path_identity(classes)}
            temporary = pointer.with_name(pointer.name + "." + uuid.uuid4().hex + ".tmp")
            temporary.write_text(json.dumps(entry, indent=2) + "\n", encoding="utf-8")
            temporary.replace(pointer)
        self.record(name + "-build-cache", "PASS", cache_hit=cache_hit, key=key, identity=identity, classes=str(classes))
        for index, (main, args) in enumerate(mains):
            # Launch a new JVM even on a cache hit. The DLL is never hot-reloaded.
            if not inputs_unchanged():
                return
            runtime_classpath = os.pathsep.join([str(classes), *map(str, classpath)])
            expected_rows = {"com.rustcraft.bench.EventOracle": 5, "RegistryOracle": 8, "CapabilityOracle": 3}.get(main)
            self.command(name + "-" + str(index + 1), [toolchain["java"]["path"], *launch, "-cp", runtime_classpath, main, *args], self.output, expected_oracle_rows=expected_rows)
            if not inputs_unchanged():
                return

    def checked_manifest(self, manifest_path, kind):
        if not manifest_path or not Path(manifest_path).is_file():
            raise MissingPrerequisite("Supply a local " + kind + " manifest; see tools/testing/README.md.")
        manifest_file = Path(manifest_path).resolve()
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        if manifest.get("schema_version") != 1 or manifest.get("kind") != kind:
            raise ValueError("Expected schema_version=1, kind=" + kind)
        versions = ("14.23.5.2860",) if kind == "forge-classpath" else ("14.23.5.2860", "14.23.5.2846")
        if manifest.get("minecraft_version") != "1.12.2" or manifest.get("forge_version") not in versions:
            raise ValueError("Unsupported Minecraft/Forge identity for this manifest kind.")
        entries = manifest.get("classpath" if kind == "forge-classpath" else "artifacts")
        if not isinstance(entries, list) or not entries:
            raise MissingPrerequisite("Manifest has no artifact inventory.")
        paths = []
        for entry in entries:
            path = (manifest_file.parent / entry["path"]).resolve()
            if not path.is_file():
                raise MissingPrerequisite("Missing artifact: " + str(path))
            digest = entry.get("sha256", "")
            if not re.fullmatch("[0-9a-f]{64}", digest) or file_sha256(path) != digest:
                raise ValueError("Artifact hash mismatch or invalid digest: " + str(path))
            paths.append(path)
        return paths, manifest

    def run(self):
        lane = self.args.lane
        if lane in ("public", "java-jni", "fixture", "property", "decoder"):
            for item in lane_inventory(lane):
                if "argv" not in item:
                    continue
                if item["id"] == "evidence-checker-regressions":
                    fixture = ROOT / "tools/tests/fixtures/broken-provenance-2026-09-18.yaml"
                    if not fixture.is_file():
                        self.record(item["id"], "NOT_RUN", reason="MISSING_PREREQUISITE", detail="Required checker fixture missing: " + str(fixture))
                        continue
                    try:
                        import yaml  # Existing checker requirement; never install it here.
                    except ImportError:
                        self.record(item["id"], "NOT_RUN", reason="MISSING_PREREQUISITE", detail="Existing evidence checker requires PyYAML.")
                        continue
                required_build = item.get("requires_success")
                if required_build and not any(result["id"] == required_build and result["status"] == "PASS" for result in self.results):
                    self.record(item["id"], "NOT_RUN", reason="BUILD_PREREQUISITE_FAILED")
                    continue
                argv = item["argv"]
                if item["id"] == "synthetic-fixture-replay":
                    argv = [*argv, "--report", str(self.output / "synthetic-fixture-replay.json")]
                self.command(item["id"], argv, minimum_test_count=item.get("minimum_test_count"))
            if lane in ("public", "java-jni"):
                built = next(result for result in self.results if result["id"] == "ffi-release")
                if built["status"] == "PASS":
                    dll = ROOT / "target" / "release" / ("rustcraft_ffi.dll" if os.name == "nt" else "librustcraft_ffi.so")
                    self.java("java-v2", JAVA_SOURCES, [(JAVA_MAINS[0], []), (JAVA_MAINS[1], [str(dll)]),
                                                       (JAVA_MAINS[2], []), (JAVA_MAINS[3], [str(dll)])], dll=dll)
                else:
                    self.record("java-v2", "NOT_RUN", reason="BUILD_PREREQUISITE_FAILED")
        elif lane in ("forge", "benchmark"):
            try:
                classpath, manifest = self.checked_manifest(self.args.forge_classpath_manifest, "forge-classpath")
                self.record("forge-artifact-preflight", "PASS", manifest=manifest, artifacts=[path_identity(path) for path in classpath])
            except MissingPrerequisite as error:
                self.record(lane, "NOT_RUN", reason="MISSING_EXTERNAL_ARTIFACT", detail=str(error))
                return
            if lane == "forge":
                self.java("forge-oracles", ["tools/forge-oracle/src/com/rustcraft/bench/EventOracle.java", "tools/forge-oracle/src/RegistryOracle.java", "tools/forge-oracle/src/CapabilityOracle.java"],
                          [("com.rustcraft.bench.EventOracle", []), ("RegistryOracle", []), ("CapabilityOracle", [])], classpath=classpath)
            else:
                self.java("forge-benchmark", ["tools/forge-bench/src/ForgeBenchmarks.java"], [("ForgeBenchmarks", [])], classpath=classpath)
        elif lane == "modpack":
            try:
                paths, manifest = self.checked_manifest(self.args.modpack_artifact_manifest, "modpack-artifacts")
                self.record("modpack-artifact-preflight", "PASS", manifest=manifest, artifacts=[path_identity(path) for path in paths])
                self.record("modpack", "NOT_RUN", reason="NO_QUALIFIED_OFFLINE_REPLAY_HARNESS", detail="Artifact hashes do not prove modpack compatibility. Live campaigns are outside this stage.")
            except MissingPrerequisite as error:
                self.record("modpack", "NOT_RUN", reason="MISSING_EXTERNAL_ARTIFACT", detail=str(error))

    def finish(self):
        final_sources = source_snapshot()
        if final_sources != self.sources:
            self.record("source-stability", "FAIL", reason="SOURCE_CHANGED_DURING_RUN")
        statuses = {item["status"] for item in self.results}
        status = "FAIL" if "FAIL" in statuses else "INCOMPLETE" if "NOT_RUN" in statuses or not statuses else "PASS"
        source_paths = [ROOT / "tools/run-rustcraft-tests.ps1", Path(__file__)]
        receipt = {"schema_version": 1, "lane": self.args.lane, "status": status, "started_utc": self.started,
                   "proptest_cases": 2048 if self.args.lane == "property" and getattr(self.args, "stress", False) else 64,
                   "finished_utc": datetime.now(timezone.utc).isoformat(), "inventory": lane_inventory(self.args.lane),
                   "results": self.results, "public_exclusions": EXCLUSIONS if self.args.lane == "public" else [],
                   "runner_sources": [path_identity(path) for path in source_paths],
                   "source_hashes_before": self.sources, "source_hashes_after": final_sources,
                   "output_directory": str(self.output), "cached_test_results": False}
        receipt_path = self.output / "results.json"
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print("Lane {}: {}\nReceipt: {}".format(self.args.lane, status, receipt_path), flush=True)
        return 1 if status == "FAIL" else 2 if status == "INCOMPLETE" else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lane", choices=["public", "property", "fixture", "decoder", "java-jni", "forge", "modpack", "benchmark"])
    parser.add_argument("--java-home")
    parser.add_argument("--forge-classpath-manifest")
    parser.add_argument("--modpack-artifact-manifest")
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--stress", action="store_true", help="Property lane only: increase PROPTEST_CASES from 64 to 2048.")
    args = parser.parse_args()
    if args.stress and args.lane != "property":
        parser.error("--stress is supported only by the property lane")
    if args.inventory:
        print(json.dumps({"lane": args.lane, "inventory": lane_inventory(args.lane), "public_exclusions": EXCLUSIONS if args.lane == "public" else []}, indent=2))
        return 0
    runner = Runner(args)
    try:
        runner.run()
    except Exception as error:
        runner.record("runner", "FAIL", reason=type(error).__name__, detail=str(error))
    return runner.finish()


if __name__ == "__main__":
    sys.exit(main())
