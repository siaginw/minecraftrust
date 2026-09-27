#!/usr/bin/env bash
# Builds the live-SHADOW campaign coremod jar from the pinned Clean Forge runtime.
# Usage: build_campaign_coremod.sh <srg_minecraft_jar> <output_jar>
# Compilation references ONLY the pinned qualified artifacts (forge .2860, SRG
# minecraft study jar, launchwrapper, ASM) — never a best-effort substitute.
set -e
SRG_JAR="$1"
OUT_JAR="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RT="D:/rustcraft-runtime-targets/clean-forge-2860/server"
JAVA_HOME="D:/rustcraft-toolchains/temurin8/jdk8u504-b01"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
ASM="$RT/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"
LW="$RT/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
FORGE="$RT/forge-1.12.2-14.23.5.2860.jar"
CP="$FORGE;$SRG_JAR;$ASM;$LW"

BUILD="$ROOT/target/live-shadow-campaign/coremod-build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

SOURCES=(
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveShadowCoreMod.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SnapshotCapture.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SyntheticCaptureSource.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/OwnedSnapshotBridge.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterGate.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/PrivateBuildTickets.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveChunkBindings.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LivePacketCapture.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureDraft.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SealedLiveCapture.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveComparisonQueue.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveForgeCaptureSource.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveCaptureScope.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LegacyCaptureScopes.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveShadowCampaignConsumer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/CompressionCtx.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/NativeCompressionEncoder.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/ChunkMutationTracker.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4Coherency.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4PacketParityHarness.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4ValidatorBoundary.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4LifecycleCases.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4DeferredTest.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4ExtractorParity.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4PacketPerfStudy.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M5ResendDriver.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/OutboundFrameCtx.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/M4DecoderGate.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/ChunkMutationTransformer.java"
)
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$CP" -d "$BUILD" "${SOURCES[@]}" 2> "$BUILD/javac-errors.log" || {
  cat "$BUILD/javac-errors.log" | grep -E "error" | head -20
  echo "COMPILE FAILED — see $BUILD/javac-errors.log"
  exit 1
}

mkdir -p "$BUILD/META-INF"
cat > "$BUILD/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
FMLCorePlugin: com.rustcraft.coremod.LiveShadowCoreMod
EOF
rm -f "$OUT_JAR"
"$JAR" cfm "$OUT_JAR" "$BUILD/META-INF/MANIFEST.MF" -C "$BUILD" .
echo "BUILT $OUT_JAR"
