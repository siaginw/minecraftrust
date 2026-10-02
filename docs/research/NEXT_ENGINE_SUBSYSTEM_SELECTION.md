# Selection of Next Engine Subsystem for Rust Migration

## 1. Executive Summary

With the `NativeChunk` core performance optimization and memory contract pass complete, `NativeChunk` now operates as a high-throughput, memory-sound living chunk engine (2.76 ns zero-JNI reads, 105.6 ns authoritative writes, 732 ns packet serialization). 

Before expanding semantic authority into new domains, we evaluated the candidate subsystems in terms of:
1. **Leverage & ROI**: Potential CPU and memory reduction for the overall server.
2. **Coupling & Mod Hazard**: Risk of breaking Forge mod mixins, event listeners, or custom block behavior.
3. **Rust Autonomy**: Degree to which the subsystem can operate purely within native state without constant round-trips to Java.

---

## 2. Subsystem Candidate Evaluation

### Candidate A: Chunk Storage / Region Files / Anvil (Phase 4)
- **Scope**: Reading and writing `.mca` region files, parsing and serializing NBT data in Rust (`simdnbt` / custom reader).
- **Pros**:
  - High leverage: Region file I/O and zlib/gzip decompression are notorious CPU bottlenecks during world loading and saving.
  - Well-defined boundaries: Region files and NBT schemas are bit-level standardized.
  - Rust excels at disk I/O, memory-mapped files, and streaming decompression.
- **Cons**:
  - Forge mod data: Modded tile entities and entities write custom NBT tags (`writeToNBT`). Without a full modded NBT registry, Rust cannot serialize arbitrary mod state independently.
- **Verdict**: **Strong Candidate for Phase 4**, but best approached after packet wire serialization is fully autonomous.

### Candidate B: Chunk Lighting Propagation Algorithms (Phase 5)
- **Scope**: Migrating the Phosphor-style flood-fill lighting propagation algorithm into Rust.
- **Current State**: `block_light` and `sky_light` *data arrays* are already owned by Rust `NativeSection` (`AtomicU32`). Currently, Java Phosphor executes light updates and commits nibble values into Rust via zero-JNI direct memory or CAS.
- **Pros**:
  - Eliminates light queue overhead on the JVM main thread.
  - Data structures are already in Rust memory!
- **Cons**:
  - Tight coupling to block opacity and light opacity checks across chunk borders. If adjacent chunks are not yet loaded in Rust, light propagation requires complex boundary synchronization.
- **Verdict**: Logical follow-up once multi-chunk spatial indexing is implemented.

### Candidate C: Spatial Voxel Collision & Raycasting (Phase 5 Seam)
- **Scope**: Axis-aligned bounding box (AABB) intersection and raycasting through chunk blocks.
- **Pros**:
  - Entities and players query block collision every tick (`World.getCollisionBoxes`).
  - Read-heavy: Uses `getBlockState` which is already in Rust direct memory!
- **Cons**:
  - Custom block collision boxes (`Block.getCollisionBoundingBox`) depend on modded Java method calls.
- **Verdict**: Medium priority; benefits from zero-JNI block reads already in place.

### Candidate D: Unbuffered Native SPacketChunkData Wire Emission (Phase 3 Final Seam)
- **Scope**: Emitting `SPacketChunkData` Netty network buffers directly from `NativeChunk` wire cache into Netty channels, eliminating intermediate Java byte arrays entirely.
- **Pros**:
  - Closes Phase 3 completely.
  - Immediate payoff: Static wire cache is already 732 ns; sending it directly over the socket bypasses Java GC allocation.
  - Zero mod compatibility risk: Network packet payload bytes are 100% Protocol 340 compliant.
- **Verdict**: **Recommended Immediate Subsystem** to finalize Phase 3.

---

## 3. Decision Matrix

| Subsystem | Architectural Leverage | Mod Compatibility Risk | Readiness | Recommendation |
|:---|:---:|:---:|:---:|:---:|
| **Direct Netty Chunk Packet Wire Emission** | High (eliminates packet byte allocations) | None (bit-exact Protocol 340) | Immediate (wire cache exists) | **Selected for Immediate Step** |
| **Anvil / Region File I/O (Phase 4)** | Very High (removes Java I/O bottleneck) | Medium (modded NBT tags) | High (requires NBT parser) | **Phase 4 Target** |
| **Lighting Propagation (Phase 5)** | High (multi-threaded light engine) | Low-Medium (chunk borders) | Medium (arrays ready, needs graph) | **Phase 5 Target** |
| **Block Collision / Raycasting** | Medium (tick acceleration) | High (modded bounding boxes) | Medium | **Phase 5 Target** |

---

## 4. Conclusion

The recommended trajectory is:
1. **Complete Phase 3 Wire Emission**: Wire packet broadcast directly into Netty byte buffers without Java byte copying.
2. **Transition to Phase 4 (Anvil / Region Storage)**: Implement native region file loading/saving in Rust using `simdnbt` principles.
