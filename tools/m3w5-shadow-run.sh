#!/usr/bin/env bash
# M3W5 terrain LIVE SHADOW run (clean Forge Target A).
# Target A ONLY, DISPOSABLE COPY of the tracked world (tracked fixtures untouched).
# Java authoritative; Rust shadow compares density + ChunkPrimer blocks and discards.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
LABEL=m3w5-shadow
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/shadow-run"
rm -rf "$RUN"
mkdir -p "$RUN"
# copy the fixture (world copy = disposable)
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
# deploy the SHADOW measurement build
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
# disposable: DELETE existing world so all generation is FRESH
rm -rf "$RUN/world"
cd "$RUN"
( sleep 120; echo stop; sleep 30 ) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!
for i in $(seq 1 120); do
  sleep 2; grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
done
grep -q "Done (" "run-$LABEL.log" || { echo "BOOT FAILED"; cat "run-$LABEL.log"; exit 1; }
echo "SERVER BOOT COMPLETED — GENERATING CHUNKS VIA BOT"
sleep 10
( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=m2cebot \
    M1_TP=30 M1_TP_INTERVAL=2 M1_TP_STEP=200 M1_TP_SPAN=20000 \
    HOLD_SECONDS=70 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m3w5-shadow 127.0.0.1 25565 >/dev/null 2>&1 || true ) &
wait $SRV || true
cp m1-metrics.txt "$ROOT/machine/raw/M3W5-shadow-metrics-final.txt" 2>/dev/null
echo "SHADOW RUN COMPLETE"
