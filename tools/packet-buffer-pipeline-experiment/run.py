"""H14 source-bound private-loopback correctness and optional measurement campaign."""
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
import tarfile
import time
import tomllib
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect
sys.path.insert(0, str(ROOT / "tools/nbt-region-experiment"))
from job import LaunchFailure, launch

FIELDS = ("shared slow quantum wall_ns retain_copied shared_hits admission_pauses body_peak_bytes "
          "reservation_peak_jobs reservation_peak_bytes compressed_input compressed_output passthrough_copied "
          "initialized_output header_generated header_copied encrypted written writes socket_short_writes "
          "would_block compression_ns receiver_copied receiver_reads").split()

def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def sources(metadata):
    paths = [p for p in HERE.rglob("*") if p.is_file() and not {"target", "__pycache__"}.intersection(p.relative_to(HERE).parts)]
    for package in metadata["packages"]:
        if package["source"] is None:
            base = Path(package["manifest_path"]).parent
            if not base.is_relative_to(ROOT):
                raise RuntimeError("local dependency outside isolated checkout")
            paths.append(Path(package["manifest_path"]))
            paths += [p for p in (base / "src").rglob("*") if p.is_file()]
            if (base / "build.rs").exists(): paths.append(base / "build.rs")
    paths += [ROOT / p for p in ("Cargo.toml", "Cargo.lock", "tools/testing/hardening_guard.py",
        "machine/architecture-hardening/isolation.json", "tools/nbt-region-experiment/job.py", "tools/nbt-region-experiment/test_job.py",
        "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java", "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java")]
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}

