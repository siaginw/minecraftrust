# Minecraft 1.12.2 / Forge 14.23.5.2860 Reference Tick Pipeline

## 1. Executive Summary
This document provides an exhaustive, source-grounded specification of the steady-state execution pipeline for Minecraft Java Edition 1.12.2 with Forge 14.23.5.2860 (`build 2860`, commit `d3f01843f7e7a4f613b5e8113d381fd8747b4343`).

Steady-state game execution is driven by a single authoritative thread (`Server thread`) running an accumulator-based 50 ms / 20 TPS loop in `MinecraftServer.run()`. Every tick decomposes into three strictly ordered hierarchical phases:
1. **Server-Level Tick Coordination (`MinecraftServer.tick()`)**: Server tasks drain, Forge server tick events fire, per-dimension processing iterates, and network packet flushing occurs.
2. **Dimension / Level Tick (`WorldServer.tick()`)**: Weather updates, sleeping checks, mob spawning, chunk unload queues, scheduled block ticks (`tickUpdates()`), random block ticks (`updateBlocks()`), chunk map packet syncing, and village logic.
3. **World Entity Processing (`WorldServer.updateEntities()`)**: Weather entities, player ticking, riding entity resolution, regular entity updates, and tile entity ticking with Forge invalidation safety.

---

## 2. Steady-State Loop (`MinecraftServer.run()`)

### 2.1 Loop Entry and Timing State
Upon completion of `DedicatedServer.init()`, `MinecraftServer.run()` records initial timestamps:
- Target tick duration: $50\text{ ms}$ (20 ticks per second).
- Time source: `MinecraftServer.getCurrentTimeMillis()` -> maps to `System.currentTimeMillis()`.
- State variables:
  - `this.currentTime`: wall-clock timestamp of the previous loop iteration.
  - `p_` (long accumulator): accumulated delta time debt awaiting tick processing (initialized to `0L`).
  - `this.timeOfLastWarning`: wall-clock timestamp of the last overload warning.

### 2.2 Lag Accumulation & Clamping
In each loop iteration:
1. Sample wall-clock: `long p_x = getCurrentTimeMillis();`
2. Compute step delta: `long p_xx = p_x - this.currentTime;`
3. Overload detection ($p_{xx} > 2000\text{ ms}$):
   - When step delta exceeds 2 seconds (40 ticks of accumulated real time), the server clamps the delta:
     ```java
     LOGGER.warn("Can't keep up! Did the system time change, or is the server overloaded? Running {}ms behind, skipping {} tick(s)", p_xx, p_xx / 50L);
     p_xx = 2000L;
     this.timeOfLastWarning = this.currentTime;
     ```
   - *Consequence:* The maximum number of catch-up ticks executed in a burst is hard-capped at 40. Lag beyond 2,000 ms is discarded (dropped ticks / time dilation).
4. System clock backward step ($p_{xx} < 0\text{ ms}$):
   - Logs warning: `"Time ran backwards! Did the system time change?"`.
   - Clamps: `p_xx = 0L;`.
5. Accumulator increment: `p_ += p_xx;`
6. Timestamp advance: `this.currentTime = p_x;`

### 2.3 Sleep Fast-Path (All Players Sleeping)
If all players on the server are asleep:
```java
if (this.worlds[0].areAllPlayersAsleep()) {
    this.tick();
    p_ = 0L;
}
```
- A single tick is executed immediately.
- The entire accumulated delta debt `p_` is reset to zero, skipping night smoothly without catch-up burst lag.

### 2.4 Tick Catch-Up Loop
When not sleeping, the server drains the accumulator in 50 ms quanta:
```java
while (p_ > 50L) {
    p_ -= 50L;
    this.tick();
}
```
- **Tick < 50 ms:** `p_` remains $< 50$ ms. Loop body does not repeat. Proceed to sleep.
- **Tick ≈ 50 ms:** `p_` drains each iteration. Steady 20.0 TPS maintained.
- **Tick > 50 ms:** `p_` accumulates remainder. Consecutive ticks execute back-to-back with 0 ms sleep until `p_ <= 50L`.
- **Tick >> 50 ms:** Clamped at 2,000 ms; runs up to 40 consecutive ticks back-to-back.

### 2.5 Inter-Tick Sleep Calculation
```java
Thread.sleep(Math.max(1L, 50L - p_));
```
- Remaining sub-quantum time ($50\text{ ms} - p_$) is yielded to the operating system.
- Enforces an unconditional minimum sleep of $1\text{ ms}$ (`Math.max(1L, ...)`), ensuring background threads (Netty I/O, File I/O, OS scheduling) receive CPU slices even under full load.

---

## 3. Server-Level Tick (`MinecraftServer.tick()`)
Every call to `this.tick()` executes the following ordered sequence:

```text
[Phase 1: Pre-Server Tick Hook]
  └─ FMLCommonHandler.instance().onPreServerTick()
       └─ Post TickEvent.ServerTickEvent(START)

[Phase 2: Tick Counter Increment & Timing Record]
  ├─ this.tickCounter++
  └─ Sample i = System.nanoTime()

[Phase 3: Chunk I/O Async Drain]
  └─ net.minecraftforge.common.chunkio.ChunkIOExecutor.tick()
       └─ Polls finished async chunk disk load tasks, registers into WorldServer

[Phase 4: Scheduled Server Task Queue ("jobs")]
  └─ synchronized (this.futureTaskQueue)
       └─ Drains ListenableFutureTask queue (util.Util.runTask)
       └─ Handles cross-thread tasks (Netty packet handlers, async mod callbacks)

[Phase 5: Level Iteration ("levels")]
  └─ For each dimension id in DimensionManager.getIDs(tickCounter % 200 == 0):
       ├─ WorldServer worldserver = DimensionManager.getWorld(id)
       ├─ If worldserver == null: continue
       ├─ If tickCounter % 20 == 0: sync time & difficulty packet to players
       ├─ FMLCommonHandler.instance().onPreWorldTick(worldserver)
       │    └─ Post TickEvent.WorldTickEvent(Side.SERVER, START, worldserver)
       ├─ worldserver.tick() [See Section 4]
       ├─ worldserver.updateEntities() [See Section 5]
       ├─ FMLCommonHandler.instance().onPostWorldTick(worldserver)
       │    └─ Post TickEvent.WorldTickEvent(Side.SERVER, END, worldserver)
       └─ worldserver.getEntityTracker().tick()
            └─ Updates player tracking sets, dispatches movement/spawn/destroy packets

[Phase 6: Forge Dimension Unloading ("dim_unloading")]
  └─ net.minecraftforge.common.DimensionManager.unloadWorlds(this.worldTickTimes)
       └─ Checks dimensions queued for unload, closes chunk providers, fires WorldEvent.Unload

[Phase 7: Network System Processing ("connection")]
  └─ this.networkSystem.networkTick()
       └─ Iterates NetworkManager connections, processes inbound packet queue

[Phase 8: Player List Processing ("players")]
  └─ this.playerList.onTick()
       ├─ Updates player ping timestamps (sends KeepAlive every 40 ticks / 2s)
       └─ Periodic player save checks

[Phase 9: Command Functions ("commandFunctions")]
  └─ this.getFunctionManager().update()
       └─ Executes tick-tagged mcfunctions

[Phase 10: Custom Tickables ("tickables")]
  └─ For each ITickable in this.tickables: tickable.update()

[Phase 11: Tick Time Record & Post-Server Tick Hook]
  ├─ Record elapsed nanoseconds into this.tickTimeArray[this.tickCounter % 100]
  └─ FMLCommonHandler.instance().onPostServerTick()
       └─ Post TickEvent.ServerTickEvent(END)
```

---

## 4. World-Level Tick (`WorldServer.tick()`)
Inside Phase 5, each dimension's `WorldServer.tick()` executes the following discrete sub-phases:

1. **Weather Update (`super.tick() -> World.updateWeather()`):**
   - Decrements rain timer (`cleanWeatherTime`, `rainTime`, `thunderTime`).
   - Updates rain and thunder strength values with linear clamping `[0.0, 1.0]`.
   - Modifies server state flags (`raining`, `thundering`).
2. **Hardcore Difficulty Sync:**
   - In hardcore mode, clamps difficulty to `EnumDifficulty.HARD`.
3. **Biome Provider Cache Cleanup:**
   - Calls `provider.getBiomeProvider().cleanupCache()` to evict stale biome coordinate lookups.
4. **All Players Sleeping Check:**
   - If `areAllPlayersAsleep()`:
     - If `doDaylightCycle` is enabled, computes delta to sunrise (`(time + 24000L) - (time % 24000L)`) and advances world time.
     - Calls `wakeAllPlayers()` (clears player bed status, resets sleep timer).
5. **Mob Spawning (`mobSpawner`):**
   - If `doMobSpawning` game rule is true:
     - Invokes `WorldEntitySpawner.findChunksForSpawning(...)`.
     - Scans eligible chunks around active players, checks spawn caps (Monster, Creature, Ambient, Water), performs collision checks, instantiates entities, fires `ForgeEventFactory.canEntitySpawn()`, and adds entities to the world.
6. **Chunk Source Tick (`chunkSource`):**
   - Calls `ChunkProviderServer.tick()`.
   - Iterates `droppedChunksSet` (chunks flagged for unload), serializes chunk data via Anvil save queue, and unloads chunk objects.
