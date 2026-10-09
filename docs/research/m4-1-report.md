# M4.1 Report — Generalized Native Section + Modpack Coherency
**Date:** 2026-09-21
**Provenance:** M41-GENERATIVE-PALETTE (dll 6efe026329380865, coremod 5e97c320e119d740)
**Machine results:** `machine/M41-modpack-shadow-results.yaml`

---

## A. Executive Summary

M4.1 generalized NativeSection from the fixed 4-bit prototype to full 1.12.2
palette semantics (linear 4-bit, hashmap 5–8-bit, global registry), validated
the generalized wire encoder **byte-exactly against the real vanilla
implementation** (26/26 cases including every cross-word bit width), ran modpack
SHADOW on clean Forge (2,931/2,931) and Revelation (38,179/38,949-class clean —
re-run numbers in §M), and mapped the compatibility boundary precisely: SevTech
patches the base-terrain primer fill (field kernel still bit-exact at 46.9M
doubles), where the M4 retention gate correctly self-disabled with zero state
retained. Refresh design (section dirty-mask + lazy pull) is implemented and
measured; hook cost 0.1 µs, section pull 2.7 µs.

**Two real encoder bugs were found and fixed by the new real-vanilla parity
harness — bugs that every self-consistent test had missed** (§F). The single
most important methodological result of this milestone: *roundtrip self-tests
cannot prove wire parity; only comparison against the actual implementation can.*

## B. Scope & Method

All work under the standing rubric: REFERENCE_JAVA (decompiled live jars) →
RUST_PARITY → EXTERNAL_OPTIMIZATION_RESEARCH → RUST_OPTIMIZED → COMPLETE
BENCHMARK → DUAL-AXIS DECISION → COMPATIBILITY LADDER. No authoritative native
output anywhere; every live mode is SHADOW (Java authoritative, read-only
comparison) with registration gated on bit-identity.

## C. 1.12.2 Palette Semantics (verified against bytecode, not assumptions)

Decompiled from `minecraft_server.1.12.2.srg.jar`:

- **Global state id = (blockId << 4) | meta.** Stone's default state is id 16,
  not 1. Vanilla srg registry after Bootstrap: **5,365 states**.
- `BlockStateContainer.write(PacketBuffer)` = `writeByte(bits); palette.write(buf); writeVarInt(longCount); writeLong*bigEndian`.
- **Linear palette** (≤4 bits): `writeVarInt(len)`, entries = global state ids,
  insertion order, air at 0 (registered in `<init>`).
- **HashMap palette** (5–8 bits): same form; entry order = palette-id order
  (insertion order); resizes only when full → widths land on
  `ceil(log2(paletteLen))`.
- **Global palette** (requested bits > 8): palette serializes as **only
  `varint(0)`** — no entries; data packs raw global ids at
  `bits = MathHelper.log2(BLOCK_STATE_IDS.size())` = `BigInteger.bitLength` =
  `32 - nlz(size)` — **13 for vanilla 5,365; runtime-dependent under mods**.
- Mode thresholds (incl. air): ≤16 → 4-bit linear; 17–256 → hashmap
  ceil-log2 bits; >256 → global.

## D. External Research (summary)

Lithium/Cubicite-class palettes keep packed storage and pay unpack on access;
Valence/fastanvil model palettes as (bits, palette, words) per section and
rebuild on change. Our chosen hybrid — canonical u16 engine form + derived
cached wire palette — follows the M1 lesson (zero staging at packet time) and
the refresh analysis (§L): transfers and rebuilds are bursty, reads are hot.

## E. Generalized NativeSection Design

- Engine representation: `states: u16[4096]` canonical global ids
  (65,535 cap = inherent ChunkPrimer `char[]` limit).
- Wire derivation: `build_local_palette()` — dedup scan (air pinned at 0),
  bit-width per §C, BitArray pack; cached in `palette_cache`, invalidated on
  any mutation/refresh.
- Light arrays 2×2,048 B carried per section; `non_air_count`, flags.
- Sparse chunk: `Option<Box<NativeSection>>[16]`; all-air refreshed sections
  are released and drop out of the primary mask.

## F. The Cross-Word Spanning Bug (found by real-vanilla harness)

First harness pass: **0/26** despite all offline self-tests passing. Byte-diff
decomposition showed 4-bit and 8-bit cases byte-exact but 5/6/7-bit cases
diverging inside data words: the spanning branch had the two word halves
swapped (`word0 |= value >> fit; word1 |= value << fit` instead of
`word0 |= (value & mask) << offset; word1 |= value >> fit`). Widths that
divide 64 (4, 8) never span → invisible to them; the offline spec-model
harness roundtripped through the same buggy pack/unpack pair → self-consistent.
Fixed in `NativeSection::pack_entry` (single shared packer for local + global
modes). Second real bug: global bits was a hardcoded constant where vanilla
computes it from the live registry — now an `AtomicU8` set by the bridge
(`setGlobalPaletteBits`, FFI 313) from `BLOCK_STATE_IDS.size()` at first load.

