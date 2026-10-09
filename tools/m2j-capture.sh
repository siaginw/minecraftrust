#!/usr/bin/env bash
# M2-J: ONE bounded Revelation JFR discovery capture (operator approval).
# Single fresh JVM, M1 OFF, existing world/config/workload:
#   boot -> settle 12s -> 150s TP-hop streaming -> recovery (to self-stop).
# Self-stop at boot+420s so vanilla autosave (6000 ticks ~= 300s uptime)
# fires inside the recovery window. Total wall <= ~9min (< 12min cap).
# JFR: settings=profile (10ms method sampling + allocation sampling),
# stackdepth=96, dumponexit=true. Early in-run validation at T+170s via
# jcmd JFR.check + JFR.dump + jfr summary (same run, no restart/extension).
# All paths ABSOLUTE (previous attempts failed on relative paths after cd).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
JCMD="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/jcmd.exe"
JFR="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/jfr.exe"
LABEL=m2j-jfr1
TL="$ROOT/machine/raw/M2J-$LABEL.timeline"
: > "$TL" || { echo "cannot create $TL"; exit 1; }
log_phase() { echo "$1 $(date +%H:%M:%S)" | tee -a "$TL"; }

N=$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c java.exe)
[ "${N:-1}" = "0" ] || { echo "java already running ($N); aborting"; exit 1; }

cd "$ROOT/machine/targetC/server" || exit 1
( sleep 420; echo stop; sleep 60 ) | "$JAVA" -Xmx6G -Xms6G \
  -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Drustcraft.m1.dumpdiagnostics=true \
  -XX:+FlightRecorder \
  "-XX:StartFlightRecording=name=m2j,settings=profile,filename=m2j-full.jfr,duration=480s,dumponexit=true" \
  -XX:FlightRecorderOptions=stackdepth=96 \
  -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime \
  -Xloggc:"gc-$LABEL.log" \
  -jar forge-1.12.2-14.23.5.2846-universal.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!

( sleep 170
  WPID=$(powershell -NoProfile -Command "(Get-Process java -ErrorAction SilentlyContinue | Select-Object -First 1).Id" 2>/dev/null | tr -d '\r\n ')
  [ -z "$WPID" ] && WPID=$SRV
  echo "[early-check] winpid=$WPID (script pid $SRV)"
  "$JCMD" "$WPID" JFR.check name=m2j 2>&1 | sed 's/^/[early-check] /'
  "$JCMD" "$WPID" JFR.dump name=m2j filename=m2j-early.jfr 2>&1 | sed 's/^/[early-dump] /'
  "$JFR" summary m2j-early.jfr 2>&1 | grep -E "jdk.ExecutionSample|ObjectAllocation|Duration" | sed 's/^/[early-summary] /'
) &

for i in $(seq 1 200); do
  sleep 5
  grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
done
grep -q "Done (" "run-$LABEL.log" || { echo "SERVER NEVER READY"; kill $SRV 2>/dev/null || true; sleep 20; exit 1; }
sleep 5
log_phase "boot_done"

sleep 12
log_phase "settle_end"

cd "$ROOT" || exit 1
python tools/m1i_rcon.py 127.0.0.1 25570 m1i-perf "op perfbotb" > /dev/null 2>&1 || true
M1_MODLIST=machine/targetC/mod-ids.json M1_USER=perfbotb M1_TP=999 M1_TP_INTERVAL=4 M1_TP_STEP=40 M1_WALK_SPAN=600 M1_WALK_DELAY=8 HOLD_SECONDS=150 SESSIONS=1 python tools/targeta_client.py m2j-walk 127.0.0.1 25567 > /dev/null 2>&1 || true
log_phase "stream_end"

wait $SRV || true
log_phase "recovery_end"
mv "$ROOT/machine/targetC/server/m2j-early.jfr" "$ROOT/machine/raw/M2J-jfr1-early.jfr" 2>/dev/null || true
mv "$ROOT/machine/targetC/server/m2j-full.jfr" "$ROOT/machine/raw/M2J-jfr1.jfr" 2>/dev/null || true
echo "capture complete; timeline: $TL"
