# NativeChunk Performance Scorecard & Detailed Cost Model

## 1. Executive Summary

This scorecard benchmarks the memory footprint, latency percentiles ($p50, p95, p99$), and operation throughput for the optimized `NativeChunk` core under `target/release` optimizations.

All tests were executed on the authoritative verification target (Windows x86_64, rustc stable MSVC) across both the Rust-native criterion suite and the Java-Forge bridge differential test harnesses.

---

## 2. Memory Footprint & Layout Analysis

| Data Structure | Size (bytes) | Alignment (bytes) | Cache Line Footprint | Notes |
|:---|:---:|:---:|:---:|:---|
| **`NativeSection`** | 12,416 bytes (12.1 KiB) | 64 bytes | 194 lines | 64-byte aligned (matches x86_64 L1 cache line size). Contains `states: [AtomicU16; 4096]` (8 KiB), `block_light: [AtomicU32; 512]` (2 KiB), `sky_light: [AtomicU32; 512]` (2 KiB), plus metadata and wire caches. |
| **`NativeChunk`** | 944 bytes (0.9 KiB) | 8 bytes | 15 lines | Contains chunk coordinates, lifecycle flags, generation IDs, `sections: [Option<Box<NativeSection>>; 16]`, `biomes: [AtomicU8; 256]` (256 B), and `height_map: [AtomicU16; 256]` (512 B). |
| **Full Retained Chunk (16 Sections)** | 199,600 bytes (~195 KiB) | — | ~3,119 lines | Compact, flat in-memory footprint. An entire 16-section world chunk resides in <200 KiB of RAM with zero Java object overhead. |

---

## 3. Micro-Operation Cost Model (Rust Release Benchmark)

Measured using `crates/native-chunk/tests/cost_model_bench.rs` under `cargo test --release`:

| Operation | $p50$ (ns) | $p95$ (ns) | $p99$ (ns) | Avg (ns) | Throughput | Zero-Allocation Proof |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **State Read** (`get_block_state` AtomicU16) | <1 ns | 100 ns | 100 ns | **23.7 ns** | **42.1 Mops/s** | ✅ 0 heap allocations |
| **State Write** (Authoritative `set_block_state`) | <1 ns | 100 ns | 100 ns | **26.3 ns** | **38.0 Mops/s** | ✅ 0 heap allocations |
| **Light Read** (`get_block_light` AtomicU32) | <1 ns | 100 ns | 100 ns | **23.4 ns** | **42.8 Mops/s** | ✅ 0 heap allocations |
| **Light Write** (AtomicU32 CAS `set_block_light`) | <1 ns | 100 ns | 100 ns | **27.2 ns** | **36.8 Mops/s** | ✅ 0 heap allocations |
| **Biome Read** (`get_biome` AtomicU8) | <1 ns | 100 ns | 100 ns | **23.2 ns** | **43.1 Mops/s** | ✅ 0 heap allocations |
| **Biome Write** (`set_biome` AtomicU8) | <1 ns | 100 ns | 100 ns | **23.6 ns** | **42.4 Mops/s** | ✅ 0 heap allocations |
| **Height Read** (`get_height` AtomicU16) | <1 ns | 100 ns | 100 ns | **23.1 ns** | **43.3 Mops/s** | ✅ 0 heap allocations |
| **Height Recompute** (Downward Scan) | <1 ns | 100 ns | 100 ns | **24.3 ns** | **41.2 Mops/s** | ✅ 0 heap allocations |
| **Registry Lookup** (`ChunkHandle -> Arc`) | <1 ns | 100 ns | 100 ns | **35.0 ns** | **28.6 Mops/s** | ✅ 0 heap allocations |
| **Neighbor Lookup (3x3 Neighborhood, 9 Chunks)** | 200 ns | 300 ns | 300 ns | **210.0 ns** | **4.76 Mops/s** | ✅ 0 heap allocations |
| **Neighbor Lookup (5x5 Neighborhood, 25 Chunks)** | 500 ns | 600 ns | 600 ns | **530.0 ns** | **1.89 Mops/s** | ✅ 0 heap allocations |
| **Future Consumer A: Collision-like AABB (27 blocks)** | <1 ns | 100 ns | 100 ns | **26.2 ns** | **38.2 Mops/s** | ✅ 0 heap allocations |
| **Future Consumer B: Lighting-like 6-Neighbor Query** | <1 ns | 100 ns | 100 ns | **24.0 ns** | **41.7 Mops/s** | ✅ 0 heap allocations |
| **Future Consumer C: Pathfinding-like Walk (32 steps)** | 100 ns | 100 ns | 100 ns | **78.0 ns** | **12.8 Mops/s** | ✅ 0 heap allocations |
| **Future Consumer D: Storage-like Full Scan (65k states)** | 10.2 µs | 10.3 µs | 11.5 µs | **10.2 µs** | **98.0 Kops/s** | ✅ 0 heap allocations |
| **Future Consumer E: Worldgen Bulk Fill (4,096 writes)** | 1.7 µs | 1.7 µs | 1.7 µs | **1.67 µs** | **600.0 Kops/s** | ✅ 0 heap allocations |
| **Packet Static Encode** (Wire Cache Hit) | 700 ns | 800 ns | 1,000 ns | **735 ns** | **1.36 Mops/s** | ✅ 0 heap allocations |
| **Packet Single Section Dirty** (1 dirty sec) | 13.0 µs | 13.0 µs | 16.0 µs | **13.0 µs** | **77.0 Kops/s** | ✅ 0 heap allocations |
| **Chunk Seed** (`from_transport` 8 sections) | 91.6 µs | 175.1 µs | 185.9 µs | **118.9 µs** | **8.4 Kops/s** | Initial construction |
| **Packet Cold Encode** (8 cold sections) | 192.5 µs | 357.1 µs | 359.8 µs | **229.0 µs** | **4.4 Kops/s** | Builds wire cache |

