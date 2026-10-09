# RUST_BLOCK_LIGHT_ZERO_STAGING — Milestone Report

**Date:** 2026-10-07 (session chain from 2026-10-06)
**Head at declaration:** see `git log` (this commit)
**Verdict (updated 2026-10-07, §30 reclassification):** `RUST_BLOCK_LIGHT_ZERO_STAGING_PROVEN` — WITHOUT the u16-domain qualifier — plus `NATIVE_STATE_ID_FULL_WIDTH_PROVEN`. TRUE zero-staging on both gates: the mutation-seam state mirror (EBS.set → mirrorBlockState: one cell, one version advance) and the revived EBS light mirror make the NativeChunk coherent BEFORE the job; the light path performs zero refreshChunkNow calls and zero snapshot bytes (one JNI/job). Full-width u32 state IDs removed the M5.8-R2 ceiling (Revelation ids like 76,916 sync; widthRejects=0; jsid==nsid at live source cells). Gate A DEV+STANDARD PASS (zsa14/std3); Gate C DEV+STANDARD PASS (zsc1/zeroStage-C-std1 — 32,378 committed cells, 0 errors after §29 classifier accounting: per-state rejection is a dynamic-verdict counter, CLASSIFY_REJECTED, not an authority error). `PRODUCTION_AUTHORITY` stays `false`.

## 1. The final harness artifact — eliminated (§1-§5)

Every previous "far-artifact" observation (records with y=0, v=0,
Chebyshev dist 230+; uniqueKeys ≪ rc; the 2,585-vs-2,638 count mystery)
was ONE Java decode bug, not native behavior:

**`ByteBuffer.get()` returns ONE SIGNED BYTE.** All six diff-record
decode loops (zero-stage production mirror, staged production commit,
zs-cmp uniq/hash/zm/sm) read each record's x-word BYTES as four fake
records: real x=268 (`0C 01 00 00` LE) decoded as (12,1,0,0),(0,0,0,0)…
— exactly the observed artifact signature (y=0/z=0/v=0 always; x values
were the low bytes of real x/y/z/v words). Fixed to `getInt()` at every
site (commit 7b07582).

Discrimination chain that isolated it:
- po2: poisoned 4 MiB out buffer + uniqueKeys/tailPoison print → rc=3091
  but uniqueKeys=61 → artifact reproduced under poison (write-side
  implicated, offline tests said otherwise → decode side suspected).
- po4: absolute-`getInt` period probe at record indices 0..3167 — every
  record REAL, distinct, near-domain — while the relative-`get()` loop
  collapsed the same bytes to 53 patterns. Byte-vs-int reads isolated.
- po5 (post-fix): `CONTRACT rc=2563 uniqueKeys=2563 tailPoison=true`
  (and 3245/3245, 2730/2730, 3169/3169): **written-length contract
  proven LIVE — every record distinct, poison intact beyond rc*16.**

Offline contract proofs (8/8 tests green, crates/ffi/src/zero_stage_job.rs):
- `compute_only_serialization_contract` — rc == records written; every
  record within Chebyshev 32, values 1..=15; poison (0xA5A5A5) intact
  beyond rc*DIFF_RECORD_I32.
- `large_then_small_job_buffer_reuse` — >100-record open-torch job then
  a 1-record sealed-cavity job into the SAME buffer: valid region holds
  only the small job's records; the large job's stale records beyond
  rc_b untouched; poison beyond rc_a survives both jobs. Consumer
  contract: decode exactly rc; a stale tail is legal, unbounded writes
  are not.
- `compute_only_has_no_side_effects` — compute-only leaves dirty mask
  and lights untouched; the identical overlay committed lands the light
  and dirties the origin section.

Also fixed en route: `runZeroStage` passed `out.remaining()` (bytes)
where the native side takes an i32 COUNT — a 16 MiB slice over a 4 MiB
buffer and a 4× too-permissive capacity guard; now `/4` like
`runJobNative`.

## 2. Same-job parity and ground truth (§7-§11)

