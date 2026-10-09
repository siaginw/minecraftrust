#!/usr/bin/env python3
"""M2CP analyzer: per-run metrics from Revelation matched OFF/ON study.

Inputs per run: machine/targetC/server/run-<label>.log (shutdown dump with
m2c counters + netty_worker_cpu), machine/raw/M2CP-<label>-metrics-final.txt
(mspt cumulative), machine/raw/M2CP-<label>-gc.log (JDK8 PrintGCDetails),
machine/targetA/client-<label>-walk.jsonl (bot transcript).

Output: per-run table + per-arm median/range + ratios. Stdout only.
"""
import json, re, statistics, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RUNS = [("m2cp-c-o1", "OFF"), ("m2cp-c-n1", "ON"),
        ("m2cp-c-o2", "OFF"), ("m2cp-c-n2", "ON"),
        ("m2cp-c-o3", "OFF"), ("m2cp-c-n3", "ON")]


def counters_from_log(path):
    txt = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    m = re.search(r"M1 metrics at shutdown(.*?)(?:Stopping server|\Z)", txt, re.S)
    block = m.group(1) if m else ""
    out = {}
    for k, v in re.findall(r"^(m2c_[a-z_]+|\S*_ns)=(\d+)", block, re.M):
        out[k] = int(v)
    mm = re.search(r"netty_worker_threads=(\d+) netty_worker_cpu_ms=(\d+)", block)
    if mm:
        out["netty_worker_cpu_ms"] = int(mm.group(2))
    return out


def mspt_from_metrics(path):
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    txt = p.read_text(encoding="utf-8", errors="replace")
    ms = re.search(r"mspt_n=(\d+) mspt_max_ms=([\d.]+) mspt_mean_ms=([\d.]+) mspt_deadline_over_50ms=(\d+)", txt)
    if not ms:
        return {}
    return {"mspt_n": int(ms.group(1)), "mspt_max": float(ms.group(2)),
            "mspt_mean": float(ms.group(3)), "mspt_over50": int(ms.group(4))}


def gc_summary(path):
    p = pathlib.Path(path)
    if not p.exists():
        return {}
    txt = p.read_text(encoding="utf-8", errors="replace")
    gcs = re.findall(r"^\S+T[\d:. -]+: [\d.]+: \[.*(Pause .*?)(\d+)K->(\d+)K\(\d+K\), ([\d.]+) secs\]", txt, re.M)
    # JDK8 parallel GC lines: "[GC (Metadata GC Threshold) ... 123K->456K(789K), 0.012 secs]"
    pauses = [float(x) for x in re.findall(r", ([\d.]+) secs\]", txt)]
    n_full = len(re.findall(r"^\S+T[\d:. -]+: [\d.]+: \[Full", txt, re.M))
    n_young = len(re.findall(r"^\S+T[\d:. -]+: [\d.]+: \[GC ", txt, re.M))
    stopped = [float(x) for x in re.findall(r"application threads were stopped: ([\d.]+) seconds", txt)]
    return {"gc_young": n_young, "gc_full": n_full,
            "gc_pause_total_ms": round(sum(pauses) * 1000, 1),
            "app_stopped_total_ms": round(sum(stopped) * 1000, 1)}


def walk_stats(label):
    p = ROOT / "machine" / "targetA" / f"client-{label}-walk.jsonl"
    rows = [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    r = rows[0]
    return {"chunks": r.get("chunk_data", 0), "frames": r.get("frames", 0),
            "errors": r.get("errors", 0), "hops": r.get("tp_hops", 0)}


WALK_SECONDS = 400.0
rows = []
for label, arm in RUNS:
    c = counters_from_log(ROOT / "machine" / "targetC" / "server" / f"run-{label}.log")
    c.update(mspt_from_metrics(ROOT / "machine" / "raw" / f"M2CP-{label}-metrics-final.txt"))
    g = gc_summary(ROOT / "machine" / "raw" / f"M2CP-{label}-gc.log")
    w = walk_stats(label)
    elig = c.get("m2c_java_packets", 0) or c.get("m2c_native_packets", 0)
    plain = c.get("m2c_bytes_in", 0) or c.get("m2c_native_bytes_tx", 0)
    comp = c.get("m2c_bytes_java", 0) or c.get("m2c_bytes_rust", 0)
    ns = c.get("m2c_java_ns", 0) or c.get("m2c_rust_ns", 0)
    row = {"label": label, "arm": arm, "eligible": elig, "plain_MB": plain / 1e6,
           "comp_MB": comp / 1e6, "ratio_pct": 100.0 * comp / plain if plain else 0,
           "mb_per_s": plain / 1e6 / WALK_SECONDS,
           "pkts_per_s": elig / WALK_SECONDS,
           "compress_s": ns / 1e9, "us_per_pkt": ns / 1000.0 / elig if elig else 0,
           "compress_MBp_s": plain / 1e6 / (ns / 1e9) if ns else 0,
           "netty_cpu_s": c.get("netty_worker_cpu_ms", 0) / 1000.0,
           "mspt_mean": c.get("mspt_mean", 0), "mspt_max": c.get("mspt_max", 0),
           "mspt_over50": c.get("mspt_over50", 0), **g, **w}
    rows.append(row)

hdr = ["label", "arm", "eligible", "plain_MB", "comp_MB", "ratio_pct", "mb_per_s",
       "pkts_per_s", "us_per_pkt", "compress_MBp_s", "compress_s", "netty_cpu_s",
       "gc_young", "gc_full", "gc_pause_total_ms", "mspt_mean", "mspt_max", "chunks", "hops"]
print("\t".join(hdr))
for r in rows:
    print("\t".join(
        f"{r[h]:.1f}" if isinstance(r[h], float) else str(r[h]) for h in hdr))

print("\n== per-arm summary (n=3 each) ==")
for metric in ["plain_MB", "mb_per_s", "pkts_per_s", "us_per_pkt", "compress_MBp_s",
               "compress_s", "netty_cpu_s", "ratio_pct", "gc_young", "gc_pause_total_ms",
               "mspt_mean", "chunks"]:
    offs = sorted(r[metric] for r in rows if r["arm"] == "OFF")
    ons = sorted(r[metric] for r in rows if r["arm"] == "ON")
    mo, mn = statistics.median(offs), statistics.median(ons)
    rel = (mn - mo) / mo * 100 if mo else float("nan")
    print(f"{metric:22s} OFF med={mo:12.1f} range=[{offs[0]:.1f},{offs[-1]:.1f}]  "
          f"ON med={mn:12.1f} range=[{ons[0]:.1f},{ons[-1]:.1f}]  rel={rel:+.1f}%")
