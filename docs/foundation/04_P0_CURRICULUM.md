# P0 Curriculum — Minecraft 1.12.2 / Forge 14.23.5.x

P0 exists so the agent learns before replacing.

## P0-0 — Repository Intake

Study and index:
- Minecraft 1.12.2 server sources / mappings
- Forge 1.12.2 source
- ForgeGradle 2.x era setup
- MCP mappings appropriate for 1.12.2
- selected large modpack server trees
- selected high-impact mods
- selected coremods / ASM transformers

Produce:
- repo inventory
- source roots
- build instructions
- mapping/version notes
- dependency graph

## P0-1 — Boot Process

Map:
- server main entry point
- Forge bootstrap
- mod discovery
- mod construction
- pre-init/init/post-init
- registry creation
- network setup
- world loading
- dedicated server startup

Output:
`docs/learned/boot_sequence.md`

## P0-2 — Tick Loop

Study:
- server tick
- world tick
- entity tick
- TileEntity tick
- scheduled block ticks
- player processing
- network processing
- save intervals
- Forge events

Output:
`docs/learned/tick_pipeline.md`

The document must include:
- call path
- thread
- ordering dependencies
- extension points
- mod hooks
- likely hot paths

## P0-3 — World and Chunk Lifecycle

Study:
- chunk load
- chunk generation
- chunk unload
- block state access
- TileEntity ownership
- chunk dirty state
- chunk serialization
- Anvil region storage
- dimension/world ownership

Output:
`docs/learned/chunk_lifecycle.md`

## P0-4 — NBT

Study:
- all NBT tag types
- compressed/uncompressed NBT
- chunk NBT
- entity NBT
- TileEntity NBT
- ItemStack NBT
- Forge capability persistence
- mod-specific unknown tag preservation

Output:
`docs/learned/nbt_contract.md`

Important invariant:
Unknown mod NBT must round-trip without accidental loss.

## P0-5 — Networking

Study:
- protocol 340
- login
- status
- encryption
- compression
- packet framing
- play packets
- Forge/FML handshake
- custom payload channels
- mod networking

Output:
`docs/learned/network_pipeline.md`

## P0-6 — Forge Runtime

Study:
- event bus
- registries
- capabilities
- OreDictionary
- recipes
- dimensions
- TileEntity registration
- mod network wrappers
- sided behavior
- lifecycle events

Output:
`docs/learned/forge_runtime.md`

## P0-7 — Coremods and ASM

This is mandatory.

Study:
- LaunchWrapper
- ASM transformers
- coremods
- access transformers
- reflection
- direct field access
- class replacement/modification
- mixin-like behavior used by mods

Produce a table:
- mod/coremod
- classes transformed
- methods/fields expected
- compatibility risk
- possible mitigation

Output:
`docs/learned/coremod_compatibility.md`

## P0-8 — Performance Baseline

Profile at least:
- vanilla 1.12.2
- Forge with no mods
- medium pack
- large pack
- deliberately abusive test world

Capture:
- MSPT
- TPS
- CPU per thread
- allocations
- GC
- chunk load latency
- chunk save latency
- packet throughput
- compression time
- NBT encode/decode
- TileEntity tick time
- entity tick time
- pathfinding time

Output:
`benchmarks/baseline/`

## P0-9 — Candidate Selection

For each subsystem score:
- CPU cost
- allocation cost
- mod interaction
- thread-safety difficulty
- behavioral sensitivity
- migration complexity
- expected performance gain

The agent must not choose a migration target solely because it is easy.

It should optimize for:
**benefit / risk / long-term architectural value**.

## P0-10 — Golden Test Worlds

Create deterministic test worlds for:
- redstone
- pistons
- fluids
- inventories
- TileEntities
- chunk boundaries
- Nether/End
- mob AI
- pathfinding
- machines from major mods
- AE2-style networks
- energy systems
- pipes/conduits

Each world becomes a permanent regression artifact.
