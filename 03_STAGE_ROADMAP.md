# Stage Roadmap

## P0 — Learn, Measure, Map

No major rewrite begins until P0 is complete.

Deliverables:
- source map of Minecraft 1.12.2 server
- source map of Forge 14.23.5.x
- startup flow
- tick flow
- world/chunk lifecycle
- networking flow
- save/load flow
- Forge event flow
- TileEntity lifecycle
- entity lifecycle
- mod/coremod compatibility audit
- benchmark harness
- profiling harness
- documentation corpus
- subsystem ownership manifest

Exit criteria:
- agent can answer where a behavior lives and cite source locations
- baseline server is reproducibly benchmarked
- profiling identifies real bottlenecks

## P1 — Native Foundation

Rust:
- buffer abstraction
- native library boundary
- tracing
- metrics
- benchmark framework
- FFI safety layer
- shared handle registry

No gameplay migration yet.

## P2 — Low-Risk Native Acceleration

Candidates:
1. compression/decompression
2. packet buffer helpers
3. NBT encode/decode
4. region file I/O
5. file buffering / async persistence helpers

Goals:
- near-zero behavior risk
- demonstrate measurable improvements
- validate deployment and debugging workflow

## P3 — Isolated Compute Subsystems

Candidates:
- lighting
- pathfinding
- spatial queries
- chunk compression
- broad-phase collision helpers
- chunk generation helpers where safe

Every subsystem requires:
- parity corpus
- stress tests
- modpack regression test
- profile before/after

## P4 — Native Storage Ownership

Rust begins owning:
- chunk backing storage
- block-state storage
- compact palettes
- region persistence
- selected entity indexes

Java receives compatible views/proxies where necessary.

This is where architecture becomes more invasive.

## P5 — Regionized Scheduling

Introduce region ownership:
- region owns chunks
- region owns entities
- worker owns region while ticking
- cross-region work uses message/command queues

Do not parallelize blindly.

Preserve deterministic ordering where Minecraft behavior depends on it.

## P6 — Simulation Migration

Move selected gameplay systems into Rust:
- block updates
- scheduled ticks
- fluids
- redstone components
- entity simulation
- AI components

Migration order is determined by:
- measured cost
- compatibility risk
- test coverage
- mod dependency surface

## P7 — Forge Compatibility Runtime

Java increasingly becomes:
- Forge API surface
- mod execution host
- compatibility facade

Rust owns most server state and simulation.

## P8 — Rust-Dominant Runtime

Target:
- Java exists only for mods that require it
- native runtime can boot and operate core 1.12.2 behavior independently
- Forge compatibility becomes an optional hosted layer

## P9 — Full Rust Mode

Optional final destination:
- full native 1.12.2 server behavior
- compatibility runtime loads legacy mods as needed
- pure-Rust server possible without JVM when no Forge mods are used
