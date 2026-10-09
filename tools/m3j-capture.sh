#!/usr/bin/env bash
# M3J factory/dirty-world JFR profile (operator authorization 2026-09-20).
# ONE bounded Revelation capture whose ServerThread evidence decides M3.
# No synthetic stress loops: natural mob spawning (night), a modest
# realistic vanilla storage/smelting room built via RCON (engine-owned TE
# work), real dirty-chunk saves, natural recovery.
# Phases (timeline file, wall clock): BOOT SETTLE BUILD EXCLUDED_FROM_SHARES
# FACTORY_ACTIVE(day, base running) ENTITY_ACTIVE(night, mobs+base)
# RECOVERY(bot disconnect unload-save + final autosave) then self-stop.
# JFR: settings=profile (10ms method sampling + allocation sampling),
# stackdepth=96, dumponexit. Targeted JVM management only (command-line
# marker; operator's unrelated JVMs untouched).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
JFR="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/jfr.exe"
RCON() { python "$ROOT/tools/m1i_rcon.py" 127.0.0.1 25570 m1i-perf "$@"; }
LABEL=m3j-f1
TL="$ROOT/machine/raw/M3J-$LABEL.timeline"
SRVMARK="forge-1.12.2-14.23.5.2846-universal.jar"
: > "$TL"
log_phase() { echo "$1 $(date +%H:%M:%S)" | tee -a "$TL"; }

srv_pids() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$SRVMARK*' } | Select-Object -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r\n '
}
P="$(srv_pids)"
[ -z "$P" ] || { echo "stale Target-C JVM pid=$P; aborting"; exit 1; }
netstat -ano | grep -qE ":25567\s.*LISTENING" && { echo "port 25567 busy"; exit 1; }

log_phase "LAUNCH $(date +%s)"
cd "$ROOT/machine/targetC/server" || exit 1
( sleep 1180; echo stop; sleep 60 ) | "$JAVA" -Xmx6G -Xms6G \
  -Djava.library.path=. \
  -Dminecraftrust.native_chunk_packet=OFF \
  -Dminecraftrust.m1.handoff=A \
  -Drustcraft.m1.dumpdiagnostics=true \
  -XX:+FlightRecorder \
  "-XX:StartFlightRecording=name=m3j,settings=profile,filename=m3j-full.jfr,duration=1300s,dumponexit=true" \
  -XX:FlightRecorderOptions=stackdepth=96 \
  -verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime \
  -Xloggc:"gc-$LABEL.log" \
  -jar forge-1.12.2-14.23.5.2846-universal.jar nogui \
  > "run-$LABEL.log" 2>&1 &
SRV=$!

for i in $(seq 1 200); do
  sleep 5
  grep -q "Done (" "run-$LABEL.log" 2>/dev/null && break
done
grep -q "Done (" "run-$LABEL.log" || { echo "SERVER NEVER READY"; P="$(srv_pids)"; for pid in $P; do taskkill //F //PID "$pid" >/dev/null 2>&1 || true; done; exit 1; }
log_phase "BOOT_DONE"

sleep 30
log_phase "SETTLE_END"

# bot joins ONCE, stays the whole window at its saved position (no TP/walk)
cd "$ROOT" || exit 1
( M1_MODLIST="$ROOT/machine/targetC/mod-ids.json" M1_USER=perfbotb \
    HOLD_SECONDS=880 SESSIONS=1 \
    python tools/targeta_client.py m3j-base 127.0.0.1 25567 > /dev/null 2>&1 || true ) &
BOT=$!
sleep 20   # login + chunk stream
log_phase "BOT_READY"

# keep the bot alive so hostiles genuinely chase it (real targeted pathfinding)
( for k in $(seq 1 14); do
    RCON "effect perfbotb 11 180 4" > /dev/null 2>&1 || true
    RCON "effect perfbotb 10 180 1" > /dev/null 2>&1 || true
    sleep 120
  done ) &
KEEP=$!

# ---- BUILD phase (excluded from steady-state shares) ----
log_phase "BUILD_START"
RCON "execute at perfbotb run fill ~-10 ~-1 ~-10 ~10 ~7 ~10 air" > /dev/null 2>&1 || true
RCON "execute at perfbotb run fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 stone" > /dev/null 2>&1 || true
RCON "execute at perfbotb run fill ~-9 ~ ~-8 ~-2 ~ ~-8 furnace facing=south" > /dev/null 2>&1 || true
RCON "execute at perfbotb run fill ~-9 ~ ~1 ~-1 ~ ~4 chest" > /dev/null 2>&1 || true
RCON "execute at perfbotb run fill ~-9 ~1 ~1 ~-1 ~1 ~4 hopper" > /dev/null 2>&1 || true
# feed the smelting array (fuel + ore) and drop items onto the hopper row
for i in 0 1 2 3 4 5 6 7; do
  X=$(( -9 + i ))
  RCON "execute at perfbotb run blockdata ~$X ~ ~-8 {Items:[{Slot:0b,id:minecraft:iron_ore,Count:64b},{Slot:1b,id:minecraft:coal,Count:64b}]}" > /dev/null 2>&1 || true
done
for i in 0 1 2 3 4 5 6 7; do
  X=$(( -9 + i ))
  RCON "execute at perfbotb run summon item ~$X ~4 ~2 {Item:{id:minecraft:cobblestone,Count:64b},PickupDelay:32767s}" > /dev/null 2>&1 || true
  RCON "execute at perfbotb run summon item ~$X ~4 ~3 {Item:{id:minecraft:cobblestone,Count:64b}}" > /dev/null 2>&1 || true
done
RCON "time set day" > /dev/null 2>&1 || true
sleep 5
log_phase "BUILD_END"

# ---- FACTORY_ACTIVE: day window, base running (hoppers/furnaces/items) ----
sleep 360
log_phase "FACTORY_END"

# ---- ENTITY_ACTIVE: night window, natural hostile spawning + chase ----
RCON "time set 12500" > /dev/null 2>&1 || true
sleep 420
log_phase "ENTITY_END"

# bot hold expires here -> disconnect -> RECOVERY (unload-save of dirty chunks)
sleep 100
log_phase "RECOVERY_END"

wait $SRV || true
kill $KEEP 2>/dev/null || true
wait $BOT 2>/dev/null || true
log_phase "STOP"
mv "$ROOT/machine/targetC/server/m3j-full.jfr" "$ROOT/machine/raw/M3J-$LABEL.jfr" 2>/dev/null || echo "JFR MOVE FAILED"
mv "$ROOT/machine/targetC/server/gc-$LABEL.log" "$ROOT/machine/raw/M3J-$LABEL-gc.log" 2>/dev/null || true
mv "$ROOT/machine/targetC/server/run-$LABEL.log" "$ROOT/machine/targetC/server/run-$LABEL.log" 2>/dev/null || true
echo "capture complete; timeline: $TL"
