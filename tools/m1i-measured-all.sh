#!/usr/bin/env bash
# M1I measured campaign: 8 matched runs, strictly sequential, balanced order.
# C: OFF, ON, ON, OFF   D: ON, OFF, OFF, ON
set -u
for spec in "C OFF i-c1" "C ON_EXPERIMENTAL i-c2" "C ON_EXPERIMENTAL i-c3" "C OFF i-c4" \
            "D ON_EXPERIMENTAL i-d1" "D OFF i-d2" "D OFF i-d3" "D ON_EXPERIMENTAL i-d4"; do
  set -- $spec
  echo "=== $(date +%H:%M:%S) START $1 $2 $3 ==="
  bash tools/m1i-driver.sh "$1" "$2" "$3" 380 120 || echo "RUN $3 FAILED (continuing)"
  echo "=== $(date +%H:%M:%S) END $3 ==="
  # hard guarantee: no JVM may outlive its run before the next starts
  for i in $(seq 1 60); do
    n=$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c "java.exe")
    [ "${n:-1}" = "0" ] && break
    taskkill //F //IM java.exe >/dev/null 2>&1 || true
    sleep 10
  done
done
echo "ALL RUNS COMPLETE $(date +%H:%M:%S)"
