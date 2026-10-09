# Rust Engine Ownership Audit Inventory (§26)

Created for RUST_ENGINE_OWNERSHIP_PERFORMANCE_AUDIT (next phase after
RUST_BLOCK_LIGHT_ZERO_STAGING_PROVEN). This is an INVENTORY ONLY per the
phase mandate: candidate optimizations are listed, NOT implemented, NOT
benchmarked. Baseline metrics captured in §28 below; run receipts cited.

Milestone context: RUST_BLOCK_LIGHT_ZERO_STAGING_PROVEN (see
docs/research/zero-staging-milestone.md) — block light is fully
zero-staged: World.checkLightFor(BLOCK) → ONE JNI call
(LightAuthorityBridge.runZeroStage) → RegistryWorld over the
world-lifecycle NativeChunk registry → direct native commit + diff-out
Java mirror. SKY stays Java/Phosphor.

## Rust-owned live subsystems (13)

| # | subsystem | crate(s) | ownership state | entry boundary |
|---|---|---|---|---|
| 1 | Region file READ (Anvil decode) | region-io | SHADOW-proven, live on A+C | RegionFileReadTransformer → RustRegionReadHook |
| 2 | Region file WRITE (Anvil encode) | region-io, transport | proven (Direct Netty wire emission boundary, fd5c459) | RegionFileAuthorityTransformer → RustRegionWriteHook |
| 3 | Compression (zlib/deflate ladder) | compression | full ladder proven; default OFF (READY_OPTIONAL) | NativeCompressionEncoder |
| 4 | Chunk packet encode (SPacketChunkData body) | chunk-packet, native-state-vnext | proven correct; value blocked on Java staging (M1) | SPacketChunkDataTransformer |
| 5 | Block light engine (BLOCK dim) | light-engine, native-chunk, ffi | **ZERO-STAGED, PROVEN** (this milestone) | CheckLightAuthorityTransformer → LightAuthorityHook |
| 6 | Sky light engine | — | **NOT OWNED (by policy)** — SKY stays Java/Phosphor | passthrough in LightAuthorityHook |
| 7 | NativeChunk registry (world lifecycle) | native-chunk | world-lifecycle bound (onLoad/onUnload), EBS write-seam mirror, reload repair | NativeChunkRegistryHook + ChunkMutationTracker |
| 8 | Block-state classification table (id→opacity/emission) | ffi (bridge side build) | session-complete, fail-closed on unknown id | LightAuthorityHook.ensureFullTable |
| 9 | Worldgen density shim | worldgen-noise | SHADOW_ACTIVE on Target A (bit-exact 240-chunk corpus) | WorldgenShadowTransformer |
| 10 | Spawn-query index | spatial-index, spawn-index | validated 114.9× skip; not wired (value blocked on state ownership) | — |
| 11 | Collision broadphase counters | spatial-index | 46.2% ceiling measured; not wired | — |
| 12 | Light batch ctx lifecycle | ffi (LightBatchCtx) | staged-path native ctx; create/closeRaw export-gated | LightBatchCtx |
| 13 | Observation/capture pipeline (frames, sessions) | tools/bridge capture package | live on campaign runs; session-bound admission | LiveShadowCoreMod chain |

## NOT-FULL-OWNERSHIP (explicit)

- SKY LIGHT: policy decision (goal §"SKY STAYS JAVA/PHOSPHOR") — the
  zero-stage claim covers BLOCK only.
- BLOCK STATE AUTHORITY: states flow Java→native via dirty-section pull
  (refreshChunkNow at the job seam — proven LOAD-BEARING in the §14
  audit: removing it made every job rc=0 because state mutations are
  invisible to the kernel without the pull) and the section-mutation
  marks. Rust reads/queries states but does not own mutations.
- NBT semantics: BLOCKED (H9) per AGENTS.md.
- Networking beyond region/chunk seams: PARKED per AGENTS.md.
- MSPT/tick pipeline: untouched (no A/B performed in this milestone by
  mandate).

## §28 Baseline metrics (capture only, no optimization)

