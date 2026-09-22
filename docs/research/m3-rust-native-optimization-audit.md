# M3 Rust-Native Optimization Audit

Date: 2026-09-20. Operator-directed methodological review (research/code audit
only — no server runs, no live integration, no default changes). Provenance:
M3R2-OPTIMIZATION-AUDIT (code audit + analysis of committed evidence; no new
measurements).

## The methodological finding up front

The operator's distinction is correct and one of the three M3 rejections
fails it. Of the three rejection reasons possible — (A) no useful boundary
even for an optimized Rust architecture, vs (B) the straightforward parity
implementation lost to Java — **M3-WORLDGEN-NOISE was rejected on (B) while
being worded as if (A)**. The M3W performance path WAS the parity port: the
audit below shows no Rust-native optimization was implemented. Under the new
selection rule (section 7 of this audit), that NOT_WORTH_IT is INVALID and
worldgen must be re-evaluated as RUST_OPTIMIZED before a final verdict.

## A. Spawn-index rejection validity (M3.0)

- Rejection reason class: **A at the query level** — java_ix ≈ rust_ix ≈
  100 ns/query with the algorithm identical; the residual cost is hash
  lookup + candidate predicate + (for Rust) one JNI crossing. No algorithm,
  layout, or batching change alters that: the query is memory-latency-bound
  on a structure too small to vectorize. The B-vs-A distinction does not
  rescue it STANDALONE.
- Future-native-state question (directive 5): the retained assets
  (crates/spatial-index LongKeyBuckets + the parity-proven spawn index +
  JNI surface) exist precisely for the contingency of a future Rust-owned
  packed chunk/section metadata subsystem that could absorb the index's
  maintenance. No such consumer is currently committed (collision — the
  one plausible co-owner — is rejected below).
- Revised classification: **PARKED_FOR_FUTURE_NATIVE_STATE** (conditional).
  Standalone verdict remains ALGORITHM_ONLY; reactivation only as a rider on
  a future native-state subsystem, never standalone.

## B. Collision rejection validity (M3C)

- Rejection reason class: **A (boundary), not B** — the measurement was not
  of a parity port racing Java; it measured the WORK ITSELF: p50 11
  positions/query, 1-2 sections, callbacks on 100% of considered positions
  (the Java virtual call IS the semantics), and a 46.2% exact-skip ceiling
  fully captured by Java-side section counters (O(1) per write, no
  boundary).
- Future-metadata question (directive 6): would free packed section state
  change it? NO — the blocker is the BOUNDARY, not the state: post-counter
  queries leave ~6 candidate positions whose exact resolution is per-block
  Java callbacks; one per-query JNI crossing (~100 ns, measured in M3.0/M2CP)
  costs as much as the entire remaining Java scan. Owning the metadata for
  free would not remove the crossing.
- Revised classification: **unchanged, NOT_WORTH_IT (unconditional)** —
  NOT a dependent-future candidate. The M3C measurement stands under the
  new rule because it bounds the optimized architecture, not just the
  parity implementation.

## C. Worldgen rejection validity (M3W)

- Rejection reason class: **B** — and the wording in
  machine/M3W-worldgen-noise-results.yaml overreached: it claimed "no
  SIMD/reassociation headroom", but only ORDER-CHANGING reassociation is
  forbidden; per-lane scalar semantics on independent lanes is exact and
  was never attempted.
- The 1.31x-slower result is a RUST_PARITY data point, valid as a baseline,
  invalid as a final verdict.

## D. Exact optimizations ACTUALLY implemented in M3W (code audit)

Audited: crates/worldgen-noise/src/lib.rs (sha 1ea618ce…) +
crates/ffi/src/wnoise.rs (652006b0…).

