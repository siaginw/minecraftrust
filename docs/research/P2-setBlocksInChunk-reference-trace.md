# P2: setBlocksInChunk Reference Trace — Density → Base Terrain Boundary

**Date**: 2026-09-21  
**Scope**: Exact disassembly trace of `ChunkGeneratorOverworld.func_185977_a` (setBlocksInChunk) and `Biome.func_180628_b` — the complete base terrain generation pipeline before MapGen/population.

---

## Call Chain

```
ChunkGeneratorOverworld.func_185932_a(chunkX, chunkZ)
  → new ChunkPrimer()
  → func_185976_a (terrain shape - stone/air from density)
  → BiomeProvider.getBiomesForGeneration (16x16 biome array)
  → func_185977_a(chunkX, chunkZ, primer, biomes[])  ← PRE-POP ORACLE CAPTURE POINT (RETURN)
  → MapGenBase.func_186125_a (caves, ravines)
  → MapGenMineshaft/Village/Stronghold/ScatteredFeature/Monument/Mansion (structures)
  → new Chunk(...)
```

**Oracle capture point**: `func_185977_a` **RETURN** — after all biomes have run surface replacement, before any structure generation.

---

## func_185977_a (setBlocksInChunk) — Full Disassembly

```java
public void func_185977_a(int chunkX, int chunkZ, ChunkPrimer primer, Biome[] biomes) {
    double noiseScale = 0.03125;                    // 1/32
    double[] perlinNoise = field_186002_u;          // double[256] - decorator noise

    // 1. Generate Perlin decorator noise for 16x16 column grid
    // NoiseGeneratorPerlin.func_151599_a(double[], x, z, 16, 16, 0.0625, 0.0625, 1.0)
    // Input: chunkX*16, chunkZ*16 (block coordinates)
    // Scale: 0.0625 = 1/16 (per-block)
    // Output: 256 doubles in field_186002_u
    perlinNoise = noiseGenPerlin.func_151599_a(
        perlinNoise,
        chunkX * 16.0, chunkZ * 16.0,
        16, 16,
        0.0625, 0.0625, 1.0
    );

    // 2. Per-column biome surface replacement
    for (int x = 0; x < 16; x++) {
        for (int z = 0; z < 16; z++) {
            Biome biome = biomes[z * 16 + x];       // column-major: z*16 + x
            double noiseVal = perlinNoise[z * 16 + x];
            
            biome.func_180622_a(world, random, primer,
                chunkX * 16 + x, chunkZ * 16 + z, noiseVal);
        }
    }
}
```

**Key fields**:
- `field_186002_u` — `double[256]` decorator noise cache (per-column)
- `field_185994_m` — `NoiseGeneratorPerlin` instance (decorator)
- `field_185995_n` — `World` reference
- `field_185990_i` — `Random` (setSeed per-chunk in func_185932_a)

---

## Biome.func_180622_a → func_180628_b (final)

```java
public void func_180622_a(World world, Random rand, ChunkPrimer primer, 
                          int blockX, int blockZ, double noiseVal) {
    func_180628_b(world, rand, primer, blockX, blockZ, noiseVal);
}
```

### func_180628_b — Surface Replacement Algorithm

**Inputs**:
- `world` — for sea level (`world.func_181545_F()` → typically 63)
- `rand` — per-chunk Random (seeded from chunk coords)
- `primer` — `ChunkPrimer` with `char[65536]` block storage
- `blockX, blockZ` — absolute block coordinates
- `noiseVal` — decorator noise for this column (from `field_186002_u`)

**Biome state fields** (per-biome constants):
- `field_76752_A` — `topBlock` (IBlockState) — e.g., grass_block
- `field_76753_B` — `fillerBlock` (IBlockState) — e.g., dirt
- `field_185366_b` — `underwaterTop` (IBlockState) — gravel
- `field_185365_a` — `underwaterFiller` (IBlockState) — sand
- `field_185368_d` — `shallowWaterTop` (IBlockState) — gravel
- `field_185371_g` — `lavaBlock` (IBlockState)
- `field_185372_h` — `waterBlock` (IBlockState)
- `field_185369_e` — `redSandTop` (IBlockState)
- `field_185370_f` — `redSandFiller` (IBlockState)
- `field_185367_c` — `bedrockBlock` (IBlockState)

