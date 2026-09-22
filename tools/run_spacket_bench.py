import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
cp = [
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
] + libs
cp_str = ";".join(cp)

os.makedirs("tools/bench-spacket/bin", exist_ok=True)

src = "tools/bench-spacket/src/com/rustcraft/bench/SPacketBenchmark.java"
cls = "com.rustcraft.bench.SPacketBenchmark"

print("Compiling SPacketBenchmark...")
res = subprocess.run([javac, "-cp", cp_str, "-d", "tools/bench-spacket/bin", src], capture_output=True, text=True)
if res.returncode != 0:
    print("Compilation error:", res.stderr)
    sys.exit(1)

print("Running SPacketBenchmark on frozen Java 8 environment...")
res = subprocess.run([java, "-cp", cp_str + ";tools/bench-spacket/bin", cls], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
if res.returncode != 0:
    sys.exit(res.returncode)
