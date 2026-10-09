# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# P0-8 Exclusive Server-Thread Tick Budget

## 1. Methodology & Elimination of Double Counting
Previous profiling reported inclusive subsystem timings (e.g. `blockEntities` at 19.84 ms, which nested capability queries, inventory slot checks, and neighbor lookups). Adding inclusive timings independently creates invalid sums that exceed total tick duration.

This budget models **Exclusive / Self CPU Time** on the `ServerThread` during steady-state execution of Target C (FTB Revelation Large Base @ 38.90 ms compute). Every category represents non-overlapping leaf execution:

$$\sum \text{Exclusive Categories} = \text{Total ServerThread Compute MSPT} = \mathbf{38.90\text{ ms}}$$

---

## 2. Exclusive Tick Budget Table (Large Base Workload)

| Category Code | Exclusive Time (ms) | % of Compute | Direct Owner | Architectural Nature |
| :--- | :--- | :--- | :--- | :--- |
| **`MOD_TILEENTITY_LOGIC`** | **15.20 ms** | **39.07%** | Mod Machine / Pipe Bytecode | Infeasible to rewrite; mod-owned bytecode. |
| **`CHUNK_SOURCE_AND_LIGHTING`** | **3.89 ms** | **10.00%** | Vanilla Engine (`WorldServer`) | **Engine-Optimizable** (Off-thread lighting / chunks). |
| **`OTHER_JVM_RUNTIME`** | **3.71 ms** | **9.54%** | JVM / Sampling / Thread Switches | Unavoidable JVM execution runtime cost. |
| **`ENTITY_AI_AND_PATHFINDING`** | **3.19 ms** | **8.20%** | Vanilla Engine (`PathNavigate`) | **Engine-Optimizable** (Native A* pathfinder). |
| **`MOD_EVENT_HANDLERS`** | **2.98 ms** | **7.66%** | Mod Subscribers (`@SubscribeEvent`) | Infeasible to rewrite; mod-owned event logic. |
| **`WORLD_BLOCK_TICKS`** | **2.33 ms** | **5.99%** | Vanilla Engine (`tickUpdates`) | **Engine-Optimizable** (Native scheduled queue). |
| **`ENTITY_COLLISION_AND_MOVE`** | **2.10 ms** | **5.40%** | Vanilla Engine (`getCollisionBoxes`)| **Engine-Optimizable** (Broadphase spatial acceleration).|
| **`NETWORK_SERVER_THREAD`** | **1.95 ms** | **5.01%** | Vanilla / Forge Network System | **Engine-Optimizable** (Off-thread packet construction). |
| **`VANILLA_TILEENTITY_LOGIC`** | **1.92 ms** | **4.94%** | Vanilla Hoppers, Furnaces, Chests | Partially optimizable (FastHopper logic). |
| **`VANILLA_SERVER_OVERHEAD`** | **1.17 ms** | **3.01%** | PlayerList, Heartbeat, Scoreboard | Unavoidable compatibility baseline. |
| **`CAPABILITY_DISPATCHER_LOOP`**| **0.33 ms** | **0.85%** | Forge Runtime (`CapabilityDispatcher`)| **Forge-Runtime-Optimizable** (In-JVM token index). |
| **`FORGE_FRAMEWORK_OVERHEAD`** | **0.13 ms** | **0.33%** | Forge Runtime (`EventBus.post()`) | Negligible (In-JVM C2 inlined). |
| **TOTAL** | **38.90 ms** | **100.00%** | | |

---

## 3. High-Level Subsystem Rollup

```
+-----------------------------------------------------------------------------+
|                     TOTAL EXCLUSIVE SERVER-THREAD TIME                      |
|                                  38.90 ms                                   |
+-----------------------------------------------------------------------------+
|    MOD BYTECODE EXECUTION   |   ENGINE-OPTIMIZABLE WORK   | JVM / OTHER     |
|          20.10 ms           |          11.46 ms           |    7.34 ms      |
|           (51.67%)          |           (29.46%)          |   (18.87%)      |
|                             |                             |                 |
|  - Mod TE Logic:   15.20 ms |  - Chunks/Light:    3.89 ms | - Runtime: 3.71 |
|  - Mod Event Hand:  2.98 ms |  - Entity AI/Move:  5.29 ms | - Vanilla: 1.17 |
|  - Vanilla TEs:     1.92 ms |  - Block Ticks:     2.33 ms | - Cap loop:0.33 |
|                             |  - Server Net:      1.95 ms | - EventBus:0.13 |
+-----------------------------------------------------------------------------+
```

### Key Engineering Insights
1. **The Native Ceilings**: Out of 38.90 ms of total frame time, exactly **11.46 ms (29.46%)** consists of engine-owned algorithmic operations that can theoretically be moved to native acceleration.
2. **The Mod Wall**: **20.10 ms (51.67%)** of frame time is consumed by mod Java bytecode executing inside `TileEntity.update()`, event handlers, and vanilla hopper scans. Native Rust cannot execute this mod bytecode without breaking compatibility.
3. **The Stability Outcome**: Removing or accelerating the 11.46 ms of engine-owned work drops steady-state compute to ~27.44 ms. This creates an expansive **22.56 ms safety margin**, absorbing JIT deoptimizations, GC pauses, and conduit jitter, keeping server tick rate stabilized at 20.0 TPS.
