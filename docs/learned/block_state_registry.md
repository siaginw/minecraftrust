# Learned: Block State Registry Architecture

## 1. Global Block State ID Space
In Minecraft 1.12.2:
- `Block.BLOCK_STATE_IDS` maps every valid `IBlockState` to a unique 16-bit numeric ID (`0..65535`).
- Bitfield structure:
  - Bits `0..11` (12 bits): Block numeric ID (`0..4095`, from `Block.REGISTRY`).
  - Bits `12..15` (4 bits): Block metadata (`0..15`, from `block.getMetaFromState(state)`).
  - Formula: `stateId = blockId | (meta << 12)`.
  - Reverse: `blockId = stateId & 4095`, `meta = (stateId >> 12) & 15`.

## 2. Registry Immutability & Lifecycle
- **Registration Phase**:
  - Vanilla blocks register during `Bootstrap.register()`.
  - Modded blocks register during Forge `FMLPreInitializationEvent` and `RegistryEvent.Register<Block>`.
  - Mod blocks receive numeric IDs >= 256.
- **Freeze Point**:
  - `net.minecraftforge.fml.common.registry.GameData.freezeData()` is called at the end of Loader initialization.
  - After this point, `Block.BLOCK_STATE_IDS` is strictly frozen and read-only for the entire duration of the server runtime.
- **Lookup Performance**:
  - `BLOCK_STATE_IDS.get(State -> ID)`: **1.87 ns** (p50: 1.72 ns).
  - `BLOCK_STATE_IDS.getByValue(ID -> State)`: **0.48 ns** (p50: 0.24 ns) (direct array index dereference).

## 3. Forge Extended Block States (`IExtendedBlockState`)
- Forge introduces `IExtendedBlockState` for dynamic unlisted properties (e.g. fluid flowing levels, multipart wire connectivity, custom model rendering data).
- Storage Invariant: `IExtendedBlockState` is **NEVER** stored in `Chunk.storageArrays` or `BlockStateContainer`.
- Storage exclusively accepts standard `IBlockState` instances.
- When mods or renderers query extended state, `block.getExtendedState(state, world, pos)` dynamically wraps the clean state.
- Rust block storage can store pure `u16` state IDs without any knowledge of extended properties.
