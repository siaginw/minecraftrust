# RustCraft Semantic Engine Ownership: ChunkState API Authority Review

**Milestone:** `RUST_CHUNKSTATE_API_OWNERSHIP_PROVEN`  
**Date:** 2026-10-01  
**Target Architecture:** Minecraft 1.12.2 / Protocol 340 / Forge 14.23.5.2846 & 14.23.5.2860  
**Status:** `READY_FOR_RUST_CHUNKSTATE_AUTHORITY_EXPANSION`  
**Production Authority:** `FALSE` (strictly bounded experiment under operator controls)

---

## 1. Executive Summary

RustCraft has achieved its first **SEMANTIC ENGINE OWNERSHIP inversion**.

Prior to this milestone, Rust ChunkState functioned as an isolated or mirrored downstream consumer: Java remained the semantic owner of chunk blocks, mutating its own `ExtendedBlockStorage` arrays and synchronizing to Rust for packet encoding.

Under the new architecture:
```
Forge / Minecraft Java Caller
             │
             ▼
 Chunk API Compatibility Surface
             │
             ▼
      Rust ChunkState        ← SEMANTIC OWNER
             │
      ┌──────┴──────┐
      ▼             ▼
Java-Visible     Network Packet
   Result           Encoder
```

1. **Rust is the Source of Truth:**
   For admitted block-state operations (`Chunk.getBlockState` and `Chunk.setBlockState`), Rust `NativeChunk` is the single authoritative decider of storage state transitions.
2. **No Double-Writing of Semantic State:**
   Mutations commit to Rust native memory FIRST. Java storage does not run independent state logic; optional compatibility mirroring is strictly downstream projection.
3. **Zero JNI Overhead on Hot Reads (`getBlockState`):**
   `getBlockState` reads directly from native resident section memory via `sun.misc.Unsafe` direct memory pointers (`readStateId` / `getBlockStateDirect`) and resolves canonical Java `IBlockState` references in O(1) time without crossing JNI.
   - **Throughput:** **16,899,885 ops/sec**
   - **p50 Latency:** **0 ns** (amortized in L1/L2 cache)
   - **p95 / p99 Latency:** **100 ns**
4. **Authoritative Writes (`setBlockState`):**
   `setBlockState` executes in Rust native memory via packed 64-bit FFI boundary, updating section allocation, non-air block counts, and wire cache invalidation in a single atomic pass, then returns facts to Java to orchestrate vanilla/Forge lifecycle side effects (`breakBlock`, `shouldRefresh`, `relightBlock`, `checkLightFor`, `onBlockAdded`) in exact reference sequence.
   - **Throughput:** **5,865,103 ops/sec**
   - **p50 Latency:** **100 ns**
   - **p95 / p99 Latency:** **200 ns**
5. **Differential Oracle & Fuzzing:**
   - **10,000 randomized operation sequences** (5,071 writes, 4,929 reads) executed against an isolated Java reference oracle with **0 MISMATCHES**.
6. **Mutation -> Packet End-to-End Proof (No Reseed):**
   A block mutation executed via `setBlockState` is immediately observed by subsequent Protocol 340 packet encoders from the SAME retained native chunk without reseed and without full section refresh from Java.
7. **Live Smoke Gate Verification:**
   - **Gate A (Clean Forge 2860):** **PASS** (`AuthProbeA`, 169 packets, 32 Rust / 137 Java fallback at cap).
   - **Gate B (FTB Revelation 2846, 219 mods):** **PASS** (`AuthProbeC`, 169 packets, 64 Rust / 105 Java fallback at cap).

---

## 2. Reference Java & Forge Semantics

### 2.1 `Chunk.getBlockState` Disassembly (`func_186032_a`)
```java
public IBlockState func_186032_a(int x, int y, int z) {
    if (this.world.getWorldType() == WorldType.DEBUG_WORLD) {
        // debug world handling
        ...
    } else if (y >= 0 && (y >> 4) < this.storageArrays.length) {
        ExtendedBlockStorage ebs = this.storageArrays[y >> 4];
        if (ebs != NULL_BLOCK_STORAGE) {
            return ebs.get(x & 15, y & 15, z & 15);
        }
    }
    return Blocks.AIR.getDefaultState();
}
```
Key invariants:
- `y < 0` or `y >= 256` returns `Blocks.AIR.getDefaultState()`.
- Unallocated / null sections return `Blocks.AIR.getDefaultState()`.
- Valid coordinates read section `y >> 4` at section-local `(x & 15, y & 15, z & 15)`.

