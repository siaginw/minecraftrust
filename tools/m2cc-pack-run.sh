#!/usr/bin/env bash
# M2CC modpack shadow campaign runner: one pack run in M2C SHADOW.
# M1 stays OFF (native_chunk_packet=OFF). M2C mode from arg.
# Usage: m2cc-pack-run.sh <C|D> <MODE> <label> <run_seconds> [paranoid]
set -e
PACK="${1:?C|D}"; MODE="${2:?SHADOW}"; LABEL="${3:?}"; SECS="${4:?}"; PARANOID="${5:-}"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
PFLAG=""; [ -n "$PARANOID" ] && PFLAG="-Dio.netty.leakDetection.level=paranoid"
case "$PACK" in
  C) DIR=machine/targetC/server; JAR=forge-1.12.2-14.23.5.2846-universal.jar; HEAP=-Xmx6G ;;
  D) DIR=machine/targetD/server; JAR=forge-1.12.2-14.23.5.2860.jar;    HEAP=-Xmx6G ;;
  *) echo bad pack; exit 2 ;;
esac
cd "$DIR"
(
  sleep "$SECS"
  echo "stop"
  sleep 60
) | "$JAVA" $HEAP -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.native_compress="$MODE" \
  $PFLAG \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar "$JAR" nogui \
  > "run-$LABEL.log" 2>&1
echo "EXIT=$? label=$LABEL pack=$PACK m2c=$MODE"
