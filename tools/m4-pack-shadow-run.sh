#!/usr/bin/env bash
# M4.1 modpack SHADOW run: generalized NativeSection + NativeChunk retention
# under a full modpack (Revelation = Target C, SevTech = Ages = Target D).
#
# Java authoritative; Rust shadow compares base terrain bit-exactly, registers
# the generalized NativeChunk, and exercises all three consumers (packet
# encode, occupancy, persistence staging) against modpack-generated sections.
# M1 packet path OFF. Disposable copy; tracked fixtures untouched.
# Usage: m4-pack-shadow-run.sh <C|D> [run_seconds]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
PACK="${1:?C|D}"
SECS="${2:-680}"
case "$PACK" in
  C) SRC="$ROOT/machine/targetC/server"; SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"
     PORT=25567; BOTUSER=perfbotb; LABEL=m4-rev-shadow; MODLIST="$ROOT/machine/targetC/mod-ids.json" ;;
  D) SRC="$ROOT/machine/targetD/server"; SRVMARK="forge-1.12.2-14.23.5.2860.jar"
     PORT=25568; BOTUSER=perfbotb; LABEL=m4-sev-shadow; MODLIST="$ROOT/machine/targetD/mod-ids.json" ;;
  *) echo "bad pack: $PACK"; exit 2 ;;
esac
RUN="$SRC/../shadow-run"

echo "[$(date +%H:%M:%S)] Preparing disposable $PACK environment..."
rm -rf "$RUN"
mkdir -p "$RUN"
for d in config libraries mods scripts structures; do
  cp -r "$SRC/$d" "$RUN/" 2>/dev/null || true
done
for f in "$SRVMARK" minecraft_server.1.12.2.jar server.properties eula.txt options.txt ops.json whitelist.json; do
  cp "$SRC/$f" "$RUN/" 2>/dev/null || true
done

# Deploy fresh measurement build (generalized sections + refresh exports)
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"

rm -rf "$RUN/world"
cd "$RUN"

echo "[$(date +%H:%M:%S)] Launching $PACK Forge server in SHADOW mode..."
( sleep "$SECS"; echo stop; sleep 60 ) | "$JAVA" -Xmx6G -Xms6G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar "$SRVMARK" nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!

echo "[$(date +%H:%M:%S)] Waiting for Done (pid $SRV)..."
BOOTED=false
for i in $(seq 1 60); do
  sleep 5
  grep -q "Done (" "run-$LABEL.log" 2>/dev/null && { BOOTED=true; break; }
  kill -0 $SRV 2>/dev/null || { echo "SERVER EXITED PREMATURELY"; tail -n 40 "run-$LABEL.log"; exit 1; }
done
[ "$BOOTED" = "true" ] || { echo "BOOT TIMEOUT"; kill $SRV 2>/dev/null || true; exit 1; }

echo "[$(date +%H:%M:%S)] Done reached. Settling 30s for FML post-Done init..."
sleep 30

echo "[$(date +%H:%M:%S)] Starting TP walk bot..."
( M1_MODLIST="$MODLIST" M1_USER=$BOTUSER \
    M1_TP=140 M1_TP_INTERVAL=4 M1_TP_STEP=200 M1_TP_Z_STEP=120 M1_TP_SPAN=10000 \
    HOLD_SECONDS=450 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" "$LABEL" 127.0.0.1 "$PORT" >/dev/null 2>&1 || true ) &

wait $SRV || true
echo "[$(date +%H:%M:%S)] Server exited. Harvesting..."

cp "run-$LABEL.log" "$ROOT/machine/raw/$LABEL-server.log" 2>/dev/null || true
cp m1-metrics.txt "$ROOT/machine/raw/$LABEL-metrics.txt" 2>/dev/null || true
echo "[$(date +%H:%M:%S)] $LABEL complete. Metrics: machine/raw/$LABEL-metrics.txt"
