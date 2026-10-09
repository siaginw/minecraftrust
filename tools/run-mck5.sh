#!/usr/bin/env bash
# M-CK5 offline entry script — shadow observer + frame memory optimization.
# Verifies dependencies, rebuilds when required, identifies the exact native
# library, runs the correctness gates, optionally the bounded benchmark, and
# saves results under a unique run ID. Returns failure when a required gate
# fails. OFFLINE ONLY: no server boot, no live observer installation, no native
# transmission; issue #1 stays OPEN.
#
# Usage: bash tools/run-mck5.sh [RUN_ID] [--bench]
set -e
cd "$(dirname "$0")/.."
RUN_ID="${1:-mck5-$(date +%Y%m%d-%H%M%S)}"
BENCH="${2:-}"
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
JAVAC="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe"
SRG="third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar"
FORGE="third_party_reference/forge/server/forge-1.12.2-14.23.5.2860.jar"
ASM="third_party_reference/forge/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"
LW="third_party_reference/forge/server/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
OUT="machine/raw/$RUN_ID"
mkdir -p "$OUT"

echo "== M-CK5 entry: RUN_ID=$RUN_ID =="
[ -f "$SRG" ] && [ -f "$FORGE" ] || { echo "DEPENDENCY MISSING (vanilla/forge jars)"; exit 1; }
[ -f target/release/rustcraft_ffi.dll ] || cargo build -p ffi --release

echo "== native library identity =="
DLL_SHA=$(sha256sum target/release/rustcraft_ffi.dll | cut -d' ' -f1)
echo "dll_sha256=$DLL_SHA path=$(pwd)/target/release/rustcraft_ffi.dll" | tee "$OUT/native-identity.txt"
git rev-parse HEAD >> "$OUT/native-identity.txt" 2>/dev/null || true

echo "== rebuild bridge =="
bash tools/build-coremod.sh >/dev/null

CP="target/release;tools/dist/coremod-build;$FORGE;$SRG;$ASM;$LW"
GATES="MCK5ObserverGates MCK5OptimizationTests MCK42OwnershipGates MCK42FrameBoundaryGates MCK4Validation M53FrameOracle M52CompressionTests M4AuthoritativeTest"
FAILED=0
for G in $GATES; do
  echo "== gate $G =="
  if (cd target/release && "$JAVA" -Djava.library.path=. -cp "$CP" "com.rustcraft.bridge.$G") > "$OUT/$G.txt" 2>&1; then
    grep -oE "RESULT: [0-9]+ pass, [0-9]+ fail" "$OUT/$G.txt" | tail -1
  else
    grep -oE "RESULT: [0-9]+ pass, [0-9]+ fail" "$OUT/$G.txt" | tail -1
    echo "GATE FAILED: $G"; FAILED=1
  fi
done
echo "== rust tests =="
(cargo test -p compression --release --lib && cargo test -p native-chunk --release) > "$OUT/rust-tests.txt" 2>&1 \
  && grep "test result" "$OUT/rust-tests.txt" || { echo "RUST GATES FAILED"; FAILED=1; }

if [ "$BENCH" = "--bench" ] && [ "$FAILED" = "0" ]; then
  echo "== bounded benchmark (3 fresh JVMs, rotated) =="
  for ROT in ABCD BCDA CDAB; do
    (cd target/release && "$JAVA" -Djava.library.path=. -cp "$CP" com.rustcraft.bridge.MCK5Bench "$ROT") > "$OUT/bench-$ROT.txt" 2>&1 \
      || { echo "BENCH FAILED: $ROT"; FAILED=1; }
  done
fi

echo "== summary =="
echo "run_id=$RUN_ID dll=$DLL_SHA failed=$FAILED (evidence: $OUT)"
[ "$FAILED" = "0" ] && echo "MCK5 ENTRY: ALL REQUIRED GATES PASSED" || exit 1
