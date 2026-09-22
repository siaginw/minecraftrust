# P5: Native Storage & Chunk-State Frontier Research (Design Specification)

**Date**: 2026-09-21  
**Scope**: High-level architectural blueprint for migrating Minecraft 1.12.2 / Forge chunk state into Rust-owned native memory, unblocking M1 (SPacketChunkData), M2-PERSIST (native region save/load), M3-SPAWN, and M3-COLLISION.

---

## 1. The Core Bottleneck: The Java Object Tax

All prior parked candidates converged on a single structural barrier:

| Subsystem | Isolated Native Perf | HotSpot Baseline | JNI / Mirror Tax | Final Verdict |
|---|---|---|---|---|
| **M1 SPacketChunkData** | 2.5x - 4x faster | 1.0x (Java) | JNI buffer copy / section inspection | `REOPEN_AFTER_NATIVE_STATE` |
| **M2-PERSIST Region Save** | 3.5x faster | 1.0x (Java) | Off-thread NBT build + JNI transfer | `ARCHITECTURAL_REOPEN_CANDIDATE` |
| **M3 Spawn Index** | 5.2x faster | 1.0x (Java) | Entity/block position mirror synchronization | `REOPEN_AFTER_NATIVE_STATE` |
| **M3 Collision** | 2.1x faster | 1.0x (Java) | AABB translation + block fetching | `REOPEN_AFTER_NATIVE_STATE` |

**Root Cause**: When Java owns the chunk data in heap objects (`Chunk`, `ExtendedBlockStorage[]`, `char[]`, `NibbleArray`), native code is merely a visitor. Crossing the JNI boundary to read thousands of block states or copying 131 KB arrays wipes out native compute advantages.

**The Solution**: Invert ownership. **Rust owns the chunk storage natively; Java becomes the client via zero-overhead Unsafe facades.**

---

## 2. Native Chunk Memory Model

### Section Data Layout (Cache-Line Optimized)
In Minecraft 1.12.2, each chunk column consists of sixteen 16x16x16 sections (Y = 0..15).
```rust
#[repr(C, align(64))]
pub struct NativeSection {
    /// 16x16x16 = 4096 block states.
    /// Index = (y << 8) | (z << 4) | x (standard Anvil ordering)
    pub blocks: [u16; 4096],           // 8,192 bytes
    
    /// 4096 4-bit block light values packed into 2048 bytes
    pub block_light: [u8; 2048],       // 2,048 bytes
    
    /// 4096 4-bit sky light values packed into 2048 bytes
    pub sky_light: [u8; 2048],         // 2,048 bytes
    
    /// Non-zero block count (used for empty-section skipping in packets)
    pub block_ref_count: u16,          // 2 bytes
    /// Tickable block count
    pub tick_ref_count: u16,           // 2 bytes
    pub _pad: [u8; 60],                // Align to 64 bytes
}
// Total size per section: exactly 12,352 bytes (~12 KB)
```

### Column Data Layout
```rust
#[repr(C, align(64))]
pub struct NativeChunk {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub populated: bool,
    pub dirty: bool,
    pub section_mask: u16,             // Bitmask of non-empty sections
    
    /// Heightmap: 16x16 highest non-air block Y coordinates
    pub heightmap: [i16; 256],         // 512 bytes
    
    /// 16x16 biome IDs
    pub biomes: [u8; 256],             // 256 bytes
    
    /// 16 section pointers (null if section is air)
    pub sections: [*mut NativeSection; 16], // 128 bytes
}
```

---

## 3. Java Interoperability: The `sun.misc.Unsafe` Facade

How can 200+ Forge mods access chunk blocks without breaking compatibility?

### The Legacy Way (Slow)
- Call JNI `nativeGetBlockState(chunkPtr, x, y, z)` for every block query.
- Cost: ~10-15 ns per JNI call. Unacceptable in tight render/tick loops.

### The Modern Engine Way: Memory-Mapped Java Facade
In HotSpot JVM, `sun.misc.Unsafe.getShort(address)` is an intrinsic compiled directly into a single assembly instruction:
```asm
movzwl (%rsi, %rcx, 2), %eax
```
Zero function-call overhead, zero JNI frame setup, zero GC safepoint checks!

```java
public class ExtendedBlockStorage {
    // Replaces char[] blockStorage with raw native memory address
    private long nativeSectionAddress;

    public IBlockState get(int x, int y, int z) {
        if (nativeSectionAddress == 0L) return Blocks.AIR.getDefaultState();
        int index = (y << 8) | (z << 4) | x;
        // Single assembly MOV instruction via Unsafe!
        char blockId = UNSAFE.getChar(nativeSectionAddress + (index << 1));
        return Block.BLOCK_STATE_IDS.getByValue(blockId);
    }

    public void set(int x, int y, int z, IBlockState state) {
        char blockId = (char)Block.BLOCK_STATE_IDS.getId(state);
        int index = (y << 8) | (z << 4) | x;
        UNSAFE.putChar(nativeSectionAddress + (index << 1), blockId);
    }
}
```

