# M4.1 Task 6 — Mutation Paths Audit
**Date:** 2026-09-21
**Status:** Complete — All mutation paths identified and invalidation hooks verified

---

## 1. Chunk Block State Mutation (Primary Path)

### `Chunk.setBlockState(BlockPos, IBlockState)` — Chunk.java:372
**Trigger:** Any block placement/break by player, piston, water flow, fire spread, explosion, etc.
**Flow:**
1. Computes local coords: `x & 15`, `y`, `z & 15`
2. Checks/updates precipitationHeightMap
3. Gets old state via `getBlockState()`
4. If old == new: returns null (no-op)
5. Gets/creates `ExtendedBlockStorage` for section `y >> 4`
6. Calls `storage.set(x, y&15, z, newState)` → `BlockStateContainer.set()` → palette resize possible
7. If block changed type: handles `breakBlock`, `onBlockAdded`, TileEntity create/remove
8. If light opacity changed: calls `relightBlock()` / `propagateSkylightOcclusion()`
9. Marks chunk `dirty = true`

**Invalidation requirement:** ANY call that changes block state must invalidate native cache.

**Current hook:** `NativeChunkBridge.invalidateChunk(cx, cz)` — called by coremod? **NOT YET HOOKED**

---

## 2. ExtendedBlockStorage.set() — ExtendedBlockStorage.java:30
**Trigger:** Called by `Chunk.setBlockState()`, also by world-gen population
**Flow:**
1. Gets old state at position
2. Updates `blockRefCount` / `tickRefCount` for old/new blocks
3. Calls `data.set(index, newState)` → `BlockStateContainer.set()`
4. Palette may resize (Linear→HashMap→Global)

**Invalidation requirement:** Same as above — covered by Chunk.setBlockState invalidation.

---

## 3. BlockStateContainer.set() — BlockStateContainer.java:60
**Trigger:** All block state writes
**Flow:**
1. Gets palette ID via `palette.idFor(state)` — may trigger `onResize()`
2. Sets entry in `BitArray` at index
3. Palette resize copies all 4096 entries to new storage

**Invalidation requirement:** Palette resize changes wire format — invalidates cached wire payload.

---

## 4. Chunk Population / Structure Generation

### `Chunk.populate(IChunkProvider, IChunkGenerator)` — Chunk.java:711
**Trigger:** First time chunk is generated (after base terrain)
**Flow:**
1. Checks neighbor chunks loaded
2. Calls `IChunkGenerator.populate(this.x, this.z)` 
3. Generator places: trees, ores, flowers, dungeons, strongholds, mineshafts, villages, temples, etc.
4. Each placement calls `Chunk.setBlockState()` → triggers invalidation

**Invalidation requirement:** Full chunk repopulation → invalidate entire chunk native state.

**Current hook:** WorldgenShadow.shadowTerrain() registers native chunk AFTER base terrain (func_185976_a return), BEFORE populate. Post-population mutations NOT invalidated.

---

### `Chunk.setTerrainPopulated(boolean)` — Chunk.java:1019
**Trigger:** After `populate()` completes
**Action:** Sets `isTerrainPopulated = true`; marks `dirty = true`

---

## 5. Chunk Storage Array Replacement

### `Chunk.setStorageArrays(ExtendedBlockStorage[])` — Chunk.java:825
**Trigger:** `AnvilChunkLoader.loadChunk()` / `ChunkSerializer.readChunk()` — chunk load from disk
**Flow:**
1. Validates array length == 16
2. `System.arraycopy(newArrays, 0, this.storageArrays, 0, 16)`
3. Replaces ALL sections atomically

**Invalidation requirement:** Complete chunk state replacement → MUST invalidate and re-register.

**Current hook:** None. Anvil load path not intercepted.

---

## 6. TileEntity Operations

### `Chunk.addTileEntity(BlockPos, TileEntity)` — Chunk.java:594
### `Chunk.removeTileEntity(BlockPos)` — Chunk.java:607
**Trigger:** Block state change to/from ITileEntityProvider (chests, furnaces, signs, etc.)
**Flow:**
- add: validates block is ITileEntityProvider, puts in tileEntities map, calls `world.addTileEntity()`
- remove: removes from map, calls `tileEntity.invalidate()`

**Invalidation requirement:** TileEntity state not in NativeChunk (only block states). Block change already covered by setBlockState. TileEntity NBT handled separately in persistence.

---

## 7. Light Updates

### `Chunk.setLightFor(EnumSkyBlock, BlockPos, int)` — Chunk.java:474
### `Chunk.relightBlock(int, int, int)` — Chunk.java:245
### `Chunk.generateSkylightMap()` — Chunk.java:137
**Trigger:** Block placement/break affecting light, chunk load, time change
**Flow:** Modifies `ExtendedBlockStorage.blockLight` / `skyLight` NibbleArrays

**Invalidation requirement:** Light arrays are part of SPacketChunkData payload. Must invalidate for correct wire encode.

**Current status:** NativeSection stores `block_light[2048]` and `sky_light[2048]` — updated only at registration. Light changes NOT propagated.

---

## 8. Chunk Load / Unload

### `Chunk.onLoad()` / `Chunk.onUnload()` — Chunk.java:616, 625
**Trigger:** Chunk enters/leaves loaded radius
**Flow:**
- onLoad: adds TileEntities to world, loads entities
- onUnload: marks TileEntities for removal, unloads entities

**Invalidation requirement:** 
- onLoad: Should re-register if chunk was previously unloaded (new generation)
- onUnload: Should call `NativeChunkBridge.unloadChunk()`

