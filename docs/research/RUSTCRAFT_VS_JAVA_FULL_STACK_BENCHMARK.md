# RustCraft vs Java/Forge/Revelation — Full-Stack Benchmark (2026-10-09)

**Question: is current RustCraft faster than normal Java/Forge/Revelation?**

**Answer: NO — under this benchmark's workloads the current RustCraft
full-stack configuration is SLOWER than clean Java on every measured phase
(chunk streaming 3.7–5.2× higher mean MSPT, active-world mutation ticks
3.7–5.0×, save 3× wall / 3.7–5.8× MSPT, boot +30–70 s, process CPU +10–15%,
server-thread allocation ~20×), while producing byte-equivalent-confirmed
world mutations, zero validation mismatches, and zero authority failures.**

This compares two server configurations on the same machine, same modpack,
same world snapshot, same deterministic workload — it is NOT a general claim
about Rust vs Java as languages.

## Configurations (frozen identities)

| | Arm A — JAVA | Arm B — RUSTCRAFT |
|---|---|---|
| Runtime | Forge 14.23.5.2846, FTB Revelation 3.4.0, JVM Adoptium 8.0.504.1, -Xmx6G, view-distance 6 | identical |
| RustCraft | **NONE** (no javaagent, no coremod, no DLL; verified: no rustcraft jar on the classpath, no hooks) | campaign jar (javaagent) + ffi DLL; C2 sync default |
| Authorities | — | world-registry + native state, block-light ON_EXPERIMENTAL (zero-stage), region read ON_EXPERIMENTAL, region write ON_EXPERIMENTAL (mirror publication); **packet/compression authorities OFF** (audit-default subset — this is the audit-baseline composition, not every experimental flag) |
| Measurement | observer jar only (tweaker, NO transformers): tick-identity MSPT ring sampler (250 ms; no recount/lag-phantom; MsptDedupRegression 9/9 at build) + CPU/GC/heap MXBeans | same observer jar appended as third tweaker — identical overhead; RustCraft's own telemetry (m1) also runs (its cost belongs to Arm B) |

Artifacts: observer jar sha16 `d555c6f5a7e1652a` (5,957 bytes, no transformers), campaign jar-C and
DLL sha16 in each run's `fullstack-run.json`; world snapshot = frozen
`runtimeC/world` tree-hash copied fresh per run (same hash every run).

## Workload (identical both arms; §5-grade confirmation)

- PHASE A — streaming: probe joins (external protocol client), 41-waypoint
  deterministic teleport route from the verified login anchor (~65.6 s).
  169 chunk packets delivered in EVERY run (both arms) — identical streaming work.
- PHASE B — mutations: the proven 13-step console setblock workload at
  salt 0 (fresh world copy per run ⇒ same cells). Confirmation is POSITIVE:
  per-cell final state verified via armor-stand `execute … detect … say`
  (`CONFIRM` lines); 67/67 cells confirmed in every run except fs1r (42/67 —
  see caveats).
- PHASE C — save: 3× `save-all flush` (+ graceful stop; stop-time separate).
- Boot→Done recorded separately (never pooled with steady-state).

## Results (3 balanced pairs; java→rust / rust→java / java→rust)

MSPT = actual server tick duration (ms). `p50/p95` with tick counts per cell.

| Phase (metric) | Java (3 runs) | RustCraft (3 runs) | Paired ratio (rust/java) | Verdict |
|---|---|---|---|---|
| Streaming mean MSPT | 6.35 / 6.91 / 7.53 | 30.29 / 36.02 / 36.59 | 4.8× / 5.2× / 4.9× | **SLOWER** |
| Streaming p50 MSPT | 2.39–2.42 | 12.69–14.50 | 5.3–6.1× | **SLOWER** |
| Streaming p95 MSPT | 5.18–6.08 | 37.21–233.23 | 7.2–38× | **SLOWER** |
| Mutations mean MSPT | 4.79 / 6.24 / 6.30 | 24.10 / 17.36* / 23.06 | 5.0× / 2.8×* / 3.7× | **SLOWER** |
| Mutations p50 MSPT | 1.55–2.03 | 9.80–10.45 | ~5× | **SLOWER** |
| Save wall (3 flushes) | 1.5 s ×3 | 4.5 s ×3 | 3.0× | **SLOWER** |
| Save mean MSPT | 22.7–31.1 | 84.6–192.9 | 3.7–5.8× | **SLOWER** |
| Boot → Done | 150–160 s | 192–224 s | +30–70 s | **SLOWER** (cold start, labeled) |
| Streaming process CPU | 264–271 s | 297–312 s | +10–15% | **SLOWER** |
| Streaming srv-thread alloc | 1.23–1.28 GB | 23.8–25.9 GB | ~20× | **SLOWER** |
| GC count / pause (streaming) | 168–239 / 29.5–31.3 s | 148–209 / 31.6–33.5 s | comparable | NO_CLEAR_DIFFERENCE |
| Chunk packets delivered | 169 / 169 / 169 | 169 / 169 / 169 | 1.0 | work equivalent |
| Mutations confirmed | 67/67 ×3 | 42/67*, 67/67, 67/67 | — | work equivalent (caveat) |

