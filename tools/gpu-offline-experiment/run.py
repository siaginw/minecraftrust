"""Bounded Windows hardware experiment; does not launch or modify a server."""
from __future__ import annotations
import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def oracle(index, rounds, seed):
    value = (index * 2654435761 + 17) & 0xFFFFFFFF
    for r in range(rounds):
        value ^= value >> 16
        value = (value * 0x7FEB352D) & 0xFFFFFFFF
        value ^= value >> 15
        value = (value * 0x846CA68B) & 0xFFFFFFFF
        value ^= value >> 16
        value = (value + seed + r) & 0xFFFFFFFF
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vs-root", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("this isolated adapter requires Windows Direct3D 11")
    output = ROOT / "target/gpu-offline-experiment" / uuid.uuid4().hex
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(schema="H22_5_GPU_OFFLINE_V1", status="INCOMPLETE", scope="BOUNDED_SYNTHETIC_INTEGER_MIXING_ONLY",
                   production_authority=False, server_started=False, processes=[], platform=platform.platform(),
                   logical_processors=os.cpu_count(), original_before=inspect())
    sources = [HERE / name for name in ("main.cpp", "kernel.hlsl", "run.py", "PROVENANCE.json", "hash-prospector-UNLICENSE")]
    sources += [ROOT / "tools/testing/hardening_guard.py", ROOT / "machine/architecture-hardening/isolation.json"]
    receipt["sources_before"] = {str(p): sha(p) for p in sources}
    env = dict(os.environ)
    removed = ("CL", "_CL_", "LINK", "_LINK_")
    for key in removed:
        env.pop(key, None)
    receipt["environment_policy"] = {"removed_compiler_overrides": removed}
    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    def run(name, command, timeout=60, required=True):
        start = time.perf_counter()
        timed_out = False
        with (output/(name+".stdout")).open("wb") as stdout, (output/(name+".stderr")).open("wb") as stderr:
            child = subprocess.Popen(command, cwd=output, shell=False, stdout=stdout, stderr=stderr, env=env)
            try:
                child.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                # Stop only this experiment's process tree, including build cmd descendants.
                kill = subprocess.run([str(Path(os.environ["SystemRoot"])/"System32/taskkill.exe"), "/PID", str(child.pid), "/T", "/F"], capture_output=True, timeout=5)
                (output/(name+".timeout-kill.stdout")).write_bytes(kill.stdout)
                (output/(name+".timeout-kill.stderr")).write_bytes(kill.stderr)
                child.wait(timeout=5)
            result = subprocess.CompletedProcess(command, child.returncode)
        result.stdout = (output/(name+".stdout")).read_bytes()
        result.stderr = (output/(name+".stderr")).read_bytes()
        receipt["processes"].append(dict(name=name, argv=list(map(str, command)), exit=result.returncode,
            timed_out=timed_out, deadline_seconds=timeout, seconds=time.perf_counter()-start,
            stdout_sha256=sha(output/(name+".stdout")), stderr_sha256=sha(output/(name+".stderr"))))
        save()
        if timed_out:
            raise RuntimeError(name + " deadline exceeded; partial outputs retained")
        if required and result.returncode:
            raise RuntimeError(name + " failed; raw outputs retained")
        return result
    try:
        if receipt["original_before"]["status"] != "PASS":
            raise RuntimeError("isolation guard failed")
        vs = args.vs_root.resolve(strict=True)
        compiler_dirs = sorted((vs / "VC/Tools/MSVC").glob("*/bin/Hostx64/x64"),
                               key=lambda p: tuple(map(int, p.parents[2].name.split("."))))
        compiler = compiler_dirs[-1] / "cl.exe"
        cmd = Path(os.environ["SystemRoot"]) / "System32/cmd.exe"
        libraries = Path(os.environ["SystemRoot"]) / "System32"
        tool_files = [compiler, compiler.with_name("c1xx.dll"), compiler.with_name("c2.dll"), compiler.with_name("link.exe"),
                      libraries/"d3dcompiler_47.dll", libraries/"d3d11.dll", libraries/"dxgi.dll", Path(sys.executable),
                      vs/"Common7/Tools/VsDevCmd.bat", cmd, libraries/"taskkill.exe"]
        if (libraries/"nvidia-smi.exe").is_file():
            tool_files.append(libraries/"nvidia-smi.exe")
        receipt["tools_before"] = {str(p):sha(p) for p in tool_files}
        avx2 = bool(ctypes.windll.kernel32.IsProcessorFeaturePresent(40))
        receipt["cpu_avx2_flag"] = avx2
        arch = "/arch:AVX2 " if avx2 else ""
        def quoted(path):
            text = str(path)
            if any(c in text for c in '"&|<>%!\r\n'):
                raise ValueError("unsupported command path characters")
            return '"' + text + '"'
        command = "\n".join(["@echo off", "call " + quoted(vs / "Common7/Tools/VsDevCmd.bat") + " -arch=x64 -host_arch=x64 >nul",
            "if errorlevel 1 exit /b %errorlevel%", "where cl", quoted(compiler) + " /nologo /Bv /std:c++17 /O2 " + arch +
            "/EHsc /W4 /WX /DNDEBUG " + quoted(HERE / "main.cpp") + " /Fo:" + quoted(output / "main.obj") +
            " /Fe:" + quoted(output / "probe.exe") + " /link d3d11.lib dxgi.lib d3dcompiler.lib", "exit /b %errorlevel%", ""])
        batch = output / "build.cmd"; batch.write_text(command)
        receipt["build_script_sha256"] = sha(batch)
        run("build", [str(cmd), "/d", "/c", str(batch)], timeout=60)
        executable = output / "probe.exe"; receipt["executable_sha256"] = sha(executable)
        smi = libraries / "nvidia-smi.exe"
        if smi.is_file():
            run("gpu-load-before", [str(smi), "--query-gpu=name,driver_version,memory.total,utilization.gpu", "--format=csv,noheader"], required=False)
        result = run("experiment", [str(executable), str(HERE / "kernel.hlsl")], timeout=45)
        if result.stderr:
            raise RuntimeError("unexpected experiment diagnostic output")
        rows = [json.loads(line) for line in result.stdout.decode("utf-8").splitlines()]
        if len(rows) != 91 or rows[0]["kind"] != "device":
            raise RuntimeError("incorrect result count/schema")
        receipt["device"] = rows[0]
        samples = rows[1:]
        seen = set()
        for row in samples:
            key = row["count"],row["rounds"],row["repeat"]
            if key in seen or row["count"] not in (4096,65536,1048576) or row["rounds"] not in (0,1,64) or not -1<=row["repeat"]<9:
                raise RuntimeError("duplicate or invalid experiment case")
            seen.add(key)
            if row["kind"] != "sample" or row["seed"] != 0x9E3779B9+row["repeat"]+1 or row["golden_indices"] != [0,1,31,row["count"]-1] or row["logical_array_bytes_each_direction"] != row["count"]*4 or row["parameter_upload_bytes"] != 16:
                raise RuntimeError("sample identity/schema mismatch")
            if row["parity"] is not True or row["golden_values"] != [oracle(i,row["rounds"],row["seed"]) for i in row["golden_indices"]]:
                raise RuntimeError("independent Python integer oracle divergence")
            for field in ("scalar_ms","simd_ms","pool_ms","gpu_whole_ms","gpu_kernel_ms","gpu_wrapper_ms","buffers_ms","pool_setup_ms"):
                if not math.isfinite(row[field]) or row[field]<0:
                    raise RuntimeError("invalid timing sample")
        receipt["samples"] = samples
        summaries = []
        for count in (4096,65536,1048576):
            for rounds in (0,1,64):
                group = [s for s in samples if s["count"]==count and s["rounds"]==rounds and s["repeat"]>=0]
                med = {field: statistics.median(s[field] for s in group) for field in ("scalar_ms","simd_ms","pool_ms","gpu_whole_ms","gpu_kernel_ms","gpu_wrapper_ms")}
                best_cpu = min(med["scalar_ms"],med["simd_ms"],med["pool_ms"])
                summaries.append(dict(count=count,rounds=rounds,samples=len(group),median_ms=med,
                    max_ms={field:max(s[field] for s in group) for field in med},
                    best_cpu_over_gpu_ratio=best_cpu/med["gpu_whole_ms"],
                    non_kernel_fraction=1-med["gpu_kernel_ms"]/med["gpu_whole_ms"],
                    decision="PARK_GPU_FOR_THIS_WORKLOAD" if med["gpu_whole_ms"]>=best_cpu else (
                        "NEAR_UNITY_INCONCLUSIVE" if best_cpu/med["gpu_whole_ms"]<1.1 else "SYNTHETIC_MEDIAN_ADVANTAGE_UNQUALIFIED")))
        receipt["summary"] = summaries
        original_shader = (HERE / "kernel.hlsl").read_text()
        needle = "Output[id.x] = value;"
        if original_shader.count(needle) != 1:
            raise RuntimeError("fault injection anchor changed")
        faulty_shader = output / "fault-output.hlsl"
        faulty_shader.write_text(original_shader.replace(needle, "Output[id.x] = value + (id.x == 0 ? 1 : 0);"))
        negative = run("fault-control", [str(executable), str(faulty_shader)], timeout=45, required=False)
        if negative.returncode != 1 or b"FIRST DIVERGENCE count=4096 rounds=0 repeat=-1 index=0 input=17 scalar=17 pool=17 gpu=18" not in negative.stderr:
            raise RuntimeError("GPU output fault was not detected at the first differing element")
        receipt["fault_control"] = dict(status="EXPECTED_FIRST_DIVERGENCE", shader_sha256=sha(faulty_shader))
        if smi.is_file():
            run("gpu-load-after", [str(smi), "--query-gpu=name,driver_version,memory.total,utilization.gpu", "--format=csv,noheader"], required=False)
        receipt["status"] = "PASS_EXPLORATORY_ONLY"
        receipt["integration_decision"] = "PARK_GPU_AFTER_BOUNDED_PROXY"
        receipt["decision_reason"] = "Cheap workloads lose, non-kernel costs dominate warm time, cold setup and latency variation prevent live acceptance; heavy synthetic gain is not a qualified offline Minecraft kernel"
        receipt["limitations"] = ["Synthetic mixing intensity is not Minecraft generation, lighting, or a complete map index",
            "Host/GPU load and CPU affinity are uncontrolled; timings are exploratory, not performance qualification",
            "Non-kernel fraction includes driver/submission/synchronization and CPU memcpy, not just PCIe transfer",
            "GPU whole time includes upload, dispatch, completion wait, readback and copy; device/shader/buffer initialization is separately reported",
            "GPU wrapper time additionally includes timestamp-query collection; array byte counts exclude parameter upload and unknown driver traffic",
            "Scalar, explicit AVX2 when available, and persistent SIMD CPU pool baselines are checked; no live-server work is enabled",
            "Best CPU is best tested median: fixed scalar/SIMD/pool ordering, warm caches and eight-worker cap are not optimized-machine qualification",
            "Tool hashes do not close over SDK headers/import libraries, transitive VsDevCmd scripts, firmware or full graphics driver binaries",
            "Driver/system API calls are bounded externally by a 45-second child deadline; no software GPU fallback"]
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        receipt["sources_after"] = {str(p):sha(p) for p in sources}
        receipt["tools_after"] = {p:sha(p) for p in receipt.get("tools_before",{})}
        receipt["build_script_unchanged"] = "batch" not in locals() or receipt.get("build_script_sha256") == sha(batch)
        receipt["original_after"] = inspect()
        if receipt["sources_before"] != receipt["sources_after"] or receipt.get("tools_before",{}) != receipt["tools_after"] or receipt["original_after"]["status"] != "PASS" or not receipt["build_script_unchanged"]:
            receipt["status"] = "FAIL"; receipt["drift"] = True
        save()
    print(json.dumps(dict(status=receipt["status"], receipt=str(output/"receipt.json"), sha256=sha(output/"receipt.json"))))
    return int(receipt["status"] == "FAIL")


if __name__ == "__main__":
    raise SystemExit(main())
