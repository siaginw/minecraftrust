#!/usr/bin/env bash
# Runs the five live writer-protocol foundation suites (offline, pure Java):
# PrivateBuildTicketsTest, LiveWriterGateTest, LiveChunkBindingsTest,
# LiveWriterProtocolEndToEndTest, LiveCaptureAdmissionTest.
# Compilation references ONLY the pinned qualified artifacts, exactly like
# build_campaign_coremod.sh — never a best-effort substitute.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RT="D:/rustcraft-runtime-targets/clean-forge-2860/server"
JAVA_HOME="D:/rustcraft-toolchains/temurin8/jdk8u504-b01"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"
SRG_JAR="$ROOT/target/live-shadow-campaign/srg-minecraft.jar"
if [ ! -f "$SRG_JAR" ]; then
  SRG_JAR="$ROOT/target/live-transformer-build/srg/minecraft_server.1.12.2.srg.jar"
fi
ASM="$RT/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"
LW="$RT/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
FORGE="$RT/forge-1.12.2-14.23.5.2860.jar"
CP="$FORGE;$SRG_JAR;$ASM;$LW"

BUILD="$ROOT/target/live-foundation-tests-build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$CP" -d "$BUILD" \
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java" \
  "$ROOT"/tools/bridge/src/com/rustcraft/bridge/capture/*.java \
  "$ROOT"/tools/live-capture-tests/src/com/rustcraft/bridge/capture/*.java

STATUS=0
for SUITE in PrivateBuildTicketsTest LiveWriterGateTest LiveChunkBindingsTest \
             LiveWriterProtocolEndToEndTest LiveCaptureAdmissionTest; do
  echo "== $SUITE"
  "$JAVA" -cp "$BUILD" "com.rustcraft.bridge.capture.$SUITE" || STATUS=1
done
if [ "$STATUS" -eq 0 ]; then echo "LIVE FOUNDATION SUITES: ALL GREEN"; else echo "LIVE FOUNDATION SUITES: FAILURES"; fi
exit "$STATUS"