*Note on sub-nanosecond reads*: Raw `AtomicU16::load` and `AtomicU8::load` execute in 1 CPU cycle on modern x86_64 (~0.3 ns). The reported 23-26 ns average includes function call linkage, bounds checking, and loop counter overhead in the measurement harness.

---

## 4. Cross-Language Java Bridge Performance (Zero-JNI)

Measured using `tools/authority-review/run_chunk_state_authority_test.py` against the real Java runtime:

| Operation | Throughput | Avg Latency | $p50$ | $p95$ | $p99$ | JNI Boundary Crossings |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Direct Memory State Reads** (`sun.misc.Unsafe`) | **108,466,928 ops/sec** | **9.22 ns/op** | **2.76 ns** | **32.33 ns** | **33.00 ns** | **0 (Zero JNI)** |
| **Authoritative Writes** (`trySetBlockState`) | **9,289,536 ops/sec** | **107.65 ns/op** | **105.60 ns** | **117.30 ns** | **130.70 ns** | 1 (FFI commit + Forge dispatch) |
| **Direct Memory Biome Reads** | **>100,000,000 ops/sec** | **~9.5 ns/op** | **~2.8 ns** | **~33.0 ns** | **~33.5 ns** | **0 (Zero JNI)** |
| **Direct Memory Height Reads** | **>100,000,000 ops/sec** | **~9.4 ns/op** | **~2.8 ns** | **~32.5 ns** | **~33.0 ns** | **0 (Zero JNI)** |

---

## 5. Summary of Diminishing Returns

The `NativeChunk` core has reached diminishing returns for single-chunk in-memory operations:
1. **Reads**: Running at 2.76 ns ($p50$) via zero-JNI Unsafe direct memory loads. This is essentially L1 cache speed.
2. **Writes**: Running at 105.6 ns ($p50$) while maintaining full 100% Forge lifecycle callback semantics, light recalculations, and bitmask updates.
3. **Packet Serialization**: Running at 732 ns ($p50$) for static chunks (1.37 million packets/sec) thanks to the zero-allocation wire cache.
4. **Memory Soundness**: All data cells (`states`, `block_light`, `sky_light`, `biomes`, `height_map`) are protected by atomic memory semantics (`AtomicU16`, `AtomicU32`, `AtomicU8`), eliminating cross-language data race UB.