**Algorithm** (y = 255 down to 0):

```java
int seaLevel = world.func_181545_F();        // typically 63
IBlockState topBlock = field_76752_A;
IBlockState fillerBlock = field_76753_B;
int surfaceDepthCounter = -1;

// surfaceHeight = floor(noiseVal/3 + 3 + rand.nextDouble()*0.25)
int surfaceHeight = (int)(noiseVal / 3.0 + 3.0 + rand.nextDouble() * 0.25);

int localX = blockX & 15;   // chunk-local x
int localZ = blockZ & 15;   // chunk-local z

for (int y = 255; y >= 0; y--) {
    // 1. BEDROCK (y <= 5): 1/5 chance per column
    if (y <= 5 && rand.nextInt(5) == 0) {
        primer.func_177855_a(localX, y, localZ, field_185367_c); // bedrock
        continue;
    }

    IBlockState current = primer.func_177856_a(localX, y, localZ);
    
    // 2. AIR (Material.AIR): track first non-air below
    if (current.getMaterial() == Material.AIR) {
        surfaceDepthCounter = -1;
        continue;
    }
    
    // 3. STONE (Blocks.STONE): surface replacement logic
    if (current.getBlock() == Blocks.STONE) {
        if (surfaceDepthCounter == -1) {  // First stone encountered (top of stone)
            if (surfaceHeight <= 0) {
                topBlock = field_185366_b;     // underwaterTop (gravel)
                fillerBlock = field_185365_a;  // underwaterFiller (sand)
            } else if (y >= seaLevel - 4 && y <= seaLevel + 1) {
                // Shoreline band: use biome defaults
                topBlock = field_76752_A;
                fillerBlock = field_76753_B;
            } else {
                topBlock = field_76752_A;
                fillerBlock = field_76753_B;
            }
        }
        
        if (y < seaLevel) {  // Below sea level
            // Temperature check for lava vs water
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, y, blockZ);
            float temp = func_180626_a(pos);  // biome temperature at position
            if (temp < 0.15f) {
                topBlock = field_185371_g;  // lava
            } else {
                topBlock = field_185372_h;  // water
            }
        } else if (y >= seaLevel - 1 && y <= seaLevel + surfaceHeight) {
            // Surface layer: topBlock for surfaceHeight+1 layers
            surfaceDepthCounter = surfaceHeight;
        } else if (y > seaLevel + surfaceHeight) {
            // Filler layer
        }
        
        // Apply block
        if (y >= seaLevel - 1 && surfaceDepthCounter > 0) {
            primer.func_177855_a(localX, y, localZ, topBlock);
            surfaceDepthCounter--;
        } else if (y >= seaLevel - 1) {
            primer.func_177855_a(localX, y, localZ, fillerBlock);
        } else if (y >= seaLevel - 7 - surfaceHeight) {
            // Deep underwater: underwaterTop/filler
            primer.func_177855_a(localX, y, localZ, field_185368_d); // shallowWaterTop (gravel)
        } else {
            primer.func_177855_a(localX, y, localZ, fillerBlock);
        }
        
        // SAND FALLBACK: if filler was sand, randomize red sand variant
        if (surfaceDepthCounter == 0 && fillerBlock.getBlock() == Blocks.SAND) {
            if (surfaceHeight > 1 && rand.nextInt(4) == 0) {
                surfaceDepthCounter = Math.max(y - 63, 0) + rand.nextInt(4);
                if (fillerBlock.getValue(BlockSand.VARIANT) == EnumType.RED_SAND) {
                    fillerBlock = field_185369_e; // redSandTop
                } else {
                    fillerBlock = field_185370_f; // redSandFiller
                }
            }
        }
    }
}
```

