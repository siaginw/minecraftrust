# Rust Engine Optimization Backlog (audit §46)

Born from `RUST_ENGINE_OWNERSHIP_PERFORMANCE_AUDIT.md` (same session).
Every item cites a MEASURED basis (bench + receipt). Nothing here is
implemented; priorities follow §39 (hot-path share × reachable speedup ×
frequency × leverage × confidence − risk − complexity − memory −
maintenance).

Two standing rules (born from the P0 phase, 2026-10-09):

1. **A component win is a hypothesis.** No item reaches P0 without a
   whole-job estimate on a frozen REAL corpus. Citation: the audit's
   frontier bake-off said 4.6× component — whole-job A/B measured 0-3%
   (REJECTED), while the "smaller" hasher win (1.8× component) delivered
   the real 17%. Light has `crates/ffi/tests/kernel_corpus.rs`; build the
   analogue for any other subsystem before trusting a replay.
2. **HYPOTHESIS vs verdict.** An incident classification made without
   capturing the failure site's inputs is a HYPOTHESIS — the verdict
   words (TEST_BUG / REAL_BUG / RACE / DETECTOR_ACCOUNTING_BUG) are
   reserved for instrumented evidence. Citation: the audit called the
   region flake "REAL BUG: detector self-trigger" from two sessions of
   theory; one instrumented panic capture overturned it in minutes
   (TEST_BUG; the detector never fired). Instrument first, then name.
3. **Counter-guided implementation: verify the BRANCH, not the label.**
   Before implementing against a counted population, print or trace
   which code path the counted items actually take — a counter's name is
   a label, not a path. Citation: OPT-SYNC-001's first precheck targeted
   present-but-empty EBS sections while the 67% all-air population
   actually flows through the absent-storage path; only the unchanged
   SECTIONS_REFRESHED counter exposed it (one burned gate run).

