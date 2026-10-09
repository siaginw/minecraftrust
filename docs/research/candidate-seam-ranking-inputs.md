# Candidate Seam Re-Ranking & P0-8 Empirical Inputs

## 1. Ground Truth from Real Modpack Profiling
The P0-8 integration baselines reveal two fundamental truths that supersede speculative micro-optimizations:
1. **The In-JVM Hot Path Fallacy**: Micro-optimizing operations that already take <25 ns in Java (such as `EventBus.post()` at 15.4 ns or `OreDictionary.getOreID()` at 3.8 ns) by moving them to Rust is **counter-productive**. JNI boundary crossing costs ~18.5 ns per call, guaranteeing a net performance regression.
2. **The Macro-Work Unit Reality**: The operations that actually destroy TPS in real modpacks are:
   - **Autosave spikes** (94.5 ms to 142.0 ms synchronous stall serializing thousands of NBT compounds).
   - **Chunk packet encoding** (182 µs per packet streaming across network threads).
   - **Conduit & TileEntity capability thrashing** (42,500 linear scans per tick).
   - **Region memory thrashing** from single-threaded server world execution.

## 2. Re-Scored Seams Catalog

### Tier 1: Highest Value Engine Accelerators (P1 Implementation Candidates)

#### SEAM-CHUNK-SAVE: Off-Heap Chunk Serialization & Direct Region Encoding
- **Current Cost**: 94.5 ms (FTB) and 142.0 ms (SevTech) synchronous server-thread spike every 900 ticks.
- **Mechanism**: Move chunk NBT packing and raw byte compression to native background worker threads using coarse `ChunkSnapshot` buffers.
- **Estimated Headroom**: Eliminates 80–120 ms from the autosave spike, flattening autosave latency to <15 ms.
- **Feasibility**: High. Chunk data can be snapshot coarsely without per-block JNI.

#### SEAM-PACKET-CHUNKDATA: Direct Native SPacketChunkData Construction
- **Current Cost**: 182 µs per chunk packet; up to 5.8 ms/tick during worldgen flight or multi-player login.
- **Mechanism**: Direct extraction of 16-bit blockstate arrays into pre-allocated off-heap network buffers with hardware-accelerated libdeflate.
- **Estimated Headroom**: 3.0 to 5.0 ms saved during exploration and player movement.
- **Feasibility**: High. Clean protocol boundary; verified in P0-5.

### Tier 2: Forge Runtime Enhancements (Moderate Headroom)

#### SEAM-CAPABILITY-FASTPATH: Capability Dispatch Flat Token Indexing
- **Current Cost**: 42,500 capability queries/tick consuming ~1.8 ms of server-thread compute.
- **Mechanism**: Replace linear array iteration in `CapabilityDispatcher` with a dense integer-indexed array lookup.
- **Estimated Headroom**: 1.0 to 2.0 ms saved in dense conduit/machine networks.
- **Feasibility**: Moderate. Requires ASM patch to `CapabilityDispatcher` in Java.

#### SEAM-RECIPE-INDEXING: Native Indexed Recipe Matrix Lookup (Indexable Subset)
- **Current Cost**: Unindexed linear scan across 10,480 recipes in SevTech taking up to 1,173 ns per lookup.
- **Mechanism**: Maintain a hash index in Rust for standard shaped/shapeless recipes, falling back to Java for complex script recipes.
- **Estimated Headroom**: 0.5 to 1.5 ms during heavy auto-crafting.
- **Feasibility**: Moderate. Scoped strictly to indexable subset.

### Tier 3: Demoted / Low-Value Candidates (Do Not Migrate to Rust)

#### SEAM-EVENTBUS-PRIMITIVE: EventBus Invocation Native Dispatch
- **Status**: **DEMOTED (NEGATIVE VALUE)**.
- **Reason**: In-JVM dispatch takes 15.41 ns. Moving event firing across JNI costs 18.5 ns + parameter marshaling. Results in measurable slowdown.

#### SEAM-OREDICTIONARY-NATIVE: Native OreDictionary Bimap Replacement
- **Status**: **DEMOTED (NEGATIVE VALUE)**.
- **Reason**: In-JVM hash lookup takes 3.87 ns to 15.08 ns. JNI crossing penalty exceeds total operation time.

#### SEAM-TILEENTITY-NATIVE-MIGRATION: Rewriting TileEntities in Rust
- **Status**: **DEMOTED (UNFEASIBLE / ECOSYSTEM INCOMPATIBLE)**.
- **Reason**: TileEntity logic is mod-owned. Rewriting in Rust requires rewriting hundreds of closed-source or unmaintained Java mods. Violates Core Invariant 3.
