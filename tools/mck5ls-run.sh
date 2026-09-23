#!/usr/bin/env bash
# M-CK5-LS — ONE bounded SevTech Ages (Target D, 3.2.3 / Forge 14.23.5.2860,
# 262 mods) SHADOW-OBSERVER live validation run. Same read-only observer as
# Target A / Revelation (pairing-hardening v2 semantics preserved). In-place
# pack server (install/world/config untouched); -Dminecraftrust.frame_shadow=LIVE
# opts IN (default OFF); Java authoritative for every packet; rust output
# discarded. Unique run artifacts; refuse to overwrite; marker-matched JVM kill
# only (-Dminecraftrust.campaign=MCK5LS — the jar NAME collides with Target A,
# so the marker property is the unique PID selector).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY=python
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
TD="$ROOT/machine/targetD/server"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="MCK5LS-$TS"
MARK="-Dminecraftrust.campaign=MCK5LS"
HOSTPORT="127.0.0.1 25568"
OUT="$ROOT/machine/raw"

for f in "$OUT/$RUNID-meta.txt" "$OUT/$RUNID-metrics.txt" "$OUT/$RUNID-server.log" \
         "$OUT/$RUNID-client.jsonl" "$OUT/$RUNID-campaign.log"; do
  [ -e "$f" ] && { echo "REFUSING: $f exists"; exit 3; }
done

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/$RUNID-campaign.log"; }

srv_pids() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*minecraftrust.campaign=MCK5LS*' } | Select-Object -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r\n '
}
kill_srv() {
  for i in $(seq 1 20); do
    P="$(srv_pids)"; [ -z "$P" ] && return 0
    for pid in $P; do taskkill //F //PID "$pid" >/dev/null 2>&1 || true; done
    sleep 2
  done
}

# ---- pre-flight ----
P="$(srv_pids)"
[ -n "$P" ] && { log "FATAL: stale SevTech JVM pid=$P"; exit 1; }
netstat -ano | grep -qE ":25568\s.*LISTENING" && { log "FATAL: port 25568 listening"; exit 1; }

cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$TD/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$TD/rustcraft_ffi.dll" 2>/dev/null || log "WARN: dll copy busy (previous copy retained; dll unchanged this checkpoint)"

{
  echo "run_id=$RUNID"
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -d' ' -f1)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -d' ' -f1)"
  echo "timestamp=$TS"
  echo "pack=SevTech Ages 3.2.3 forge-14.23.5.2860 (262 mods) in-place; mode=frame_shadow-LIVE observer-only; everything else OFF"
} > "$OUT/$RUNID-meta.txt"
log "MCK5LS campaign start $RUNID"

# run IN the pack dir (in-place, m2cc-pack-run.sh pattern) — the server
# inherits THIS cwd at spawn (the two earlier missing-cd harness defects)
cd "$TD"

transcript_ok() {
  $PY - "$1" "$2" "$3" "$4" <<'PYEOF'
import json, sys
path, want_sess, min_chunks, max_err = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
try:
    rows = [json.loads(l) for l in open(path, encoding="utf-8")]
except FileNotFoundError:
    sys.exit(1)
if len(rows) != want_sess: sys.exit(1)
if sum(r.get("errors", 0) for r in rows) > max_err: sys.exit(1)
if sum(r.get("chunk_data", 0) for r in rows) < min_chunks: sys.exit(1)
sys.exit(0)
PYEOF
}

wait_ready() {
  sleep 30
  for attempt in $(seq 1 14); do
    rm -f "$TD/client-mck5ls-probe.jsonl"
    env M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" HOLD_SECONDS=12 SESSIONS=1 TARGETA_OUT_DIR="$TD" \
      $PY "$ROOT/tools/targeta_client.py" mck5ls-probe $HOSTPORT >/dev/null 2>&1 || true
    if transcript_ok "$TD/client-mck5ls-probe.jsonl" 1 1 0; then
      log "server accepting logins (probe attempt $attempt)"
      return 0
    fi
    sleep 15
  done
  return 1
}

