# M4.1 Research: External Native Chunk Architecture Survey (Expanded)

**Date**: 2026-09-21  
**Sources**: Reference implementations + literature

---

## 1. Valence (Rust) - `valence_chunk` crate

### Internal Representation (from source knowledge):
- **Chunk storage**: Section-based (`Section` struct per 16x16x16)
- **Block state representation**: Variable-width palette into `Box<[u64]>`
- **Palette**: 
  - Linear palette for small state counts (≤16)
  - HashMap-backed for medium (≤256) 
  - Global registry for large (>256)
- **Wire encoding**: Direct serialization from packed u64 slice + palette
- **Zero-copy**: Encodes section packets directly from packed storage

### Key Architecture:
```rust
// Simplified from valence_chunk
struct Section {
    blocks: Box<[u64]>,           // Packed bit array
    palette: Palette,             // Linear / HashMap / Global
    light: LightData,             // Block + sky light
    heightmap: Heightmap,
}
```

### Applicability to 1.12.2:
- Direct architectural match for Protocol 340 section format
- Palette transition logic mirrors vanilla exactly

---

## 2. Lithium (Java/Fabric) - Block State Optimizations

### Key Optimizations (from literature + Fabric source):
- **Section metadata caching**: 
  - `has_random_ticks` boolean
  - `is_completely_air` boolean  
  - `is_completely_solid` boolean
  - `opaque_block_count` integer
- **Short-circuit paths**: Skip lighting/collision/serialization for uniform sections
- **Palette specialization**: 
  - Fast path for single-state sections (empty/full)
  - Fast path for 4-bit sections (most common in overworld)
  - Avoids object allocation in hot paths

### Applicability to 1.12.2:
- M4 `NativeSection` already has `non_air_count` and flags for instant section classification
- Lithium's metadata caching maps directly to `NativeSection.flags`

---

## 3. Oxide (C++) - Section-Oriented Chunk Model

### Architecture (from public documentation):
- **Chunk = Array of optional Sections** (16 vertical sections)
- **Palette**: Dynamic bit width (4–16 bits), packed u64 array
- **Memory layout**: Contiguous vertical columns for noise/height calculations
- **Wire encoding**: Direct section-to-packet mapping

### Key Insight for 1.12.2:
- Confirms section-level palette containers mapped directly to wire format yield minimal latency
- 64-byte alignment on section buffers enables SIMD scanning

---

## 4. Cuberite (C++) - Modern Section Architecture

### Evolution:
- **Legacy (1.8)**: Flat 16x16x256 arrays of `BLOCKTYPE` (u8) + `NIBBLETYPE` (u4 meta)
- **Modern (1.12+)**: Sectioned into 16x16x16 with palette

### SIMD Optimizations:
- 64-byte alignment on block arrays
- AVX2 scanning for air/solid classification
- Fast lighting propagation via packed bit operations

### 1.12.2 Gap:
- Cuberite's 1.8 columnar layout requires expensive runtime conversion to Protocol 340 section format
- Validates that **native storage should match wire format** to avoid conversion

---

## 5. fastanvil / fastnbt (Rust) - NBT/Region Parsing

### Architecture:
- Zero-copy parsing of Anvil MCA region files
- Direct decompression into section block arrays
- SIMD-accelerated decompression (zlib-rs + SIMD)

### Applicability:
- Second consumer proof: Persistence staging from `NativeChunk` → MCA format
- `NativeChunk::stage_persistence` mirrors this design

---

## 6. Minecraft Modern (1.16+) vs 1.12.2 Palette Differences

| Aspect | 1.12.2 | 1.16+ |
|---|---|---|
| Global palette | `Block.BLOCK_STATE_IDS` (int IDs) | `BlockStateRegistry` (namespaced IDs) |
| BitArray spans | Yes (crosses 64-bit boundaries) | **No** (per-word, no spanning) |
| Local palette max | 256 states (8-bit) | 4096 states (12-bit) |
| Global palette trigger | bits > 8 | bits > 12 |
| Packet palette encoding | VarInt global IDs | VarInt namespaced IDs |

**Critical**: 1.12.2 `BitArray` **does cross 64-bit word boundaries** for 5, 6, 7, 9, 10, 12, 13 bit widths. Only 4, 8, 16 bit are word-aligned.

---

## 7. Synthesis: Palette Strategies for Generalized NativeSection

| Strategy | Description | Memory (per section) | Mutation | Wire Encoding | Best For |
|---|---|---|---|---|---|
| **A. Wire-Matching** | Store local palette + packed bits exactly as wire | 2–8 KB | Must rebuild on resize | Zero-copy (memcpy) | Packet-heavy workloads |
| **B. Canonical Global IDs** | Store `Block.BLOCK_STATE_IDS` u16 per block | 8 KB (4096×u16) | O(1) direct | Build palette on demand | Simulation/collision/lighting |
| **C. Hybrid (Recommended)** | Canonical IDs + cached local palette | ~8–12 KB | O(1) + lazy palette | Fast path from cache | Engine + packet + persistence |

---

## 8. Decision: Option C — Hybrid Canonical + Cached Palette

### Engine Representation (Authoritative):
```rust
pub struct NativeSection {
    // Authoritative block state - global registry IDs
    states: [u16; 4096],           // 8,192 bytes (covers all global IDs up to 65535)
    
    // Derived/cached for wire encoding
    palette_cache: Option<LocalPalette>,  // Built on first wire encode
    palette_dirty: bool,           // Invalidate on mutation
    
    // Lighting (unchanged)
    block_light: [u8; 2048],
    sky_light: [u8; 2048],
    
    // Metadata (Lithium-style)
    non_air_count: u16,
    flags: SectionFlags,
    y_index: u8,
}
```

### LocalPalette (Wire Format):
```rust
struct LocalPalette {
    bits: u8,
    ids: Vec<u32>,           // Global state IDs in palette order
    packed: Vec<u64>,        // Packed bit array matching wire layout
}
```

### Palette Build Algorithm (on demand):
1. Scan `states[4096]` → collect unique global IDs
2. If unique ≤ 16: bits=4, Linear palette
3. If unique ≤ 256: bits=ceil(log2(unique)), HashMap palette
4. If unique > 256: bits=global_bits, Global palette (write 0 palette)
5. Pack into `packed` using 1.12.2 BitArray spanning logic
6. Cache until next mutation

### Advantages:
- **Random access**: O(1) block get/set via `states[index]`
- **Wire encoding**: Fast path from cached palette
- **Mutation**: Invalidate cache (`palette_dirty = true`), no re-encode needed
- **Memory**: 8 KB base + small cache overhead
- **Persistence**: Direct from `states` array
- **Collision/lighting**: Direct from `states` without palette decode

---

## 9. Next: Implement Generalized NativeSection (Option C)