## G. Dynamic Palette Parity Harness (offline spec model)

`crates/native-chunk/examples/palette_parity.rs` — 17 cases across
transitions 1..>256 unique states: mode selection, wire sizes, roundtrip.
Retained as a fast regression gate; superseded (not replaced) by §H for
wire-byte claims.

## H. Real-Vanilla Wire Parity Harness (the gold standard)

`tools/bridge/src/com/rustcraft/bridge/M4WireParityHarness.java` — offline,
no server: Bootstrap-registers the actual vanilla classes, builds a live
`BlockStateContainer`, fills it via `set()` in section-index order (aligning
vanilla palette insertion with our scan order), serializes with
`func_186009_b(PacketBuffer)` — the exact method `SPacketChunkData` calls —
and byte-compares against the native encoder fed the true registry state ids.

**Result: 26/26 byte-exact.** Coverage: bits {4,5,6,7,8,13} × modes
{linear, hashmap, global} × unique-state counts 1..512, including every
cross-word width and the global-palette boundary (>256 → global, `varint(0)`
palette, 13-bit raw ids).

## I. M1 Direct-Native Revalidation

Current release build: **1.17 µs mean** full-chunk packet (31,008 B,
~855K chunks/sec, p99 1.0 µs) — wire bytes now proven exact by §H.
(First-pass 32.84 µs observation was the pre-release-build bench; both are
~2 orders below the Java construction path measured in M1.4.)

## J. Mutation Paths Audit (summary — full doc `m4-1-mutation-audit.md`)

11 mutation paths catalogued with entry points, invalidation needs, and the 5
required coremod hooks (setBlockState, setStorageArrays, onUnload, light,
post-populate). Hooks designed, not yet installed — the entry gate for any
ON_EXPERIMENTAL native-authoritative mode (M4.2).

## K. Versioned Snapshot Model

Per-chunk `mutation_generation` / `snapshot_generation` / `dirty_sections`.
Consumers bracket reads with `begin_snapshot`/`end_snapshot`; any mutation
during a read fails the token check. `mark_mutation` (chunk) and
`mark_section_mutation` (section bit) bump the generation; `refresh_section`
bumps it again atomically under the write lock and clears the bit.
FFI 309–312; unit-tested (`test_versioned_snapshot_model`,
`test_section_refresh_model`; 7/7 crate tests).

## L. Refresh Design & Measurements (full doc `m4-1-refresh-design.md`)

Section-granular dirty mask + lazy pull on the consumer path:

| Operation | mean | note |
|---|---|---|
| `markSectionMutation` hook | 0.10 µs p50 | per Java mutation; bursts collapse into a mask |
| `refreshSection` 12 KB | 2.7 µs | ~4.6 GB/s effective |
| 5-section chunk refresh | 11.1 µs | population burst case |
| `registerPrimer` 128 KB | 44.6 µs | coarse alternative; also invalidates handles |
| refresh + encode (4-state / 60-state) | 13.7 / 80.6 µs | palette rebuild dominates, not transfer |

v2 headroom: incremental palette (rebuild only on unseen id), hashmap dedup
(O(4096) vs O(4096×u)), single-block write-through for sporadic edits.

## M. Modpack SHADOW Results

| Target | Terrain compared | Matches | Registered | Consumers | Verdict |
|---|---|---|---|---|---|
| A clean Forge (fixed build) | 2,931 | 2,931 | 2,931 | 3×2,931 | CLEAN |
| C Revelation (fixed build) | 35,660 | 35,660 | 35,660 | 3×35,660 | CLEAN (1.05 GB encoded; first pass on pre-fix dll: 38,179/38,179) |
| D SevTech | 56,949 | 0 | **0** | 0 | DIVERGENT_PACK_FILL — safely self-disabled |

SevTech detail (§N): field kernel 56,949/56,949 bit-exact (46,982,925 doubles);
terrain primer diverges from the first cell (y=0 air vs stone) on every chunk.
Pack ships `sevpatches-1.9-10` + `JustEnoughIDs-1.0.3-55` Mixin tweakers that
patch chunk/primer code. Server otherwise clean (pack-baseline recipe/NPE noise
only, same class as prior campaigns); contexts 2/2 balanced; 0 native errors.

## N. SevTech Boundary Finding

The density→noise kernel is pack-portable (initNoiseField contract untouched by
262 mods, both campaigns). The density→primer fill is NOT: our
`terrainSetBlocksOpt` models the vanilla fill (stone=16, water=144, sea level
from settings); SevTech's patched fill writes something else at every chunk.
The retention gate (register only on bit-identity) turned this into a
read-only observation: 0 native chunks, 0 packet bytes, no leak, no crash.
**This is the M4 compatibility boundary: per-pack terrain-fill eligibility,
mirroring the M3W worldgen classification discipline.**

