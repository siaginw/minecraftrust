"""Source-bound H8 section/packet-cache prototype and Java 8 differential oracle."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect
sys.path.insert(0, str(ROOT / "tools/nbt-region-experiment"))
from job import LaunchFailure, launch

FIXTURES = {"uniform", "small", "large_sparse", "direct", "dense_hot", "no_sky",
            "partial", "recipient", "empty", "all_sections", "mutated", "air",
            "local_15", "local_16", "local_32", "local_128", "local_255", "direct_256"}
LAYOUT_FIELDS = ("kind name repeat layout reads writes packet_rounds legacy_read_ns prototype_read_ns "
                 "legacy_write_ns prototype_write_ns legacy_warm_body_ns legacy_cold_body_ns "
                 "prototype_body_ns legacy_inline_bytes prototype_inline_bytes prototype_vector_bytes "
                 "lookup_capacity_entries packet_bytes linear_lookups hashed_lookups digest").split()
CACHE_FIELDS = ("kind repeat players radius resident_budget mutation slow legacy_ns cached_ns hits misses invalidated "
                "invalidation_ns legacy_pending_peak_bytes cached_live_peak_bytes cached_resident_peak_bytes "
                "cached_external_peak_bytes evictions backpressure_drains delivered_bytes checksum").split()


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sources(metadata):
    paths = [p for p in HERE.rglob("*") if p.is_file()
             and not {"target", "__pycache__"}.intersection(p.relative_to(HERE).parts)]
    for package in metadata["packages"]:
        if package["source"] is not None:
            raise RuntimeError("unexpected external dependency; this experiment only uses local crates")
        base = Path(package["manifest_path"]).parent
        if not base.is_relative_to(ROOT):
            raise RuntimeError("dependency outside isolated checkout")
        paths += [Path(package["manifest_path"])]
        paths += [p for p in (base / "src").rglob("*") if p.is_file()]
        if (base / "build.rs").exists():
            paths.append(base / "build.rs")
    paths += [ROOT / p for p in ("Cargo.toml", "tools/testing/hardening_guard.py",
        "tools/nbt-region-experiment/job.py", "tools/nbt-region-experiment/test_job.py",
        "machine/architecture-hardening/isolation.json",
        "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java",
        "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java")]
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}


def measurements(raw):
    layout, cache, transition, lookup = [], [], [], []
    for line in raw.splitlines():
        fields = line.split("\t")
        if fields[0] in ("layout", "cache"):
            schema = LAYOUT_FIELDS if fields[0] == "layout" else CACHE_FIELDS
            if len(fields) != len(schema):
                raise ValueError("measurement column count")
            row = dict(zip(schema, fields))
            for key in schema:
                if key not in ("kind", "name", "layout"):
                    row[key] = int(row[key])
                    if key != "repeat" and row[key] < 0:
                        raise ValueError("negative measurement")
            (layout if fields[0] == "layout" else cache).append(row)
        elif fields[0] == "transition" and len(fields) == 6:
            transition.append(dict(zip(("repeat", "promotion_ns", "demotion_ns", "promotions", "demotions"), map(int, fields[1:]))))
        elif fields[0] == "lookup" and len(fields) == 9:
            lookup.append(dict(zip(("repeat", "cardinality", "writes", "linear_ns", "hashed_ns", "linear_lookups", "hashed_lookups", "hash_capacity_entries"), map(int, fields[1:]))))
        else:
            raise ValueError("unexpected benchmark output: " + line)
    expected_layout = set(itertools.product(range(-1, 5), ("uniform", "small_palette", "large_palette", "dense_hot", "direct_1024")))
    expected_cache = set(itertools.product(range(-1, 5), (1, 16), (2, 8), (65536, 524288), (0, 1), (0, 1)))
    if len(layout) != 30 or {(r["repeat"], r["name"]) for r in layout} != expected_layout:
        raise ValueError("layout inventory")
    if len(cache) != 192 or {(r["repeat"], r["players"], r["radius"], r["resident_budget"], r["mutation"], r["slow"]) for r in cache} != expected_cache:
        raise ValueError("cache inventory")
    if len(transition) != 6 or {r["repeat"] for r in transition} != set(range(-1, 5)):
        raise ValueError("transition inventory")
    if len(lookup) != 12 or {(r["repeat"], r["cardinality"]) for r in lookup} != set(itertools.product(range(-1, 5), (8, 64))):
        raise ValueError("lookup inventory")
    for row in lookup:
        if row["writes"] != 32768 or row["linear_lookups"] != row["writes"] or row["hashed_lookups"] != row["writes"] or row["linear_ns"] <= 0 or row["hashed_ns"] <= 0:
            raise ValueError("lookup measurement")
    for row in layout:
        if (row["reads"], row["writes"], row["packet_rounds"]) != (100000, 10000, 16):
            raise ValueError("layout operation counts")
        if any(row[field] <= 0 for field in LAYOUT_FIELDS if field.endswith("_ns")):
            raise ValueError("nonpositive timing")
    for row in cache:
        requests = row["players"] * (2 * row["radius"] + 1) ** 2 * 2
        if row["hits"] + row["misses"] != requests:
            raise ValueError("request accounting")
        live_budget = row["resident_budget"] * 3 // 2
        if row["cached_live_peak_bytes"] > live_budget or row["cached_resident_peak_bytes"] > row["resident_budget"] or row["legacy_pending_peak_bytes"] > live_budget:
            raise ValueError("payload retention budget exceeded")
        if row["cached_external_peak_bytes"] > row["cached_live_peak_bytes"]:
            raise ValueError("retention accounting")
        if row["legacy_ns"] <= 0 or row["cached_ns"] <= 0:
            raise ValueError("nonpositive workload timing")
    for row in transition:
        if row["promotions"] < 1 or row["demotions"] < 1:
            raise ValueError("transition missing")
    def summarize(rows, group_fields, numeric_fields):
        groups = {}
        for row in rows:
            if row["repeat"] < 0:
                continue
            key = tuple(row[f] for f in group_fields)
            groups.setdefault(key, []).append(row)
        output = []
        for key, values in groups.items():
            if len(values) != 5:
                raise ValueError("sample count")
            entry = dict(zip(group_fields, key))
            entry["samples"] = 5
            for field in numeric_fields:
                numbers = [v[field] for v in values]
                entry[field] = {"min": min(numbers), "median": statistics.median(numbers), "max": max(numbers)}
            output.append(entry)
        return output
    return {"raw_rows": len(layout) + len(cache) + len(transition) + len(lookup), "discarded_warmup_rows": 40,
            "measurement_samples_per_case": 5,
            "layout": summarize(layout, ["name", "layout"], [f for f in LAYOUT_FIELDS if f not in ("kind", "name", "layout", "repeat", "digest")]),
            "cache": summarize(cache, ["players", "radius", "resident_budget", "mutation", "slow"], [f for f in CACHE_FIELDS if f not in ("kind", "repeat", "players", "radius", "resident_budget", "mutation", "slow", "checksum")]),
            "lookup": summarize(lookup, ["cardinality"], ["writes", "linear_ns", "hashed_ns", "linear_lookups", "hashed_lookups", "hash_capacity_entries"]),
            "transitions": summarize(transition, [], ["promotion_ns", "demotion_ns", "promotions", "demotions"])}


def check_fixture_artifacts(directory):
    records = []
    for line in (directory / "manifest.tsv").read_text().splitlines():
        name, mask, length, checksum = line.split("\t")
        if name not in FIXTURES:
            raise ValueError("unknown fixture")
        raw = (directory / (name + ".prototype.bin")).read_bytes()
        if raw != (directory / (name + ".legacy.bin")).read_bytes() or len(raw) != int(length):
            raise ValueError("fixture file mismatch")
        digest = 0xcbf29ce484222325
        for b in raw:
            digest = ((digest ^ b) * 0x100000001b3) & ((1 << 64) - 1)
        if digest != int(checksum):
            raise ValueError("fixture digest mismatch")
        records.append({"name": name, "mask": int(mask), "body_bytes": len(raw), "body_sha256": sha(directory / (name + ".prototype.bin")), "input_sha256": sha(directory / (name + ".input"))})
    if len(records) != len(FIXTURES) or {r["name"] for r in records} != FIXTURES:
        raise ValueError("fixture inventory")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--correctness-only", action="store_true")
    parser.add_argument("--cargo", type=Path, default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"))
    parser.add_argument("--java-home", type=Path, default=Path(r"D:\rustcraft-toolchains\temurin8\jdk8u504-b01"))
    args = parser.parse_args()
    if ROOT.resolve() != Path(r"D:\minecraftrust-astra-hardening").resolve():
        parser.error("runner restricted to isolated checkout")
    output = ROOT / "target/architecture-hardening" / ("h8-section-packet-cache-" + uuid.uuid4().hex[:12])
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"schema_version": 1, "kind": "H8_SECTION_PACKET_CACHE_PROTOTYPE", "status": "INCOMPLETE",
               "started_utc": datetime.now(timezone.utc).isoformat(), "production_authority": False,
               "correctness_only": args.correctness_only, "results": [], "limits": {
                   "runtime_process_memory_mib": 512, "runtime_active_processes": 1,
                   "runtime_output_bytes_per_stream": 1048576, "benchmark_deadline_seconds": 180,
                   "resident_payload_budgets": [65536, 524288], "unique_adopted_live_payload_budgets": [98304, 786432]},
               "limitations": ["Synthetic serial protocol-340/no-block-entity bodies, before framing/compression/encryption.",
                   "Current identity and generation updates are trusted harness inputs, not integrated H6/JVM ownership.",
                   "Payload budgets exclude metadata, source worlds, temporary builders and allocator overhead.",
                   "Shared-host timings with five samples per case; no real-server or general speedup claim.",
                   "Hash inventory covers listed local source/runtime/tool files, not full OS, MSVC toolchain or SDK."]}
    env = dict(os.environ)
    for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, argv, *, timeout=60, limited=False, negative=None):
        argv = list(map(str, argv))
        stdout, stderr = output / (name + ".stdout.log"), output / (name + ".stderr.log")
        start = time.monotonic()
        code, timed, peak, exception = None, False, None, None
        if limited:
            try:
                code, out, err, timed, peak = launch(argv, cwd=ROOT, env=env, memory_mib=512, timeout=timeout)
            except LaunchFailure as failure:
                code, out, err, timed, peak = failure.code, failure.stdout, failure.stderr, failure.timed_out, failure.peak
                exception = str(failure)
            stdout.write_bytes(out)
            stderr.write_bytes(err)
        else:
            with stdout.open("wb") as out, stderr.open("wb") as err:
                child = subprocess.Popen(argv, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=out, stderr=err, shell=False)
                try:
                    code = child.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    timed = True
                    # Limit cleanup to this child and its descendants, never a name-wide kill.
                    subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"], stdout=err, stderr=err, timeout=10, check=False)
                    if child.poll() is None:
                        child.kill()
                    code = child.wait(timeout=10)
        raw = stdout.read_text(encoding="utf-8", errors="replace")
        errors = stderr.read_text(encoding="utf-8", errors="replace")
        ok = not timed and exception is None and (code == 0 if negative is None else code != 0 and negative in raw + errors)
        row = {"name": name, "argv": argv, "status": "PASS" if ok else "FAIL", "exit_code": code,
               "timed_out": timed, "elapsed_seconds": time.monotonic() - start,
               "job_memory_limit_applied": limited, "peak_process_commit_bytes": peak,
               "stdout": stdout.relative_to(ROOT).as_posix(), "stdout_sha256": sha(stdout),
               "stderr": stderr.relative_to(ROOT).as_posix(), "stderr_sha256": sha(stderr)}
        if negative:
            row["expected_failure_marker"] = negative
        if exception:
            row["exception"] = exception
        receipt["results"].append(row)
        save()
        print(json.dumps(row), flush=True)
        if not ok:
            raise RuntimeError(name + " failed; partial logs retained")
        return raw, errors

    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":
            raise RuntimeError("isolation/production gate failure")
        rustup = args.cargo.resolve().parent / "rustup.exe"
        resolved, _ = run("resolve-toolchain", [rustup, "which", "rustc"])
        toolchain = Path(resolved.strip()).parent
        cargo = toolchain / "cargo.exe"
        env["PATH"] = str(toolchain) + os.pathsep + env.get("PATH", "")
        env["RUSTC"], env["RUSTDOC"] = str(toolchain / "rustc.exe"), str(toolchain / "rustdoc.exe")
        java, javac = args.java_home / "bin/java.exe", args.java_home / "bin/javac.exe"
        tool_paths = [args.cargo.resolve(), rustup, Path(sys.executable), Path(shutil.which("git")).resolve(), java, javac,
                      args.java_home / "jre/bin/server/jvm.dll", args.java_home / "jre/lib/rt.jar", args.java_home / "lib/tools.jar"]
        tool_paths += [toolchain / name for name in ("cargo.exe", "rustc.exe", "rustdoc.exe", "rustfmt.exe", "cargo-fmt.exe", "cargo-clippy.exe", "clippy-driver.exe")]
        receipt["tool_hashes_before"] = {str(p): sha(p) for p in tool_paths}
        manifest = HERE / "Cargo.toml"
        base = ["--locked", "--offline", "--manifest-path", manifest, "--target-dir", output / "cargo"]
        raw, _ = run("dependency-metadata", [cargo, "metadata", "--locked", "--offline", "--manifest-path", manifest, "--format-version", "1"])
        metadata = json.loads(raw)
        receipt["source_hashes_before"] = sources(metadata)
        receipt["local_packages"] = [{"name": p["name"], "version": p["version"], "manifest": p["manifest_path"], "license": p["license"]} for p in metadata["packages"]]
        run("rustc-version", [toolchain / "rustc.exe", "-Vv"])
        _, java_version = run("java-version", [java, "-Xms16m", "-Xmx128m", "-version"], limited=True)
        if 'version "1.8.' not in java_version:
            raise RuntimeError("Java 8 required")
        run("format", [cargo, "fmt", "--manifest-path", manifest, "--check"])
        run("clippy", [cargo, "clippy", *base, "--all-targets", "--no-deps", "--", "-D", "warnings"], timeout=120)
        raw, _ = run("rust-contracts", [cargo, "test", *base], timeout=120)
        if "10 passed; 0 failed" not in raw:
            raise RuntimeError("Rust regression inventory changed")
        run("process-controls", [sys.executable, ROOT / "tools/nbt-region-experiment/test_job.py"], timeout=30)
        build, _ = run("release-build", [cargo, "build", *base, "--release", "--message-format=json"], timeout=120)
        binaries = []
        for line in build.splitlines():
            if line.startswith("{"):
                entry = json.loads(line)
                if entry.get("reason") == "compiler-artifact" and entry.get("executable") and entry["target"]["name"] == "section-packet-cache-experiment":
                    binaries.append(Path(entry["executable"]))
        if len(binaries) != 1:
            raise RuntimeError("expected exactly one release executable")
        binary = binaries[0]
        receipt["binary"] = {"path": str(binary), "sha256_before": sha(binary)}
        fixtures = output / "fixtures"
        raw, _ = run("fixtures", [binary, "fixtures", fixtures], limited=True)
        if "FIXTURES_PASS cases=18" not in raw:
            raise RuntimeError("fixture generation inventory")
        receipt["fixtures"] = check_fixture_artifacts(fixtures)
        classes = output / "classes"
        classes.mkdir()
        run("compile-java-oracle", [javac, "-J-Xms16m", "-J-Xmx128m", "-source", "8", "-target", "8", "-Xlint:all", "-Werror", "-d", classes, HERE / "PacketOracle.java"], limited=True)
        oracle = [java, "-Xms16m", "-Xmx128m", "-cp", classes, "PacketOracle"]
        raw, _ = run("java-oracle", [*oracle, fixtures], limited=True)
        if "PASS PacketOracle cases=18 encoders=2" not in raw:
            raise RuntimeError("Java oracle inventory")
        tampered = output / "tampered-fixture"
        shutil.copytree(fixtures, tampered)
        fault = tampered / "uniform.prototype.bin"
        content = bytearray(fault.read_bytes()); content[0] ^= 1; fault.write_bytes(content)
        run("java-tamper-control", [*oracle, tampered], limited=True, negative="FIRST_DIVERGENCE file=uniform.prototype.bin offset=0")
        receipt["tamper_control"] = {"file": str(fault), "sha256": sha(fault), "expected_first_offset": 0}
        receipt["java_classes"] = {str(p): sha(p) for p in classes.glob("*.class")}
        if not args.correctness_only:
            raw, _ = run("benchmark", [binary, "bench"], timeout=180, limited=True)
            receipt["measurements"] = measurements(raw)
        receipt["status"] = "PASS_BOUNDED_PROTOTYPE_ONLY"
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        try:
            if "metadata" in locals():
                receipt["source_hashes_after"] = sources(metadata)
            receipt["tool_hashes_after"] = {p: sha(p) for p in receipt.get("tool_hashes_before", {})}
            for category in ("source_hashes", "tool_hashes"):
                if receipt.get(category + "_before") != receipt.get(category + "_after"):
                    raise RuntimeError(category + " drift")
            if "binary" in receipt:
                receipt["binary"]["sha256_after"] = sha(receipt["binary"]["path"])
                if receipt["binary"]["sha256_before"] != receipt["binary"]["sha256_after"]:
                    raise RuntimeError("binary drift")
            if "java_classes" in receipt and receipt["java_classes"] != {p: sha(p) for p in receipt["java_classes"]}:
                raise RuntimeError("Java class drift")
            receipt["isolation_after"] = inspect()
            if receipt["isolation_after"]["status"] != "PASS":
                raise RuntimeError("isolation/production gate drift")
        except Exception as error:
            receipt["status"] = "FAIL"
            receipt["finalization_error"] = type(error).__name__ + ": " + str(error)
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat()
        save()
        print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json"), "sha256": sha(output / "receipt.json")}), flush=True)
    return 0 if receipt["status"].startswith("PASS") else 1


if __name__ == "__main__":
    raise SystemExit(main())
