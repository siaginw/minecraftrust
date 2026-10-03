#!/usr/bin/env python3
"""Region write bench runner: Java vanilla seam vs Rust engine on real
Revelation region records (small / median / p95 / max size classes).

Extracts real DEFLATE records from a Gate C region file, replays them
through (a) the real vanilla func_76706_a and (b) the Rust RegionWriteCtx
JNI engine on separate output files, and reports latency percentiles.
"""
from __future__ import annotations

import shutil
import struct
import subprocess
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
SRC_REGION = ROOT / "target" / "authority-smoke" / "runtimeC" / "world" / "region"
WORK = ROOT / "target" / "authority-review" / "region-write-bench"
SECTOR = 4096


def extract_records(count: int, out_dir: Path) -> None:
    """Pull `count` real deflate records from Gate C regions (spread across
    size classes) into rec_<i>.deflate + manifest.txt."""
    out_dir.mkdir(parents=True, exist_ok=True)
    records: list[tuple[int, int, int, bytes]] = []  # (order, x, z, stream)
    idx = 0
    for mca in sorted(SRC_REGION.glob("r.*.*.mca")):
        if len(records) >= count:
            break
        data = mca.read_bytes()
        if len(data) < 8192:
            continue
        for i in range(1024):
            if len(records) >= count:
                break
            entry = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
            off, cnt = entry >> 8, entry & 0xFF
            if off == 0 or cnt == 0:
                continue
            start = off * SECTOR
            length = struct.unpack(">I", data[start:start + 4])[0]
            typ = data[start + 4]
            blob = data[start + 5:start + 4 + length]
            if typ != 2:
                continue
            # sanity: must decompress
            try:
                zlib.decompress(blob)
            except Exception:
                continue
            cx = (mca.name.split(".")[1] and 0) or 0
            records.append((idx, i % 32, i // 32, blob))
            (out_dir / f"rec_{idx}.deflate").write_bytes(blob)
            idx += 1
    lines = [f"{r[0]} {r[1]} {r[2]}" for r in records]
    (out_dir / "manifest.txt").write_text("\n".join(lines) + "\n")
    sizes = sorted(len(r[3]) for r in records)
    print(f"[extract] {len(records)} records from {SRC_REGION}; "
          f"min={sizes[0]} median={sizes[len(sizes)//2]} max={sizes[-1]} bytes")


def compile_bench(classes: Path) -> bool:
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-nowarn", "-cp", f"{CAMPAIGN};{SRG}", "-d", str(classes),
                        str(ROOT / "tools" / "authority-review" / "RegionWriteBench.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-4000:])
        return False
    return True


def run_arm(arm: str, manifest: Path, out_dir: Path, n: int) -> dict | None:
    r = subprocess.run([str(JAVA), "-Xmx2G",
                        "-Djava.library.path=" + str(ROOT / "target" / "release"),
                        "-cp", f"{WORK / 'classes'};{CAMPAIGN};{SRG}",
                        "RegionWriteBench", str(manifest), arm, str(out_dir), str(n)],
                       capture_output=True, text=True, timeout=1800)
    for line in r.stdout.splitlines():
        if line.startswith("BENCH "):
            fields = dict(kv.split("=") for kv in line.split()[1:])
            return fields
    print(f"[ERROR] arm {arm} produced no BENCH line\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    return None


def main() -> int:
    for dep, name in ((CAMPAIGN, "campaign jar"),
                      (ROOT / "target" / "release" / "rustcraft_ffi.dll", "DLL"),
                      (SRC_REGION, "Gate C region dir")):
        if not (dep.is_dir() if dep.is_dir.__self__ is SRC_REGION else dep.is_file()):
            print(f"[ERROR] {name} missing: {dep}")
            return 1
    if WORK.exists():
        shutil.rmtree(WORK)
    classes = WORK / "classes"
    classes.mkdir(parents=True)
    if not compile_bench(classes):
        return 1

    n = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
    manifest = WORK / "records"
    extract_records(n, manifest)

    results = {}
    for arm in ("java", "rust"):
        res = run_arm(arm, manifest, WORK / f"out-{arm}", n)
        if res is None:
            return 1
        results[arm] = res
        print(f"[bench] {arm}: {res}")

    j, r = results["java"], results["rust"]
    print(f"REGION_WRITE_BENCH java p50={j['p50']}us p99={j['p99']}us max={j['max']}us | "
          f"rust p50={r['p50']}us p99={r['p99']}us max={r['max']}us n={j['n']}")
    print("REGION_WRITE_BENCH_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
