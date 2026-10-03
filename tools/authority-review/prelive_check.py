"""Pre-live gate for region read/write campaigns (upgrade #1).

Runs the offline proofs that catch every live-campaign bug found to date —
in seconds, no Minecraft:
  1. region-io unit + live-read + mixed-writer + capacity-fuzz suites
  2. FFI read/write suites
  3. policy/tooling self-tests

Run before ANY live campaign:  python tools/authority-review/prelive_check.py
Exits non-zero on the first failing suite.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SUITES = [
    ["cargo", "test", "-p", "region-io", "--release"],
    ["cargo", "test", "-p", "ffi", "--release", "region_write"],
    ["cargo", "test", "-p", "ffi", "--release", "region_read"],
    [sys.executable, "tools/campaign/self_test.py"],
    [sys.executable, "tools/campaign/evidence_db_test.py"],
    [sys.executable, "tools/testing/test_execution_policy_test.py"],
]


def main() -> int:
    for suite in SUITES:
        print(f"[prelive] {' '.join(suite)}")
        r = subprocess.run(suite, cwd=str(ROOT), capture_output=True, text=True)
        tail = (r.stdout + r.stderr).strip().splitlines()
        if r.returncode != 0:
            print("\n".join(tail[-15:]))
            print(f"PRELIVE_CHECK_FAILED at {' '.join(suite)}")
            return 1
        for line in reversed(tail):
            if line.startswith("test result:") or line.endswith("PASSED"):
                print(f"  {line.strip()}")
                break
    print("PRELIVE_CHECK_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
