# DataFixer Pipeline & Schema Versioning in Minecraft 1.12.2

## 1. Architecture Overview
Minecraft 1.12.2 uses a forward-migrating schema pipeline managed by `net.minecraft.util.datafix.DataFixer`.
Its purpose is to update legacy NBT schemas (from Minecraft 1.8 through 1.11) into 1.12.2 layout upon loading.

- **Baseline 1.12.2 DataVersion:** Strictly **`1343`** (written into `level.dat`, player `.dat`, and chunk headers).
- **Core Interface:** `IDataFixer`:
  ```java
  public NBTTagCompound process(IFixType type, NBTTagCompound compound) {
      int version = compound.hasKey("DataVersion", 99) ? compound.getInteger("DataVersion") : -1;
      return version >= 1343 ? compound : this.process(type, compound, version);
  }
  ```

---

## 2. Fast-Path Optimization

If `DataVersion >= 1343`, `DataFixer` performs **zero processing, zero traversals, and zero allocations**, returning the passed compound reference immediately.

### Pipeline Execution Order (When `DataVersion < 1343`):
1. **Fix Resolution (`processFixes`):** Iterates registered `IFixableData` components where `fix.getFixVersion() > currentVersion`. Transforms legacy tag names (e.g. converting numeric block IDs to resource locations, renaming entity IDs).
2. **Walker Traversal (`processWalkers`):** Recursively walks child compounds:
   - Walks through `TileEntities` in chunks
   - Walks through `Entities` and nested `Passengers`
   - Walks through `Items` and `Inventory` lists
   - Migrates item tags recursively

---

## 3. Invocation Seams in Vanilla & Forge

| Location | Fix Type | Invocations | Purpose |
| :--- | :--- | :--- | :--- |
| `AnvilChunkLoader.loadChunk__Async` | `FixTypes.CHUNK` | Background Chunk I/O | Migrates chunk sections, entities, tile entities |
| `AnvilSaveHandler.loadWorldInfo` | `FixTypes.LEVEL` | Server Boot / World Init | Migrates `level.dat` gamerules, generator settings |
| `SaveHandler.readPlayerData` | `FixTypes.PLAYER` | Player Connection | Migrates player inventory, Ender chest, abilities |
| `TemplateManager.readTemplate` | `FixTypes.STRUCTURE` | Worldgen / Structure Block | Migrates `.nbt` structure templates |

---

## 4. Rust Integration & Migration Boundary

1. **Native Worlds (Version $\ge 1343$):**
   - For all worlds created or previously saved in 1.12.2, `DataVersion == 1343`.
   - Rust chunk loaders can safely bypass the Java `DataFixer` pipeline completely when `DataVersion >= 1343`.
2. **Legacy Import (Version $< 1343$):**
   - If an imported world has `DataVersion < 1343`, the compound must be passed to the Java `DataFixer` for one-time migration before Rust takes authoritative ownership.
