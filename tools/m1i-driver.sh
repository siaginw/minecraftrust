#!/usr/bin/env bash
# M1I driver: one instrumented matched run with phases idle -> login/chunk
# burst -> movement/streaming (walk corridor) -> idle. Writes a phase
# timeline file used by the offline analyzer.
# Usage: m1i-driver.sh <pack> <mode> <label> <run_seconds> [walk_seconds]
# Phase layout after boot (seconds): idle 60 -> burst 5 bots x25s -> walk 2
# bots x WALK -> idle 45.
set -e
PACK="${1:?C|D}"; MODE="${2:?}"; LABEL="${3:?}"; SECS="${4:?}"
WALK="${5:-120}"
case "$PACK" in
  C) PORT=25567; RPORT=25570; MODLIST=machine/targetC/mod-ids.json ;;
  D) PORT=25568; RPORT=25571; MODLIST=machine/targetD/mod-ids.json ;;
esac
TL="machine/raw/M1I-$LABEL.timeline"
: > "$TL"

log_phase() {
  echo "$1 $(date +%H:%M:%S)" >> "$TL"
  # snapshot the cumulative MSPT histogram at each phase boundary so the
  # analyzer can compute exact per-phase distributions by subtraction
  cp "machine/target$PACK/server/m1-metrics.txt" "machine/raw/M1I-$LABEL-$1.mspt" 2>/dev/null || true
}

bash tools/m1i-perf-run.sh "$PACK" "$MODE" "$LABEL" "$SECS" &
RUNPID=$!

# wait for server Done line
LOG="machine/target$PACK/server/run-$LABEL.log"
for i in $(seq 1 200); do
  sleep 5
  grep -q "Done (" "$LOG" 2>/dev/null && break
done
grep -q "Done (" "$LOG" || { echo "server never reached Done"; kill $RUNPID 2>/dev/null || true; exit 1; }
sleep 5
log_phase "boot_done"

echo "== idle phase (40s)"
sleep 40; log_phase "idle_end"

echo "== burst phase (5 bots, 25s each)"
M1_MODLIST="$MODLIST" HOLD_SECONDS=15 SESSIONS=3 python tools/targeta_client.py "$LABEL-burst" 127.0.0.1 "$PORT" > /dev/null 2>&1 || true
log_phase "burst_end"

echo "== walk phase (1 TP bot, ${WALK}s)"
python tools/m1i_rcon.py 127.0.0.1 "$RPORT" m1i-perf "op perfbotb" > /dev/null 2>&1 || true
M1_MODLIST="$MODLIST" M1_USER=perfbotb M1_TP=999 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_TP_SPAN=600 M1_WALK_DELAY=8 HOLD_SECONDS="$WALK" SESSIONS=1 python tools/targeta_client.py "$LABEL-walk" 127.0.0.1 "$PORT" > /dev/null 2>&1 || true
log_phase "walk_end"

echo "== post idle (30s)"
sleep 30; log_phase "postidle_end"

wait $RUNPID
echo "run $LABEL complete; timeline: $TL"
