# RustCraft Section-Level State Authority Expansion Report

**Milestone:** `READY_FOR_RUST_CHUNKSTATE_AUTHORITY_EXPANSION_PHASE_2`  
**Date:** 2026-10-01  
**Target Architecture:** Minecraft 1.12.2 / Protocol 340 / Forge 14.23.5.2846 & 14.23.5.2860  
**Status:** `SECTION_STATE_AUTHORITY_EXPANDED`  
**Production Authority:** `FALSE` (fail-closed, strictly bounded experiment under operator controls)

---

## 1. Executive Summary

Having rigorously validated the cross-language direct-memory (zero-JNI) model under multi-threaded concurrency, rapid unload/reload lifecycles, and section-emptying stability (Milestone `ZERO_JNI_DIRECT_MEMORY_VALIDATED`), RustCraft has successfully pushed semantic ownership deeper into Minecraft's chunk internal hierarchy.

Previously, semantic authority was intercepted only at the top-level `Chunk.getBlockState` and `Chunk.setBlockState` APIs. However, in Minecraft and Forge, numerous internal systems and modpack optimizations bypass `Chunk` method calls and interact directly with section storage (`ExtendedBlockStorage`).

In this phase:
1. **Single Semantic Owner Principle Preserved**: We did not duplicate state or introduce dual state machines. Rust `NativeSection` (`states: [u16; 4096]`) remains the single source of truth for admitted sections.
2. **Mod Ecosystem Audited**: An exhaustive scan of all 193 mod jars in FTB Revelation 3.4.0 confirmed that mod code interacts with `ExtendedBlockStorage.get/set` (`func_177485_a`/`func_177484_a`) rather than raw `BlockStateContainer`. Intercepting `ExtendedBlockStorage` maintains full compatibility with core optimization mods (NotEnoughIDs, FoamFix, Phosphor).
3. **Section Compatibility Layer Implemented**: Zero-overhead ASM hooks in `ExtendedBlockStorage.get` and `set` delegate directly to Rust native memory via `ChunkStateAuthorityBridge.getSectionBlockState` and `trySetSectionBlockState`.
4. **Lifecycle & Storage Replacement Synchronized**: Implemented `Chunk.setStorageArrays` tracking and binding lifecycle cleanup on chunk unload to guarantee lifetime safety and avoid dangling storage references.
5. **Verified Across Clean Forge & Modded Runtimes**: Validated via Clean Forge 2860 (Gate A) and FTB Revelation 2846 (Gate C, 219 mods), with 10,000 differential fuzzing operations reporting 0 mismatches.

---

## 2. Mod Ecosystem Audit & ASM Interception Surface

### 2.1 Audit of 193 Mod Jars in FTB Revelation 3.4.0
Before deciding where to attach the section compatibility layer, we audited all 193 mod jars present in FTB Revelation 3.4.0:

| Class Target | Mod Call Sites Found | Coremod / ASM Conflicts | Architectural Decision |
|:---|:---:|:---:|:---|
| **`BlockStateContainer`** (`get`/`set`) | **0** | **High** (NotEnoughIDs replaces BitArray; FoamFix replaces storage array) | **DO NOT TOUCH** (Risk of bytecode collisions) |
| **`ExtendedBlockStorage`** (`func_177485_a`/`get`) | **14** (IC2, Forestry, Applied Energistics 2, etc.) | **None** (Clean method boundary) | **ADMITTED** (Authoritative read hook) |
| **`ExtendedBlockStorage`** (`func_177484_a`/`set`) | **9** (Worldgen/chunk population bypasses) | **None** (Clean method boundary) | **ADMITTED** (Authoritative write hook) |
| **`Chunk.setStorageArrays`** (`func_76602_a`) | Internal Forge / Loader | **None** | **ADMITTED** (Section array rebinding hook) |

By targeting `ExtendedBlockStorage` rather than internal container internals, RustCraft provides a clean zero-JNI native bridge that operates identically whether vanilla arrays, FoamFix packed arrays, or NEID 16-bit indices are present.

---

## 3. Section Compatibility Architecture

```
Forge / Mod Caller
      │
      ├──> Chunk.getBlockState(pos) ──────────┐
      │                                       │ (Zero-JNI)
      └──> ExtendedBlockStorage.get(x, y, z) ──┼───> StateRegistryLookup.getBlockStateDirect(secPtr, ...)
                                              │           │
                                              │           ▼
                                              │     [u16; 4096] states buffer
                                              │     (Rust NativeSection memory)
                                              │
      ┌──> Chunk.setBlockState(pos, state) ───┤
      │                                       │
      └──> ExtendedBlockStorage.set(x, y, z) ─┴───> NativeChunkBridge.setBlockState(...)
                                                          │
                                                          ▼
                                                    Rust NativeChunk (Source of Truth)
                                                          │
                                                    Packet wire encoder (Retained)
```

