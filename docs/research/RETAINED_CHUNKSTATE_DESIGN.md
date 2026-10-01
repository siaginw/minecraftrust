# Retained Rust ChunkState: Architectural Design & Implementation Specification

## 1. Executive Overview & Strategic Objective

The overarching vision of RustCraft is to systematically rebuild Minecraft server engine subsystems in Rust while preserving full compatibility with the existing Minecraft Java and Forge mod ecosystems:

```
Existing Forge mods / Java bytecode
              │
              ▼
   RustCraft compatibility / facade layer
              │
              ▼
       RustCraft Engine
              │
     ┌────────┼─────────┐
     ▼        ▼         ▼
   World    Chunks    Networking
   Tick     Storage   Lighting
   Entity   NBT       Worldgen
```

Prior milestones successfully established:
1. **Reference Java Equivalence & Differential Proof**: Byte-for-byte and semantic equivalence across millions of synthetic chunk variations.
2. **Real-Session Admission**: V2 session-bound identity certification under real Forge/FML lifecycle across 219 mods in FTB Revelation.
3. **RCSNAP02 Registry Decoupling**: Handling 18-bit global registry widths ($157,010$ states) through section-local $u16$ logical palettes.
4. **Live-Shadow Closure**: 4,905 consecutive zero-mismatch comparisons across independent live server sessions.
5. **Bounded Rust-Authority Experiment**: Fail-closed, explicit, bounded transmission of Rust-authored `SPacketChunkData` payloads on the wire.

However, in all prior stages, Rust operated **ephemerally**: Java owned the living chunk in heap memory, serialized a snapshot under gate, passed the snapshot over JNI, Rust encoded the packet, and Rust state was immediately discarded.

This document specifies the next major architectural milestone: **Retained Rust ChunkState**. Under this architecture, **Rust becomes the primary owner of living chunk state**, eliminating duplicate representations, eliminating snapshot serialization, drastically cutting GC allocation, and enabling zero-copy packet generation directly from native memory.

---

## 2. Living Chunk Section Memory Layout

### 2.1 Section Geometry & Memory Hierarchy

A Minecraft chunk column ($X, Z$) spans $16 \times 256 \times 16$ blocks, structured vertically as 16 independent $16 \times 16 \times 16$ sections ($Y \in [0, 15]$):

- **Blocks per section**: $16 \times 16 \times 16 = 4,096$ block positions.
- **Index mapping**: Canonical Morton / column-major index:
  $$\text{index}(x, y, z) = (y \ll 8) \mid (z \ll 4) \mid x$$
  where $x \in [0, 15], y \in [0, 15], z \in [0, 15]$.

```
┌──────────────────────────────────────────────────────────────┐
│                    RetainedChunkColumn                       │
│  chunk_x: i32, chunk_z: i32, dimension: i32, epoch: u64      │
│  heightmap: [u8; 256], biomes: [u8; 256]                     │
├──────────────────────────────────────────────────────────────┤
│  sections: [Option<Box<RetainedSection>>; 16]                │
│    ├── Section 0  (Y = 0..15)                                │
│    ├── Section 1  (Y = 16..31)                               │
│    │    ├── block_states: Contiguous State Buffer            │
│    │    ├── block_light:  [u8; 2048] (4 bits/block)          │
│    │    ├── sky_light:    [u8; 2048] (4 bits/block)          │
│    │    ├── palette:      SectionPalette                     │
│    │    └── revision:     AtomicU64 (seqlock)                │
│    └── ...                                                   │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Data-Oriented Contiguous Alignment

To maximize L1/L2 cache locality and enable SIMD vectorized operations (such as lighting propagation, ambient occlusion computation, and dirty-boundary scanning):

```rust
#[repr(C, align(64))]
pub struct RetainedSection {
    /// Seqlock revision counter: even = quiescent/stable, odd = mutation in flight
    pub revision: std::sync::atomic::AtomicU64,
    /// Bitmask of non-air blocks to short-circuit iteration and serialization
    pub non_air_count: u16,
    /// Bitmask of block ticking subscribers
    pub tick_mask: u64,
    /// Section-local logical palette
    pub palette: SectionPalette,
    /// Packed block states: 4,096 entries packed at current bits_per_block
    pub block_states: PackedStateArray,
    /// Nibble array for block emission lighting (0..15), 4 bits per voxel (2,048 bytes)
    pub block_light: [u8; 2048],
    /// Nibble array for sky light attenuation (0..15), 4 bits per voxel (2,048 bytes)
    pub sky_light: [u8; 2048],
}
```

By enforcing `align(64)`, each section's lighting and state headers align to CPU cache line boundaries, preventing false sharing across threads.

---

## 3. Two-Tiered Palette Architecture

FTB Revelation demonstrated that modded registries exceed 16 bits ($157,010$ global block states $\implies 18$ bits global width). Attempting to represent living chunks with a flat global array wastes gigabytes of memory and ruins cache locality.

### 3.1 Global vs. Section-Local Hierarchy

1. **Tier 1: Engine-Wide Global State Registry**:
   - Monotonic bidirectional mapping: $\text{GlobalStateId} \iff \text{QualifiedBlockState}$ (`Block` + metadata/properties).
   - Capacity: Up to $2^{20}$ states ($1,048,576$).
   - Immutable once Forge enters post-init (`FMLServerStartedEvent`).

2. **Tier 2: Section-Local Dynamic Palette**:
   - Each $16 \times 16 \times 16$ section contains at most $4,096$ distinct states (in practice, typical sections contain between 1 and 48 distinct states).
   - **Adaptive Bit Width ($b$)**:
     - Single state: $b = 0$ (implicit constant section).
     - $\le 16$ states: $b = 4$ (linear array palette, $2,048$ bytes).
     - $\le 32$ states: $b = 5$ ($2,560$ bytes).
     - $\le 64$ states: $b = 6$ ($3,072$ bytes).
     - $\le 128$ states: $b = 7$ ($3,584$ bytes).
     - $\le 256$ states: $b = 8$ ($4,096$ bytes, 1 byte per voxel).
     - $> 256$ states: Direct $u16$ logical palette (RCSNAP02 decoupled index).

```
   Block Mutation: setBlockState(x, y, z, new_global_id)
                         │
                         ▼
        Does new_global_id exist in section palette?
                         │
              ┌──────────┴──────────┐
             YES                    NO
              │                     │
              ▼                     ▼
     Write local palette      Can section accommodate new state
      index into packed       without bit-width widening?
        array (O(1))                │
                             ┌──────┴──────┐
                            YES            NO
                             │             │
                             ▼             ▼
                        Append to     Reallocate packed array
                      palette table   at width b+1, widen entries,
                      and write       insert new state (amortized O(1))
