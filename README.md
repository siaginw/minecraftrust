# RustCraft Minecraft 1.12.2 Rust Server Foundation

## Project Goal

Build a **Minecraft Java Edition 1.12.2 / Forge 14.23.5.x compatible server runtime** that begins as a high-compatibility Java/Forge system with selectively accelerated Rust subsystems, then progressively moves ownership into Rust until the server can operate as a predominantly or fully Rust implementation.

The project is **not** "rewrite Minecraft in Rust as quickly as possible."

The project is:

1. Learn the exact behavior and architecture of Minecraft 1.12.2 + Forge.
2. Build a permanent test, profiling, documentation, and compatibility foundation.
3. Replace isolated systems where Rust can provide measurable benefit.
4. Move the Java/Rust boundary inward deliberately.
5. Preserve mod compatibility for large 1.12.2 Forge packs.
6. Prevent regressions through differential tests and benchmarks.
7. Eventually make Rust own the simulation engine while Java becomes optional compatibility infrastructure.

## Core Design Principle

> **The Java/Rust boundary is temporary and mobile. It must never become a chatty permanent architecture.**

Never design around one JNI call per block, entity, item, or API method.

Prefer:
- coarse work units
- batched operations
- shared/native memory
- long-lived handles
- region/chunk level ownership
- measurable contracts

Avoid:
- fine-grained JNI
- unnecessary object conversion
- duplicate mutable state
- hidden copies
- cross-language ownership ambiguity

## Target

- Minecraft Java Edition: **1.12.2**
- Protocol: **340**
- Forge: **14.23.5.x**
- Initial compatibility target: large established 1.12.2 Forge modpacks
- Long-term runtime target: Rust-dominant or full Rust simulation/runtime

## Documentation Index

- `01_PROJECT_CHARTER.md`
- `02_ARCHITECTURE.md`
- `03_STAGE_ROADMAP.md`
- `04_P0_CURRICULUM.md`
- `06_BOUNDARY_AND_OWNERSHIP.md`
- `07_BENCHMARKING_AND_REGRESSION.md`
- `08_RUST_CRATES_AND_REFERENCES.md`
- `09_CODE_GRAPH_AND_REPO_NAVIGATION.md`
- `10_DOCUMENTATION_SYSTEM.md`
- `11_INITIAL_REPO_SKELETON.md`
- `machine/ownership_manifest.example.yaml`
- `machine/subsystem_status.example.yaml`

