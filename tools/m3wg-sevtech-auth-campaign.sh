#!/usr/bin/env bash
# M3 Worldgen AUTHORITATIVE campaign: SevTech: Ages 3.2.3 (Target D)
# Twin-world pre-population terrain oracle + live authoritative characterization.
# Runs:
#   World J: worldgen OFF_MEASURE (pure Java reference) → prepop OFF_MEASURE
#   World R: worldgen ON_EXPERIMENTAL (Rust authoritative) → prepop ON_EXPERIMENTAL
# Same seed, same generator settings, same exact ordered bot corpus,
# same Forge/JVM/config. Compares pre-population base-terrain hashes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
SRC="$ROOT/machine/targetD/server"
COREMOD="$ROOT/tools/dist/rustcraft-m1-coremod.jar"
DLL="$ROOT/target/release/rustcraft_ffi.dll"
FORGE_JAR="forge-1.12.2-14.23.5.2860.jar"
MODLIST="$ROOT/machine/targetD/mod-ids.json"
PORT=25568

SEED="123456789"
HOLD_SEC=480
SERVER_LIFETIME=600
TAG="sev-auth"

mkdir -p "$ROOT/machine/raw/sev-auth"

run_world() {
    local WORLD_TAG="$1"     # "worldj" or "worldr"
    local WG_MODE="$2"       # "OFF_MEASURE" or "ON_EXPERIMENTAL"
    local RUN_DIR="$ROOT/machine/targetD/run-$WORLD_TAG-auth"
    local LOG_FILE="$RUN_DIR/server.log"
    local CLIENT_LABEL="M3WG-sev-auth-$WORLD_TAG"

    echo "=================================================================="
    echo "STARTING RUN: $WORLD_TAG (Mode: $WG_MODE) — SevTech"
    echo "=================================================================="

    rm -rf "$RUN_DIR"
    mkdir -p "$RUN_DIR"

    cp -r "$SRC/config" "$RUN_DIR/"
    cp -r "$SRC/libraries" "$RUN_DIR/"
    cp -r "$SRC/mods" "$RUN_DIR/"
    cp -r "$SRC/scripts" "$RUN_DIR/" 2>/dev/null || true
    cp -r "$SRC/structures" "$RUN_DIR/" 2>/dev/null || true
    cp "$SRC/$FORGE_JAR" "$RUN_DIR/"
    cp "$SRC/minecraft_server.1.12.2.jar" "$RUN_DIR/"
    cp "$SRC/server.properties" "$RUN_DIR/"
    cp "$SRC/eula.txt" "$RUN_DIR/"
    cp "$SRC/options.txt" "$RUN_DIR/" 2>/dev/null || true
    cp "$SRC/ops.json" "$RUN_DIR/" 2>/dev/null || true
    cp "$SRC/whitelist.json" "$RUN_DIR/" 2>/dev/null || true
    cp "$SRC/banned-ips.json" "$RUN_DIR/" 2>/dev/null || true
    cp "$SRC/banned-players.json" "$RUN_DIR/" 2>/dev/null || true

    cp "$COREMOD" "$RUN_DIR/mods/rustcraft-m1-coremod.jar"
    cp "$DLL" "$RUN_DIR/rustcraft_ffi.dll"

    rm -rf "$RUN_DIR/world"
    sed -i "s/^level-seed=.*/level-seed=$SEED/" "$RUN_DIR/server.properties"

    cat << 'EOF' > "$RUN_DIR/ops.json"
[
  { "uuid": "aa22fb93-118a-3eb8-bb2b-4e6d2d155ecb", "name": "perfbotb", "level": 4, "bypassesPlayerLimit": true },
  { "uuid": "b1937677-02ce-3ad7-b497-9e1d5d69f9c4", "name": "m2cebot", "level": 4, "bypassesPlayerLimit": true }
]
EOF

    cd "$RUN_DIR"

    ( sleep "$SERVER_LIFETIME"; echo "stop"; sleep 30 ) | "$JAVA" -Xmx6G -Xms6G -Djava.library.path=. \
      -Dminecraftrust.native_chunk_packet=OFF \
      -Dminecraftrust.m1.handoff=A \
      -Dminecraftrust.worldgen="$WG_MODE" \
      -Drustcraft.m1.dumpdiagnostics=true \
      -jar "$FORGE_JAR" nogui \
      > "$LOG_FILE" 2>&1 &
    local SRV_PID=$!

    echo "Waiting for server to boot in $RUN_DIR (PID $SRV_PID)..."
    local BOOTED=0
    for i in $(seq 1 60); do
        sleep 5
        if grep -q "Done (" "$LOG_FILE" 2>/dev/null; then BOOTED=1; break; fi
        if ! kill -0 "$SRV_PID" 2>/dev/null; then
            echo "SERVER PROCESS DIED EARLY!"; tail -40 "$LOG_FILE"; exit 1
        fi
    done
    if [ "$BOOTED" -ne 1 ]; then echo "BOOT TIMEOUT!"; exit 1; fi
    echo "Server booted successfully. Settling 30s for FML post-Done init..."
    sleep 30

    echo "Connecting deterministic bot client ($CLIENT_LABEL)..."
    TARGETA_OUT_DIR="$ROOT/machine/raw/sev-auth" \
      M1_MODLIST="$MODLIST" M1_USER=perfbotb \
      M1_TP=140 M1_TP_INTERVAL=4 M1_TP_STEP=200 M1_TP_Z_STEP=120 M1_TP_SPAN=10000 \
      HOLD_SECONDS="$HOLD_SEC" SESSIONS=1 \
      python "$ROOT/tools/targeta_client.py" "$CLIENT_LABEL" 127.0.0.1 "$PORT" || true

    echo "Client session finished. Waiting for server shutdown..."
    wait "$SRV_PID" || true
    echo "Server process exited."

    cp "$RUN_DIR/m1-metrics.txt" "$ROOT/machine/raw/sev-auth/$TAG-$WORLD_TAG-metrics.txt" 2>/dev/null || true
    cp "$LOG_FILE" "$ROOT/machine/raw/sev-auth/$TAG-$WORLD_TAG-server.log" 2>/dev/null || true
    if [ -f "$ROOT/machine/raw/sev-auth/client-$CLIENT_LABEL.jsonl" ]; then
        mv "$ROOT/machine/raw/sev-auth/client-$CLIENT_LABEL.jsonl" \
           "$ROOT/machine/raw/sev-auth/$TAG-$WORLD_TAG-client.jsonl"
    fi
    cp "$RUN_DIR/m3wg-prepop-$WG_MODE.txt" "$ROOT/machine/raw/sev-auth/$TAG-$WORLD_TAG-prepop.txt" 2>/dev/null \
        && echo "Harvested prepop: $TAG-$WORLD_TAG-prepop.txt" || echo "WARN: prepop file missing for $WORLD_TAG"
}

# Run World J (Java OFF_MEASURE) then World R (Rust ON_EXPERIMENTAL)
run_world "worldj" "OFF_MEASURE"

echo "Cooling down 15s between runs..."
sleep 15

run_world "worldr" "ON_EXPERIMENTAL"

echo "=================================================================="
echo "COMPARING PRE-POPULATION TERRAIN ORACLES (JAVA vs RUST) — SevTech"
echo "=================================================================="
python "$ROOT/tools/compare_prepop.py" \
  "$ROOT/machine/raw/sev-auth/$TAG-worldj-prepop.txt" \
  "$ROOT/machine/raw/sev-auth/$TAG-worldr-prepop.txt" \
  "$ROOT/machine/raw/sev-auth/$TAG-prepop-comparison.txt" \
  || echo "(compare_prepop exited nonzero: see $TAG-prepop-comparison.txt)"

echo "SEVTECH AUTHORITATIVE CAMPAIGN COMPLETE."
