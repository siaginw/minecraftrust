# Chunk Unload Pipeline & Memory Reclamation

## 1. Overview
Chunk unloading reclaims memory by evicting chunks that no longer have player watchers, active tickets, or spawn chunk requirements.
In Forge 14.23.5.2860, unloading is governed by `ChunkProviderServer`, `PlayerChunkMap`, and `ForgeChunkManager`.

```
[ Player moves away / Ticket released ]
                   │
                   ▼
  PlayerChunkMap.entry.removePlayer()
                   │
  Does chunk have any remaining watchers?
            ┌──────┴──────┐
           Yes            No
            │             │
            ▼             ▼
       Keep loaded   PlayerChunkMap calls ChunkProviderServer.queueUnload(chunk)
                          │
                          ▼
             chunk.unloadQueued = true
             droppedChunksSet.add(pos.asLong())
                          │
                          ▼
            ChunkProviderServer.tick() (Server Thread)
             - Limit: drains up to 100 chunks per tick
             - Checks: is chunk still unloadQueued?
             - Checks: is chunk in world.getPersistentChunks()?
                          │
                          ▼
             Chunk Eviction Sequence:
             1. chunk.onUnload()
                - Unregister tile entities from world
                - Unregister entities from world
                - Post ChunkEvent.Unload to Forge event bus
             2. saveChunkData(chunk) -> AnvilChunkLoader
             3. saveChunkExtraData(chunk)
             4. id2ChunkMap.remove(pos.asLong())
             5. Place into ForgeChunkManager.dormantChunkCache
```

## 2. Low-Level Mechanics

### A. The 100-Chunk Unload Rate Limiter
In `ChunkProviderServer.tick()`:
Vanilla processes unload candidates by iterating `droppedChunksSet`. To avoid severe tick stalls when multiple players teleport across dimensions (which suddenly abandons hundreds of chunks), vanilla and Forge process candidates in batches (up to 100 candidates per tick).

### B. Prevention of Accidental Unload
Before unlinking the chunk, `ChunkProviderServer` verifies:
1. `chunk.unloadQueued == true`: If a player returned to the chunk between enqueue and drain, `loadChunk(x, z)` cleared this flag, aborting unload.
2. `world.getPersistentChunks().containsKey(pos)`: If a mod acquired a `ForgeChunkManager` ticket on this chunk, it is immediately skipped and stripped from `droppedChunksSet`.
3. Spawn chunk protection: Coordinates falling within the $25 \times 25$ spawn grid around `world.getSpawnPoint()` are never queued for unload.

### C. Forge Dormant Chunk Cache
Vanilla completely discards the `Chunk` instance on unload, forcing a full disk read, zlib decompression, and NBT reconstruction if the player walks back across the chunk boundary.
Forge introduces `ForgeChunkManager.dormantChunkCache`:
- Guava `Cache<Long, ChunkEntry>` with configurable size (`dormantChunkCacheSize`, default 0, modpacks typically 64-512).
- When a chunk unloads:
  - Entities and TileEntities are saved to NBT and cleared from the chunk instance.
  - The chunk's `ExtendedBlockStorage[]` block arrays, heightmaps, and biome arrays remain allocated in JVM heap memory.
- When re-requested via `fetchDormantChunk()`:
  - The in-memory block storage is reused directly.
  - Only entities and tile entities are hydrated from NBT.
  - Bypasses disk read, zlib inflation, and multi-megabyte array allocations.
