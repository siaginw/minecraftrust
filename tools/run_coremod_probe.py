import glob
import os
import subprocess
import sys

JAVA_HOME = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot"
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAVA = os.path.join(JAVA_HOME, "bin", "java.exe")

def main():
    os.makedirs("tools/coremod-probe/bin", exist_ok=True)
    os.makedirs("benchmarks/forge/p0-7", exist_ok=True)

    libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
    asm_jars = [jar for jar in libs if "asm" in jar]
    cp = os.pathsep.join(asm_jars + ["tools/coremod-probe/bin"])

    print("Compiling CoreModProbe...")
    cmd_compile = [JAVAC, "-cp", cp, "-d", "tools/coremod-probe/bin", "tools/coremod-probe/src/CoreModProbe.java"]
    res = subprocess.run(cmd_compile, capture_output=True, text=True)
    if res.returncode != 0:
        print("Compilation error for CoreModProbe:\n", res.stderr)
        sys.exit(1)

    print("Running CoreModProbe...")
    cmd_run = [JAVA, "-cp", cp, "CoreModProbe"]
    res = subprocess.run(cmd_run, capture_output=True, text=True)
    print(res.stdout)
    if res.stderr:
        print("STDERR:", res.stderr)
    if res.returncode != 0:
        sys.exit(res.returncode)

if __name__ == "__main__":
    main()