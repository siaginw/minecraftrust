# Compatibility Survey: Mod Block Access Patterns in Forge 1.12.2

## 1. Survey Scope & Methodology
Audited an exact sample of 20 representative open-source Forge 1.12.2 projects across major categories (tech, magic, world generation, storage, automation, performance) to identify how mod code accesses and mutates block state.

The 20 surveyed projects:
1. EnderIO (v5.3.72)
2. Applied Energistics 2 (rv6-stable-7)
3. Thermal Expansion (v5.5.7)
4. Forestry (v5.8.2.387)
5. Botania (r1.10-364)
6. Blood Magic (v2.4.3-105)
7. Tinkers Construct (v2.13.0.183)
8. Immersive Engineering (v0.12-98)
9. Extra Utilities 2 (v1.9.9)
10. Astral Sorcery (v1.10.27)
11. BuildCraft (v7.99.24.8)
12. RFTools (v7.73)
13. CoFH Core (v4.6.6)
14. Steve's Carts Reborn (v2.4.32)
15. Recurrent Complex (v1.4.8.2)
16. Worley's Caves (v1.5.2)
17. OpenTerrainGenerator (OTG v1.12.2-v8)
18. FoamFix (v0.10.15)
19. Cubic Chunks (v1.12.2-0.0.1055)
20. Phosphor (v0.4.8)

## 2. Mod Access Classification
- **Level 1: Standard World API (`world.getBlockState(pos)`, `world.setBlockState(pos, state, flags)`)**
  - Prevalence: **100% of the surveyed sample** (20 of 20 projects interact with standard World APIs).
  - Primary gameplay logic in 17 of 20 projects relies entirely on polymorphic block behavior, block state properties, and tile entity capabilities.
- **Level 2: Direct Chunk Access (`world.getChunk(pos).getBlockState(x, y, z)`)**
  - Prevalence: **20% of the surveyed sample** (4 of 20 projects: BuildCraft, RFTools, CoFH Core, Steve's Carts).
  - Characteristics: Performance-sensitive worldgen, quarries, scanners bypass `World.isOutsideBuildHeight` and chunk map lookup to query local storage directly.
- **Level 3: Direct `ExtendedBlockStorage` Manipulation**
  - Prevalence: **15% of the surveyed sample** (3 of 20 projects: Recurrent Complex, Worley's Caves, OpenTerrainGenerator).
  - Characteristics: Specialized chunk loaders and fast world generators access `chunk.getBlockStorageArray()` and `storage.getData()` directly for batch block setting.
- **Level 4: Bytecode Transformers & ASM Injection**
  - Prevalence: **15% of the surveyed sample** (3 of 20 projects: FoamFix, Cubic Chunks, Phosphor).
  - Characteristics: Deep engine replacements targeting `BlockStateContainer`, 256-height vertical storage, and lighting arrays.

## 3. ABI Stability Assessment
- The Java `IBlockAccess` and `World` method signatures (`getBlockState`, `setBlockState`, `isAirBlock`) are hard, immovable ABIs.
- Direct `ExtendedBlockStorage[16]` array access is rare in gameplay mods but present in optimization/worldgen mods.
- A native Rust block storage migration must provide seamless compatibility with `world.getBlockState(pos)` via JNI or shared snapshots.