| ID | Subsystem | Current cost (measured) | Hotspot % | Proposed change | Candidate | Expected impact | Risk | Bench required | Priority | Depends |
|---|---|---|---|---|---|---|---|---|---|---|
| ~~OPT-LIGHT-001~~ CLOSED REJECTED | zero-stage frontier | VecDeque 64.6µs p50 on cascade REPLAY (component) | whole-job share: ~0-3% (measured) | Vec + monotonic head — implemented + FIFO-ordering-proof, kept opt-in (`vec-head-fifo` feature) | std Vec | whole-job A/B on frozen real corpus (1,461 jobs ×3 runs): 0-3%, WITHIN NOISE — frontier ops are not the bottleneck; per-cell world reads dominate | low | done | CLOSED | none |
| ~~OPT-LIGHT-002~~ CLOSED KEPT | zero-stage overlay | std HashMap SipHash: 34.6µs p50 per 2k-cell replay | overlay = per-cell writes+reads across BFS | local 15-line FxHash-style hasher (NO new dependency); section bitset N/A — production has no section-local membership structure (§8 finding) | inline Fx | whole-job A/B frozen real corpus: mean 2.62→2.17ms (−17%), p95 11.5→9.5ms (−18%), stable ×3 runs; exact DIFF_DIGEST equality across variants; ship as default | low (internal engine-generated keys) | done | CLOSED | none |
| ~~OPT-REG-001~~ CLOSED TEST_BUG | region write concurrency test | read_during_write_never_tears panicked ~15–25% of runs | misattributed to the external-writer detector | **diagnosis complete (§31)**: status was READ_IO_ERROR (5), NOT NOT_ELIGIBLE (6) — the detector NEVER fired (external_write_detected=0 in every capture). The reader can legally observe a half-updated record because the writer publishes data sectors BEFORE the header entry (vanilla order) outside the inner lock; strict decompression fail-closes to READ_IO_ERROR by design (FFI falls to Java). The test treated that designed transient as impossible. NO production change; test now tolerates READ_IO_ERROR (0/40 failures); detector fully exonerated | none | n/a — no engine bug existed | none | done | CLOSED | none |
| ~~OPT-LIGHT-003~~ CLOSED KEPT | section-local world access | registry HashMap + map lock per cell/neighbor read | locality measured: 92.8% same-section, 14 unique chunks/job-set | 8-slot job-local chunk-handle cache (SectionCacheAccess; external research found no importable crate — the ecosystem answer at N=8 is the local linear scan; Starlight's queue-carries-value idea recorded for a FUTURE algorithm phase) | local (0 new deps) | whole-corpus −27.6% mean / −28.4% p95 vs legacy-access (cumulative −42% light-job mean vs P0 baseline); digest identical; promoted as default; `legacy-access` reproduces the old path | low (per-job cache; §11 revalidation backstop) | done | CLOSED | none |
| ~~OPT-ALG-001~~ CLOSED KEPT | light kernel algorithm | full-justify per pop (9 reads); addition-heavy live workloads | removal-dominated corpus hid it; live kernel time was the reveal | Starlight-inspired carries-value ADDITION pass (direct-apply max(em, v−att), zero neighbor reads; removal legacy-exact) | local (0 new deps; PaperMC/Starlight MIT, concepts only) | corpus TIE (removal-dominated); LIVE −18% kernel time (JNI 106.0 vs 129.3s, 45,732 vs 39,194 committed); digest/total_rc identical; 100k fuzz PASSED ×2 | medium (algorithm change — operator-authorized; equivalence proven 3 ways; stale-baseline unsoundness of the first draft caught and fixed) | done | CLOSED | none |
| ~~OPT-SYNC-004~~ CLOSED KEPT ×2 | sync extraction | (a) INDEPENDENT extraction verification CLOSED: DUAL_VERIFY_RUNS=0 root-caused — the packed path was DEAD CODE since OPT-SYNC-002 (initFastExtract Class.forName cross-loader + (ByteBuffer)IntBuffer.rewind() CCE aborting fullSync per chunk); fixed via live-object-graph derivation + palette BACKING-buffer addressing. Chain green: dualVerify 3,252(A)/3,016(C) gate arrivals, 0 divergences; readback full-rate mismatch=0; Gates A/C PASS. (b) BULK PALETTE RESULT: per-entry-vs-packed A/B on the identical workload (dv2 forced-fallback vs dv4 live): extraction 151→24 µs/s (−84%), registration 638→254 ms (−60%, −71% vs pre-002). Focused JNI fixtures (4 tests: widths 4-16 local+registry, word boundaries, >65,535 ids, malformed rejection, negative packed==native-wrong catch) | local (0 deps; Minestom/Apache-2.0 design-reviewed) | see left | low (fallback paths preserved) | done | CLOSED | none |
| OPT-SYNC-005 ATTRIBUTION DONE | chunk-envelope cost | slow-run extra ~1.4s sat outside section timers; precheck timer read 0 (broken) | reconciling per-op timeline (wall+CPU+alloc+GC deltas, exclusive children, bounded op-ID traces): op is CPU-BOUND (CPU 88-112% of wall, in-op GC=0); EXTRACT 37% (DV sampling 21% inside), JNI 25%, VALIDATE 15%; unaccounted 3%. Slow run CAUSE_UNRESOLVED_WITHIN_BUDGET (2/2 fast; discriminating counters retained). REMOVABLE: allocation/reflection-hygiene cluster ~13% of registration wall (per-section 20 KiB direct readback buffer, scratch arrays, uncached isEmpty getMethod, 3x chunk resolve). Bake-off C1 (hygiene pack) / C2 (MethodHandle DV vehicle, JDK8-weak expected REJECT) defined in receipt — NOT implemented (no-production-optimization task) | local (0 deps; JDK8 MH + JNI + engine-practice research recorded) | see receipt bakeoff_design | low (allocation/hygiene only; verification contract untouched) | next: OPT-SYNC-006 bake-off, not started | OPEN (design-only) | none |
| ~~OPT-SYNC-006~~ CLOSED KEPT (C2 promoted default) | sync chunk-envelope hygiene + verifier vehicle | OPT-SYNC-005 measured ~13% removable: per-section 20 KiB direct readback buffer, scratch arrays, uncached precheck getMethod, 3x chunk resolve; MH verifier = open question on JDK8 | C1 = slot-leased scratch (3 witness storages NEVER alias; bl/sl zeroed on lease) + ClassValue runtime-class caches + op-scoped ChunkCtx; C2 = C1 + dualVerify via unreflected MethodHandles (same Methods, virtual dispatch preserved) | local (0 deps) | paired in-vivo bake (same live section objects, alternating order): C1 alloc −62% (live −91%), C2 DV −27% both orders; LIVE A/B 3/3 matched pairs, clean separation (worst-C2 214.0 < best-C0 222.7ms): −5.7/−5.8% exact-work pairs (−14.9% third w/ work skew), DV −26%, validate −17%, JNI unchanged, alloc churn 365→33MB; mism=0/dvdiv=0 all 11 boots; C0 reproducible via -Drustcraft.syncPath=C0 | low (leases+cached lookups; verification coverage untouched; SyncLeaseRegression on every build) | done | CLOSED | none |
| ~~OPT-FS-001~~ CLOSED FIX_MEASURED_BENEFIT | composition allocation storm | full-stack A/B: ~20x srv-thread alloc, 4.8-5.2x streaming MSPT | 1ms delta+stack sampler: LiveWriterHooks.diagnoseTokenMismatch ran full reflective token-stack introspection per writerEnd at file-stream frequency (~52GB/run attributed; onCheckLight/buildTable/sync exonerated by op-scoped counters) | DIAGNOSE_BUDGET=64 latch (evidence preserved for first 64 ops) | streaming MSPT −51% (30.3-36.6 → 12.2-21.1, no overlap), mutations −54%, save p95 collapse; alloc −18%; correctness identical (mism=0, 67/67, same authority execution) | low (diagnostic-path budget only) | done | CLOSED | none |
| OPT-FS-002 | LaunchClassLoader transformer churn | ~20GB/run defineClass/ZipFile/ASM bucket during streaming (fs-attr2 sampler; separate from OPT-FS-001) | none yet | none | attribute which transformer pass allocates on lazy classload, then bound it (plan-scoped skip of non-target classes) | medium | next measured task | P1 | none |
| OPT-PKT-001 | packet palette rebuild | 48µs p50 unique-count per section rebuild (direct-u32) | only on PALETTE_DIRTY; cached otherwise | reuse §48 finding: keep direct-u32 (2× hot reads); if rebuild shows in server profile, add incremental palette maintenance | none yet | removes rebuild spikes if profiled | low | server-profiled first (§5 discipline) | P2 | none |
| OPT-TELEM-001 | MSPT sampler duplication | TWO working samplers now (coremod m1-metrics histogram; tweaker reservoir) — the P0 phase built new telemetry before discovering the existing one had everything | n/a | consolidate to one (coremod's field_71311_j attach + histogram is the survivor; the tweaker reservoir duplicates it) | none | removes double-attach cost + drift; ONE canonical MSPT source | none | n/a | **P2** (bumped from P3: second citation 2026-10-09 — the duplication cost a fix cycle in the P0 phase and nearly forced 4 baseline reruns before the extractor read m1-metrics) | none |

Rejected/not-pursued this round (with reasons):
- **Section-local u16 palette state storage** (§48): LOSES ~2× on every
  hot read (random read 1.70 vs 0.90µs; light-shape 3.40 vs 1.60µs;
  mutate 5.20 vs 0.80µs) to save 8KB/section. Direct-u32 verdict: KEEP.
- **Parallel light jobs / rayon** (§31): jobs are server-tick-ordered
  and cheap (37µs/job class); no measured contention. Not pursued.
- **SIMD for propagation** (§30): branch-heavy BFS; bulk ops are already
  linear scans under 2µs/section. No profile support.
- **parking_lot / lock swaps** (§17): registry locks never appeared in
  any profile; hold times sub-µs. Not pursued.
- **hashbrown as a dependency** (§14): std HashMap IS hashbrown; only
  the hasher differs (see OPT-LIGHT-002 — hasher swap, not map swap).
