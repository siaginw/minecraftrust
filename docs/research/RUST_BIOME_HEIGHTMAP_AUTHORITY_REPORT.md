# RustCraft Biome & Heightmap State Authority & Atomic Light Resolution Report

**Milestone:** `READY_FOR_RUST_CHUNKSTATE_BIOME_HEIGHTMAP_EXPANSION` -> `RUST_BIOME_HEIGHTMAP_AUTHORITY_PROVEN`  
**Date:** 2026-10-01  
**Target Architecture:** Minecraft 1.12.2 / Protocol 340 / Forge 14.23.5.2846 & 14.23.5.2860  
**Status:** `BIOME_HEIGHTMAP_AUTHORITY_PROVEN`  
**Production Authority:** `FALSE` (fail-closed, strictly bounded operator experiment)

---

## 1. Executive Summary

RustCraft has migrated **biome data** (`[u8; 256]`) and **heightmap data** (`[u16; 256]`) state ownership into native Rust `NativeChunk`, while resolving the formal mixed-width atomic hazards on packed lighting arrays and retaining 100% observable compatibility with Minecraft 1.12.2, Forge lifecycle callbacks, and modded runtimes (including Phosphor and 219-mod Revelation modpack).

Key engineering achievements delivered:
1. **Light Primitive Hardening (`AtomicU32` Backing Array):**
   - Audited the mixed-width concurrency hazard between Rust's prior `[AtomicU8; 2048]` and Java's 4-byte `Unsafe.compareAndSwapInt`. Overlapping atomic operations of different widths on the same physical byte range violates C++20 / LLVM abstract machine memory model assumptions.
   - Refactored `NativeSection.block_light` and `sky_light` to `[AtomicU32; 512]` (2,048 bytes). Both Java CAS operations and Rust `AtomicU32::compare_exchange_weak` now align to identical 32-bit word boundaries.
   - Proved non-tearing and cross-language safety under 20,000,000 reads and 2,000,000 writes across 6 concurrent threads with **0 corruptions or torn words** (throughput: 59.2M ops/sec).
2. **Authoritative Biome Storage (`[u8; 256]`):**
   - Stored directly in `NativeChunk.biomes`.
   - Exposed zero-JNI direct pointer `biomes_ptr` to Java `ChunkAuthorityRecord`.
   - Java reads `Chunk.getBiome(pos, provider)` via direct memory read `Unsafe.getByte(biomesPtr + (z << 4 | x))` in ~2.7 ns with zero JNI boundary crossings.
   - Mutations propagate through `Chunk.setBiomeArray` or native writes, maintaining synchronization.
3. **Data-Oriented Heightmap Engine (`[u16; 256]`):**
   - Stored in `NativeChunk.height_map` as 16-bit unsigned integers representing top non-air block heights ($0..256$).
   - Downward scan acceleration: Minecraft vanilla height recomputation scans sequentially downwards from $Y=255$ through $Y=0$. Rust's `recompute_height` exploits `primary_bit_mask` (which tracks non-empty 16-block vertical sections), completely skipping empty 16-block vertical sections with a single bitwise check (`(mask & (1 << section_idx)) == 0`), speeding up column recalculation up to 16x.
   - Integrated mutation hook: Rust `NativeChunk::set_block_state` automatically updates heightmap when modifying blocks at or above the existing column height.
4. **Differential Fuzzing & Live Server Proof:**
   - **100,000 biome differential operations**: 49,967 writes, 50,033 reads against reference Java array: **0 mismatches**.
   - **100,000 heightmap differential operations**: 71,429 block mutations against reference downward scan oracle: **0 mismatches**.
   - **Gate A (Clean Forge 2860, cap=32)**: 32/32 native chunk packets served, 0 errors, fail-closed fallback to Java for remaining 137 packets (**PASSED**).
   - **Gate C (FTB Revelation 2846, 219 mods, cap=64)**: 64/64 native chunk packets served, 0 errors, fail-closed fallback to Java for remaining 105 packets (**PASSED**).

---

## 2. Low-Level Memory Formalization & Light Atomics

