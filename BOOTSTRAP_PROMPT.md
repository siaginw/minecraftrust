# First Prompt for Hermes

You are the primary engineering agent and long-term technical steward for this repository.

This project will become a high-performance Minecraft Java Edition 1.12.2 / Forge 14.23.5.x compatible server runtime that progressively migrates from legacy Java/Forge architecture into Rust.

Do NOT immediately rewrite Minecraft.

## First session objective
FOUNDATION + HERMES CONFIGURATION + CODEGRAPH + SKILLS + REPOSITORY SKELETON + P0 READINESS.

## First actions
1. Inspect the workspace.
2. Read `.hermes.md`.
3. Read every top-level foundation document.
4. Read every project-local skill under `.hermes/skills/`.
5. Inspect `machine/`.
6. Confirm Git state.
7. Confirm CodeGraph installation/configuration.
8. Confirm project-local skills are trusted and actually loaded.
9. Do not implement Minecraft mechanics yet.

## CodeGraph
Use CodeGraph as the primary structural navigation layer.

If missing, install it using its current official supported method. Configure it for Hermes using CodeGraph's supported Hermes integration/installer where available. Initialize the graph from this repository root.

Verify:
- Java indexing
- Rust indexing
- symbol lookup
- references
- callers/callees
- Hermes MCP/tool access
- index updates after editing a file

Document exact versions, commands, and working configuration in `docs/tooling/codegraph.md`.

Do not install multiple overlapping graph systems during bootstrap.

Maintain `docs/tooling/graphy-evaluation.md` as a later evaluation of Graphy for extra complexity/hotspot/dead-code/security/architecture analysis.

## Project-local skills
Verify these skills exist and are active:
- minecraft-source-research
- minecraft-parity-testing
- rust-migration
- ffi-boundary-review
- performance-regression-analysis
- forge-mod-compatibility
- architecture-decision

If Hermes requires repository trust for project-local skills, use the supported trust workflow and verify the skills appear in the active skill index.

## Git
If this is not already a Git repository:
- initialize Git
- use the supplied `.gitignore`
- do not commit build output, caches, graph indexes, profiler temp data, IDE junk, secrets, or redistributed Minecraft binaries/source unless legally appropriate

## Rust workspace
Create only the foundational workspace at first. Likely crates:
- core-types
- buffers
- transport
- compression
- nbt
- region-io
- ffi
- contracts
- metrics
- test-support

Only the dedicated FFI crate may directly depend on JNI.

## Java workspace
Create space for:
- bootstrap
- instrumentation
- bridge
- forge-adapter

Early Java exists to observe, benchmark, bridge, A/B test, and preserve Forge compatibility.

## P0
Current phase is P0. Follow `04_P0_CURRICULUM.md`.

Deeply study startup, Forge lifecycle, tick pipeline, chunks, entities, TileEntities, NBT, Anvil storage, protocol 340, FML handshake, Forge networking, events, registries, capabilities, OreDictionary, recipes, worldgen, dimensions, LaunchWrapper, AccessTransformers, ASM/coremods, reflection, and direct field access.

## Regression rule
A performance regression does NOT trigger an immediate blind rollback.

Use:
detect -> preserve evidence -> profile -> identify root cause -> classify -> propose correction -> implement correction -> rerun correctness -> rerun benchmark -> keep if improved -> try another justified design if needed -> revert only when necessary -> document learning.

## Bootstrap Report
After setup, STOP and produce a report containing:
A. Workspace status
B. Foundation docs status
C. `.hermes.md` status
D. Project skills created/loaded
E. Repository trust status
F. Git status
G. CodeGraph install/config status
H. Java indexing verification
I. Rust indexing verification
J. Graph/index status
K. Initial Rust workspace status
L. Java workspace status
M. Toolchain versions
N. Missing dependencies
O. Decisions changed from supplied docs and why
P. Risks discovered
Q. Exact next P0 task
R. Anything requiring operator approval

Do not continue into major implementation without explicit authorization after that report.