7. **Skylight Subtraction Calculation:**
   - Calls `calculateSkylightSubtracted(1.0F)`. Updates ambient light levels based on celestial angle and weather.
8. **World Time Increment:**
   - `worldInfo.setWorldTotalTime(totalTime + 1L);` (unconditional monotonic tick count).
   - If `doDaylightCycle`: `worldInfo.setWorldTime(worldTime + 1L);` (in-game time of day).
9. **Scheduled Block Ticks (`tickPending`):**
   - Calls `WorldServer.tickUpdates(false)`. Drains prioritized block tick queue (redstone, liquid flow, gravity blocks). See `docs/learned/scheduled_tick_model.md`.
10. **Random Block Ticks & Environmental Effects (`tickBlocks`):**
    - Calls `WorldServer.updateBlocks()`.
    - Iterates `playerChunkMap.getChunkIterator()` (chunks within 128 blocks / 8 chunks of active players).
    - For each chunk:
      - `enqueueRelightChecks()`: repairs queued skylight/blocklight diffs.
      - `chunk.onTick(false)`: updates chunk tile entity active timers.
      - **Lightning & Thunder:** 1/100,000 chance per chunk during thunderstorm; spawns skeleton trap horse or lightning bolt.
      - **Ice & Snow:** 1/16 chance per chunk; checks precipitation height, freezes water blocks (`Blocks.ICE`), places snow layers (`Blocks.SNOW_LAYER`), and fills cauldrons with rain.
      - **Random Block Ticks:** For each 16x16x16 `ExtendedBlockStorage` section requiring ticks, selects `randomTickSpeed` (default 3) random coordinates via LCG random generator; if block has `tickRandomly=true`, calls `block.randomTick(world, pos, state, rand)`.
11. **Player Chunk Map Sync (`chunkMap`):**
    - Calls `playerChunkMap.tick()`.
    - Drains dirty chunk block change packets (`SPacketBlockChange`, `SPacketMultiBlockChange`) and transmits them to players tracking those chunks.
12. **Village & Siege Logic (`village`):**
    - `villageCollection.tick()`: updates door lists, villager counts, iron golem counts, reputation.
    - `villageSiege.tick()`: evaluates zombie siege conditions near villages at midnight.
13. **Portal Forcer Cleanup (`portalForcer`):**
    - Removes expired Nether/custom portal destination coordinates from cache.
14. **Queued Block Events (`sendQueuedBlockEvents()`):**
    - Drains `blockEventQueue` (Block Action packets: chest lid angles, piston arms, note blocks) and fires `SPacketBlockAction`.

---

## 5. Entity & TileEntity Tick Pipeline (`WorldServer.updateEntities()`)
Following `WorldServer.tick()`, `MinecraftServer` invokes `worldserver.updateEntities()`. This phase is partitioned as follows:

```text
[Step 1: Weather Effect Entities]
  └─ Iterates this.weatherEffects (lightning bolts)
       └─ Updates entity position, fires lightning strike damage, decrements life

[Step 2: Server Player Entities (tickPlayers)]
  └─ Iterates this.playerEntities
       ├─ Dismounts dead vehicle entities
       ├─ Calls this.updateEntity(player)
       │    ├─ FMLCommonHandler.instance().onPlayerPreTick(player) -> PlayerTickEvent(START)
       │    ├─ player.onUpdate() [physics, inventory, status effects, bed timers]
       │    └─ FMLCommonHandler.instance().onPlayerPostTick(player) -> PlayerTickEvent(END)
       └─ If player.isDead: removes player from chunk and loaded lists

[Step 3: Regular World Entities]
  └─ Iterates this.loadedEntityList (indexed loop: i = 0 .. size - 1)
       ├─ Fetch entity = loadedEntityList.get(i)
       ├─ If entity.getRidingEntity() != null:
       │    └─ If riding entity is dead or not riding: dismount; else skip (rider ticked by vehicle)
       ├─ If !entity.isDead:
       │    └─ updateEntityWithOptionalForce(entity, true) -> entity.onUpdate()
       └─ If entity.isDead:
            ├─ Remove from current chunk: chunk.removeEntity(entity)
            ├─ loadedEntityList.remove(i--)
            └─ onEntityRemoved(entity)

[Step 4: TileEntity Tick Loop]
  └─ Sets this.processingLoadedTiles = true
  └─ Iterates this.tickableTileEntities with Iterator:
       ├─ Fetch tile = iterator.next()
       ├─ If tile.isInvalid(): iterator.remove(); loadedTileEntityList.remove(tile); continue
       ├─ If chunk not loaded: continue
       ├─ tile.update() [ITickable implementation]
       └─ If tile.isInvalid(): iterator.remove(); loadedTileEntityList.remove(tile)
  └─ Sets this.processingLoadedTiles = false

[Step 5: TileEntity Invalidation & Addition Queues]
  ├─ If !this.tileEntitiesToBeRemoved.isEmpty():
  │    ├─ For each tile in tileEntitiesToBeRemoved:
  │    │    ├─ tile.onChunkUnload()
  │    │    └─ if not ITickable: loadedTileEntityList.remove(tile)
  │    └─ tileEntitiesToBeRemoved.clear()
  └─ If !this.addedTileEntityList.isEmpty():
       ├─ For each tile in addedTileEntityList:
       │    ├─ If not invalid: register into loadedTileEntityList and tickableTileEntities
       │    └─ Chunk chunk = getChunk(pos); chunk.addTileEntity(pos, tile)
       └─ addedTileEntityList.clear()
```