# Bounded console schedule (~800s window): exploration-period block mutations,
# then stop.
( sleep 480
  echo "say MCK5LS mutation batch (block + light traffic)"
  echo "setblock 100 70 100 minecraft:glowstone"
  echo "setblock -60 64 -60 minecraft:sea_lantern"
  echo "fill -10 63 -10 10 66 10 minecraft:stone hollow"
  sleep 300
  echo "say MCK5LS stopping"
  echo "stop"
  sleep 90
) | "$JAVA" -Xmx6G -Djava.library.path=. \
  $MARK \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.worldgen=OFF \
  -Dminecraftrust.native_compress=OFF \
  -Dminecraftrust.frame_shadow=LIVE \
  -Dminecraftrust.frame_shadow.every=2 \
  -Dminecraftrust.frame_shadow.maxcomparisons=6000 \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2860.jar nogui \
  > "$TD/run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$OUT/$RUNID-meta.txt"

for i in $(seq 1 240); do
  sleep 3; grep -q "Done (" "run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { log "BOOT FAILED pid=$SRV"; tail -40 "run-$RUNID.log" >> "$OUT/$RUNID-meta.txt"; kill_srv; exit 1; }
done
grep -q "Done (" "run-$RUNID.log" || { log "BOOT TIMEOUT"; kill_srv; exit 1; }
log "SevTech booted pid=$SRV; waiting for login readiness"

if ! wait_ready; then
  log "STOP-CONDITION: server never accepted logins"; kill_srv
  cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null
  cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
  exit 2
fi

# Phase A: perfbotb corridor walk (chunk + movement + modded payload/TE traffic)
log "Phase A: corridor walk session (perfbotb, 40 hops)"
rm -f client-mck5ls-walk.jsonl
if ! env M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" M1_USER=perfbotb \
     M1_TP=40 M1_TP_INTERVAL=5 M1_TP_STEP=96 M1_TP_Z_STEP=48 M1_TP_SPAN=2400 \
     HOLD_SECONDS=210 SESSIONS=1 TARGETA_OUT_DIR="$TD" \
     $PY "$ROOT/tools/targeta_client.py" mck5ls-walk $HOSTPORT > client-mck5ls-walk-stdout.txt 2>&1; then
  log "STOP-CONDITION: walk client failed"; kill_srv
  cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null
  cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
  cp client-mck5ls-walk.jsonl "$OUT/$RUNID-client.jsonl" 2>/dev/null
  exit 2
fi
transcript_ok client-mck5ls-walk.jsonl 1 30 0 || { log "STOP-CONDITION: walk transcript invalid (<30 chunks or errors)"; kill_srv; exit 2; }
log "Phase A complete"

# Phase B: reconnect/disconnect sessions (handshake + small packets)
log "Phase B: 3 reconnect sessions (mck5lsbot)"
rm -f client-mck5ls-sess.jsonl
env M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" M1_USER=mck5lsbot \
  HOLD_SECONDS=25 SESSIONS=3 TARGETA_OUT_DIR="$TD" \
  $PY "$ROOT/tools/targeta_client.py" mck5ls-sess $HOSTPORT > client-mck5ls-sess-stdout.txt 2>&1 || true
transcript_ok client-mck5ls-sess.jsonl 3 3 0 || log "WARN: session transcript below target (recorded as-is)"

log "client phases complete; waiting for server window to close"
wait $SRV || true
cp m1-metrics.txt "$OUT/$RUNID-metrics.txt" 2>/dev/null
cp "run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
cat client-mck5ls-walk.jsonl client-mck5ls-sess.jsonl > "$OUT/$RUNID-client.jsonl" 2>/dev/null
log "RUN COMPLETE run_id=$RUNID"
grep -E "mck5live|frameShadowHook" "$OUT/$RUNID-metrics.txt" | tail -12
