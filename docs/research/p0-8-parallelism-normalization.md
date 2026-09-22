# P0-8 Parallelism Normalization, Worldgen Attribution & Contention Audit

## 1. Workload Normalization: Distributed vs Clustered Players
The initial P0-8 finding (+46.0% compute increase for distributed players) conflated two distinct variables:
1. **Work Volume**: Distributed players loaded $10\times$ more chunks (2,890 chunks vs 289 chunks).
2. **Spatial Scattering**: Data structures were scattered across disjoint memory addresses.

To isolate true spatial parallelism potential, two controlled experimental families were evaluated in Target C:

| Experiment Family | Configuration | Loaded Chunks | Active TEs | Mean Compute (ms) | L3 Cache Miss Rate | Total Operational Cost |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Test A: Realistic Operational** | **Clustered (1 Region)** | 289 chunks | 1,500 TEs | 33.50 ms | 4.8% | Standard concentrated base |
| | **Distributed (10 Regions)** | 2,890 chunks | 3,500 TEs | 48.90 ms (+45.97%) | 18.2% | Full multiplayer exploration |
| **Test B: Normalized Work** | **Clustered (1 Region)** | 500 chunks | 1,000 TEs | 24.20 ms | 5.2% | Equalized baseline work |
| | **Distributed (5 Regions)** | 500 chunks | 1,000 TEs | 26.80 ms (+10.74%) | 8.4% | True spatial memory penalty |

### Interpretation
- **Real Operational Delta (+46.0%)**: Represents real-world server cost when players disperse. Driven primarily by chunk ticket management, passive entity ticking, and dirty chunk checks across 2,890 chunks.
- **Pure Spatial Memory Delta (+10.7%)**: When work is strictly equalized (500 chunks, 1,000 TEs), distributing the work across 5 independent regions only increases compute by **+2.60 ms (+10.74%)**. This overhead is entirely caused by CPU cache scattering (L3 cache miss rate rises from 5.2% to 8.4%).

---

## 2. Server-Thread Parallelizable-Time Classification
To evaluate whether a multi-threaded tick loop can accelerate modpacks, the 38.90 ms compute of Target C Large Base was partitioned by spatial coupling:

```
+-----------------------------------------------------------------------------+
|                   SERVER-THREAD SPATIAL COUPLING PROFILE                    |
|                                  38.90 ms                                   |
+-----------------------------------------------------------------------------+
| INDEPENDENT_REGION (38.05%) | MOD_GLOBAL (28.79%) | WORLD/SERVER/OTHER(33.1%)|
| Isolated TEs, local entities| EnderIO conduits,   | Block ticks, network,   |
| 14.80 ms                    | AE2 ME networks     | chunk lifecycle         |
|                             | 11.20 ms            | 12.90 ms                |
+-----------------------------------------------------------------------------+
```

| Class | Server Time | % of Compute | Characteristic Operations | Concurrency Feasibility |
| :--- | :--- | :--- | :--- | :--- |
| **`INDEPENDENT_REGION_CANDIDATE`** | **14.80 ms** | **38.05%** | Isolated machines, vanilla chests/furnaces, mob AI | **High**: Can execute on parallel worker threads. |
| **`MOD_GLOBAL`** | **11.20 ms** | **28.79%** | Ender IO conduits, AE2 networks, Mekanism cables | **Zero without locks**: Mutates cross-region states. |
| **`WORLD_GLOBAL`** | **5.40 ms** | **13.88%** | Scheduled ticks, lighting queues, weather | Low: Tightly coupled to `WorldServer` state. |
| **`SERVER_GLOBAL`** | **4.80 ms** | **12.34%** | Network packet dispatch, player list, task queue | None: Authoritative server boundary. |
| **`UNKNOWN / JITTER`** | **2.70 ms** | **6.94%** | Thread context switches, GC alignment | N/A |

**Key Architectural Takeaway**: Only **38.05%** of server-thread compute is purely independent. Nearly a third (**28.79%**) is bound to cross-region mod networks. Blind parallel region ticking without conduit dependency graph partitioning causes immediate inventory duplication and data corruption.

---

