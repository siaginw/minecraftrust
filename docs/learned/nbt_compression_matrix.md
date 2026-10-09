# NBT Compression Format Usage Matrix

## 1. Executive Summary
Minecraft Java Edition 1.12.2 employs three distinct compression formats for NBT serialization depending on the subsystem and storage medium:
1. **GZIP (`java.util.zip.GZIPInputStream` / `GZIPOutputStream`):** Standalone world metadata, saved data tables, and structure templates.
2. **ZLIB / Deflate (RFC 1950, Compression Type 2):** Chunk records inside Anvil `.mca` region files.
3. **Uncompressed / Raw Binary:** Network packet buffers and in-memory streams.

---

## 2. Comprehensive Subsystem Compression Matrix

| Subsystem / File Pattern | Compression Format | Codec Class / Method | Header Magic Bytes | Buffer Size / Depth Cap |
| :--- | :--- | :--- | :--- | :--- |
| **`level.dat` / `level.dat_old`** | **GZIP** | `CompressedStreamTools.readCompressed` | `0x1F 0x8B` | Infinite (`NBTSizeTracker.INFINITE`) |
| **`data/*.dat` (WorldSavedData)** | **GZIP** | `CompressedStreamTools.readCompressed` | `0x1F 0x8B` | Infinite |
| **`structures/*.nbt` (Templates)** | **GZIP** | `CompressedStreamTools.readCompressed` | `0x1F 0x8B` | Infinite |
| **`forcedchunks.dat` (Forge)** | **GZIP** | `CompressedStreamTools.readCompressed` | `0x1F 0x8B` | Infinite |
| **`playerdata/*.dat` (Players)** | **GZIP** | `CompressedStreamTools.readCompressed` | `0x1F 0x8B` | Infinite |
| **Region Chunks (`region/*.mca`)** | **ZLIB (RFC 1950)** | `RegionFile.getChunkDataInputStream` | `0x78 0x9C` (or `0x78 0xDA`) | Sector-aligned, max 1 MiB |
| **Legacy Region Chunks (Type 1)**| **GZIP** | `RegionFile` (Type 1 supported) | `0x1F 0x8B` | Historical Anvil compatibility |
| **Network `PacketBuffer`** | **Uncompressed** | `PacketBuffer.readCompoundTag` | `0x0A` (`TAG_Compound`) | **2 MiB cap (`NBTSizeTracker(2097152L)`)** |
| **`SPacketUpdateTileEntity`** | **Uncompressed** | Embedded in Netty Packet | `0x0A` | 2 MiB cap |
| **`SPacketSetSlot` / Items** | **Uncompressed** | `PacketBuffer.writeItemStack` | `0x0A` (or `0x00` if null) | 2 MiB cap |

---

## 3. Network vs Storage Compression Boundaries

### Storage Boundary:
- Chunk storage uses ZLIB compression per chunk record. Each chunk is compressed independently using `DeflaterOutputStream(new Deflater())` before writing into 4096-byte sectors.
- This per-chunk compression isolates corruption: a defect in one chunk does not invalidate adjacent chunks in the `.mca` file.

### Network Boundary:
- Individual NBT compounds written to `PacketBuffer` (such as TileEntity sync or ItemStack tags) are **uncompressed** binary NBT bytes.
- Compression over the network operates at the **Netty channel level**:
  - `NettyCompressionEncoder` / `NettyCompressionDecoder` wraps the entire packet frame in ZLIB if packet length exceeds the compression threshold (typically 256 bytes, configured in `server.properties` via `network-compression-threshold`).
  - NBT inside the packet is not double-compressed.
