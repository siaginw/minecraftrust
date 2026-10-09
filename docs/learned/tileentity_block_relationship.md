# Learned: TileEntity and Block State Relationship & Forge Hooks

## 1. Vanilla vs. Forge TileEntity Association
- **Vanilla Rule**:
  - `block instanceof ITileEntityProvider`
  - Blocks implementing `ITileEntityProvider` implement `createNewTileEntity(World, int meta)` to spawn a `TileEntity`.
- **Forge Extension**:
  - Forge completely supersedes `ITileEntityProvider` via bytecode patches on `Block.java`:
    - `public boolean hasTileEntity(IBlockState state)`
    - `public TileEntity createTileEntity(World world, IBlockState state)`
  - Crucial distinction: `hasTileEntity` is a function of the **`IBlockState`**, not just the block class!
  - Mod blocks frequently have tile entities for specific metadata variants while other variants are plain blocks.

## 2. Chunk Lifecycle & Binding
- In `Chunk.setBlockState`:
  1. If old block had a tile entity and new block is different, `oldBlock.breakBlock(world, pos, oldState)` is invoked, and `world.removeTileEntity(pos)` removes the instance.
  2. If new block has a tile entity (`block.hasTileEntity(newState)`), `Chunk.setBlockState` checks if an instance already exists at `pos`. If not, `world.setTileEntity(pos, block.createTileEntity(world, newState))` binds the new TE.
- Measured Creation Overhead:
  - Plain block set: **~7–8 ns**.
  - Basic TileEntity instantiation & map insertion (microbenchmark): **198.9 ns** (p50: 100 ns, p95: 300 ns).
    - Note: This metric strictly isolates bare object allocation (`new TileEntityChest()`), setting `BlockPos`, and inserting into a `Map<BlockPos, TileEntity>`.
    - **It explicitly excludes**: Forge capability attachment/dispatching, mod constructor initialization complexity, world binding (`setWorld`), lifecycle hooks (`onLoad`), event bus notifications (`AttachCapabilitiesEvent`), block/neighbor physics callbacks (`onBlockAdded`, `neighborChanged`), and network synchronization. Real full modded TileEntity placement lifecycle cost is significantly higher.

## 3. Implication for Native Rust Block Storage
Rust block storage can authoritatively store raw block IDs and metadata, but whenever a block mutation occurs that alters a block with `hasTileEntity(state) == true`, the Java server-thread bridge must be notified to invoke `createTileEntity` and update `World.loadedTileEntityList`.
Bypassing Java tile entity creation would break 100% of Forge tech/storage machines.
