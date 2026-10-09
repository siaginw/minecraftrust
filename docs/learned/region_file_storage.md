# RegionFile & RegionFileCache Concurrency and Storage Mechanics

## 1. Subsystem Overview
`RegionFile` and `RegionFileCache` (in package `net.minecraft.world.chunk.storage`) provide the direct low-level I/O abstraction over the physical `.mca` files. They encapsulate `RandomAccessFile` file handles, free-sector tracking bitmaps, and sector header updates.

## 2. RegionFileCache Lifecycle & Map Sizing
`RegionFileCache` maintains a static LRU-like cache of open `RegionFile` handles:
```java
private static final Map<File, RegionFile> REGIONS_BY_FILE = Maps.newHashMap();
```
- **Creation & Lookup:** `createOrLoadRegionFile(File worldDir, int chunkX, int chunkZ)` calculates the region coordinates, checks `REGIONS_BY_FILE`, and constructs a new `RegionFile` instance if absent.
- **Cache Eviction Policy:** If `REGIONS_BY_FILE.size() >= 256`, vanilla invokes `clearRegionFileReferences()`, which synchronizes, closes **all 256 open RegionFile handles**, and wipes the entire map!
  - *Performance Implication:* Servers with many players exploring vast worlds experience periodic I/O freezes when the 256-file cache flushes and re-opens dozens of file handles.

## 3. Concurrency, Locking, & Thread Safety
All access to a `RegionFile` instance is protected by Java monitor locks:
- `RegionFile.getChunkDataInputStream(int x, int z)` is `synchronized`.
- `RegionFile.getChunkDataOutputStream(int x, int z)` returns a custom `ChunkBuffer` stream whose `close()` method invokes `synchronized (RegionFile.this) { write(x, z, buf, count); }`.
- `RegionFileCache.createOrLoadRegionFile` is `synchronized`.

### Thread Access Patterns:
1. **Reads:** Executed primarily on background `Chunk I/O Executor Thread-#` worker threads during async chunk loads.
2. **Writes:** Executed exclusively on the background `File IO Thread` (`ThreadedFileIOBase`).
3. **Contention:** Because `RegionFile` uses instance-level synchronization, reads and writes to different chunks within the *same* region file serialize against each other. Reads or writes to *different* region files proceed in parallel across threads.

## 4. Sector Allocation Algorithm
When saving a chunk, `RegionFile` determines if the chunk can be written into its existing sector location:
1. Calculates required sectors:
   $$S_\text{req} = \lfloor (\text{payloadLength} + 4) / 4096 \rfloor + 1$$
2. Compares $S_\text{req}$ with the currently allocated sector count $S_\text{cur}$:
   - If $S_\text{req} \le S_\text{cur}$: Writes payload **in-place** at `sectorOffset * 4096`. (Any leftover sectors at the tail of the block are currently leaked until file reload).
   - If $S_\text{req} > S_\text{cur}$:
     - Marks the old $S_\text{cur}$ sectors as free in `sectorFree` bitset.
     - Scans `sectorFree` from sector 2 upwards for the first contiguous run of $S_\text{req}$ free sectors (First-Fit).
     - If no run is found, allocates $S_\text{req}$ sectors at the end of the file.
     - Marks newly allocated sectors as used in `sectorFree`.
     - Writes payload into new sector location.
     - Updates Sector 0 (Location Table) and Sector 1 (Timestamp Table).

## 5. Crash Consistency & Atomicity Analysis
Minecraft's `RegionFile` is **not crash-consistent** in the ACID sense:
1. **Lack of `fsync()` / `flush()`:**
   Neither `RegionFile.write()` nor `ThreadedFileIOBase` invokes `FileDescriptor.sync()` or `RandomAccessFile.getChannel().force()`. Writes are committed only to the OS page cache. If the host machine suffers a kernel panic or sudden power loss, dirty OS cache pages may be lost or written out of order.
2. **In-Place Mutation Vulnerability:**
   When rewriting a chunk that fits within its existing sector allocation, the payload is overwritten in-place. If the JVM terminates mid-write, the sector contains partially old and partially new data, resulting in `ZipException: incorrect data check` on next boot.
3. **Sector Relocation Safety:**
   When a chunk grows and relocates to a new sector offset, the payload is written to the new sectors *before* the Location Table in Sector 0 is updated. If a crash occurs during payload writing, the old location table entry still points to the old sector, safely preserving the prior chunk state.
4. **Header Vulnerability:**
   Because Sector 0 and Sector 1 reside at the very beginning of the file, any corrupt sector write or truncated file operation that touches bytes 0..8191 can invalidate or orphan all 1,024 chunks in that region.

## 6. Crash Semantics: Memory Queue vs In-Flight Disk Mutation

| Storage Phase | Crash Mechanism | Data State on Restart | Recovery Outcome |
| :--- | :--- | :--- | :--- |
| **Phase A: In `chunksToSave` Queue** | Process killed (SIGKILL / OutOfMemory) while chunk NBT is buffered in JVM memory | Region file sectors and Location Table are completely untouched. | **Clean Rollback:** Chunk safely rolls back to previous successful save. No corruption. |
| **Phase B1: In-Place Sector Write** | Crash occurs while `File IO Thread` writes `dataFile.write(buf)` into same sectors ($S_\text{req} \le S_\text{cur}$) | Sector contains torn bytes (partially old zlib stream, partially new zlib stream). | **Corruption & Regeneration:** Next boot hits `ZipException` or `EOFException`. `AnvilChunkLoader` catches error, drops chunk, and regenerates. |
| **Phase B2: Relocated Sector Write** | Crash occurs while writing payload to newly allocated sectors ($S_\text{req} > S_\text{cur}$) *before* `setOffset()` | New sectors contain incomplete data; Sector 0 Location Table STILL points to old sectors. | **Clean Rollback:** Location table still references old sectors. Leaks orphaned sectors in file, but old chunk data survives intact! |
| **Phase B3: Header Update Commit** | Crash occurs while seeking to Sector 0 (`this.setOffset`) | Sector 0 may suffer a partial 4-byte write. | **Header Corruption:** Location offset points to invalid file coordinates, or whole region file header becomes corrupted. |
