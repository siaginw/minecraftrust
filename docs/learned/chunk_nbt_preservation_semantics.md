# Chunk NBT Preservation Semantics & Codec Guarantees

## 1. Defining NBT Equivalence: Semantic vs Byte Identity
In binary NBT (Named Binary Tag), an `NBTTagCompound` represents an unordered mapping of string keys to typed payload values.
- **Semantic Identity:** Two NBT compounds are *semantically identical* if and only if they possess identical key sets, where each key maps to an identical tag type and semantically equal value (recursively for compounds and lists).
- **Byte Representation:** Because standard hash-map implementations (such as Java's `HashMap` used in `NBTTagCompound`) do not guarantee deterministic iteration order across JVM versions or executions, two semantically identical NBT compounds can produce **different byte streams** when serialized.
- **Compatibility Invariant:** Minecraft 1.12.2 and Forge mods observe NBT through Java semantic getters (`getString`, `getCompoundTag`, `hasKey`). Byte-for-byte serialization identity is **not** an observable contract of the reference game engine. Future Rust implementations must guarantee semantic equivalence; byte layout normalization is expected and safe.

---

## 2. Three Distinct Processing Paths

### Path A: Raw NBT Codec Round-Trip
$$\text{Binary Bytes} \longrightarrow \text{NBT Parser} \longrightarrow \text{In-Memory Compound Tree} \longrightarrow \text{NBT Writer} \longrightarrow \text{Binary Bytes}$$
- Tests the generic NBT parser/serializer in isolation.
- Operates on untyped, arbitrary tag structures.

### Path B: Raw Anvil Payload Round-Trip
$$\text{RegionFile Sector} \longrightarrow \text{ZLIB Inflate} \longrightarrow \text{NBT Manipulation} \longrightarrow \text{ZLIB Deflate} \longrightarrow \text{RegionFile Sector}$$
- Tests chunk storage containers without constructing Minecraft game objects.
- Does not invoke `Chunk`, `World`, `Entity`, or `ExtendedBlockStorage` constructors.

### Path C: Minecraft Semantic Chunk Round-Trip
$$\text{RegionFile} \longrightarrow \text{AnvilChunkLoader} \longrightarrow \text{Chunk Object Model} \longrightarrow \text{saveChunk()} \longrightarrow \text{RegionFile}$$
- The authoritative game lifecycle path.
- Deconstructs raw NBT into typed JVM objects:
  - `Sections` $\rightarrow$ `ExtendedBlockStorage[]`
  - `Entities` $\rightarrow$ `List<Entity>`
  - `TileEntities` $\rightarrow$ `Map<BlockPos, TileEntity>`
  - `ForgeCaps` $\rightarrow$ `CapabilityDispatcher`
- Reconstructs brand-new `NBTTagCompound` structures from scratch during `saveChunk()`.

---

## 3. Unknown Tag Preservation Matrix

| Injection Location | Path A: Raw NBT Codec | Path B: Raw Anvil Storage | Path C: Semantic Chunk Save | Failure Mechanism in Path C |
| :--- | :--- | :--- | :--- | :--- |
| **Root Tag** (sibling to `Level`) | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`DROPPED`** | `saveChunk()` instantiates a brand new root compound containing only `Level` and `DataVersion`. Unknown siblings are discarded. |
| **Level Tag** (unknown child of `Level`) | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`DROPPED`** | `writeChunkToNBT()` explicitly writes known keys (`xPos`, `Sections`, etc.). Unrecognized tags in `Level` are ignored. |
| **Section Tag** (unknown child of `Section`) | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`DROPPED`** | `ExtendedBlockStorage` only stores voxel IDs and light nibbles. It has no container for unknown section tags. |
| **Entity Custom Tag** | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`DROPPED`** | Vanilla `Entity.writeToNBT()` writes hardcoded field lists. Custom fields drop unless backed by registered Forge capabilities. |
| **TileEntity Custom Tag** | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`NORMALIZED`** / **`PRESERVED`** | Vanilla tile entities read known keys; Forge tile entities serialize via `writeToNBT(compound)`. Unknown keys dropped unless mod preserves them. |
| **ForgeCaps** (`Level.ForgeCaps`) | `BYTE_IDENTICAL` | `BYTE_IDENTICAL` | **`SEMANTICALLY_PRESERVED`** | Forge explicitly deserializes into `chunk.capabilities` on load and serializes via `capabilities.serializeNBT()` on save. |

### Architectural Conclusion for Rust Migration:
1. Pure storage and codec layers (Paths A & B) can and must preserve all arbitrary NBT data.
2. The semantic game layer (Path C) in Minecraft 1.12.2 is **lossy** by design: arbitrary unknown tags injected outside registered capability domains do **not** survive a save/reload cycle in the reference server.
3. Therefore, any hybrid or full Rust runtime must faithfully replicate Forge's capability dispatch mechanism to preserve mod data, rather than assuming vanilla Java preserves arbitrary unmapped tags.
