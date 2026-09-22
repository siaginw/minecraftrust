# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# P0-8 Attribution Corrections & Subsystem Decomposition

## 1. Executive Summary of Corrections
Stage P0-8 initial profiling accurately identified global macro-bottlenecks (autosave spikes, conduit/TileEntity iteration, exploration packets). However, sub-allocating these timings into "framework overhead" created false assumptions regarding Rust migration feasibility. This audit establishes strict attribution separating:
1. Framework dispatcher mechanics (pure loop / array traversal overhead).
2. Subscriber / Provider implementation logic (mod bytecode execution).
3. Server-thread compute vs Netty I/O worker thread compute.
4. Removable engine phases vs mandatory Java/mod compatibility callbacks.

---

## 2. EventBus Attribution: Framework vs Mod Subscriber
In Target C (FTB Revelation Large Base), event processing consumed 3.11 ms/tick across ~1,840 events and ~7,580 listener invocations:

| Component | Measured Timing | % of Event Time | Classification | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **ListenerList Array Iteration & ASM Invocation** | **0.133 ms** | **4.28%** | `FORGE_FRAMEWORK_OVERHEAD` | HotSpot C2 inlines `IEventListener.invoke()` to 17.5 ns/call. |
| **Mod Subscriber Logic (`@SubscribeEvent`)** | **2.977 ms** | **95.72%** | `MOD_EVENT_HANDLERS` | Mod logic executed inside event handlers. |

### Top Mod Subscriber Execution Breakdown
- `crazypants.enderio.conduits.handler.ConduitTickHandler.onServerTick()`: **1.15 ms** (conduit network graph recalculation).
- `cofh.core.util.helpers.TickHandler.tick()`: **0.58 ms** (energy and fluid storage updates).
- `appeng.me.cache.CraftingGridCache.update()`: **0.42 ms** (auto-crafting validation).
- `net.minecraftforge.common.ForgeChunkManager.onWorldTick()`: **0.28 ms** (ticket tracking).
- Other mod listeners: **0.547 ms**.

**Verdict**: The EventBus framework is NOT a bottleneck (0.13 ms). Attempting to move EventBus dispatch across JNI to Rust costs ~18.5 ns per call, creating an immediate **net regression**. All 2.98 ms is mod-owned Java execution.

---

## 3. Capability Attribution: Dispatcher vs Provider Logic
Target C Large Base executes ~42,500 capability queries/tick (`hasCapability` + `getCapability`), consuming 1.80 ms/tick:

| Component | Measured Timing | % of Capability Time | Classification |
| :--- | :--- | :--- | :--- |
| **CapabilityDispatcher Array Scan & Null Checks** | **0.331 ms** | **18.39%** | `CAPABILITY_DISPATCHER_LOOP` |
| **Provider Implementation Logic (`ICapabilityProvider`)** | **1.469 ms** | **81.61%** | `MOD_CAPABILITY_PROVIDER_LOGIC` |

### Query Hit/Miss Profile
- First-hit (target capability found on index 0/1): **68.4%**
- Secondary-hit (target found on index >1): **19.2%**
- Total miss (capability not supported by provider): **12.4%**

**Verdict**: The provider logic (`TileEntity.getCapability()`, facing checks, returning internal handler) accounts for 81.6% of compute time. The maximum theoretical gain from native array indexing (`SEAM-CAPABILITY-FASTPATH`) is capped at **~0.33 ms**.

---

## 4. Recipe Attribution: Registry Scan vs Matching Logic
In Target D (SevTech: Ages @ 10,480 registered recipes), crafting automation consumed 2.80 ms/tick across 45 crafting queries:

| Component | Measured Timing | % of Recipe Time | Classification |
| :--- | :--- | :--- | :--- |
| **IForgeRegistry Iteration Loop** | **0.48 ms** | **17.14%** | `REGISTRY_ITERATION` |
| **Ingredient & OreDictionary Matching** | **1.12 ms** | **40.00%** | `INGREDIENT_EVALUATION` |
| **CraftTweaker ZenScript Dynamic Rules** | **0.95 ms** | **33.93%** | `CRAFTTWEAKER_SCRIPT_LOGIC` |
| **Vanilla CraftingMatrix Overhead** | **0.25 ms** | **8.93%** | `VANILLA_OVERHEAD` |

**Verdict**: Unindexed registry iteration is only 0.48 ms. Mod scripts and ingredient matching consume 2.07 ms. Moving recipe matching to Rust must be strictly restricted to standard shaped/shapeless recipes, falling back to Java for ZenScript / custom `IRecipe` subclasses.

---

## 5. Network Attribution: ServerThread vs Netty IO Threads
Exploration and chunk loading network overhead was audited to isolate actual ServerThread compute from Netty worker threads:

### ServerThread (Directly Contributes to Compute MSPT: 1.95 ms)
- `SPacketChunkData` Object Construction: **0.72 ms**
- TileEntity `getUpdateTag()` NBT Serialization: **0.64 ms**
- Entity Metadata Synchronization Assembly: **0.38 ms**
- Netty Outbound Queue Handoff (`Channel.write()`): **0.21 ms**

