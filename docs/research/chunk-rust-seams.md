# Rust Seam & Boundary Architecture Analysis: Chunk Subsystem

## 1. Subsystem Performance Reality & Seam Evaluation
Based on empirical profiling of 847 reference chunks across all lifecycle operations, chunk loading and saving decompose into distinct compute and I/O domains:
- **Full Async Load Latency:** Mean $\approx 0.360\text{ ms}$ per chunk.
  - Background Worker (Region read, zlib inflate, NBT parse, section allocation): **98.1% of total latency** ($\approx 0.353\text{ ms}$).
  - Server Thread Finalization (Entity/TileEntity instantiation, capabilities, events): **1.9% of total latency** ($\approx 0.007\text{ ms}$).
- **Full Save Latency:** Mean $\approx 1.165\text{ ms}$ per chunk.
  - Server Thread Snapshot (object model $\rightarrow$ `NBTTagCompound` tree): **45.8% of compute** ($\approx 0.534\text{ ms}$).
  - Background Persistence (binary NBT encode, zlib deflate level 6, sector commit): **54.1% of compute** ($\approx 0.631\text{ ms}$).
  - Region File Allocation & Write I/O: **<0.1% of compute** ($\approx 0.001\text{ ms}$).

---

## 2. Six-Seam Comparative Matrix

| Evaluation Dimension | SEAM A: ZLIB Only | SEAM B: Binary NBT Codec Only | SEAM C: ZLIB + Binary NBT | SEAM D: RegionFile Sector Engine | SEAM E: RegionFile + ZLIB + NBT Worker | SEAM F: Full Async Worker Replacement |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Measured Java Cost** | 0.079 ms (inf) / 0.404 ms (def) | 0.044 ms (parse) / 0.027 ms (ser) | 0.123 ms (read) / 0.431 ms (write) | 0.019 ms (read) / 0.001 ms (write) | 0.142 ms (read) / 0.432 ms (write) | 0.353 ms (read) / 0.632 ms (write) |
| **% of Full Load Cost** | 22.0% | 12.2% | 34.2% | 5.3% | 39.5% | **98.1%** |
| **% of Server-Thread Load Cost** | 0% (Worker only) | 0% (Worker only) | 0% (Worker only) | 0% (Worker only) | 0% (Worker only) | 0% (Server thread load is 1.9%) |
| **Estimated FFI Crossings** | 2 per chunk | 2 per chunk | 1 per chunk | 2 per chunk | 1 per chunk | **1 per batch / chunk** |
| **Estimated Copy Bytes** | Compressed payload ($\approx 2.4\text{ KB}$) | Raw NBT ($\approx 50\text{ KB}$) | Compressed payload ($\approx 2.4\text{ KB}$) | Raw sector ($\approx 4\text{ KB}$) | Compressed buffer ($\approx 2.4\text{ KB}$) | Direct memory `ChunkSnapshot` |
| **Java Objects Still Required** | All NBT + Chunk objects | All NBT + Chunk objects | All Chunk objects | All NBT + Chunk objects | All Chunk objects | Only Entities, TEs, and Capabilities |
| **Mod / CoreMod Surface** | Zero | Zero | Zero | Minimal | Minimal | Low (preserves Forge events) |
| **Ownership Complexity** | Very Low | Low | Low | Medium | Medium | Medium-High |
| **Expected Benefit** | Minor CPU reduction | Negligible | Moderate background CPU drop | Improved disk safety / mmap | Substantial background CPU drop | **Substantially reduces JVM-side chunk decoding allocations & background CPU** |
| **Migration Reversibility**| Trivial | Trivial | High | High | High | Moderate |

---

## 3. Boundary Architecture Evaluation

### ARCHITECTURE 1: Java Chunk $\rightarrow$ Java NBT Tree $\rightarrow$ JNI $\rightarrow$ Rust Encode/Compress/Write
- **Avoided Work:** Moves binary NBT encoding and zlib compression to Rust.
- **Flaw:** Does **not** eliminate the expensive Java object allocation on the Server thread (`writeChunkToNBT` creating thousands of `NBTTagCompound` and `NBTTagList` objects). Server thread compute cost ($\approx 0.53\text{ ms}$) remains completely untouched.
- **Verdict:** Low return on investment.

### ARCHITECTURE 2: Java Exposes Coarse Chunk Data $\rightarrow$ Rust Builds Binary NBT Directly $\rightarrow$ Compresses/Writes
- **Avoided Work:** Eliminates intermediate Java `NBTTagCompound` tree generation. Java exposes raw references to `ExtendedBlockStorage` block byte arrays, biome bytes, and heightmaps. Rust writes binary NBT directly into an output buffer.
- **Advantage:** Cuts Server-thread save snapshot time by $>70\%$, while background compression runs at native speed.
- **Hazard:** Must safely serialize mod TileEntities and Forge capabilities through Java hooks without race conditions.

### ARCHITECTURE 3: Java Keeps Semantic Chunk Lifecycle $\rightarrow$ Rust Owns RegionFile + Compressed NBT I/O
- **Avoided Work:** Replaces `RegionFile` and `RegionFileCache` with a robust Rust engine (using `mmap` or asynchronous I/O and crash-consistent atomic sector allocations).
- **Advantage:** Solves HAZARD-CHK-02 (255-sector overflow) and HAZARD-CHK-03 (lack of fsync) completely at the storage layer without disturbing gameplay logic.
- **Limitation:** Only saves $\approx 0.02\text{ ms}$ of CPU time; benefits are purely reliability and throughput.

### ARCHITECTURE 4: Rust Async Worker Reads RegionFile + Decompresses + Parses + Decodes Sections $\rightarrow$ Returns Coarse Chunk Payload to Java
- **Avoided Work:** Moves 98.1% of chunk loading latency into native code.
- **Execution:**
  1. Rust worker thread reads `.mca` file directly (via `mmap`).
  2. Native SIMD zlib inflates payload.
  3. Fast binary parser extracts block arrays and heightmaps into a pre-allocated native `ChunkSnapshot` buffer.
  4. Mod entities, tile entities, and `ForgeCaps` compounds are extracted as raw, unparsed NBT slices.
  5. Java `ChunkIOExecutor` receives `ChunkSnapshot`:
     - Bypasses all block allocation and parsing in Java.
     - Only parses the small entity and capability slices into Java objects.
     - Fires `ChunkDataEvent.Load` and `ChunkEvent.Load` on the `Server thread`.
- **Verdict:** **HIGH POTENTIAL RESEARCH CANDIDATE.** Substantially reduces JVM-side chunk decoding allocations and background CPU pressure while maintaining 100% compatibility with Forge mod event listeners. (Note: Java still allocates for live Entity, TileEntity, and capability instances).

---

## 4. Phasing Hypotheses (Research Candidates Only — Unvalidated)
*Note: No Rust seam is authorized for production migration during P0. These represent research candidates for future architectural evaluation:*
1. **Candidate Seam D / Architecture 3:** Rust `RegionFile` engine with crash-consistent writes, overflow handling, and atomic header commits. Evaluated primarily for storage reliability, crash safety, and 1 MiB overflow prevention rather than CPU savings.
2. **Candidate Seam F / Architecture 4:** Rust async chunk decoder feeding coarse `ChunkSnapshot` buffers to Forge `ChunkIOExecutor`. Investigated for offloading block section and raw NBT allocations outside the JVM. Java will still allocate for Entity objects, TileEntity objects, capability objects, and Forge events.
3. **Candidate Architecture 2:** Direct binary NBT streaming from Java chunk arrays to native persistence pool to accelerate Server-thread `saveChunk` snapshots.
