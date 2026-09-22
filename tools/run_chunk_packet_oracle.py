import subprocess, os, sys, glob

javac = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
java = "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"

libs = glob.glob("third_party_reference/forge/server/libraries/**/*.jar", recursive=True)
cp = [
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar"
] + libs
cp_str = ";".join(cp)

os.makedirs("tools/chunk-packet-oracle/bin", exist_ok=True)

src = "tools/chunk-packet-oracle/src/com/rustcraft/oracle/ChunkPacketOracle.java"
cls = "com.rustcraft.oracle.ChunkPacketOracle"

print("Compiling NativeChunkPacket...")
subprocess.run([javac, "-cp", cp_str, "-d", "tools/bridge/bin", "tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java"], check=True)

print("Compiling ChunkPacketOracle...")
res = subprocess.run([javac, "-cp", cp_str + ";tools/bridge/bin", "-d", "tools/chunk-packet-oracle/bin", src], capture_output=True, text=True)
if res.returncode != 0:
    print("Compilation error:", res.stderr)
    sys.exit(1)

print("Running ChunkPacketOracle with native library target/debug/rustcraft_ffi.dll...")
res = subprocess.run([java, "-Djava.library.path=target/debug", "-cp", cp_str + ";tools/bridge/bin;tools/chunk-packet-oracle/bin", cls], capture_output=True, text=True)
print(res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
if res.returncode != 0:
    sys.exit(res.returncode)
