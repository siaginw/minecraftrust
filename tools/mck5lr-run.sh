#!/usr/bin/env bash
# M-CK5-LR — ONE bounded Revelation (Target C, FTB Revelation 3.4.0 /
# Forge 14.23.5.2846) SHADOW-OBSERVER live validation run.
# In-place pack server (install/world/config untouched — established M2CC
# practice); read-only observer via -Dminecraftrust.frame_shadow=LIVE (default
# OFF); Java authoritative for every packet; rust output discarded.
# Unique run artifacts; refuse to overwrite; marker-matched JVM kill only.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY=python
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
TC="$ROOT/machine/targetC/server"
TS=$(date +%Y%m%d-%H%M%S)
RUNID="MCK5LR-$TS"
SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"
HOSTPORT="127.0.0.1 25567"
OUT="$ROOT/machine/raw"

for f in "$OUT/$RUNID-meta.txt" "$OUT/$RUNID-metrics.txt" "$OUT/$RUNID-server.log" \
         "$OUT/$RUNID-client.jsonl" "$OUT/$RUNID-campaign.log"; do
  [ -e "$f" ] && { echo "REFUSING: $f exists"; exit 3; }
done

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/$RUNID-campaign.log"; }

srv_pids() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$SRVMARK*' } | Select-Object -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r\n '
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
[ -n "$P" ] && { log "FATAL: stale Revelation JVM pid=$P"; exit 1; }
netstat -ano | grep -qE ":25567\s.*LISTENING" && { log "FATAL: port 25567 listening"; exit 1; }

cp "$ROOT/tools/dist/rustcraft-m1-coremod.jar" "$TC/mods/rustcraft-m1-coremod.jar"
cp "$ROOT/target/release/rustcraft_ffi.dll" "$TC/rustcraft_ffi.dll"

{
  echo "run_id=$RUNID"
  echo "dll=$(sha256sum "$ROOT/target/release/rustcraft_ffi.dll" | cut -d' ' -f1)"
  echo "coremod=$(sha256sum "$ROOT/tools/dist/rustcraft-m1-coremod.jar" | cut -d' ' -f1)"
  echo "timestamp=$TS"
  echo "pack=FTB Revelation 3.4.0 forge-14.23.5.2846 (196 mods) in-place; mode=frame_shadow-LIVE observer-only; everything else OFF"
} > "$OUT/$RUNID-meta.txt"
log "MCK5LR campaign start $RUNID"
cd "$TC"   # pack server runs in-place (m2cc-pack-run.sh pattern)

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
if sum(r.get("chunk_data", 0) for r in rows) < min_chunks: sys.exit(0 if False else 1)
sys.exit(0)
PYEOF
}

wait_ready() {
  sleep 30
  for attempt in $(seq 1 14); do
    rm -f "$TC/client-mck5lr-probe.jsonl"
    env M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" HOLD_SECONDS=12 SESSIONS=1 TARGETA_OUT_DIR="$TC" \
      $PY "$ROOT/tools/targeta_client.py" mck5lr-probe $HOSTPORT >/dev/null 2>&1 || true
    if transcript_ok "$TC/client-mck5lr-probe.jsonl" 1 1 0; then
      log "server accepting logins (probe attempt $attempt)"
      return 0
    fi
    sleep 15
  done
  return 1
}

# Bounded console schedule (~760s window): exploration-period block mutations
# (block/TE/light traffic incl. modded chunks), then stop.
( sleep 460
  echo "say MCK5LR mutation batch (block + light traffic)"
  echo "setblock 100 70 100 minecraft:glowstone"
  echo "setblock -60 64 -60 minecraft:sea_lantern"
  echo "fill -10 63 -10 10 66 10 minecraft:stone hollow"
  sleep 300
  echo "say MCK5LR stopping"
  echo "stop"
  sleep 90
) | "$JAVA" -Xmx6G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.worldgen=OFF \
  -Dminecraftrust.native_compress=OFF \
  -Dminecraftrust.frame_shadow=LIVE \
  -Dminecraftrust.frame_shadow.every=2 \
  -Dminecraftrust.frame_shadow.maxcomparisons=6000 \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar "$SRVMARK" nogui \
  > "$TC/run-$RUNID.log" 2>&1 &