---

## 6. Empirical Profiling Breakdown (Forge 2860 Baseline)
*Note: Corrected in `p0-2-v2` methodology (see `docs/benchmarks/p0-2-methodology-correction.md`). Profiler percentages represent hierarchical shares of parent sections and root execution, not additive system CPU. Absolute times are derived from monotonic nanosecond compute measurements (mean compute MSPT: 0.263 ms).*

| Subsystem / Profiler Section | % of Parent | % of Root | Absolute Compute Time | Function & Description |
| :--- | :--- | :--- | :--- | :--- |
| **`root`** | - | **100.00%** | **0.2630 ms** | Total `MinecraftServer.tick()` execution time |
| ├── **`levels`** | **87.91%** | **87.91%** | **0.2312 ms** | Total dimension processing across all worlds |
| │   └── **`world`** | **96.34%** | **84.69%** | **0.2227 ms** | Overworld (dimension 0) execution |
| │       ├── **`tick`** | **93.75%** | **79.40%** | **0.2088 ms** | Internal world simulation loop |
| │       │   ├── `chunkSource` | 60.59% | 48.11% | 0.1265 ms | ChunkProviderServer unload polling & cache |
| │       │   ├── `entities` | 27.46% | 21.80% | 0.0573 ms | Entity & TileEntity update loops |
| │       │   │   ├── `regular` | 64.09% | 13.97% | 0.0367 ms | Living & non-living entity logic |
| │       │   │   └── `blockEntities` | 20.67% | 4.51% | 0.0118 ms | TileEntity ticking |
| │       │   └── `tickPending` | 5.99% | 4.76% | 0.0125 ms | Scheduled block ticks |
| │       └── `tracker` | 5.45% | 4.61% | 0.0121 ms | Entity tracker position deltas |
| ├── **`players`** | **1.66%** | **1.66%** | **0.0044 ms** | PlayerList ping & keep-alive transmission |
| ├── **`snooper`** | **0.66%** | **0.66%** | **0.0017 ms** | Telemetry collection |
| ├── **`dim_unloading`** | **0.55%** | **0.55%** | **0.0014 ms** | Forge DimensionManager world unload queue |
| ├── **`commandFunctions`**| **0.40%** | **0.40%** | **0.0011 ms** | Command function manager execution |
| ├── **`connection`** | **0.29%** | **0.29%** | **0.0008 ms** | NetworkSystem channel packet queue flush |
| └── **`jobs`** | **0.22%** | **0.22%** | **0.0006 ms** | Cross-thread task queue draining (`futureTaskQueue`) |
| **`root.tickables`** | **0.06%** | Custom server ITickables |
| **`root.tallying`** | **0.05%** | Rolling tick time array calculation |

---

## 7. Migration Invariants & Subsystem Boundaries

1. **Strict Ordering of Scheduled Ticks Before Entity Ticking:**
   Scheduled block ticks modify the physical block layout (e.g. falling sand, flowing lava, redstone doors). Entities ticking immediately afterward depend on this updated collision and block state. Reversing or parallelizing this relationship without dependency barriers breaks vanilla parity.
2. **Packet Processing Position:**
   Incoming packets dispatched via `PacketThreadUtil` execute in `futureTaskQueue` at the start of the tick (`jobs`). Direct socket packet processing in `NetworkSystem.networkTick()` runs at the end of the tick. This creates a two-phase packet ingestion architecture.
3. **TileEntity Invalidation Delay:**
   TileEntities are never removed or invalidated mid-iteration. Invalidation requests during `ITickable.update()` set the invalid flag; actual removal is deferred until the loop finishes or the addition/removal queues drain.
4. **Dimension Parallelization Hazard:**
   Vanilla dimensions tick sequentially on `Server thread`. Forge coremods and dimension-transfer logic (`ServerTravelAgent`, `WorldServerMulti`, cross-dimensional capabilities) assume synchronous, uninterrupted execution on `Server thread`. Multi-threading dimension ticks requires cross-world mutation locks.
