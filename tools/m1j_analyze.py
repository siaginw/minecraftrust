#!/usr/bin/env python3
"""M1J analyzer: Revelation streaming regression investigation.

Per run (labels j-c01..j-c10, odd=OFF even=ON):
  walk1 = settle_end -> walk_mid (warmup-sensitive half)
  walk2 = walk_mid   -> walk_end  (warmer half)
  MSPT stats from cumulative histogram snapshots; GC/safepoint/GCLocker from
  gc log windowed by timeline; m1 counters from run-log shutdown block;
  packet volume from bot transcript.
"""
import glob
import json
import os
import re
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m1i_analyze import hist_from_file, stats_from_hist, sub_hist, sod  # noqa: E402

SRV = "machine/targetC/server"


def parse_gc_full(path):
    ev = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3}).*?\[(?:Full )?GC \(([^)]+)\)(?: \[PSYoungGen: (\d+K)->(\d+K)\(\d+K\)\])?.*?, (\d+\.\d+) secs\]", line)
        if m:
            sodv = (int(m.group(2)) * 3600 + int(m.group(3)) * 60 + int(m.group(4))) + int(m.group(5)) / 1000.0
            cause = m.group(6)
            kind = "full" if line.find("[Full GC") >= 0 else "young"
            mb = 0.0
            if m.group(7):
                mb = (int(m.group(7)[:-1]) - int(m.group(8)[:-1])) / 1024.0
            ev.append((sodv, kind, float(m.group(9)) * 1000, mb, cause))
            continue
        m2 = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3}).*?application threads were stopped: (\d+\.\d+) seconds", line)
        if m2:
            sodv = (int(m2.group(2)) * 3600 + int(m2.group(3)) * 60 + int(m2.group(4))) + int(m2.group(5)) / 1000.0
            ev.append((sodv, "stop", float(m2.group(6)) * 1000, 0.0, "safepoint"))
    return ev


def m1_from_log(label):
    d = {}
    for line in open(f"{SRV}/run-{label}.log", encoding="utf-8", errors="replace"):
        m = re.search(r"(?<![\w.])(m1_[a-z_]+|transformer\.transformCount)=(\S+)", line)
        if m:
            d[m.group(1)] = m.group(2)
    return {k: int(v) for k, v in d.items() if v.lstrip("-").isdigit()}


def bot_stats(label):
    p = f"machine/targetA/client-{label}-walk.jsonl"
    rows = [json.loads(l) for l in open(p)] if os.path.exists(p) else []
    tot = sum(r["chunk_data"] for r in rows)
    by = sum(r["chunk_bytes"] for r in rows)
    return tot, by / 1e6


def load_run(label):
    tl = f"machine/raw/M1J-{label}.timeline"
    phases = [(l.split()[0], l.split()[1]) for l in open(tl) if l.strip()]
    d = dict(phases)
    hists = {ph: hist_from_file(f"machine/raw/M1J-{label}-{ph}.mspt") for ph in d if ph != "walk_mid"}
    hists["walk_mid"] = hist_from_file(f"machine/raw/M1J-{label}-walk_mid.mspt")
    gc = parse_gc_full(f"{SRV}/gc-{label}.log")
    m1 = m1_from_log(label)
    bot = bot_stats(label)

    def win(a, b):
        ta, tb = sod(d[a]), sod(d[b])
        return [e for e in gc if ta <= e[0] < tb], max(1, tb - ta)

    def phase(a, b):
        ha, hb = hists.get(a), hists.get(b)
        if ha is None or hb is None:
            return None
        return stats_from_hist(sub_hist(hb, ha))

    out = {"label": label, "m1": m1, "bot_pkts": bot[0], "bot_MB": bot[1]}
    for name, a, b in [("walk_all", "settle_end", "walk_end"),
                       ("walk1", "settle_end", "walk_mid"),
                       ("walk2", "walk_mid", "walk_end")]:
        s = phase(a, b)
        g, dur = win(a, b)
        young = [e for e in g if e[1] == "young"]
        gclocker = [e for e in young if "GCLocker" in e[4]]
        stops = [e for e in g if e[1] == "stop"]
        out[name] = {
            "stats": s, "dur_s": dur,
            "yGC": len(young), "gclocker": len(gclocker),
            "fullGC": sum(1 for e in g if e[1] == "full"),
            "gcPauseMs": sum(e[2] for e in g if e[1] != "stop"),
            "allocMB": sum(e[3] for e in young),
            "stops": len(stops), "stopMs": sum(e[2] for e in stops),
        }
    return out


