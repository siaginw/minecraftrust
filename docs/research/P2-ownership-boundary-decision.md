# P2: Chunk Generation Ownership Boundary Decision

**Date**: 2026-09-21  
**Scope**: Milestone M3W5 — Ownership boundary selection for native chunk generation.

---

## 1. Options Evaluated

### Option A: Base Terrain Only (Density -> Trilinear Interpolation -> Stone/Water/Air -> ChunkPrimer)
- **Scope**: Rust takes 825-double density field (or generates it via M3W4 kernel), evaluates 4x4x32 cell trilinear interpolation across 16x16x256 voxel grid, writes `STONE` (16), `WATER` (144), or `AIR` (0) into `[u16; 65536]` ChunkPrimer buffer.
- **Mod Compatibility**: 100% universal.
  - Zero Forge patches in `setBlocksInChunk` (`func_185976_a`).
  - Zero Forge events fired.
  - Zero mod hooks exist in clean Forge or modpacks (SevTech, Revelation).
  - Pure deterministic math (0 RNG calls).
- **Risk**: None.

### Option B: Base Terrain + Biome Surface Replacement
- **Scope**: Option A + `replaceBiomeBlocks` (`func_185977_a`) for vanilla biomes.
- **Mod Compatibility**: High modpack risk.
  - Forge fires `ChunkGeneratorEvent.ReplaceBiomeBlocks` (mods can cancel or replace).
  - Mods override `Biome.genTerrainBlocks` (Biomes O' Plenty, Twilight Forest, Traverse).
  - Bedrock generation consumes 256 `rand.nextInt(5)` calls per column.
- **Risk**: Mod incompatibilities on modpacks without complex fallback trampolines.

### Option C: Full Chunk Generation Including Carvers/Structures
- **Scope**: Options A + B + Caves, Ravines, Mineshafts, Strongholds.
- **Mod Compatibility**: Incompatible with modpacks.
  - `InitMapGenEvent` actively hooks carvers in Quark, YUNG's, Worley's Caves.
- **Risk**: Unacceptable.

---

## 2. Decision: Option A Primary + Clean Forge Option B Study

1. **Immediate Production Boundary**: **Option A**.
   - Universal across clean Forge and all modpack targets.
   - Replaces 100% of the heavy 3D floating-point trilinear interpolation loop in Java.
   - Zero-copy native write directly to ChunkPrimer memory.
2. **Clean Forge Target A**: Explore Option B in shadow mode for vanilla biomes with instant Java fallback.
