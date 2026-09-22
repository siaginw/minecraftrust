# Minecraft 1.12.2 Entity Tick Model

## 1. Overview & Architectural Role
In Minecraft 1.12.2 / Forge 14.23.5.2860, entity execution represents the single largest CPU consumer during server ticking (**45.80%** of overall tick time in baseline profiling, with regular entity updates accounting for **44.00%**).

All entity logic executes synchronously on `Server thread` inside `WorldServer.updateEntities()`.

---

## 2. Entity Lifecycle & Categorization

Entities in `WorldServer` are segmented into four distinct runtime groups:
1. **Weather Effects (`this.weatherEffects`):** Lightning bolts (`EntityLightningBolt`). Ticked first, bypassing standard entity physics.
2. **Server Players (`this.playerEntities`):** Human players (`EntityPlayerMP`). Ticked in a dedicated player phase before general entities.
3. **Loaded Entities (`this.loadedEntityList`):** Master list of all active world entities (mobs, items, projectiles, vehicles).
4. **Unloaded Entities (`this.unloadedEntityList`):** Queue of entities removed via chunk unloads or dimension transfers awaiting cleanup.

---

## 3. The Execution Sequence (`WorldServer.updateEntities()`)

```text
WorldServer.updateEntities()
│
├── Step 1: Weather Effects Ticking
│    └── Iterates this.weatherEffects (indexed loop)
│         ├── Calls entity.onUpdate()
│         └── If entity.isDead -> weatherEffects.remove(i--)
│
├── Step 2: Server Player Ticking (tickPlayers())
│    └── Iterates this.playerEntities (indexed loop)
│         ├── Dismount check: if riding entity is dead -> player.dismountRidingEntity()
│         ├── If not riding (or vehicle dead):
│         │    └── this.updateEntity(player)
│         │         ├── Forge Hook: FMLCommonHandler.onPlayerPreTick(player) -> PlayerTickEvent(START)
│         │         ├── player.onUpdate() [physics, food, potion effects, inventory]
│         │         └── Forge Hook: FMLCommonHandler.onPlayerPostTick(player) -> PlayerTickEvent(END)
│         └── If player.isDead:
│              ├── chunk.removeEntity(player)
│              ├── loadedEntityList.remove(player)
│              └── onEntityRemoved(player)
│
├── Step 3: Regular Entity Ticking Loop
│    └── Iterates this.loadedEntityList (indexed loop: i = 0 .. size - 1)
│         ├── Entity entity = loadedEntityList.get(i)
│         ├── Passenger / Riding Gating:
│         │    └── If entity.getRidingEntity() != null:
│         │         ├── If riding entity dead or not passenger: dismount()
│         │         └── Else: continue (vehicle ticks its passengers directly!)
│         ├── Active Entity Execution:
│         │    └── If !entity.isDead:
│         │         └── updateEntityWithOptionalForce(entity, true)
│         │              └── entity.onUpdate() [AI, movement, collisions, timers]
│         └── Dead Entity Cleanup:
│              └── If entity.isDead:
│                   ├── int chunkX = entity.chunkCoordX, chunkZ = entity.chunkCoordZ
│                   ├── If isChunkLoaded(chunkX, chunkZ):
│                   │    └── getChunk(chunkX, chunkZ).removeEntity(entity)
│                   ├── loadedEntityList.remove(i--)
│                   └── onEntityRemoved(entity)
│
└── Step 4: TileEntity Ticking (See docs/learned/tileentity_tick_model.md)
```

---

## 4. Iteration Mechanics & Concurrent Modification Handling

### 4.1 Indexed For-Loop (`int i = 0; i < loadedEntityList.size(); ++i`)
Unlike collections using fail-fast `Iterator`, `loadedEntityList` uses an indexed for-loop:
```java
for (int i = 0; i < this.loadedEntityList.size(); ++i) {
    Entity entity = this.loadedEntityList.get(i);
    ...
    if (entity.isDead) {
        int chunkX = entity.chunkCoordX;
        int chunkZ = entity.chunkCoordZ;
        if (entity.addedToChunk && this.isChunkLoaded(chunkX, chunkZ, true)) {
            this.getChunk(chunkX, chunkZ).removeEntity(entity);
        }
        this.loadedEntityList.remove(i--);
        this.onEntityRemoved(entity);
    }
}
```

