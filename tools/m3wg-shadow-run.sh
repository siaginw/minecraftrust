#!/usr/bin/env bash
# M3 worldgen LIVE SHADOW run (operator authorization 2026-09-20).
# Target A ONLY, DISPOSABLE COPY of the tracked world (tracked fixtures
# untouched). Fresh chunk generation via perfbotb TP walk in a COPY world dir.
# Java authoritative; Rust shadow compares + discards. Bounded (~10 min).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
LABEL=m3wg-shadow1
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/shadow-run"
rm -rf "$RUN"
mkdir -p "$RUN"
# copy the fixture (world copy = disposable)
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
# deploy the SHADOW measurement build (jar 15559a0d)
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
# disposable: DELETE existing world so all generation is FRESH
rm -rf "$RUN/world"
cd "$RUN"
( sleep 620; echo stop; sleep 45 ) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!
for i in $(seq 1 200); do
  sleep 5; grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
done
grep -q "Done (" "run-$LABEL.log" || { echo "BOOT FAILED"; exit 1; }
sleep 25
( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=perfbotb \
    M1_TP=110 M1_TP_INTERVAL=4 M1_TP_STEP=200 M1_TP_SPAN=20000 \
    HOLD_SECONDS=420 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m3wg-shadow 127.0.0.1 25565 >/dev/null 2>&1 || true ) &
wait $SRV || true
cp m1-metrics.txt "$ROOT/machine/raw/M3WG-shadow-metrics-final.txt" 2>/dev/null
echo "shadow run complete"
