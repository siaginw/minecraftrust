"""Fresh source-bound checks for the non-authorizing H6 retained-state prototype."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
CRATE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources() -> dict:
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", Path(__file__),
             ROOT / "docs/architecture/retained-native-state.md", ROOT / "tools/testing/hardening_guard.py",
             ROOT / "machine/architecture-hardening/isolation.json"]
    for name in ("native-state-vnext", "rustcraft-core", "native-capability"):
        crate = ROOT / "crates" / name
        paths.append(crate / "Cargo.toml")
        paths.extend(crate.rglob("*.rs"))
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(set(paths))}


def expected_checksum(cardinality: int) -> int:
    # Independent Python dense reference, not a second invocation of the probe.
    values = [70000 + i % cardinality for i in range(4096)]
    seed = 0x746573746866
    for index in range(50000):
        seed ^= (seed << 13) & ((1 << 64) - 1)
        seed ^= seed >> 7
        seed ^= (seed << 17) & ((1 << 64) - 1)
        values[seed % 4096] = 70000 + ((index * 31 + 1) % cardinality)
    return sum((i + 1) * value for i, value in enumerate(values))


def validate_probe(probe: dict) -> dict:
    fixed = {"schema": "H6_LAYOUT_PROBE_V1", "scope": "SYNTHETIC_SINGLE_THREAD",
             "production_authority": False, "samples": 5, "warmup_samples": 1,
             "reads_per_sample": 200000, "writes_per_sample": 50000,
             "canonicalizations_per_sample": 32,
             "baseline": "independent_u32_Vec_not_legacy_native_chunk",
             "memory_scope": "vector_payload_excludes_allocator_and_hash_buckets",
             "packing_scope": "canonical_local_indices_only_no_wire_packet"}
    if set(probe) != set(fixed) | {"rows", "transitions"}:
        raise ValueError("unexpected probe root fields")
    if any(type(probe[key]) is not type(value) or probe[key] != value for key, value in fixed.items()):
        raise ValueError("unexpected probe constants")
    expected = {(p, line, count) for p in (64, 128, 512) for line in (8, 32)
                for count in (1, 4, 16, 64, 128, 512, 2048)}
    samples = ("dense_read_ns", "layout_read_ns", "dense_write_ns", "layout_write_ns", "canonical_index_ns")
    scalar_keys = {"promote_unique", "linear_lookup_max", "cardinality", "state_bytes", "index_bytes",
                   "count_bytes", "lookup_capacity_entries", "linear_lookups", "hashed_lookups", "final_checksum"}
    allowed = scalar_keys | set(samples) | {"median_" + key for key in samples} | {"layout", "dense_match"}
    seen = set()
    for row in probe["rows"]:
        if set(row) != allowed or row["dense_match"] is not True:
            raise ValueError("unexpected row schema or failed dense match")
        if any(type(row[key]) is not int or row[key] < 0 for key in scalar_keys):
            raise ValueError("invalid unsigned scalar")
        key = row["promote_unique"], row["linear_lookup_max"], row["cardinality"]
        if key not in expected or key in seen:
            raise ValueError("unexpected/duplicate policy row")
        seen.add(key)
        count = row["cardinality"]
        layout = "Uniform" if count == 1 else "DenseHot" if count >= row["promote_unique"] else "LocalPalette"
        if row["layout"] != layout or row["final_checksum"] != expected_checksum(count):
            raise ValueError("layout/checksum differs from independent expected result")
        for name in samples:
            values = row[name]
            if (not isinstance(values, list) or len(values) != 5
                    or any(type(value) is not int or value <= 0 for value in values)
                    or type(row["median_" + name]) is not int
                    or row["median_" + name] != statistics.median(values)):
                raise ValueError("invalid timing samples/median")
        if layout == "Uniform" and (row["state_bytes"], row["index_bytes"], row["count_bytes"]) != (4, 0, 0):
            raise ValueError("unexpected uniform payload")
        if layout == "DenseHot" and (row["state_bytes"], row["index_bytes"], row["count_bytes"]) != (16384, 0, 0):
            raise ValueError("unexpected dense payload")
        if layout == "LocalPalette" and (row["state_bytes"] < count * 4 or row["index_bytes"] != 8192):
            raise ValueError("unexpected local palette payload")
    if seen != expected:
        raise ValueError("missing policy rows")
    transitions = probe["transitions"]
    if not isinstance(transitions, list) or len(transitions) != 3:
        raise ValueError("missing transition controls")
    for threshold, row in zip((64, 512, 2048), transitions):
        if (set(row) != {"hot_write_threshold", "promotion_ns", "three_sweeps_demotion_ns", "promotions", "demotions"}
                or any(type(value) is not int or value <= 0 for value in row.values())
                or row["hot_write_threshold"] != threshold or row["promotions"] != 1 or row["demotions"] != 1):
            raise ValueError("invalid transition control")
    return {"policy_rows": 42, "transition_controls": 3, "samples_per_case": 5,
            "independent_python_checksum_matches": 42, "server_or_packet_performance_claim": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = (args.output or ROOT / "target/architecture-hardening" /
              ("h6-retained-state-" + uuid.uuid4().hex[:12])).resolve()
    if not output.is_relative_to((ROOT / "target").resolve()):
        parser.error("output must remain in this isolated checkout's target directory")
    output.mkdir(parents=True, exist_ok=False)
    cargo = args.cargo.resolve(strict=True)
    env = dict(os.environ)
    removed = [key for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTDOCFLAGS", "RUSTC",
                              "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "CARGO_BUILD_RUSTC")
               if env.pop(key, None) is not None]
    receipt = {"schema": "H6_RETAINED_STATE_CHECK_V1", "milestone": "H6_FOUNDATION",
               "scope": "SERIALIZED_MODEL_AND_SYNTHETIC_LAYOUT_ONLY", "runtime_integration": False,
               "production_authority": False, "started_utc": datetime.now(timezone.utc).isoformat(),
               "source_hashes_before": sources(), "cargo": {"path": str(cargo), "sha256": digest(cargo)},
               "python": {"path": sys.executable, "sha256": digest(Path(sys.executable)), "version": sys.version},
               "host": {"platform": platform.platform(), "machine": platform.machine(),
                        "processor_hint": os.environ.get("PROCESSOR_IDENTIFIER"),
                        "logical_cpu_count": os.cpu_count(), "exclusive_host": False},
               "sanitized_environment_keys": removed, "processes": []}

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, argv):
        argv = list(map(str, argv))
        start = time.monotonic()
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
        stdout, stderr = output / (name + ".stdout"), output / (name + ".stderr")
        stdout.write_bytes(result.stdout)
        stderr.write_bytes(result.stderr)
        receipt["processes"].append({"name": name, "argv": argv, "returncode": result.returncode,
            "seconds": time.monotonic() - start, "stdout_sha256": digest(stdout), "stderr_sha256": digest(stderr),
            "stdout_bytes": len(result.stdout), "stderr_bytes": len(result.stderr)})
        save()
        if result.returncode:
            raise RuntimeError(name + " returned " + str(result.returncode))
        return result.stdout.decode("utf-8", errors="strict")

    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":
            raise RuntimeError("isolation or production gate guard failed")
        receipt["cargo_version"] = run("cargo-version", [cargo, "--version", "--verbose"])
        rustup = cargo.with_name("rustup.exe" if os.name == "nt" else "rustup")
        receipt["toolchain_executables"] = {}
        for tool in ("cargo", "rustc", "rustfmt", "cargo-clippy", "clippy-driver"):
            path = Path(run("resolve-" + tool, [rustup, "which", tool]).strip()).resolve(strict=True)
            receipt["toolchain_executables"][tool] = {"path": str(path), "sha256": digest(path)}
        receipt["rustc_version"] = run("rustc-version", [receipt["toolchain_executables"]["rustc"]["path"], "--version", "--verbose"])
        common = ["--locked", "--offline", "--manifest-path", CRATE / "Cargo.toml",
                  "-p", "native-state-vnext", "--target-dir", output / "build"]
        tests = run("tests", [cargo, "test", *common])
        summaries = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", tests)
        if summaries != [("4", "0", "0", "0", "0"), ("7", "0", "0", "0", "0"),
                         ("27", "0", "0", "0", "0"), ("0", "0", "0", "0", "0")]:
            raise RuntimeError("unexpected test counts/schema: " + repr(summaries))
        receipt["test_counts"] = {"unit": 4, "layout": 7, "lifecycle": 27, "total": 38}
        run("clippy", [cargo, "clippy", *common, "--all-targets", "--", "-D", "warnings"])
        run("format", [cargo, "fmt", "--manifest-path", CRATE / "Cargo.toml", "-p", "native-state-vnext", "--", "--check"])
        run("probe-build", [cargo, "build", *common, "--release", "--example", "layout_probe"])
        probe_exe = output / "build/release/examples" / ("layout_probe.exe" if os.name == "nt" else "layout_probe")
        receipt["probe_executable"] = {"path": str(probe_exe), "sha256": digest(probe_exe)}
        probe = json.loads(run("probe", [probe_exe]))
        receipt["probe_checks"] = validate_probe(probe)
        receipt["probe_results"] = probe
        receipt["status"] = "PASS"
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        receipt["source_hashes_after"] = sources()
        receipt["isolation_after"] = inspect()
        tools_after = {name: digest(Path(record["path"])) for name, record in receipt.get("toolchain_executables", {}).items()}
        receipt["tool_hashes_after"] = tools_after
        if (receipt["source_hashes_before"] != receipt["source_hashes_after"]
                or receipt["cargo"]["sha256"] != digest(cargo)
                or receipt["python"]["sha256"] != digest(Path(sys.executable))
                or any(receipt["toolchain_executables"][name]["sha256"] != value for name, value in tools_after.items())
                or receipt["isolation_after"]["status"] != "PASS"):
            receipt["status"] = "FAIL"
            receipt["source_tool_or_isolation_drift"] = True
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat()
        save()
    print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json")}))
    return int(receipt["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
