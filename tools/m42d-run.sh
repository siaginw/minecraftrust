#!/usr/bin/env bash
# M4.2C — ONE bounded clean-Forge Target-A SHADOW revalidation on the fixed build.
# Unique run dir (refuses to overwrite); Java authoritative; Java transmits every
# packet; comparator read-only; stop-and-preserve on first unexplained event.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="M42D-$TS"
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/$RUNID-run"

# refuse to overwrite any existing artifact
for f in "$RUN" "$ROOT/machine/raw/$RUNID-meta.txt" "$ROOT/machine/raw/$RUNID-metrics.txt" \
         "$ROOT/machine/raw/$RUNID-server.log"; do
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
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -c1-16)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -c1-16)"
  echo "timestamp=$TS"
  echo "mode=worldgen-SHADOW+m4.coherency+m4.packet_compare-SHADOW"
} > "$ROOT/machine/raw/$RUNID-meta.txt"

# Workload: bot stays in a small span (chunks leave/re-enter view => full-chunk
# resends of mutated chunks); commanded mutations near spawn.
( sleep 80
  echo "say M42D mutations batch 1 (blocks + light emitters)"
  echo "setblock 8 70 8 minecraft:glowstone"
  echo "fill -20 63 -20 20 68 20 minecraft:stone hollow"
  echo "setblock 30 70 -30 minecraft:sea_lantern"
  echo "setblock 0 80 0 minecraft:glowstone"
  echo "setblock 0 80 0 minecraft:air"
  echo "setblock 12 200 12 minecraft:stone"      # empty->nonempty section (y=200)
  sleep 200
  echo "say M42D mutations batch 2 (light changes AFTER first sync)"
  echo "setblock -8 64 -8 minecraft:torch"
  echo "fill -30 63 -30 -25 63 -25 minecraft:glowstone"
  echo "setblock 30 70 -30 minecraft:air"
  sleep 60
  echo "say M42D batch 3 (remove emitters — light regression)"
  echo "setblock 8 70 8 minecraft:air"
  echo "setblock -8 64 -8 minecraft:air"
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
  > "run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$ROOT/machine/raw/$RUNID-meta.txt"

for i in $(seq 1 90); do
  sleep 3; grep -q "Done (" "run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { echo "BOOT FAILED pid=$SRV"; tail -30 "run-$RUNID.log" >> "$ROOT/machine/raw/$RUNID-meta.txt"; exit 1; }
done
grep -q "Done (" "run-$RUNID.log" || { echo "BOOT TIMEOUT pid=$SRV"; kill $SRV; exit 1; }
echo "BOOTED pid=$SRV — small-span walk"
sleep 10

( M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=m2cebot \
    M1_TP=30 M1_TP_INTERVAL=3 M1_TP_STEP=150 M1_TP_Z_STEP=90 M1_TP_SPAN=900 \
    HOLD_SECONDS=420 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m42d 127.0.0.1 25565 >/dev/null 2>&1 || true ) &

wait $SRV || true
RC=$?
echo "server_exit=$RC pid=$SRV" >> "$ROOT/machine/raw/$RUNID-meta.txt"
# pre-shutdown retention is captured inside the final metrics dump (exit hook).
cp m1-metrics.txt "$ROOT/machine/raw/$RUNID-metrics.txt" 2>/dev/null
cp "run-$RUNID.log" "$ROOT/machine/raw/$RUNID-server.log" 2>/dev/null
echo "RUN COMPLETE run_id=$RUNID"
