# M1 Canonical Payload Scope

## Authoritative Boundary

**ANSWER B**: Rust outputs **SECTION BYTES + BIOME BYTES**.

The M1 native path owns the complete `SPacketChunkData` payload — all bytes
that go into `field_149286_e` (the `byte[]` data field). This includes:

1. Per-section block data (palette + BitArray storage words)
2. Per-section block light (2048 bytes)
3. Per-section sky light (2048 bytes, when present)
4. Biomes (256 bytes, when `fullChunk == true`)

## Data Flow

```
Java populateStagingBuffer()
  → writes [header | sections | biomes] into DirectByteBuffer
  → staging buffer = INPUT-A schema v1

JNI call: encodeSections(stagingAddr, stagedLen, outAddr, outCap)
  → Rust reads staging, writes wire-format bytes to output
  → returns: byte count written (≥ 0) or error code (< 0)

Java reads `written` bytes from output buffer
  → sets as packet field_149286_e payload
```

## Staging Schema (INPUT-A v1)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 2 | schema_version (0x0001) |
| 2 | 2 | effective_bit_mask |
| 4 | 1 | flags (bit 0 = fullChunk, bit 1 = skyLight) |
| 5 | 1 | section_count (must == popcount(mask)) |
| 6+ | var | per-section data (section_idx, bitsPerBlock, palette, storage, blockLight, [skyLight]) |
| end | 256 | biomes (only when fullChunk) |

## Rust Output

Rust `encode_sections()` transcodes the staging buffer into Minecraft Protocol 340
wire format. The output contains the same data in wire order:

- Per-section: bitsPerBlock byte, palette VarInt+entries, storage VarInt+longs, blockLight, [skyLight]
- Biomes: 256 raw bytes (when fullChunk)

## Return Value Semantics

| Value | Meaning |
|-------|---------|
| >= 0 | Bytes written to output buffer (valid payload) |
| < 0 | Error code (see ChunkPacketError enum) |

`written == 0` is **impossible** for any valid input: zero-section fullChunk still
produces 256 biome bytes. Zero-section non-fullChunk produces 0 sections and 0
biomes = 0 bytes, but that case has an empty staging buffer (6-byte header only)
and Rust returns `Ok(0)`.

## What Java Does NOT Append

Java does not append biomes or any other payload bytes after the Rust output.
The `written` bytes from Rust ARE the complete payload. Java only:

1. Copies `written` bytes to a `byte[]`
2. Sets packet fields (chunkX, chunkZ, fullChunk, mask, buffer, TEs)
3. TileEntity NBT tags are collected separately (100% Java, not in payload bytes)

## Consistency Requirement

All parity tests, benchmarks, and documentation MUST use this same boundary.
No zero-section special case may use a different ownership boundary.

---
*Traced from: NativeChunkPacket.java (populateStagingBuffer, executeNativePopulation),
crates/chunk-packet/src/lib.rs (encode_sections). Commit: 7476105*
