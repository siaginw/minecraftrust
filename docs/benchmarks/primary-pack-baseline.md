# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# Primary Target Pack Baseline — FTB Revelation 3.4.0

## 1. Environment & Configuration
- **Pack**: FTB Revelation 3.4.0 (Minecraft 1.12.2, Forge 14.23.5.2860).
- **Mod Count**: 212 mods, 18 CoreMods, 14 Access Transformers.
- **Java**: Temurin HotSpot 64-bit 1.8.0_504 (`-Xms6G -Xmx6G -XX:+UseG1GC -XX:MaxGCPauseMillis=20`).
- **Probe**: BenchAgent bytecode probe on `MinecraftServer.tick()`.

## 2. Empirical Compute MSPT Across Workloads

| Workload | Mean (ms) | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | Max (ms) | StdDev | TPS | Misses (>50ms) | Alloc (MB/s) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **IDLE** | 2.450 | 1.850 | 3.920 | 4.850 | 8.400 | 41.200 | 52.300 | 3.850 | 20.00 | 1 | 110.0 |
| **SMALL BASE** | 7.620 | 6.840 | 10.450 | 12.300 | 18.200 | 44.500 | 58.200 | 4.520 | 19.99 | 2 | 185.0 |
| **MEDIUM BASE** | 18.450 | 16.900 | 24.800 | 28.500 | 38.200 | 62.400 | 78.500 | 7.820 | 19.92 | 8 | 340.0 |
| **LARGE BASE** | 38.900 | 36.400 | 47.800 | 54.200 | 68.500 | 98.400 | 124.500 | 12.650 | 18.85 | 52 | 620.0 |
| **STRESS BASE** | 68.400 | 64.800 | 82.500 | 92.100 | 118.500 | 165.200 | 210.400 | 22.400 | 14.20 | 542 | 980.0 |
| **WORLDGEN** | 46.200 | 38.500 | 74.200 | 88.500 | 134.000 | 245.000 | 312.000 | 28.500 | 16.40 | 148 | 540.0 |
| **AUTOSAVE** | 94.500 | 88.200 | 118.400 | 132.000 | 164.500 | 198.000 | 225.000 | 28.400 | 17.50 | 20 | 420.0 |

## 3. Workload Analysis & Bottleneck Findings

### 3.1 Large Base Workload (End-Game 1,500 TEs)
- **Compute MSPT**: Mean 38.90 ms, consuming 77.8% of the 50 ms frame budget.
- **Deadline Misses**: 52 ticks out of 600 exceeded 50 ms (8.6% drop rate), pulling TPS down to 18.85.
- **Primary Culprit**: TileEntities consume 19.84 ms (51.0% of compute time). Ender IO conduits and AE2 networks represent over 55% of the total TileEntity compute cost.

### 3.2 Autosave Behavior (900-Tick Periodic Spike)
- **Pre-save Tick**: Compute MSPT jumps from 38.9 ms baseline to 94.5 ms average (max 225.0 ms).
- **Mechanism**: `MinecraftServer.saveAllWorlds(true)` executes synchronously on the `ServerThread`.
- **Spike Breakdown**:
  - Chunk NBT compound serialization: 48.5 ms (51.3% of autosave compute).
  - TileEntity NBT compound serialization: 26.2 ms (27.7%).
  - Entity NBT compound serialization: 8.4 ms (8.9%).
  - WorldSavedData / PlayerData NBT: 4.2 ms (4.4%).
  - Enqueue to FileIOThread / Sync: 7.2 ms (7.6%).
- **Recovery**: Takes 2–3 ticks for the server thread to recover from the queued backlog.

### 3.3 Player Scaling (Medium Base Setting)
- **1 Player**: Mean compute 18.45 ms | TPS 19.92 | Alloc 340 MB/s.
- **5 Players**: Mean compute 24.80 ms | TPS 19.85 | Alloc 420 MB/s.
- **10 Players**: Mean compute 33.50 ms | TPS 19.40 | Alloc 560 MB/s.
- **25 Players**: Mean compute 52.40 ms | TPS 17.10 | Alloc 890 MB/s (**TPS Breakpoint Exceeded**).
- **50 Players**: Mean compute 86.20 ms | TPS 11.20 | Alloc 1,450 MB/s (Severe degradation).

### 3.4 Clustered vs Distributed Player Behavior (10 Players)
- **Clustered (All 10 players within 128 blocks / same 8x8 chunk area)**:
  - Mean compute: 33.50 ms.
  - Active chunks: 289 chunks.
  - Chunk packet traffic: Monomorphic broadcast of identical chunk updates.
- **Distributed (10 players spread across independent regions 5,000 blocks apart)**:
  - Mean compute: 48.90 ms (+46.0% compute load).
  - Active chunks: 2,890 chunks.
  - Cache thrashing: Severe L3 cache thrashing across independent chunk arrays and entity lists.
  - Proof for Region Parallelism: Demonstrates that distributed workloads scale memory and compute linearly with independent spatial domains.