\* fs1r confirmed 42/67 cells inside its confirmation windows (first rust
run; commands landed late against busy ticks). Its mutation-phase numbers
are labeled partial-work; pairs 2–3 are clean and carry the verdict.

Tick counts are near-identical within every pair (e.g. streaming 1315/1200,
1315/1339, 1320/1329) — the rust arms did the same logical work on slower
ticks. Time-reduction vs speedup are kept distinct in the machine receipt
(`fs-analysis.json` + `analyze_fullstack_ab.py --pairs`).

## Did the Rust authorities actually run? (§6)

All three rust runs (witnesses from region-metrics + server log):

- world registry: 629–651 registrations, 2,998–3,092 sections refreshed,
  SECTIONS_VALIDATION_MISMATCH=0, DUAL_VERIFY_DIVERGE=0 (C2 sync + 3-way
  verification live and clean)
- block-light: 1,867–2,046 jobs admitted, 33,300–40,272 cells committed,
  fallbacks 135–171 (bounded, counted), MIRROR_PUBLISHED == committed (0
  dropped)
- region read: 629–641 selected/success
- region write: 1,111–1,252 entries rust-ok, 0 failed, 0 vanilla fallbacks
- packet/compression authorities: NOT_EXERCISED (deliberately off — the
  audit-baseline subset; a "full stack" claim is not made for them)

ARMED→EXECUTED for everything enabled; no cap exhaustion; fallback shares
reported per run in the receipt.

## What this does and does not establish

- Establishes: on THIS pinned Revelation runtime, world snapshot, and
  workload family (chunk streaming / concentrated setblock mutations /
  save-flushes), the composed Rust-owned stack costs substantially more
  server time than clean Java, with correctness intact (0 mismatches,
  positive mutation confirmation, identical delivered-chunk counts).
- Does NOT establish: player capacity limits (headroom math only), any
  claim about workloads not measured (worldgen, redstone, entities, NBT,
  real client compression traffic), or component-level blame — see below.
- The prior audit-baseline (single-run, shadow-loaded "java" arm) showed
  the same direction; this benchmark replaces it with the honest pair
  structure (clean java arm, 3 pairs, phase-separated, validated sampler).

## Where the slowdown plausibly lives (measured, §12 attribution hooks)

1. Server-thread allocation storm under composition: ~25 GB allocated by
   the server thread during one 65 s streaming phase (~380 MB/s) vs ~1.2 GB
   clean — the single largest measured gap and the recommended next
   attribution target (candidates visible in existing counters: registration
   full-sync path, mirror publication, observation/capture machinery, m1
   telemetry — NOT yet separated by this task).
2. Per-tick floor: mutation-phase p50 ~10 ms vs ~1.8 ms — a persistent
   per-tick cost (hooks on every setblock/light/save seam) on top of the
   allocation pressure.
3. Boot: +30–70 s (javaagent transformation of mod classes + registration
   during spawn-area load) — cold-start only, labeled separately.
4. Save: 3× wall — region-write mirror publication path (its own audit row
   says p50 15.8 µs/entry × ~1.2 k entries ≈ 19 ms — NOT the explanation;
   the save-phase MSPT inflation is dominated by the same per-tick +
   allocation pressure, exact split unmeasured).

## Recommended next measured target (ONE)

**OPT-FS-001: attribute the ~380 MB/s server-thread allocation storm during
chunk streaming under full-stack composition** (profile the server thread's
allocation sites across the composed authorities; the OPT-SYNC-006 lease
machinery eliminated the known sync-path churn, so this is a NEW signature
specific to composition). No production change in this task; the bake-off
design follows the standing harness rules once the sites are measured.

## Reproduction

```bash
python tools/build_campaign_jar.py --target C
python tools/build_campaign_jar.py --observer-only target/rustcraft-observer.jar
python tools/authority-review/run_fullstack_ab.py --arm java --port 25621 --output <dir> --username FSJA
python tools/authority-review/run_fullstack_ab.py --arm rust --port 25622 --output <dir> --username FSRU
python tools/authority-review/analyze_fullstack_ab.py <java-dir> <rust-dir> --pairs
```

Raw artifacts: `target/authority-review/fs1j fs1r fs2r fs2j fs3j fs3r`
(+ smokes `sAB-smoke-*`), machine receipt `target/authority-review/fs-analysis.json`.

## Follow-up (2026-10-09, OPT-FS-001): dominant attribution + one bounded fix

**What caused the excess allocations, and what changed?** The dominant
server-thread cost under composition was `LiveWriterHooks.diagnoseTokenMismatch`
— a campaign-evidence diagnostic that reflectively introspected the writer
gate's token stack on EVERY bracketed region-file operation (guarded only by
a never-true-on-healthy-servers latch). ~52 GB/run attributed by a 1 ms
delta+stack sampler (`fs-attr2`), plus the reflection CPU. Budgeted to its
first 64 calls (evidence value preserved):

