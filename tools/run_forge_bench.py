import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
cp = [
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\minecraft_server.1.12.2.jar",
    r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
] + libs
cp_str = ";".join(cp)

os.makedirs("tools/forge-bench/bin", exist_ok=True)
os.makedirs("benchmarks/forge/p0-7", exist_ok=True)

src = "tools/forge-bench/src/ForgeBenchmarks.java"
print("Compiling ForgeBenchmarks...")
res = subprocess.run([javac, "-cp", cp_str, "-d", "tools/forge-bench/bin", src], capture_output=True, text=True)
if res.returncode != 0:
    print("Compilation error:", res.stderr)
    sys.exit(1)

print("Running ForgeBenchmarks...")
res = subprocess.run([java, "-cp", cp_str + ";tools/forge-bench/bin", "ForgeBenchmarks"], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
if res.returncode != 0:
    sys.exit(res.returncode)

print("ForgeBenchmarks complete!")
