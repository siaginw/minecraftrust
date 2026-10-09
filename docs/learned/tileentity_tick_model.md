# Minecraft 1.12.2 / Forge TileEntity Tick Model

## 1. Overview & Architectural Role
TileEntities (Block Entities) represent persistent block-associated state and complex computational logic (chests, furnaces, hoppers, mob spawners, modded machinery).

In vanilla Minecraft 1.12.2 and Forge 14.23.5.2860, TileEntity ticking occurs synchronously on `Server thread` immediately after general entity updates within `World.updateEntities()`. In idle baseline profiling, TileEntity ticking consumes **1.04%** of tick time (dominated by `minecraft:chest` and `minecraft:mob_spawner`). In modpacks, TileEntity ticking frequently scales into the dominant CPU bottleneck.

---

## 2. Master Collections & State Flags (`World.java`)

`World` manages four interdependent collections and a reentrancy guard:

```java
public final List<TileEntity> loadedTileEntityList = Lists.newArrayList();
public final List<TileEntity> tickableTileEntities = Lists.newArrayList();
private final List<TileEntity> addedTileEntityList = Lists.newArrayList();
private final List<TileEntity> tileEntitiesToBeRemoved = Lists.newArrayList();
private boolean processingLoadedTiles; // REENTRANCY GUARD FLAG
```

### 2.1 Collection Responsibilities
1. **`loadedTileEntityList`:** Authoritative registry of ALL loaded TileEntities in the dimension (both tickable and non-tickable). Used for world queries (`getTileEntity(pos)`).
2. **`tickableTileEntities`:** Specialized subset containing ONLY instances implementing `net.minecraft.util.ITickable`.
3. **`addedTileEntityList`:** Staging buffer for TileEntities created while the tick loop is actively executing.
4. **`tileEntitiesToBeRemoved`:** Staging buffer for TileEntities queued for unregistration / chunk unload.
5. **`processingLoadedTiles`:** Boolean barrier active during the execution of `tickableTileEntities`. Prevents concurrent modification of master lists while mod tile entities run their `update()` methods.

---

## 3. The Execution Loop (`World.updateEntities()`)

```java
this.processingLoadedTiles = true; // LOCK ACTIVE ITERATION

// 1. Drain pending removal queue
if (!this.tileEntitiesToBeRemoved.isEmpty()) {
    for (TileEntity tile : this.tileEntitiesToBeRemoved) {
        tile.onChunkUnload();
    }
    this.tickableTileEntities.removeAll(this.tileEntitiesToBeRemoved);
    this.loadedTileEntityList.removeAll(this.tileEntitiesToBeRemoved);
    this.tileEntitiesToBeRemoved.clear();
}

// 2. Iterate active tickable TileEntities
Iterator<TileEntity> iterator = this.tickableTileEntities.iterator();
while (iterator.hasNext()) {
    TileEntity tileentity = iterator.next();
    
    // Check 1: Invalidation prior to tick
    if (!tileentity.isInvalid() && tileentity.hasWorld()) {
        BlockPos pos = tileentity.getPos();
        // Check 2: Chunk loaded and world border validation
        if (this.isBlockLoaded(pos) && this.worldBorder.contains(pos)) {
            try {
                this.profiler.func_194340_a(() -> String.valueOf(TileEntity.getKey(tileentity.getClass())));
                ((ITickable)tileentity).update(); // EXECUTE TILE LOGIC
                this.profiler.endSection();
            } catch (Throwable throwable) {
                CrashReport crash = CrashReport.makeCrashReport(throwable, "Ticking block entity");
                CrashReportCategory cat = crash.makeCategory("Block entity being ticked");
                tileentity.addInfoToCrashReport(cat);
                throw new ReportedException(crash);
            }
        }
    }

    // Check 3: Invalidation during tick
    if (tileentity.isInvalid()) {
        iterator.remove();
        this.loadedTileEntityList.remove(tileentity);
        if (this.isBlockLoaded(tileentity.getPos())) {
            Chunk chunk = this.getChunk(tileentity.getPos());
            chunk.removeTileEntity(tileentity.getPos());
        }
    }
}

this.processingLoadedTiles = false; // RELEASE ITERATION LOCK

// 3. Drain pending addition queue
if (!this.addedTileEntityList.isEmpty()) {
    for (int i = 0; i < this.addedTileEntityList.size(); ++i) {
        TileEntity tile = this.addedTileEntityList.get(i);
        if (!tile.isInvalid()) {
            if (!this.loadedTileEntityList.contains(tile)) {
                this.addTileEntity(tile);
            }
            if (this.isBlockLoaded(tile.getPos())) {
                Chunk chunk = this.getChunk(tile.getPos());
                IBlockState state = chunk.getBlockState(tile.getPos());
                chunk.addTileEntity(tile.getPos(), tile);
                this.notifyBlockUpdate(tile.getPos(), state, state, 3);
            }
        }
    }
    this.addedTileEntityList.clear();
}
```

---

## 4. Forge-Specific Lifecycle & Safety Enhancements

In Forge 14.23.5.2860, several critical patches were introduced to fix vanilla bugs and support complex modded machinery (`World.java.patch`):

### 4.1 Early World Binding (`setWorld`)
Vanilla fails to attach the world instance to dynamically spawned TileEntities until the following tick. Forge patches `World.addTileEntity`:
```java
// Forge: set the world early as vanilla doesn't set it until next tick
tileEntity.setWorld(this);
```
*Why this matters:* Modded machines often query capabilities, fluids, or energy from neighboring blocks immediately upon placement. Without early world binding, calling `getCapability()` during initialization crashes with `NullPointerException`.

### 4.2 Invalidation Deferral
Vanilla called `tile.invalidate()` immediately when a block was broken mid-tick. If that tile was currently executing or queued in the iteration list, this triggered illegal state exceptions. Forge collects invalidated tiles into a deferred list:
```java
toInvalidate.add(tileentity2);
iterator1.remove();
...
toInvalidate.forEach(TileEntity::invalidate);
```

### 4.3 Non-Tickable Removal Safety
Vanilla only removed TileEntities from `loadedTileEntityList` if they appeared in `tickableTileEntities`. Non-tickable TileEntities (e.g. signs, skulls) that were removed while `processingLoadedTiles` was true would leak into `loadedTileEntityList`. Forge explicitly handles non-tickables:
```java
if (!(tileentity2 instanceof ITickable)) {
    this.loadedTileEntityList.remove(tileentity2);
}
```

---

## 5. Invariants for Rust Migration

1. **Reentrancy Protection (`processingLoadedTiles`):**
   When a machine runs `update()`, it frequently interacts with adjacent blocks: pushing items into chests, pulling power from cables, or placing/breaking blocks. These operations invoke `world.setBlockState()` and `world.setTileEntity()`, which attempt to mutate `loadedTileEntityList`. Mutating the master list while iterating causes fatal crashes. Rust must preserve this two-phase deferred addition/removal queue structure.
2. **Chunk Boundary Gating (`isBlockLoaded`):**
   A TileEntity must never execute its `update()` method if its enclosing chunk is unloaded. In unpatched servers, ticking TileEntities on chunk edges causes cascading chunk loading that consumes server memory.
3. **World Border Containment:**
   TileEntities situated outside the active `worldBorder` are skipped.
4. **Capability Cache Invalidation:**
   In Forge, `TileEntity.invalidate()` invalidates all attached capabilities (`ICapabilityProvider.invalidateCaps()`). Rust bridges must respect capability invalidation hooks when modded TileEntities are removed.