Source: target/authority-review/zeroStage-A-po6/receipt.json +
server.log + region-metrics.txt (dev tier, Target A, 1,741 zero-stage
jobs, 25,170 committed cells, verdict PASS):

- zeroStageJobs=1741; committedCells=25170; errors=0; staleGen=0
- jniNanos (cumulative across jobs) ≈ 6.4e7 ns for ~1741 jobs ≈ 37 µs/job
  median-class (includes frontier BFS over up to 9 chunks)
- commitNanos (Java mirror, now REAL cells post getInt fix) ≈ 3.3e6 ns
  cumulative ≈ 1.9 µs/job average at ~14 cells/job typical
- worldRegistryRegisters=634; unregisters=336; worldRegistryMissing=0
- Compare-harness budget lines: staged compute ≈ 2–3× zero rc on
  transition jobs; GT probe confirms zero correctness (8/8 cells)

## §33 memory impact of full-width u32 state cells (measured sizes)

- Section states array: 8,192 B (u16) → 16,384 B (u32) = +8,192 B per
  RESIDENT section; light arrays unchanged (2×2,048 B).
- Empty section: no states array allocated beyond the boxed section
  (sections allocate lazily); typical Revelation section +8 KiB.
- Worst case (all 16 sections): +128 KiB/chunk; 1,000 loaded sections
  ≈ +8 MiB. Loaded-set reality (C-std1: ~270-630 chunks, 4-6 resident
  sections each) ≈ +9-31 MiB — the honest price of lossless state
  identity, accepted by design choice A (§12/§13 rationale).

## §32 baseline metrics (capture-only; receipts zsa14/std3/C-std1)

- Mirror seam: one state mirror ≈ single JNI + registry write-lock; HOOK
  overhead ≈ 1,800-2,850 sets/run absorbed with 0 errors.
- Zero-stage: ~37 µs/job JNI-class; ~1.9 µs/job Java mirror commit; job
  totals 629-1,830/run; committed 32-40k cells/run.
- Table build: full registry classification once per session;
  Revelation ~110k states incl. 289 permanent-dynamic verdicts.

## Final lighting architecture (§34 freeze)

Java mutation → EBS.set seam (state mirror) + EBS light-write seam
(light mirror) → NativeChunk already coherent → World.checkLightFor →
ONE JNI (runZeroStage) → registry traversal → native commit + diff-out
Java mirror. No light-path refreshChunkNow (bootstrap/load sync only;
async dirty-refresh worker retained for non-seam paths: skylight
regen, direct container writes). SKY stays Java/Phosphor.
PRODUCTION_AUTHORITY false; kernel/fuzz unchanged (no 100k rerun).

## Candidate optimizations (LISTED ONLY — do not implement in this phase)

1. Per-job direct ByteBuffer reuse (allocation-free out buffer) — the
   4 MiB direct allocate per job is the largest per-job fixed cost after
   the fix; a pooled/sliced buffer removes it. Risk: buffer-lifetime
   discipline; §1-5 contract tests already pin the invariants.
2. Dirty-section pull batching — refreshChunkNow runs per job; multiple
   jobs in a tick on the same chunk re-pull. Dedup via a per-tick
   generation stamp.
3. Diff-out skip when commit target already coherent (jl==nl pre-check
   box) — most worldgen-era jobs commit 0; early-out before BFS.
4. Table build caching — ensureFullTable rebuilds per job in the compare
   path only (production reuses zsTable); audit zsTable lifetime for
   cache-across-jobs.
5. Frontier visited-cap tuning — 1<<22 default; measure actual
   high-water (region-metrics visited cells) to right-size.
6. mirrorBlockLight batching — EBS writes during relight bursts are
   one-JNI-per-cell; a small coalescing buffer at the tracker cuts JNI
   crossings (M4.2D follow-up).
7. (audit-only) §14 finding: the state pull could become a state
   write-seam mirror (setBlockState hook) to eliminate the per-job
   refresh — SEMANTIC CHANGE, needs its own milestone, not a perf tweak.
