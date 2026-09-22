# 1.12.2 CHUNK FORMAT DEEP DIVE (SPacketChunkData, protocol 340)

Extracted from unpacked 1.12.2 sources. This is the authoritative send format
for the future native chunk core and zero-copy encoding. Cross-links M1
(`crates/chunk-packet`) and M2-C (`crates/compression`).

Sources: `network/play/server/SPacketChunkData.java`, `world/chunk/Chunk.java`,
`world/chunk/BlockStateContainer.java`, `world/chunk/BlockStatePalette*.java`,
`world/chunk/storage/ExtendedBlockStorage.java`, `util/BitArray.java`,
`util/math/BlockPos.java`, `util/NibbleArray.java`, plus the Rust
`crates/chunk-packet/src/lib.rs` encoder.

## Packet header (`writePacketData`)

```
Int   chunkX          ← BE int, NOT VarInt   (source: writeInt/readInt)
Int   chunkZ
Boolean fullChunk
VarInt availableSections   (primary bitmask; bit i ⇔ section at y = 16*i)
VarInt dataLen
byte[] data               (pre-serialized chunk section stream)
VarInt tileEntityCount
  per TE: NBT (compoundTag)
```

Read guard: `dataLen` must not exceed 2 MiB (2097152) or decode throws
`RuntimeException("Chunk Packet trying to allocate too much memory on read")`.

## Chunk data sub-format (`extractChunkData` → in-constructor PacketBuffer)

Section selection: for each of 16 `ExtendedBlockStorage` (y ascending), include
it if the storage is non-null AND (`fullChunk` OR storage non-empty) AND
(`primaryBitMask & (1<<i) != 0`). Included bits are OR'd back into
`availableSections`. Sections with all-air are omitted when `fullChunk`.

Per included section, in y-ascending order:

```
Byte   bitsPerBlock            (palette/log2 bit width)
       palette (per bitsPerBlock):
         bits ≤ 4 :  VarInt count, then count × VarInt globalStateId   (BlockStatePaletteLinear)
         bits 5..8:  VarInt mapSize, then mapSize × VarInt globalStateId (BlockStatePaletteHashMap)
         bits > 8 :  VarInt 0    (BlockStatePaletteRegistry — global ids, no entries)
       BitArray backing:
         VarInt longCount, then longCount × Long (BE) = packed words (4096 entries)
Byte[] blockLight   (2048 raw bytes, NibbleArray)
Byte[] skyLight     (2048 raw bytes)  — ONLY if provider.hasSkyLight()
```

If `fullChunk`: `byte[] biomeArray` = **256 raw bytes** appended after all sections.

No heightmaps in 1.12.2 (they arrive in 1.15).

## Palette & bits rules (`BlockStateContainer.setBits`)

- bits ≤ 4 → clamped to **4**, `BlockStatePaletteLinear` (indexed array).
- 5 ≤ bits ≤ 8 → `BlockStatePaletteHashMap` (map id→state).
- bits > 8 → `BlockStatePaletteRegistry` (global palette; ids are direct into
  `Block.BLOCK_STATE_IDS`; wire palette length = 0). `bits` recomputed as
  `log2(BLOCK_STATE_IDS.size())`.
- Resize path (`onResize`) rebuilds backing array and re-ids every entry.

## BitArray format (`util/BitArray.java`)

- `longsPerWord` = `(bits*4096 + 63) >> 6`; backing `long[]` of that length.
- `getAt(index)/setAt` pack 4096 values of `bits` width into the words.
- Wire: `VarInt longCount` then `longCount × Long` (BE). The Rust encoder
  (`crates/chunk-packet`) copies the words raw from the staging buffer.

## Light arrays

- `NibbleArray` = 2048 bytes = 4096 nibbles; one nibble per block (4-bit).
- Block light always present per section. Sky light appended only when the
  world provider `hasSkyLight()` (dimension 0/-1 sky=true; dimension 1 void
  has no sky light). 1.12.2 has NO per-section boolean flag for light presence
  — presence is implied by `hasSkyLight()`; a decoder must know the dimension.

## Biomes

- 256 bytes, chunk-local column biome ids, only on full (ground-up) chunks
  (`fullChunk` true). Not present on delta sub-chunk updates.

## Packet sizing

- `calculateChunkSize` sums: per section `1 + paletteLen + longCount*8 + 2048(+2048 sky)`, plus 256 if full.
- `dataLen` (VarInt) prefixes the byte[] stream.
- True packet body (before framing): `4 + 4 + 1 + VarInt(mask) + VarInt(dataLen) + dataLen + VarInt(teCount) + ΣTE`.
- After compression (M2-C), the payload inside the zlib stream is
  `VarInt(uncompressedSize) + [deflate]`; see `machine/network-pipeline.yaml`
  compression_frame_observed (threshold 256, usz=0 means stored).

## Forge / NEID interaction

- Notch's Extended Item Data / NEID-style mods do NOT change the block-state
  palette format itself (bit width grows up to registry/global when >8 bits),
  and the item-id width is a short (ItemStack, unchanged). Block IDs beyond
  vanilla are carried in the palette as global state ids; the runtime registry
  (`Block.BLOCK_STATE_IDS`) is what extends, not the packet wire shape.
- Confirm per-modpack at validation time; treat any palette-width assumption
  (e.g. "4 bits enough") as bounded by `log2(BLOCK_STATE_IDS.size())`.

## Cross-links

- Existing Rust encoder: `crates/chunk-packet/src/lib.rs` (`encode_sections`,
  `encode_sections_raw`, `write_varint`, `predict_output_len`, staging ABI
  `M1_CHUNK_STAGING_V1`). IMPLEMENTED for section payload; missing: header
  (int x/z, full, mask, teCount TE NBT), decode, framing, per-dimension
  hasSkyLight gate (currently a parameter).
- Compression: `crates/compression` (zlib deflate, level 6, per-context).
- Learned doc: `docs/learned/block_state_container.md`,
  `docs/learned/extended_block_storage.md`.
- Parity fixtures live in `tools/chunk-packet-oracle`, `tools/compression-interop`,
  `tools/block-storage-oracle`; coverage in `docs/protocol-340/coverage-matrix.md`.
