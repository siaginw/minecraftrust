#!/usr/bin/env bash
# M-CK6 — bounded AUTHORITATIVE frame-engine campaign on clean Forge Target A.
# PHASE: A (tiny: 1 session, 30s hold) | B (normal: 1 walk session + mutations) |
# C (confirmation: walk + 3 reconnect sessions).
# Mode: -Dminecraftrust.frame_authority=ON_EXPERIMENTAL (explicit operator opt-in;
# default OFF). Java remains authoritative for packet SERIALIZATION; Rust takes
# authority ONLY for compression+framing, fail-closed to the normal Java path.
# Fresh disposable world per run; unique artifacts; refuse to overwrite.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
PHASE="${1:?usage: mck6auth-run.sh <A|B|C>}"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="MCK6A-$PHASE-$TS"
SRC="$ROOT/machine/targetA/server"
RUN="$ROOT/machine/targetA/$RUNID-run"
OUT="$ROOT/machine/raw"

for f in "$OUT/$RUNID-meta.txt" "$OUT/$RUNID-metrics.txt" "$OUT/$RUNID-server.log" "$OUT/$RUNID-client.jsonl"; do
  [ -e "$f" ] && { echo "REFUSING: $f exists"; exit 3; }
done
mkdir -p "$RUN"
cp -r "$SRC/." "$RUN/" 2>/dev/null || exit 1
cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$RUN/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$RUN/rustcraft_ffi.dll"
rm -rf "$RUN/world"

{
  echo "run_id=$RUNID phase=$PHASE"
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -d' ' -f1)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -d' ' -f1)"
  echo "timestamp=$TS"
  echo "mode=frame_authority=ON_EXPERIMENTAL (rust framing/compression authority; java serialization authoritative; fail-closed)"
} > "$OUT/$RUNID-meta.txt"

case "$PHASE" in
  A) WINDOW=150; CLIENT_ENV="M1_USER=authbot HOLD_SECONDS=30 SESSIONS=1";;
  B) WINDOW=330; CLIENT_ENV="M1_USER=authbot M1_TP=20 M1_TP_INTERVAL=4 M1_TP_STEP=128 M1_TP_SPAN=600 HOLD_SECONDS=140 SESSIONS=1";;
  C) WINDOW=430; CLIENT_ENV="M1_USER=authbot M1_TP=24 M1_TP_INTERVAL=4 M1_TP_STEP=128 M1_TP_SPAN=800 HOLD_SECONDS=150 SESSIONS=1";;
  *) echo bad phase; exit 2;;
esac

# Phase C needs concurrent channels: raise max-players IN THE DISPOSABLE RUN
# COPY ONLY (the operator's source server.properties is never modified).
if [ "$PHASE" = "C" ]; then
  sed -i 's/^max-players=.*/max-players=4/' "$RUN/server.properties"
fi

cd "$RUN"
( sleep $((WINDOW - 120)); echo "setblock 8 70 8 minecraft:glowstone"; echo "fill -10 63 -10 10 66 10 minecraft:stone hollow"; sleep 90; echo "stop"; sleep 60 ) \
 | "$JAVA" -Xmx4G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.worldgen=OFF \
  -Dminecraftrust.native_compress=OFF \
  -Dminecraftrust.frame_shadow=OFF \
  -Dminecraftrust.frame_authority=ON_EXPERIMENTAL \
  -Dminecraftrust.frame_authority.crosscheck=16 \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$OUT/$RUNID-meta.txt"

for i in $(seq 1 90); do
  sleep 3; grep -q "Done (" "run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { echo "BOOT FAILED"; tail -40 "run-$RUNID.log" >> "$OUT/$RUNID-meta.txt"; exit 1; }
done
grep -q "Done (" "run-$RUNID.log" || { echo "BOOT TIMEOUT"; kill $SRV; exit 1; }
echo "BOOTED phase=$PHASE pid=$SRV"
sleep 12

( eval "M1_MODLIST=\"$ROOT/machine/targetA/mod-ids.json\" $CLIENT_ENV" \
    TARGETA_OUT_DIR="$RUN" \
    python "$ROOT/tools/targeta_client.py" mck6a-$PHASE 127.0.0.1 25565 > client-stdout.txt 2>&1 || true ) &

# Phase C: second, independent client process for reconnect-session channels
if [ "$PHASE" = "C" ]; then
  ( sleep 50
    env M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=authbot2 \
      HOLD_SECONDS=20 SESSIONS=3 TARGETA_OUT_DIR="$RUN" \
      python "$ROOT/tools/targeta_client.py" mck6a-C-sess 127.0.0.1 25565 > client-stdout-sess.txt 2>&1 || true ) &
fi

wait $SRV || true
cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null
cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
cat "$RUN"/client-mck6a-*.jsonl > "$OUT/$RUNID-client.jsonl" 2>/dev/null
echo "RUN COMPLETE run_id=$RUNID"
grep -aE "mck6auth|frameShadowHook" "$OUT/$RUNID-metrics.txt" | tail -12
