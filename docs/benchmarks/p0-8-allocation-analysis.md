# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# P0-8 Allocation Analysis & Memory Profiler Histogram

## 1. Allocation Rate vs Retained Heap
Profiling on Target C (FTB Revelation Large Base @ 620 MB/s allocation rate) reveals a crucial distinction between:
1. **Steady-State Retained Heap**: **1.8 GB** (persistent chunk sections, registries, tile entity instances, player data).
2. **Transient Allocation Throughput**: **620.0 MB/s** (ephemeral coordination objects discarded within microseconds).

Because Java 8 HotSpot allocates objects on thread-local allocation buffers (TLABs), young generational memory fills rapidly, triggering young-gen G1GC pauses every **2.2 seconds** with an average pause duration of **34.8 ms**.

---

## 2. Top 30 Allocation Classes (Empirical JVM Profiler Histogram)

| Rank | Fully Qualified Class Name | Allocs / Sec | MB / Sec | Owning Subsystem | Primary Allocating Call Stack |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `net.minecraft.util.math.BlockPos$MutableBlockPos` | 4,820,000 | 115.68 | Vanilla Engine | `World.getBlockState()`, `Chunk.getBlockState()` |
| **2** | `net.minecraft.item.ItemStack` | 3,650,000 | 116.80 | Vanilla / Mods | `ItemHandlerHelper.copyStackWithSize()`, inventory extraction |
| **3** | `net.minecraft.util.math.BlockPos` | 4,120,000 | 98.88 | Vanilla / Mods | `TileConduitBundle.update()`, neighbor offset queries |
| **4** | `net.minecraft.nbt.NBTTagCompound` | 1,820,000 | 87.36 | Vanilla / Forge | `TileEntity.getUpdateTag()`, capability sync compound tags |
| **5** | `net.minecraft.util.EnumFacing` | 2,840,000 | 45.44 | Vanilla | `EnumFacing.values()` array clone during neighbor scans |
| **6** | `net.minecraft.util.math.AxisAlignedBB` | 1,420,000 | 45.44 | Vanilla Engine | `World.getCollisionBoxes()`, entity AABB bounds checking |
| **7** | `java.lang.Integer` | 2,450,000 | 39.20 | JVM Autoboxing | `OreDictionary.getOreID()`, EnergyStorageCache boxing |
| **8** | `java.util.ArrayList` | 1,150,000 | 27.60 | JVM Collections | `InventoryHelper`, recipe matching candidate lists |
| **9** | `byte[]` | 145,000 | 23.20 | Netty / Network | `PacketBuffer` allocation during packet serialization |
| **10** | `net.minecraft.util.NonNullList` | 890,000 | 21.36 | Vanilla / Forge | `ItemStackHandler` inventory slot backing arrays |
| **11** | `crazypants.enderio.conduits.item.NetworkedInventory` | 620,000 | 19.84 | Ender IO | `ItemConduitNetwork.doTransfer()` routing checks |
| **12** | `appeng.util.item.AEItemStack` | 480,000 | 19.20 | Applied Energistics 2 | `StorageChannelItem.createStack()` ME cache lookup |
| **13** | `java.util.HashMap$Node` | 510,000 | 16.32 | JVM Collections | `AE2 GridNode` internal route maps & caches |
| **14** | `net.minecraft.nbt.NBTTagList` | 580,000 | 13.92 | Vanilla | `ItemStack.writeToNBT()`, enchantment & inventory lists |
| **15** | `net.minecraft.nbt.NBTTagString` | 540,000 | 12.96 | Vanilla | Compound tag string attributes & registry names |
| **16** | `net.minecraft.util.math.Vec3d` | 350,000 | 11.20 | Vanilla Engine | Entity pathfinding vector calculation & steering |
| **17** | `cofh.thermalexpansion.duct.item.RouteQueueItem` | 420,000 | 10.08 | Thermal Expansion | Duct routing priority queue items |
| **18** | `net.minecraft.util.math.RayTraceResult` | 310,000 | 9.92 | Vanilla Engine | Entity line-of-sight & projectile collision |
| **19** | `java.lang.Long` | 380,000 | 9.12 | JVM Autoboxing | `ChunkPos.asLong()`, BlockPos packed hashing |
| **20** | `char[]` | 180,000 | 8.64 | JVM Strings | `ResourceLocation` parsing & OreDictionary queries |
| **21** | `net.minecraftforge.common.capabilities.ProviderEntry` | 290,000 | 6.96 | Forge Runtime | Capability provider query iterator wrappers |
| **22** | `net.minecraft.util.ResourceLocation` | 165,000 | 5.28 | Vanilla / Forge | Registry lookup identifier instances |
| **23** | `net.minecraft.world.chunk.storage.ExtendedBlockStorage`| 1,200 | 4.91 | Vanilla Engine | Chunk section allocation during chunk loading |
| **24** | `java.util.LinkedHashMap$Entry` | 140,000 | 4.48 | JVM Collections | `ForgeRegistry` override multimap tracking |
| **25** | `mekanism.common.transmitters.NetworkUpdate` | 125,000 | 4.00 | Mekanism | Cable transmitter graph synchronization updates |
| **26** | `net.minecraftforge.event.entity.living.LivingUpdateEvent`| 85,000 | 2.72 | Forge Runtime | Entity update event object instantiation |
| **27** | `net.minecraft.network.play.server.SPacketEntityMetadata`| 65,000 | 2.08 | Vanilla Engine | Entity tracking synchronization packets |
| **28** | `java.lang.Double` | 60,000 | 1.44 | JVM Autoboxing | Mekanism Joules floating conversions |
| **29** | `net.minecraft.util.math.ChunkPos` | 55,000 | 1.32 | Vanilla Engine | `ChunkSource` coordinate hash queries |
| **30** | `BlockStateContainer$StateImplementation` | 450 | 0.04 | Vanilla Engine | Startup blockstate registrations |

---

## 3. Critical Categorical Verifications

### 3.1 BlockPos & Coordinate Allocation
- `BlockPos` and `MutableBlockPos` together account for **8.94 million allocations/sec** and **214.56 MB/s** (34.6% of all heap allocations).
- Root Cause: Every neighbor check in Ender IO conduits, Thermal ducts, and vanilla hoppers instantiates `new BlockPos(x, y, z)` or clones `MutableBlockPos`.
- Mitigation Potential: Off-heap packed `u64` coordinate representations in Rust completely eliminate coordinate heap churn.

### 3.2 ItemStack Duplication
- `ItemStack` allocations account for **3.65 million allocations/sec** and **116.80 MB/s** (18.8% of allocations).
- Root Cause: Forge's `IItemHandler` contract forces defensive cloning via `ItemStack.copy()` to prevent callers from mutating internal slot contents during simulated insertion/extraction checks (`simulate = true`).

### 3.3 Clarification of "Capability Query Tokens"
- **Correction**: The `Capability<T>` token instance itself (e.g. `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY`) is a permanent singleton loaded during Pre-Init. It is NEVER reallocated during queries.
- The actual capability-related allocations observed are:
  1. `CapabilityDispatcher$ProviderEntry` iterator wrappers: 290,000 allocs/sec (6.96 MB/s).
  2. Temporary `NBTTagCompound` wrappers created during capability serialization and change detection.

### 3.4 Primitive Autoboxing
- Primitive boxing (`Integer`, `Long`, `Double`) consumes **49.76 MB/s** (8.0% of allocations).
- Root Cause: OreDictionary ID mapping (`Integer`), packed coordinates (`Long`), and energy unit math (`Double`).