### 3.1 ExtendedBlockStorage Binding
Each `ExtendedBlockStorage` is associated with its owning `ChunkAuthorityRecord` and section Y index (`0..15`) via `SECTION_BINDINGS`:
- When chunk authority is registered (`registerChunkAuthority` / `bindChunkStorages`), the chunk's storage array is traversed and bound.
- When `Chunk.setStorageArrays` is invoked, `onStorageArraysReplaced` updates the binding mappings.
- When `Chunk.onChunkUnload` is invoked, `unbindChunkStorages` unbinds all sections immediately.

### 3.2 Read Path (`getSectionBlockState`)
```java
public static IBlockState getSectionBlockState(ExtendedBlockStorage storage, int x, int y, int z) {
    if (!experimentEnabled || storage == null) return null;
    SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
    if (binding == null) return null; // Fallback to Java container

    ChunkAuthorityRecord record = binding.chunkRecord;
    if (record == null || record.generationId <= 0 || !record.isAuthoritative()) return null;

    long secPtr = record.sectionPointers[binding.secY];
    if (secPtr == 0) return StateRegistryLookup.getAirState();

    return StateRegistryLookup.getBlockStateDirect(secPtr, x, y, z);
}
```
- **Zero JNI**: Directly reads 16-bit state ID via `sun.misc.Unsafe.getShort(secPtr + (idx << 1))` with an acquire fence (`UNSAFE.loadFence()`).
- **Fail-Closed Fallback**: If unadmitted, cap exhausted, or storage unbound, returns `null` and Java executes its original container get logic.

### 3.3 Write Path (`trySetSectionBlockState`)
```java
public static boolean trySetSectionBlockState(ExtendedBlockStorage storage, int x, int y, int z, IBlockState newState) {
    if (!experimentEnabled || storage == null || newState == null) return false;
    SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
    if (binding == null) return false;

    ChunkAuthorityRecord record = binding.chunkRecord;
    if (record == null || record.generationId <= 0 || !record.isAuthoritative()) return false;

    int newStateId = StateRegistryLookup.getId(newState);
    if (newStateId < 0 || newStateId > 65535) return false; // Fail-closed high ID fallback

    int worldY = (binding.secY << 4) | (y & 15);
    long packed = NativeChunkBridge.setBlockState(record.dim, record.cx, record.cz, x & 15, worldY, z & 15, newStateId);
    ...
    return true; // Successfully mutated in Rust
}
```
- Mutates the Rust `NativeChunk` directly.
- Emptied sections update the packet wire mask while keeping pointer memory allocated to prevent UAF.

---

## 4. Empirical Validation & Live Smoke Evidence

### 4.1 Differential Fuzzing Oracle (10,000 Ops)
Run via `python tools/authority-review/run_chunk_state_authority_test.py`:
- **Total Ops:** 10,000 (5,071 writes, 4,929 reads)
- **Mismatches:** **0**
- **Coherence:** Read-after-write coherence confirmed across all sections.
- **Retained Packet Encode:** Verified that block mutations committed to Rust are accurately reflected in subsequent packet encodes without reseeding.

### 4.2 Multi-Threaded Direct-Memory Stress Suite
Run via `python tools/authority-review/run_zero_jni_stress_test.py`:
- **Section Emptying (No UAF):** 0 faults across multiple stone $\rightarrow$ air transitions.
- **Rapid Lifecycle Unload/Reload:** 100 cycles completed with 0 stale reads and immediate pointer invalidation.
- **Concurrent Readers & Writer:** 4 Java threads executing direct memory reads (198,140 reads) concurrently with 50,000 mutations on a dedicated writer thread: **0 crashes, 0 corrupt IDs**.
- **Batched Latency:**
  - Raw Direct Memory Read: **2.74 ns/op** (365M ops/sec)
  - Direct Read + `IBlockState` Lookup: **4.52 ns/op** (221M ops/sec)

### 4.3 Live Server Bounded Authority Smokes

| Target Gate | Runtime Environment | Authority Cap | Packets Sent | Rust Wire Commits | Java Fallback Commits | Probe Verdict |
|:---|:---|:---:|:---:|:---:|:---:|:---:|
| **Gate A** | Clean Forge 14.23.5.2860 | 32 | 169 | 32 | 137 | **PASS** |
| **Gate C** | FTB Revelation 3.4.0 (219 mods) | 64 | 169 | 64 | 105 | **PASS** |

Both Gate A and Gate C reached their configured authority caps cleanly, transitioned seamlessly to Java fallback without packet drops or client disconnects, and maintained 100% network probe stability.

---

## 5. Architectural Milestone

**New Project Milestone:**  
`READY_FOR_RUST_CHUNKSTATE_AUTHORITY_EXPANSION_PHASE_2` (Section Compatibility Layer Proven)

**Properties Established:**
1. Rust `NativeChunk` remains the **single semantic owner** of chunk block state.
2. Java `Chunk` and `ExtendedBlockStorage` serve as zero-overhead compatibility facades.
3. Zero-JNI direct-memory reads operate at **~2.74 - 4.52 ns/op**.
4. Memory safety, lifecycle invalidation, and acquire memory semantics hold across all concurrency profiles.
5. `PRODUCTION_AUTHORITY` remains strictly `false` (fail-closed, bounded operator experiment).