### 4.2 In-Place Removals (`i--`)
When an entity dies during its tick (e.g. killed by poison, void damage, or self-despawn), it sets `this.isDead = true`. The enclosing loop immediately removes it from `loadedEntityList` and decrements the index (`i--`), maintaining correct iteration order without allocating an intermediate removal queue.

### 4.3 In-Flight Additions (`spawnEntity`)
If an entity spawns new entities during its tick (e.g. chicken laying an egg, mob splitting, projectile launch):
- `World.spawnEntity(newEntity)` appends the new entity to the END of `loadedEntityList`.
- Because `loadedEntityList.size()` is re-evaluated each iteration of `i < this.loadedEntityList.size()`, **entities added during the tick WILL be ticked in that same tick** if they are appended after the current index `i`.

---

## 5. Riding and Passenger Ordering Invariant

A critical architectural invariant governs vehicles and passengers:
1. **Riding entities do NOT update independently in `loadedEntityList`:**
   ```java
   if (entity.getRidingEntity() != null) {
       if (!entity.getRidingEntity().isDead && entity.getRidingEntity().isPassenger(entity)) {
           continue; // SKIP INDEPENDENT TICK!
       }
       entity.dismountRidingEntity();
   }
   ```
2. **Vehicles Update Passengers:**
   When the vehicle (e.g. `EntityBoat`, `EntityMinecart`, `EntityHorse`) ticks in `updateEntityWithOptionalForce`, its `updatePassenger(entity)` method updates passenger positions and physics synchronously with the vehicle's movement.
3. **Implication:** Passenger position synchronization is strictly locked to vehicle tick timing, preventing visual jitter and coordinate desync between vehicle and passenger.

---

## 6. Profiling Breakdown of Entity Execution
Empirical CPU distribution inside `entities.regular` (from baseline run `profile-results-2026-09-17_08.35.55.txt`):

| Subcomponent | % of Entity Tick | Description |
| :--- | :--- | :--- |
| `unspecified` | 41.03% | General entity logic, status timers, fall damage checks |
| `move` | 20.42% | Bounding box collision detection (`MoverType.SELF`) against world voxel voxels |
| `rest` / `chunkCheck` | 9.75% | Verifying chunk coordinate boundaries (`chunkCoordX/Z` updates) |
| `entityBaseTick` | 8.07% | Fire decrement, portal cooldown timers, water entry logic |
| `ai` | 7.88% | Pathfinding, GoalSelector (`AITasks`), TargetSelector |
| ├── `newAi.goalSelector` | (26.85% of AI) | Evaluating `EntityAIBase.shouldExecute()` across active tasks |
| ├── `newAi.controls` | (24.17% of AI) | Look, jump, and move controllers |
| └── `newAi.navigation` | (16.48% of AI) | Node-based path computing (`PathNavigate`) |
| `travel` | 6.07% | Fluid drag, gravity application, slippery block friction |
| `livingEntityBaseTick` | 3.73% | Potion effect decrement, equipment attribute updates |
| `push` | 1.05% | Entity-entity collision repulsion (`applyEntityCollision`) |
| `headTurn` | 0.72% | Pitch/yaw body rotation interpolation |

---

## 7. Crash Isolation & Parity Rules for Rust

1. **Entity Crash Envelope:**
   Vanilla wraps entity updates in a dedicated try-catch block producing structured crash reports:
   ```java
   CrashReport crash = CrashReport.makeCrashReport(throwable, "Ticking entity");
   CrashReportCategory category = crash.makeCategory("Entity being ticked");
   entity.addEntityCrashInfo(category);
   ```
2. **Chunk Membership Synchrony:**
   An entity's chunk coordinate (`entity.chunkCoordX`, `entity.chunkCoordZ`) MUST remain strictly synchronized with the chunk's internal `entityLists[16]` slice. Moving across a chunk boundary requires atomic removal from the source chunk and insertion into the target chunk. Failure to do so causes "ghost entities" that persist in memory after their chunk unloads.
3. **No Blind Parallelization:**
   Entity AI and movement read and mutate world voxel state, spawn particles, play sounds, and read neighboring entities within bounding boxes. Naive entity parallelization causes data races on block states and neighboring entity lists. Any parallel entity system must use spatial partitioning or read-only world snapshot isolation.