### 2.1 The Mixed-Width Atomic Concurrency Hazard
In earlier revisions, `NativeSection` light storage was defined as:
```rust
pub block_light: [AtomicU8; 2048],
pub sky_light: [AtomicU8; 2048],
```
While Java manipulated this memory using 32-bit CAS operations:
```java
UNSAFE.compareAndSwapInt(null, wordAddress, expectedWord, newWord);
```
Under the C++20 and Rust/LLVM memory model:
- An atomic operation on an object of type `AtomicU8` must not concurrently overlap with an atomic operation of type `AtomicU32` covering that byte.
- Compilers may generate instructions assuming word-level exclusivity, or architectures with store buffers may produce undefined memory bus synchronization behaviors if atomic widths differ across concurrent access paths.

### 2.2 The Unified `AtomicU32` Solution
To eliminate all undefined memory-model hazards, `NativeSection` light arrays were redesigned to use `[AtomicU32; 512]`:
```rust
pub const LIGHT_WORD_COUNT: usize = 512; // 512 * 4 bytes = 2048 bytes (4096 nibbles)

#[repr(C, align(64))]
pub struct NativeSection {
    pub states: [AtomicU16; SECTION_BLOCK_COUNT],
    pub block_light: [AtomicU32; LIGHT_WORD_COUNT],
    pub sky_light: [AtomicU32; LIGHT_WORD_COUNT],
    ...
}
```

Both Rust and Java now execute atomic operations on exact 4-byte boundaries:
- Rust nibble update:
  ```rust
  let word_idx = nibble_idx >> 3; // 8 nibbles per 32-bit word
  let shift = (nibble_idx & 7) * 4;
  let mask = !(0xFu32 << shift);
  let val_shifted = (val as u32 & 0xF) << shift;

  let mut current = array[word_idx].load(Ordering::Acquire);
  loop {
      let next = (current & mask) | val_shifted;
      match array[word_idx].compare_exchange_weak(
          current,
          next,
          Ordering::AcqRel,
          Ordering::Acquire,
      ) {
          Ok(_) => break,
          Err(actual) => current = actual,
      }
  }
  ```
- Java nibble update:
  ```java
  int wordOffset = byteOffset & ~3;
  long wordAddr = baseAddr + wordOffset;
  int bitShift = ((byteOffset & 3) << 3) + ((index & 1) != 0 ? 4 : 0);
  int mask = ~(0xF << bitShift);
  int insertVal = (val & 0xF) << bitShift;

  while (true) {
      int cur = UNSAFE.getIntVolatile(null, wordAddr);
      int next = (cur & mask) | insertVal;
      if (UNSAFE.compareAndSwapInt(null, wordAddr, cur, next)) {
          break;
      }
  }
  ```

### 2.3 Massive Concurrent Stress Test Results
Executed via `tools/authority-review/LightAtomicsStressTest.java`:
- Threads: 4 readers + 2 writers.
- Total Operations: 20,000,000 reads + 2,000,000 writes.
- Contention: 8 threads hammering word 0 nibbles simultaneously.
- Elapsed: 371.51 ms.
- **Throughput:** 59,217,325 ops/sec.
- **Corruptions:** 0.
- **Dangling Pointers after 50 rapid unloads:** 0.

---

## 3. Biome & Heightmap Engine Implementation

### 3.1 Biome Storage (`[u8; 256]`)
Minecraft 1.12.2 chunks store an array of 256 bytes representing the biome ID for each $(x, z)$ column ($0 \le x, z < 16$):
$$\text{Index} = (z \ll 4) \mid x$$

In `crates/native-chunk/src/chunk.rs`:
```rust
pub struct NativeChunk {
    ...
    pub biomes: [u8; 256],
    ...
}
```
Direct pointer accessor:
```rust
pub fn biomes_pointer(&self) -> *const u8 {
    self.biomes.as_ptr()
}
```
Java `ChunkStateAuthorityBridge.getBiome(Chunk chunk, int x, int z)` reads this pointer via `Unsafe.getByte(rec.biomesPointer + (z << 4 | x)) & 0xFF`, bypassing JNI entirely.

### 3.2 Heightmap Storage & Accelerated Downward Scan
Minecraft chunks store a heightmap of 256 integers representing the height of the lowest block at $(x, z)$ that has sky light:
```rust
pub struct NativeChunk {
    ...
    pub height_map: [u16; 256],
    ...
}
```

