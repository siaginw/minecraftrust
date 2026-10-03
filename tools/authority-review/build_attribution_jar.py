#!/usr/bin/env python3
"""Builds rustcraft-attribution.jar: the goal-§4 writer-attribution agent
(WriterAttributionAgent premain + WriterAttribution bootstrap helper).

TEST-ONLY: the agent is inert without -Drustcraft.writerAttribution=true.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAR = JDK8 / "bin" / "jar.exe"
OUT = ROOT / "target" / "rustcraft-attribution.jar"
BUILD = ROOT / "target" / "attribution-build"

# launchwrapper-provided ASM on the compile classpath
ASM_CANDIDATES = [
    ROOT / "target/authority-smoke/runtimeA/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar",
    ROOT / "target/authority-smoke/runtimeC/libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar",
]
ASM = next((p for p in ASM_CANDIDATES if p.is_file()), None)
SRC = ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/WriterAttribution.java"
SRC2 = ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/WriterAttributionAgent.java"


def main() -> int:
    if ASM is None:
        print("[ERROR] no ASM jar found for compile classpath", file=sys.stderr)
        return 1
    if BUILD.exists():
        import shutil
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True, exist_ok=True)
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8",
                        "-target", "8", "-nowarn", "-cp", str(ASM),
                        "-d", str(BUILD), str(SRC), str(SRC2)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-4000:], file=sys.stderr)
        return 1
    meta = BUILD / "META-INF"
    meta.mkdir(exist_ok=True)
    (meta / "MANIFEST.MF").write_text(
        "Manifest-Version: 1.0\n"
        "Premain-Class: com.rustcraft.offline.agent.WriterAttributionAgent\n",
        encoding="utf-8")
    if OUT.exists():
        OUT.unlink()
    r = subprocess.run([str(JAR), "cfm", str(OUT), str(meta / "MANIFEST.MF"),
                        "-C", str(BUILD), "."], capture_output=True, text=True)
    if r.returncode != 0:
        print("JAR ERROR:\n" + r.stderr[-2000:], file=sys.stderr)
        return 1
    print(f"[ok] {OUT} ({OUT.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