SRV=$!
echo "server_pid=$SRV" >> "$OUT/$RUNID-meta.txt"

for i in $(seq 1 240); do
  sleep 3; grep -q "Done (" "$TC/run-$RUNID.log" 2>/dev/null && break
  kill -0 $SRV 2>/dev/null || { log "BOOT FAILED pid=$SRV"; tail -40 "$TC/run-$RUNID.log" >> "$OUT/$RUNID-meta.txt"; kill_srv; exit 1; }
done
grep -q "Done (" "$TC/run-$RUNID.log" || { log "BOOT TIMEOUT"; kill_srv; exit 1; }
log "Revelation booted pid=$SRV; waiting for login readiness"

if ! wait_ready; then
  log "STOP-CONDITION: server never accepted logins"; kill_srv
  cp "$TC/m1-metrics.txt" "$OUT/$RUNID-metrics.txt" 2>/dev/null
  cp "$TC/run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
  exit 2
fi

# Phase A: perfbotb corridor walk (chunk + movement + modded payload/TE traffic)
log "Phase A: corridor walk session (perfbotb, 60 hops)"
rm -f "$TC/client-mck5lr-walk.jsonl"
if ! env M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" M1_USER=perfbotb \
     M1_TP=60 M1_TP_INTERVAL=4 M1_TP_STEP=96 M1_TP_Z_STEP=48 M1_TP_SPAN=3000 \
     HOLD_SECONDS=210 SESSIONS=1 TARGETA_OUT_DIR="$TC" \
     $PY "$ROOT/tools/targeta_client.py" mck5lr-walk $HOSTPORT > "$TC/client-mck5lr-walk-stdout.txt" 2>&1; then
  log "STOP-CONDITION: walk client failed"; kill_srv
  cp "$TC/m1-metrics.txt" "$OUT/$RUNID-metrics.txt" 2>/dev/null
  cp "$TC/run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
  cp "$TC/client-mck5lr-walk.jsonl" "$OUT/$RUNID-client.jsonl" 2>/dev/null
  exit 2
fi
transcript_ok "$TC/client-mck5lr-walk.jsonl" 1 50 0 || { log "STOP-CONDITION: walk transcript invalid (<50 chunks or errors)"; kill_srv; exit 2; }
log "Phase A complete"

# Phase B: reconnect/disconnect sessions (handshake + small packets)
log "Phase B: 3 reconnect sessions (perfbota)"
rm -f "$TC/client-mck5lr-sess.jsonl"
env M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" M1_USER=perfbota \
  HOLD_SECONDS=25 SESSIONS=3 TARGETA_OUT_DIR="$TC" \
  $PY "$ROOT/tools/targeta_client.py" mck5lr-sess $HOSTPORT > "$TC/client-mck5lr-sess-stdout.txt" 2>&1 || true
transcript_ok "$TC/client-mck5lr-sess.jsonl" 3 3 0 || log "WARN: session transcript below target (recorded as-is)"

log "client phases complete; waiting for server window to close"
wait $SRV || true
cp "$TC/m1-metrics.txt" "$OUT/$RUNID-metrics.txt" 2>/dev/null
cp "$TC/run-$RUNID.log" "$OUT/$RUNID-server.log" 2>/dev/null
cat "$TC/client-mck5lr-walk.jsonl" "$TC/client-mck5lr-sess.jsonl" > "$OUT/$RUNID-client.jsonl" 2>/dev/null
log "RUN COMPLETE run_id=$RUNID"
grep -E "mck5live|frameShadowHook" "$OUT/$RUNID-metrics.txt" | tail -12
