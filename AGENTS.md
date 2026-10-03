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

Central module: `tools/testing/test_execution_policy.py` (`--test-tier
dev|standard|milestone|soak`; explicit `--stability-s` / `--boot-timeout-s`
overrides win). Full policy: `docs/engineering/TEST_EXECUTION_POLICY.md`.

Before adding or modifying a RustCraft test campaign, select the narrowest
test tier that proves the requested property. Routine runs must not use
SOAK. Any test exceeding the MILESTONE tier must state what time-dependent
failure mode requires it.

## Repo conventions

- Rust workspace under `crates/`; JNI boundary only in `crates/ffi`.
- Campaign/test runners under `tools/authority-review/`; shared helpers in
  `tools/live-shadow-v2/` (`run_join_probe.py`, `join_probe.py`).
- Evidence before claims: every authority milestone ships receipts, logs,
  and scan results; documents under `docs/research/` cite them.
- `PRODUCTION_AUTHORITY` stays false unless a dedicated milestone proves
  otherwise. Networking is PARKED; NBT semantic authority is BLOCKED (H9).
- Validation before push: `cargo fmt --check`, `cargo clippy --workspace
  --release`, `cargo test --workspace --release`; granular commits; verify
  local HEAD == origin/main and a clean tree.