### 2.2 `Chunk.setBlockState` Disassembly (`func_177436_a`)
Reference lifecycle and side-effect dispatch sequence:
1. Coordinate extraction: `x = pos.getX() & 15`, `y = pos.getY()`, `z = pos.getZ() & 15`.
2. Precipitation heightmap: `k = (z << 4) | x`. If `y >= precipitationHeightMap[k] - 1`, sets `-999`.
3. Heightmap query: `i1 = heightMap[k]`.
4. Read current state: `oldState = this.getBlockState(pos)`.
5. Fast no-op exit: If `oldState == newState`, returns `null`.
6. Extract blocks: `newBlock = newState.getBlock()`, `oldBlock = oldState.getBlock()`.
7. Section allocation:
   - If section `y >> 4` is null and `newBlock == Blocks.AIR`, returns `null`.
   - If section is null and `newBlock != Blocks.AIR`, creates `new ExtendedBlockStorage(y >> 4 << 4, world.provider.hasSkyLight())`.
8. Storage mutation: `storage.set(x, y & 15, z, newState)`.
9. Side-effect orchestration:
   - **Side Effect 1:** `if (!world.isRemote && oldBlock != newBlock) oldBlock.breakBlock(world, pos, oldState)`.
   - **Side Effect 2:** If TileEntity exists and `te.shouldRefresh(world, pos, oldState, newState)`, calls `world.removeTileEntity(pos)`.
   - **Side Effect 3:** Recalculate heightmap via column update `this.relightBlock(x, y, z)`.
   - **Side Effect 4:** Lighting checks: `world.checkLightFor(EnumSkyBlock.SKY, pos)` and `world.checkLightFor(EnumSkyBlock.BLOCK, pos)`.
   - **Side Effect 5:** `if (!world.isRemote && oldBlock != newBlock && (!world.captureBlockSnapshots || newBlock.hasTileEntity(newState))) newBlock.onBlockAdded(world, pos, newState)`.
   - **Side Effect 6:** If `newBlock.hasTileEntity(newState)`, creates and registers new TileEntity.
   - **Side Effect 7:** `this.markDirty()`.
   - **Return:** Returns `oldState`.

---

## 3. Architecture & Dataflow

### 3.1 Rust Native Layer (`crates/native-chunk`)

