#!/usr/bin/env bash
# M-CK5-LIVE — ONE bounded clean-Forge Target-A SHADOW-OBSERVER validation run.
# Unique run dir (refuses to overwrite); Java authoritative; Java transmits every
# packet; the observer only COPIES flowing bytes and DISCARDS all native output.
# Defaults stay OFF; this script opts IN via -Dminecraftrust.frame_shadow=LIVE.
# Fresh disposable world per run; stop-and-preserve on any failure.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="MCK5L-$TS"
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/$RUNID-run"

for f in "$RUN" "$ROOT/machine/raw/$RUNID-meta.txt" "$ROOT/machine/raw/$RUNID-metrics.txt" \
         "$ROOT/machine/raw/$RUNID-server.log" "$ROOT/machine/raw/$RUNID-client.jsonl"; do
  if [ -e "$f" ]; then echo "REFUSING: $f already exists"; exit 3; fi
done
mkdir -p "$RUN"
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
rm -rf "$RUN/world"
cd "$RUN"

{
  echo "run_id=$RUNID"
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -d' ' -f1)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -d' ' -f1)"
  echo "timestamp=$TS"
  echo "mode=frame_shadow-LIVE (observer only; everything else OFF; Java authoritative)"
} > "$ROOT/machine/raw/$RUNID-meta.txt"

# Bounded console schedule: two block-mutation batches (block_change + light ->
# chunk resends = compressed traffic), then stop. ~5 minutes total.
( sleep 20
  echo "say MCK5L observer campaign start"
  sleep 25
  echo "say MCK5L mutations batch 1"
  echo "setblock 8 70 8 minecraft:glowstone"
  echo "setblock 30 70 -30 minecraft:sea_lantern"
  echo "setblock 0 80 0 minecraft:glowstone"
  sleep 60
  echo "say MCK5L mutations batch 2"
  echo "fill -20 63 -20 20 68 20 minecraft:stone hollow"
  echo "setblock -8 64 -8 minecraft:torch"
  sleep 120
  echo "say MCK5L stopping"
  sleep 2
  echo "stop"
  sleep 30
) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.worldgen=OFF \
  -Dminecraftrust.native_compress=OFF \
  -Dminecraftrust.frame_shadow=LIVE \
  -Dminecraftrust.frame_shadow.every=2 \
  -Dminecraftrust.frame_shadow.maxcomparisons=5000 \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$ROOT/machine/raw/$RUNID-meta.txt"

for i in $(seq 1 90); do
  sleep 3; grep -q "Done (" "run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { echo "BOOT FAILED pid=$SRV"; tail -40 "run-$RUNID.log" >> "$ROOT/machine/raw/$RUNID-meta.txt"; exit 1; }
done
grep -q "Done (" "run-$RUNID.log" || { echo "BOOT TIMEOUT pid=$SRV"; kill $SRV; exit 1; }
echo "BOOTED pid=$SRV"
sleep 10

# Two sequential login/play/disconnect sessions (reconnect lifecycle, two
# channels), teleport exploration for chunk traffic + natural small packets.
( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=m2cebot \
    M1_TP=12 M1_TP_INTERVAL=5 M1_TP_STEP=120 M1_TP_Z_STEP=60 M1_TP_SPAN=300 \
    HOLD_SECONDS=90 SESSIONS=2 \
    TARGETA_OUT_DIR="$RUN" \
    python "$ROOT/tools/targeta_client.py" mck5l 127.0.0.1 25565 > "$RUN/client-stdout.txt" 2>&1 || true ) &

wait $SRV || true
RC=$?
echo "server_exit=$RC" >> "$ROOT/machine/raw/$RUNID-meta.txt"
cp m1-metrics.txt "$ROOT/machine/raw/$RUNID-metrics.txt" 2>/dev/null
cp "run-$RUNID.log" "$ROOT/machine/raw/$RUNID-server.log" 2>/dev/null
cp "$RUN/client-mck5l.jsonl" "$ROOT/machine/raw/$RUNID-client.jsonl" 2>/dev/null
cp "$RUN/client-stdout.txt" "$ROOT/machine/raw/$RUNID-client-stdout.txt" 2>/dev/null
echo "RUN COMPLETE run_id=$RUNID"
grep -E "mck5live|frameShadowHook" "$ROOT/machine/raw/$RUNID-metrics.txt" | tail -10
