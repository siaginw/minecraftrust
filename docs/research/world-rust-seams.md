# Research: World & Block Storage Migration Seam Analysis (WORLD-1 to WORLD-8)

## 1. Seam Catalog Overview
To evaluate where authoritative world ownership can transition from Java to Rust without breaking Forge mod compatibility, we assess eight candidate migration seams:

| Seam ID | Description | Direction | Granularity | TPS Impact | Compatibility Risk | Recommendation |
|---------|-------------|-----------|-------------|------------|--------------------|----------------|
| **WORLD-1** | Coordinate math & packed long codec in Rust | Dual | Primitive scalar | Negligible (~0.5 ns) | Low | Approved (in `core-types`) |
| **WORLD-2** | Native Chunk Section Storage (Flat `[u16; 4096]`) | Rust Authoritative | Coarse (Section) | High (cache locality) | Medium (EBS reflection) | Candidate for Stage P1 |
| **WORLD-3** | Per-Block JNI Crossing (`getBlockState`) | Java -> Rust | Fine-grained scalar | Catastrophic (-24x) | High (hot-loop penalty) | **REJECTED** |
| **WORLD-4** | Shared Memory Chunk Snapshot (Read-Only) | Rust -> Java | Coarse buffer | High (zero-copy query) | Low | **LEADING RESEARCH CANDIDATE** |
| **WORLD-5** | Native Chunk Packet Serialization (`SPacketChunkData` offload) | Rust Authoritative | Coarse packet batch | High (~18.9 µs/chunk assembly saved; up to ~182 µs if avoiding block-by-block scans) | Low | **LEADING RESEARCH CANDIDATE** |
| **WORLD-6** | Native Heightmap & Skylight Occlusion Tracker | Rust Authoritative | Section/Chunk | Medium (~5 µs/set saved) | Medium | Research Candidate |
| **WORLD-7** | Native Block Mutation Batch Engine (`MutationBatch`) | Java -> Rust | Coarse batch | High (worldgen/automation) | Medium (requires event sync) | Research Candidate |
| **WORLD-8** | Full Authoritative World in Rust | Rust Authoritative | Entire Subsystem | Maximum | Extreme (breaks all mods) | Long-term target only |

## 2. In-Depth Evaluation of Critical Seams

### WORLD-3: Per-Block JNI Crossing — REJECTED
- **Mechanism**: Replace Java `Chunk.getBlockState` with a direct JNI native call into Rust memory for every block read.
- **Empirical Evidence (§40)**:
  - Java local read: **3.31 ns**
  - JNI call overhead: **~15.2 ns**
  - Per-block JNI total: **~20.0 ns** (6x slower than Java)
  - Bulk scan of 4,096 blocks: **81.92 µs** (JNI) vs **3.34 µs** (bulk buffer) -> **24.5x penalty**.
- **Conclusion**: Fine-grained per-block JNI in hot loops is strictly forbidden by project architectural invariants.

### WORLD-4: Shared Memory Section Snapshot — LEADING RESEARCH CANDIDATE
- **Mechanism**: Rust maintains flat 8 KB direct memory buffers per section (`[u16; 4096]`). Java reads from direct buffer handle via `sun.misc.Unsafe` or off-heap pointer.
- **Benefits**:
  - Eliminates Java pointer chasing (`storageArrays -> EBS -> data -> storage -> longArray`).
  - Read latency in Java is equal to direct array index (~1-2 ns).
  - Enables zero-copy handoff to Netty chunk serialization.

### WORLD-5: Chunk Packet Offload (NET-8 / WORLD-5 Synergy) — LEADING RESEARCH CANDIDATE
- **Mechanism**: Rust formats `SPacketChunkData` directly from native section memory, only querying Java for TileEntity NBT update tags (`tileentity.getUpdateTag()`).
- **Timing Reconciliation**:
  - Direct 16-section `writePacketData` buffer extraction: **6.59 µs** (measured).
  - 64 TileEntity NBT update tag serialization: **12.29 µs** (measured).
  - Total reference server-thread packet assembly: **~18.9 µs** per full chunk.
  - (Note: The ~182.4 µs figure measured in early profiling reflects a full 65,536 block-by-block `getBlockState()` traversal, not the optimized contiguous buffer write).
- **Benefits**: Saves ~18.9 µs of server-thread time per chunk packet, completely decoupling chunk network payload preparation from the tick loop while invoking Java only for TileEntity NBT sync.
