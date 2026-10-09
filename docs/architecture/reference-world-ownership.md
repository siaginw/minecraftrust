# Reference World & Dimension Ownership Architecture

## 1. World & Dimension Lifecycle Ownership
In Minecraft 1.12.2 with Forge 14.23.5.2860:
- **`MinecraftServer`**: Sole authoritative coordinator of server execution. Ticks all active dimensions sequentially within a single primary tick loop (`MinecraftServer.tickChildren()`).
- **`DimensionManager` (`net.minecraftforge.common.DimensionManager`)**: Central authority for dimension registration and instance tracking.
  - Maintains `Int2ObjectMap<WorldServer> worlds` (synchronized `Int2ObjectLinkedOpenHashMap`).
  - Manages dimension types (`DimensionType.OVERWORLD`, `NETHER`, `THE_END`, and modded dimension types).
  - Handles dynamic dimension loading (`DimensionManager.initDimension(int dim)`). Dim 0 creates `WorldServer`; non-zero dimensions instantiate `WorldServerMulti`, sharing the root save directory and scoreboard.
- **`WorldServer`**: Authoritative container for world state within a dimension:
  - Owns `ChunkProviderServer` (chunk lifecycle, generation, loading, cache).
  - Owns `PlayerChunkMap` (player tracking, chunk watch lists, network packet dispatch).
  - Owns `Map<BlockPos, TileEntity>` loaded tile entities and ticking lists.
  - Owns `List<Entity>` and `ClassInheritanceMultiMap<Entity>`.
  - Owns `WorldBorder`, `Scoreboard`, and weather simulation.

## 2. Thread Invariants & Concurrency Constraints
- **Primary Server Thread**:
  - In reference Minecraft and Forge, the authoritative world mutation path (`setBlockState`), tile entity updates (`update()`), entity movement, player interactions, redstone, and chunk generation hooks execute on the main Server thread.
  - The reference implementation assumes sequential single-threaded execution for `Chunk.storageArrays` and `World.loadedTileEntityList`, though asynchronous mods and coremods have historically violated these assumptions with unsynchronized off-thread reads or worldgen tasks.
- **Asynchronous IO & Netty**:
  - Chunk saving and loading (`AnvilChunkLoader`) run asynchronously via `IAsyncChunkSaver` / `ThreadedFileIOBase`.
  - Netty event loops read pre-assembled packets or send direct buffers off the server thread.
- **Cross-Dimension Interaction**:
  - Teleportation or cross-dimension capability reads require synchronization or main-thread scheduling (`addScheduledTask`).

## 3. Migration Seam Implication
Moving world storage or coordinate tracking to Rust must preserve the sequential single-threaded execution model of the server tick or enforce strict per-chunk reader-writer isolation to prevent data races with Forge mod reflection.
