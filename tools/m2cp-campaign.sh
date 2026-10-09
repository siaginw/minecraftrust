#!/usr/bin/env bash
# M2CP matched OFF/ON performance study on FTB Revelation / Target C
# (operator authorization 2026-09-20; the FINAL bounded M2-C study).
# 6 fresh-JVM runs, strictly alternating OFF_MEASURE,ON,OFF_MEASURE,ON,
# OFF_MEASURE,ON (3 per arm). Same world/bot workload/view-distance/JVM/
# flags/machine; only the M2C mode property differs. No dual compression:
# OFF_MEASURE = vanilla wire with timing brackets; ON = native authoritative.
# Workload = the M2CC/M2CER-validated perfbotb corridor walk (400s).
# Readiness-gated; per-phase transcript validation; targeted server-JVM kill.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PY=python
TC="$ROOT/machine/targetC"
TA="$ROOT/machine/targetA"
MARK="$ROOT/machine/raw/M2CP-campaign-marker.txt"
SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"
HOSTPORT="127.0.0.1 25567"
MODLIST="$ROOT/machine/targetC/mod-ids.json"
WINDOW=580

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$MARK"; }

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
  for attempt in $(seq 1 12); do
    rm -f "$TA/client-m2cp-probe.jsonl"
    env M1_MODLIST="$MODLIST" HOLD_SECONDS=12 SESSIONS=1 $PY tools/targeta_client.py m2cp-probe $HOSTPORT >/dev/null 2>&1 || true
    if transcript_ok "$TA/client-m2cp-probe.jsonl" 1 1 0; then
      log "  server accepting logins (probe attempt $attempt)"
      return 0
    fi
    sleep 15
  done
  return 1
}

one_run() { # $1 = MODE (OFF_MEASURE|ON_EXPERIMENTAL), $2 = LABEL
  local MODE="$1" LABEL="$2"
  log "RUN $LABEL start (m2c=$MODE, window ${WINDOW}s, fresh JVM)"
  bash tools/m2cp-run.sh "$MODE" "$LABEL" "$WINDOW" &
  local SRV=$!
  for i in $(seq 1 200); do
    grep -q "Done (" "$TC/server/run-$LABEL.log" 2>/dev/null && break
    sleep 2
  done
  if ! grep -q "Done (" "$TC/server/run-$LABEL.log" 2>/dev/null; then
    log "FATAL: $LABEL boot timeout"; kill_srv; exit 1
  fi
  if ! wait_ready; then
    log "FATAL: $LABEL never accepted logins"; kill_srv; exit 1
  fi
  log "  $LABEL: walk start (perfbotb, 400s deterministic corridor)"
  if ! M1_USER=perfbotb M1_TP=100 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_TP_SPAN=600 \
       M1_MODLIST="$MODLIST" HOLD_SECONDS=400 SESSIONS=1 \
       $PY tools/targeta_client.py "$LABEL-walk" $HOSTPORT; then
    log "STOP-CONDITION: $LABEL walk client crashed"; kill_srv; exit 2
  fi
  if ! transcript_ok "$TA/client-$LABEL-walk.jsonl" 1 15000 0; then
    log "STOP-CONDITION: $LABEL walk transcript invalid"; kill_srv; exit 2
  fi
  log "  $LABEL: walk validated; waiting for window close"
  wait $SRV || true
  cp "$TC/server/m1-metrics.txt" "$ROOT/machine/raw/M2CP-$LABEL-metrics-final.txt"
  cp "$TC/server/gc-$LABEL.log" "$ROOT/machine/raw/M2CP-$LABEL-gc.log" 2>/dev/null || log "  $LABEL: gc log missing"
  log "RUN $LABEL complete"
  kill_srv
}

P="$(srv_pids)"
if [ -n "$P" ]; then log "FATAL: stale Target-C JVM pid=$P"; exit 1; fi
if netstat -ano | grep -qE ":25567\s.*LISTENING"; then log "FATAL: port 25567 listening"; exit 1; fi
rm -f "$MARK"
log "M2CP matched study start: coremod 981354f5 (measurement build), dll 0acb7469, order OFF,ON,OFF,ON,OFF,ON"

one_run OFF_MEASURE   m2cp-c-o1
one_run ON_EXPERIMENTAL m2cp-c-n1
one_run OFF_MEASURE   m2cp-c-o2
one_run ON_EXPERIMENTAL m2cp-c-n2
one_run OFF_MEASURE   m2cp-c-o3
one_run ON_EXPERIMENTAL m2cp-c-n3

log "M2CP campaign complete"
