#!/usr/bin/env python3
"""RUST_REGION_READ_AUTHORITY offline LIVE suite (goals §15-§18).

Runs RegionReadLiveHarness in SHADOW and ON_EXPERIMENTAL modes against a
real Gate A region copy through the REAL transformed read seam:
  - every existing chunk read through Rust (ON) or the tee-wrapped vanilla
    stream (SHADOW), parsed by the REAL CompressedStreamTools;
  - write->read and rewrite->read coherency on the same RegionFile;
  - a truncated record must fail closed with 0 partial streams.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
SRC_REGION = ROOT / "target" / "authority-smoke" / "runtimeA" / "world" / "region" / "r.0.0.mca"
WORK = ROOT / "target" / "authority-review" / "region-read"
RT_A = ROOT / "target" / "authority-smoke" / "runtimeA"
LW = RT_A / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
ASM = RT_A / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" / "asm-debug-all-5.2.jar"


def run_mode(mode: str, count: int) -> int:
    mode_dir = WORK / mode.lower()
    if mode_dir.exists():
        shutil.rmtree(mode_dir)
    work_dir = mode_dir / "world" / "region"
    work_dir.mkdir(parents=True)
    cmd = [str(JAVA), "-Xmx2G",
           "-Djava.library.path=" + str(ROOT / "target" / "release"),
           "-Drustcraft.regionReadExperiment=true",
           f"-Drustcraft.regionReadMode={mode}",
           "-cp", f"{WORK / 'classes'};{CAMPAIGN};{SRG};{LW};{ASM}",
           "RegionReadLiveHarness", mode, str(SRG), str(work_dir), str(count),
           str(SRC_REGION)]
    r = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=900)
    print("\n".join(line for line in r.stdout.splitlines()
                    if "[harness]" in line or "REGION_READ" in line))
    if r.returncode != 0:
        print("STDOUT TAIL:\n" + r.stdout[-2500:])
        print("STDERR:\n" + r.stderr[-2500:])
        return 1
    return 0


def main() -> int:
    classes = WORK / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    for dep, name in ((CAMPAIGN, "campaign jar"),
                      (ROOT / "target" / "release" / "rustcraft_ffi.dll", "DLL"),
                      (SRC_REGION, "source region")):
        if not dep.is_file():
            print(f"[ERROR] {name} missing: {dep}")
            return 1
    failures = 0
    if run_mode("SHADOW", 24):
        failures += 1
    if run_mode("ON_EXPERIMENTAL", 24):
        failures += 1
    if failures:
        print(f"REGION_READ_OFFLINE_FAILED ({failures})")
        return 1
    print("REGION_READ_OFFLINE_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
