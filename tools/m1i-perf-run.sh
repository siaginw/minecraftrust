#!/usr/bin/env bash
# M1I whole-server perf closure: instrumented matched run for Target C/D.
# Usage: m1i-perf-run.sh <pack: C|D> <mode: OFF|ON_EXPERIMENTAL> <label> <run_seconds>
# Env: M1_HANDOFF (default A). Adds GC + safepoint telemetry (identical flags
# both arms). Same coremod jar (MSPT instrumentation) both arms; only the
# minecraftrust.native_chunk_packet property differs.
set -e
PACK="${1:?C|D}"
MODE="${2:?OFF|ON_EXPERIMENTAL}"
LABEL="${3:?label}"
SECS="${4:?run_seconds}"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
case "$PACK" in
  C) DIR=machine/targetC/server; JAR=forge-1.12.2-14.23.5.2846-universal.jar ;;
  D) DIR=machine/targetD/server; JAR=forge-1.12.2-14.23.5.2860.jar ;;
  *) echo "bad pack"; exit 2 ;;
esac
cd "$DIR"
(
  sleep "$SECS"
  echo "stop"
  sleep 45
) | "$JAVA" -Xmx6G -Xms6G \
  -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet="$MODE" \
  -Dminecraftrust.m1.handoff="${M1_HANDOFF:-A}" \
  -Drustcraft.m1.dumpdiagnostics=true \
  -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime \
  -Xloggc:"gc-$LABEL.log" \
  -jar "$JAR" nogui \
  > "run-$LABEL.log" 2>&1
echo "EXIT=$? label=$LABEL pack=$PACK mode=$MODE"
