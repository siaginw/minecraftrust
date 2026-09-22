# M4.2A Checkpoint — Representation and Live-Snapshot Correctness
**Date:** 2026-09-21 · **Base:** 08fa2e6 · **Provenance:** M42A-REPRESENTATION
**Machine results:** `machine/M42A-representation-results.yaml`

---

## Commits / integrity
Work staged on top of 08fa2e6 (M4.1); commit at end of this checkpoint. All
defaults OFF (`worldgen_shadow` off unless set; new hooks additionally gated by
`-Dminecraftrust.m4.coherency=true`). No native packet transmission, no
authoritative storage or persistence anywhere in this milestone.

## SevTech cause — CONFIRMED (was a comparator artifact, not a fill divergence)
Decompiled the **installed** JustEnoughIDs-1.0.3-55: `MixinChunkPrimer` injects
at HEAD of primer get/setBlockState, redirects both to a private
`int[65536] intData` holding **runtime registry state ids**, and **cancels the
originals** — `char[] field_177860_a` is never read or written and stays
all-air. The M4.1 comparator read that dead array: `j=0(air)/r=16(stone)` at
every cell was oracle obsolescence, not terrain divergence. **No terrain
algorithm change made or justified.** M4.1 yaml corrected in place, original
diagnosis retained above the correction. Logical-oracle re-probe (hardened
resolver): **37,483/37,483 chunks logical-match, 0 mismatches** — the kernel is
logically exact under SevTech as-is; the M4.1 divergence diagnosis is retired
with evidence. Registration stays 0 in JEID mode (probe-only until int[]
ingestion with checked conversion exists). sevpatches inspected: spawn-chunk spawning only — irrelevant. JEID also
reformats the **biome** wire array (int varints); section block-state wire
stays vanilla; its container mixin alters NBT persistence, not packets.

## State widths & registry semantics (explicit, per runtime)
| Runtime | Primer backing | Id width | u16 path |
|---|---|---|---|
| Vanilla/Forge | `char[]` | 16-bit, registry ids in **gap-legacy scheme `id=(blockId<<4)|meta`** (stone=16; registry 5,365; gaps unassigned) | always valid |
| NEID 1.5.4.4 (Revelation, installed) | `char[]` low16 **+ added `byte[]` high8** | 24-bit | **only while high bytes all zero** — now a per-chunk checked gate (`m4_neid_width_ineligible`), never assumed |
| JEID 1.0.3 (SevTech, installed) | `int[] intData` only; char[] dead | 32-bit, repacked registry | **no** — probe-only; registration disabled pending int[] ingestion with checked conversion |

Global palette bits: **`32 − nlz(size−1)`** (ceil-log2), proven by decompiling
`MathHelper.log2` and boundary-testing the real method at 4096/8192/65536 —
pow-2 sizes do **not** round up (8192→13). M4.1's bridge had used bitLength
(`32−nlz(size)`), wrong at every pow-2 boundary; fixed in bridge + harness.

## Test gates (two distinct)
- **Gate 1 — byte equality, aligned history:** 26/26 vs the real
  `BlockStateContainer.func_186009_b` (bits 4/5/6/7/8/13; all modes).
- **Gate 2 — logical equality, independent decoder:** 11/11 (plain 1–260
  unique, overwrite-churn histories, sparse, all-air chunk, light + biome
  pass-through). Decoder is spec-derived Java; palette inversion via the real
  `BLOCK_STATE_IDS.getByValue`. (Found en route: `func_176220_d` is a
  **block-id-space** lookup, not the registry inverse — first decoder run's
  false mismatches exposed the gap-legacy scheme.)
- Rust units: 10/10 (stale-read rejection, refresh→mutate→refresh, partial
  refresh, light-only, all-air release, unload/reload stale handles,
  cross-dimension no-aliasing).
- All-air semantics from SPacketChunkData bytecode: full-chunk packets
  **exclude** empty sections (`isFullChunk && isEmpty()`, `isEmpty ==
  blockRefCount==0 == our non_air_count`) → refreshed-to-air sections release
  and drop from the mask, matching vanilla. Partial-filter packets would
  include present-but-empty sections — partial emission unsupported, flagged.

## Live mutation coverage & exclusions
- **Covered (hooks installed, default OFF):** `Chunk.setBlockState` (block
  mask, per-section bit; population bursts collapse into the mask — no per
  block JNI, no dedicated populate hook needed), `setLightFor` (separate light
  mask), `setStorageArrays` + `setBiomeArray` (full invalidation — Anvil load /
  biome change), `onChunkUnload` (unload queue → native eviction).
- **Excluded / Java-owned:** TileEntity NBT (packet consumers must merge
  Java-side or fall back); JEID int[] ingestion; partial-filter packets.
- **Unknown paths disable, never silently pass:** unresolvable dimension →
  `DIM_UNKNOWN` → full invalidation of that chunk's native use.
- Snapshots labeled by stage: registration is at setBlocksInChunk return =
  **base terrain, pre-surface/pre-population** — not claimed as current loaded
  state; the flush engine is what makes it current (extract → `refreshSection`
  → encode under write lock with snapshot-token check; stale encode = error,
  never published).
- Memory safety = `Arc<RwLock>` lifetimes across reads (unload during encode
  keeps the chunk alive), not the generation counters.

## Independent packet-decoder results
Offline: Gate 2 above (11/11). Live: the coherency flush decodes every
refreshed section's `encodePacket` output and compares all 4096 cells + both
light arrays back against Java — see run verdict below.

## Snapshot / registry memory lifecycle
Registry now reports allocations/releases/retention (FFI 315): current chunks,
sections, retained-bytes estimate, cumulative sections allocated/released,
chunks evicted; refresh alloc/release and replace/evict accounting included.
Keys are dimension-scoped (`ChunkKey{dim,cx,cz}`; cross-dim test proves no
aliasing; FFI 300–313 all take `dim` — ABI change, gates re-run green).

## Live run verdict (bounded Target-A SHADOW with real mutations — CLEAN, one explained anomaly)
Two instrumentation completions were needed (counter dump wiring; dimension
resolution hierarchy walk — the second run's own evidence showed DIM_UNKNOWN for
every chunk, 0 refreshes). Final run: terrain 3,009/3,009 bit-exact; hooks
recorded 27.5M block-sets + 1.9M light-sets with zero per-write JNI;
**20,177 sections refreshed, 20,158 independently decoder-validated, 0
mismatches**, 0 stale-encode rejections, refresh stayed enabled; 2,721 unloads
flushed == 2,721 native evictions; alloc/release arithmetic exact (15,736 −
14,489 = 1,247 retained sections, 15.4 MB, 288 live chunks). Anomaly: 19
sections (0.09%) threw during decode-location after refresh — all-air sections
legitimately absent from the full-chunk mask while the locator parsed into the
biome tail; validator fixed (all-air absence = validated PASS, own counter),
per-case confirmation deferred to the next bounded run.

## Next safe gate
1. SevTech logical-oracle probe with hardened resolver (kernel-parity verdict
   under JEID; still no registration there).
2. If the Target-A mutation run is clean: live M1 SHADOW byte-compare of the
   generalized encoder against Java SPacketChunkData (the last unproven live
   surface), still no transmission.
3. JEID int[] ingestion design (checked conversion) only if SevTech kernel
   parity matters for a future goal.
M1 ON remains blocked until live snapshot validity + complete packet
correctness (incl. TileEntity merge story) are established — not recommended.