**Current hook:** None for onLoad. `unloadChunk` exists but not called from coremod.

---

## 9. Anvil Chunk Save / Load

### `AnvilChunkLoader.loadChunk()` / `saveChunk()`
**Trigger:** Persistent storage IO
**Flow:**
- load: Reads NBT → ChunkPrimer → `Chunk(World, ChunkPrimer, ...)` constructor → `setStorageArrays()`
- save: `Chunk.getDataForNBT()` → writes to region file

**Invalidation requirement:** 
- Load: Full state replacement → must re-register with new generation_id
- Save: Uses `stagePersistence()` consumer — reads native state if available

---

## 10. Command / Mod Block Mutations

### `/setblock`, `/fill`, `/clone`, structure blocks, mod APIs
**Trigger:** Direct world.setBlockState() calls
**Flow:** Eventually calls `Chunk.setBlockState()` → same invalidation path

**Current hook:** None — relies on Chunk.setBlockState interception.

---

## 11. Entity Block Interactions

### Explosions, pistons, enderman pickup, silverfish, rabbit eating crops
**Trigger:** Entity AI / physics
**Flow:** Calls `world.setBlockState()` → `Chunk.setBlockState()`

---

## Summary: Invalidation Coverage Matrix

| Mutation Path | Entry Point | Currently Invalidated? | Hook Location |
|---------------|-------------|------------------------|---------------|
| Player block place/break | `Chunk.setBlockState` | **NO** | Need coremod hook |
| Piston push/retract | `Chunk.setBlockState` | **NO** | Same |
| Water/lava flow | `Chunk.setBlockState` | **NO** | Same |
| Fire spread | `Chunk.setBlockState` | **NO** | Same |
| Explosion | `Chunk.setBlockState` | **NO** | Same |
| Structure generation | `Chunk.populate` → `setBlockState` | **NO** | Post-populate hook needed |
| Chunk load (Anvil) | `Chunk.setStorageArrays` | **NO** | Anvil load hook needed |
| Light updates | `Chunk.setLightFor` / `relightBlock` | **NO** | Light hook needed |
| TileEntity add/remove | `Chunk.add/removeTileEntity` | Partial (block change covered) | N/A |
| Chunk unload | `Chunk.onUnload` | **NO** | Need coremod hook |
| Command/mod mutations | `World.setBlockState` → `Chunk.setBlockState` | **NO** | Same as player |

---

## Required Coremod Hooks for M4.1 Completion

### 1. `Chunk.setBlockState` interception
```java
// At start of method, after no-op check:
if (NativeChunkBridge.isAvailable()) {
    NativeChunkBridge.invalidate(chunk.x, chunk.z);
}
```

### 2. `Chunk.setStorageArrays` interception (Anvil load)
```java
// After System.arraycopy:
if (NativeChunkBridge.isAvailable()) {
    // Full re-register with new generation
    // Need access to new storage arrays + biomes
}
```

### 3. `Chunk.onUnload` interception
```java
if (NativeChunkBridge.isAvailable()) {
    NativeChunkBridge.unload(this.x, this.z);
}
```

### 4. Light update interception
```java
// In setLightFor / relightBlock after modification:
if (NativeChunkBridge.isAvailable()) {
    NativeChunkBridge.invalidate(this.x, this.z); // light is part of payload
}
```

### 5. Post-population re-registration
```java
// In Chunk.populate() after generator.populate() returns:
if (NativeChunkBridge.isAvailable() && NativeChunkBridge.isRegistered(this.x, this.z)) {
    // Re-register with updated primer
    NativeChunkBridge.invalidate(this.x, this.z);
    // Re-read primer and re-register
}
```

---

## Generation Handle Semantics

**Current design:** `ChunkRegistry` uses `generation_id` (64-bit) + `ChunkKey(cx, cz)` for stale pointer prevention.

**On invalidation:** Chunk marked `ChunkLifecycle.Invalidated` — subsequent `get(handle)` returns `None`.

**On re-registration (Anvil load / post-populate):** New `generation_id` issued via `registry.next_generation_id()`. Old handle becomes stale automatically.

**Coordination needed:** Coremod must call `invalidateChunk()` before any mutation, then re-register if needed. The `generation_id` ensures no stale reads from packet encoding thread.

---

## M1 Packet Path Interaction

### SPacketChunkDataTransformer (M1.4)
- Hooks `SPacketChunkData.<init>(Chunk, int)` constructor
- Calls `NativeChunkPacket.populatePacket(packet, chunk, filter)`
- If native available and eligible: uses native payload, returns early
- Fallback: full Java construction

**Conflict with M4:** M4 NativeChunk state is authoritative for terrain. M1 transformer reads from Chunk storage. If M4 invalidation not triggered, M1 may read stale Chunk data while native has fresh data.

**Resolution:** M1 transformer should also check native registry and prefer native state when available, OR ensure Chunk storage is kept in sync (write-through from native on mutation — not implemented).

**Recommended for M4.1:** M1 transformer queries native registry first. If registered and not invalidated, use native encode path. Else fall back to Java extraction.

---

## Next Steps (M4.1 Tasks 7-12)

1. **Task 7:** Versioned snapshot model — add `mutation_generation` vs `snapshot_generation` counters
2. **Task 8:** Refresh design — coarse section/chunk rebuild, measure transfer size/latency/frequency
3. **Task 9:** Modpack SHADOW — Revelation then SevTech validation (build coremod with hooks above)
4. **Task 10:** Memory model recalculation with generalized state
5. **Task 11:** Second consumer validation against generalized sections (occupancy + persistence)
6. **Task 12:** Complete M4.1 report (Sections A-W)