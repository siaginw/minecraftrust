#!/usr/bin/env bash
# M3 worldgen LIVE SHADOW run for FTB Revelation 3.4.0 (Target C)
# Operator authorization 2026-09-20.
# Forge 14.23.5.2846, DISPOSABLE COPY of Target C server with world deleted.
# Fresh chunk generation via perfbotb TP walk across multiple spans/biomes.
# Java authoritative; Rust shadow compares + discards. Bounded run.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
LABEL=m3wg-rev-shadow1
SRC="$ROOT/machine/targetC/server"
RUN="$ROOT/machine/targetC/shadow-run"
SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"

echo "[$(date +%H:%M:%S)] Preparing disposable Target C environment..."
rm -rf "$RUN"
mkdir -p "$RUN"

# Copy server assets (excluding heavy world and old logs)
cp -r "$SRC/config" "$RUN/"
cp -r "$SRC/libraries" "$RUN/"
cp -r "$SRC/mods" "$RUN/"
cp -r "$SRC/scripts" "$RUN/" 2>/dev/null || true
cp "$SRC/forge-1.12.2-14.23.5.2846-universal.jar" "$RUN/"
cp "$SRC/minecraft_server.1.12.2.jar" "$RUN/"
cp "$SRC/server.properties" "$RUN/"
cp "$SRC/eula.txt" "$RUN/"
cp "$SRC/options.txt" "$RUN/" 2>/dev/null || true
cp "$SRC/ops.json" "$RUN/" 2>/dev/null || true
cp "$SRC/whitelist.json" "$RUN/" 2>/dev/null || true
cp "$SRC/banned-ips.json" "$RUN/" 2>/dev/null || true
cp "$SRC/banned-players.json" "$RUN/" 2>/dev/null || true

# Deploy our updated measurement coremod jar and native DLL
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"

# Verify world directory is absent so generation is 100% fresh
rm -rf "$RUN/world"

cd "$RUN"
echo "[$(date +%H:%M:%S)] Launching Revelation Forge server in SHADOW mode..."
( sleep 680; echo stop; sleep 60 ) | "$JAVA" -Xmx6G -Xms6G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2846-universal.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!

echo "[$(date +%H:%M:%S)] Waiting for server to signal Done (pid $SRV)..."
BOOTED=false
for i in $(seq 1 40); do
  sleep 5
  if grep -q "Done (" "run-$LABEL.log" 2>/dev/null; then
    BOOTED=true
    break
  fi
  # Check if server exited unexpectedly
  if ! kill -0 $SRV 2>/dev/null; then
    echo "[$(date +%H:%M:%S)] SERVER EXITED PREMATURELY!"
    cat "run-$LABEL.log" | tail -n 40
    exit 1
  fi
done

if [ "$BOOTED" != "true" ]; then
  echo "[$(date +%H:%M:%S)] Server boot timed out!"
  kill $SRV 2>/dev/null || true
  exit 1
fi

echo "[$(date +%H:%M:%S)] Server reached Done. Settling 30s for FML post-Done init..."
sleep 30

echo "[$(date +%H:%M:%S)] Starting deterministic exploration bot..."
( M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" M1_USER=perfbotb \
  M1_TP=140 M1_TP_INTERVAL=4 M1_TP_STEP=200 M1_TP_Z_STEP=120 M1_TP_SPAN=10000 \
  HOLD_SECONDS=450 SESSIONS=1 \
  python "$ROOT/tools/targeta_client.py" "$LABEL" 127.0.0.1 25567 >/dev/null 2>&1 || true ) &
BOT_PID=$!

echo "[$(date +%H:%M:%S)] Bot launched (pid $BOT_PID). Waiting for server session to complete..."
wait $SRV || true
echo "[$(date +%H:%M:%S)] Server process exited."

# Harvest output
cp "run-$LABEL.log" "$ROOT/machine/raw/M3WG-rev-shadow-server.log" 2>/dev/null || true
if [ -f m1-metrics.txt ]; then
  cp m1-metrics.txt "$ROOT/machine/raw/M3WG-rev-shadow-metrics.txt"
  echo "[$(date +%H:%M:%S)] Copied m1-metrics.txt to machine/raw/M3WG-rev-shadow-metrics.txt"
fi

echo "[$(date +%H:%M:%S)] Revelation live shadow run finished."
