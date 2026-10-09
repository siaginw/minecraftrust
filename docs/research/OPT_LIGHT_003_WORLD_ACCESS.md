# OPT-LIGHT-003 — Section-Local World Access Fast Path

Research date 2026-10-09 · corpus af397a1ad904e519 (1,461 jobs, 25 real
chunks) · semantic digest 1cb41df2281eb10d (bake-off harness) and
0cb57a1491a02ce5 (kernel_corpus, unchanged by the promotion).

## Profile (§20/§21)

Per-cell world access (coordinate → chunk key → registry HashMap →
chunk RwLock → section → local index) per cell AND per neighbor read:
92.8% of accesses stay in the SAME section, 1.4% same chunk/other
section, 5.9% cross-chunk; 14 unique chunks across the whole job set.
⇒ a tiny job-local chunk-handle cache removes the registry map from the
hot path almost entirely.

## Harness (§10–§15)

`crates/ffi/tests/world_access_bakeoff.rs`: `WorldAccess` trait (the §10
candidate interface) + `run_job_with_access` — the IDENTICAL kernel over
any candidate. One corpus load; every candidate's DIFF_DIGEST asserted
equal; locality from an instrumented direct wrapper; results feed
`tools/optimization/render_results.py` (markdown + JSON +
target/optimization-research receipt).

## Candidates (§22) — results on the frozen corpus

| Candidate | mean | p95 | Δmean | hit% | digest |
|---|---|---|---|---|---|
| A-direct (current) | 2.47ms | 10.44ms | — | – | 1cb41df2… |
| B-last-chunk (1 slot) | 1.90ms | 8.31ms | −23.1% | 94.1% | = |
| C2-assoc | 1.93ms | 8.16ms | −21.6% | 98.9% | = |
| C4-assoc | 1.96ms | 8.16ms | −20.6% | 100% | = |
| **C8-assoc (WINNER)** | **1.87ms** | **8.06ms** | **−24.0%** | **100%** | = |
| C16-assoc | 1.87ms | 8.15ms | −24.0% | 100% | = |
| D-neighbor3x3 prefetch | 1.87ms | 7.90ms | −24.0% | 100% | = |

C16/D tie C8 without earning their complexity (§17/§24); B is within 1%
but 94% hits < 100% at C8. Section bitset: N/A (production has no
section-local membership structure). External candidates: see the
research DB (R-L003-01..05) — no importable crate for this seam; the
ecosystem answer at N=8 IS the local linear scan.

## Promotion (§39/§41)

`SectionCacheAccess` (C8) is the production default in
run_zero_stage_job; `legacy-access` feature reproduces the old path.
Generation safety (§23): cache is per-job; cached-Arc staleness is
backstopped by the §11 job-end revalidation (identical contract);
commit path mirrors registry semantics including the Invalidated check.

Whole-kernel corpus A/B (both digest 0cb57a1491a02ce5):
legacy 2.088ms/9.03ms → cache 1.511ms/6.47ms (−27.6% mean, −28.4% p95).
Cumulative light-job mean vs the P0 baseline 2.62ms: **−42%**
(FxHash overlay −17%, then this −28%).

## Live gates (§43)

See receipts: target/authority-review/o3-A-before, o3-A-after,
o3-C-after (Rust-before/after server A/B + Gate C).
