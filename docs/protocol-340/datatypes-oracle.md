# 1.12.2 DATA-TYPE ORACLES (wire primitives) + Rust coverage

Java reference = `third_party_reference/minecraft/src/net/minecraft/network/PacketBuffer.java`
(1.12.2, big-endian Netty default order). Rust coverage is against the current
workspace (`crates/*`). This doc is the oracle + gap map for the future native
protocol core. **No implementation was changed.**

## Type-by-type oracle

### VarInt — `readVarInt` / `writeVarInt`
- 7 bits/byte, MSB(0x80)=continue, little-endian base-128. Max 5 bytes.
- Write: `while((v & -128)!=0){ writeByte(v&127|128); v>>>=7; } writeByte(v)` — unsigned shift ⇒ negatives encode 5 bytes.
- Read: `acc |= (b&127)<<(i++*7)`, throw `RuntimeException("VarInt too big")` if i>5.
- No size-table lookup (that's 1.13+/modern); recompute byte count on the fly.
- Example: `0x80 0x00` = 0; plain `0x00` = 0; `-1` = 5 bytes.

### VarLong — `readVarLong` / `writeVarLong`
- Same scheme, **10 bytes max**, 64-bit, unsigned shift for negatives.

### Boolean — `readBoolean` / `writeBoolean`
- 1 byte, `0x01` true / `0x00` false (Netty).

### Byte / Short / Int / Long
- ByteBuf passthrough, **big-endian**. (LE variants exist in Netty but are never used by wire schema methods.)

### Float / Double
- 4/8 bytes, IEEE-754, **big-endian**.

### String — `writeString` / `readString(int max)`
- **VarInt UTF-8 byte-length** prefix (NOT short).
- Write cap: literal **32767 bytes** → `EncoderException("String too big...")`.
- Read caps: `len > max*4` reject (byte-length vs max chars ×4), `len < 0` reject, `str.length() > max` reject.
- Callers pass explicit `max` (16, 20, 36, 40, 256, 32767, etc.).

### Position (BlockPos) — `writeBlockPos` / `readBlockPos`
- **One BE long**, mapping `(x&0x3FFFFFF)<<38 | (y&0xFFF)<<26 | (z&0x3FFFFFF)`.
- Decode sign-extends: `x=(v<<0)>>38, y=(v<<26)>>52, z=(v<<38)>>38`. Packing masks ⇒ out-of-range truncates silently (no throw).

### UUID — `writeUniqueId` / `readUniqueId`
- **Two BE longs** (MSB, LSB). 16 bytes. NOT the 16-LE-bytes format of newer versions.

### NBT — `writeCompoundTag` / `readCompoundTag`
- null → `writeByte(0)`.
- non-null → stream full TAG_Compound: leading `0x0A`, short BE name-length, MUTF-8 name, children, `TAG_End`(0). No byte-length prefix from PacketBuffer.
- Read: peek byte; `0` → null; else reset readerIndex and `CompressedStreamTools.read` with `NBTSizeTracker(2097152)` (2 MiB cap).

### ItemStack (Slot) — `writeItemStack` / `readItemStack`
- `short id`; `<0` → empty, stop. Else: `short id, byte count, short metadata`, then NBT **only if `isDamageable() || getShareTag()`** else null.
- **id is short BE, NOT VarInt** (VarInt ids come with 1.13.2).

### ByteArray — `writeByteArray` / `readByteArray`
- `VarInt len` + bytes. Read default cap = readableBytes (self-limiting).

### VarIntArray / LongArray
- `VarInt len` + per-element `VarInt` (or `Long` BE for longs). NOTE: no `writeIntArray` in 1.12.2 — raw int[] uses `writeVarIntArray`.

### Enum — `writeEnumValue` / `readEnumValue`
- `VarInt ordinal()`. No bounds check ⇒ out-of-range throws ArrayIndexOutOfBounds.

## Rust coverage matrix

| Type | Java method | Rust | Status | Location / gap |
|---|---|---|---|---|
| VarInt | readVarInt/writeVarInt | write-only, private | **PARTIAL** | `crates/chunk-packet/src/lib.rs` `write_varint`/`varint_len` (crate-private). No public read+write codec anywhere. |
| VarLong | readVarLong/writeVarLong | none | **MISSING** | — |
| Boolean/Byte/Short/Int/Long/Float/Double (BE) | ByteBuf passthrough | none | **MISSING** | `crates/buffers` is bare `NativeBuffer` (ptr/len/cap), no endian primitive codec. |
| String (VarInt prefix, 32767 cap) | writeString/readString | NBT variant only | **MISSING (protocol)** | `crates/nbt/src/codec.rs` has 2-byte-BE-length MUTF-8 strings (NBT format) — different. No protocol String. |
| Position | writeBlockPos/readBlockPos | math only | **PARTIAL** | `crates/core-types/src/lib.rs` has consts + `BlockPos::to_long()/from_long()` exact pack; no wire write of the BE long (no primitive codec). |
| UUID | writeUniqueId/readUniqueId | none | **MISSING** | — |
| NBT (stream) | write/readCompoundTag | present | **PARTIAL** | `crates/nbt` covers stream/codec/mutf8/tape + differential oracle (`tests/test_nbt_oracle_differential.py`). Missing the PacketBuffer wrapper: `0x00` sentinel + 2 MiB read cap. |
| ItemStack/Slot | write/readItemStack | none | **MISSING** | short-id format, not varint |
| ByteArray | write/readByteArray | none | **MISSING** | depends on VarInt codec |
| VarIntArray/LongArray | writeVarIntArray/writeLongArray | none | **MISSING** | depends on VarInt codec |
| Enum | write/readEnumValue | none | **MISSING** | — |

## Key findings

- **No general-purpose VarInt read+write codec exists in the workspace.** The
  only VarInt writer (`write_varint` in `crates/chunk-packet`) is write-only and
  private to that crate. VarInt underlies String, ByteArray, VarIntArray, Enum,
  and almost every packet — it is the load-bearing missing primitive for the
  read path.
- `crates/buffers` is a raw memory container with no cursor/endian readers.
- `crates/core-types` has correct BlockPos/ChunkPos math but no wire codec.
- UUID absent repo-wide.
- NBT is the best-covered: full codec + MUTF-8 + differential oracle + fuzz.

## Existing parity tests for these types

- `tests/test_nbt_oracle_differential.py` — Java (jdk-8 srg jar) vs `crates/nbt`: MUTF-8 parity, recursion-depth parity (500 vs 520), chunk roundtrip, 1000-payload fuzz. Oracle binary: `target/release/oracle_cli.exe` (`crates/nbt/src/bin/oracle_cli.rs`).
- `tests/test_chunk_storage.py` — NBT preservation, Anvil region corruption handling, chunk parse/serialize/zlib benches.
- `crates/chunk-packet/src/lib.rs` `#[cfg(test)]` — ABI, `predict_output_len==encode`, empty-biome, single-section+skylight, global-palette wire omission, truncation, capacity, mask mismatch, dup index, palette-width<4, palette overflow. No live JVM-wire parity here (that's `tools/chunk-packet-oracle`, `tools/compression-interop`).
- `crates/compression/src/lib.rs` tests — backend pin, roundtrip, threshold, capacity, incompressible bound, level bounds, context-reuse determinism.
- `crates/core-types` tests — golden coordinate corpus + roundtrip.

## Missing tests (future protocol core)

- VarInt: golden byte-sequences (0, 1, 127, 128, -1, 2147483647, -2147483648); roundtrip fuzz; cross-check against Java oracle + minecraft-data fixtures.
- String: length-cap boundaries (32767), `max*4` read rejection.
- Position: full-range roundtrip, sign-extension corners.
- UUID: MSB/LSB long order.
- ItemStack: empty/short-id/metadata/NBT-conditional.
- Entity metadata: all 14 serializer types roundtrip.
