import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
cp = [
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
] + libs
cp_str = ";".join(cp)

os.makedirs("tools/bridge/bin", exist_ok=True)
os.makedirs("tools/chunk-packet-oracle/bin", exist_ok=True)
os.makedirs("benchmarks/m1/chunk-packet/native-ab", exist_ok=True)

print("Compiling NativeChunkPacket...")
res = subprocess.run([javac, "-cp", cp_str, "-d", "tools/bridge/bin",
                      "tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java"],
                     capture_output=True, text=True)
if res.returncode != 0:
    print("COMPILE ERROR bridge:\n", res.stderr)
    sys.exit(1)

print("Compiling coremod classes...")
res = subprocess.run([javac, "-cp", cp_str + ";tools/bridge/bin", "-d", "tools/bridge/bin",
                      "tools/bridge/src/com/rustcraft/coremod/RustCraftCoreMod.java",
                      "tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java"],
                     capture_output=True, text=True)
if res.returncode != 0:
    print("COMPILE ERROR coremod:\n", res.stderr)
    sys.exit(1)

print("Compiling M14ValidationHarness...")
res = subprocess.run([javac, "-cp", cp_str + ";tools/bridge/bin", "-d", "tools/chunk-packet-oracle/bin",
                      "tools/chunk-packet-oracle/src/com/rustcraft/oracle/M14ValidationHarness.java"],
                     capture_output=True, text=True)
if res.returncode != 0:
    print("COMPILE ERROR harness:\n", res.stderr)
    sys.exit(1)

print("ALL COMPILED OK")
