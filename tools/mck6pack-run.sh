#!/usr/bin/env bash
# M-CK6P — AUTHORITATIVE frame-engine campaign on a MODPACK (Target C =
# FTB Revelation 3.4.0 / Forge .2846; Target D = SevTech Ages 3.2.3 / .2860).
# Same FrameAuthorityHandler boundary proven on clean Forge (java serialization
# authoritative; rust owns compression+framing only; complete-frame-or-nothing;
# fail-closed to the untouched Java path). Explicit operator opt-in
# -Dminecraftrust.frame_authority=ON_EXPERIMENTAL (default OFF). In-place pack
# run (install/config untouched; minimal console mutation batch only).
# Usage: mck6pack-run.sh <C|D> <A|B|C>
#   A: 1 connection, 25s hold (wire viability proof)
#   B: representative session (walk 15 hops + mutation batch)
#   C: bounded multi-session confirmation (walk + 3 reconnect sessions)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY=python
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
PACK="${1:?C|D}"; PHASE="${2:?A|B|C}"
TS=$(date +%Y%m%d-%H%M%S)
case "$PACK" in
  C) DIR="$ROOT/machine/targetC/server"; JAR=forge-1.12.2-14.23.5.2846-universal.jar; PORT=25567; MODIDS=machine/targetC/mod-ids.json; HEAP=-Xmx6G ;;
  D) DIR="$ROOT/machine/targetD/server"; JAR=forge-1.12.2-14.23.5.2860.jar;        PORT=25568; MODIDS=machine/targetD/mod-ids.json; HEAP=-Xmx6G ;;
  *) echo bad pack; exit 2;;
esac
RUNID="MCK6P-$PACK$PHASE-$TS"
OUT="$ROOT/machine/raw"
for f in "$OUT/$RUNID-meta.txt" "$OUT/$RUNID-metrics.txt" "$OUT/$RUNID-server.log" "$OUT/$RUNID-client.jsonl"; do
  [ -e "$f" ] && { echo "REFUSING: $f exists"; exit 3; }
done
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/$RUNID-campaign.log"; }
srv_pids() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*minecraftrust.campaign=MCK6P*' } | Select-Object -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r\n '
}
kill_srv() {
  for i in $(seq 1 20); do
    P="$(srv_pids)"; [ -z "$P" ] && return 0
    for pid in $P; do taskkill //F //PID "$pid" >/dev/null 2>&1 || true; done
    sleep 2
  done
}
P="$(srv_pids)"; [ -n "$P" ] && { log "FATAL: stale campaign JVM pid=$P"; exit 1; }
netstat -ano | grep -qE ":$PORT\s.*LISTENING" && { log "FATAL: port $PORT listening"; exit 1; }

cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$DIR/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$DIR/rustcraft_ffi.dll" 2>/dev/null || log "WARN dll busy (retained copy; dll unchanged)"
{
  echo "run_id=$RUNID pack=$PACK phase=$PHASE"
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -d' ' -f1)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -d' ' -f1)"
  echo "timestamp=$TS mode=frame_authority=ON_EXPERIMENTAL (in-place pack)"
} > "$OUT/$RUNID-meta.txt"
log "MCK6P start $RUNID"

transcript_ok() {
  $PY - "$1" "$2" "$3" "$4" <<'PYEOF'
import json, sys
path, want_sess, min_chunks, max_err = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
try: rows = [json.loads(l) for l in open(path, encoding="utf-8")]
except FileNotFoundError: sys.exit(1)
if len(rows) != want_sess: sys.exit(1)
if sum(r.get("errors", 0) for r in rows) > max_err: sys.exit(1)
if sum(r.get("chunk_data", 0) for r in rows) < min_chunks: sys.exit(1)
sys.exit(0)
PYEOF
}
wait_ready() {
  sleep 25
  for a in $(seq 1 14); do
    rm -f "$DIR/client-p6probe.jsonl"
    env M1_MODLIST="$ROOT/$MODIDS" HOLD_SECONDS=12 SESSIONS=1 TARGETA_OUT_DIR="$DIR" \
      $PY "$ROOT/tools/targeta_client.py" p6probe 127.0.0.1 $PORT >/dev/null 2>&1 || true
    transcript_ok "$DIR/client-p6probe.jsonl" 1 1 0 && { log "logins accepted (probe $a)"; return 0; }
    sleep 15
  done
  return 1
}

