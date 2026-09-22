#!/usr/bin/env bash
# M2C Target-A run: OFF sanity then SHADOW, bounded, existing bot + protocol bot.
# Modes driven by minecraftrust.native_compress (M2C) — M1 stays OFF.
# Usage: m2c-a-run.sh <OFF|SHADOW> <label> <run_seconds> [paranoid]
set -e
MODE="${1:?}"; LABEL="${2:?}"; SECS="${3:?}"; PARANOID="${4:-}"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
PFLAG=""; [ -n "$PARANOID" ] && PFLAG="-Dio.netty.leakDetection.level=paranoid"
cd machine/targetA/server
(
  sleep "$SECS"
  echo "stop"
  sleep 45
) | "$JAVA" -Xmx2G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.native_compress="$MODE" \
  $PFLAG \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "../run-$LABEL.log" 2>&1
echo "EXIT=$? label=$LABEL m2c=$MODE"