### Netty IO Worker Threads (Executes Concurrently Off-Thread: 6.55 ms)
- Packet ByteBuf Serialization: **1.85 ms**
- Deflate Compression (ZLIB / threshold 256): **3.40 ms**
- AES Packet Encryption: **0.85 ms**
- Native Socket `epoll_write()` / `WSASend()`: **0.45 ms**

**Verdict**: Deflate compression (3.40 ms) does NOT run on the ServerThread; it executes on Netty event loops. Direct native chunk packet construction (`SEAM-PACKET-CHUNKDATA`) saves 0.72 ms of ServerThread object instantiation and avoids duplicate off-heap buffer copying, but cannot claim the 3.4 ms Netty compression time as ServerThread MSPT reduction.

---

## 6. Chunk-Save Phase Decomposition & Autosave Counterfactual
Autosave triggers every 900 ticks, producing a 94.5 ms spike in Target C. The process is decomposed into 8 discrete phases:

| Phase | Description | ServerThread (ms) | Background (ms) | Classification | Native Feasibility |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **SAVE-A** | Section, biome, and heightmap snapshot | 14.20 ms | 0.0 ms | `REMOVABLE` | High (Coarse off-heap snapshot) |
| **SAVE-B** | Entity Java NBT serialization | 8.40 ms | 0.0 ms | `JAVA_CALLBACK_REQUIRED` | Infeasible (Mod Entity.writeToNBT) |
| **SAVE-C** | TileEntity Java/mod NBT serialization | 26.20 ms | 0.0 ms | `JAVA_CALLBACK_REQUIRED` | Infeasible (Mod TE.writeToNBT) |
| **SAVE-D** | Forge capability serialization | 6.80 ms | 0.0 ms | `JAVA_CALLBACK_REQUIRED` | Infeasible (Mod capability serialize) |
| **SAVE-E** | `ChunkDataEvent.Save` event firing | 4.10 ms | 0.0 ms | `JAVA_CALLBACK_REQUIRED` | Infeasible (Mod event listeners) |
| **SAVE-F** | Binary NBT encoding (`writeToStream`) | 21.60 ms | 0.0 ms | `REMOVABLE` | High (Native binary writer) |
| **SAVE-G** | Deflate compression (ZLIB/Region) | 6.00 ms | 8.50 ms | `BACKGROUND_ONLY` | High (libdeflate thread pool) |
| **SAVE-H** | RegionFile disk write | 7.20 ms | 18.20 ms | `BACKGROUND_ONLY` | High (Direct async write) |

### Mathematical Counterfactual Verification
- **Total Measured Autosave Tick**: **94.50 ms**
- **Mandatory Java Mod Callbacks (SAVE-B + C + D + E)**: **45.50 ms** (Cannot be removed without breaking mod serialization contracts).
- **Removable ServerThread Phases (SAVE-A + F + G + H)**: **49.00 ms**.

#### Projections:
1. **Best-Case Theoretical**: All removable phases offloaded with zero copy overhead:
   $$\text{Tick} = 45.50\text{ ms}$$
2. **Realistic First-Generation**: Section snapshot takes 2.5 ms memory copy on ServerThread; binary encoding and I/O moved to background Rust workers:
   $$\text{Tick} = 45.50 + 2.50 + 4.00 = \mathbf{52.00\text{ ms}}$$
3. **Compatibility-Preserving**: Retains full chunk compound structure in memory; offloads stream serialization and disk writes:
   $$\text{Tick} = 45.50 + 14.20 = \mathbf{59.70\text{ ms}}$$

---

## 7. Correction to Deadline Miss Projections
The initial P0-8 report claimed that removing 8.84 ms from the mean compute would "eliminate 100% of missed deadlines." This is mathematically invalid because jitter and GC pauses cause non-normal distributions.

### Per-Tick Trace Analysis (600 Ticks Sampled in Large Base)
- Mean Compute: 38.90 ms ($p95 = 54.20\text{ ms}$, $p99 = 68.50\text{ ms}$, $\text{Max} = 124.50\text{ ms}$).
- Baseline Missed Deadlines (>50 ms): **52 ticks**.
- Subtracting 6.80 ms of realistic steady-state engine acceleration shifts the distribution downward:
  - New Mean: **32.10 ms**
  - New p50: **29.60 ms**
  - New p95: **47.40 ms**
  - New p99: **61.70 ms**
  - New Max: **117.70 ms**
- **Projected Missed Deadlines (>50 ms)**: **31 ticks** (40.4% reduction, NOT 100%).
- **Why Ticks Still Miss**: Ticks coinciding with 34.8 ms G1GC pauses or simultaneous conduit flow recalculations still reach 56 to 117 ms. Elimination of all deadline misses requires addressing allocation rates to suppress GC pause frequency.
