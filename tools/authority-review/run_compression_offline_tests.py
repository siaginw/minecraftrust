#!/usr/bin/env python3
"""Compiles and runs the offline compression-authority suite (real handler +
real vanilla decoder over EmbeddedChannel)."""
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
DLL = ROOT / "target" / "release" / "rustcraft_ffi.dll"
BUILD = ROOT / "target" / "authority-review" / "compression-tests"
SRC = ROOT / "tools" / "authority-review" / "CompressionAuthorityOfflineTest.java"


def main() -> int:
    for dep, name in ((CAMPAIGN, "campaign jar"), (DLL, "release DLL")):
        if not dep.is_file():
            print(f"[ERROR] {name} missing: {dep}")
            return 1
    BUILD.mkdir(parents=True, exist_ok=True)
    cp = f"{CAMPAIGN};{SRG}"
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-nowarn", "-cp", cp, "-d", str(BUILD), str(SRC)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-5000:])
        return 1
    r = subprocess.run([str(JAVA), "-Xmx2G",
                        "-Djava.library.path=" + str(ROOT / "target" / "release"),
                        "-cp", f"{BUILD};{cp}",
                        "com.rustcraft.bridge.CompressionAuthorityOfflineTest"],
                       cwd=str(ROOT), capture_output=True, text=True, timeout=3600)
    print(r.stdout[-12000:])
    if r.returncode != 0:
        print("STDERR:\n" + r.stderr[-4000:])
        print("COMPRESSION_OFFLINE_FAILED")
        return 1
    print("COMPRESSION_OFFLINE_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
