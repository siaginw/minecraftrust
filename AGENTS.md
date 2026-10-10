# AGENTS.md — persistent guidance for coding agents working on RustCraft

Read this file before writing or modifying tests, campaigns, or runners.

## Test execution policy (mandatory)

`TIME IS NOT COVERAGE. EVENTS ARE EVIDENCE.`

- Prefer EVENT COUNTS and COVERAGE over elapsed time. Long duration is not
  evidence by itself.
- Never use 590/600-second holds as a routine default. A timeout is a
  CEILING, not a required sleep duration.
- Once evidence criteria are reached: short post-target stability hold
  (seconds), then terminate.
- Restart tests prove restart/load/play — they do not idle for minutes.
- Long soak tests are explicit opt-in ONLY, justified by a time-dependent
  failure mode (leaks, resource lifetime, backpressure, races, churn,
  sustained GC/queue pressure). SOAK must never become the default.
- Do not weaken proof criteria when shortening: keep the event-count target,
  drop the arbitrary waiting.

Central module: `tools/testing/test_execution_policy.py` (campaign surface:
`tools/campaign/policy.py`; `--test-tier dev|standard|milestone|soak`;
explicit `--stability-s` / `--boot-timeout-s` / `--hard-timeout-s` overrides
win). Full policy: `docs/engineering/TEST_EXECUTION_POLICY.md`. Shared
campaign toolkit: `tools/campaign/` (session/waits/evidence/restart/receipt/
telemetry) — see `docs/engineering/CAMPAIGN_TOOLING.md`. Before creating or
modifying a live campaign: use the shared toolkit, choose the narrowest
tier, define evidence targets explicitly, terminate when targets + stability
succeed, emit a standard receipt, justify any SOAK, and never introduce an
unexplained long fixed wait.

Before adding or modifying a RustCraft test campaign, select the narrowest
test tier that proves the requested property. Routine runs must not use
SOAK. Any test exceeding the MILESTONE tier must state what time-dependent
failure mode requires it.

## Bytecode / mapping navigation (use this before manual javap/grep)

For any Minecraft 1.12.2 / Forge bytecode, SRG/notch/MCP naming, call-graph,
field-access, or "which mod touches X" question, query the symbol index
instead of manual `javap`/`jar tf`/`grep`/mapping-CSV archaeology:

```bash
python tools/symbols/rustcraft_symbols.py status            # present? which sources?
python tools/symbols/rustcraft_symbols.py map World.checkLightFor
python tools/symbols/rustcraft_symbols.py method func_180500_c
python tools/symbols/rustcraft_symbols.py callers func_180500_c
python tools/symbols/rustcraft_symbols.py field-readers net.minecraft.world.EnumSkyBlock.BLOCK
```

If `status` reports no index, build it once (~35 s, read-only over jars,
never touches campaigns): `python tools/symbols/rustcraft_symbols.py build`
(or `build --incremental` to reuse unchanged artifacts via the per-artifact
SHA cache; use a `"mods_dir"` spec to index a whole mod pack). Self-test:
`python tools/symbols/self_test.py`. Ground truth lives there:
`checkLightFor` = `func_180500_c`; `func_175638_a` = `getRawLight` — never
assume mappings from memory. `live-diff`/`forge-changes` warn on
Forge-version provenance mismatch (goal §31); `--strict-provenance` makes
it fatal. Full command reference:
`docs/engineering/BYTECODE_SYMBOL_INDEX.md`.
The index is navigation evidence only — authority claims still need
campaign receipts.

## Pre-boot lint + run evidence (use before/after every campaign boot)

Before building/booting a campaign, run the static bridge/hook consistency
lint — it catches the bug classes that already cost boots (JNI native/export
name mismatches behind swallowed catches, big-endian ByteBuffers feeding the
LE Rust kernel, cross-loader `Class.forName`, EnumSkyBlock ordinal gates):

```bash
python tools/runscope/rustcraft_runscope.py lint     # fatal findings => fix first
```

Scripted source edits must go through `runscope patch` (verified occurrence
counts, atomic write, postconditions; `--check` detects when a later edit
clobbered an earlier patch — the dev8-12 failure mode):

```bash
python tools/runscope/rustcraft_runscope.py patch --file <path> --replace "OLD===NEW" --must-contain "REGEX"
```

Build-time identity gate: every `field_`/`func_` literal in bridge sources
is checked against the symbol index during every campaign jar build
(`tools/runscope/srg_literal_lint.py`; unknown id = build fails; justify
inline with `SRG-LINT-JUSTIFIED`). Verify transformers offline with
`runscope verify-transformer --target-class <notch-or-mapped>` — notch
names auto-map so class gates on `transformedName` fire; a TRANSFORM-NOP
on a class the transformer claims means the gate is wrong, not that
there is nothing to check. Campaign receipts enforce hook LIVENESS under
`--light-mutations` (`HOOK_CHUNK_LOADED`/`HOOK_BLOCK_SETS`/
`MIRROR_STATE_SETS` > 0; absent counters fail — absent == stale jar ==
dead), and `region-metrics.txt` carries every hook counter by FIELD name
via the reflection dump, so new counters need no wiring.

