#!/usr/bin/env python3
"""Builds rustcraft-launch-probe.jar: the GENERIC launch-shape probe agent
(LaunchShapeProbeAgent premain). Inert without -Drustcraft.probe.classes."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAR = JDK8 / "bin" / "jar.exe"
OUT = ROOT / "target" / "rustcraft-launch-probe.jar"
BUILD = ROOT / "target" / "launch-probe-build"
SRC = ROOT / ("tools/forge-capture/src/com/rustcraft/offline/agent/"
              "LaunchShapeProbeAgent.java")


def main() -> int:
    if not JAVAC.exists():
        print(f"ERROR: javac not found at {JAVAC}", file=sys.stderr)
        return 1
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-d", str(BUILD), str(SRC)],
        capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr, file=sys.stderr)
        return r.returncode
    meta = BUILD / "META-INF"
    meta.mkdir(exist_ok=True)
    (meta / "MANIFEST.MF").write_text(
        "Manifest-Version: 1.0\n"
        "Premain-Class: com.rustcraft.offline.agent.LaunchShapeProbeAgent\n",
        encoding="utf-8")
    if OUT.exists():
        OUT.unlink()
    r = subprocess.run(
        [str(JAR), "cfm", str(OUT), str(meta / "MANIFEST.MF"),
         "-C", str(BUILD), "."],
        capture_output=True, text=True)
    if r.returncode != 0:
        print("JAR ERROR:\n" + r.stderr, file=sys.stderr)
        return r.returncode
    print(f"[OK] built {OUT} ({OUT.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
