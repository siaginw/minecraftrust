# Asynchronous Chunk Loading Architecture (Forge ChunkIO)

## 1. Pipeline Overview
In vanilla Minecraft 1.12.2, chunk loading is strictly synchronous on the `Server thread`. When a player moves into an unloaded chunk, the server halts all ticking while `RegionFile` reads 4KB sectors, decompresses zlib, and builds NBT objects.
Forge 14.23.5.2860 integrates the `net.minecraftforge.common.chunkio` subsystem to offload disk I/O, zlib decompression, and block array construction to background worker threads.

```
                  [ Player Moves / Ticket Requested ]
                                   │
                                   ▼
                 ChunkProviderServer.loadChunk(x, z)
                                   │
                 Is async loading active on this world?
                         ┌─────────┴─────────┐
                        Yes                  No
                         │                   │
                         ▼                   ▼
            ChunkIOExecutor.queueChunkLoad  Synchronous loadChunkFromFile()
                         │
                         ▼
        ThreadPool: ChunkIOThreadPoolExecutor (Worker)
         - RegionFileCache.getChunkInputStream()
         - CompressedStreamTools.read(stream)
         - AnvilChunkLoader.readChunkFromNBT()
           * Sections, Blocks, Data, Add, Biomes, ForgeCaps
           * NO Entity / TileEntity construction!
                         │
                         ▼
            Place into ChunkIOExecutor sync queue
                         │
                         ▼
          MinecraftServer.tick() -> ChunkIOExecutor.tick()
          (Server Thread - Synchronous Callback Drain)
           - AnvilChunkLoader.loadEntities()
           - MinecraftForge.EVENT_BUS.post(ChunkDataEvent.Load)
           - id2ChunkMap.put(pos, chunk)
           - chunk.onLoad()
           - chunk.populate(provider, generator)
           - Run registered callbacks (e.g. PlayerChunkMap)
```

## 2. Key Components
1. **`ChunkIOExecutor`:**
   - Static orchestrator managing active and pending async load tasks.
   - Drained every tick from `MinecraftServer.tick()` (Phase 3 of steady-state pipeline).
   - If a main-thread subsystem requests a chunk synchronously while it is loading asynchronously, `ChunkIOExecutor.syncChunkLoad()` promotes the task, blocking until the worker finishes rather than loading a duplicate instance.
2. **`QueuedChunk`:**
   - Hash key wrapper storing `(x, z, world)`.
   - Used in queues and deduplication maps.
3. **`ChunkIOThreadPoolExecutor`:**
   - Custom `ThreadPoolExecutor` using daemon threads named `Chunk I/O Executor Thread-#`.
   - Thread priority: `Thread.NORM_PRIORITY - 1` (priority 4) to prevent starving the Server thread.
4. **`ChunkIOProvider`:**
   - Implements `IThreadedFileIOProvider` callback.
   - `run()`: Executes off-thread on worker. Returns `Object[]{chunk, nbt}`.
   - `syncCallback()`: Executes on `Server thread`. Hydrates entities, fires Forge events, and inserts chunk into authoritative map.

## 3. Concurrency Boundaries & Thread Safety Invariants
1. **No Off-Thread World Mutation:**
   Background workers in `ChunkIOThreadPoolExecutor` **never** invoke `world.spawnEntityInWorld()`, `world.setBlockState()`, or `world.addTileEntity()`.
2. **Deferred Entity Hydration:**
   Entity and TileEntity constructors frequently invoke mod hooks, register tick listeners, or inspect neighboring blocks. Therefore, `AnvilChunkLoader.readChunkFromNBT()` skips entities entirely during `loadChunk__Async`. Entities are only constructed in `syncCallback()` on the `Server thread`.
3. **Event Bus Boundary:**
   `ChunkDataEvent.Load` and `ChunkEvent.Load` are **strictly forbidden** from firing off-thread. Forge modders universally assume event listeners execute on the `Server thread`.
4. **Deduplication:**
   If multiple players simultaneously step into range of chunk $(X, Z)$, only one `QueuedChunk` worker task is submitted. Additional requests register callbacks on the existing future.

## 4. Off-Thread Classification: Reference Engine vs General Mod Surface
A critical distinction must be maintained between internal reference implementation execution and public API contracts:

1. **REFERENCE IMPLEMENTATION OFF-THREAD:**
   The reference Forge server selectively isolates specific, self-contained sub-tasks to run off-thread on background worker threads (`ChunkIOThreadPoolExecutor`):
   - Seeking and reading raw bytes from `RegionFile`.
   - Zlib inflation (`InflaterInputStream`).
   - Parsing raw NBT compounds into memory.
   - Constructing isolated `Chunk` and `ExtendedBlockStorage` block arrays that are **not yet published** to the world.
   These operations are safe *only* because the reference loader maintains strict single-worker ownership and completely defers all external world interaction.

2. **GENERAL PUBLIC THREAD-SAFETY GUARANTEE (UNPROVEN / REJECTED):**
   This isolated worker pipeline does **NOT** grant a general thread-safety guarantee for chunk or world APIs. Minecraft's classes (`Chunk`, `World`, `TileEntity`, `Block`) remain inherently non-thread-safe. Arbitrary mods, coremods, or external threads cannot safely invoke `getChunk()`, `setBlockState()`, or query capabilities from background threads.
