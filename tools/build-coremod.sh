#!/usr/bin/env bash
# Build the M1.4 coremod jar: RustCraftCoreMod + SPacketChunkDataTransformer + bridge.
# Output: tools/dist/rustcraft-m1-coremod.jar (self-contained; loads rustcraft_ffi at runtime).
set -e
JAVAC="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
JAR="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/jar.exe"
CP="third_party_reference/forge/server/forge-1.12.2-14.23.5.2860.jar;third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar;third_party_reference/forge/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar;third_party_reference/forge/server/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"

mkdir -p tools/dist/coremod-build
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$CP" \
  -d tools/dist/coremod-build \
  tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java \
  tools/bridge/src/com/rustcraft/bridge/CompressionCtx.java \
  tools/bridge/src/com/rustcraft/bridge/NativeCompressionEncoder.java \
  tools/bridge/src/com/rustcraft/coremod/RustCraftCoreMod.java \
  tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java \
  tools/bridge/src/com/rustcraft/coremod/NetworkManagerCompressionTransformer.java   tools/bridge/src/com/rustcraft/bridge/CollisionProbe.java \
  tools/bridge/src/com/rustcraft/coremod/WorldCollisionProbeTransformer.java \
  tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java \
  tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java   tools/bridge/src/com/rustcraft/bridge/ChunkMutationTracker.java   tools/bridge/src/com/rustcraft/bridge/M4Coherency.java   tools/bridge/src/com/rustcraft/coremod/ChunkMutationTransformer.java   tools/bridge/src/com/rustcraft/bridge/M4PacketParityHarness.java   tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java \
  tools/bridge/src/com/rustcraft/bridge/M4ValidatorBoundary.java \
  tools/bridge/src/com/rustcraft/bridge/M4LifecycleCases.java \
  tools/bridge/src/com/rustcraft/bridge/M4DeferredTest.java \
  tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java \
  tools/bridge/src/com/rustcraft/bridge/M4AuthoritativeTest.java \
  tools/bridge/src/com/rustcraft/bridge/M4PacketPerfStudy.java \
  tools/bridge/src/com/rustcraft/bridge/M4ExtractorParity.java \
  tools/bridge/src/com/rustcraft/bridge/M5PipelineOracle.java \
  tools/bridge/src/com/rustcraft/bridge/M5ResendDriver.java \
  tools/bridge/src/com/rustcraft/coremod/WorldgenShadowTransformer.java

mkdir -p tools/dist/coremod-build/META-INF
cat > tools/dist/coremod-build/mcmod.info << 'EOF'
[{
  "modid": "rustcraft_m1_coremod",
  "name": "RustCraft M1 CoreMod",
  "description": "M1.4 chunk-packet native bridge (SPacketChunkData hook). Research runtime; default OFF.",
  "version": "1.0.0"
}]
EOF
cat > tools/dist/manifest.mf << 'MANIFEST'
Manifest-Version: 1.0
FMLCorePlugin: com.rustcraft.coremod.RustCraftCoreMod
FMLCorePluginContainsFMLMod: true
MANIFEST

rm -f tools/dist/rustcraft-m1-coremod.jar
cd tools/dist/coremod-build && "$JAR" cfm ../rustcraft-m1-coremod.jar ../manifest.mf . && cd ../../..
echo "BUILT tools/dist/rustcraft-m1-coremod.jar"
