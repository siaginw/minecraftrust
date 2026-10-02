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

if __name__ == "__main__":
    run_bench()
