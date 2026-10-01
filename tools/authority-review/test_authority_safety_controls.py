#!/usr/bin/env python3
"""Compiles and executes AuthoritySafetyControlsTest to verify hard safety controls."""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"

CAMPAIGN_JAR = ROOT / "target" / "rustcraft-campaign.jar"
SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
RT = Path(r"D:\minecraftrust\machine\targetC\server")
FORGE = RT / "forge-1.12.2-14.23.5.2846-universal.jar"
ASM = RT / "libraries" / "org" / "ow2" / "asm" / "asm-all" / "5.2" / "asm-all-5.2.jar"
LW = RT / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"

TEST_SRC = ROOT / "tools" / "authority-review" / "AuthoritySafetyControlsTest.java"
BUILD_DIR = ROOT / "target" / "authority-test-build"


def main():
    if not JAVAC.exists() or not JAVA.exists():
        print(f"ERROR: JDK 8 tools not found in {JDK8}", file=sys.stderr)
        return 1
    if not CAMPAIGN_JAR.exists():
        print(f"ERROR: Campaign jar not found at {CAMPAIGN_JAR}. Run tools/build_campaign_jar.py first.", file=sys.stderr)
        return 1

    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    cp = f"{CAMPAIGN_JAR};{FORGE};{SRG_JAR};{ASM};{LW};{BUILD_DIR}"

    print("[test] Compiling AuthoritySafetyControlsTest...")
    compile_cmd = [
        str(JAVAC),
        "-encoding", "UTF-8",
        "-source", "8",
        "-target", "8",
        "-cp", cp,
        "-d", str(BUILD_DIR),
        str(TEST_SRC)
    ]
    res = subprocess.run(compile_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("JAVAC ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    print("[test] Executing AuthoritySafetyControlsTest in JVM...")
    run_cmd = [
        str(JAVA),
        "-cp", cp,
        "com.rustcraft.authority.AuthoritySafetyControlsTest"
    ]
    res = subprocess.run(run_cmd, capture_output=True, text=True)
    print(res.stdout)
    if res.returncode != 0:
        print("TEST RUNNER ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    print("[test] Safety controls verification complete.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
