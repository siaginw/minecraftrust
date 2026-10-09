# Reference Chunk Ownership & Concurrency Boundary Architecture

## 1. Authoritative Hierarchy
To prevent race conditions, stale reads, and split-brain states, the chunk subsystem defines strict authority tiers:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Master State Owner: ChunkProviderServer (Server thread)  │
│    - Authoritative loaded set: id2ChunkMap                 │
│    - Unload queue: droppedChunksSet                         │
│    - Only thread permitted to add, tick, or remove chunks  │
└──────────────────────────────┬──────────────────────────────┘
                               │ Dispatches coarse requests
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Background Load Workers: ChunkIOThreadPoolExecutor       │
│    - Owns temporary off-thread Chunk instances during parse  │
│    - Owns direct sector byte buffers and zlib decompression │
│    - Handed off atomically to Server thread via sync queue  │
└──────────────────────────────┬──────────────────────────────┘
                               │ Read-only queries
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Storage Layer: RegionFileCache & RegionFile Handles       │
│    - Thread-safe via instance monitor locks                 │
│    - Shared between ChunkIO workers (reads) and File IO (w) │
└──────────────────────────────▲──────────────────────────────┘
                               │ Writes coarse batches
                               │
┌─────────────────────────────────────────────────────────────┐
│ 4. Background Persistence Worker: ThreadedFileIOBase        │
│    - Owns chunksToSave queue and serialization drain        │
│    - Owns sector allocation updates on RegionFile           │
└─────────────────────────────────────────────────────────────┘
```

## 2. Thread Access Rules
1. **Server Thread Exclusivity:**
   - Adding or removing chunks from `id2ChunkMap`.
   - Mutating block voxels, block metadata, light levels, tile entities, and entities.
   - Firing all Forge chunk events (`ChunkEvent`, `ChunkDataEvent`, `PopulateChunkEvent`).
2. **Worker Thread Isolation:**
   - Background threads operate only on isolated, unlinked `Chunk` instances that have not yet been published to `id2ChunkMap`.
   - Never query or mutate the enclosing `World` or neighboring chunks.
3. **Immutability Handoff:**
   - During save: The `Server thread` performs a synchronous NBT serialization snapshot into `chunksToSave`. Once queued, the NBT structure is completely immutable and owned by `ThreadedFileIOBase`.
   - During load: The worker thread reads and constructs block arrays and heightmaps into an unlinked `Chunk` instance. Once parsed, ownership transfers to `ChunkIOExecutor`'s sync queue, and finally to `ChunkProviderServer` on the `Server thread`.
