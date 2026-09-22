# Compatibility Hazards: CoreMods and Block Storage Transformations

## 1. Major Transformer Hazards
### Hazard 1: FoamFix (asiekierka)
- **Transformation Target**: `net.minecraft.world.chunk.BlockStateContainer`.
- **Mechanism**: FoamFix replaces the dynamic `BitArray` and palette hierarchy with custom flyweight containers (`BlockStateContainerDeduplicated`) to reduce memory footprint across millions of blocks.
- **Risk to Rust Migration**:
  - If Rust owns block storage inside JVM memory via JNI reflection or field patching, FoamFix ASM transforms may fail to apply or crash on field injection.
  - Mitigation: If Rust replaces chunk storage, FoamFix is redundant and should be disabled or intercepted cleanly.

### Hazard 2: Cubic Chunks (Barteks2x)
- **Transformation Target**: Completely replaces `Chunk` and `WorldServer` 256-block vertical storage with infinite vertical cubic chunks (`ICubeProvider`, `Cube`).
- **Risk to Rust Migration**:
  - Incompatible with vanilla 16-section assumptions.
  - Any 1.12.2 hybrid runtime must explicitly declare whether it supports vanilla 256-height chunks or Cubic Chunks. Current target: Standard Forge 14.23.5.x 256-height storage.

### Hazard 3: Phosphor (jellysquid3)
- **Transformation Target**: `ExtendedBlockStorage.blockLight` and `skyLight` (`NibbleArray`), lighting propagation algorithms.
- **Risk to Rust Migration**:
  - Phosphor rewires lighting checks triggered during `setBlockState`.
  - Rust block mutations must trigger light updates through standard Forge/Phosphor entry points.

### Hazard 4: TileEntity Desynchronization
- Direct memory modification of block state without calling `TileEntity.updateContainingBlockInfo()` or `world.removeTileEntity()` causes phantom tile entities and crash loops in mod energy grids (IC2, Thermal Expansion).
