# M1 Selection: First Production Migration Candidate Decision

## 1. Selection Mandate & Philosophy
The objective of Stage P0-9 is to select **exactly ONE** first production migration experiment (M1) that transitions the project from passive research into active, evidence-grounded engineering.

The selection philosophy prioritizes:
1. **Measured Real Value**: Meaningful reduction in ServerThread active compute, tail latency, or GC churn.
2. **Compatibility Safety**: Zero risk of world data corruption or unrecoverable state loss.
3. **Reversibility & Fallback**: Instantaneous, zero-cost runtime fallback to the Java reference path.
4. **Coarse FFI Granularity**: Work units operating on entire buffers, not per-block or per-entity micro-calls.
5. **Deterministic Parity Testability**: A 100% objective, byte-level differential oracle.
6. **Architectural Learning**: Establishing proven patterns for JNI lifecycle, off-heap buffers, panic containment, and shadow validation.

---

## 2. Empirical Ground Truth: Correction of the 182.4 µs Reference Error
Earlier design drafts incorrectly stated that Java reference `SPacketChunkData` section serialization takes ~182.4 µs per full chunk.
Empirical benchmarking on the frozen Java 8 HotSpot environment disproved this:
- **182.4 µs** represented a synthetic benchmark scanning all 65,536 blocks via high-level `Chunk.getBlockState(x, y, z)` calls.
- **Direct Java Section Serialization** actually takes only **11.65 µs** for a full 16-section chunk (and **3.50 µs** for a normal 4-section surface chunk) because in Minecraft 1.12.2, `BlockStateContainer.storage` **is already maintained in memory as a packed `BitArray`**!

### Measured Breakdown of Java `SPacketChunkData` (16 Sections + 64 TileEntities)

| Phase | Description | Mean Latency | p50 | p95 | p99 | Scope in M1 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Size Calculation** | Iterates sections, calculates buffer capacity | 0.17 µs | 0.20 µs | 0.20 µs | 0.30 µs | Unchanged |
| **2. Buffer & Packet Alloc** | `new byte[size]`, Netty wrapper | 3.18 µs | 3.10 µs | 3.80 µs | 4.30 µs | **Replaced by Netty DirectByteBuf** |
| **3. BlockStateContainer** | Palette VarInts + `storage.longArray` copy | 9.64 µs | 9.60 µs | 9.80 µs | 11.00 µs | **Accelerated in Rust** |
| **4. Lighting Arrays** | Block light + sky light array copy | 2.01 µs | 2.00 µs | 2.10 µs | 2.20 µs | **Accelerated in Rust** |
| **5. Biome Array Copy** | 256-byte biome copy (full chunk) | 0.03 µs | 0.00 µs | 0.10 µs | 0.10 µs | **Accelerated in Rust** |
| **6. TileEntity getUpdateTag**| Mod NBT update tags (64 TEs) | 5.61 µs | 5.10 µs | 5.50 µs | 7.40 µs | **Remains 100% Java-Owned** |
| **TOTAL CONSTRUCTOR** | Full Java `new SPacketChunkData` | **20.79 µs** | **20.30 µs** | **21.20 µs** | **24.50 µs** | Partial Replacement |

---

## 3. The Corrected Selection Rubric & Scoring Results

The canonical rubric weights remain invariant:
- **25%** Measured ServerThread Active CPU / Tail Latency Value
- **20%** Forge & Mod Compatibility Safety (Zero persistent corruption risk)
- **15%** Reversibility & Automatic Fallback Feasibility
- **10%** FFI Granularity (Coarse buffer units vs chatty calls)
- **10%** Deterministic Parity Testability
- **10%** Architectural Learning Value
- **5%** Allocation Reduction Throughput
- **5%** Implementation Simplicity

### Canonical Candidate Scoring Table (Empirically Corrected)

| Rank | ID | Candidate Name | Weighted Score | ServerThread Delta | Tail Latency Delta | Compat Safety | Reversibility | FFI Granularity | Parity Testability |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **`M1-C`** | **NATIVE `SPacketChunkData` PAYLOAD** | **8.53 / 10.0** | **0.04–0.08 ms** | **0.40–0.50 ms** | **10.0** | **10.0** | **8.5** | **10.0** |
| 2 | `M1-A` | `SAVE-A` Vanilla Chunk Snapshot | **7.90 / 10.0** | 0.00 ms (11.7 save) | 11.70 ms save| 6.5 | 8.0 | 9.0 | 8.0 |
| 3 | `M1-B` | `SAVE-F/G` Native NBT & Deflate Encoder | **7.18 / 10.0** | 0.00 ms (off-thread)| Queue flush | 8.0 | 8.5 | 8.0 | 9.5 |
| 4 | `M1-F` | Vanilla Worldgen Helpers (Noise) | **7.05 / 10.0** | 0.00 ms base | 6.50 ms gen | 7.0 | 8.5 | 9.0 | 7.5 |
| 5 | `M1-D` | Collision / AABB Acceleration | **6.43 / 10.0** | 1.20–1.80 ms | 2.10 ms | 5.0 | 7.5 | 4.5 | 6.0 |
| 6 | `M1-G` | Engine Allocation Reduction | **5.85 / 10.0** | 0.80–1.20 ms | GC pause reduction| 5.5 | 7.0 | 3.0 | 7.0 |
| 7 | `M1-E` | Lighting Propagation Acceleration | **5.65 / 10.0** | 2.00–3.00 ms | 5.00 ms | 3.0 | 5.0 | 6.0 | 5.0 |

---

## 4. The Winning Decision: M1-C Confirmed
Despite the downward correction of raw micro-savings (from a synthetic 182.4 µs to an empirical 11.65 µs baseline), **Candidate `M1-C` remains the definitive #1 winner (Score 8.53 vs 7.90)**.

### Why M1-C Still Wins
1. **Unrivaled Safety (10.0 / 10.0)**: M1-C operates on ephemeral network wire data. A defect cannot corrupt `.mca` world files. Runner-up `M1-A` (Score 7.90) directly modifies persistent world storage and is too dangerous for an initial M1 milestone.
2. **Deterministic Parity (10.0 / 10.0)**: The wire format of Protocol 340 `SPacketChunkData` allows exact binary `memcmp` against reference Java output in shadow mode.
3. **Flawless Reversibility (10.0 / 10.0)**: Instantaneous, zero-cost per-packet fallback to the Java constructor.
4. **Foundational Architecture (9.5 / 10.0)**: Proves the entire off-heap direct buffer FFI pipeline, Netty buffer integration, and panic containment.
5. **Real-World Benefit**: Saves ~4.36 µs per full chunk (37.4% reduction), provides 0.40–0.50 ms tail relief during exploration bursts (100 chunks/tick), and eliminates up to 25 MB/s of young-gen heap allocation churn.
