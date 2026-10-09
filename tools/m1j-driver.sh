#!/usr/bin/env bash
# M1J Revelation streaming regression investigation: one focused run.
# Phases: boot -> settle 12s -> streaming 150s (TP-hop corridor bot, mid
# snapshot at 75s for warmup-drift analysis) -> stop.
# Usage: m1j-driver.sh <mode: OFF|ON_EXPERIMENTAL> <label> [stream_seconds]
set -e
MODE="${1:?}"; LABEL="${2:?}"; STREAM="${3:-150}"
PORT=25567; RPORT=25570; MODLIST=machine/targetC/mod-ids.json
TL="machine/raw/M1J-$LABEL.timeline"
: > "$TL"
log_phase() {
  echo "$1 $(date +%H:%M:%S)" >> "$TL"
  cp "machine/targetC/server/m1-metrics.txt" "machine/raw/M1J-$LABEL-$1.mspt" 2>/dev/null || true
}

bash tools/m1i-perf-run.sh C "$MODE" "$LABEL" $((STREAM + 260)) &
RUNPID=$!
LOG="machine/targetC/server/run-$LABEL.log"
for i in $(seq 1 200); do
  sleep 5
  grep -q "Done (" "$LOG" 2>/dev/null && break
done
grep -q "Done (" "$LOG" || { echo "server never reached Done"; kill $RUNPID 2>/dev/null || true; exit 1; }
sleep 8
log_phase "boot_done"
sleep 12; log_phase "settle_end"

python tools/m1i_rcon.py 127.0.0.1 "$RPORT" m1i-perf "op perfbotb" > /dev/null 2>&1 || true
# mid-stream snapshot (warmup-drift split) while the single bot session runs
( sleep $((STREAM / 2)); cp machine/targetC/server/m1-metrics.txt "machine/raw/M1J-$LABEL-walk_mid.mspt" 2>/dev/null; echo "walk_mid $(date +%H:%M:%S)" >> "$TL" ) &
MIDPID=$!
M1_MODLIST="$MODLIST" M1_USER=perfbotb M1_TP=999 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_TP_SPAN=600 M1_WALK_DELAY=8 HOLD_SECONDS="$STREAM" SESSIONS=1 python tools/targeta_client.py "$LABEL-walk" 127.0.0.1 "$PORT" > /dev/null 2>&1 || true
kill $MIDPID 2>/dev/null || true
log_phase "walk_end"

wait $RUNPID
echo "run $LABEL complete"
