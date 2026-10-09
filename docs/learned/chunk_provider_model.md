# ChunkProviderServer & Chunk Lifecycle Architecture

## 1. Subsystem Overview
`ChunkProviderServer` is the authoritative orchestrator of chunk lifecycle on the dedicated server. It resides in package `net.minecraft.world.gen` and implements `IChunkProvider`.
In Forge 14.23.5.2860, vanilla's rudimentary chunk loading and storage have been heavily patched to introduce high-performance collections, asynchronous disk loading (`ChunkIOExecutor`), forced chunk ticket integration (`ForgeChunkManager`), and dormant memory caching.

## 2. Authoritative Data Structures
In vanilla 1.12.2, loaded chunks were stored in a custom `LongHashMap<Chunk>`. Forge patches this to use fastutil's `Long2ObjectMap`:

```java
public final Long2ObjectMap<Chunk> id2ChunkMap = new Long2ObjectOpenHashMap<Chunk>(8192);
public final Set<Long> droppedChunksSet = Sets.<Long>newHashSet();
public final IChunkLoader chunkLoader; // AnvilChunkLoader instance
public final IChunkGenerator chunkGenerator; // e.g. ChunkGeneratorOverworld
public final WorldServer world;
```

### Key Properties:
- **Key Encoding:** Chunk coordinates $(X, Z)$ are packed into a single 64-bit primitive `long` via `ChunkPos.asLong(int x, int z)`:
  $$\text{packed} = ((long)x \ \& \ \text{0xFFFFFFFFL}) \ | \ (((long)z \ \& \ \text{0xFFFFFFFFL}) \ll 32)$$
- **Thread Affinity:** `id2ChunkMap` is **NOT THREAD-SAFE** (`Long2ObjectOpenHashMap`). Concurrent access represents **UNDEFINED / UNSUPPORTED CONCURRENT ACCESS**. The `Server thread` is the sole authoritative reader and mutator. Off-thread access violates the engine's single-writer concurrency model.
- **Dropped Set:** `droppedChunksSet` holds chunk coordinate hashes queued for unloading. Populated when players move away; drained during `tick()`.

## 3. Chunk Request Flow (`provideChunk` vs `loadChunk`)
There are two primary entry points for obtaining a chunk:

### A. `provideChunk(int x, int z)`
Guarantees a chunk is returned. If absent from memory, it attempts to load from disk; if missing from disk, it generates a brand new chunk:
1. Calls `loadChunk(x, z)`. If found, returns immediately.
2. If absent, enters generation block:
   - Allocates key `ChunkPos.asLong(x, z)`.
   - Invokes `chunkGenerator.generateChunk(x, z)`.
   - Inserts into `id2ChunkMap.put(key, chunk)`.
   - Calls `chunk.onLoad()`.
   - Calls `chunk.populate(this, this.chunkGenerator)`.
   - Returns generated chunk.

### B. `loadChunk(int x, int z)` / `loadChunk(int x, int z, Runnable runnable)`
Loads a chunk if it exists, but does **not** generate new terrain if missing:
1. Checks `id2ChunkMap.get(key)`. If present, cancels any pending unload (`droppedChunksSet.remove(key)`) and returns.
2. Checks Forge's `ForgeChunkManager.fetchDormantChunk(key, world)`. If dormant chunk exists in memory, restores entities and returns without touching disk.
3. Checks `ChunkIOExecutor`: if async loading is enabled, enqueues request and returns null (or executes synchronous callback if `runnable` provided).
4. Synchronously calls `loadChunkFromFile(x, z)`:
   - Invokes `chunkLoader.loadChunk(world, x, z)`.
   - If loaded, updates `chunk.setLastSaveTime(world.getTotalWorldTime())`.
   - Calls `chunkGenerator.recreateStructures(chunk, x, z)`.
   - Inserts into `id2ChunkMap`.
   - Calls `chunk.onLoad()`.
   - Calls `chunk.populate(this, this.chunkGenerator)`.

## 4. Steady-State Tick Loop (`ChunkProviderServer.tick`)
Invoked once per server tick from `WorldServer.tick()`:
```java
public boolean tick() {
    if (!this.world.disableLevelSaving) {
        // 1. Process dropped chunks (up to 100 per tick)
        if (!this.droppedChunksSet.isEmpty()) {
            for (Long o : this.droppedChunksSet) {
                Chunk chunk = this.id2ChunkMap.get(o);
                if (chunk != null && chunk.unloadQueued) {
                    chunk.onUnload();
                    this.saveChunkData(chunk);
                    this.saveChunkExtraData(chunk);
                    this.id2ChunkMap.remove(o);
                }
            }
            this.droppedChunksSet.clear();
        }
        // 2. Poll chunk loader for write status
        this.chunkLoader.chunkTick();
    }
    return false;
}
```

### Unload Invariants:
1. **Batch Limit:** Vanilla and Forge process unload candidates with a bounded loop (in vanilla, 100 candidates per tick).
2. **Forced Ticket Protection:** Chunks holding active `ForgeChunkManager` tickets or located within spawn chunk coordinates ($25 \times 25$ grid around world spawn) are never retained in `droppedChunksSet`.
3. **Resurrection Safety:** If a player moves back into an unload-queued chunk before `tick()` drains `droppedChunksSet`, `loadChunk(x, z)` removes the coordinate from `droppedChunksSet` and clears `chunk.unloadQueued = false`, avoiding redundant I/O churn.