case "$PHASE" in
  A) WINDOW=260; MUT_AT=0; MUTS="";;
  B) WINDOW=420; MUT_AT=280; MUTS="1";;
  C) WINDOW=520; MUT_AT=300; MUTS="1";;
  *) echo bad phase; exit 2;;
esac

cd "$DIR"
( if [ -n "$MUTS" ]; then sleep $MUT_AT; echo "say MCK6P mutation batch"; echo "setblock 100 70 100 minecraft:glowstone"; echo "setblock -60 64 -60 minecraft:sea_lantern"; fi
  sleep $((WINDOW - MUT_AT - 90)); echo "stop"; sleep 90 ) \
| "$JAVA" $HEAP -Djava.library.path=. \
  -Dminecraftrust.campaign=MCK6P \
  -Dminecraftrust.native_chunk_packet=OFF -Dminecraftrust.worldgen=OFF \
  -Dminecraftrust.native_compress=OFF -Dminecraftrust.frame_shadow=OFF \
  -Dminecraftrust.frame_authority=ON_EXPERIMENTAL \
  -Dminecraftrust.frame_authority.crosscheck=16 \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar "$JAR" nogui > "run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$OUT/$RUNID-meta.txt"
for i in $(seq 1 240); do
  sleep 3; grep -q "Done (" "run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { log "BOOT FAILED"; tail -40 "run-$RUNID.log" >> "$OUT/$RUNID-meta.txt"; kill_srv; exit 1; }
done
grep -q "Done (" "run-$RUNID.log" || { log "BOOT TIMEOUT"; kill_srv; exit 1; }
log "booted pack=$PACK phase=$PHASE pid=$SRV"
wait_ready || { log "STOP: never accepted logins"; kill_srv; cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null; cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null; exit 2; }

case "$PHASE" in
  A) CLIENTS="HOLD_SECONDS=25 SESSIONS=1";;
  B) CLIENTS="M1_TP=15 M1_TP_INTERVAL=5 M1_TP_STEP=128 M1_TP_SPAN=900 HOLD_SECONDS=110 SESSIONS=1";;
  C) CLIENTS="M1_TP=15 M1_TP_INTERVAL=5 M1_TP_STEP=128 M1_TP_SPAN=900 HOLD_SECONDS=110 SESSIONS=1";;
esac
rm -f client-p6a.jsonl client-p6sess.jsonl
( eval "M1_MODLIST=\"$ROOT/$MODIDS\" $CLIENTS" TARGETA_OUT_DIR="$DIR" \
    $PY "$ROOT/tools/targeta_client.py" p6a 127.0.0.1 $PORT > client-p6a-stdout.txt 2>&1 || true ) &
if [ "$PHASE" = "C" ]; then
  ( sleep 40
    env M1_MODLIST="$ROOT/$MODIDS" M1_USER=p6bot HOLD_SECONDS=20 SESSIONS=3 TARGETA_OUT_DIR="$DIR" \
      $PY "$ROOT/tools/targeta_client.py" p6sess 127.0.0.1 $PORT > client-p6sess-stdout.txt 2>&1 || true ) &
fi
wait $SRV || true
cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null
cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
cat client-p6a.jsonl client-p6sess.jsonl > "$OUT/$RUNID-client.jsonl" 2>/dev/null
log "RUN COMPLETE run_id=$RUNID"
grep -aE "mck6auth" "$OUT/$RUNID-metrics.txt" | tail -8