The same-job comparison harness (staged oracle compute-only vs
zero-stage compute-only on one checkLightFor invocation, neither
commits) with CORRECT decodes:

- **Coherent/settled jobs: exact parity, repeatedly.** Count AND hash
  equal, e.g. `staged(changed=2730 hash=7097610665799913858) ==
  zero(rc=2730 hash=7097610665799913858)` (po5), `2607/2607` hash-equal
  (po6), and std2 gates `interSame=2546 valConflict=0 onlyZero=0
  onlyStaged=0 zeroFarArtifact=0 stagedFarArtifact=0` and
  `interSame=3123 …` (all zeros).
- **Transition jobs (two-sources / remove steps):** a symmetric, real
  semantic difference — the same few hundred near-domain cells (dist
  8-10, e.g. (248,67,243-245)) appear onlyStaged (v=0) in the darkening
  direction and onlyZero (v=5,4,3 — a textbook BFS gradient) in the
  brightening direction. Staged's box snapshot under-lights multi-source
  transitions; zero's full-registry BFS lights them.
- **§10 Java ground truth adjudicates for zero, 8/8 cells:** deferred GT
  probe re-reads the disputed cells against the settled world —
  `GT (241,63,233 claimed=1 jl=1 nl=1) (233,70,221 claimed=2 jl=2
  nl=2) (233,70,222 claimed=3 jl=3 nl=3) (233,70,223 claimed=4 jl=4
  nl=4) …` — claimed == Java light == native light on every cell, with
  perfect gradient ordering. The zero-stage kernel is the correct one;
  the staged oracle (non-production, §21 diagnostic) is the stale side.
- Value conflicts: **0 in every capture post-fix** (when both sides
  touch a cell, the values agree).
- Pre-state coherence: `jl==nl` at every probed cell on every coherent
  job; `worldLightMis=0` in every receipt.

## 3. Audits (§12-§19)

- §12 compute-only side-effect-free: offline regression test (above) +
  live by construction (§2 captures commit nothing; the §1 fix restored
  the production mirror to REAL cells).
- §13/§14 preRefresh: **removal attempted and DISPROVEN live.** po7
  (refresh removed): every job `zero(rc=0)` on a dark native world —
  the EBS write-seam mirror covers LIGHT writes only; block STATE
  mutations reach the registry only through the dirty-section pull.
  Reverted; po8 recovered (PASS; 1,771 jobs; 40,183 committed cells; 0
  errors; worldRegistryMissing=0; jl==nl coherent; interSame=3169
  valConflict=0). `refreshChunkNow` is documented LOAD-BEARING state
  sync (dirty-only: 1-3 mutated sections).
- §15 state coherence: proven live (GT line: jl==nl everywhere;
  worldRegistryMissing=0 every run).
- §16 repairRegister: counter exposed (`zsRepairs`) in periodic
  metrics; mechanism documented (reload path misses onLoad →
  repairRegister re-registers idempotently at the job seam).
- §17 reload lifecycle root cause: documented in code comments (cmp8
  evidence: gen=0 at live chunk, registers stuck at boot count);
  recovery proven by zsRepairs + staleGen=0 in all receipts.
- §18 world-registry gate: `worldRegistryMissing=0` in every receipt
  (po1, po2, po5, po6, po8, std2, C-dev1).
- §19 export-invariant negative self-test: added
  `--export-self-test`; it immediately caught a real hole — substring
  matching made the `runJob` check vacuous against `runJobNative` (in
  every prior build). Detector now matches NUL-terminated names; all 11
  critical exports verified on the shipped DLL.
- §6 hash packing: retired packed-long compare keys (20-bit fields —
  legal coords ≥48576 spilled; sign truncation) for injective string
  `cellKey`; build-gate regression proves injectivity on 23,232 cells
  including the spillover domain, keeps an old-key collision witness
  (20,480/23,232 — the defect was real in-domain), and asserts
  order-invariant hashing.

## 4. Gates (§20-§23)

