# RUST_ENGINE_OWNERSHIP_PERFORMANCE_AUDIT

Audit session 2026-10-09, HEAD at start 2870319 (clean, main == origin).
Baseline runs, bake-offs, and receipts under `target/authority-review/audit-*`.
Companion docs: `RUST_ENGINE_BENCHMARK_BASELINE.md` (whole-server A/B),
`RUST_ENGINE_OPTIMIZATION_BACKLOG.md` (ranked, measured),
`rust-engine-ownership-audit-inventory.md` (pre-audit inventory).

## Canonical subsystem table (§3/§4)

Component latencies from this session's bake-offs (offline, release,
p50 unless noted) and prior milestone receipts (cited). "Whole-server
effect" from the A/B baseline table (single STANDARD runs — treat
±10% as noise per §44).

| Subsystem | Status | Semantic owner | Java prerequisite | JNI/op | Copies/op | Allocs/op | Locks/atomics | Hot data structure | Component p50 | p95 | p99 | Whole-server effect | Fallback % | Known debt | Verdict | Priority |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 NativeChunk registry/lifecycle | PROVEN CURRENT | Rust (world-lifecycle bound) | none (onLoad/onUnload seams) | 1 per chunk load/unload | 0 | registry entry | RwLock map; AtomicU64 gen | HashMap<ChunkKey, Arc<RwLock>> | sub-µs lookups | — | — | none visible | 0% (worldRegistryMissing=0) | reload-repair path retained (0 firings in gates) | KEEP | P3 |
| 2 NativeSection state storage (u32) | PROVEN CURRENT | Rust | none (seam mirror) | 0 (in-Rust) | 0 | 0 | AtomicU32 cells | [AtomicU32;4096] + palette/wire caches | random read 0.90µs/4096; scan 0.40µs; mutate 0.80µs/256 | 0.90µs | ~0.9µs | none visible (baseline A/B within noise) | 0% width rejects | +8KiB/section (§48 validated: palette alt loses ~2× hot reads) | KEEP | P3 |
| 3 Chunk.getBlockState | PROVEN CURRENT | Rust (direct-memory) | none | 0 via Unsafe pointers; 1 via probe (diagnostic) | 0 | 0 | plain/volatile read | u32 cell @4B stride | ~ns class (§27 confirmed: direct-read path) | — | — | none | 0% | pointer contract is width-coupled (documented) | KEEP | P3 |
| 4 Chunk.setBlockState | PROVEN CURRENT | Java writes, Rust mirrors at EBS.set seam | none | 1 mirror/cell | 0 | 0 | registry write-lock + 1 version bump | see 1/2 | mirror ≈ µs class (2.7k/run absorbed, 0 errors) | — | — | none | 0% (liveness-gated) | direct BlockStateContainer writes by mods un-mirrored (pull covers) | KEEP | P3 |
| 5 EBS façade | PROVEN CURRENT | shared (write seam) | none | 1/state + 1/light per write | 0 | 0 | — | seam hooks | µs class | — | — | none | 0% | light hook notch-name drift risk (linted) | KEEP | P3 |
| 6 block-light DATA | PROVEN CURRENT | Rust (registry arrays + mirrors) | none | 0 after mirror | 0 | 0 | AtomicU32 nibble words | 512×AtomicU32/section | ~ns reads (CAS writes) | — | — | none | 0% | sky stays Java (policy) | KEEP | P3 |
| 7 block-light PROPAGATION (zero-stage) | PROVEN CURRENT | Rust | NONE (true zero-staging; no refresh, no snapshots; 1 JNI/job) | 1/job | 0 snapshot bytes; diff-out rc×16B | overlay/visited maps + frontier Vecs/job | none (single-threaded jobs) | VecDeque frontier + SipHash overlay | ~37µs/job class (JNI incl.) | — | — | parity exact; committed cells 32–40k/run (light-only comp.) | <1% dynamic (per-state §29 verdicts) | F-1: under full-stack composition, save-path publication pre-syncs light → jobs often rc=0 no-ops (LEGAL; gate reinterpretation documented) | OPTIMIZE | P0 |
| 8 biome | PROVEN CURRENT | Rust | push at load/refresh | 0 hot | 0 | 0 | — | [u8;256] | ~ns | — | — | none | 0% | — | KEEP | P3 |
| 9 height | PROVEN CURRENT | Rust | maintained at mirror | 0 hot | 0 | 0 | AtomicU16 | [u16;256] | ~ns | — | — | none | 0% | — | KEEP | P3 |
| 10 retained packet body / wire cache | PROVEN CURRENT | Rust | none | 0 (cache hit) | 1 wire copy on miss | palette Vecs on rebuild | none | wire_cache bytes | hit: ~0; rebuild: 48µs unique-count + pack | 80µs | — | none measured | n/a | rebuild cost on dirty (P2 watch item) | KEEP | P3 |
| 11 single-copy packet body | PROVEN (experiment, default off) | Rust | none | 1 encode | 1 payload copy | 1 buffer | — | SingleCopy pipeline | 0.46–1.51µs emission (receipt) | — | — | not enabled in baseline runs | n/a (bounded cap) | — | KEEP | P3 |
| 12 bounded packet authority | PROVEN bounded (experiment) | Rust within cap | admission checks | 1/chunk | 0 | 0 | — | admission policy | receipt-level | — | — | not enabled in baseline runs | capped fallback by design | PRODUCTION_AUTHORITY false | KEEP | P3 |
| 13 network compression | PROVEN (READY_OPTIONAL) | Rust | none | 1/packet | 0 (direct path) | 0 | — | zlib-rs ladder | 1.27–2.16×/packet (68k corpus receipt) | — | — | not enabled in baseline runs | 0 fallbacks (receipt) | — | KEEP | P3 |
| 14 region read + decompression | PROVEN CURRENT | Rust | none | 1/chunk read | 1 into out buffer | sector buffer | per-file lock | reader + registry | receipt-clean; counters 0-mismatch | — | — | in full-stack run: 625 reads, 0 errors | 0% (javaReadFallback=0) | — | KEEP | P3 |
| 15 region write | PROVEN CURRENT | Rust | Java mirror publication (bounded) | 1/entry | framing buffers | sector alloc | per-file lock | allocator + free list | p50 15.8µs (prior receipt) | — | — | in full-stack run: 784 writes, 0 failures | 0% observed | OPT-REG-001: external-writer detector self-trigger (availability, P1) | KEEP | P1 debt |
| 16 mutation/coherency tracking | PROVEN CURRENT | Rust (seam) | none | see 4/5 | 0 | per-event counters | CHM state map | seam hooks + async worker | µs class | — | — | none | 0% | worker retained for non-seam paths (documented) | KEEP | P3 |
| 17 JNI/Java bridge (whole) | PROVEN CURRENT | mixed | see per-subsystem | §18 inventory below | — | table buffers 1/job (light) | — | reflection cached | — | — | — | none | — | decode/lint/tooling gates in place | KEEP | P3 |

