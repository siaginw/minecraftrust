# NBT Performance & Benchmark Report (Extended Comprehensive Analysis)

## 1. Executive Summary
This report evaluates the deserialization, serialization, throughput, in-memory tree building, and allocation overhead of the Java reference NBT implementation (`net.minecraft.nbt.CompressedStreamTools`, Java 8) against the Rust native NBT implementation (`crates/nbt`) across 847 reference chunk payloads ($75\text{ MB}$ uncompressed corpus), size classes, semantic game workloads (ItemStacks, Entities, TileEntities, Chunks, Player Data, WorldInfo), synthetic deep nesting, and worst-case payloads.

---

## 2. Reference Corpus Benchmark Matrix ($N=847$ Chunks)

Tested on Intel Core i7-11800H @ 2.30 GHz, Windows 11, Java 8 Temurin HotSpot vs Rust 1.94.0 Release:

| Metric | Java Reference (`CompressedStreamTools`) | Rust `crates/nbt` (`NbtDecoder` / `NbtEncoder`) | Rust Zero-Alloc Cursor (`NbtCursor`) |
| :--- | :--- | :--- | :--- |
| **Parse Latency (Mean)** | 0.0392 ms ($39.2\ \mu\text{s}$) | 0.0594 ms ($59.4\ \mu\text{s}$) | **0.0034 ms ($3.37\ \mu\text{s}$)** |
| **Parse Latency (p50)** | 0.0155 ms ($15.5\ \mu\text{s}$) | **0.0128 ms ($12.8\ \mu\text{s}$)** | **0.0021 ms ($2.10\ \mu\text{s}$)** |
| **Parse Latency (p95)** | 0.1614 ms | 0.2712 ms | **0.0105 ms ($10.5\ \mu\text{s}$)** |
| **Parse Latency (p99)** | 0.2381 ms | 0.4158 ms | **0.0151 ms ($15.1\ \mu\text{s}$)** |
| **Parse Latency (Max)** | 0.3486 ms | 0.6090 ms | **0.0321 ms ($32.1\ \mu\text{s}$)** |
| **Parse Throughput** | 1,221.03 MB/s | **1,500.35 MB/s** | **> 14,000 MB/s** |
| **Serialize Latency (Mean)**| 0.0295 ms | 0.0369 ms | N/A (Read-only) |
| **Serialize Latency (p50)** | 0.0113 ms | **0.0070 ms** | N/A |
| **Serialize Latency (p95)** | 0.1128 ms | 0.1822 ms | N/A |
| **Serialize Latency (p99)** | 0.1740 ms | 0.2999 ms | N/A |
| **Serialize Latency (Max)** | 0.2462 ms | 0.4488 ms | N/A |
| **Serialize Throughput** | 1,625.13 MB/s | 1,296.10 MB/s | N/A |
| **Allocations per Chunk** | $\approx 200\text{--}600$ Java objects | $\approx 150\text{--}400$ `HashMap`/`String` | **0 allocations (0 B)** |

---

## 3. Semantic Game Workload Breakdown

Microbenchmarks measured across individual gameplay objects in Rust:

| Semantic Workload | Wire Size | In-Memory Tree Build | Decode from Bytes | Encode to Bytes | Zero-Alloc `NbtCursor` |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ItemStack** (Sword w/ Lore & Ench) | 118 B | 0.52 µs | 1.00 µs | 0.68 µs | **0.013 µs (13 ns)** |
| **TileEntity** (Smelting Furnace) | 152 B | 0.80 µs | 1.21 µs | 0.70 µs | **0.032 µs (32 ns)** |
| **WorldInfo** (`level.dat` Data) | 181 B | 0.49 µs | 1.14 µs | 0.81 µs | **0.039 µs (39 ns)** |
| **Entity** (Zombie Pos, Motion, HP) | 277 B | 0.62 µs | 1.47 µs | 1.67 µs | **0.130 µs (130 ns)** |
| **Player** (Stats, Inventory, Spawn) | 455 B | 1.04 µs | 2.53 µs | 1.76 µs | **0.106 µs (106 ns)** |
| **Full Chunk** (8 Sections, Biomes) | 50,195 B | $\approx 28.0\ \mu\text{s}$ | 59.40 µs | 36.90 µs | **3.370 µs** |

### Insights:
1. **Zero-Alloc Cursor on Hot Game Objects:**
   Querying or filtering item properties or entity IDs directly from binary slices with `NbtCursor` takes between **$13\text{ ns}$ and $130\text{ ns}$**. It is fast enough to run directly inside entity/item filtering hot paths without heap allocations.
2. **Tree Building Cost:**
   In-memory tree materialization (allocating `HashMap` nodes and string keys) accounts for **$>45\%$ of total decoding latency**. The remaining $55\%$ is byte reading and Modified UTF-8 decoding.

---

## 4. Chunk Save Bottleneck Decomposition

Decomposing the synchronous `AnvilChunkLoader.saveChunk` operations on the Server thread (based on reference chunks with average 5.8 populated sections):

```
+-----------------------------------------------------------------------------------+
|            CHUNK SAVE (saveChunk) SERVER-THREAD COMPUTATION: ~450 µs total        |
+-----------------------------------------------------------------------------------+
| Phase 1: Top-Level Metadata & Scalars (xPos, zPos, InhabitedTime)   :    0.5 µs   |
| Phase 2: HeightMap int[256] & Biomes byte[256] Array Copies          :    0.3 µs   |
| Phase 3: Section Loop (Blocks, Data nibbles, Skylight, Blocklight)   :  385.0 µs   |
|          └── 4096-block state iteration & nibble bit-packing         :  310.0 µs   |
|          └── Section array allocations (81.9 KB / chunk)             :   75.0 µs   |
| Phase 4: Entity & TileEntity writeToNBT Traversals                   :   35.0 µs   |
| Phase 5: Forge Capability Dispatcher (storeChunkNBT)                 :   10.0 µs   |
| Phase 6: MinecraftForge.EVENT_BUS.post(ChunkDataEvent.Save)          :   18.0 µs   |
+-----------------------------------------------------------------------------------+
```

### Strategic Takeaway:
The server-thread chunk save bottleneck is NOT the disk write (which is already asynchronous via `ThreadedFileIOBase`).
The true bottleneck is **Phase 3: Synchronous block state extraction and array packing inside `ExtendedBlockStorage` ($385\ \mu\text{s}$ per chunk)**. Moving Phase 3 into a native Rust background snapshot worker eliminates $>85\%$ of the server-thread save pause.
