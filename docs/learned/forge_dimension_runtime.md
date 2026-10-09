# Learned: Forge DimensionManager & Multi-World Architecture

## 1. Overview & System Purpose
Vanilla Minecraft hardcodes three dimensions: Overworld (0), Nether (-1), and The End (1). Forge's `DimensionManager` (`net.minecraftforge.common.DimensionManager`) generalizes dimension registration and lifecycle management, allowing mods to dynamically register and load hundreds of custom dimensions (e.g. Twilight Forest, Deep Dark, Mining World, Compact Machines, Galacticraft planets).

---

## 2. Core Mechanics & Architecture

### 2.1 Dimension Types & Providers
- `DimensionType`: Represents a category of dimension (e.g. `OVERWORLD`, `NETHER`, `THE_END`). Associated with a `WorldProvider` class.
- `WorldProvider`: Subclass defining environment rules: light levels, biome providers, sky renderers, whether beds explode, whether players can respawn.
- `registerDimension(int id, DimensionType type)`: Maps an arbitrary integer dimension ID to a `DimensionType`.

### 2.2 Numeric Dimension ID Rules
- Dimension IDs are signed 32-bit integers (`int`).
- Standard vanilla IDs:
  - `0`: Overworld
  - `-1`: Nether
  - `1`: The End
- Modded dimension IDs can be arbitrary positive or negative values (e.g. `-2`, `7`, `100`, `144`).
- **Dynamic Allocation**: Mods like RFTools Dimensions and Mystcraft dynamically allocate new integer dimension IDs at runtime as players create pocket dimensions.

### 2.3 World Lifecycle & Unload Queues
- Forge manages loaded `WorldServer` instances in `Hashtable<Integer, WorldServer> worlds`.
- Dimensions are loaded on-demand when an entity teleports or a chunk ticket is requested (`initDimension(int dim)`).
- **Unload Queue**: Forge maintains an unload queue (`unloadQueue` BitSet). When a dimension has zero loaded player chunks and no persistent chunkloaders (Forge Chunk Manager tickets), it is added to the unload queue and cleanly unloaded during server tick to reclaim RAM.
- Dimension 0 (Overworld) is **never unloaded**.

---

## 3. Teleportation & Entity Transfer
Transferring an entity between dimensions requires:
1. Moving the entity off its current world server entity list.
2. Invoking `EntityTravelToDimensionEvent`.
3. Finding or generating a destination portal via `ITeleporter`.
4. Spawning the entity into the destination `WorldServer`.
5. Firing `PlayerChangedDimensionEvent`.

---

## 4. Invariants for Native / Rust Hybrid Architecture
1. **Never Assume Dimension IDs are Bounded or Contiguous**:
   - Rust world management must support arbitrary sparse signed integer IDs (`-2147483648..2147483647`).
2. **Synchronize Dimension Lifecycle with Java**:
   - Creating or unloading a dimension must remain synchronized with Forge's `DimensionManager.initDimension()` and `unloadWorld()` hooks.