## O. Compatibility Ladder Status

offline differential (roundtrip + spec model) → **real-implementation parity
(26/26)** → clean-Forge SHADOW (2,931/2,931) → modpack SHADOW: Revelation ✓,
SevTech ✗ (pack fill divergence, safely handled). ON_EXPERIMENTAL: not
attempted this milestone (blocked on J's hooks by design).

## P. Memory Model (probe: `crates/native-chunk/examples/memory_model.rs`)

- `NativeSection` = 12,352 B engine base; +2.1–6.7 KB cached wire palette
  (4-bit → 13-bit). `NativeChunk` = 936 B + active sections.
- Typical 5-section overworld chunk: **61.3 KB base / 71.9 KB cached**;
  radius-20 (1,681 chunks): 100.6 / 118.0 MB.
- Vanilla packed 4-bit section ≈ 6.3 KB (computed layout): native canonical is
  ~2× per section — a deliberate trade for O(1) access and zero unpack;
  inactive sections cost 8 B (sparse `Option<Box>`).
- u16 id cap = 65,535 = inherent ChunkPrimer limit; vanilla registry 5,365.

## Q. Second Consumer Validation

- **Packet consumer (first):** byte-exactness per §H; live invocation per §M.
- **Occupancy + persistence consumers:** invoked per registered chunk in every
  live run (2,931 + 38,179 first-pass; re-run pending in §M), structural
  validation (correct masks, counts, section headers) in 7/7 unit tests.
  Byte-parity vs `Chunk.getDataForNBT`/Anvil NBT is M4.2 work (needs live
  Chunk objects; our persistence format is a defined intermediate).

## R. Stop Conditions & Safety

All clear: 0 wire mismatches (harness), 0 terrain mismatches where eligible,
0 native errors, 0 crashes, contexts balanced, registry unload verified, zero
native state retained on the divergent pack. M1/M2C remain OFF in all runs.

## S. Limitations & Threats to Validity

- Revelation's first pass used the pre-spanning-fix encoder; base-terrain
  sections are ≤16-state (4-bit, non-spanning) so those bytes were unaffected,
  and the fixed-build re-run (35,660/35,660) is the authoritative number.
- Palette-entry byte parity is asserted under aligned palette order
  (insertion order); production packets may legitimately differ in palette
  order from a Java-built packet — client-equivalent, not byte-identical,
  unless order is forced (the M1 SHADOW byte-compare did force it via
  Java's own palette; M4.2 re-run will re-prove under the generalized path).
- Refresh/palette benchmarks are single-host offline runs (JDK8, release DLL);
  no live-server mutation frequency measurements yet (hooks not installed).
- Memory model is computed-layout, not measured RSS/heap dump.

## T. M4.2 Recommendations

1. SevTech fill-divergence RE (sevpatches/JEID) → per-pack fill-model plugin or
   documented ineligibility.
2. Install the J hooks; add live mutation-frequency instrumentation.
3. M1 ON_EXPERIMENTAL fed from the native registry (offline parity now proven).
4. Palette-rebuild optimization (§L v2 items).
5. Persistence consumer parity vs Anvil NBT.

## U. Artifact Map

Rust: `crates/native-chunk/src/{section,chunk,registry,lib}.rs`,
examples `{palette_parity,memory_model}.rs` — FFI: `crates/ffi/src/native_chunk.rs`
(calls 300–313) — Java bridge: `NativeChunkBridge.java`, `WorldgenShadow.java`
(M4 hooks), harnesses/benches: `M4WireParityHarness.java`,
`M4RefreshBenchmark.java`, `M1DirectEncodeBenchmark.java` — runner:
`tools/m4-pack-shadow-run.sh` — docs: `m4-1-mutation-audit.md`,
`m4-1-refresh-design.md`, this report — results:
`machine/M41-modpack-shadow-results.yaml`, raw logs in `machine/raw/`.

## V. Provenance

dll sha256 6efe026329380865, coremod 5e97c320e119d740 (fixed builds);
first-pass modpack dll pre-fix (terrain/registration unaffected — §S).
Raw: `machine/raw/{M4-shadow-metrics-final,m4-rev-shadow-*,m4-sev-shadow-*}.txt|log`.

## W. Classification (dual-axis)

- **Performance value: HIGH** — 1.17 µs full-chunk packet encode (bytes
  proven exact), 2.7 µs section refresh, 0.1 µs mutation hook.
- **Rust migration value: HIGH** — multi-consumer native chunk foundation
  proven across the ladder with a precisely-mapped per-pack boundary; the
  generalized section is the substrate for M1 reactivation, persistence
  staging, and any future native-authoritative world state.