def pstats(s):
    if not s:
        return "n/a"
    return (f"p50={s['p50']:.1f} p95={s['p95']:.1f} p99={s['p99']:.1f} "
            f"mean={s['mean']:.1f} max={s['max']:.0f} over50={s['over50']}")


def main():
    runs = [load_run(os.path.basename(p)[len("M1J-"):-len(".timeline")])
            for p in sorted(glob.glob("machine/raw/M1J-j-c*.timeline"))]
    arms = {"OFF": [r for i, r in enumerate(runs) if i % 2 == 0],
            "ON": [r for i, r in enumerate(runs) if i % 2 == 1]}
    for r in runs:
        arm = "OFF" if runs.index(r) % 2 == 0 else "ON "
        print(f"\n== {r['label']} ({arm})  bot: {r['bot_pkts']} pkts / {r['bot_MB']:.1f} MB")
        for w in ["walk_all", "walk1", "walk2"]:
            x = r[w]
            print(f"  {w:<8} {pstats(x['stats'])} | yGC={x['yGC']} gclkr={x['gclocker']} "
                  f"full={x['fullGC']} gcMs={x['gcPauseMs']:.0f} alloc={x['allocMB']:.0f}MB "
                  f"({x['allocMB']/x['dur_s']:.0f}MB/s) stops={x['stops']}({x['stopMs']:.0f}ms)")
        m = r["m1"]
        nat, jav = m.get("m1_native_packets_transmitted", 0), m.get("m1_java_packets_transmitted", 0)
        pkts = max(1, nat + jav)
        print(f"  m1: native={nat} java={jav} pregate={m.get('m1_native_pregate_skipped',0)} "
              f"outMB={m.get('m1_bytes_output',0)/1e6:.0f} | per-packet ns: "
              f"stage={m.get('m1_stage_ns',0)/pkts:.0f} predict={m.get('m1_payload_predict_ns',0)/pkts:.0f} "
              f"alloc={m.get('m1_alloc_ns',0)/pkts:.0f} jni={m.get('m1_jni_rust_ns',0)/pkts:.0f} "
              f"pregate={m.get('m1_pregate_ns',0)/pkts:.0f} total={m.get('m1_total_native_ns',0)/pkts:.0f}")
        w = r["walk_all"]
        if w["stats"]:
            mbps = r["bot_MB"] / w["dur_s"]
            print(f"  norm: streamMB={r['bot_MB']:.1f} ({mbps:.2f} MB/s) "
                  f"meanMSPT_per_MB={w['stats']['mean']/max(mbps,0.01):.1f} ms/MB "
                  f"pkts={r['bot_pkts']} meanPktKB={r['bot_MB']*1024/max(1,r['bot_pkts']):.1f}")

    print("\n######## ARM SUMMARY (walk_all) ########")
    for metric, get in [
        ("p50_ms", lambda r: r["walk_all"]["stats"]["p50"]),
        ("p95_ms", lambda r: r["walk_all"]["stats"]["p95"]),
        ("p99_ms", lambda r: r["walk_all"]["stats"]["p99"]),
        ("mean_ms", lambda r: r["walk_all"]["stats"]["mean"]),
        ("over50", lambda r: r["walk_all"]["stats"]["over50"]),
        ("yGC", lambda r: r["walk_all"]["yGC"]),
        ("allocMBps", lambda r: r["walk_all"]["allocMB"] / r["walk_all"]["dur_s"]),
        ("gcMs", lambda r: r["walk_all"]["gcPauseMs"]),
        ("bot_pkts", lambda r: r["bot_pkts"]),
        ("bot_MB", lambda r: r["bot_MB"]),
        ("meanMSPT_per_MB", lambda r: r["walk_all"]["stats"]["mean"] / max(0.01, r["bot_MB"] / r["walk_all"]["dur_s"])),
    ]:
        o = [get(r) for r in arms["OFF"]]
        n = [get(r) for r in arms["ON"]]
        wins = sum(1 for a in n for b in o if a < b)  # fraction of ON-better pairs
        print(f"  {metric:<16} OFF med={st.median(o):>8.1f} [{min(o):.1f}..{max(o):.1f}]  "
              f"ON med={st.median(n):>8.1f} [{min(n):.1f}..{max(n):.1f}]  ON-better pairs={wins}/{len(o)*len(n)}")


if __name__ == "__main__":
    main()
