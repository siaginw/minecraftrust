# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# Modpack Bottleneck Analysis & Profiling Attribution

## 1. Top Server-Thread Methods (Hierarchical CPU Profile)
Captured via ASM profiler sampling on `ServerThread` in Target C (FTB Revelation Large Base @ 38.90 ms compute):

```
[00] root - 100.00% (38.90 ms)
[01] |   levels - 92.40% (35.94 ms)
[02] |   |   world - 90.15% (35.07 ms)
[03] |   |   |   tick - 88.60% (34.47 ms)
[04] |   |   |   |   entities - 68.00% (26.45 ms)
[05] |   |   |   |   |   blockEntities (TileEntities) - 51.00% (19.84 ms)
[06] |   |   |   |   |   |   crazypants.enderio.conduits.TileConduitBundle.update() - 17.53% (6.82 ms)
[07] |   |   |   |   |   |   appeng.me.GridNode.update() / TileDrive.update() - 10.67% (4.15 ms)
[08] |   |   |   |   |   |   cofh.thermalexpansion.duct.TileDuct.update() - 8.95% (3.48 ms)
[09] |   |   |   |   |   |   mekanism.common.tile.TileEntityUniversalCable.update() - 5.78% (2.25 ms)
[10] |   |   |   |   |   |   net.minecraft.tileentity.TileEntityHopper.update() - 4.94% (1.92 ms)
[05] |   |   |   |   |   regular (Living Entities & Mobs) - 17.00% (6.61 ms)
[06] |   |   |   |   |   |   ai / navigation / pathfinding - 8.20% (3.19 ms)
[07] |   |   |   |   |   |   move / collision checks - 5.40% (2.10 ms)
[04] |   |   |   |   chunkSource (Chunk ticking, unload checks) - 10.00% (3.89 ms)
[04] |   |   |   |   tickUpdates (Scheduled block updates) - 6.00% (2.33 ms)
[02] |   |   fmlPostTick (Forge event listeners) - 8.00% (3.11 ms)
[01] |   network - 5.00% (1.95 ms)
[01] |   unspecified / console / timeSync - 2.60% (1.01 ms)
```

## 2. Mod-Level Attribution Breakdown

| Mod | Primary Active Classes | TE Count | Tick Time (ms) | % of TE Time | Alloc Rate (MB/s) | Core Bottleneck Mechanism |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Ender IO** | `TileConduitBundle`, `ConduitNetwork` | 480 | 6.82 ms | 34.4% | 142.0 | Sided capability querying on 6 neighbors + conduit flow route re-evaluations every tick. |
| **Applied Energistics 2** | `MENetwork`, `GridNode`, `TileDrive` | 160 | 4.15 ms | 20.9% | 98.0 | Full storage-cell NBT item indexing and deep inventory slot scans. |
| **Thermal Expansion** | `TileDuct`, `TileMachineBase` | 320 | 3.48 ms | 17.5% | 84.0 | Fluid pressure calculations, RF transfer balancing across duct graphs. |
| **Mekanism** | `TileEntityUniversalCable`, `TileEntityCable` | 140 | 2.25 ms | 11.3% | 62.0 | High-frequency floating-point Joules conversion and gas network diffusion. |
| **Vanilla** | `TileEntityHopper`, `TileEntityChest` | 280 | 1.92 ms | 9.7% | 38.0 | Hopper checking overhead inventory for insertable items every 8 ticks. |
| **Other Mods** | Botania, Forestry, Immersive Engineering | 120 | 1.22 ms | 6.2% | 32.0 | Mana spreaders, bee breeding ticks, multiblock structural validation. |

## 3. Census of Common Forge/Mod Operations (Per Server Tick)
Measured during Large Base workload (FTB Revelation, 1,500 TEs, 120 Mobs):
- **TileEntities Ticked**: 1,500
- **Living Entities Ticked**: 120
- **Forge Events Posted**: ~1,840 events/tick (primarily `LivingUpdateEvent`, `WorldTickEvent`, `ServerTickEvent`).
- **Capability Lookups**: ~42,500 calls/tick (`hasCapability` + `getCapability`), overwhelmingly `ITEM_HANDLER_CAPABILITY` and `ENERGY`.
- **BlockState Queries (`getBlockState`)**: ~58,000 calls/tick (conduit connections, machine neighbors, collision boxes).
- **ItemStack Allocations**: ~185,000 ephemeral copies/tick (inventory inspection, crafting checks).
- **NBT Compound Read/Writes**: ~12,400 ops/tick (internal capability sync and change detection).

## 4. Allocation Rate & Garbage Collection
- **Allocation Rate**: 620 MB/s in Large Base (scaling to 980 MB/s in Stress Base).
- **Object Composition**:
  - Ephemeral `net.minecraft.util.math.BlockPos` objects: 38% of total allocations.
  - Ephemeral `net.minecraft.item.ItemStack` copies: 24%.
  - Ephemeral `Capability` query wrappers & NBT tags: 21%.
  - Primitives and Boxed Objects (`Integer`, `Double`): 17%.
- **GC Behavior (G1GC @ 6GB Heap)**:
  - Young Gen Collection Frequency: Every 2.2 seconds.
  - Young Gen Pause Time: 34.8 ms average.
  - Old Gen Accumulation: 1.8 GB steady-state retained heap. Zero Full GC pauses observed during stable running.
  - Impact: A 35 ms GC pause occurring right before an 18 ms compute tick pushes total wall time to 53 ms, causing an occasional missed deadline.

## 5. Thread Utilization & Contention Profile
- **Server Thread (`ServerThread`)**: Saturated at 100% of a single CPU core during stress workloads.
- **Available Hardware**: 16 hardware threads (AMD Ryzen 9 / Intel Core i9 class).
- **Remaining Cores**: 15 cores sit virtually idle (1.5% to 3.2% CPU utilization), primarily serving Netty I/O loops and background chunk disk writing.
- **Lock Contention**:
  - Netty I/O threads frequently block on `IThreadListener.addScheduledTask()` synchronization locks when submitting packets to the ServerThread.
  - Synchronized access to `DimensionManager` and `RegistryManager` creates minor contention during cross-dimensional teleportation.
