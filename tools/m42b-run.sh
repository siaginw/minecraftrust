#!/usr/bin/env bash
# M4.2B — ONE bounded Target-A SHADOW run: live native-snapshot packet correctness.
# worldgen SHADOW registers chunks; coherency hooks track mutations; the packet
# comparator compares the native encoder against every REAL SPacketChunkData the
# server builds (Gate A bytes / Gate B decoded states). Java packets remain the
# transmitted output. Unique run ID + server PID recorded; no auto-rerun.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="M42B-$TS"
LABEL=$RUNID
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/m42b-run"

rm -rf "$RUN"; mkdir -p "$RUN"
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
rm -rf "$RUN/world"
cd "$RUN"

echo "run_id=$RUNID" > "$ROOT/machine/raw/$RUNID-meta.txt"
echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -c1-16)" >> "$ROOT/machine/raw/$RUNID-meta.txt"
echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -c1-16)" >> "$ROOT/machine/raw/$RUNID-meta.txt"

# Mutation schedule: near spawn (loaded + registered); bot stays in a small span
# so chunks leave/re-enter view => full-chunk RESENDS after mutation.
( sleep 80
  echo "say M42B mutations batch 1"
  echo "setblock 8 70 8 minecraft:glowstone"
  echo "fill -20 63 -20 20 68 20 minecraft:stone hollow"
  echo "setblock 30 70 -30 minecraft:sea_lantern"
  echo "setblock 0 80 0 minecraft:glowstone"
  echo "setblock 0 80 0 minecraft:air"
  sleep 260
  echo "say M42B mutations batch 2"
  echo "setblock -8 64 -8 minecraft:torch"
  echo "fill -30 63 -30 -25 63 -25 minecraft:glowstone"
  sleep 120
  echo "stop"
  sleep 60
) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.worldgen_shadow=SHADOW \
  -Dminecraftrust.m4.coherency=true \
  -Dminecraftrust.m4.packet_compare=SHADOW \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$ROOT/machine/raw/$RUNID-meta.txt"

for i in $(seq 1 90); do
  sleep 3; grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { echo "BOOT FAILED pid=$SRV"; tail -30 "run-$LABEL.log"; exit 1; }
done
grep -q "Done (" "run-$LABEL.log" || { echo "BOOT TIMEOUT pid=$SRV"; kill $SRV; exit 1; }
echo "BOOTED pid=$SRV — small-span walk (keeps spawn chunks in/out of view for resends)"
sleep 10

( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=m2cebot \
    M1_TP=30 M1_TP_INTERVAL=3 M1_TP_STEP=150 M1_TP_Z_STEP=90 M1_TP_SPAN=900 \
    HOLD_SECONDS=420 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m42b 127.0.0.1 25565 >/dev/null 2>&1 || true ) &

wait $SRV || true
RC=$?
echo "server_exit=$RC pid=$SRV" >> "$ROOT/machine/raw/$RUNID-meta.txt"
cp m1-metrics.txt "$ROOT/machine/raw/$RUNID-metrics.txt" 2>/dev/null
cp "run-$LABEL.log" "$ROOT/machine/raw/$RUNID-server.log" 2>/dev/null
echo "RUN COMPLETE run_id=$RUNID — verdict from machine/raw/$RUNID-metrics.txt (m42c_* lines)"
