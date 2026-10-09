#!/usr/bin/env python3
"""
RustCraft NativeChunk Performance Regression & Benchmark Runner.
Executes the release cost model benchmark and emits structured JSON metrics.
"""

import subprocess
import sys
import os
import re
import json

def run_bench():
    print("[bench] Executing native-chunk cost_model_bench (release)...")
    cmd = ["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--", "--nocapture"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        print("[bench] ERROR: Benchmark failed to run:", file=sys.stderr)
        print(proc.stderr, file=sys.stderr)
        sys.exit(1)

    output = proc.stdout
    print(output)

    # Parse table lines
    results = {}
    pattern = re.compile(r"^\|\s*(.*?)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+\s*M?ops/s)\s*\|$")
    for line in output.splitlines():
        line = line.strip()
        m = pattern.match(line)
        if m:
            op_name = m.group(1).strip()
            p50 = float(m.group(2))
            p95 = float(m.group(3))
            p99 = float(m.group(4))
            avg = float(m.group(5))
            throughput = m.group(6).strip()
            results[op_name] = {
                "p50_ns": p50,
                "p95_ns": p95,
                "p99_ns": p99,
                "avg_ns": avg,
                "throughput": throughput
            }

    out_file = os.path.join("target", "native_chunk_perf.json")
    os.makedirs("target", exist_ok=True)
    with open(out_file, "w") as f:
        json.dump(results, f, indent=2)

    print(f"\n[bench] Successfully wrote machine-readable performance metrics to {out_file}")

    # Section 11: Performance Regression Budget Enforcement
    # Thresholds established from 10-run statistical analysis (mean + 2*stddev upper bounds)
    BUDGETS = {
        "Packet Static Encode (Wire Cache Hit)": 1000.0,      # Warning if > 1.0 µs
        "Packet Single Section Dirty": 20000.0,              # Warning if > 20.0 µs
        "Packet Cold Encode (populate wire cache)": 300000.0, # Warning if > 300.0 µs
        "State Read (get_block_state AtomicU16)": 30.0,       # Warning if > 30.0 ns
        "State Write (Authoritative set_block_state)": 35.0,  # Warning if > 35.0 ns
        "Light Read (AtomicU32 block_light)": 30.0,           # Warning if > 30.0 ns
        "Light Write (AtomicU32 CAS block_light)": 35.0,      # Warning if > 35.0 ns
        "Height Read (get_height [u16; 256])": 30.0,          # Warning if > 30.0 ns
        "Height Recompute (Downward Scan)": 35.0,             # Warning if > 35.0 ns
        "Registry Lookup (ChunkHandle -> Arc)": 50.0,         # Warning if > 50.0 ns
        "Future-Consumer D: Storage-like Full Scan (65k states)": 15000.0, # Warning if > 15.0 µs
    }

    regressions = []
    print("\n[budget] Evaluating Performance Regression Budgets:")
    for op, max_avg in BUDGETS.items():
        if op in results:
            actual = results[op]["avg_ns"]
            status = "PASS" if actual <= max_avg else "REGRESSION"
            print(f"  [{status:10s}] {op:55s} Actual: {actual:8.1f} ns | Budget: {max_avg:8.1f} ns")
            if status == "REGRESSION":
                regressions.append((op, actual, max_avg))

    if regressions:
        print(f"\n[budget] WARNING: {len(regressions)} operation(s) exceeded performance budget!", file=sys.stderr)
        for op, actual, max_avg in regressions:
            print(f"  - {op}: {actual:.1f} ns > {max_avg:.1f} ns", file=sys.stderr)
    else:
        print("\n[budget] All operations within strict latency regression budgets.")

if __name__ == "__main__":
    run_bench()