def dependencies(metadata):
    pins = {(p["name"], p["version"]): p.get("checksum") for p in tomllib.loads((HERE / "Cargo.lock").read_text())["package"]}
    records = {}
    for package in metadata["packages"]:
        if package["source"] is None: continue
        directory = Path(package["manifest_path"]).parent
        archive = directory.parents[2] / "cache" / directory.parent.name / (directory.name + ".crate")
        if sha(archive) != pins[(package["name"], package["version"])]: raise RuntimeError("crate checksum mismatch")
        files = {}
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                if not member.isfile(): continue
                prefix = directory.name + "/"
                if not member.name.startswith(prefix): raise RuntimeError("crate root")
                relative = member.name[len(prefix):]
                if Path(relative).is_absolute() or ".." in Path(relative).parts: raise RuntimeError("crate path")
                digest = hashlib.sha256(tar.extractfile(member).read()).hexdigest()
                if sha(directory / relative) != digest: raise RuntimeError("changed dependency file " + relative)
                files[relative] = digest
        records[directory.name] = {"archive_sha256": sha(archive), "files": len(files), "manifest_sha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), "license": package["license"]}
    root_lock = tomllib.loads((ROOT / "Cargo.lock").read_text())["package"]
    for name in ("flate2", "zlib-rs"):
        pin = next(p for p in root_lock if p["name"] == name)
        if pins[(name, pin["version"])] != pin["checksum"]: raise RuntimeError("production compression pin changed")
    return records

def parse_pipeline(raw):
    result, wires, allocations = {}, {}, {}
    for line in raw.splitlines():
        fields = line.split("\t")
        if fields[0] == "pipeline":
            if len(fields) != len(FIELDS) + 1 or result: raise ValueError("pipeline row schema")
            result = dict(zip(FIELDS, map(int, fields[1:])))
        elif fields[0] == "wire" and len(fields) == 4:
            if fields[1] in wires: raise ValueError("duplicate wire")
            wires[fields[1]] = {"bytes": int(fields[2]), "fnv64": int(fields[3])}
        elif fields[0] == "allocation" and len(fields) == 6:
            allocations[fields[1]] = dict(zip(("allocations", "requested_bytes", "deallocations", "freed_bytes"), map(int, fields[2:])))
        elif fields[0] in ("encryption_ns", "retained_generated") and len(fields) == 2:
            result[fields[0]] = int(fields[1])
        else: raise ValueError("unexpected native output " + line)
    if set(wires) != {"0", "1", "2", "3"} or len(allocations) != 8: raise ValueError("output inventory")
    if result["encrypted"] != result["written"] or result["written"] != result["receiver_copied"] or result["written"] != sum(w["bytes"] for w in wires.values()): raise ValueError("wire byte accounting")
    if result["body_peak_bytes"] > 524288 or result["reservation_peak_jobs"] > 16 or result["reservation_peak_bytes"] > 1048576: raise ValueError("retention bounds")
    if result["written"] != 128282: raise ValueError("qualified fixture stream size changed")
    if result["retain_copied"] != (103072 if result["shared"] else 206144): raise ValueError("retained serialization copy accounting")
    result["wires"], result["allocation_traffic"] = wires, allocations
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--measure", action="store_true")
    args = parser.parse_args()
    if ROOT.resolve() != Path(r"D:\minecraftrust-astra-hardening").resolve(): parser.error("isolated checkout only")
    output = ROOT / "target/architecture-hardening" / ("h14-packet-buffer-" + uuid.uuid4().hex[:12])
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"schema_version": 1, "kind": "H14_PACKET_BUFFER_PIPELINE", "status": "INCOMPLETE", "production_authority": False,
               "started_utc": datetime.now(timezone.utc).isoformat(), "measurement_requested": args.measure, "results": [],
               "limits": {"body_payload_bytes": 524288, "job_output_capacity_bytes": 1048576, "jobs_including_retained_outputs": 16,
                          "connections": 4, "workers": 2, "runtime_commit_mib": 512, "runtime_active_processes": 1, "child_output_bytes_per_stream": 1048576},
               "limitations": ["Synthetic packet0x7e payload and fixed public keys; no Minecraft gameplay/authentication/JNI integration.",
                   "Java-valid deflate streams need not be byte-identical; independent AES oracle uses validated native frames.",
                   "Explicit application payload copies and Rust allocator requests are measured; backend/crypto/OS/kernel internal copies remain unknown.",
                   "Payload reservations exclude metadata, retained source snapshots, backend contexts and kernel socket queues; child Job adds an overall commit limit.",
                   "Shared host and instrumented allocator; no zero-copy or general production-throughput claim."]}
    env = dict(os.environ)
    for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"): env.pop(key, None)
    def save(): (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    def run(name, argv, limited=False, timeout=60, negative=None):
        argv = list(map(str, argv)); begin = time.monotonic(); stdout, stderr = output / (name + ".stdout.log"), output / (name + ".stderr.log")
        code, timed, peak, error = None, False, None, None
        if limited:
            try: code, out, err, timed, peak = launch(argv, cwd=ROOT, env=env, memory_mib=512, timeout=timeout)
            except LaunchFailure as failure:
                code, out, err, timed, peak, error = failure.code, failure.stdout, failure.stderr, failure.timed_out, failure.peak, str(failure)
            stdout.write_bytes(out); stderr.write_bytes(err)
        else:
            with stdout.open("wb") as out, stderr.open("wb") as err:
                child = subprocess.Popen(argv, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=out, stderr=err, shell=False)
                try: code = child.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    timed = True
                    subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"], stdout=err, stderr=err, timeout=10, check=False)
                    if child.poll() is None: child.kill()
                    code = child.wait(timeout=10)
        raw, errors = stdout.read_text(encoding="utf-8", errors="replace"), stderr.read_text(encoding="utf-8", errors="replace")
        ok = not timed and error is None and (code == 0 if negative is None else code != 0 and negative in raw + errors)
        row = {"name": name, "argv": argv, "status": "PASS" if ok else "FAIL", "exit_code": code, "timed_out": timed,
               "seconds": time.monotonic() - begin, "job_limited": limited, "peak_process_commit_bytes": peak,
               "stdout": stdout.relative_to(ROOT).as_posix(), "stdout_sha256": sha(stdout), "stderr": stderr.relative_to(ROOT).as_posix(), "stderr_sha256": sha(stderr)}
        if error: row["error"] = error
        if negative: row["expected_failure"] = negative
        receipt["results"].append(row); save(); print(json.dumps(row), flush=True)
        if not ok: raise RuntimeError(name + " failed; raw prefixes retained")
        return raw, errors
    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS": raise RuntimeError("isolation/gate")
        shim = Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"); rustup = shim.parent / "rustup.exe"
        raw, _ = run("resolve-toolchain", [rustup, "which", "rustc"]); toolchain = Path(raw.strip()).parent; cargo = toolchain / "cargo.exe"
        env["PATH"] = str(toolchain) + os.pathsep + env.get("PATH", ""); env["RUSTC"] = str(toolchain / "rustc.exe"); env["RUSTDOC"] = str(toolchain / "rustdoc.exe")
        java_home = Path(r"D:\rustcraft-toolchains\temurin8\jdk8u504-b01"); java, javac = java_home / "bin/java.exe", java_home / "bin/javac.exe"
        tool_paths = [shim, rustup, Path(sys.executable), Path(shutil.which("git")).resolve(), java, javac, java_home / "jre/bin/server/jvm.dll", java_home / "jre/lib/rt.jar", java_home / "lib/tools.jar"]
        tool_paths += [toolchain / n for n in ("cargo.exe", "rustc.exe", "rustdoc.exe", "rustfmt.exe", "cargo-fmt.exe", "cargo-clippy.exe", "clippy-driver.exe")]
        receipt["tool_hashes_before"] = {str(p): sha(p) for p in tool_paths}
        manifest = HERE / "Cargo.toml"; base = ["--locked", "--offline", "--manifest-path", manifest, "--target-dir", output / "cargo"]
        raw, _ = run("metadata", [cargo, "metadata", "--locked", "--offline", "--manifest-path", manifest, "--format-version", "1"]); metadata = json.loads(raw)
        receipt["source_hashes_before"] = sources(metadata); receipt["dependencies_before"] = dependencies(metadata)
        receipt["preserved_development_failures"] = {str(p.relative_to(ROOT)).replace("\\", "/"): sha(p) for folder in ("h14-before-windows-recv-fix", "h14-before-partial-send-loop-fix", "h14-pressure-delay-before", "h14-canceled-hole-before") for p in (ROOT / "target/architecture-hardening" / folder).glob("*.log")}
        run("accepted-backend-warning-source", ["git", "show", "HEAD:crates/compression/src/frame.rs"])
        run("backend-features", [cargo, "tree", "--locked", "--offline", "--manifest-path", manifest, "-e", "features"])
        run("rustc-version", [toolchain / "rustc.exe", "-Vv"])
        _, version = run("java-version", [java, "-Xms16m", "-Xmx128m", "-version"], limited=True)
        if 'version "1.8.' not in version: raise RuntimeError("Java8 required")
        run("format", [cargo, "fmt", "--manifest-path", manifest, "--check"])
        run("clippy", [cargo, "clippy", *base, "--all-targets", "--no-deps", "--", "-D", "warnings"], timeout=120)
        raw, _ = run("rust-contracts", [cargo, "test", *base, "--", "--nocapture", "--test-threads=1"], timeout=120)
        if "14 passed; 0 failed" not in raw: raise RuntimeError("Rust regression inventory")
        run("job-controls", [sys.executable, ROOT / "tools/nbt-region-experiment/test_job.py"], timeout=30)
        raw, _ = run("release-build", [cargo, "build", *base, "--release", "--message-format=json"], timeout=120)
        binaries = [Path(x["executable"]) for line in raw.splitlines() if line.startswith("{") for x in [json.loads(line)] if x.get("reason") == "compiler-artifact" and x.get("executable") and x["target"]["name"] == "packet-buffer-pipeline-experiment"]
        if len(binaries) != 1: raise RuntimeError("binary inventory")
        binary = binaries[0]; receipt["binary"] = {"path": str(binary), "sha256_before": sha(binary)}
        classes = output / "classes"; classes.mkdir()
        run("compile-oracle", [javac, "-J-Xms16m", "-J-Xmx128m", "-source", "8", "-target", "8", "-Xlint:all", "-Werror", "-d", classes, HERE / "PacketPipelineOracle.java"], limited=True)
        oracle = [java, "-Xms16m", "-Xmx128m", "-cp", classes, "PacketPipelineOracle"]
        receipt["correctness"] = []
        golden = None
        for label, shared, slow, quantum in (("shared-fast", 1, 0, 65536), ("shared-slow", 1, 1, 65536), ("unshared-slow", 0, 1, 65536), ("tiny-vectors", 1, 0, 17)):
            directory = output / label
            raw, _ = run(label, [binary, "run", directory, shared, slow, quantum, 1], limited=True, timeout=20)
            parsed = parse_pipeline(raw)
            raw, _ = run(label + "-java", [*oracle, directory], limited=True, timeout=30)
            match = re.search(r"PASS PacketPipelineOracle connections=4 packets=27 encrypted_bytes=128282 exact_jdk_frames=(\d+) native_frame_decode_and_wire_exact=true", raw)
            if not match: raise RuntimeError("Java oracle inventory")
            streams = {p.name: sha(p) for p in directory.glob("*.wire")}
            if golden is None: golden = streams
            elif streams != golden: raise RuntimeError("stream differs across sharing/slow/partial-send paths")
            parsed.update({"case": label, "wire_sha256": streams, "exact_jdk_frames": int(match[1])}); receipt["correctness"].append(parsed)
        reference = output / "shared-fast"
        for name, file, marker in (("cipher-tamper", "connection-0.wire", "FIRST_DIVERGENCE wire connection=0 offset=0"), ("zlib-tamper", "connection-0-packet-2.frame", "COMPRESSION_DIVERGENCE")):
            directory = output / name; shutil.copytree(reference, directory)
            path = directory / file; payload = bytearray(path.read_bytes()); payload[0 if name == "cipher-tamper" else -1] ^= 1; path.write_bytes(payload)
            run(name, [*oracle, directory], limited=True, negative=marker)
        receipt["qualified_wire_sha256"] = golden
        receipt["java_classes"] = {str(p): sha(p) for p in classes.glob("*.class")}
        if args.measure:
            receipt["samples"] = []
            for repeat in range(-1, 5):
                combinations = list(itertools.product((0, 1), (0, 1)))
                if repeat % 2 == 0: combinations.reverse()
                for shared, slow in combinations:
                    label = f"measure-{repeat}-{shared}-{slow}"; directory = output / label
                    raw, _ = run(label, [binary, "run", directory, shared, slow, 65536, 0], limited=True, timeout=20)
                    sample = parse_pipeline(raw)
                    if {p.name: sha(p) for p in directory.glob("*.wire")} != golden: raise RuntimeError("measured stream differs from Java-qualified fixture")
                    sample["repeat"] = repeat; receipt["samples"].append(sample)
            raw, _ = run("bytes-vs-arc", [binary, "handles"], limited=True, timeout=30)
            handles = []
            for line in raw.splitlines():
                f = line.split("\t")
                if len(f) != 7 or f[0] != "handles": raise ValueError("handle schema")
                handles.append(dict(zip(("repeat", "payload_bytes", "arc_ns", "bytes_ns", "arc_allocations", "bytes_allocations"), map(int, f[1:]))))
            if len(handles) != 18 or {(h["repeat"], h["payload_bytes"]) for h in handles} != set(itertools.product(range(-1, 5), (64, 4096, 65536))): raise ValueError("handle inventory")
            receipt["handle_samples"] = handles
            receipt["timing_summary"] = []
            for shared, slow in itertools.product((0, 1), (0, 1)):
                samples = [s for s in receipt["samples"] if s["repeat"] >= 0 and (s["shared"], s["slow"]) == (shared, slow)]
                receipt["timing_summary"].append({"shared": shared, "slow": slow, "samples": len(samples), "wall_ns": {"min": min(s["wall_ns"] for s in samples), "median": statistics.median(s["wall_ns"] for s in samples), "max": max(s["wall_ns"] for s in samples)}})
        receipt["status"] = "PASS_BOUNDED_PROTOTYPE_ONLY"
    except Exception as error:
        receipt["status"] = "FAIL"; receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        try:
            if "metadata" in locals(): receipt["source_hashes_after"] = sources(metadata); receipt["dependencies_after"] = dependencies(metadata)
            receipt["tool_hashes_after"] = {p: sha(p) for p in receipt.get("tool_hashes_before", {})}
            for field in ("source_hashes", "dependencies", "tool_hashes"):
                if receipt.get(field + "_before") != receipt.get(field + "_after"): raise RuntimeError(field + " drift")
            if "binary" in receipt:
                receipt["binary"]["sha256_after"] = sha(receipt["binary"]["path"])
                if receipt["binary"]["sha256_before"] != receipt["binary"]["sha256_after"]: raise RuntimeError("binary drift")
            if "java_classes" in receipt and receipt["java_classes"] != {p: sha(p) for p in receipt["java_classes"]}: raise RuntimeError("Java class drift")
            receipt["isolation_after"] = inspect()
            if receipt["isolation_after"]["status"] != "PASS": raise RuntimeError("isolation/gate drift")
        except Exception as error:
            receipt["status"] = "FAIL"; receipt["finalization_error"] = type(error).__name__ + ": " + str(error)
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat(); save()
        print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json"), "sha256": sha(output / "receipt.json")}), flush=True)
    return 0 if receipt["status"].startswith("PASS") else 1

if __name__ == "__main__": raise SystemExit(main())
