# Tick Parallelism Constraints & Region Boundary Analysis

## 1. Executive Summary
This research document establishes the theoretical and architectural boundaries for decomposing Minecraft 1.12.2 / Forge 14.23.5.2860 server execution into parallel or concurrent execution units.

A primary goal of the long-term Rust runtime program is to eliminate the single-threaded CPU bottleneck on `Server thread`. However, naive parallelization of world state causes race conditions, visual artifacts, desynchronized redstone, and mod crashes.

This analysis classifies every discovered tick phase into locality categories and catalogs the explicit cross-boundary dependency edges that any future scheduler must satisfy.

---

## 2. Locality Classification

### Category A: Obviously Global (Must Run Sequentially on Global Orchestrator)
Work that mutates process-wide state, coordinates cross-dimensional systems, or fires global lifecycle hooks:
1. **`FMLCommonHandler.onPreServerTick()` / `onPostServerTick()`:** Mod event listeners expect global synchronous execution.
2. **`MinecraftServer.tickCounter++`:** Global tick timestamp reference.
3. **`futureTaskQueue` (scheduled tasks):** Cross-thread tasks submitted by Netty or mods can mutate arbitrary dimensions or player data.
4. **`DimensionManager.unloadWorlds()`:** World lifecycle creation/unloading and file handle disposal.
5. **`NetworkSystem.networkTick()`:** Global Netty channel management and socket connection acceptance.
6. **`PlayerList.onTick()`:** Global player ping monitoring and keep-alive packets.
7. **`MinecraftServer.timeOfLastWarning` & Lag Clamping:** Main tick loop pacing and overload warnings.

### Category B: World-Local (Confined to a Single Dimension)
Work whose state dependencies are bounded within a single `WorldServer` instance:
1. **`World.updateWeather()`:** Rain and thunder timers, cloud cover, lightning frequency.
2. **`WorldServer.worldInfo` Time Increment:** `worldTotalTime` and `worldTime` counters.
3. **`WorldEntitySpawner.findChunksForSpawning()`:** Mob cap evaluation and spawning calculations across active chunks.
4. **`VillageCollection.tick()` & `VillageSiege.tick()`:** Village door registers, iron golem counts, midnight zombie raids.
5. **`Teleporter.removeStalePortalLocations()`:** Nether portal destination cache eviction.
6. **`WorldServer.sendQueuedBlockEvents()`:** Block action packet queue (`SPacketBlockAction`).
7. **`ChunkProviderServer.tick()`:** Chunk unload queue evaluation and Anvil file queue submission.

### Category C: Chunk-Local (Confined to a 16x16 Chunk Column)
Work that operates strictly within the voxel data and metadata of an individual chunk:
1. **`Chunk.enqueueRelightChecks()`:** Skylight/blocklight diff repairs.
2. **`Chunk.onTick(false)`:** Chunk internal tile entity active timer update.
3. **Ice and Snow Freezing:** `WorldServer.updateBlocks()` precipitation checks placed at `getPrecipitationHeight()`.
4. **TileEntity Invalidation Removal:** Deleting invalidated tile entities from a chunk's internal map (`chunk.removeTileEntity()`).
5. **PlayerChunkMap Dirty Block Sync:** Serializing dirty block changes (`SPacketBlockChange` / `SPacketMultiBlockChange`) for players tracking that chunk.

### Category D: Region-Local Candidate (Confined to a 32x32 Chunk / 512x512 Block Region)
Work that spans multiple neighboring chunks, but rarely crosses an Anvil region file boundary (32x32 chunks):
1. **Random Block Ticking (`ExtendedBlockStorage.randomTick()`):** Most block ticks affect only themselves or immediately adjacent neighbors (crops growing, leaf decay, fire spread).
2. **Scheduled Block Ticks (`tickUpdates()`):** Redstone repeaters, comparators, and liquid flows typically propagate within local contraptions or bases contained within a few hundred blocks.
3. **Chunk I/O Serialization:** Saving chunks to `.mca` files is naturally partitioned by 32x32 chunk region files.

