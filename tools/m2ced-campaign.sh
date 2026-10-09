#!/usr/bin/env bash
# M2CED SevTech ON_EXPERIMENTAL bounded campaign (operator authorization
# 2026-09-20). SevTech Ages 3.2.3 / Target D ONLY — final modpack ON rung.
# M1 stays OFF; M2-PERSIST parked; default mode NOT changed (stop boundary).
# Workload mirrors M2CC-D SHADOW (perfbotb corridor walk) plus the reconnect
# batch that the M2CC window expiry prevented (15 sessions; 8 paranoid).
# Readiness-gated (M2CER lesson: modpack logins at Done+2s are rejected);
# per-phase transcript validation; targeted server-JVM kill only.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PY=python
TD="$ROOT/machine/targetD"
TA="$ROOT/machine/targetA"
MARK="$ROOT/machine/raw/M2CED-campaign-marker.txt"
SRVMARK="forge-1.12.2-14.23.5.2860.jar"
HOSTPORT="127.0.0.1 25568"

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
    rm -f "$TA/client-mced-probe.jsonl"
    env M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" HOLD_SECONDS=12 SESSIONS=1 $PY tools/targeta_client.py mced-probe $HOSTPORT >/dev/null 2>&1 || true
    if transcript_ok "$TA/client-mced-probe.jsonl" 1 1 0; then
      log "server accepting logins (probe attempt $attempt)"
      return 0
    fi
    sleep 15
  done
  return 1
}

# ---- pre-flight ----
P="$(srv_pids)"
if [ -n "$P" ]; then log "FATAL: stale server JVM pid=$P matching '$SRVMARK' (Target A shares this jar name - verify it is Target D before proceeding)"; exit 1; fi
if netstat -ano | grep -qE ":25568\s.*LISTENING"; then log "FATAL: port 25568 already listening"; exit 1; fi
rm -f "$MARK"
log "M2CED campaign start (attempt 3, modlist on ALL client invocations): run1=ON normal, run2=ON paranoid; coremod c975fc1c, dll 0acb7469"

# ================= RUN 1: ON_EXPERIMENTAL normal =================
log "RUN1 launching SevTech server (ON_EXPERIMENTAL, window 640s)"
bash tools/m2cc-pack-run.sh D ON_EXPERIMENTAL mced-d-on1 640 &
SRV=$!
for i in $(seq 1 200); do
  grep -q "Done (" "$TD/server/run-mced-d-on1.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TD/server/run-mced-d-on1.log" 2>/dev/null; then
  log "FATAL: RUN1 boot timeout"; kill_srv; exit 1
fi
log "RUN1 booted; waiting for login readiness"
if ! wait_ready; then
  log "FATAL: RUN1 server never accepted logins"; kill_srv; exit 1
fi
log "RUN1 phase A: corridor walk (perfbotb, M2CC-D-mirrored TP workload)"
if ! M1_USER=perfbotb M1_TP=64 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_TP_SPAN=600 \
     M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" \
     HOLD_SECONDS=260 SESSIONS=1 $PY tools/targeta_client.py mced-d-on1-walk $HOSTPORT; then
  log "STOP-CONDITION: RUN1 walk client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mced-d-on1-walk.jsonl" 1 5000 0; then
  log "STOP-CONDITION: RUN1 walk transcript invalid (decode errors or low chunk volume); aborting"
  kill_srv; exit 2
fi
log "RUN1 phase A validated; phase B: 15 login/logout sessions (8s hold)"
if ! M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" HOLD_SECONDS=8 SESSIONS=15 $PY tools/targeta_client.py mced-d-on1 $HOSTPORT; then
  log "STOP-CONDITION: RUN1 session client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mced-d-on1.jsonl" 15 3000 0; then
  log "STOP-CONDITION: RUN1 session transcript invalid; aborting"
  kill_srv; exit 2
fi
log "RUN1 client phases complete and validated; waiting for server window to close"
wait $SRV || true
cp "$TD/server/m1-metrics.txt" "$ROOT/machine/raw/M2CED-on1-metrics-final.txt"
log "RUN1 done; metrics snapshot saved"
kill_srv

# ================= RUN 2: ON_EXPERIMENTAL paranoid =================
log "RUN2 launching SevTech server (ON_EXPERIMENTAL + paranoid leak detection, window 320s)"
bash tools/m2cc-pack-run.sh D ON_EXPERIMENTAL mced-d-on2 320 paranoid &
SRV=$!
for i in $(seq 1 200); do
  grep -q "Done (" "$TD/server/run-mced-d-on2.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TD/server/run-mced-d-on2.log" 2>/dev/null; then
  log "FATAL: RUN2 boot timeout"; kill_srv; exit 1
fi
log "RUN2 booted; waiting for login readiness"
if ! wait_ready; then
  log "FATAL: RUN2 server never accepted logins"; kill_srv; exit 1
fi
log "RUN2 server accepting; 8 login/logout sessions (8s hold)"
if ! M1_MODLIST="$ROOT/machine/targetD/mod-ids.json" HOLD_SECONDS=8 SESSIONS=8 $PY tools/targeta_client.py mced-d-on2-par $HOSTPORT; then
  log "STOP-CONDITION: RUN2 client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mced-d-on2-par.jsonl" 8 2000 0; then
  log "STOP-CONDITION: RUN2 transcript invalid; aborting"
  kill_srv; exit 2
fi
log "RUN2 client phases complete and validated; waiting for server window to close"
wait $SRV || true
cp "$TD/server/m1-metrics.txt" "$ROOT/machine/raw/M2CED-on2-metrics-final.txt"
log "RUN2 done; metrics snapshot saved"
kill_srv

log "M2CED campaign complete"
