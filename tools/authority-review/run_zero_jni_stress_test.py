#!/usr/bin/env python3
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"

SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN_JAR = ROOT / "target" / "rustcraft-campaign.jar"
OUT_DIR = ROOT / "target" / "authority-review"

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    cp = f"{CAMPAIGN_JAR};{SRG_JAR}"
    test_src = ROOT / "tools" / "authority-review" / "ZeroJniDirectMemoryStressTest.java"

    print("[compile] Compiling ZeroJniDirectMemoryStressTest.java...")
    cmd_compile = [
        str(JAVAC),
        "-encoding", "UTF-8",
        "-cp", cp,
        "-d", str(OUT_DIR),
        str(test_src)
    ]
    res = subprocess.run(cmd_compile, capture_output=True, text=True)
    if res.returncode != 0:
        print("JAVAC ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    print("[run] Executing ZeroJniDirectMemoryStressTest...")
    cmd_run = [
        str(JAVA),
        f"-Djava.library.path={ROOT}",
        "-cp", f"{OUT_DIR};{cp}",
        "com.rustcraft.authority.ZeroJniDirectMemoryStressTest"
    ]
    res_run = subprocess.run(cmd_run, capture_output=True, text=True)
    print(res_run.stdout)
    if res_run.stderr:
        print(res_run.stderr, file=sys.stderr)
    return res_run.returncode

if __name__ == "__main__":
    sys.exit(main())
