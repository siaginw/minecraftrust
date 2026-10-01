#!/usr/bin/env bash
# Builds the live-SHADOW campaign coremod jar from a pinned runtime.
# Usage: build_campaign_coremod.sh <srg_minecraft_jar> <output_jar> [runtime_root] [asm_jar] [forge_jar] [plan_java]
# The optional plan is a GENERATED LiveWriterPlan.java for a different runtime.
# It is a parameter rather than an edit because the plan is an artifact: the
# committed one is Clean Forge's, and qualification has to run the same writers
# under a plan that was generated from that runtime's own evidence.
# Compilation references ONLY the pinned qualified artifacts of THAT runtime (its
# forge build, its SRG minecraft study jar, its launchwrapper, and ITS OWN ASM
# jar) — never a best-effort substitute, and never another runtime's ASM. The
# defaults reproduce the Clean Forge build exactly; passing the Revelation root
# and its asm-all builds the same sources against the Revelation pins instead.
set -e
SRG_JAR="$1"
OUT_JAR="$2"
# pwd -W on MSYS/Git Bash; javac.exe is a Windows binary and cannot open a
# /d/... path, so a POSIX ROOT fails before a single source is read.
ROOT="$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)"
JAVA_HOME="${JAVA_HOME:-D:/rustcraft-toolchains/temurin8/jdk8u504-b01}"
if [ ! -d "$JAVA_HOME" ] && [ -d "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot" ]; then
  JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot"
fi
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
# The ASM jar is a parameter rather than a constant for one reason: the two
# runtimes pin different builds (asm-debug-all for Clean Forge, asm-all for
# Revelation), and putting the other tree's jar on this classpath is exactly the
# substitution the qualification exists to prevent.
ASM="${4:-$RT/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar}"
LW="$RT/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
FORGE="${5:-$RT/forge-1.12.2-14.23.5.2860.jar}"
PLAN="${6:-$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java}"
CP="$FORGE;$SRG_JAR;$ASM;$LW"

BUILD="$ROOT/target/live-shadow-campaign/coremod-build-$(basename "$OUT_JAR" .jar)"
rm -rf "$BUILD"
mkdir -p "$BUILD"

SOURCES=(
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveShadowCoreMod.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveWriterOrdering.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/LiveSessionAdmissionTweaker.java"
  "$ROOT/tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/AsmTreeCompat.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/SessionBoundAdmissionPolicy.java"
  "$ROOT/tools/bridge/src/com/rustcraft/coremod/SessionBoundIdentityCertificate.java"
  "$PLAN"
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
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowScopeGate.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventJournal.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/RevelationFixtureHarvest.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CampaignTeleportController.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventComparator.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SessionCompatibilityContract.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/PhaseDScopePolicy.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveShadowCampaignConsumer.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/PacketAuthorityExperiment.java"
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
  # LiveHookSupport reaches the same-process acquisition recorder, and the
  # recorder is what the chain producer reads. Omitting them does not build a
  # smaller jar, it fails the build: javac cannot resolve a type the sources
  # reference, and a launch that somehow had them anyway would be reading a
  # different copy of the class the writers used.
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/SameProcessAcquisition.java"
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/SessionEvidenceFlush.java"
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/TransformationChainEvidence.java"
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/LoaderTransformChain.java"
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/LoaderDefinitionWitness.java"
  "$ROOT/tools/bridge/src/com/rustcraft/qualification/CalleeIsolation.java"
  "$ROOT/tools/bridge/src/com/rustcraft/livetransformer/FrameRelationWitness.java"
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
Premain-Class: com.rustcraft.offline.agent.ObservationAgent
EOF
rm -f "$OUT_JAR"
"$JAR" cfm "$OUT_JAR" "$BUILD/META-INF/MANIFEST.MF" -C "$BUILD" .
echo "BUILT $OUT_JAR"
