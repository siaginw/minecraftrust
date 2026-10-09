#!/usr/bin/env bash
# Phase-D offline controls (section 18) and offline replay (section 19).
# Compiles the bridge capture classes plus the Phase-D controls against the
# pinned Clean Forge 2860 artifacts, then runs:
#   1. PhaseDControls          -- the twenty pre-live-event proofs (no DLL)
#   2. PhaseDOfflineReplay     -- one REAL oracle event through the exact
#                                 Phase-D pipeline with the real Rust DLL
# Usage: run_phase_d_controls.sh <out-dir>
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)"
OUT="${1:-$ROOT/target/phase-d-controls}"
RT="D:/rustcraft-runtime-targets/clean-forge-2860/server"
JAVA_HOME="D:/rustcraft-toolchains/temurin8/jdk8u504-b01"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"
SRG="$ROOT/target/architecture-hardening/h10-final-01/compile-only-srg.jar"
DLL="$ROOT/target/release/rustcraft_ffi.dll"
EVENTS="$ROOT/target/architecture-hardening/h23-forge-witness-probe-04/oracle/events.json"
CP="$RT/forge-1.12.2-14.23.5.2860.jar;$SRG;$RT/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar;$RT/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"

mkdir -p "$OUT/classes"
SOURCES=(
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/OwnedSnapshotBridge.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterGate.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/PrivateBuildTickets.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveChunkBindings.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveComparisonQueue.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveCaptureScope.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LegacyCaptureScopes.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LiveForgeCaptureSource.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowScopeGate.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventJournal.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventComparator.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SessionCompatibilityContract.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/PhaseDScopePolicy.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/LivePacketCapture.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/CaptureDraft.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/capture/SealedLiveCapture.java"
  "$ROOT/tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java"
  "$ROOT/tools/live-shadow-v2/src/com/rustcraft/bridge/capture/PhaseDControls.java"
  "$ROOT/tools/live-shadow-v2/src/com/rustcraft/bridge/capture/PhaseDOfflineReplay.java"
)
MSYS2_ARG_CONV_EXCL='*' "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}" 2> "$OUT/javac.log" || {
  grep -E "error" "$OUT/javac.log" | head -20; echo "COMPILE FAILED"; exit 1; }

echo "== Phase D offline controls (section 18)"
MSYS2_ARG_CONV_EXCL='*' "$JAVA" -cp "$OUT/classes;$CP" \
  com.rustcraft.bridge.capture.PhaseDControls "$OUT"

echo "== Phase D offline replay (section 19): terrain-001f through the real DLL"
MSYS2_ARG_CONV_EXCL='*' "$JAVA" -cp "$OUT/classes;$CP" \
  com.rustcraft.bridge.capture.PhaseDOfflineReplay \
  "$EVENTS" terrain-001f "$DLL" "$OUT/replay"

echo "PHASE D OFFLINE CONTROLS + REPLAY: ALL GREEN"
