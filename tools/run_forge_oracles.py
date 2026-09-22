import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
cp = [
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
] + libs
cp_str = ";".join(cp)

os.makedirs("tools/forge-oracle/bin", exist_ok=True)
os.makedirs("benchmarks/forge/p0-7", exist_ok=True)

oracles = ["EventOracle", "RegistryOracle", "CapabilityOracle"]

for ora in oracles:
    if ora == "EventOracle":
        src = f"tools/forge-oracle/src/com/rustcraft/bench/{ora}.java"
        cls = f"com.rustcraft.bench.{ora}"
    else:
        src = f"tools/forge-oracle/src/{ora}.java"
        cls = ora
    print(f"Compiling {ora}...")
    res = subprocess.run([javac, "-cp", cp_str, "-d", "tools/forge-oracle/bin", src], capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Compilation error for {ora}:", res.stderr)
        sys.exit(1)
    
    print(f"Running {ora}...")
    res = subprocess.run([java, "-cp", cp_str + ";tools/forge-oracle/bin", cls], capture_output=True, text=True)
    print(res.stdout)
    if res.stderr:
        print("STDERR:", res.stderr)
    if res.returncode != 0:
        sys.exit(res.returncode)

print("All Forge Oracles executed successfully!")
