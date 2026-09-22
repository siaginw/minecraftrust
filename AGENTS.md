# AGENTS.md — Agent Operating Guide for minecraftrust

Migrated from the Hermes agent setup (2026-09-19). This file orients any agent
(ZCode, Hermes, or other) taking over this project.

## What this project is

Rust/native performance work on a Minecraft 1.12.2 Forge server: JNI bridges,
chunk/packet pipeline rewrites, differential parity testing against the vanilla
server behavior. Repo root: `D:\minecraftrust` (git branch `master`).

## Read-first map (numbered docs in repo root)

1. `01_PROJECT_CHARTER.md` — goals and non-goals
2. `02_ARCHITECTURE.md` — system design
3. `03_STAGE_ROADMAP.md` — milestone plan (M1.x series; M1.2 was last completed)
4. `05_AGENT_OPERATING_RULES.md` — agent workflow rules
5. `07_BENCHMARKING_AND_REGRESSION.md` — how to measure (mandatory before perf claims)
6. `09_CODE_GRAPH_AND_REPO_NAVIGATION.md` — repo navigation
7. `12_BOOTSTRAP_PROMPT_FOR_HERMES.md` — the original agent bootstrap (historical reference)

## Engineering charter (carried over from Hermes SOUL.md)

- Truth > confidence. Evidence > intuition. Measurements > assumptions.
- Never claim something is faster without a benchmark: baseline → change one
  thing → same workload → measure → explain why.
- Compiling and starting do not prove correctness. Use parity/differential
  tests; every fixed bug becomes a test when practical.
- A regression is an investigation trigger, not an automatic revert.
- Profile first; do not optimize insignificant work because it is easy.
- Do not let generated summaries replace reading important source directly.
- Persist discoveries, failed approaches, and benchmark results in the docs —
  never leave essential knowledge only in chat history.

## Project-specific hard-won knowledge

- **Thread isolation when profiling**: periodic autosave builds NBT in memory on
  `ServerThread` and queues IO; binary encoding, compression, and disk IO run
  asynchronously on `File IO Thread`. Never count worker-thread CPU as
  ServerThread compute MSPT unless an explicit queue-draining flush is invoked.
- Java toolchain: `C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/javac.exe`,
  compile classpath against `third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar`.

## Migrated skills

The Hermes agent's self-built skills now live in
`C:\Users\Siagi\.zcode\skills\minecraft-rust\` (also still in the Hermes data
root under `AppData\Local\hermes\profiles\rustdev\skills\`). Sub-skills:

- `jni-direct-buffer-addressing`
- `benchmark-evidence-integrity`
- `ffi-boundary-review`
- `forge-mod-compatibility`
- `minecraft-parity-testing`
- `architecture-decision`

Related skill folders: `nw-server`, `openclaw-imports`, `yuanbao` (New World RE
project tooling — see memories below).

## Sibling project boundaries (from Hermes memories)

- `D:/nw-server` is the **protected original/reference repo** — never edit it.
  Use `D:/nwserverhermes` as the working copy for edits, notes, and proposed
  patches unless the user explicitly authorizes editing the original.
- New World Obsidian vault: `C:/Users/Siagi/Documents/wiki` (nw-server pages
  under `pages/`); Harvest data root: `D:/SteamLibrary/steamapps/common/New World/Bin64/Harvest_data`.

## User preferences

- Tight, direct summaries; no wall-of-text evidence dumps unless requested.
- Deep, evidence-grounded autonomous work; durable reference/index docs over
  context-window summaries; verifiable artifacts (manifests, checksum
  inventories, guard scripts, explicit verification counts).

## Permanent candidate-evaluation rubric (operator-mandated 2026-09-20, M3W4)

Every Rust-migration candidate is evaluated in tiers; a RUST_PARITY loss
alone NEVER justifies NOT_WORTH_IT (the M3W worldgen case proved this:
the parity port lost 1.31× to HotSpot, the researched/optimized redesign
now beats Java 5.6% at the production shape, bit-exact).

1. REFERENCE_JAVA — understand the ACTUAL installed/transformed runtime
   behavior (disassembly/decompile of the live jar, not assumptions).
2. RUST_PARITY — straightforward exact port proving correctness.
3. EXTERNAL_OPTIMIZATION_RESEARCH — before judging performance, search
   best-of-breed techniques (GitHub, crates, C/C++, SIMD/compiler docs,
   engineering writeups; community sources when technically concrete).
4. RUST_OPTIMIZED — best reasonable Rust-native design: better
   algorithm/layout, SIMD, production-realistic batching, persistent
   native state, zero-copy, cache optimization, fusion, specialization,
   concurrency where semantics permit.
5. COMPLETE BENCHMARK — JAVA_REFERENCE vs RUST_PARITY vs RUST_OPTIMIZED
   at the REAL production invocation shape (e.g. Forge generates one
   chunk per call — n=1 is the criterion, not n≥4 batching).
6. DUAL-AXIS DECISION — score PERFORMANCE VALUE and RUST MIGRATION
   VALUE separately; never merge into one vague score.
7. COMPATIBILITY LADDER — offline differential → SHADOW → clean-Forge
   ON_EXPERIMENTAL → modpack SHADOW/ON → combined subsystem integration.
8. PARKING RULE — before parking document: external projects searched,
   techniques considered/implemented/rejected (with reasons), remaining
   headroom, architectural value, future-native-state dependency.

## Subsystem status (operator-mandated 2026-09-20; details in
docs/research/subsystem-status.md)

- M1 SPacketChunkData: REOPEN_AFTER_NATIVE_STATE (retain impl/tests;
  revisit when Rust owns chunk/section data)
- M2-C Compression: READY_OPTIONAL (default OFF; potential v2 separately)
- M2-PERSIST: ARCHITECTURAL_REOPEN_CANDIDATE (reassess later as native
  storage infrastructure, not solely MSPT optimization)
- M3 Spawn Index: REOPEN_AFTER_NATIVE_STATE (algorithm-only standalone)
- M3 Collision: REOPEN_AFTER_NATIVE_STATE (mirror not worthwhile alone)
- M3 Worldgen Density: SHADOW_ACTIVE (Target A validated; active frontier)
