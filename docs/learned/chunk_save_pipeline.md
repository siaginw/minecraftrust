# Chunk Save Pipeline & Asynchronous Disk Flushing

## 1. Overview
Chunk persistence in Minecraft 1.12.2 is a two-stage decoupled pipeline:
1. **Synchronous NBT Snapshotting (Server Thread):** Java world structures (`Chunk`, `Entity`, `TileEntity`, `NextTickListEntry`) are serialized into memory-resident `NBTTagCompound` structures.
2. **Asynchronous Sector Writing (File IO Thread):** A background daemon thread drains pending NBT compounds, applies zlib compression, and commits sectors to `.mca` region files.

```
                    [ Chunk Save Initiated ]
            (Autosave tick % 900 == 0 / Chunk Unload / /save-all)
                               │
                               ▼
            ChunkProviderServer.saveChunks(boolean all)
                               │
       Iterate loadedChunks.values() -> chunk.needsSaving()
                               │
                               ▼
            AnvilChunkLoader.saveChunk(world, chunk)
             (Executes on Server Thread - Synchronous)
             - writeChunkToNBT(): Blocks, Entities, TileEntities
             - ForgeChunkManager.storeChunkNBT()
             - MinecraftForge.EVENT_BUS.post(ChunkDataEvent.Save)
             - addChunkToPending(pos, nbt)
               * chunksToSave.put(pos, nbt)
             - ThreadedFileIOBase.queueIO(this)
                               │
                 Server thread resumes execution
                               │
                               ▼
               ThreadedFileIOBase (File IO Thread)
             - Priority: Thread.MIN_PRIORITY (1)
             - Drains AnvilChunkLoader.writeNextIO()
             - RegionFileCache.getChunkOutputStream(pos.x, pos.z)
             - CompressedStreamTools.write(nbt, stream)
               * zlib compression (level 6)
               * RegionFile.write(): sector allocation & header update
             - Sleep cadence: 10 ms between chunks when idle
```

## 2. Low-Level Mechanics

### A. The Synchronous Snapshot
In `AnvilChunkLoader.java`:
```java
public void saveChunk(World worldIn, Chunk chunkIn) throws MinecraftException, IOException {
    worldIn.checkSessionLock();
    NBTTagCompound root = new NBTTagCompound();
    NBTTagCompound level = new NBTTagCompound();
    root.setTag("Level", level);
    root.setInteger("DataVersion", 1343);
    
    ForgeChunkManager.storeChunkNBT(chunkIn, level);
    this.writeChunkToNBT(chunkIn, worldIn, level);
    MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunkIn, root));
    
    this.addChunkToPending(chunkIn.getPos(), root);
}
```
**Critical Thread Safety Invariant:**
Because `writeChunkToNBT()` traverses active entity lists (`chunk.getEntityLists()`) and the tile entity map (`chunk.getTileEntityMap().values()`), it **must** execute on the `Server thread`. If entity lists were read concurrently while entities moved or despawned, `ConcurrentModificationException` would crash the server.

### B. Pending Queue (`chunksToSave`)
Pending writes are stored in:
```java
private final Map<ChunkPos, NBTTagCompound> chunksToSave = Maps.<ChunkPos, NBTTagCompound>newConcurrentMap();
```
- A `ConcurrentHashMap` keyed by `ChunkPos`.
- Deduplication: If a chunk is marked dirty and saved multiple times before `ThreadedFileIOBase` reaches it, subsequent saves overwrite the NBT in `chunksToSave`, ensuring each chunk is compressed and written at most once per flush cycle.

### C. `ThreadedFileIOBase` Worker
Runs as a singleton background thread named `"File IO Thread"`:
- Thread priority: `1` (`Thread.MIN_PRIORITY`).
- While `isThreadWaiting == false`: Sleeps 10 ms after writing each chunk, and 25 ms when the queue is empty.
- When `waitForFinish()` is called (e.g. during server shutdown or `/save-all flush`): `isThreadWaiting` is set to `true`, eliminating the 10 ms inter-chunk sleep and allowing the thread to drain the write queue at maximum disk throughput.
