# Next-Subsystem Re-Score After the Single-Copy Boundary

**Date:** 2026-10-02
**Input:** fresh post-implementation JFR profiles (Gate A, identical 2-round
probe workloads, authority off vs single-copy direct), true-MSPT A/B
(BenchAgent on `func_71217_p`), the live emission benchmarks, and the Gate
A/C campaign receipts.

## 1. What the fresh profile actually says

With the single-copy boundary live, the packet path's exclusive CPU is below
sampling resolution: whole-process exclusive samples put compression at 2/187
(single-copy) vs 4/289 (baseline) — ~1% either way — and the event-loop share
is ~0. The b3df84c-era profile's dominant cost (84.69% bridge scaffolding in
the steady chunk-streaming window) no longer exists: the capture/transport
machinery is not on the admitted path, and the residual server-thread cost
(the native encode + header write) measures 0.46-1.51 us per packet.

Consequence: **`RUST_NETWORK_COMPRESSION` is now the largest remaining item
on the outbound chunk path**, but its measured share at real single-client
workloads is ~1% of whole-process CPU — the zlib cost only becomes material
under many-client chunk floods, and the b3df84c→single-copy win removed the
surrounding scaffolding that used to amplify it.

## 2. Scorecard (10 = highest priority; whole-process CPU, Netty CPU,
allocation, GC, I/O wait, Java-object elimination, native-state leverage,
engineering risk, compatibility risk all weighed)

| Subsystem | CPU share (measured, fresh JFR) | Alloc/GC leverage | Native-state leverage | Risk | Score | Verdict |
|---|---|---|---|---|---|---|
| **RUST_NETWORK_COMPRESSION** (zlib-ng / backend swap behind the existing `NettyCompressionEncoder` seam) | ~1% whole-process, ~0 event-loop at 1-2 clients; scales with client count | low (deflater output buffers) | medium (deflate operates on the already-native body) | low: the compression INTEGRATION boundary is already proven (`CompressionCtx`/`NativeCompressionEncoder` exist; the frame contract is byte-verified) | **7.0** | **SELECTED NEXT** |
| STORAGE_NBT_ANVIL (region I/O + NBT in Rust) | dominant during load/save bursts (previous profiles), ~0 at steady join | high (removes NBT object churn + byte[] on save) | high (NativeChunk already stages persistence: `stage_persistence`) | medium: modded TE/entity NBT must stay Java; boundary is the chunk-level auto-save payload | **7.5 on burst workloads, 4 on this profile** | strong #2 — needs a save/load-burst profile to overtake |
| COLLISION (AABB/raycast in Rust) | low at these workloads | low | medium (block reads already zero-JNI) | medium-high: modded `getCollisionBoundingBox` | 3 | defer |
| SCHEDULED_TICKS | low at idle+join | medium | medium | high semantic risk (tick ordering vs mods) | 3 | defer |
| LIGHTING PROPAGATION (algorithm, not data) | low at these workloads (data ownership done) | medium (queue churn) | high (arrays already in Rust) | medium: cross-chunk boundary sync | 4 | defer until multi-chunk spatial index |

## 3. Decision

**Selected next subsystem: `RUST_NETWORK_COMPRESSION`.**

Rationale:
1. It is the direct continuation of the proven seam — the single-copy body
   now exists as ONE contiguous native-authored buffer; swapping or
   short-circuiting the deflater backend operates on exactly that buffer with
   the same shadow-compare harness this milestone built (compression output
   byte-compare via zlib inflate, already implemented in the offline suite).
2. The compatibility risk is the lowest of the remaining candidates: the
   compression stage is a pure byte->byte function behind a boundary whose
   wire contract this milestone proved end-to-end on two runtimes.
3. STORAGE_NBT_ANVIL remains the bigger prize on save/load-burst workloads
   but requires its own burst profile first; it should be re-scored against a
   save-heavy capture before committing.

Per the task contract: **no implementation of the next subsystem is included
in this milestone** — this document only selects it.
