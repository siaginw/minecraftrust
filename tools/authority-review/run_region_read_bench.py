#!/usr/bin/env python3
"""Goal §20-§23: offline read benchmark A/B over the real Revelation corpus.

Arms: java (vanilla RegionFile + Java inflate) vs rust (RegionReadCtx JNI
read + Rust decompress). WARM = second pass (page-cache hot). COLD-ish =
first pass over a FRESH copy of the corpus (first-touch; Windows offers no
honest cache flush — labeled accordingly). Allocation accounting is
by-design (reported per arm in the bench javadoc), not GC-measured.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
CORPUS = ROOT / "target" / "authority-smoke" / "runtimeC" / "world" / "region"
WORK = ROOT / "target" / "authority-review" / "region-read-bench"


def compile_bench(classes: Path) -> bool:
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8",
                        "-target", "8", "-nowarn",
                        "-cp", f"{CAMPAIGN};{SRG}", "-d", str(classes),
                        str(ROOT / "tools" / "authority-review" / "RegionReadBench.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-4000:])
        return False
    return True


def run_arm(arm: str, corpus: Path, cap: int) -> dict:
    r = subprocess.run([str(JAVA), "-Xmx4G",
                        "-Djava.library.path=" + str(ROOT / "target" / "release"),
                        "-cp", f"{WORK / 'classes'};{CAMPAIGN};{SRG}",
                        "RegionReadBench", str(corpus), arm, str(cap)],
                       capture_output=True, text=True, timeout=3600)
    for line in r.stdout.splitlines():
        if line.startswith("BENCH "):
            fields = dict(kv.split("=", 1) for kv in line.split()[1:] if "=" in kv)
            return fields
    print(f"[ERROR] arm {arm}: no BENCH line\n{r.stdout[-1500:]}\n{r.stderr[-1500:]}")
    return {}


def main() -> int:
    cap = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    classes = WORK / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    if not compile_bench(classes):
        return 1
    if not CORPUS.is_dir():
        print(f"[ERROR] corpus missing: {CORPUS}")
        return 1

    results = {"meta": {"cap": cap, "corpus_files": len(list(CORPUS.glob('*.mca')))}}

    # COLD-ish: fresh copy, first pass (first-touch)
    cold = WORK / "cold-copy"
    if cold.exists():
        shutil.rmtree(cold)
    cold.mkdir(parents=True)
    t0 = time.time()
    for f in CORPUS.glob("*.mca"):
        shutil.copyfile(f, cold / f.name)
    copy_secs = time.time() - t0
    results["meta"]["copy_secs"] = round(copy_secs, 1)

    for arm in ("java", "rust"):
        r = run_arm(arm, cold, cap)
        if not r:
            return 1
        results[f"{arm}_coldish"] = r
        print(f"[bench] {arm} COLD-ish: {r}")
    # WARM: second pass over the same (now hot) files
    for arm in ("java", "rust"):
        r = run_arm(arm, cold, cap)
        if not r:
            return 1
        results[f"{arm}_warm"] = r
        print(f"[bench] {arm} WARM: {r}")

    (WORK / "bench.json").write_text(json.dumps(results, indent=2, sort_keys=True) + "\n")
    jw, rw = results["java_warm"], results["rust_warm"]
    print(f"REGION_READ_BENCH warm: java p50={jw['p50']}us p99={jw['p99']}us "
          f"MBps={jw['MBps']} | rust p50={rw['p50']}us p99={rw['p99']}us "
          f"MBps={rw['MBps']} n={jw['n']}")
    print("REGION_READ_BENCH_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
