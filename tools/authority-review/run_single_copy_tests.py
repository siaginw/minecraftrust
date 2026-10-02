#!/usr/bin/env python3
"""Compiles and runs the offline single-copy safety and lifetime suite.

Classpath: the SRG study jar (vanilla wire classes + shaded Netty) plus the
campaign jar (bridge + coremod classes). The release DLL is refreshed to the
repo root first so the bridge binds the CURRENT native build.
"""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
DLL = ROOT / "target" / "release" / "rustcraft_ffi.dll"
BUILD = ROOT / "target" / "authority-review" / "single-copy-tests"
TEST_SRC = ROOT / "tools" / "authority-review" / "SingleCopySafetyAndLifetimeTest.java"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    for dep, name in ((SRG, "SRG jar"), (CAMPAIGN, "campaign jar"), (DLL, "release DLL")):
        if not dep.is_file():
            print(f"[ERROR] {name} missing: {dep}")
            return 1

    # Refresh the root DLL the bridge falls back to.
    root_dll = ROOT / "rustcraft_ffi.dll"
    if not root_dll.exists() or sha256(root_dll) != sha256(DLL):
        shutil.copyfile(DLL, root_dll)
        print("[setup] refreshed repo-root rustcraft_ffi.dll")

    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)

    cp = f"{CAMPAIGN};{SRG}"
    compile_cmd = [
        str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-nowarn", "-cp", cp, "-d", str(BUILD), str(TEST_SRC),
    ]
    result = subprocess.run(compile_cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print("JAVAC ERROR:\n" + result.stderr[-6000:])
        return 1

    run_cmd = [
        str(JAVA), "-Xmx2G",
        "-cp", f"{BUILD};{cp}",
        "com.rustcraft.bridge.SingleCopySafetyAndLifetimeTest",
    ]
    result = subprocess.run(run_cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=3600)
    print(result.stdout[-20000:])
    if result.returncode != 0:
        print("STDERR:\n" + result.stderr[-8000:])
        print("SINGLE_COPY_TESTS_FAILED")
        return 1
    print("SINGLE_COPY_TESTS_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
