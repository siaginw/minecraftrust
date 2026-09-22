# M3W2 External Rust/SIMD Optimization Research (pre-implementation)

Date: 2026-09-20. Operator-directed research pass before RUST_OPTIMIZED.
Provenance: M3W2-SIMD-RESEARCH (primary sources fetched; no server runs).

## A. Sources examined (primary evidence ranked first)

1. **FastNoise2** (Auburn, MIT) — README + `include/FastNoise/Generators/Perlin.inl` fetched and READ (primary source).
2. **FastSIMD** (Auburn) — referenced by FastNoise2 README for multiversion dispatch.
3. **rust-simd-noise** (verpeteren) — README fetched (benchmarks + technique claims).
4. **FastNoiseSIMD** (Auburn, historical) — design lineage of 1-3 (not separately fetched; superseded by FastNoise2 source).
5. Rust std::arch / `is_x86_feature_detected!` — established std API surface (std docs; no fetch needed for stable, years-old API).

## B-F. Technique extraction and parity classification

| # | Technique (source) | Why it helps | Applicable to MC ImprovedNoise? | Parity | ISA |
|---|---|---|---|---|---|
| T1 | **Lanes = independent sample positions**; one position per lane, whole sample evaluated as vector ops (FastNoise2 Perlin.inl `Gen(seed, x, y, z)`) | 8 samples/cycle-class throughput; removes per-sample loop overhead | YES — on the CHUNK axis (independent chunks), per M3R2 dependency map | **EXACT_SAFE** (each lane runs the full scalar expression tree; no reassociation) | AVX2 (f64×8) |
| T2 | **Entire lerp tree as one expression, intermediates in registers** (Perlin.inl: `Lerp(Lerp(Lerp(...)...))`, xf0/xf1 computed once) | eliminates per-corner temporaries/stores | YES — same tree shape as MC's kernel | **EXACT_SAFE** (same op order as scalar) | any |
| T3 | **Integer-prime hashing instead of perm tables** (Perlin.inl `HashPrimes(seed, x0, y0, z0)`; rust-simd-noise similar) | avoids gathers entirely — THE major FastNoise2 trick | **NO — PARITY_BREAKING**: Minecraft's output IS the perm-table lookup sequence; replacing the hash changes every value | PARITY_BREAKING | — |
| T4 | **f32 SIMD + FMA3** (rust-simd-noise avx2; FastNoise2 float32v) | 2× lanes + fused mul-add throughput | **NO — PARITY_BREAKING**: MC kernel is f64 with separate mul/add; FMA contraction changes rounding (rust-simd-noise's own AVX2+FMA3 combo is unusable here) | PARITY_BREAKING | — |
| T5 | **Perm/grad lookups via gather** (adaptation of scalar L1 lookups to lanes) | keeps exact table semantics under SIMD | YES — `_mm256_i32gather_epi32` on perm[512] (L1-resident, 2KB), `_mm256_i32gather_pd` on flattened grad tables | **NEEDS_PROOF** (index arithmetic must be lane-exact; proven by differential) | AVX2 |
| T6 | **Flattened perm→grad tables** `PGX[i]=GRAD_X[perm[i]&15]` (lookup-table restructuring; classic, consistent with both repos' precompute style) | removes one indirection per grad component (28→ per sample: 4 i32 + 24 f64 gathers) | YES | **EXACT_SAFE** (pure precompute of index-pure values) | any |
| T7 | **Multiversion compile + runtime dispatch** (FastSIMD FeatureSet templates; rust-simd-noise runtime pick) | best ISA per machine without losing portability | YES — `#[target_feature(enable="avx2")]` + `is_x86_feature_detected!("avx2")`, scalar fallback | **EXACT_SAFE** | AVX2+ |
| T8 | **Fused node-graph execution** (FastNoise2: whole pipeline in SIMD registers, min alloc/bandwidth) | avoids array round-trips between stages | PARTIAL — our octave accumulation must stay in-order per element; batching all 4 fields of a chunk into one call keeps cross-field data resident; octave sums write once per octave (required) | **NEEDS_PROOF** (element order preserved; cross-octave order unchanged) | any |
| T9 | **Batch generation API** (rust-simd-noise "block of noise"; both repos) | amortizes call/setup; enables T1 lane filling | YES — chunk batching per M3R2 | **EXACT_SAFE** (position-pure outputs) | any |
| T10 | **Persistent scratch/state reuse** (both repos' generator objects) | zero per-call alloc | YES — handle-owned slabs | **EXACT_SAFE** | any |
| T11 | Auto-vectorization reliance | none needed | NOT USED — explicit intrinsics preferred (both repos hand-write kernels; autovec fails on gathers/perm) | n/a | — |

Benchmark expectations grounded (rust-simd-noise, i7-6700): scalar→AVX2 = 8× (2D FBM) to 30× (cellular). OUR case is harder (f64 not f32 → 8 lanes not 16; gathers not arithmetic hash; no FMA): realistic band **2-4× over scalar Rust** on the chunk axis.

## G. Implementation plan for RUST_OPTIMIZED (from the strongest applicable ideas)

Implemented now: T1 (chunk-axis lanes), T2 (register-resident tree), T5 (gathers), T6 (flattened tables), T7 (runtime dispatch), T9 (batch API), T10 (persistent slab), T8-partial (single JNI crossing per batch). Explicitly REJECTED as parity-breaking: T3, T4. The vanilla y-skip caching quirk is replicated per-lane via blend(mask keep-cached, recompute) — recomputed lanes' values are discarded where cached must be kept, preserving per-lane scalar semantics exactly (class: NEEDS_PROOF, proven by the differential gate).

Gate: bit-exact differential vs the REAL installed classes on the batched path (zero mismatches) BEFORE any timing claim — same oracle discipline as M3W.
