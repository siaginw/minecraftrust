#!/usr/bin/env bash
# M3 Worldgen Clean Forge Authoritative ON_EXPERIMENTAL Campaign.
# Runs twin worlds:
#   World J: M3 worldgen OFF_MEASURE (pure Java reference baseline)
#   World R: M3 worldgen ON_EXPERIMENTAL (Rust authoritative density field)
# Same seed (123456789), same generator settings, same Forge version, same bot walk.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
SRC="$ROOT/machine/targetA/server"
COREMOD="$ROOT/tools/dist/rustcraft-m1-coremod.jar"
DLL="$ROOT/target/release/rustcraft_ffi.dll"

SEED="123456789"
HOLD_SEC=360
SERVER_LIFETIME=400

mkdir -p "$ROOT/machine/raw"

run_world() {
    local WORLD_TAG="$1"     # "worldj" or "worldr"
    local WG_MODE="$2"       # "OFF_MEASURE" or "ON_EXPERIMENTAL"
    local RUN_DIR="$ROOT/machine/targetA/run-$WORLD_TAG"
    local LOG_FILE="$RUN_DIR/server.log"
    local CLIENT_LABEL="M3WG-auth-targeta-$WORLD_TAG"

    echo "=================================================================="
    echo "STARTING RUN: $WORLD_TAG (Mode: $WG_MODE)"
    echo "=================================================================="

    rm -rf "$RUN_DIR"
    mkdir -p "$RUN_DIR"
    cp -r "$SRC/." "$RUN_DIR/"

    # Deploy coremod and native DLL
    cp "$COREMOD" "$RUN_DIR/mods/rustcraft-m1-coremod.jar"
    cp "$DLL" "$RUN_DIR/rustcraft_ffi.dll"

    # Ensure clean fresh world with deterministic seed
    rm -rf "$RUN_DIR/world"
    sed -i "s/^level-seed=.*/level-seed=$SEED/" "$RUN_DIR/server.properties"

    # Configure ops.json with perfbotb and m2cebot
    cat << 'EOF' > "$RUN_DIR/ops.json"
[
  {
    "uuid": "aa22fb93-118a-3eb8-bb2b-4e6d2d155ecb",
    "name": "perfbotb",
    "level": 4,
    "bypassesPlayerLimit": true
  },
  {
    "uuid": "b1937677-02ce-3ad7-b497-9e1d5d69f9c4",
    "name": "m2cebot",
    "level": 4,
    "bypassesPlayerLimit": true
  }
]
EOF

    cd "$RUN_DIR"

    # Launch server with piped input for stop command
    ( sleep "$SERVER_LIFETIME"; echo "stop"; sleep 30 ) | "$JAVA" -Xmx4G -Xms4G -Djava.library.path=. \
      -Dminecraftrust.native_chunk_packet=OFF \
      -Dminecraftrust.m1.handoff=A \
      -Dminecraftrust.worldgen="$WG_MODE" \
      -Drustcraft.m1.dumpdiagnostics=true \
      -jar forge-1.12.2-14.23.5.2860.jar nogui \
      > "$LOG_FILE" 2>&1 &
    local SRV_PID=$!

    echo "Waiting for server to boot in $RUN_DIR (PID $SRV_PID)..."
    local BOOTED=0
    for i in $(seq 1 120); do
        sleep 2
        if grep -q "Done (" "$LOG_FILE" 2>/dev/null; then
            BOOTED=1
            break
        fi
        if ! kill -0 "$SRV_PID" 2>/dev/null; then
            echo "SERVER PROCESS DIED EARLY!"
            cat "$LOG_FILE"
            exit 1
        fi
    done

    if [ "$BOOTED" -ne 1 ]; then
        echo "BOOT TIMEOUT!"
        exit 1
    fi
    echo "Server booted successfully. Sleeping 10s before client connection..."
    sleep 10

    echo "Connecting deterministic bot client ($CLIENT_LABEL)..."
    TARGETA_OUT_DIR="$ROOT/machine/raw" \
      M1_MODLIST="$ROOT/machine/targetA/mod-ids.json" M1_USER=perfbotb \
      M1_TP=110 M1_TP_INTERVAL=3 M1_TP_STEP=200 M1_TP_SPAN=20000 \
      HOLD_SECONDS="$HOLD_SEC" SESSIONS=1 \
      python "$ROOT/tools/targeta_client.py" "$CLIENT_LABEL" 127.0.0.1 25565 || true

    echo "Client session finished. Waiting for server shutdown..."
    wait "$SRV_PID" || true
    echo "Server process exited."

    # Harvest artifacts
    cp "$RUN_DIR/m1-metrics.txt" "$ROOT/machine/raw/M3WG-auth-targeta-$WORLD_TAG-metrics.txt" 2>/dev/null || true
    cp "$LOG_FILE" "$ROOT/machine/raw/M3WG-auth-targeta-$WORLD_TAG-server.log" 2>/dev/null || true
    if [ -f "$ROOT/machine/raw/client-$CLIENT_LABEL.jsonl" ]; then
        mv "$ROOT/machine/raw/client-$CLIENT_LABEL.jsonl" "$ROOT/machine/raw/M3WG-auth-targeta-$WORLD_TAG-client.jsonl"
    fi

    # Preserve generated world
    rm -rf "$ROOT/machine/targetA/world-$WORLD_TAG"
    cp -r "$RUN_DIR/world" "$ROOT/machine/targetA/world-$WORLD_TAG"
    echo "Saved world to $ROOT/machine/targetA/world-$WORLD_TAG"
}

# Run World J (Java OFF_MEASURE)
run_world "worldj" "OFF_MEASURE"

echo "Cooling down 15s between runs..."
sleep 15

# Run World R (Rust ON_EXPERIMENTAL)
run_world "worldr" "ON_EXPERIMENTAL"

echo "=================================================================="
echo "RUNNING SEMANTIC CHUNK PARITY ORACLE (WORLD J vs WORLD R)"
echo "=================================================================="
python "$ROOT/tools/compare_worlds.py" \
  "$ROOT/machine/targetA/world-worldj" \
  "$ROOT/machine/targetA/world-worldr" \
  | tee "$ROOT/machine/raw/M3WG-auth-targeta-chunk-parity.txt"

echo "TWIN WORLD CAMPAIGN COMPLETE."
