# M4.1 Task 8 — Native Chunk Refresh Design
**Date:** 2026-09-21
**Status:** Implemented + measured offline
**Components:** `native-chunk` (chunk.rs, registry.rs, section.rs), `ffi` (native_chunk.rs), bridge (NativeChunkBridge.java)

---

## 1. Problem

Native chunks are registered at base-terrain time (M4 `shadowTerrain` hook). The
mutation audit (`m4-1-mutation-audit.md`) identified 11 mutation paths —
population, player edits, pistons, fluids, light, Anvil load — that diverge Java
state from native state. Refresh design decides **when and at what granularity**
native state is re-synchronized, and what it costs.

## 2. Design: Section-Granular Dirty Mask + Lazy Pull

**Chosen:** coarse section rebuild, pulled lazily on the consumer path.

| Mechanism | Call | Cost class |
|---|---|---|
| Mutation hook | `markSectionMutation(cx, cz, y>>4)` | ~0.1 µs — sets one bit in `dirty_sections: u16`, bumps `mutation_generation`, `ActiveNative → Dirty` |
| Consumer-side pull | `refreshSection(cx, cz, y, states8KB, light4KB?)` | ~2.2 µs — replaces the section in place, same generation_id, clears the dirty bit |
| Consumer query | `getDirtySections(cx, cz)` | <0.1 µs — mask read |

Flow:
1. Coremod hooks `Chunk.setBlockState` (and light/save hooks per audit) →
   `markSectionMutation`. A population burst of N block writes collapses into a
   16-bit mask: N × 0.1 µs, zero transfers.
2. Next consumer read (packet encode / persistence staging): Java checks
   `getDirtySections`, pushes only dirty sections via `refreshSection`, then
   encodes. All-air refreshed sections release their `Box<NativeSection>` and
   clear the mask bit.
3. When `dirty_sections == 0` the chunk returns `ActiveNative`. Handles
   (`generation_id`) stay valid across refresh — only re-registration (Anvil
   load) mints a new generation.

**Why not per-block push?** One JNI per `setBlockState` (~0.5–1 µs) plus write
lock per call; population writes thousands of blocks per chunk. The audit's
frequency profile (§4) makes burst-collapsing strictly better.

**Why not full-chunk re-register?** Measured 39 µs vs 11.4 µs for a 5-section
refresh (3.4×), and it invalidates every outstanding consumer handle for that
chunk. Reserved for identity-changing events (Anvil load, /regen).

## 3. Measurements (offline, JDK 8, release DLL, this repo's M4RefreshBenchmark)

| Operation | mean | p50 | p95 | p99 | Transfer |
|---|---|---|---|---|---|
| `markSectionMutation` (hook) | 0.17 µs | 0.10 | 0.10 | 0.10 | 0 B |
| `refreshSection` 12 KB (states+light) | 2.21 µs | 2.20 | 2.30 | 2.30 | 5.56 GB/s |
| `refreshSection` 8 KB (states only) | 2.16 µs | 2.20 | 2.20 | 2.20 | 3.79 GB/s |
| 5-section chunk refresh (population) | 11.38 µs | 11.10 | 11.30 | 20.70 | 5.40 GB/s |
| `registerPrimer` full-chunk 128 KB | 39.02 µs | 38.10 | 42.20 | 61.50 | 3.36 GB/s |
| `encodePacket` (cached palette) | 0.85 µs | 0.80 | 0.90 | 0.90 | — |
| refresh 1 section + encode (60-state) | 75.65 µs | 68.50 | 89.20 | 94.70 | — |
| refresh 1 section + encode (4-state)  | 13.65 µs | 13.20 | — | 16.30 | — |

### Finding: palette rebuild dominates, not transfer
The 12 KB transfer costs 2.2 µs, but the first encode after a content change
rebuilds the section's local palette (dedup scan + BitArray repack):
- 4-state terrain section: ~10 µs rebuild (total path 13.65 µs)
- 60-state modded section: ~70 µs rebuild (total path 75.65 µs)

