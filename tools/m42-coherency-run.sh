#!/usr/bin/env bash
# M4.2A E — ONE bounded Target-A clean-Forge SHADOW check with REAL mutations.
# worldgen SHADOW registers native chunks; ChunkMutationTransformer records
# Java-side block/light mutations (pure Java); M4Coherency flush refreshes +
# decoder-validates; console setblock/fill commands mutate walked chunks.
# Stop conditions: any m42_validation_mismatch > 0 or m42_refresh_disabled=true.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
LABEL=m42-coherency
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/m42-run"

rm -rf "$RUN"; mkdir -p "$RUN"
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
rm -rf "$RUN/world"
cd "$RUN"

# Command schedule: after boot+walk window, mutate walked (registered) chunks:
# block place/break, a fill burst (population-like), light emitters, light removal.
( sleep 80
  echo "say M42 mutations batch 1 (spawn, early)"
  echo "setblock 8 70 8 minecraft:glowstone"
  echo "fill -20 63 -20 20 68 20 minecraft:stone hollow"
  echo "setblock 200 70 200 minecraft:sea_lantern"
  sleep 320
  echo "say M42 mutations batch 2 (late)"
  echo "setblock -8 64 -8 minecraft:stone"
  echo "setblock 0 80 0 minecraft:glowstone"
  echo "setblock 0 80 0 minecraft:air"
  echo "setblock 100 70 100 minecraft:torch"
  echo "setblock 8 70 8 minecraft:glowstone"
  echo "setblock -8 64 -8 minecraft:stone"
  echo "fill -20 63 -20 20 68 20 minecraft:stone hollow"
  echo "setblock 0 80 0 minecraft:glowstone"
  echo "setblock 0 80 0 minecraft:air"
  echo "setblock 100 70 100 minecraft:sea_lantern"
  echo "setblock -100 70 -100 minecraft:torch"
  sleep 90
  echo "stop"
  sleep 60
) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Dminecraftrust.m4.coherency=true \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!

for i in $(seq 1 90); do
  sleep 3; grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { echo "BOOT FAILED"; tail -30 "run-$LABEL.log"; exit 1; }
done
grep -q "Done (" "run-$LABEL.log" || { echo "BOOT TIMEOUT"; kill $SRV; exit 1; }
echo "BOOTED — starting TP walk"
sleep 10

( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=m2cebot \
    M1_TP=30 M1_TP_INTERVAL=2 M1_TP_STEP=200 M1_TP_SPAN=6000 \
    HOLD_SECONDS=380 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m42-coh 127.0.0.1 25565 >/dev/null 2>&1 || true ) &

wait $SRV || true
cp m1-metrics.txt "$ROOT/machine/raw/M42-coherency-metrics.txt" 2>/dev/null
cp "run-$LABEL.log" "$ROOT/machine/raw/M42-coherency-server.log" 2>/dev/null
echo "RUN COMPLETE — verdict from machine/raw/M42-coherency-metrics.txt (m42_* / m4_stats_* lines)"