### Category E: Entity-Local Candidate (Confined to an Individual Entity Bounding Box)
Work that updates an entity's internal properties without mutating external world voxels:
1. **Entity State Timers:** Fire duration decrement, portal cooldown counter, potion effect expiration (`livingEntityBaseTick`).
2. **Entity AI Goal Evaluation (`EntityAIBase.shouldExecute()`):** Pure computation evaluating internal entity state, target distance, and cooldowns.
3. **Player Physics & Inventory:** Fall distance calculation, hunger decrement, item cooldown timers.

### Category F: Unknown / Cross-Cutting (Unbounded Dependency Reach)
Work that can dynamically read or mutate arbitrary remote state across chunk, region, or dimension boundaries:
1. **Arbitrary Mod TileEntity Ticking (`ITickable.update()`):** Modded machines (e.g. Applied Energistics ME networks, Ender IO dimensional transceivers, Mekanism quantum entangloporters) can interact with inventories, fluid tanks, and energy grids situated thousands of blocks away or in other dimensions.
2. **Forge Capabilities (`ICapabilityProvider.getCapability()`):** Inter-block energy and item transport frequently cascades across chunk boundaries.
3. **Entity Pathfinding & Raytracing (`PathNavigate`):** Mobs scan voxel collision boxes across multiple chunks to compute navigation routes.
4. **Entity Explosions (`Explosion.doExplosionA()` / `doExplosionB()`):** TNT or creeper explosions read and destroy block states across large spherical bounding volumes.

---

## 3. Dependency Graph & Coupling Edges

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                            CROSS-CUTTING EDGES                              │
├────────────────────────────┬────────────────────────────┬───────────────────┤
│ Edge Type                  │ Source -> Target           │ Nature / Blocker  │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Spatial Voxel Neighbor     │ Chunk (X, Z) ->            │ Random ticks,     │
│                            │ Chunk (X ± 1, Z ± 1)       │ liquid flow,      │
│                            │                            │ lighting diffs    │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Entity Spatial Query       │ Entity -> World / Chunks   │ AI pathfinding,   │
│                            │ within AABB                │ block collision,  │
│                            │                            │ entity repulsion  │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Vehicle-Passenger Lock     │ Vehicle Entity ->          │ Synchronous       │
│                            │ Passenger Entity           │ position update   │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Mod Machine Capability     │ TileEntity (Pos A) ->      │ Cross-chunk       │
│ Network                    │ TileEntity (Pos B)         │ energy/item pipe  │
│                            │                            │ cascades          │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Scheduled Tick Mirror      │ TreeSet (Sorted Order) <-> │ Synchronous lock; │
│                            │ HashSet (Deduplication)    │ size invariant    │
├────────────────────────────┼────────────────────────────┼───────────────────┤
│ Global Mod Event Bus       │ Forge Hook ->              │ Arbitrary side    │
│                            │ Mod Subscribers            │ effects across JVM│
└────────────────────────────┴────────────────────────────┴───────────────────┘
```

---

## 4. Hypotheses & Research Candidates for Future Region Parallelism

*Note: The following designs are unvalidated research hypotheses for future evaluation, not final architectural decisions. Region size, boundary synchronization, and scheduling models cannot be finalized until empirical locality and dependency data are gathered.*

1. **Research Candidate 1: Region Isolation with Border Padding (Halo Zones Hypothesis):**
   Hypothesis: If regions (e.g. 32x32 chunks or smaller locality partitions) are ticked on parallel threads, a border halo might be locked or treated as read-only to evaluate concurrent race conditions when block updates or entity movements cross region boundaries. Needs empirical dependency and contention testing.
2. **Research Candidate 2: Two-Phase Commit for Cross-Region Actions (Hypothesis):**
   Hypothesis: When an entity or block mutation in Region A affects Region B (e.g. pushing an item into a chest across a boundary), Region A might post an actor message or cross-region transaction into Region B's mailbox to be applied during Region B's integration phase. Needs performance modeling against lock-free / shared-memory alternatives.
3. **Research Candidate 3: Mod Machine Deferral (Hypothesis):**
   TileEntities with cross-dimensional or long-distance pipe networks cannot be scheduled purely by naive spatial locality. They must either run in a global integration phase or participate in a dependency graph barrier.
4. **Research Candidate 4: Inbound Packet Ingestion Decoupling (Hypothesis):**
   If incoming player packets mutate world state via `futureTaskQueue`, a parallel runtime could potentially route packets by target coordinate into region-specific actor queues rather than a single monolithic global queue. Subject to FML networking compatibility validation.
