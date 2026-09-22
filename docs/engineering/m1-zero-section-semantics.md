# M1 Zero-Section & Effective-Mask Semantics

## The Previously Observed 63/1000 Fallbacks — Root Cause

M14ValidationHarness iterates `numSections = p % 16`, mask = `(1<<numSections)-1`
(or 0xFFFF at 16). For `p % 16 == 0`, the mock chunk has **zero populated
sections** and mask = 0. Over 1000 packets, 63 packets hit numSections=0
(p=0,16,32,...,992).

Old behavior chain (WRONG):

1. `vanillaWritesSection` used `ebs != null`. Mock chunk's storage array is
   filled by `Chunk.<init>` with `Chunk.field_186036_a` (the shared NULL
   sentinel), which is **non-null**, so `ebs != null` was always true.

> **CORRECTION (later this session, probe-verified)**: `Chunk.field_186036_a`
> is in fact **null at runtime** — `<clinit>` assigns `aconst_null` to it
> (verified via javap on the SRG jar and `ProbeSentinel`). It is a
> null constant, not an object singleton. The original `ebs != null`
> checks were therefore NOT always-true; they were correct null checks.
> The sentinel-style comparisons remain valid (they are equality checks
> against null) and the M1.4 fixes are behavior-preserving, not bug fixes.
2. Full-chunk packets therefore attempted to encode the sentinel as a real
   section; non-full-chunk packets staged 0 sections but the effective-mask
   computation still returned nonzero bits for sentinel slots.
3. Either the Rust encoder rejected the inconsistent staging buffer
   (section_count != popcount(mask) → `InvalidSectionBitmask`), or
   `written <= 0` misread a valid small/zero `written` as an error.
4. Result: fallback counted, packet NOT native.

Corrected behavior chain (commit 7476105):

1. `vanillaWritesSection` compares `ebs != Chunk.field_186036_a` — matches
   vanilla `extractChunkData` exactly.
2. Effective mask = bits for sections vanilla would actually write
   (sentinel or empty sections in a full chunk are skipped).
3. Zero populated sections → section_count=0, mask=0, staging = 6-byte header
   (+256 biome bytes when fullChunk). Rust accepts both cases
   (`test_empty_chunk_biomes_only` proves the fullChunk variant).
4. `written < 0` is the only error condition. Zero-section fullChunk returns
   `written = 256` (biomes only). Zero-section partial chunk returns
   `written = 0` — valid empty payload.

Result: 1000/1000 native, 0 fallbacks.

## Byte-Count Semantics (no ambiguity)

| Case | requested mask | effective mask | fullChunk | section bytes | biome bytes | Rust `written` | final payload |
|------|---------------|----------------|-----------|---------------|-------------|----------------|---------------|
| 16 non-empty sections | 0xFFFF | 0xFFFF (minus empty) | true | Σ sections | 256 | sections+256 | = written |
| 0 sections, fullChunk | 0xFFFF (or any) | 0x0000 | true | 0 | 256 | 256 | 256 bytes |
| 0 sections, partial | low bits | 0x0000 | false | 0 | 0 | 0 | 0 bytes |

- "0 section bytes" ≠ "0 total payload bytes" only when fullChunk=true: the
  256-byte biome array is still present. `written` from Rust always includes
  biomes when fullChunk (see `m1-payload-scope.md` — scope is SECTION+BIOME).
- Final `SPacketChunkData` payload == the `written` bytes from Rust. Java
  appends nothing afterward.

## Vanilla Semantics (verified from SRG jar bytecode)

`Chunk.extractChunkData(PacketBuffer, Chunk, int filter)` / the
`SPacketChunkData(Chunk, int)` constructor:

- Section written iff: `ebs != Chunk.field_186036_a` && `(!fullChunk || !ebs.isEmpty())` && `(filter & (1<<i)) != 0`
- `extractChunkData` **returns the effective mask**, which the constructor
  stores in `field_186948_c`. The requested filter is NOT copied verbatim.
- TileEntities included iff: `fullChunk || (filter & (1 << (y >> 4))) != 0`
- Biomes (256B) appended only when fullChunk.