Not full ownership (do not mislabel): NBT semantics (BLOCKED, H9);
collision broadphase (PARKED prototype); worldgen density (SHADOW);
scheduler (not owned); sky light (Java/Phosphor by policy); spawn-index
(validated, unwired).

## §18 JNI crossing inventory (live hot paths)

| Crossing | Frequency | Bytes | Removable? |
|---|---|---|---|
| runZeroStage | 1/light job | ~200KB table ptr + diff-out rc×16 | RETAINED (minimal by design) |
| mirrorBlockState | 1/state write | 32B | RETAINED (seam) |
| mirrorBlockLight | 1/light write | 28B | RETAINED (seam) |
| registerPrimer/unload | 1/chunk lifecycle | 262KB primer (shell zeros) at register | MOVED TO LOAD-TIME already |
| refreshSection | async worker only | 20KB/section | RETAINED (non-seam coverage) |
| region read/write FFI | 1/chunk op | payload | RETAINED |
| encodePacketPayload | 1/chunk encode (experiment on) | out buffer | RETAINED |
| probes (light/state) | diagnostics only | 24B | RETAINED (gates/evidence) |

No hidden Java-preparation path remains on the light hot path (the
historical one — refreshChunkNow — is gone; §B checks pass at 0).

## §10 atomics audit (classification)

- Light storage AtomicU32 CAS: REQUIRED (cross-language writers).
- State AtomicU32: REQUIRED (Java Unsafe direct readers cross-language).
- Generation AtomicU64: REQUIRED (lifecycle rejection).
- Dirty masks/bitfields: REPLACEABLE in principle (single-threaded jobs)
  but free (bitfield ops); no change warranted.