---

## 4. Unlocking the Parked Milestones

Once chunk memory is native, the previously parked milestones unlock compound performance gains:

### 1. M1 SPacketChunkData (Packet Serialization)
- **Current Problem**: Java extracts block arrays, packs into Netty buffer, passes across JNI or compresses in Java.
- **Native Pipeline**:
  ```
  NativeChunk (Rust)
    → SIMD Palette Bit-Packer (Rust)
    → Direct Network Buffer (Rust `crates/buffers`)
    → libdeflater Packet Compression (Rust `crates/compression`)
    → Direct Netty IO Ring
  ```
  **Expected Gain**: 4x - 6x packet generation throughput. Zero Java GC churn.

### 2. M2-PERSIST (Anvil Region IO & Persistence)
- **Current Problem**: Java builds NBT on ServerThread, queues to File IO Thread, compresses and writes to disk.
- **Native Pipeline**:
  ```
  NativeChunk (Rust)
    → Lock-free snapshot
    → Native Fast-NBT Binary Serialization (Rust `crates/nbt`)
    → SIMD Deflate Compression (Rust `crates/compression`)
    → Direct OS Write / io_uring / Overlapped File IO (`crates/region-io`)
  ```
  **Expected Gain**: Completely offloads autosave from JVM heap, eliminating periodic GC pauses and MSPT spikes during world save.

### 3. M3 Spatial & Collision Queries
- Raycasting and entity AABB collision checks can query `NativeChunk.sections` directly in C/Rust SIMD without object allocations or JNI boundary calls.
- Can evaluate 64 bounding-box intersections simultaneously via AVX2.

---

## 5. Implementation Roadmap & Guardrails

1. **Phase 1: Shadow Mirroring**
   - Java remains authoritative.
   - Coremod mirrors chunk mutations (`setBlockState`, `setLight`) into a parallel `NativeChunkTable`.
   - Offline & live parity assertions verify exact memory mirror.

2. **Phase 2: Read-Delegation (Opt-in)**
   - Wire `ExtendedBlockStorage.get` and `ChunkPrimer` to read from native memory via `sun.misc.Unsafe`.
   - Benchmark mod compatibility across Revelation and SevTech.

3. **Phase 3: Full Native Inversion**
   - Native storage becomes authoritative.
   - Region loader (`crates/region-io`) populates native memory directly on background threads.
   - Java `Chunk` objects act purely as thin wrapper handles for Forge event dispatching.

---

## 6. Safety & Concurrency Guarantees

- **Memory Ownership**: Rust `ChunkArena` manages native section allocation; pages are pinned and never moved by Java GC.
- **Concurrency**: RCU (Read-Copy-Update) or reader-writer epoch tracking ensures packet serialization and persistence can read chunk state asynchronously while the main tick thread mutates blocks without race conditions.
- **Clean Teardown**: JVM shutdown hook invokes native destructor, cleanly flushing dirty chunks and freeing native arenas.

---

## 7. Empirical Materialization Cost Measurements (M3W5 Study)

From the M3W5 differential oracle & micro-benchmark (`tools/worldgen-interop/src/com/rustcraft/worldgen/TerrainParity.java`, payload 65,536 `char` = 131,072 bytes per chunk):

| Strategy | p50 (µs) | p90 (µs) | p99 (µs) | Materialization Overhead | Notes |
|---|---|---|---|---|---|
| **1. Native Retained / Zero-Copy** | **23.90** | 24.00 | 44.30 | **+0.00 µs (0.0%)** | Data stays in native memory; 0 byte JNI transfer |
| **2. Direct Buffer + Java Heap Copy** | **30.50** | 30.80 | 58.60 | **+6.60 µs (+27.6%)** | Requires `asCharBuffer().get(char[])` + 128 KB heap alloc |
| **3. GetPrimitiveArrayCritical** | **22.60** | 27.60 | 44.10 | **-1.30 µs (pinned)** | Direct write into pinned Java array; pins GC safepoints |
| **Java Baseline (`setBlocksInChunk`)** | **30.50** | 46.40 | 55.40 | Baseline | Pure Java bytecode execution on heap `char[]` |

### Architectural Conclusion
1. **The Materialization Tax**: Copying the generated chunk terrain from native memory into Java heap objects incurs **6.60 µs / chunk (+27.6% overhead)**, plus allocating 128 KB of garbage per chunk on the JVM young generation.
2. **The Zero-Copy Dividend**: By retaining the `ChunkPrimer` / `NativeChunk` in native memory throughout generation, decoration, packet compression, and region IO, this 6.60 µs transfer tax and all 128 KB GC allocations are **100% eliminated**.
3. **M4 Pipeline Synergy**: Fused native generation (`generate_terrain_fused`) writing directly into `NativeSection` structures enables the entire downstream pipeline (`Native SPacketChunkData` -> `Native Compression` -> `Netty`) to execute with zero intermediate Java heap arrays.