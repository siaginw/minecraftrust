#!/bin/bash
# M1-final tail-latency study driver: R fresh JVM processes, BALANCED pass
# order (alternating java-first / native-first), raw samples per fixture.
# Output: machine/raw/M1F-tail-j{1..4}.csv (order recorded in each run's stderr
# banner line and in the aggregator summary).
set -e
R="${R:-4}"
OUT_PREFIX="${OUT_PREFIX:-M1F-tail}"
for j in $(seq 1 "$R"); do
  if [ $((j % 2)) -eq 1 ]; then ORDER="java-first"; else ORDER="native-first"; fi
  echo "== JVM $j order=$ORDER"
  bash tools/run-shadow.sh com.rustcraft.oracle.TailLatencyStudy \
    "machine/raw/${OUT_PREFIX}-j${j}.csv" "$ORDER" 2>&1 | grep -E "TailLatencyStudy|Error|Exception" || true
done
echo "done: $R JVMs"
