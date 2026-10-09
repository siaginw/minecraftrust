# RUST_ENGINE_BENCHMARK_HARNESS

The plug-in candidate bake-off framework (born with OPT-LIGHT-003,
2026-10-09). Permanent project workflow: PROFILE → external RESEARCH →
SHORTLIST → ONE harness → REAL corpus → semantics + perf + memory →
live A/B → KEEP/REJECT.

## Core pieces

- **Candidate interface**: `WorldAccess` trait
  (crates/ffi/src/zero_stage_job.rs) — the per-cell registry-access
  seam. `run_job_with_access(access, …)` runs the IDENTICAL kernel over
  any candidate; `run_zero_stage_job` picks the production default.
  The same pattern (trait at the seam + `run_*_with` entry) is the
  extension point for other subsystems (§29): packet extraction,
  compression, region allocation — define the seam trait there when its
  first bake-off happens.
- **Candidate adapters** live in `crates/ffi/tests/world_access_bakeoff.rs`
  (B/C/D families + instrumented direct). Adding one = implement the
  trait; no benchmark logic duplicated.
- **One corpus**: `crates/ffi/tests/corpus_common/mod.rs` loads the
  frozen REAL corpus from the zsa14 campaign world .mca (hash
  af397a1ad904e519; set `RUSTCRAFT_CORPUS_MCA` to swap). All candidates
  consume identical bytes; corpus hash printed every run.
- **Semantic equality** (§16): every candidate's DIFF_DIGEST asserted
  equal to A-direct's within the same process; the kernel_corpus digest
  (0cb57a1491a02ce5) additionally pins the production path across
  feature builds.
- **Result schema + receipts** (§33/§34): bake-off lines are parsed by
  `tools/optimization/render_results.py <OPT-ID>` → markdown table +
  JSON receipt at `target/optimization-research/<OPT-ID>.json`
  (timestamped, corpus + locality + per-candidate stats + winner).
- **A/B features**: `legacy-overlay` (P0 hasher), `legacy-access`
  (OPT-LIGHT-003) reproduce prior production paths for before/after
  builds; `vec-head-fifo` keeps the rejected P0 candidate runnable.

## Candidate categories supported (§15)

DROP-IN CRATE / CUSTOM LOCAL / ALGORITHM PORT / DATA-LAYOUT PROTOTYPE /
ARCHITECTURAL PROTOTYPE — all enter through the same trait.

## Promotion gate (§39/§41)

exact digest equality → whole-corpus win → memory/dependency cost review
→ production default behind the inverse `legacy-*` feature → Gate A/C →
Rust-before/after server A/B → KEEP/REJECT recorded in the backlog.
