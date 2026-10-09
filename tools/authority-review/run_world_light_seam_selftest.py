#!/usr/bin/env python3
"""§5: compile + run the World.checkLightFor seam self-test offline.

Verifies the canonical identities and BLOCK/SKY discrimination against the
SRG-named server jar WITHOUT booting a server. Exit 0 and the marker line
WORLD_LIGHT_SEAM_SELFTEST_PASSED mean green.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft"
               r"\minecraft_server.1.12.2.srg.jar")
FORGE_BINPATCHED = Path(r"C:\Users\Siagi\.gradle\caches\minecraft"
                        r"\net\minecraftforge\forge\1.12.2-14.23.5.2847"
                        r"\snapshot\20171003"
                        r"\forge-1.12.2-14.23.5.2847-binpatched.jar")
CAMPAIGN_JAR = ROOT / "target" / "rustcraft-campaign.jar"
RT_A = ROOT / "target" / "authority-smoke" / "runtimeA"
ASM = RT_A / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" \
      / "asm-debug-all-5.2.jar"
LW = RT_A / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" \
     / "launchwrapper-1.12.jar"
SRC = ROOT / "tools" / "authority-review" / "WorldLightSeamSelfTest.java"
OUT = ROOT / "target" / "world-light-seam-selftest"


def main() -> int:
    for p in (JAVAC, JAVA, SRG_JAR, FORGE_BINPATCHED, CAMPAIGN_JAR, SRC):
        if not p.exists():
            print(f"ERROR: missing {p}", file=sys.stderr)
            return 2
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{CAMPAIGN_JAR};{SRG_JAR};{FORGE_BINPATCHED};{ASM};{LW}"
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-nowarn",
         "-cp", cp, "-d", str(OUT), str(SRC)],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.returncode != 0:
        print("JAVAC ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JAVA), "-cp", f"{OUT};{cp}", "WorldLightSeamSelfTest"],
        capture_output=True, text=True, timeout=120,
        encoding="utf-8", errors="replace")
    print(res.stdout)
    if res.returncode != 0:
        print("SELFTEST STDERR:\n" + res.stderr, file=sys.stderr)
        return res.returncode
    if "WORLD_LIGHT_SEAM_SELFTEST_PASSED" not in res.stdout:
        print("ERROR: passed marker missing", file=sys.stderr)
        return 1
    print("[OK] World light seam self-test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
