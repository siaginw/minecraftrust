#!/usr/bin/env bash
# M2CER Revelation ON_EXPERIMENTAL bounded campaign (operator authorization
# 2026-09-20). FTB Revelation 3.4.0 / Target C ONLY; SevTech NOT touched.
# Two runs: normal ON then paranoid ON. M1 stays OFF. Existing M2CC workload
# mirrored exactly (perfbotb corridor walk + 25 login sessions; 10 paranoid).
#
# Readiness lesson (attempt 1, preserved as *-failed1): on Revelation, logins
# at Done+2s are rejected ("Server is still starting!") while FML post-Done
# init (loot tables, WorldData, dims) completes - and a fast-failed client
# still exits 0. This driver therefore: settles 30s, PROBES with a real login
# until the server accepts, and validates every phase transcript (session
# count, 0 errors, chunk volume) before proceeding.
#
# Process discipline: only the Target-C server JVM is managed (matched by its
# command-line marker); the operator's unrelated JVMs are never touched.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PY=python
TC="$ROOT/machine/targetC"
TA="$ROOT/machine/targetA"
MARK="$ROOT/machine/raw/M2CER-campaign-marker.txt"
SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"
HOSTPORT="127.0.0.1 25567"

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

# transcript_ok <file> <expected_sessions> <min_total_chunks> <max_errors>
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

# wait_ready <runlabel>: settle then probe until a real login joins
wait_ready() {
  sleep 30
  for attempt in $(seq 1 12); do
    rm -f "$TA/client-mcer-probe.jsonl"
    HOLD_SECONDS=12 SESSIONS=1 $PY tools/targeta_client.py mcer-probe $HOSTPORT >/dev/null 2>&1 || true
    if transcript_ok "$TA/client-mcer-probe.jsonl" 1 1 0; then
      log "server accepting logins (probe attempt $attempt)"
      return 0
    fi
    sleep 15
  done
  return 1
}

# ---- pre-flight ----
P="$(srv_pids)"
if [ -n "$P" ]; then log "FATAL: stale Target-C server JVM pid=$P at campaign start"; exit 1; fi
if netstat -ano | grep -qE ":25567\s.*LISTENING"; then log "FATAL: port 25567 already listening"; exit 1; fi
rm -f "$MARK"
log "M2CER campaign start (attempt 2, readiness-gated): run1=ON normal, run2=ON paranoid; coremod c975fc1c, dll 0acb7469"

# ================= RUN 1: ON_EXPERIMENTAL normal =================
log "RUN1 launching Revelation server (ON_EXPERIMENTAL, window 960s)"
bash tools/m2cc-pack-run.sh C ON_EXPERIMENTAL mcer-c-on1 960 &
SRV=$!
for i in $(seq 1 240); do
  grep -q "Done (" "$TC/server/run-mcer-c-on1.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TC/server/run-mcer-c-on1.log" 2>/dev/null; then
  log "FATAL: RUN1 boot timeout"; kill_srv; exit 1
fi
log "RUN1 booted; waiting for login readiness"
if ! wait_ready; then
  log "FATAL: RUN1 server never accepted logins"; kill_srv; exit 1
fi
log "RUN1 phase A: corridor walk (perfbotb, M2CC-mirrored TP workload)"
if ! M1_USER=perfbotb M1_TP=100 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_TP_SPAN=600 \
     HOLD_SECONDS=400 SESSIONS=1 $PY tools/targeta_client.py mcer-c-on1-walk $HOSTPORT; then
  log "STOP-CONDITION: RUN1 walk client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mcer-c-on1-walk.jsonl" 1 1000 0; then
  log "STOP-CONDITION: RUN1 walk transcript invalid (decode errors or no chunk volume); aborting"
  kill_srv; exit 2
fi
log "RUN1 phase A validated; phase B: 25 login/logout sessions (8s hold)"
if ! HOLD_SECONDS=8 SESSIONS=25 $PY tools/targeta_client.py mcer-c-on1 $HOSTPORT; then
  log "STOP-CONDITION: RUN1 session client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mcer-c-on1.jsonl" 25 5000 0; then
  log "STOP-CONDITION: RUN1 session transcript invalid; aborting"
  kill_srv; exit 2
fi
log "RUN1 client phases complete and validated; waiting for server window to close"
wait $SRV || true
cp "$TC/server/m1-metrics.txt" "$ROOT/machine/raw/M2CER-on1-metrics-final.txt"
log "RUN1 done; metrics snapshot saved"
kill_srv

# ================= RUN 2: ON_EXPERIMENTAL paranoid =================
log "RUN2 launching Revelation server (ON_EXPERIMENTAL + paranoid leak detection, window 400s)"
bash tools/m2cc-pack-run.sh C ON_EXPERIMENTAL mcer-c-on2 400 paranoid &
SRV=$!
for i in $(seq 1 240); do
  grep -q "Done (" "$TC/server/run-mcer-c-on2.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TC/server/run-mcer-c-on2.log" 2>/dev/null; then
  log "FATAL: RUN2 boot timeout"; kill_srv; exit 1
fi
log "RUN2 booted; waiting for login readiness"
if ! wait_ready; then
  log "FATAL: RUN2 server never accepted logins"; kill_srv; exit 1
fi
log "RUN2 server accepting; 10 login/logout sessions (8s hold)"
if ! HOLD_SECONDS=8 SESSIONS=10 $PY tools/targeta_client.py mcer-c-on2-par $HOSTPORT; then
  log "STOP-CONDITION: RUN2 client crashed; aborting"
  kill_srv; exit 2
fi
if ! transcript_ok "$TA/client-mcer-c-on2-par.jsonl" 10 2000 0; then
  log "STOP-CONDITION: RUN2 transcript invalid; aborting"
  kill_srv; exit 2
fi
log "RUN2 client phases complete and validated; waiting for server window to close"
wait $SRV || true
cp "$TC/server/m1-metrics.txt" "$ROOT/machine/raw/M2CER-on2-metrics-final.txt"
log "RUN2 done; metrics snapshot saved"
kill_srv

log "M2CER campaign complete"
