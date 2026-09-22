# Minecraft 1.12.2 / Forge 14.23.5.2860 Steady-State Tick Invariants

## 1. Executive Summary
This document establishes the verified architectural invariants governing steady-state server execution for Minecraft Java Edition 1.12.2 with Forge 14.23.5.2860.

Every invariant recorded here is grounded directly in deobfuscated source code (`third_party_reference/minecraft/src`), Forge patch files (`third_party_reference/forge/src/patches`), or empirical profiling logs (`benchmarks/baseline/`).

---

## 2. Invariant Catalog

### Invariant 1: Single Authoritative Mutator Thread for World State
- **Statement:** All authoritative game state mutation—including block state changes, entity movement/spawning/removal, TileEntity updates, inventory modifications, and scheduled tick queue manipulation—occurs exclusively on `Server thread`.
- **Evidence:** `IThreadListener.isCallingFromMinecraftThread()` evaluates `Thread.currentThread() == this.serverThread` (`MinecraftServer.java:1207`). Inbound packets attempting mutation off-thread are interrupted by `PacketThreadUtil.checkThreadAndEnqueue()` and redirected to `futureTaskQueue` (`PacketThreadUtil.java:6-16`).
- **Boundary Clarification:** Background worker threads (`Netty Server IO #N`, `File IO Thread`, `User Authenticator #N`) operate concurrently, but perform I/O, decompression, encryption, and disk serialization on snapshots or decoupled buffers, never on live world data structures.

### Invariant 2: Scheduled Block Ticks Precede Entity Ticking
- **Statement:** Within each dimension's tick cycle, scheduled block ticks (`WorldServer.tickUpdates()`) execute strictly BEFORE entity updates (`WorldServer.updateEntities()`).
- **Evidence:** `MinecraftServer.updateTimeLightAndEntities()` calls `worldserver.tick()` at line 557, which executes `this.tickUpdates(false)` at `WorldServer.java:214`. Subsequently, `MinecraftServer` calls `worldserver.updateEntities()` at line 560.
- **Architectural Consequence:** Block updates (such as falling sand converting to falling block entities, water extinguishing lava into obsidian, or redstone doors opening) complete their state changes before entities calculate collisions and pathfinding in that same tick.

### Invariant 3: Scheduled Block Tick 3-Tier Total Ordering
- **Statement:** The scheduled block tick queue is ordered strictly by:
  1. `scheduledTime` (ascending)
  2. `priority` (ascending)
  3. `tickEntryID` (ascending FIFO sequence counter)
- **Evidence:** `NextTickListEntry.compareTo(NextTickListEntry other)` (`NextTickListEntry.java:42-56`). `tickEntryID` is monotonically incremented for every instantiation (`NextTickListEntry.java:15`), guaranteeing that ties never occur.
- **Architectural Consequence:** Redstone repeaters, comparators, and automated machines depend on this deterministic ordering. Reordering entries scheduled for the same world tick causes observable race conditions and broken circuitry.

### Invariant 4: Scheduled Block Tick Deduplication Ignores Time and Priority
- **Statement:** The `HashSet` mirror for scheduled ticks deduplicates entries purely on `(BlockPos, Block)`, ignoring `scheduledTime`, `priority`, and `tickEntryID`.
- **Evidence:** `NextTickListEntry.equals(Object obj)` checks only `this.position.equals(other.position) && Block.isEqualTo(this.block, other.block)` (`NextTickListEntry.java:61-68`).
- **Architectural Consequence:** A block at a given coordinate cannot possess more than one pending scheduled tick in the queue at any time.

### Invariant 5: Hard Upper Limit of 65,536 Scheduled Ticks per World Tick
- **Statement:** A maximum of 65,536 scheduled block ticks can execute in a single world tick.
- **Evidence:** `WorldServer.tickUpdates(boolean runAll)` explicitly clamps the drain batch size:
  `int count = this.pendingTickListEntriesTreeSet.size(); if (count > 65536) { count = 65536; }` (`WorldServer.java:547-550`).
