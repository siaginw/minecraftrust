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
- `05_AGENT_OPERATING_RULES.md`
- `06_BOUNDARY_AND_OWNERSHIP.md`
- `07_BENCHMARKING_AND_REGRESSION.md`
- `08_RUST_CRATES_AND_REFERENCES.md`
- `09_CODE_GRAPH_AND_REPO_NAVIGATION.md`
- `10_DOCUMENTATION_SYSTEM.md`
- `11_INITIAL_REPO_SKELETON.md`
- `machine/ownership_manifest.example.yaml`
- `machine/subsystem_status.example.yaml`

## v2 RustCraft Bootstrap Additions

This bundle adds the real RustCraft project brain (`.RustCraft.md`), a first-run `BOOTSTRAP_PROMPT.md`, project-local skills under `.RustCraft/skills/`, CodeGraph setup guidance, Graphy evaluation guidance, live ownership/status/gate manifests, `.gitignore`, and the intended project directory skeleton.

Recommended use:
1. Extract into the project root.
2. Open the project root in RustCraft Desktop.
3. Send RustCraft the contents of `BOOTSTRAP_PROMPT.md`.
4. Review the Bootstrap Report before authorizing P0 research or implementation.
