#!/usr/bin/env bash
# M2CP perf-study runner: Target C (Revelation), FIXED 6G heap + GC telemetry,
# identical flags for both arms (only -Dminecraftrust.native_compress differs).
# Usage: m2cp-run.sh <MODE> <label> <run_seconds>
set -e
MODE="${1:?}"; LABEL="${2:?}"; SECS="${3:?}"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
cd machine/targetC/server
(
  sleep "$SECS"
  echo "stop"
  sleep 45
) | "$JAVA" -Xms6G -Xmx6G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.native_compress="$MODE" \
  -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime \
  -Xloggc:"gc-$LABEL.log" \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2846-universal.jar nogui \
  > "run-$LABEL.log" 2>&1
echo "EXIT=$? label=$LABEL m2c=$MODE"