- **Architectural Consequence:** Excess backlog remains in `TreeSet` and executes in subsequent world ticks. Bypassing this cap alters vanilla tick budget and lag degradation mechanics.

### Invariant 6: Reentrant TileEntity List Mutation Protection
- **Statement:** While `tickableTileEntities` is actively iterating, direct additions and removals to `loadedTileEntityList` and `tickableTileEntities` are prohibited; mutations are staged in `addedTileEntityList` and `tileEntitiesToBeRemoved`.
- **Evidence:** `World.updateEntities()` sets `this.processingLoadedTiles = true` (`World.java:1072`), stages removals, and only flushes `addedTileEntityList` after `processingLoadedTiles = false` (`World.java:1120-1145`).
- **Architectural Consequence:** Modded machines can place blocks and spawn tile entities during `update()` without triggering `ConcurrentModificationException`.

### Invariant 7: Vehicles Tick Passengers Synchronously
- **Statement:** An entity riding another entity is skipped during master `loadedEntityList` iteration; its update is invoked synchronously by its vehicle inside `updatePassenger()`.
- **Evidence:** `WorldServer.updateEntities()` contains:
  `if (entity.getRidingEntity() != null) { if (!entity.getRidingEntity().isDead && entity.getRidingEntity().isPassenger(entity)) { continue; } ... }` (`World.java:1043-1049`).
- **Architectural Consequence:** Eliminates sub-tick coordinate desynchronization and visual jitter between vehicles and riders.

### Invariant 8: Server-Thread Task Queue Exception Isolation
- **Statement:** An unhandled exception thrown inside a task scheduled via `futureTaskQueue` is logged as a fatal error but NEVER crashes the server tick loop.
- **Evidence:** `Util.runTask(FutureTask<V> task, Logger logger)` catches `ExecutionException` and `InterruptedException`, logs `logger.fatal("Error executing task", e)`, and returns null (`Util.java:15-19`).
- **Architectural Consequence:** A faulty mod network packet handler or async task does not terminate the server process.

### Invariant 9: Forge Tick Event Phase Sockets
- **Statement:** Forge tick events are dispatched in strictly balanced pairs (`START` and `END`) at defined lifecycle milestones:
  - `ServerTickEvent`: START before `tickCounter++` / END after all server processing completes.
  - `WorldTickEvent`: START before dimension tick / END after dimension entity update.
  - `PlayerTickEvent`: START at beginning of `player.onUpdate()` / END at completion of `player.onUpdate()`.
- **Evidence:** `MinecraftServer.java.patch` lines 163 and 179; `WorldServer.java.patch` lines 214 and 222; `EntityPlayer.java.patch` lines 28 and 45.
- **Architectural Consequence:** Mods rely on `START` for state setup and `END` for post-simulation cleanup.

### Invariant 10: 2,000 ms Catch-Up Lag Clamp (40 Ticks Max)
- **Statement:** The maximum delta time debt that can accumulate in `MinecraftServer.run()` is 2,000 ms (40 ticks). Excess real-time lag is permanently dropped.
- **Evidence:** `MinecraftServer.run()` checks `if (p_xx > 2000L) { p_xx = 2000L; }` (`MinecraftServer.java:406-412`).
- **Architectural Consequence:** Prevents the "spiral of death" where overloaded servers spend eternity executing back-to-back catch-up ticks.

### Invariant 11: All-Players-Sleeping Skips Lag Catch-Up
- **Statement:** If all players in Dimension 0 are asleep, `MinecraftServer.run()` executes 1 tick and immediately resets accumulated time debt `p_ = 0L`.
- **Evidence:** `MinecraftServer.run()` lines 417-420:
  `if (this.worlds[0].areAllPlayersAsleep()) { this.tick(); p_ = 0L; }`.
- **Architectural Consequence:** Night skip operates smoothly without triggering a 100-tick fast-forward burst.