- Registry RwLock: REQUIRED (multi-reader + worldgen threads); never
  profiled hot.
- Ordering choices audited: light CAS uses Acq/Rel (REQUIRED —
  cross-language visibility); state stores Release/loads Acquire
  (REQUIRED); no OVER-STRONG SeqCst on hot paths found.

## §28/§50 memory audit & duplication

Per resident section: states 16,384B + block light 2,048B + sky 2,048B
+ section header ≈ 20.5KiB (states were 8KiB pre-full-width: +8KiB —
the §48-validated trade). Per loaded chunk (typ 4–6 sections): ~82–123KiB
+ biomes/height/meta. Real loaded set (C-std1-class runs, ~270–630
chunks): ~22–77MiB Rust-retained. JAVA DUPLICATION: Java retains its own
Chunk EBS states+light (vanilla structures) — Rust ownership currently
DUPLICATES ~2× the section data (states 2×, light 2× while mirrors run;
sky 1× shared-copy). Memory unlocked if Java backing ever goes
façade-only: ~10KiB/section (states 8KiB + block light 2KiB) ≈ 40–60KiB
typical chunk, ~11–38MiB on the real loaded set. Not implemented (§50:
identify only).

## §25 flaky region test — diagnosis (SUPERSEDED by the P0 phase)

Initial audit classification (detector self-trigger) was WRONG on the
mechanism. P0-phase capture (stats in the panic + a detector-site
eprintln, 70+ harness runs): the status was READ_IO_ERROR (5), not
NOT_ELIGIBLE (6), and `external_write_detected=0` always — the detector
never fired. The reader legally observed a half-updated record (writer
publishes data sectors before the header entry, vanilla order, outside
the inner lock); strict decompression fail-closed by design. Final
classification: **TEST_BUG** — the harness treated a designed concurrent
transient as impossible. Fixed in the P0 phase (READ_IO_ERROR tolerated
+ counted; 0/40 failures after). The no-tear invariant held in EVERY
run (100+ total). No engine change required.

## §38 historical claims

See RUST_ENGINE_BENCHMARK_BASELINE.md §38 (STILL VALID classes).

## OPT-SYNC-004 (post-audit addition)

Two distinct outcomes: (a) **independent extraction verification
CLOSED** — DUAL_VERIFY_RUNS=0 root-caused to the packed path being
dead code since OPT-SYNC-002 (cross-loader Class.forName in
initFastExtract + an IntBuffer.rewind() cast CCE); fixed, and the
3-way chain (per-cell registry reference ↔ packed decode ↔ native
read-back) is green on both gates with focused JNI fixtures offline.
(b) **bulk palette performance** — the live packed path beats the
per-entry reflective incumbent −84% extraction / −60% whole
registration on the identical workload. The OPT-SYNC-003 'JIT variance'
hypothesis is retired (moot: the slow phase was the per-cell fallback's
runtime variance). Receipt:
target/optimization-research/OPT-SYNC-004.json (also mirrored here).

### Gate C live-path distribution (§9 completion, 2026-10-09)

Four live-packed C runs (dv4-C, s004-C-live1..3): median REG ~574ms
(208/207 fast; 940 first-run; 1611 slow — 1/4 slow, exploratory n).
Key §9 finding: **extraction is now STABLE across fast AND slow runs
(24-25 µs/s, phases within 1ms)** — dead-path slow runs blew up
extraction itself (589-817 µs/s); the live path's slow run inflates the
CHUNK-level envelope (chunk_MS 1590 vs 188) instead: per-chunk
reflection, precheck nibble scans (~10k sections), absent-mark JNI
(~7k calls), biomes. The next profiling target is that envelope, not
extraction.

## Milestone

`RUST_ENGINE_OWNERSHIP_PERFORMANCE_AUDIT_COMPLETE` declared when the
baseline table below is populated (runs land) and every live subsystem
above carries verdict+priority: DONE — see baseline doc.
PRODUCTION_AUTHORITY remains false. No production behavior changed this
phase (measurement tooling + test instrumentation only).

## OPT-SYNC-005 (2026-10-09) — chunk-envelope attribution: op is CPU-bound; verification is the price of authority

