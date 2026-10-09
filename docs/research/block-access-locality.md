# Research: Block Access Locality and Cache Performance

## 1. Spatial Locality in Minecraft Gameplay
Block accesses on the server thread exhibit intense spatial and temporal clustering:
- **Redstone & Automation**: Repeated queries to neighboring coordinates `(x±1, y±1, z±1)`.
- **Entity Collision & Navigation**: Querying 2x1x2 to 3x2x3 bounding boxes around moving entities every tick.
- **Crop / Plant Growth**: Random ticks select 3 blocks per section, accessing local light and biome attributes.
- **Chunk Generation & Population**: Highly localized sequential iteration across entire columns and sections.

## 2. Java Heap Memory Layout vs Cache Locality
- In Java HotSpot:
  - `storageArrays[16]` is an array of object references.
  - Each `ExtendedBlockStorage` is a separate heap allocation.
  - `BlockStateContainer` is an inner object pointing to a `BitArray` containing `long[]`.
  - Reading a block state incurs multiple pointer indirections (`chunk -> storageArrays -> EBS -> data -> storage -> longArray`).
  - Cache misses dominate when iterating across section boundaries or scanning large volumes.

## 3. Flat Native Memory Optimization (Rust Design)
- Rust representation:
  - A contiguous array of 4,096 `u16` state IDs per section (8,192 bytes = 8 KB).
  - 8 KB fits entirely within a modern CPU L1 data cache (typically 32 KB–48 KB per core).
  - An entire 16-section chunk of raw block state IDs requires `16 * 8 KB = 128 KB`, fitting comfortably inside the L2 cache (typically 512 KB–1 MB per core).
- Cache Locality Advantage: Sequential iteration over native block memory yields zero pointer dereferences and hardware stream prefetching.