Rebuild is O(4096 × unique_states) due to the linear dedup/palette-lookup scan.
Vanilla Java pays the same class of cost per SPacketChunkData (its palettes are
never cached across packets), so this is not a regression — but it is the
optimization headroom for v2:
1. **Incremental palette**: keep the old LocalPalette across refresh; rebuild
   only if a new global id appears that isn't in the current palette.
2. **Hash-map dedup** in `build_local_palette` instead of linear scan
   (O(4096) instead of O(4096 × u)).
3. **Write-through for single-block edits**: a dedicated `setBlockNative(idx, id)`
   FFI (updates states + existing palette in place) for the sporadic
   player-edit case; section refresh reserved for bursts.

## 4. Frequency Profile (from mutation audit + prior campaign observations)

| Path | Frequency (typical busy server) | Refresh work per event |
|---|---|---|
| Chunk population | once per new chunk (~10–60/s during exploration) | burst → mask (0.1 µs/write) + 1–5 section pulls (2.2 µs each) at first consumer read |
| Player place/break | ~1–50/s server-wide | 1 hook (0.1 µs) + 1 section pull on next encode |
| Piston/redstone farms | up to ~100s/s, localized to few chunks | mask bits already set → amortized to one pull per encode per dirty section |
| Fluid/fire spread | bursty, tens/s during events | same as population (mask collapse) |
| Light updates | piggyback block edits; full relight on gen/load | light arrays ride the same section pull (12 KB form) |
| Anvil chunk load | on chunk reload | full `registerPrimer` (39 µs) — identity change, new generation |
| Chunk unload | radius movement | `unloadChunk` (registry remove) |

Amortized cost per player-visible event: **hook ≈ 0.1 µs per block write +
≤ 2.2 µs per dirty section per consumer read + one palette rebuild
(10–70 µs content-dependent) per changed section per encode**. Compare: the M1
Java packet path alone was ~200+ µs per chunk packet (M1.4 campaign), and
vanilla rebuilds its wire palettes every packet anyway.

## 5. Correctness Interplay with Snapshot Model (Task 7)

- `markSectionMutation` bumps `mutation_generation` → any in-flight consumer
  holding a snapshot token fails its `end_snapshot` check and re-reads.
- `refreshSection` bumps `mutation_generation` again (data changed) and clears
  the dirty bit atomically under the chunk write lock.
- Consumers that ignore the dirty mask and encode anyway get *stale but
  self-consistent* data (section replacement is atomic under the lock) — the
  same guarantee vanilla gives between packet construction and tick.

## 6. Not Yet Wired Live

The mutation-audit hooks (§1 of audit's "Required Coremod Hooks") are designed
but not yet installed in `Chunk.setBlockState`/`setStorageArrays`/`onUnload`.
Task 9's modpack SHADOW does not need them (SHADOW compares at generation
time before population); they are the entry gate for any ON_EXPERIMENTAL
native-authoritative mode and are listed as M4.2 work.

## 7. Artifact Map

- Rust: `NativeChunk::mark_section_mutation/refresh_section/dirty_mask`
  (crates/native-chunk/src/chunk.rs), `ChunkRegistry::{mark_section_mutation,
  refresh_section, dirty_mask}` (registry.rs), `NativeSection::replace_states`
  (section.rs)
- FFI: calls 310 (`markSectionMutation`), 311 (`refreshSection`), 312
  (`getDirtySections`) in crates/ffi/src/native_chunk.rs
- Java: declarations in tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java
- Benchmark: tools/bridge/src/com/rustcraft/bridge/M4RefreshBenchmark.java
  (compiled to tools/dist/bench-build, run against target/release/rustcraft_ffi.dll)
- Tests: `test_section_refresh_model`, `test_versioned_snapshot_model`
  (crates/native-chunk/src/lib.rs) — 7/7 pass
