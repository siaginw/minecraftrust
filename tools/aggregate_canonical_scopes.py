#!/usr/bin/env python3
"""M1.4-R: aggregate canonical scopes CSV into scope x fixture summary.

§5 authoritative comparison: SCOPE-C (Java production) vs SCOPE-C-N
(native production path) — identical semantic boundaries.
Component scopes (A/A-N/B) kept for decomposition only; cross-scope
ratios are NOT production speedups.
"""
import csv, math, statistics, sys, collections

path = sys.argv[1] if len(sys.argv) > 1 else "machine/raw/M14R-canonical-scopes.csv"
rows = list(csv.DictReader(open(path, encoding="utf-8")))
groups = collections.defaultdict(list)
plen = {}
for r in rows:
    if r["scope"] not in ("SCOPE-A", "SCOPE-A-N", "SCOPE-B", "SCOPE-C", "SCOPE-C-N"):
        continue  # EQUALITY hash-audit rows are not timing samples
    key = (r["scope"], r["fixture"], r["mask_hex"], r["sections"], r["full_chunk"])
    ns = int(r["elapsed_ns"])
    groups[key].append(ns)
    plen[key] = int(r["payload_bytes"])


def stats(v):
    """n>=1 sorted sample list -> dict of stats; None percentiles if n==0."""
    n = len(v)
    if n == 0:
        return None
    return {
        "n": n,
        "mean": statistics.fmean(v) / 1000.0,
        "p50": v[n // 2] / 1000.0,
        "p90": v[min(n - 1, int(n * 0.90))] / 1000.0,
        "p95": v[min(n - 1, int(n * 0.95))] / 1000.0,
        "p99": v[min(n - 1, int(n * 0.99))] / 1000.0,
        "std": (statistics.pstdev(v) / 1000.0) if n > 1 else 0.0,
    }


print(f"{'scope':10} {'fixture':12} {'mask':7} {'sec':3} {'full':5} {'n':5} {'p50_us':8} {'p90_us':8} {'p99_us':8} {'mean_us':8} {'bytes':7}")
agg = collections.defaultdict(list)
for key in sorted(groups):
    v = sorted(groups[key])
    s = stats(v)
    if s is None:  # cannot happen from defaultdict iteration, guard anyway
        print(f"{key[0]:10} {key[1]:12} {key[2]:7} {key[3]:3} {key[4]:5} NO_SAMPLES")
        continue
    print(f"{key[0]:10} {key[1]:12} {key[2]:7} {key[3]:3} {key[4]:5} {s['n']:5} {s['p50']:8.2f} {s['p90']:8.2f} {s['p99']:8.2f} {s['mean']:8.2f} {plen[key]:7}")
    agg[key[0]].extend(v)

print("\n== per-scope totals (all fixtures pooled) ==")
for scope in ("SCOPE-A", "SCOPE-A-N", "SCOPE-B", "SCOPE-C", "SCOPE-C-N"):
    v = sorted(agg[scope])
    s = stats(v)
    if s is None:
        print(f"{scope:10} NO_SAMPLES")
        continue
    print(f"{scope:10} n={s['n']} p50={s['p50']:7.2f}us p90={s['p90']:7.2f}us p95={s['p95']:7.2f}us p99={s['p99']:7.2f}us mean={s['mean']:7.2f}us std={s['std']:6.2f}us")

# §5 AUTHORITATIVE equal-scope comparison: SCOPE-C vs SCOPE-C-N per fixture
print("\n== AUTHORITATIVE: SCOPE-C (Java production) vs SCOPE-C-N (native production), per fixture ==")
jc = {k[1:]: v for k, v in groups.items() if k[0] == "SCOPE-C"}
nc = {k[1:]: v for k, v in groups.items() if k[0] == "SCOPE-C-N"}
for key in sorted(jc):
    if key not in nc:
        print(f"{key[0]:12} mask={key[1]:7} sec={key[2]:3} full={key[3]:5} NO_NATIVE_SAMPLES")
        continue
    js = stats(sorted(jc[key]))
    ns_ = stats(sorted(nc[key]))
    if js is None or ns_ is None:
        print(f"{key[0]:12} mask={key[1]:7} sec={key[2]:3} full={key[3]:5} NO_SAMPLES")
        continue
    d = js["p50"] - ns_["p50"]
    pct = (d / js["p50"]) * 100.0 if js["p50"] else float("nan")
    print(f"{key[0]:12} mask={key[1]:7} sec={key[2]:3} full={key[3]:5} "
          f"java_p50={js['p50']:7.2f} native_p50={ns_['p50']:7.2f} "
          f"native_delta_us={d:6.2f} native_delta_pct={pct:6.1f}%")

# component decomposition (NOT production comparison)
print("\n== component decomposition (cross-scope; NOT a production speedup) ==")
ja = {k[1:]: v for k, v in groups.items() if k[0] == "SCOPE-A"}
nat = {k[1:]: v for k, v in groups.items() if k[0] == "SCOPE-A-N"}
for key in sorted(ja):
    if key in nat:
        a = sorted(ja[key])
        nn = sorted(nat[key])
        a50 = a[len(a) // 2] / 1000.0
        n50 = nn[len(nn) // 2] / 1000.0
        print(f"{key[0]:12} mask={key[1]:7} sec={key[2]:3} full={key[3]:5} javaA_p50={a50:7.2f} nativeAN_p50={n50:7.2f} component_ratio={n50 / a50:5.2f}")