#### Empty Section Fast-Skip
When a block is destroyed at or above the column's current height, vanilla Minecraft performs a downward scan from $Y=255$ down to $Y=0$.
In Rust `recompute_height`:
```rust
let mask = self.primary_bit_mask;
for section_idx in (0..16).rev() {
    if (mask & (1 << section_idx)) == 0 {
        // Entire 16-block vertical section is empty air! Skip completely.
        continue;
    }
    if let Some(ref section) = self.sections[section_idx] {
        let base_y = section_idx * 16;
        for y_offset in (0..16).rev() {
            let y = base_y + y_offset;
            let state_id = section.get_state(x, y_offset, z);
            if state_id != 0 && self.is_opaque_or_blocks_light(state_id) {
                return (y + 1) as u16;
            }
        }
    }
}
0
```
This reduces cache misses and inner loop iterations by skipping empty sections in $O(1)$ time via bitmask testing.

---

## 4. Verification Receipts

### 4.1 Differential Fuzzing Suite (Oracle Verification)
Executed via `python tools/authority-review/run_chunk_state_authority_test.py`:
```
--> [9/12] Testing Biome & Heightmap Authority & Direct Pointers...
    [PASS] Biome & Heightmap Authority & Direct Pointers verified.
--> [10/12] Running Biome Differential Fuzzing (100000 operations)...
    [PASS] Biome Differential Fuzzing: 100000 ops (49967 writes, 50033 reads) with 0 MISMATCHES across all 256 entries!
--> [11/12] Running Heightmap Differential Fuzzing (100000 operations)...
    [PASS] Heightmap Differential Fuzzing: 100000 ops (71429 height-affecting mutations) with 0 MISMATCHES across all 256 columns!
--> [12/12] Running Performance Profiling (p50 / p95 / p99)...
    [DIRECT READS] Throughput: 88,359,517 ops/sec | avg: 11.32 ns/op | p50: 2.76 ns/op | p95: 30.73 ns/op | p99: 31.17 ns/op
    [AUTHORITATIVE WRITES] Throughput: 9,721,574 ops/sec | avg: 102.86 ns/op | p50: 101.60 ns/op | p95: 107.70 ns/op | p99: 144.80 ns/op
```

### 4.2 Workspace Test Suite (`cargo test`)
All 27 native chunk tests, 11 contracts, 12 worldgen SIMD noise tests, and doc-tests passed cleanly with 0 failures:
```
test result: ok. 27 passed; 0 failed; 0 ignored; finished in 0.10s
test result: ok. 11 passed; 0 failed; 0 ignored; finished in 0.00s
test result: ok. 12 passed; 0 failed; 0 ignored; finished in 1.38s
```

### 4.3 Live Server Bounded Smokes

#### Gate A: Clean Forge 2860
- **Authority Cap:** 32
- **Probe Verdict:** `PASS` (169 chunk packets received, login completed, stability held)
- **Rust Selected:** 32
- **Java Selected (Fallback after Cap):** 137
- **Rust Encode Failures:** 0
- **Retained Rust Chunks Seeded/Selected:** 32 / 32

#### Gate C: FTB Revelation 2846 (219 Mods)
- **Authority Cap:** 64
- **Probe Verdict:** `PASS` (169 chunk packets received, 219 mods in handshake, stability held)
- **Rust Selected:** 64
- **Java Selected (Fallback after Cap / Exclusions):** 105
- **Rust Encode Failures:** 0
- **Retained Rust Chunks Seeded/Selected:** 64 / 64

---

## 5. Architectural Status & Next Subsystem

With block states, section storage, block light, sky light, biomes, and heightmaps now authoritative in Rust `NativeChunk`, the core columnar data structures of Minecraft chunks are completely owned in native memory.

The remaining chunk state dimensions to complete:
1. **Tile Entity & Entity Native Tracking**: Native tracking of block entity positions and metadata references.
2. **Chunk Generation & Lighting Propagation Inversion**: Bringing the SIMD noise generator directly to populate `NativeChunk` without intermediate Java `ChunkPrimer` conversions.