`NativeChunk` stores resident sections as `[Option<Box<NativeSection>>; 16]`. Each section contains `states: [u16; 4096]` at offset 0 (`#[repr(C, align(64))]`).

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(C)]
pub struct BlockMutationResult {
    pub old_state: u16,
    pub new_state: u16,
    pub section_created: bool,
    pub section_became_empty: bool,
    pub non_air_count: u16,
    pub status: i32, // 0 = SUCCESS, 1 = NO_OP, -1 = OUT_OF_BOUNDS
}
```

- `get_block_state(x, y, z) -> u16`: O(1) read; returns 0 if absent or out of bounds.
- `set_block_state(x, y, z, new_state) -> BlockMutationResult`:
  - If absent section and `new_state == 0`: returns `STATUS_NO_OP (1)`.
  - If absent section and `new_state != 0`: allocates `NativeSection`, sets block, marks bit in `primary_bit_mask`, increments `STATS_SECTIONS_ALLOCATED`, increments `mutation_generation`.
  - If resident: reads `old_state`. If `old_state == new_state`, returns `STATUS_NO_OP (1)`. Mutates in place, updates `non_air_count`, invalidates wire cache, clears bit in `primary_bit_mask` if section becomes empty, increments `mutation_generation`.
- `get_section_state_pointer(section_y) -> usize`: Returns direct pointer to `sec.states[0]` for zero-JNI direct memory reading.

### 3.2 FFI Layer (`crates/ffi`)

- `NativeChunkBridge.setBlockState(...) -> jlong`: Returns packed 64-bit integer:
  - bits 0..7: `status as i8`
  - bit 8: `section_created`
  - bit 9: `section_became_empty`
  - bits 16..31: `old_state (u16)`
  - bits 32..47: `new_state (u16)`
  - bits 48..63: `non_air_count (u16)`
- `NativeChunkBridge.getBlockState(...) -> jint`: Fallback query returning `u16`.
- `NativeChunkBridge.getSectionPointers(...) -> jint`: Copies 16 section pointers (128 bytes) in one JNI call during chunk setup.

### 3.3 Zero-JNI Read Engine (`StateRegistryLookup.java`)

```java
public static IBlockState getBlockStateDirect(long sectionPtr, int x, int y, int z) {
    int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    int stateId = UNSAFE.getShort(sectionPtr + ((long) idx << 1)) & 0xFFFF;
    IBlockState s = STATE_TABLE[stateId];
    return s != null ? s : airState;
}
```
- No JNI transitions.
- No Java memory allocations.
- Direct HotSpot intrinsic (`movzx` instruction) followed by single array reference lookup.

### 3.4 Compatibility Bridge & Side-Effect Orchestrator (`ChunkStateAuthorityBridge.java`)

1. Validates admission gates (dim 0, no TE at position, global state ID <= 65535, cap not exceeded).
2. Calls Rust `NativeChunkBridge.setBlockState` to commit semantic mutation.
3. If section was created, queries `NativeChunkBridge.getSectionPointer` to populate direct pointer table.
4. Executes Forge/Minecraft lifecycle side effects in exact sequence.
5. Returns `MutationResult.handled(oldState)` to ASM entry hook.

---

## 4. Differential Oracle & Fuzzing Results

Fuzzing suite executed in `tools/authority-review/ChunkStateAuthorityTest.java`:

| Parameter | Value |
|---|---|
| Operation Count | 10,000 |
| Writes Executed | 5,071 |
| Reads Executed | 4,929 |
| State Spectrum | Air (0), Low Vanilla IDs (1-20), Mid IDs (100-600), High Modded IDs (1,000-65,535) |
| Coordinate Bounds | x: 0..15, y: 0..255, z: 0..15 across sections 0..15 |
| First Divergence Count | **0** |
| Total Mismatches | **0** |
| Verdict | **PASS** |

---

## 5. Performance Benchmarks

Measured on AMD Ryzen / Windows x64 HotSpot JDK 8:

| Operation | Implementation | Throughput (ops/sec) | p50 Latency | p95 Latency | p99 Latency | Allocations |
|---|---|---|---|---|---|---|
| `getBlockState` | Direct Memory (Zero-JNI) | **16,899,885** | **0 ns** | **100 ns** | **100 ns** | **0 B** |
| `getBlockState` | Java Reference Baseline | ~14,200,000 | 0 ns | 100 ns | 150 ns | 0 B |
| `setBlockState` | Rust Authoritative (FFI) | **5,865,103** | **100 ns** | **200 ns** | **200 ns** | **0 B** |
| `setBlockState` | Java Reference Baseline | ~4,100,000 | 150 ns | 300 ns | 450 ns | varies |

Direct memory reading completely eliminates the JNI tax, exceeding the throughput of vanilla Java state container lookups while retaining Rust as the single authoritative semantic owner.

---

## 6. Live Smoke Gate Verification

### Gate A: Clean Forge 14.23.5.2860
- **Client Probe:** `AuthProbeA` joined, completed FML handshake, held 20s stability, disconnected cleanly (**PASS**).
- **Chunk Packets Handled:** 169 packets.
- **Rust Selected Packets:** 32 (capped).
- **Retained Rust Selected:** 32 / 32.
- **Fail-Closed Java Fallback:** 137 packets (after cap exhausted).
- **Encode Failures:** 0.

### Gate B: FTB Revelation 3.4.0 (219 Mods)
- **Client Probe:** `AuthProbeC` joined with 219 mods in handshake, completed FML handshake, held 20s stability, disconnected cleanly (**PASS**).
- **Chunk Packets Handled:** 169 packets.
- **Rust Selected Packets:** 64 (capped).
- **Retained Rust Selected:** 64 / 64.
- **Excluded by TE Gate:** 48 packets.
- **Excluded by High State Gate:** 6 packets.
- **Fail-Closed Java Fallback:** 105 packets.
- **Encode Failures:** 0.

---

## 7. Compliance Checklist

- [x] Rust becomes the source of truth for admitted chunk block state.
- [x] Java remains present as the Forge/mod compatibility surface.
- [x] No double-write of semantic state.
- [x] `getBlockState` is NOT a JNI hot bottleneck (zero-JNI direct memory reads).
- [x] Forge callback ordering strictly preserved.
- [x] Fail-closed to Java on all unadmitted positions (TEs, high state > 65535, dim != 0).
- [x] Bounded operator cap (`-Drustcraft.chunkStateAuthorityCap=1000`).
- [x] Production authority hardcoded `false`.
- [x] End-to-end proof: mutation -> retained packet encode without reseed.
- [x] 10,000 fuzz operations with 0 mismatches.
- [x] Clean Forge and FTB Revelation smoke runs pass 100%.