Two instrumented Gate C diagnostics (s005-C-diag1: 1,760 ops incl. teleport churn;
s005-C-diag2: 628 ops, work-count-matched to the OPT-SYNC-004 live series). The
reconciling timeline closes to 97–97.5% (op-unaccounted 3.0%, sec-unaccounted 2.8%):

- **CPU-bound, no wait signature**: ThreadMXBean CPU = 88–112% of op wall (Windows
  tick quantization brackets 100%); GC pauses inside ops = 0; refreshLock uncontended.
- Composition (% of op wall, diag2): EXTRACT 36.7 (DV sampling 21.3 inside) | JNI 25.3 |
  VALIDATE 15.4 | SEC_ALLOC 5.7 | biomes/absent/precheck/resolve ~8 | unaccounted 3.0.
- The old "precheck=0" was a broken timer (empty span); the real
  `isNativeDefaultSection` costs 4.2–9.4 ms/run and re-runs `getMethod`+`setAccessible`
  per call — REPEATED_AVOIDABLE_WORK.
- Slow-run variability: **CAUSE_UNRESOLVED_WITHIN_BUDGET** (2/2 fast; budget forbids
  fishing for a slow run). MORE_WORK and in-op GC/lock-wait are excluded; if it recurs,
  OP_CPU vs OP_WALL discriminates CPU-stall vs wait on first occurrence.
- Next removable cost: allocation/reflection-hygiene cluster ≈28 ms of 220 ms (~13%):
  per-section fresh 20 KiB direct readback buffer, per-section scratch arrays,
  uncached isEmpty Method, triple chunk resolution. Defined as bake-off candidates
  C1 (hygiene pack) and C2 (MethodHandle DV vehicle — JDK8-weak, expected REJECT) in
  `docs/research/OPT-SYNC-005-receipt.json`. NO production optimization merged in this
  task (goal §8/§9). Receipt: `docs/research/OPT-SYNC-005-receipt.json`.

## OPT-SYNC-006 (2026-10-09) — CLOSED KEPT: C2 promoted as sync default (−6% registration, −91% alloc churn)

The OPT-SYNC-005 bake-off ran: C0 vs C1 vs C2 through a paired in-vivo harness
(both impls on the SAME live section objects, alternating order) and a live
Gate C A/B of 3 matched pairs. Receipt: `docs/research/OPT-SYNC-006-receipt.json`.

- **C1 (reuse pack)**: slot-leased scratch arrays + direct readback buffer
  (three verification witnesses never alias; light slots zeroed on lease —
  clear() is not zeroing), ClassValue runtime-class caches (die with the
  loader), op-scoped ChunkCtx. Result: allocation churn −91% live
  (365→33MB), validate −17%, retained cost ~53KB/thread.
- **C2 = C1 + MethodHandle verifier**: the JDK8 evidence was mixed, so the
  real shape was measured: unreflected virtual `get(int,int,int)` + boxed-ID
  lookup is −26% on the DV phase live (−27% in both bake orders).
- **Live A/B**: C2 wins 3/3 pairs with clean separation (worst-C2 214.0ms <
  best-C0 222.7ms); exact-work pairs −5.7/−5.8%. JNI unchanged by design.
- **Promotion**: selector default flipped to C2; shipped C0 path reproducible
  via `-Drustcraft.syncPath=C0`. Verification coverage untouched (same 1-in-8
  sampling, stability re-read, readback full-compare; mism=0, dvdiv=0 in all
  11 boots). SyncLeaseRegression (23 checks) runs on every jar build.
- The OPT-SYNC-005 slow-run variability remains UNREPRODUCED and unclaimed;
  bake1 exhibited one anomalous boot (4.6× registration) affecting both impls'
  windows asymmetrically — spread evidence, excluded from pair math by the
  swapped-order fork.

## Full-stack A/B vs clean Java (2026-10-09) — SLOWER on every phase; receipt superseding the single-run baseline

