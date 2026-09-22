import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
minecraft_jar = r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar"
forge_jar = r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
cp = ";".join([minecraft_jar, forge_jar] + libs + ["tools/bridge/bin"])

os.makedirs("tools/chunk-packet-oracle/bin", exist_ok=True)
compile_cmd = [
    javac, "-cp", cp,
    "-d", "tools/chunk-packet-oracle/bin",
    "tools/chunk-packet-oracle/src/com/rustcraft/oracle/InspectJavaSectionBytes.java"
]
print("Compiling...")
res = subprocess.run(compile_cmd, capture_output=True, text=True)
if res.returncode != 0:
    print("Compilation failed:\n", res.stderr)
    sys.exit(1)

print("Running...")
run_cmd = [
    java, "-cp", cp + ";tools/chunk-packet-oracle/bin",
    "com.rustcraft.oracle.InspectJavaSectionBytes"
]
res = subprocess.run(run_cmd, capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("ERR:\n", res.stderr)