---

## ChunkPrimer Storage Format

```java
// field_177860_a: char[65536]  // 16*16*256 = 65536
// Index = (x << 12) | (z << 8) | y = x*4096 + z*256 + y

// Get block state
public IBlockState func_177856_a(int x, int y, int z) {
    int idx = (x << 12) | (z << 8) | y;
    int blockId = field_177860_a[idx];  // char → unsigned 16-bit
    return Block.field_176229_d.getByValue(blockId);  // ObjectIntIdentityMap
}

// Set block state
public void func_177855_a(int x, int y, int z, IBlockState state) {
    int idx = (x << 12) | (z << 8) | y;
    int blockId = Block.field_176229_d.getId(state);  // ObjectIntIdentityMap
    field_177860_a[idx] = (char)blockId;
}
```

**Critical**: `Block.field_176229_d` is a global `ObjectIntIdentityMap` assigning sequential IDs to block states at startup. The mapping is **world-session stable** but **not cross-session stable** unless the same mod set loads in same order.

---

## Forge/Mod Hook Points

### 1. Biome Event (post-surface-replacement)
`BiomeEvent.GenerateBiome` — fired in `ChunkGeneratorOverworld.func_185932_a` **after** `func_185977_a` but **before** MapGen.

### 2. Decorator/Generator Events
- `DecorateBiomeEvent.Decorate` — per-decorator (trees, flowers, ores)
- `PopulateChunkEvent.Populate` — after all decorators
- `OreGenEvent` — ore generation

### 3. Structure Generation
All `MapGenBase.func_186125_a` calls are **post-oracle** — they modify the primer after our capture point.

### 4. ChunkGeneratorOverworld Extension Points
- `func_185976_a` (terrain shape) — can be overridden
- `func_185977_a` (setBlocksInChunk) — can be overridden
- `func_185932_a` (full chunk gen) — can be overridden

---

## Pre-Pop Oracle Boundary Summary

| Phase | Method | Modifies Primer? | Captured by Oracle? |
|-------|--------|------------------|---------------------|
| Density → Stone/Air | `func_185976_a` | Yes | Yes (input to setBlocksInChunk) |
| Perlin Decorator Noise | `NoiseGeneratorPerlin.func_151599_a` | No (fills double[]) | N/A |
| Surface Replacement | `func_185977_a` → `Biome.func_180628_b` | **Yes** | **YES — at RETURN** |
| Caves/Ravines | `MapGenCaves/Ravines.func_186125_a` | Yes | No |
| Structures | `MapGen*.func_186125_a` | Yes | No |
| Decorators | `DecorateBiomeEvent` | Yes | No |
| Population | `PopulateChunkEvent` | Yes | No |

---

## Exact Oracle Capture Semantics

The coremod hook at `func_185977_a RETURN` captures:
1. **ChunkPrimer** with all 256 columns' surface replacement applied
2. **Biome array** (16x16) used for this chunk
3. **Decorator noise array** (`field_186002_u` — 256 doubles)
4. **Chunk coordinates** (chunkX, chunkZ)
5. **World seed** (via Random state)

This is the **complete base terrain** before any cave carving, structure placement, or decoration. Identical oracle output = bit-exact base terrain parity.

---

## Next Steps (P2 Continuation)

1. **External Research** — Survey Oxide, Cuberite, rust-chunk crates for:
   - ChunkPrimer-equivalent storage layouts
   - Surface replacement algorithms
   - Biome-to-block-state mapping strategies

2. **Offline Rust Parity** — Implement `setBlocksInChunk` in Rust:
   - Input: density field (already Rust-owned, bit-exact)
   - Perlin decorator noise (port `NoiseGeneratorPerlin`)
   - Biome surface replacement (port `Biome.func_180628_b`)
   - Output: `ChunkPrimer` char[65536] — compare against Java oracle

3. **Forge Hook Strategy** — Decide coremod vs. native event bus for post-oracle stages