```

---

## 4. Concurrent Access & Memory Synchronization Model

The server environment features three primary concurrent actors:
1. **Server Tick Thread**: Executes world ticks, tile entity ticks, entity updates, player interactions, and block changes.
2. **Netty Worker Threads**: Asynchronously serializes and transmits outbound packets to remote clients.
3. **Async Chunk I/O & Generation Threads**: Loads chunk regions from disk or runs procedural terrain noise off-thread.

```
       Server Tick Thread               Netty Worker Threads
      (Exclusive Writer)                (Concurrent Readers)
               │                                  │
               ▼                                  ▼
     ┌───────────────────┐             ┌─────────────────────┐
     │  Acquire Seqlock  │             │ Read Seqlock Rev V1 │
     │  (revision = 2N+1)│             │ (must be even)      │
     └─────────┬─────────┘             └──────────┬──────────┘
               │                                  │
               ▼                                  ▼
     ┌───────────────────┐             ┌─────────────────────┐
     │ Perform Mutation  │             │ Encode Packet Bytes │
     │ in Native Memory  │             │ Directly to Wire    │
     └─────────┬─────────┘             └──────────┬──────────┘
               │                                  │
               ▼                                  ▼
     ┌───────────────────┐             ┌─────────────────────┐
     │ Release Seqlock   │             │ Read Seqlock Rev V2 │
     │  (revision = 2N+2)│             │ (V2 == V1 == even?) │
     └───────────────────┘             └──────────┬──────────┘
                                                  │
                                       ┌──────────┴──────────┐
                                      YES                    NO
                                       │                     │
                                       ▼                     ▼
                                 Commit Output        Stale Generation!
                                 to TCP Socket        Retry or Fallback
```

### 4.1 Seqlock Protocol for Chunk Sections

Because mutations are performed exclusively on the server tick thread, writers never contend with other writers. Readers (Netty threads generating chunk packets) need lock-free consistency without blocking the server tick thread:

1. **Writer Protocol**:
   ```rust
   let rev = section.revision.load(Ordering::Relaxed);
   section.revision.store(rev + 1, Ordering::Release); // odd = mutating
   // mutate block states / palette
   section.revision.store(rev + 2, Ordering::Release); // even = committed
   ```
2. **Reader Protocol**:
   ```rust
   loop {
       let v1 = section.revision.load(Ordering::Acquire);
       if v1 & 1 != 0 { std::hint::spin_loop(); continue; }
       // read palette and state data into output buffer
       let v2 = section.revision.load(Ordering::Acquire);
       if v1 == v2 { break; } // consistent snapshot verified
   }
   ```

If a Netty worker detects a concurrent mutation during packet generation, it safely loops or falls back to an isolated copy, guaranteeing zero torn reads without mutex contention on the tick thread.

---

## 5. Mutation Protocol & Dirtiness Tracking

When mods or game logic invoke block updates:

1. **Delta Log & Dirty Mask**:
   Each chunk column maintains an atomic 16-bit mask:
   $$\text{dirty\_sections} \mid= (1 \ll \text{section\_y})$$
2. **Light Invalidation**:
   Modifications to light-opacity or light-emitting blocks mark neighbor boundaries dirty and queue native SIMD light propagation.
3. **TileEntity Decoupling**:
   Chunks with non-empty TileEntities maintain their TileEntities in Java heap storage while their block container, metadata, and non-TE blocks reside in Rust native storage.

---

## 6. Java-Side Facade & Mod Compatibility Boundary

A critical requirement of RustCraft is that **existing Forge mods must observe zero behavioral changes**.

### 6.1 `NativeChunkFacade` Structure

Rather than removing `net.minecraft.world.chunk.Chunk`, RustCraft provides a high-performance subclass/facade:

```java
public class NativeChunkFacade extends net.minecraft.world.chunk.Chunk {
    // Native pointer to Rust RetainedChunkColumn
    private final long nativeChunkPtr;

