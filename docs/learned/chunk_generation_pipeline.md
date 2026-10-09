# Chunk Generation Pipeline & Noise Synthesis

## 1. Pipeline Overview
When a requested chunk does not exist on disk, `ChunkProviderServer.provideChunk(x, z)` invokes the dimension's registered `IChunkGenerator` (`world.provider.createChunkGenerator()`). In the Overworld (dimension 0), this is `ChunkGeneratorOverworld`.

The generation pipeline consists of distinct, sequential stages:

```
[ Chunk Coordinates (x, z) ]
              │
              ▼
1. Biome Generation (BiomeProvider.getBiomes)
   - Sample Voronoi zoom layers and climate noise
   - Populate Biome[] array (16x16)
              │
              ▼
2. Base Terrain Noise (setBlocksInChunk)
   - Compute 3D Perlin noise field (5x5x33 grid points)
   - Trilinear interpolation across 16x16x256 voxel cells
   - Fill ChunkPrimer with Stone, Water (y < 63), or Air
              │
              ▼
3. Surface Replacement (replaceBiomeBlocks)
   - Query Biome.topBlock and Biome.fillerBlock (e.g. Grass, Dirt, Sand)
   - Bedrock generation at y=0 (randomized 0..4)
              │
              ▼
4. Carver Generation (MapGenCaves & MapGenRavines)
   - Spherical/ellipsoidal noise carving into ChunkPrimer
   - Ocean/water containment checks
              │
              ▼
5. Structure Voxel Layout (MapGenStructure)
   - Villages, Strongholds, Mineshafts, Temples, Mansions
   - Reserve bounding boxes and write component blocks
              │
              ▼
6. Chunk Instantiation
   - Wrap ChunkPrimer in new Chunk(world, primer, x, z)
   - Assign Biomes byte[] array and compute initial HeightMap
```

## 2. Low-Level Mechanics

### A. 3D Terrain Density Calculation
Minecraft calculates block density $D(x, y, z)$ over a coarse $5 \times 5 \times 33$ grid:
- $X$ and $Z$ step by 4 blocks ($4 \times 4 = 16$).
- $Y$ step by 8 blocks ($8 \times 32 = 256$).

Density formula at each grid node:
$$D(x, y, z) = \text{noise}(x, y, z) - \text{biomeHeightOffset}(y) - \text{falloff}(y)$$

Voxel states in `ChunkPrimer`:
- If interpolated $D(x, y, z) > 0$: Block is `Blocks.STONE`.
- If $D(x, y, z) \le 0$ and $y < 63$: Block is `Blocks.WATER` (sea level).
- If $D(x, y, z) \le 0$ and $y \ge 63$: Block is `Blocks.AIR`.

### B. Carvers (`MapGenCaves` & `MapGenRavines`)
Carvers iterate over a $16 \times 16$ chunk radius using a deterministic seed:
$$\text{carverSeed} = \text{worldSeed} \oplus ((long)cx \cdot 341873128712L + (long)cz \cdot 132897987541L)$$
Caves are carved by tracing recursive worm vectors, clearing solid stone and replacing with air (or lava at $y < 11$).

### C. Structure Mapping
Structure generators (`MapGenStructure`) maintain persistent component maps. During `generateChunk()`, they only place structural blocks that fall strictly within the current chunk boundary $(x, z)$. Multi-chunk structures register bounding boxes into a world-level database (`MapGenStructureData`), deferring multi-chunk assembly until population.
