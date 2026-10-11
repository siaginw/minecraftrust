#!/usr/bin/env python3
"""Provenance-hash block for run receipts — prints sha256-16 of a run's
artifacts plus the current builds, ready to paste (retro fix #3: a
hand-copied hash arrived truncated once; the printer cannot).

  python tools/runscope/run_provenance.py target/authority-review/<run> [<run>...]
  python tools/runscope/run_provenance.py <run> --json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))


def h16(path):
    if not os.path.isfile(path):
        return None
    return hashlib.sha256(open(path, "rb").read()).hexdigest()[:16]


def provenance(run_dir):
    run_dir = run_dir.rstrip("\\/")
    run_id = os.path.basename(run_dir)
    artifacts = ("fullstack-run.json", "region-metrics.txt", "server.log")
    prov = {"run_id": run_id}
    for name in artifacts:
        v = h16(os.path.join(run_dir, name))
        if v:
            prov[name.replace("fullstack-run.json", "receipt")
                   .replace("region-metrics.txt", "metrics")
                   .replace("server.log", "log") + "_sha256_16"] = v
    # run-effective build hashes come from the run's own artifact record
    # (captured at staging) — the current build may have been rebuilt since
    # (first use of this script caught exactly that: the receipt carried a
    # pre-rebuild DLL hash)
    receipt = os.path.join(run_dir, "fullstack-run.json")
    if os.path.isfile(receipt):
        try:
            rec = json.load(open(receipt, encoding="utf-8"))
            arts = rec.get("artifacts") or {}
            if arts.get("campaign_jar_sha16"):
                prov["run_campaign_jar_sha256_16"] = arts["campaign_jar_sha16"]
            if arts.get("dll_sha16"):
                prov["run_dll_sha256_16"] = arts["dll_sha16"]
        except (OSError, ValueError):
            pass
    for key, rel in (("current_campaign_jar_sha256_16",
                      os.path.join("target", "rustcraft-campaign-C.jar")),
                     ("current_dll_sha256_16",
                      os.path.join("target", "release", "rustcraft_ffi.dll"))):
        v = h16(os.path.join(REPO, rel))
        if v:
            prov[key] = v
    return prov


def main(argv):
    ap = argparse.ArgumentParser(prog="run_provenance")
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    provs = [provenance(r) for r in a.runs]
    if a.json:
        print(json.dumps(provs, indent=1))
        return 0
    for p in provs:
        print("run      %s" % p["run_id"])
        for k, v in p.items():
            if k != "run_id":
                print("  %-28s %s" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