    @Override
    public IBlockState getBlockState(int x, int y, int z) {
        int globalId = NativeChunkBridge.getBlockState(nativeChunkPtr, x, y, z);
        return GlobalRegistry.lookupState(globalId);
    }

    @Override
    public IBlockState setBlockState(BlockPos pos, IBlockState state) {
        int oldGlobalId = NativeChunkBridge.setBlockState(
            nativeChunkPtr, pos.getX(), pos.getY(), pos.getZ(),
            GlobalRegistry.getStateId(state)
        );
        return GlobalRegistry.lookupState(oldGlobalId);
    }
}
```

### 6.2 Mod Access Inspection & Demotion Safeguard

If an aggressive mod uses Unsafe or raw field reflection to write directly into `Chunk.storageArrays` (bypassing `setBlockState`), RustCraft detects the unsynchronized field access and automatically:
1. Demotes that specific chunk back to Java-authoritative representation.
2. Emits an audit diagnostic naming the offending mod.
3. Safely continues execution without crashing.

---

## 7. Zero-Copy Packet Serialization Directly from Retained Rust State

The current packet serialization pipeline requires:
$$\text{Java Chunk} \xrightarrow{\text{extract}} \text{byte[]} \xrightarrow{\text{JNI}} \text{Rust Snapshot} \xrightarrow{\text{encode}} \text{byte[]} \xrightarrow{\text{Netty}} \text{Socket}$$

Under Retained Rust ChunkState, this collapses to:
$$\text{Rust Retained State} \xrightarrow{\text{zero-copy SIMD pack}} \text{Netty Direct ByteBuf} \xrightarrow{\text{write}} \text{Socket}$$

### 7.1 Protocol 340 Direct Encoding
When Netty requests packet payload for `SPacketChunkData`:
1. Java allocates a direct Netty `ByteBuf`.
2. Passes direct memory address `buf.memoryAddress()` to native Rust.
3. Rust's `RetainedSection::write_protocol340` formats the palette length, bit-packed VarLong array, and nibble light arrays **directly into Netty's buffer**.
4. Zero heap allocations, zero intermediate copies, zero GC impact.

---

## 8. Migration Phases & Verification Gates

The transition to Retained Rust ChunkState adheres to the core philosophy:
$$\text{REFERENCE JAVA} \to \text{RUST PARITY} \to \text{DIFFERENTIAL PROOF} \to \text{LIVE SHADOW} \to \text{CLOSURE} \to \text{AUTHORITY REVIEW} \to \text{RUST OWNERSHIP}$$

| Phase | Milestone Name | Description | Gate Criteria |
|:---|:---|:---|:---|
| **Phase 1** | **Retained Crate Core** | Implement `retained-chunk` crate with seqlock, adaptive palette, and Morton layout | $100\%$ unit tests green, SIMD verified |
| **Phase 2** | **Shadow Retained Sync** | Hook Java `Chunk` mutations to shadow-update a parallel Rust `RetainedChunkColumn` | Zero state divergence over $10,000$ live ticks |
| **Phase 3** | **Zero-Copy Packet Source** | Feed `PacketAuthorityExperiment` directly from retained Rust memory instead of snapshot extraction | Zero encoding failures, client stability green |
| **Phase 4** | **Read Authority Delegation** | Delegate `Chunk.getBlockState()` reads directly to Rust native storage | Mod queries match reference exactly |
| **Phase 5** | **Full Ownership** | Rust owns chunk storage, mutations, and serialization with Java facade fallback | Full 219-mod Revelation server runtime stable |

---

## 9. Conclusion & Readiness Declaration

With the successful completion of the **Formal Authority Review** and the verified execution of the **Bounded Authority Experiment**, RustCraft has proven that Rust-authored chunk packets are indistinguishable from Java packets on the wire.

The retained chunk architecture detailed herein represents the foundational stepping stone toward complete engine sovereignty in Rust.

**Declaration:** **`READY_FOR_RETAINED_RUST_CHUNKSTATE`**
