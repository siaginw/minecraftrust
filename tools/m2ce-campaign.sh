#!/usr/bin/env bash
# M2CE ON_EXPERIMENTAL bounded Target-A campaign (operator authorization 2026-09-20).
# Clean Forge Target A ONLY. Two runs: normal ON then paranoid ON.
# M1 stays OFF (native_chunk_packet=OFF); no dual compression in ON mode.
#
# Process discipline: OTHER java.exe processes may belong to the operator's
# unrelated work (observed: gradle + forge gametest on D:\minecraftengine) —
# NEVER taskkill by image name. Only the Target-A server JVM is managed,
# matched by its unique command-line marker string.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PY=python
TA="$ROOT/machine/targetA"
MARK="$ROOT/machine/raw/M2CE-campaign-marker.txt"
SRVMARK="forge-1.12.2-14.23.5.2860.jar"

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

# ---- pre-flight: no stale Target-A server JVM, port free ----
P="$(srv_pids)"
if [ -n "$P" ]; then log "FATAL: stale Target-A server JVM pid=$P at campaign start"; exit 1; fi
if netstat -ano | grep -qE ":25565\s.*LISTENING"; then log "FATAL: port 25565 already listening"; exit 1; fi
rm -f "$MARK"
log "M2CE campaign start: run1=ON normal, run2=ON paranoid; jar c975fc1c, dll 0acb7469"

# ================= RUN 1: ON_EXPERIMENTAL normal =================
log "RUN1 launching server (ON_EXPERIMENTAL, window 1200s)"
bash tools/m2c-a-run.sh ON_EXPERIMENTAL m2ce-on1 1200 &
SRV=$!
for i in $(seq 1 240); do
  grep -q "Done (" "$TA/run-m2ce-on1.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TA/run-m2ce-on1.log" 2>/dev/null; then
  log "FATAL: RUN1 boot timeout"; kill_srv; exit 1
fi
log "RUN1 server booted; phase A: TP streaming session (m2cebot, 80 hops x 64 blocks)"
if ! M1_USER=m2cebot M1_TP=80 M1_TP_INTERVAL=3 M1_TP_STEP=64 M1_TP_SPAN=6000 \
     HOLD_SECONDS=240 SESSIONS=1 $PY tools/targeta_client.py m2ce-on1-walk; then
  log "STOP-CONDITION: RUN1 walk client failed (decode/crash); aborting"
  kill_srv; exit 2
fi
log "RUN1 phase B: 25 login/logout sessions"
if ! HOLD_SECONDS=25 SESSIONS=25 $PY tools/targeta_client.py m2ce-on1; then
  log "STOP-CONDITION: RUN1 session client failed (decode/crash); aborting"
  kill_srv; exit 2
fi
log "RUN1 client phases complete; waiting for server window to close"
wait $SRV || true
cp "$TA/server/m1-metrics.txt" "$ROOT/machine/raw/M2CE-on1-metrics-final.txt"
log "RUN1 done; metrics snapshot saved"
kill_srv

# ================= RUN 2: ON_EXPERIMENTAL paranoid =================
log "RUN2 launching server (ON_EXPERIMENTAL + paranoid leak detection, window 480s)"
bash tools/m2c-a-run.sh ON_EXPERIMENTAL m2ce-on2 480 paranoid &
SRV=$!
for i in $(seq 1 240); do
  grep -q "Done (" "$TA/run-m2ce-on2.log" 2>/dev/null && break
  sleep 2
done
if ! grep -q "Done (" "$TA/run-m2ce-on2.log" 2>/dev/null; then
  log "FATAL: RUN2 boot timeout"; kill_srv; exit 1
fi
log "RUN2 server booted; 10 login/logout sessions"
if ! HOLD_SECONDS=25 SESSIONS=10 $PY tools/targeta_client.py m2ce-on2-par; then
  log "STOP-CONDITION: RUN2 client failed (decode/crash); aborting"
  kill_srv; exit 2
fi
log "RUN2 client phases complete; waiting for server window to close"
wait $SRV || true
cp "$TA/server/m1-metrics.txt" "$ROOT/machine/raw/M2CE-on2-metrics-final.txt"
log "RUN2 done; metrics snapshot saved"
kill_srv

log "M2CE campaign complete"
