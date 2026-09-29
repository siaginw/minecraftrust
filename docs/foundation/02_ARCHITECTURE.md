# Architecture

## Long-Term Shape

```text
Minecraft 1.12.2 Forge Client
            |
            v
    Network / Protocol
            |
            v
+-------------------------------+
|        Rust Runtime Core      |
|-------------------------------|
| Transport / buffers           |
| Compression                   |
| NBT codec                     |
| Region file I/O               |
| Chunk storage                 |
| Scheduler                     |
| Spatial index                 |
| Entities                      |
| Lighting                      |
| Pathfinding                   |
| Persistence                   |
| Simulation                    |
+-------------------------------+
            ^
            |
    coarse compatibility boundary
            |
+-------------------------------+
| Embedded / attached JVM       |
|-------------------------------|
| Forge APIs                    |
| Existing mods                 |
| Legacy Java compatibility     |
+-------------------------------+
```

This is the **destination**, not the starting point.

## Initial Shape

```text
Existing Forge 1.12.2 server
            |
            +---- Java gameplay / mods
            |
            +---- Native Rust modules
                    |
                    +-- buffers
                    +-- compression
                    +-- NBT codec
                    +-- region I/O
                    +-- networking helpers
                    +-- later: lighting/pathfinding/etc.
```

## Architectural Rule: Move Ownership, Not Just Code

A migration is incomplete if Rust computes something but Java still:
- owns the authoritative state
- duplicates the same data
- copies large structures in and out every tick
- requires one FFI call per tiny operation

For each subsystem, explicitly define:
- authoritative owner
- read path
- write path
- serialization boundary
- synchronization rules
- lifetime
- rollback behavior
- benchmark

## Data-Oriented Direction

Prefer:
- compact IDs
- arrays / slabs / arenas
- contiguous data
- immutable snapshots where useful
- command buffers for mutation
- per-region ownership

Avoid:
- pointer-heavy object graphs in hot Rust paths
- duplicate object mirrors across Java and Rust
- per-object allocation across FFI