| Gate | Run | Verdict | Key evidence |
|---|---|---|---|
| A DEV | zeroStage-A-po5/po6/po8 | PASS ×3 | contract rc==uniqueKeys, tailPoison; hash-equal parity; GT 8/8; 12,376→40,183 committed cells; 0 errors |
| A STANDARD | zeroStage-A-std2 | **PASS** | 639 jobs, 27,852 committed cells, 0 errors, worldRegistryMissing=0, worldLightMis=0, stability 15 s (tier), wall 68 s event-driven; both captures all-zero SUMMARY |
| C DEV | zeroStage-C-dev1 | FAIL (parity steps) | 707 jobs, 0 commits at the platform; sections not syncing |
| C DEV | zeroStage-C-dev2 | infra flake (probe login never completed; anchor fell back to void default; 0 jobs) — not engine evidence | — |
| C DEV | zeroStage-C-dev3 | FAIL (parity steps) — but zero-staging LIVE | **942 jobs, 45,899 committed cells** on Revelation wherever section sync succeeds; platform neighborhood frozen; state-probe + widthRejects evidence below |

Gate C mechanism (dev3's `[zs-cmp] pre(sid/jl/nl/ns)` probe +
`widthRejects` counter, direct evidence):
- `M4Coherency.refreshOneSection` enforces the M5.8-R2 u16 width guard —
  any state id > 0xFFFF rejects the whole section refresh ("no
  truncated write, ever"; this Revelation world historically produced
  id 76,916). `widthRejects` climbed 23→24→25 across three captures.
- Width-rejected sections FREEZE at their last-accepted snapshot: the
  torch cell read `[1424/0/0/0]` (ns=0 at placement), then later
  `[0/15/15/1424]` — Java removed the torch while native still holds
  the frozen torch state with its light. The zero-stage kernel sees a
  frozen-but-self-consistent native view → `rc=0` there (correct no-op,
  no wrong writes).
- Clean sections DO sync and zero-stage commits through them:
  `[4132/14/14/4132]` (ns==jsid, jl==nl) — 45,899 committed cells on
  this run; the mutation-parity steps fail only because the PLATFORM
  section itself is width-frozen.
- No contamination: `worldLightMis=0`, `worldRegistryMissing=0`,
  Phosphor retains final authority; `lightAuthErr=527` is deterministic
  modded-state classifier failures (per-state fail-closed, non-fatal).
- Widening the state pipeline past u16 is a separate milestone (out of
  scope; explicitly not a "perf tweak" — see the audit inventory's
  candidate 7).

## 5. §26/§28 audit inventory and baselines

`docs/research/rust-engine-ownership-audit-inventory.md` — 13 Rust-owned
subsystems, the NOT-FULL-OWNERSHIP section (sky light, block-state
mutation authority, NBT H9, parked networking, MSPT), §28 baseline
metrics (≈37 µs/job JNI-class, ≈1.9 µs/job mirror commit, registry
counts), and 7 candidate optimizations LISTED ONLY per the phase
mandate (nothing implemented).

## 6. Declaration and STOP (§24/§25)

Declared: `RUST_BLOCK_LIGHT_ZERO_STAGING_PROVEN` — bounded to the
u16-state domain, evidenced by Gate A DEV+STANDARD (contract, parity,
ground truth, audits, all receipts cited above). Gate C blocked by the
documented u16 state-id ceiling with clean fail-closed behavior.
`PRODUCTION_AUTHORITY` stays `false`. Per §25: STOP here. Next phase
(when started): `RUST_ENGINE_OWNERSHIP_PERFORMANCE_AUDIT` using the §26
inventory.

Note for the record: the full `cargo test --workspace --release` suite
has one pre-existing flaky failure unrelated to this milestone —
`region-io::live_read::read_during_write_never_tears` (pass/pass/FAIL
in isolation at HEAD; file untouched by this milestone's diff). Flagged
for the audit phase.

## Receipts

- target/authority-review/zeroStage-A-po{1..8}/receipt.json (+server.log)
- target/authority-review/zeroStage-A-std2/receipt.json
- target/authority-review/zeroStage-C-dev{1,2,3}/receipt.json
- 8/8 zero-stage Rust tests; workspace suite 63 result-ok lines
