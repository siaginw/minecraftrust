# Learned: Chunk In-Memory Structure and Lifecycle

## 1. Primary Fields of `net.minecraft.world.chunk.Chunk`
In Minecraft 1.12.2 / Forge 14.23.5.2860, each chunk instance maintains:
- `storageArrays`: `ExtendedBlockStorage[16]` (indexed by `y >> 4`).
- `blockBiomeArray`: `byte[256]` (unsigned biome ID, index: `(z & 15) << 4 | (x & 15)`).
- `heightMap`: `int[256]` (highest light-blocking Y + 1 per column).
- `precipitationHeightMap`: `int[256]` (highest motion-blocking Y + 1 per column, sentinel `-999` indicates dirty).
- `updateSkylightColumns`: `boolean[256]`.
- `tileEntities`: `Map<BlockPos, TileEntity>` (`HashMap`).
- `entityLists`: `ClassInheritanceMultiMap<Entity>[16]` (entities partitioned by 16-block vertical section).
- `isTerrainPopulated`: `boolean` (worldgen structure generation completed).
- `isLightPopulated`: `boolean` (skylight/blocklight propagation completed).
- `dirty`: `boolean` (set whenever blocks, lighting, or metadata change; triggers save to region file).
- `inhabitedTime`: `long` (ticks players have occupied this chunk; scales local difficulty).

## 2. Chunk Heap Footprint Breakdown (Compressed OOPs)
Measured on 64-bit HotSpot JVM:
- **Base Chunk Object Overhead**: ~4,150 bytes (~4.1 KB)
  - Primitive fields + array references: ~160 bytes
  - `storageArrays` reference array (16 refs): 80 bytes
  - `blockBiomeArray` (byte[256]): 272 bytes
  - `heightMap` (int[256]): 1,040 bytes
  - `precipitationHeightMap` (int[256]): 1,040 bytes
  - `updateSkylightColumns` (boolean[256]): 272 bytes
  - `entityLists` (16 sub-lists): ~1,280 bytes
- **Per-Section Overhead (`ExtendedBlockStorage`)**:
  - 4-bit palette + Overworld Skylight: **6,424 bytes (~6.3 KB)**
    - EBS object header: 40 bytes
    - `BlockStateContainer` + 4-bit `BitArray` (256 longs): 2,208 bytes
    - `blockLight` (`NibbleArray`, byte[2048]): 2,088 bytes
    - `skyLight` (`NibbleArray`, byte[2048]): 2,088 bytes
  - 13-bit Global Palette (Nether/Modded): **10,952 bytes (~10.7 KB)**
- **Aggregate Loaded Chunk Footprint**:
  - Empty Chunk (0 sections): **~4.1 KB**
  - Surface Chunk (4 populated sections): **~29.7 KB**
  - Typical Loaded Chunk (8 populated sections): **~55.3 KB**
  - Fully Dense Chunk (16 sections, Overworld): **~106.5 KB**
  - Full Chunk (16 sections, Global Palette): **~180.1 KB**

## 3. Lifecycle States
1. **Unloaded**: Exists solely on disk in Anvil `.mca` region file as compressed NBT.
2. **Loading**: Async IO reads NBT, decompresses zlib, parses compound tags in `AnvilChunkLoader`.
3. **Active / Ticking**: Placed in `ChunkProviderServer.id2ChunkMap`. Queried by game loop, redstone, entity movement.
4. **Saving**: Marked dirty -> `AnvilChunkLoader.writeChunkToNBT` writes to Anvil save queue.
5. **Unloading**: Removed from active chunk map, unloaded if no players or chunk loaders hold tickets.
