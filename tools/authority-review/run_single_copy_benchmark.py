#!/usr/bin/env python3
"""Compiles and runs the live single-copy benchmark (A legacy / B b3df84c pooled /
C new live single-copy) on honestly-sized corpus chunks."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
BUILD = ROOT / "target" / "authority-review" / "single-copy-tests"  # shared with the suite
SRC = ROOT / "tools" / "authority-review" / "SingleCopyLiveBenchmark.java"


def main() -> int:
    cp = f"{CAMPAIGN};{SRG}"
    result = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", cp, "-d", str(BUILD), str(SRC)],
        capture_output=True, text=True)
    if result.returncode != 0:
        print("JAVAC ERROR:\n" + result.stderr[-6000:])
        return 1
    result = subprocess.run(
        [str(JAVA), "-Xmx2G", "-cp", f"{BUILD};{cp}",
         "com.rustcraft.authority.SingleCopyLiveBenchmark"],
        cwd=str(ROOT), capture_output=True, text=True, timeout=3600)
    print(result.stdout[-30000:])
    if result.returncode != 0:
        print("STDERR:\n" + result.stderr[-4000:])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