`docs/research/RUSTCRAFT_VS_JAVA_FULL_STACK_BENCHMARK.md` (+ machine receipt
`FULLSTACK_AB_2026-10-09-receipt.json`): 3 balanced pairs, clean Java arm
(zero RustCraft artifacts; 5,957-byte measurement observer with a
tick-identity sampler), Rust arm = audit-baseline composition (light
ON + registry + region read/write ON; packet/compression off). Verdict:
chunk streaming 4.8–5.2× mean MSPT, mutation ticks 3.7–5×, save 3× wall,
boot +30–70 s, process CPU +10–15%, server-thread allocation ~20×
(~25 GB per 65 s streaming phase). Work-equivalent (169 chunk packets every
run, 67/67 positively-confirmed mutations, near-identical tick counts);
0 validation mismatches; every enabled authority EXECUTED with 0 failures.
The prior audit-baseline table's single-run "java" arm (shadow-loaded) is
superseded. Next measured target: OPT-FS-001 (attribution of the
composition allocation storm) — the whole-composition cost currently
dominates every subsystem-level win in this file.

## OPT-FS-001 (2026-10-09) — CLOSED FIX_MEASURED_BENEFIT: the composition storm was a per-call campaign diagnostic

Attribution chain (receipt `docs/research/OPT-FS-001-receipt.json`): JFR
profiled reproduction → op-scoped counters (onCheckLight/buildTable/sync
exonerated: 1.14GB+7.2s total vs the 24GB phase) → 1ms delta+stack sampler
in the observer (diagnostic-gated) named `LiveWriterHooks
.diagnoseTokenMismatch`: unconditional reflective token-stack introspection
per writerEnd at file-stream frequency, ~52GB/run attributed. Budgeted to 64
calls: streaming MSPT −51% (30.3–36.6 → 12.2–21.1, clean separation),
mutations −54%, saves de-tailed, allocation −18%; correctness and authority
execution identical. The remaining clean-Java gap (~2–3× streaming MSPT)
has a named next bucket: LaunchClassLoader transformer churn (~20GB/run) —
OPT-FS-002. Instrumentation retained: fsAlloc brackets in LightAuthorityHook
(aggregate counters), observer allocSampler (`-Drustcraft.observer.
allocSampler=true`, diagnostic only), `analyze_fs_profile.py`.

## OPT-FS-002 (2026-10-09) — CLOSED: premise refuted, authorities exonerated, local fix below noise

Receipt `docs/research/OPT-FS-002-receipt.json`. The FS-002 lead
(defineClass/ASM ~20GB) is normal Forge classloading (java arm ~70GB at
boot too; transform-once; ~121 streaming classes). Authority ablations:
region-write OFF 19.5GB / light SHADOW 22.1GB vs 21.4GB full — neither is
the streaming allocation storm; mutations MSPT attributed to the light
composition (SHADOW 6.8ms ≈ java). blockPosY per-call reflection fixed
(17.4× local) — no measurable end-to-end effect (before-spread swamps
4.7ms/run); correctness identical. FS-001's budget-verification gap
closed (dumpEndWriteViolation one-shot; enforcement confirmed
budget-independent). Gap vs clean Java unchanged (~2–3× streaming).
Next: OPT-FS-003 — the always-injected hook/gate/observation layer
(staged property-disable diagnostic). Instrument caveat recorded: the 1ms
sampler captures ~10-15% of streaming bytes (bursty alloc + safepoint
bias).

## OPT-FS-003 (2026-10-09) — CLOSED ATTRIBUTED: the streaming allocation excess IS the optional capture/observation session

Receipt `docs/research/OPT-FS-003-receipt.json`. New switch
`rustcraft.noCaptureSession` skips the capture session at bootstrap while
every authority stays registered and running (switch-map finding: the
authorities all register under liveWriterDiagnostic; the SESSION is the
independently-skippable layer; writerBegin/End null-session NOOP bypasses
verified first-line). Isolation pair + confirmation: streaming allocation
19.9 GB → 1.6/1.8 GB (−92%, ≈ clean Java 1.2–1.3 GB), mutations −92%,
saves −97%, with comparable work (registrations, light commits in family,
mism=0, 169 packets, 67/67 confirmed mutations). MSPT improves only
partially (14.9 → 8.4/14.0 vs Java 6.4–7.5) — allocation and MSPT are
separate outcomes; the residual MSPT gap matches the FS-002
light-composition attribution (→ OPT-FS-004: one no-capture+SHADOW boot).
The composed benchmark verdict stands unchanged (isolation experiment,
writer-gate enforcement absent by design). FS-002 receipt scope-corrected:
light SHADOW kept the light machinery running in observation mode; the
write ablation was valid including its transformer.