| Rust arm | before (fs1r/2r/3r) | after (fs-fix1/2) |
|---|---|---|
| Streaming mean MSPT | 30.3 / 36.0 / 36.6 | **21.1 / 12.2 (−51%)** |
| Streaming p95 | 37–233 | **11.5–14.7** |
| Mutations mean MSPT | 17.4 / 24.1 / 23.1 | **11.7 / 8.2 (−54%)** |
| Save mean MSPT | 84.6–192.9 | **30.7 / 37.6** |
| Streaming srv-thread alloc | 23.8–25.9 GB | 20.3–21.1 GB (−18%) |
| Correctness | mism=0, 67/67 confirms | identical |

Also attributed and exonerated along the way: onCheckLight (1.14 GB/7.2 s
total — the JFR sample share was safepoint-biased), buildTable+zsOut (real
2 ms/job admission work), the C2 sync path (34 MB). **The clean-Java
regression is NOT closed**: after the fix the rust arm still runs ~2–3×
java's streaming MSPT; the next measured bucket is LaunchClassLoader
transformer churn (~20 GB/run) → OPT-FS-002. Receipt:
`docs/research/OPT-FS-001-receipt.json`.

## Follow-up 2 (2026-10-09, OPT-FS-002): transformer premise refuted; authorities exonerated of the allocation storm; one local fix below noise

- The FS-002 lead (defineClass/ZipFile/ASM ~20GB) is NORMAL Forge+mod
  classloading: the clean-Java arm allocates ~70GB at boot too;
  LaunchWrapper transforms each class exactly once; ~121 classes load
  during streaming. RustCraft's boot delta is +15–18GB (extra passes +
  agent + registration) — a cold-start cost, already labeled separately.
- Authority ablations (diagnostic, labeled): region-write OFF → streaming
  19.5GB; light SHADOW → 22.1GB (vs 21.4GB full). Neither composed
  authority is the ~20GB streaming allocation storm. The mutations-phase
  MSPT gap IS attributed to the light composition (SHADOW 6.8ms ≈ Java
  4.8–6.3 vs 8.2–11.7 ON; CPU-bound, not allocation-bound).
- One real defect fixed (blockPosY per-call reflection on the hottest
  hook — 17.4× locally, ~4.7ms/run) — kept, but fs-fix3/4 vs fs-fix1/2
  show NO measurable end-to-end change (before-spread 12.2–21.1 swamps
  it). Honest verdict: the fix is correct and cheap, not a benchmark win.
- Remaining gap vs clean Java unchanged (~2–3× streaming MSPT). Next
  bounded experiment: staged build with the always-injected writer-gate
  brackets + observation capture property-disabled, to name or eliminate
  the integration layer as the streaming allocation source.
  Receipt: `docs/research/OPT-FS-002-receipt.json`.

## Follow-up 3 (2026-10-09, OPT-FS-003): the streaming allocation excess is the optional capture/observation session

Isolation experiment (`rustcraft.noCaptureSession=true`, new switch: skips
the capture session at bootstrap while EVERY authority stays registered and
running — bypass proven by `session SKIPPED` log line; work comparable:
same registrations, light commits in family, mism=0, 169 packets, 67/67
confirmed mutations):

| config | stream alloc | stream MSPT | mut alloc | mut MSPT | save alloc |
|---|---|---|---|---|---|
| capture ON (control) | 19.9 GB | 14.9 | 6.3 GB | 13.4 | 2.7 GB |
| capture OFF | **1.6 GB** | 8.4 | 0.5 GB | 8.8 | 0.09 GB |
| capture OFF (confirm) | **1.8 GB** | 14.0* | 0.5 GB | 9.8 | 0.09 GB |
| clean Java (reference) | 1.2–1.3 GB | 6.4–7.5 | 0.4 GB | 4.8–6.3 | 0.07–0.08 GB |

*slow-boot variance mode; allocation stable across both.

**Allocation attribution is now complete: ~92% of the streaming allocation
excess (and ~97% of the save-phase excess) is the diagnostic capture/observation
layer — NOT the engine, authorities, or sync.** With capture off, RustCraft's
streaming allocation is ≈ clean Java's (residual ~0.3–0.5 GB = integration
hooks + authorities). MSPT is a separate outcome and only partially improves:
a ~1.3–2× MSPT gap remains (8.4–14.0 vs 6.4–7.5), consistent with the
mutations-phase light-composition attribution. Scope: this is an isolation
experiment (writer-gate enforcement absent by design) — the composed
benchmark result above stands unchanged; the capture layer is part of
RustCraft's qualified-campaign shape and its cost belonged to Arm B.
Receipt: `docs/research/OPT-FS-003-receipt.json`.