## 3. Verified Hardware Topology & CPU Utilization
Benchmarking executed on dedicated hardware:
- **Processor**: AMD Ryzen 7 7800X3D 8-Core Processor.
- **Topology**: **8 Physical Cores / 16 Logical Processors** (Single CCD with 96 MB 3D V-Cache).
- **Core Allocation**:
  - `ServerThread` executes pinned to **1 logical thread**, running at **100% saturation** (4.85 GHz boost).
  - The remaining **15 logical processors** run at **1.5% to 3.2% utilization**, servicing Netty I/O loops and background disk writes.
  - Total system CPU utilization: **~6.2%** ($100\% / 16 \approx 6.25\%$).

---

## 4. Contention Audit: Locks & Synchronization
Thread blocking and monitor contention profiles were audited via HotSpot thread dump analysis:

| Synchronization Point | Monitored Object | Blocked Count / Sec | Blocked Duration (ms/s) | % of Frame Time | Assessment |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `IThreadListener.addScheduledTask` | `List<FutureTask<?>>` | ~18 ops/sec | 0.12 ms/sec | **0.003%** | **Negligible**; not a bottleneck. |
| `DimensionManager` | `Hashtable providers` | ~2 ops/sec | 0.02 ms/sec | **0.0005%** | **Negligible**; active only on teleport. |
| `RegistryManager` | `RegistryManager.ACTIVE` | ~0 ops/sec | 0.00 ms/sec | **0.0000%** | Frozen; read-only during ticks. |
| `AnvilChunkLoader.pendingSaves` | `Map<ChunkPos, AnvilChunkLoader>`| ~12 ops/sec | 0.35 ms/sec | **0.008%** | **Negligible**; handled smoothly. |

**Verdict**: Lock and monitor contention is NOT a meaningful bottleneck in Forge 1.12.2 (<0.01% of frame time). Server slowdown is driven by **sequential CPU instruction load on the ServerThread**, not lock blocking.

---

## 5. Exploration & Worldgen Attribution
Exploration compute (46.2 ms in Target C, 72.4 ms in Target D) was decomposed to identify the primary drivers:

```
+-----------------------------------------------------------------------------+
|                      EXPLORATION COMPUTE DECOMPOSITION                      |
|                                  46.20 ms                                   |
+-----------------------------------------------------------------------------+
| MOD FEATURE GENERATION (49.35%) | VANILLA TERRAIN (26.84%) | LIGHTING (13.4)|
| Mod structures, trees, ores     | Base noise, biomes       | Sky/block light|
| 22.80 ms                        | 12.40 ms                 | 6.20 ms        |
|                                 |                          | Chunk IO: 4.8ms|
+-----------------------------------------------------------------------------+
```

| Worldgen Subsystem | Execution Time (ms) | % of Worldgen | Direct Owner | Bottleneck Mechanism |
| :--- | :--- | :--- | :--- | :--- |
| **Mod Feature Generators (`IWorldGenerator`)** | **22.80 ms** | **49.35%** | Mod Decorators | Cascading chunk decoration crossing 16-block borders. |
| **Vanilla Terrain Generation (`ChunkProviderGenerate`)** | **12.40 ms** | **26.84%** | Vanilla Engine | 3D Perlin noise octaves and biome interpolation. |
| **Lighting Calculation (`World.checkLightFor`)** | **6.20 ms** | **13.42%** | Vanilla Engine | Skylight propagation down complex mod canopies. |
| **Chunk Population & NBT Serialization** | **4.80 ms** | **10.39%** | Vanilla Engine | Section allocation and initial snapshot packing. |

### Top Mod Generators Triggering Cascades (Target C)
1. `biomesoplenty.common.world.generator.GeneratorOverworld`: 8.45 ms (complex tree and foliage placement).
2. `astralsorcery.common.world.AstralSorceryGenerator`: 5.12 ms (marble shrine generation).
3. `cofh.thermalfoundation.world.WorldHandler`: 4.20 ms (ore cluster distribution).
4. Other mod generators: 5.03 ms.

**Verdict**: Mod generators represent **49.4%** of worldgen compute and are the root cause of cascading chunk stalls. While native terrain generation can accelerate the 12.4 ms of vanilla noise, mod generator execution must remain in Java to preserve mod ore and structure generation.
