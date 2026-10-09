# Minecraft 1.12.2 / Forge Tick Compatibility & Mod Hazards

## 1. Overview
In a hybrid or progressively migrated Minecraft server runtime, the tick pipeline is the primary point of contact between game logic and modded extensions.

Forge mods in 1.12.2 make extensive assumptions about the exact timing, ordering, thread identity, and object structures of the tick loop. Violating these assumptions causes silent gameplay corruption, broken automation, desynchronized redstone, or hard crashes.

This document catalogs the specific compatibility hazards identified during P0-2 analysis.

---

## 2. Catalog of Tick Compatibility Hazards

### Hazard 1: Forge Tick Event Ordering & Phase Strictness
- **Severity:** CRITICAL
- **Mechanism:** Forge provides `TickEvent.ServerTickEvent`, `TickEvent.WorldTickEvent`, and `TickEvent.PlayerTickEvent`, each carrying a `Phase` enum (`START` or `END`).
- **Mod Assumption:**
  - Mods register event subscribers on `@SubscribeEvent` expecting `Phase.START` to run before any world/player processing, and `Phase.END` to run after all processing has finished.
  - Mods execute state resets, energy network calculations, and cooldown decrements in `ServerTickEvent(START)` or `WorldTickEvent(START)`.
  - Reordering or collapsing `START` and `END` into a single event breaks multi-mod timing synchronization.

### Hazard 2: `isCallingFromMinecraftThread()` Identity Assumption
- **Severity:** CRITICAL
- **Mechanism:** `MinecraftServer` and `WorldServer` implement `IThreadListener.isCallingFromMinecraftThread()`, which evaluates:
  `Thread.currentThread() == this.serverThread`.
- **Mod Assumption:**
  - Mods and packet handlers call `PacketThreadUtil.checkThreadAndEnqueue()` or check `world.isRemote` and `isCallingFromMinecraftThread()` before executing mutations.
  - If a hybrid runtime runs game ticks on a thread pool, an OS worker thread, or a Rust thread without matching JVM thread identity, Java mods conclude they are executing off-thread and will infinitely reschedule their work into `futureTaskQueue` or throw `ThreadQuickExitException`.

### Hazard 3: Scheduled Block Tick Three-Tier Ordering
- **Severity:** HIGH
- **Mechanism:** `WorldServer.pendingTickListEntriesTreeSet` sorts strictly by:
  `scheduledTime` -> `priority` -> `tickEntryID`.
- **Mod Assumption:**
  - Complex redstone circuits, repeaters, comparators, and automated contraptions rely on deterministic sub-tick ordering.
  - If multiple redstone components are scheduled for the same world tick, their relative priority and initial insertion sequence (`tickEntryID`) determine which updates first.
  - Changing this to an arbitrary hash order or non-stable sort causes redstone race conditions, breaking user builds.

### Hazard 4: Reentrant TileEntity List Mutation
- **Severity:** HIGH
- **Mechanism:** During `tickableTileEntities` iteration, `World.processingLoadedTiles` is set to `true`.
- **Mod Assumption:**
  - Modded machines (e.g. quarries, miners, pipes, autocrafters) regularly place blocks (`world.setBlockState`) or spawn tile entities during their `update()` method.
  - Forge and vanilla defer these mutations to `addedTileEntityList` and `tileEntitiesToBeRemoved`.
  - An implementation that modifies active iteration collections directly without a deferral buffer causes `ConcurrentModificationException`.

### Hazard 5: Direct Field Reflection on Core World Lists
- **Severity:** HIGH
- **Mechanism:** Optimization mods (e.g. FoamFix, BetterFps, performant, SpongeForge) and admin utilities use Java reflection to inspect or replace private fields on `MinecraftServer` and `World`:
  - `MinecraftServer.tickCounter`
  - `MinecraftServer.futureTaskQueue`
  - `World.loadedEntityList`
  - `World.loadedTileEntityList`
  - `WorldServer.pendingTickListEntriesTreeSet`
- **Migration Hazard:** If a Rust subsystem replaces these collections in native memory without maintaining live synchronized Java proxy collections or bridge wrappers, reflecting mods will encounter null references or stale data.

### Hazard 6: Dynamic Dimension Discovery via `DimensionManager`
- **Severity:** MEDIUM
- **Mechanism:** In `MinecraftServer.tick()`, Forge iterates dimensions using:
  `DimensionManager.getIDs(this.tickCounter % 200 == 0)`
  rather than vanilla's static `this.worlds` array.
- **Mod Assumption:**
  - Mods dynamically register, load, and unload dimensions at runtime (e.g. Mystcraft, Twilight Forest, RFTools Dimensions, Compact Machines).
  - Hardcoding dimensions to vanilla 0, -1, and 1 skips ticking all modded dimensions.

### Hazard 7: ASM / Mixin Bytecode Patching on `tick()`
- **Severity:** CRITICAL
- **Mechanism:** Optimization mods (SpongeForge, Phosphor, VanillaFix) and utility coremods inject ASM hooks directly into `MinecraftServer.tick()`, `WorldServer.tick()`, or `World.updateEntities()`.
- **Mod Assumption:**
  - If a hybrid server replaces `MinecraftServer.tick()` entirely with native Rust code, any bytecode transformer expecting to hook into `MinecraftServer.tick()` will either fail to apply or find its injected code never executed.
  - Architectural mitigation: Rust must hook at coarse boundaries (e.g. replacing chunk I/O or region storage) while preserving the top-level Java tick dispatch loop until coremod interception is addressed.

---

## 3. Summary of Compatibility Gates for P0-2
1. Retain top-level tick orchestration in Java during early migration stages.
2. Maintain identical event dispatch points for Forge `ServerTickEvent`, `WorldTickEvent`, and `PlayerTickEvent`.
3. Preserve three-tier sort order for scheduled block updates.
4. Honor `IThreadListener.isCallingFromMinecraftThread()` contract on all game-mutating threads.
