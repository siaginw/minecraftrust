#!/usr/bin/env bash
# M1J: 10 fresh-JVM runs on Revelation, strict alternation OFF,ON x5 each.
set -u
i=0
for MODE in OFF ON_EXPERIMENTAL OFF ON_EXPERIMENTAL OFF ON_EXPERIMENTAL OFF ON_EXPERIMENTAL OFF ON_EXPERIMENTAL; do
  i=$((i+1))
  L=$(printf "j-c%02d" "$i")
  echo "=== $(date +%H:%M:%S) START $L $MODE ==="
  bash tools/m1j-driver.sh "$MODE" "$L" 150 || echo "RUN $L FAILED (continuing)"
  for t in $(seq 1 60); do
    n=$(tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c "java.exe")
    [ "${n:-1}" = "0" ] && break
    taskkill //F //IM java.exe >/dev/null 2>&1 || true
    sleep 10
  done
done
echo "M1J COMPLETE $(date +%H:%M:%S)"
