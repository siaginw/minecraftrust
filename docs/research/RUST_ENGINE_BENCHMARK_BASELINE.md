# Rust Engine Benchmark Baseline (audit §6/§7/§36/§37/§38)

Machine: this repo's dev box (Windows). JVM: Adoptium 8.0.504.1, -Xmx6G.
Campaign: `run_region_write_campaign --test-tier standard --stability-s 90`
(event-driven; identical world copies, probe route, 73-command mutation
workload, save sequence, and 90s sampling window per run). MSPT telemetry:
per-tick reservoir on `field_71311_j` (the 100-slot ring) + GC/heap/CPU
via MXBeans, emitted to region-metrics.txt every 2s (audit telemetry,
measurement-only).

## Configurations

- **JAVA baseline**: light SHADOW (Java owns, observed — shadow-compare
  overhead only), region OFF, no world registry. Java slightly penalized
  by shadow observation (conservative in Rust's favor; noted).
- **RUST FULL STACK** (current validated bounded authorities): light
  ON_EXPERIMENTAL (zero-staging, seam mirrors) + world-registry + region
  write ON_EXPERIMENTAL + region read ON_EXPERIMENTAL. Packet/compression
  authorities remain OFF in these runs (default; historical receipts
  cited separately — see §38 below).

## Results (see receipts under target/authority-review/audit-*)

| Run (target/config) | verdict | ticks | MSPT mean | p50 | p95 | p99 | >50ms | >100ms | GC n/time | heap | CPU avg/max | light jobs/committed |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A-java4 (SHADOW, region off) | PASS | 1300 | 6.44ms | 4.0 | 8.0 | 49.5 | 13 | 5 | 601 / 17.3s | 1236MB | 0.087 / 0.35 | 0 / 0 |
| A-rust (FULL STACK) | FAIL* | 4000 | 3.02ms | 0.2 | 8.0 | 41.0 | 23 | 8 | 1654 / 41.8s | 357MB | 0.052 / 0.36 | 1733 / 0* |
| A-rust light-only (std3 receipt) | PASS | — | — | — | — | — | — | — | — | — | — | 648 / 40,236 |
| C-java (SHADOW, region off) | PASS | 1569 | 126.4ms | 26.0 | 100† | 100† | 722 | 672 | 1097 / 202.6s | 1189MB | 0.312 / 0.52 | 0 / 0 |
| C-rust (FULL STACK) | run† | 2315 | 10.3ms | 0.5 | 14.5 | 100† | 31 | 31 | 1355 / 224.9s | 1153MB | 0.327 / 0.53 | 1714 / 8* |

\* F-1 composition finding: with region-write authority active, the
save-path publication refreshed 10,000 sections, keeping native light
current — zero-stage jobs are then legitimate rc=0 no-ops (committed=0/8
is LEGAL under full-stack composition; the light-only std3 run shows the
committed cells when publication isn't pre-syncing). The campaign's
"committed>0" gate belongs to the light-only composition.
\† Histogram saturates at the 100ms bucket; p95/p99 read as ">=100ms".
C-java vs C-rust is a SINGLE-run pair and partially confounded (boot/
worldgen share of ticks differs across runs) — do NOT quote the mean
delta as a Rust win; repetitions with matched steady-state windows are
the follow-up (§44). A-pair direction is consistent (lower mean, lower
CPU avg in the Rust run) but same single-run caveat applies.

Extract with: `python tools/authority-review/extract_audit_baseline.py
target/authority-review/audit-*`.

## Notes

- Single runs per cell at STANDARD tier (event-driven, ~1800 ticks
  sampled per run): enough for a first audit baseline; §44 discipline
  says repetitions before quoting percentage deltas — treat differences
  within ~±10% as noise until repeated.
- Allocation rate: Java 8 has no per-thread alloc counter exposed here;
  GC count/time + heap are the proxies (reported honestly).

## §38 historical vs current

- Region write p50 15.8µs vs 25.4µs vanilla (prior milestone receipt):
  STILL VALID mechanism-wise (engine unchanged this phase); not re-run
  this session.
- Compression 1.27–2.16× per-packet (68,351-body corpus receipt): STILL
  VALID; corpus retained; not re-run this session.
- Single-copy encoder 0.46–1.51µs emission (receipt): STILL VALID;
  unaffected by state-width change (wire caches rebuilt on invalidation;
  §19 packet audit re-measured locally in the bake-offs: unique-count
  cost 48µs p50 per rebuild on direct-u32 sections — cached in
  practice).
