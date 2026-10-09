#!/usr/bin/env bash
# M3 collision-feasibility probe run (operator authorization 2026-09-20).
# ONE bounded measurement pass: perfbotb AFK through a night with natural
# mob spawning (the M3J entity workload), collision probe counters active.
# M1 OFF, M2C default OFF. No JFR (counters only). Fresh JVM, self-stop.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
LABEL=m3c-probe1
cd "$ROOT/machine/targetC/server"
( sleep 900; echo stop; sleep 45 ) | "$JAVA" -Xmx6G -Xms6G -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Dminecraftrust.collision_probe=true \
  -Drustcraft.m1.dumpdiagnostics=true \
  -jar forge-1.12.2-14.23.5.2846-universal.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!
for i in $(seq 1 200); do
  sleep 5; grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
done
grep -q "Done (" "run-$LABEL.log" || { echo "BOOT FAILED"; exit 1; }
sleep 30
( M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" M1_USER=perfbotb \
    HOLD_SECONDS=700 SESSIONS=1 \
    python "$ROOT/tools/targeta_client.py" m3c-probe 127.0.0.1 25567 >/dev/null 2>&1 || true ) &
sleep 25
python "$ROOT/tools/m1i_rcon.py" 127.0.0.1 25570 m1i-perf "time set 12500" >/dev/null 2>&1 || true
wait $SRV || true
echo "probe run complete"