After a run, one command replaces the grep/JSON archaeology (receipt digest,
STALE staged-jar detection, timeline, exception histogram, light counters,
guarded paired bake-off table with boot-anomaly quarantine;
`compare` deltas two runs):

```bash
python tools/runscope/rustcraft_runscope.py report  target/authority-review/<run>   # use --timeline
python tools/runscope/rustcraft_runscope.py compare <runA> <runB>
```

The full pre-boot gate (~5s total; all four, in this order):

```bash
python tools/runscope/rustcraft_runscope.py lint            # 0 actionable = clean; fatal blocks
python tools/runscope/rustcraft_runscope.py expect          # 6/6 seam invariants (update, never delete)
python tools/runscope/rustcraft_runscope.py preflight --port <port> --target <T>
```

Other standing commands:

- `decode-solve --pairs pairs.json` — derive shift/width/bias/signedness for
  ANY packed format crossing a seam from 2+ captured (lanes -> packed) pairs;
  emits Java+Python decoders + pinned vectors. Widths are upper bounds unless
  vectors span the lane (capture one extreme pair). Recovered Phosphor's
  `y<<52 | (x+2^25)<<26 | (z+2^25)` from 5 vectors.
- `symbols body <ident> [--calls-only] [--layer L]` — a method's real bytes,
  canonical names inline (replaces javap+awk). `fields <class>` prints
  static-final ConstantValue (lX=26 etc., MOD_JARs included).
- `report --timeline` merges light-counter changes + per-step mutation events
  + seam-decisions.jsonl (the hooks' JSONL decision log — the writer lives in
  PhosphorLightHook/WorldLightHook; spec in docs/engineering/RUNSCOPE.md).
- Sentinel-degradation catches (swallow + return literal, no record call) are
  lint-flagged: add a recordError call or a RUNSCOPE-JUSTIFIED reason. The
  (0,0,0) class is lint-visible; do not reintroduce it.

Read-only, stdlib-only, `--json` for agents. Check catalog + evidence:
`docs/engineering/RUNSCOPE.md`. Lint findings are diagnostic hints with
cited incidents — a clean lint is not a correctness proof.

## Optimization policy (mandatory for every OPT-* item)

PROFILE first; then EXTERNAL RESEARCH before implementing: search
current crates.io/GitHub/relevant engines-literature for the problem
class (record candidates + versions/licenses/dates in
docs/research/RUST_ENGINE_EXTERNAL_OPTIMIZATION_RESEARCH.md — do not
rely on model memory for crate status). Shortlist, then bake off ALL
candidates through ONE harness on the REAL corpus
(docs/research/RUST_ENGINE_BENCHMARK_HARNESS.md — WorldAccess is the
light seam; define the seam trait for other subsystems there). Exact
semantic digest equality precedes performance; whole-job timing beats
microbenchmarks; a component win is a hypothesis. Promotion requires
the live Rust-before/after server A/B. REJECT with evidence is a valid
result; closed semantics (MC rules, state identity) are never reopened
by performance work. The loop's standing entry points:
`python tools/optimization/profile_rebase.py target/authority-review/<runs>`
(PROFILE — current hotspot ranking) and
`python tools/optimization/render_results.py <OPT-ID>` (bake-off table +
target/optimization-research receipt) — PROFILE → SELECT → RESEARCH →
BAKE-OFF → PROMOTE → LIVE A/B → REPROFILE, re-profiling after every win.

Allocation attribution on the pinned JDK8 build trusts ONLY the
observer's 1ms delta+stack sampler
(`-Drustcraft.observer.allocSampler=true`, diagnostic runs) and
op-scoped `getThreadAllocatedBytes` brackets: JFR `profile`-template
TLAB sampling is inert here (1MB sampled vs a 26GB storm) and
execution-sample shares are safepoint-biased (they once pinned 80% on a
site that owned 1GB of 24GB) — treat both as ordering hints, never
causal evidence (receipt OPT-FS-001).

Diagnostics on seam/hook paths must be BUDGETED or triggered by the
condition they diagnose — never run-per-call behind a failure-only
guard: `diagnoseTokenMismatch` ran its full reflective introspection on
every writerEnd for ~52GB/run because its only early-exit was a latch
that never fires on a healthy server (same receipt).

## Repo conventions

- Rust workspace under `crates/`; JNI boundary only in `crates/ffi`.
- Campaign/test runners under `tools/authority-review/`; shared helpers in
  `tools/live-shadow-v2/` (`run_join_probe.py`, `join_probe.py`).
- Evidence before claims: every authority milestone ships receipts, logs,
  and scan results; documents under `docs/research/` cite them. Receipts
  pin provenance by run ID + artifact sha256-16 — git SHAs are secondary
  context only (history rewrites must not orphan evidence).
- `PRODUCTION_AUTHORITY` stays false unless a dedicated milestone proves
  otherwise. Networking is PARKED; NBT semantic authority is BLOCKED (H9).
- Validation before push: `cargo fmt --check`, `cargo clippy --workspace
  --release`, `cargo test --workspace --release`; granular commits; verify
  local HEAD == origin/main and a clean tree.