IMPLEMENTED (all incidental, not Rust-native design):
- idiomatic release-mode Rust compilation (strict FP, no fast-math)
- u32/i32 arithmetic mirroring Java wrap/remainder exactly
- reciprocal precomputed per octave (a vanilla semantic, not an optimization)
- function-level inlining freedom (lerp/grad/fade as #[inline])

NOT IMPLEMENTED (none of these existed in the tested path):
- SIMD in any form (0 hits for _mm/intrinsics/gather; 22 scalar
  self.perm[…] lookups per sample path)
- batching: one field per JNI call; each call allocates `vec![0.0; n]`
  (lib.rs:309) and copies into the caller's direct buffer
  (wnoise.rs:68,98 — a heap-Vec→direct-buffer double copy per field)
- persistent native scratch/output buffers
- SoA or flattened grad×perm tables
- chunk-level or field-level parallelism
- multi-chunk / multi-field JNI amortization

## E-H. Exact-semantics optimization feasibility (dependency analysis)

The exactness constraint is: per output element, the sequence of IEEE-754
operations must match the reference expression tree, and cross-octave
accumulation order must be preserved. It does NOT forbid:
1. evaluating independent elements in vector lanes, each lane executing
   the full scalar expression tree (identical ops, no reassociation);
2. reordering WHICH element is computed when (element outputs are pure
   functions of their indices and generator state);
3. caching/precomputing index-pure values.

Loop-nest dependency map (per octave populate call):
- along y within an (x,z) column: SEQUENTIAL — the vanilla y-skip quirk
  caches perm chain + x-pair lerps per Y-cell run (bytecode 696→981);
  vectorizing along y must replicate run semantics (maskable but awkward).
- across x (fixed z,y): INDEPENDENT lanes — each lane owns its column
  state; full scalar algorithm per lane = exact. Width = x_size (5 for
  terrain fields → 5/8 AVX2 lanes; usable but weak).
- across z: INDEPENDENT, same shape.
- across CHUNKS (fixed element index, octave): FULLY INDEPENDENT — chunk
  (cx,cz) fields are pure functions of (chunkX*4-derived offsets, shared
  generator state); nothing crosses chunk boundaries inside the kernel.
  8 chunks at the same (ix,iy,iz,octave) = 8 full-width AVX2 f64 lanes,
  100% utilization, exact by construction. The perm gather (2 gathers per
  corner pair × 8 corners) maps to _mm256_i32gather_pd (AVX2), pipelined.

F. SIMD feasibility: **FEASIBLE AND EXACT** on the chunk axis (and weakly
on the x axis); the y axis needs the caching-run decomposition. No
transcendentals exist in the kernel (verified), so no libm divergence
risk. FMA must stay DISABLED only where the reference uses separate
mul+add — same rule as scalar.

G. Batching feasibility (directive 4, architectural):
- shared state across a batch: the 3-4 Octaves handles (perm tables, ~4KB
  each, L1-resident) — shared read-only inside a batch; output buffers
  disjoint per chunk.
- independence: all chunks × all 4 fields mutually independent AT THE
  KERNEL LEVEL. Minecraft ordering constraint: chunk generation is
  serialized on ServerThread by ENGINE POLICY, not by kernel semantics —
  fields are position-pure, so pre-generating a batch (parallel, any
  order) then consuming in vanilla order produces identical observable
  output. (Integration would still need the SHADOW ladder to prove it
  live; this audit is offline analysis only.)
- JNI amortization: one crossing per batch instead of per field. Measured
  crossing ≈ 100 ns → for 16 chunks × 4 fields = 64 fields, amortized
  ≈ 1.6 ns/field; copies collapse to direct-buffer writes.
- parallelism: independent chunks → data-parallel inside one coarse call
  (rayon or std threads) OR pipelined behind the Java generator; exact
  because outputs are pure.

H. Persistent-state opportunities: native handle already owns
perm/octave/scratch; add persistent per-batch output slabs (zero
per-call alloc), flattened perm→grad tables (permG_x/y/z[512] doubles —
removes one indirection per grad lookup without changing arithmetic),
per-column fade/coord scratch reused across octaves (index-pure).

## I. Multi-chunk ownership

An `gen_chunk_batch(handle, chunkCoords[], n, outSlab)` export makes Rust
own: the generator state, the batch schedule, scratch, and the output slab
— the largest clean native boundary available in worldgen. Java retains:
generator construction/settings, biome inputs (per-chunk 25 floats), and
all post-field assembly (which is tiny relative to the fields and stays
Java for mod-compatibility).

## J. Should M3-WORLDGEN be reopened?

YES — as a bounded OFFLINE RUST_OPTIMIZED prototype (working name M3W2),
scope: chunk-batched (4/8/16) SIMD kernel + persistent buffers + zero-copy
output, gated on the existing bit-exact differential harness (zero
mismatches mandatory before any timing claim), benchmarked against the
same JAVA_REFERENCE under the 3-tier rule. Expected outcome band
(hypothesis, NOT a claim): 8-lane chunk-SIMD with gathers typically lands
2-4x over scalar Rust → parity-to-3x vs Java per core, plus optional
batch parallelism; even the low end reverses the 1.31x verdict. If M3W2
still loses to Java, NOT_WORTH_IT then stands on an (A)-grade result.

## K. Revised candidate evaluation methodology (binding going forward)

Every performance-bearing candidate is evaluated as three implementations
sharing one oracle:
1. REFERENCE_JAVA — the actual installed classes (never a re-implementation).
2. RUST_PARITY — straightforward exact port; establishes correctness and
   the baseline; may NOT be used alone to justify NOT_WORTH_IT.
3. RUST_OPTIMIZED — a genuine optimization attempt over the parity port:
   at minimum the exact-semantics optimizations the dependency analysis
   admits (independent-lane SIMD where lanes are provably independent,
   batching across independent work units, persistent native buffers,
   zero-copy outputs). "Reasonably optimized" = the optimizations on this
   list that the candidate's dependency map permits, not merely proposed.
NOT_WORTH_IT requires one of: no useful boundary exists even optimized;
boundary/synchronization cost dominates; or RUST_OPTIMIZED still loses.
Classifications PARKED_FOR_FUTURE_NATIVE_STATE and
DEPENDENT_FUTURE_CANDIDATE are available for conditionally-rejected work
with explicitly named future enablers.

## L. Recommendation

1. REOPEN worldgen optimization as M3W2 (offline, RUST_OPTIMIZED tier,
   bit-exact gate first, then 3-tier benchmark incl. 1/4/8/16-chunk
   batching).
2. Spawn-index: reclassify PARKED_FOR_FUTURE_NATIVE_STATE (conditional;
   standalone verdict ALGORITHM_ONLY unchanged; not reactivated).
3. Collision: NOT_WORTH_IT stands (unconditional; boundary-bound, not
   state-bound).
4. Adopt the section-K methodology for all future candidates (this
   document is the reference; AGENTS.md adoption is the operator's call).

STOP: no server runs, no live integration, no default changes performed.
