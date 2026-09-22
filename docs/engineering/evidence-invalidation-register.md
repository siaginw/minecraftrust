# Evidence Invalidation Register

> **STATUS: ACTIVE.** This register supersedes specific historical evidence
> claims listed below. Historical artifacts are NOT deleted — they are
> annotated as invalidated so future readers cannot mistake them for valid
> evidence.

## Invalidation Criteria

M1.4-R (2026-09-18) found that the Java reference encoder and the native
encoder shared section-selection semantics that deviate from true vanilla
behavior (see `m1-zero-section-semantics.md`). Any parity or performance
evidence produced under those semantics is unreliable.

## Invalidated Evidence

### 1. M1.3 100k parity campaign

**Status: INVALIDATED**

- Artifacts: `machine/M1.3-shadow-results.yaml`, `docs/migrations/M1.3-live-shadow.md`
- Reason: reference and implementation shared section-selection semantics
  that were never validated against the real vanilla constructor
  (the encoder pair used a shared `vanillaWritesSection` helper; see
  `m1-zero-section-semantics.md`). Both sides of the comparison agreed
  by construction, so zero-divergence proved only self-consistency, not
  vanilla parity.
- Post-facto note: `Chunk.field_186036_a` is null at runtime, so the
  original `ebs != null` checks were semantically correct null checks;
  the residual defect was the SHARED helper (no independent reference),
  not the null comparison itself.

### 2. M1.3 fixed T_JNI / T_HANDOFF timing

**Status: INVALIDATED**

- Artifacts: `machine/M1.3-performance-results.yaml`, decomposed timing tables
- Reason: hardcoded/assumed values (50 ns JNI, 10 ns handoff) rather than
  measured values.

### 3. P0-8 Target B/C/D performance baselines

**Status: INVALIDATED / SYNTHETIC**

- Artifacts: `tools/run-p08-benchmarks.py` synthetic tables
- Reason: no actual pack server installations existed. Values were fabricated
  template tables, not measurements.

### 4. P0-9 performance scoring inputs derived from P0-8

**Status: REQUIRES REVALIDATION**

- Reason: inputs inherited synthetic P0-8 data. Cannot be trusted until
  re-measured from real workloads.

### 5. Full hardcoded-evidence inventory (M1.4-R §22 audit)

**Status: INVALIDATED / REQUIRES REVALIDATION (41 findings)**

- Full machine-readable inventory: `tools/audit_hardcoded_benchmarks_results.json`
- Highlights:
  - `LiveShadowHarness.java:194-195` — hardcoded `jniOverheadUs=0.05` +
    Math.max floor (TIMING_CLAMP). Also compile break: calls
    `encodeSectionsWithFallback` which no longer exists in bridge.
  - `generate_m1_manifests.py:89` — pre-written 7.29µs decomposition
    including "0.05µs JNI" (HARDCODED_EXPECTED).
  - `BlockStorageBenchmarks.java:352` `JNI_BASE_NS=15.2`;
    `benchFfiGranularity()` = arithmetic on 4 constants (SYNTHETIC_RESULT).
  - `M1-acceptance-gates.yaml` — "Java ref: 11.65µs" baked into gate
    (HARDCODED_EXPECTED); `benchmark-gates.yaml` self-declared non-evidence.
  - `p08_closure_audit.py` — TOP_30_ALLOCATIONS "Verified Memory Profiler
    Histogram" = 30 dict literals; `simulate_per_tick_improvement()`
    subtracts 6.80ms constant from constant percentiles (SYNTHETIC_RESULT).
  - `performance-baselines.yaml` (491 lines authored percentiles),
    `M1.2-parity-results.yaml` (quantized p50=p90 + self-rated
    "plausibility"), pack baseline docs (HARDCODED_PERCENTILE).
- Root pattern: constants authored in `run-p08-benchmarks.py` /
  `p08_closure_audit.py` → dumped to `machine/*.yaml` → re-quoted in docs
  as "empirical". Real measurement paths exist (`run-p02-benchmarks.py`,
  `calibrateJniOverheadNs`) but the p08/M1.3 chain did not use them.

## Not Invalidated

- M1.2 differential oracle structural coverage (schema validation, error
  codes, malformed-input rejection) remains valid.
- M1.1 staging schema round-trip tests remain valid.
- Rust unit tests (10 chunk-packet tests) remain valid.
- P0-0 through P0-7 curriculum and source-research artifacts remain valid.
- M14ValidationHarness corrected run (1000/1000, 5/5 phases) remains valid
  — harness reference is the real vanilla class, not a shared helper.

## Revalidation Protocol

Before any invalidated number may be re-cited:

1. Fix must be merged (semantic repair commit).
2. A new campaign must run with true-vanilla reference independence (§6).
3. Raw samples must be stored and referenced (§4).
4. Provenance must be recorded in `machine/evidence-provenance.yaml` (§2).
