# Anvil Region Storage Format Specification (.mca)

## 1. Physical Layout Architecture
The Anvil region storage format organizes chunk data into 512 KiB - 10 MiB `.mca` files containing a $32 \times 32$ grid of chunks (1,024 chunks total per region file).
World region coordinates map directly from chunk coordinates:
$$\text{regionX} = \text{chunkX} \gg 5 = \lfloor \text{chunkX} / 32 \rfloor$$
$$\text{regionZ} = \text{chunkZ} \gg 5 = \lfloor \text{chunkZ} / 32 \rfloor$$
$$\text{Filename} = \text{"r."} + \text{regionX} + \text{"."} + \text{regionZ} + \text{".mca"}$$

All allocations inside a `.mca` file occur in **4096-byte (4 KiB) sectors**.

```
Offset 0x0000 ┌────────────────────────────────────────────────────────┐
              │ Location Table (1024 entries x 4 bytes = 4096 bytes)   │ Sector 0
Offset 0x1000 ├────────────────────────────────────────────────────────┤
              │ Timestamp Table (1024 entries x 4 bytes = 4096 bytes)  │ Sector 1
Offset 0x2000 ├────────────────────────────────────────────────────────┤
              │ Chunk Payload Sector(s)                                │ Sector 2
              │  - 4 bytes: Length (Big-Endian uint32)                 │
              │  - 1 byte: Compression Scheme (2 = ZLIB)               │
              │  - (Length - 1) bytes: Compressed NBT Stream           │
              │  - Sector Zero-Padding up to 4096 boundary             │
              ├────────────────────────────────────────────────────────┤
              │ Chunk Payload Sector(s)                                │ Sector N
              └────────────────────────────────────────────────────────┘
```

## 2. Header Tables

### A. Location Table (Bytes 0 – 4095)
Located in Sector 0. Contains 1,024 big-endian 4-byte integers indexed by chunk local position:
$$\text{localX} = \text{chunkX} \ \& \ 31$$
$$\text{localZ} = \text{chunkZ} \ \& \ 31$$
$$\text{slotIndex} = \text{localX} + \text{localZ} \cdot 32$$
$$\text{byteOffset} = \text{slotIndex} \cdot 4$$

Each 4-byte integer packs two fields:
- **Bits 0..23 (Upper 3 Bytes):** Sector offset from the start of the file ($0 \dots 16,777,215$). Multiply by 4096 to get file seek position.
- **Bits 24..31 (Lowest Byte):** Sector count allocated to this chunk ($1 \dots 255$).

If an entry is `0x00000000`, the chunk has never been generated or saved in this region.

### B. Timestamp Table (Bytes 4096 – 8191)
Located in Sector 1. Contains 1,024 big-endian 4-byte unsigned integers representing the Unix timestamp (seconds since epoch) when each chunk was last committed to disk:
$$\text{timestampByteOffset} = 4096 + \text{slotIndex} \cdot 4$$

## 3. Chunk Payload Format
Seeking to `sectorOffset * 4096` yields:
1. **Length (4 bytes, Big-Endian):** Total length of payload including the compression type byte, but excluding this 4-byte length field.
2. **Compression Type (1 byte):**
   - `0x01`: GZIP (legacy format).
   - `0x02`: ZLIB / Deflate (standard format in Minecraft 1.12.2).
3. **Payload Stream:** `(Length - 1)` bytes of RFC 1950 zlib-compressed binary NBT data.
4. **Sector Alignment Padding:** Padded with zero bytes so the next chunk starts cleanly at the next 4,096-byte boundary.

## 4. The 255-Sector (1 MiB) Overflow Hazard
Because the sector count field in the location table is strictly an 8-bit unsigned integer (`sector_count & 0xFF`), the maximum contiguous space allocatable to a single chunk is:
$$\text{Max Sectors} = 255$$
$$\text{Max Payload Size} = 255 \times 4096 = 1,044,480 \text{ bytes } (\approx 1.0 \text{ MiB})$$

### The Catastrophic Failure Mode:
In modded Forge environments, chunks containing dense tile entity networks, automated AE2/Refined Storage networks, or enormous Forestry bees/custom NBT can exceed 1 MiB when compressed.
In `RegionFile.java`:
```java
int sectorsNeeded = (length + 4) / 4096 + 1;
if (sectorsNeeded >= 256) {
    return; // SILENT RETURN! Data is NOT written!
}
```
**Consequence:** When a chunk exceeds 1 MiB, Minecraft **silently aborts saving**. No warning or crash is emitted. When the server restarts, all modifications to that chunk are lost, and the chunk reverts to its previous disk state or corrupts!
