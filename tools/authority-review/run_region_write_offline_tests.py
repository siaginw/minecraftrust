#!/usr/bin/env python3
"""RUST_REGION_WRITE_AUTHORITY offline LIVE suite.

Runs RegionWriteLiveHarness in both modes against a real Gate A region file:
  SHADOW          Rust mirror-writes while vanilla writes the real file;
                  payloads compared on both sides.
  ON_EXPERIMENTAL Rust writes the real file (vanilla body skipped);
                  in-session coherence + fresh-vanilla re-read + rewrite
                  generation-ticket path all verified.
Every run leaves artifacts under target/authority-review/region-write/.
"""
from __future__ import annotations

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
SRC = ROOT / "tools" / "authority-review" / "RegionWriteLiveHarness.java"
SRC_REGION = ROOT / "target" / "authority-smoke" / "runtimeA" / "world" / "region" / "r.0.0.mca"
WORK = ROOT / "target" / "authority-review" / "region-write"
RT_A = ROOT / "target" / "authority-smoke" / "runtimeA"
LW = RT_A / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
ASM = RT_A / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" / "asm-debug-all-5.2.jar"


def run_mode(mode: str, count: int, cap: int | None) -> int:
    mode_dir = WORK / mode.lower() if cap is None else WORK / f"{mode.lower()}-cap{cap}"
    if mode_dir.exists():
        shutil.rmtree(mode_dir)
    work_dir = mode_dir / "world" / "region"
    mirror_dir = mode_dir / "mirror"
    work_dir.mkdir(parents=True)
    mirror_dir.mkdir(parents=True)

    cmd = [str(JAVA), "-Xmx2G",
           "-Djava.library.path=" + str(ROOT / "target" / "release"),
           "-Drustcraft.regionWriteExperiment=true",
           f"-Drustcraft.regionWriteMode={mode}",
           "-Drustcraft.regionWriteMirror=" + str(mirror_dir),
           "-cp", f"{WORK / 'classes'};{CAMPAIGN};{SRG};{LW};{ASM}",
           "RegionWriteLiveHarness", mode, str(SRG), str(SRC_REGION),
           str(work_dir), str(mirror_dir), str(count)]
    if cap is not None:
        cmd.insert(1, f"-Drustcraft.regionWriteCap={cap}")
    r = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=600)
    print(f"--- {mode}" + (f" cap={cap}" if cap else "") + " ---")
    print("\n".join(line for line in r.stdout.splitlines() if "[harness]" in line
                    or "REGION_WRITE" in line or "metrics" in line))
    if r.returncode != 0:
        print("HARNESS STDOUT TAIL:\n" + r.stdout[-3000:])
        print("HARNESS STDERR:\n" + r.stderr[-3000:])
        return 1
    return 0


def main() -> int:
    for dep, name in ((CAMPAIGN, "campaign jar"), (DLL, "release DLL"),
                      (SRC_REGION, "source region file")):
        if not dep.is_file():
            print(f"[ERROR] {name} missing: {dep}")
            return 1
    classes = WORK / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-nowarn", "-cp", f"{CAMPAIGN};{SRG};{LW};{ASM}", "-d", str(classes), str(SRC)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-5000:])
        return 1

    failures = 0
    if run_mode("SHADOW", count=24, cap=None):
        failures += 1
    if run_mode("ON_EXPERIMENTAL", count=24, cap=None):
        failures += 1
    # capped run: after the cap, writes must silently fall back to vanilla and
    # the file must stay consistent (the harness verifies final readability)
    if run_mode("ON_EXPERIMENTAL", count=24, cap=10):
        failures += 1

    if failures:
        print(f"REGION_WRITE_OFFLINE_FAILED ({failures} failing runs)")
        return 1
    print("REGION_WRITE_OFFLINE_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
