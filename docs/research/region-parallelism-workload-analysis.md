# Region Parallelism & Multi-Threaded Workload Analysis

## 1. Spatial Independence in Minecraft Worlds
Minecraft worlds are partitioned into $32 \times 32$ chunk regions ($512 \times 512$ blocks), corresponding to individual `.mca` files.
- In single-player and clean vanilla worlds, interaction across regions is nearly zero outside of player movement and redstone lines crossing borders.
- On modern multi-core servers (e.g. 16 cores / 32 threads), running an entire world on a single CPU thread wastes over 90% of available compute capacity.

## 2. Empirical Evidence: Clustered vs Distributed Workloads
In P0-8, a 10-player workload was profiled under two distinct spatial distributions in Target C (FTB Revelation):

| Configuration | Active Regions | Active Chunks | Mean Compute MSPT | L3 Cache Miss Rate | Server Thread CPU | Total CPU Utilization |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Clustered (1 Region)** | 1 region | 289 chunks | 33.50 ms | 4.8% | 100% (1 core) | 6.2% (System) |
| **Distributed (10 Regions)** | 10 regions | 2,890 chunks | 48.90 ms (+46.0%) | 18.2% | 100% (1 core) | 6.2% (System) |

### Key Findings
1. **Linear Compute Scaling**: As players scatter across independent regions, compute duration increases nearly linearly with the number of disjoint chunk clusters.
2. **Cache Thrashing**: Iterating through 2,890 widely scattered chunks and 3,500 TileEntities on a single thread exhausts the CPU L3 cache (cache misses increase from 4.8% to 18.2%), causing substantial memory stalls.

## 3. Parallel Region Ticking: Architectural Prospects & Mod Hazards

### The Opportunity
If 10 independent regions could be ticked concurrently across 8 worker threads:
$$\text{Ideal Compute MSPT} \approx \frac{48.90\text{ ms}}{8} \approx 6.1\text{ ms}$$
This would provide a **$6\times$ to $8\times$ throughput multiplier**, allowing 50+ players to explore without dropping TPS.

### The Modded Forge Hazards (Why Blind Parallelism Fails)
1. **Cross-Region Conduits & Teleporters**: Mods like Ender IO, Mekanism (Quantum Entangloporter), and AE2 (Quantum Network Bridge) link distant TileEntities across regions. If Region A updates an item conduit that pushes items into an inventory in Region B concurrently, a thread race condition and inventory dupe bug occurs.
2. **Thread-Unsafe Mod Singletons**: Many mods use static non-thread-safe caches (e.g., `public static NBTTagCompound sharedBuffer` or shared recipe caches). Calling `TileEntity.update()` concurrently from multiple threads corrupts memory.
3. **World Mutation**: TileEntities frequently call `world.setBlockState()`, `world.spawnEntity()`, and `world.markChunkDirty()`. In vanilla Forge, `WorldServer` is strictly single-threaded.

### Recommended Architectural Strategy (P1/P2 Roadmap)
1. **Spatial Work Partitioning**: Group connected chunks into disjoint spatial islands.
2. **Cross-Island Dependency Tracking**: Conduits crossing islands force island merging into a single sequential work unit.
3. **Coarse Synchronization Barrier**:
   - Phase 1: Parallel Read-Only Environment Queries.
   - Phase 2: Region-Isolated TileEntity & Entity Ticking.
   - Phase 3: Synchronized Cross-Region Packet & Mutation Flushing on Server Thread.
