"""Repeat the non-authorizing H4 model checks with fresh, source-bound evidence."""
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
CRATE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources() -> dict:
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", Path(__file__),
             ROOT / "docs/architecture/native-capabilities.md", ROOT / "tools/testing/hardening_guard.py",
             ROOT / "tools/testing/rust_property_support.rs"]
    members = tomllib.loads((ROOT / "Cargo.toml").read_text())["workspace"]["members"]
    for member in members:
        if any(token in member for token in ("*", "?", "[")):
            raise ValueError("workspace globs require Cargo metadata resolution")
        paths.extend((ROOT / member).rglob("*.rs"))
        paths.append(ROOT / member / "Cargo.toml")
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(paths)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--workspace-regressions", action="store_true")
    args = parser.parse_args()
    output = (args.output or ROOT / "target/architecture-hardening" /
              ("h4-capabilities-" + uuid.uuid4().hex[:12])).resolve()
    if not output.is_relative_to((ROOT / "target").resolve()):
        parser.error("output must be inside this checkout's target directory")
    output.mkdir(parents=True, exist_ok=False)
    cargo = args.cargo.resolve(strict=True)
    env = dict(os.environ)
    removed = [name for name in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTDOCFLAGS") if env.pop(name, None) is not None]
    receipt = {"schema": "H4_CAPABILITY_MODEL_CHECK_V1", "scope": "DETERMINISTIC_MODEL_ONLY",
               "milestone": "H4_FOUNDATION", "runtime_integration": False,
               "workspace_regressions_requested": args.workspace_regressions,
               "strict_lint_scope": "native-capability only; existing workspace debt is not reclassified",
               "production_authority": False, "started_utc": datetime.now(timezone.utc).isoformat(),
               "source_hashes_before": sources(), "cargo": {"path": str(cargo), "sha256": digest(cargo)},
               "sanitized_environment_keys": removed, "processes": []}

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, argv):
        argv = list(map(str, argv))
        start = time.monotonic()
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True)
        stdout, stderr = output / (name + ".stdout"), output / (name + ".stderr")
        stdout.write_bytes(result.stdout); stderr.write_bytes(result.stderr)
        receipt["processes"].append({"name": name, "argv": argv, "returncode": result.returncode,
            "seconds": time.monotonic() - start, "stdout_sha256": digest(stdout),
            "stderr_sha256": digest(stderr), "stdout_bytes": len(result.stdout), "stderr_bytes": len(result.stderr)})
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
        if not rustup.is_file():
            raise RuntimeError("explicit rustup toolchain resolution unavailable")
        receipt["toolchain_executables"] = {}
        for tool in ("cargo", "rustc", "rustfmt", "cargo-clippy", "clippy-driver"):
            path = Path(run("resolve-" + tool, [rustup, "which", tool]).strip()).resolve(strict=True)
            receipt["toolchain_executables"][tool] = {"path": str(path), "sha256": digest(path)}
        receipt["rustc_version"] = run("rustc-version", [receipt["toolchain_executables"]["rustc"]["path"], "--version", "--verbose"])
        common = ["--locked", "--offline", "--manifest-path", CRATE / "Cargo.toml", "-p", "native-capability",
                  "--target-dir", output / "build"]
        test_output = run("tests", [cargo, "test", *common])
        summaries = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", test_output)
        if summaries != [("2", "0", "0", "0", "0"), ("26", "0", "0", "0", "0"), ("0", "0", "0", "0", "0")]:
            raise RuntimeError("unexpected test assertion count or result schema: " + repr(summaries))
        receipt["tests_passed"] = 28
        receipt["unit_tests"] = 2
        receipt["integration_tests"] = 26
        run("clippy", [cargo, "clippy", *common, "--all-targets", "--", "-D", "warnings"])
        run("format", [cargo, "fmt", "--manifest-path", CRATE / "Cargo.toml", "-p", "native-capability", "--", "--check"])
        if args.workspace_regressions:
            workspace_output = run("workspace-lib", [cargo, "test", "--workspace", "--lib", "--locked", "--offline",
                                                      "--target-dir", output / "build"])
            summaries = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", workspace_output)
            if not summaries or any(tuple(row[1:]) != ("0", "0", "0", "0") for row in summaries):
                raise RuntimeError("missing or nonpassing workspace unit test results")
            receipt["workspace_lib_tests"] = sum(int(row[0]) for row in summaries)
            receipt["workspace_lib_suites"] = len(summaries)
            run("release-ffi", [cargo, "build", "--release", "--locked", "--offline", "-p", "ffi",
                                "--target-dir", output / "build"])
            library = output / "build/release" / ("rustcraft_ffi.dll" if os.name == "nt" else "librustcraft_ffi.so")
            receipt["release_ffi"] = {"path": str(library), "sha256": digest(library), "bytes": library.stat().st_size}
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
