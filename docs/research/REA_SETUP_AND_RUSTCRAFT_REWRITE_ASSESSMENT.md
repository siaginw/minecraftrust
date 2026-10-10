# REA setup receipt + RustCraft-rewrite architecture assessment (2026-10-10)

## Part 1 — REA ("Reverse Engineer Anything") is set up and verified

**What it is:** an MCP server + CLI (TypeScript/Node, MIT) that lets coding
agents reverse-engineer software with evidence-cited results — native
binaries (via Hopper/Ghidra/IDA providers), process behavior, packages,
JS/Electron, browser captures, .NET assemblies, Android APKs, firmware, EVM.

### Installed identities (pinned)

| artifact | identity |
|---|---|
| npm package | `rea-agents` **5.0.0** (global; released 2026-10-07 from checkpoint `b33236ec`) |
| global install path | `C:/Users/Siagi/AppData/Roaming/npm/node_modules/rea-agents/` (bin: `scripts/rea.mjs`) |
| source clone (deep-dive copy) | `D:/minecraftrust/third_party_reference/rea` @ main `4d95f2b` (shallow) |
| runtime | Node v24.17.0 (supported line: 22.x ≥22.19, 24.x ≥24.11, 26+) |
| project registration | `C:/rustcraft/.mcp.json` → `node …/rea-agents/scripts/rea.mjs mcp` (stdio) |

### Verification receipts (all through the EXACT .mcp.json command shape)

- MCP `initialize` → serverInfo `{rea, 5.0.0}`; `tools/list` → **133 tools**.
- `tools/call binary_session` → composite provider reports; no target open
  (expected). Deep engines (Ghidra/Hopper/IDA) NOT installed — optional;
  `rea doctor --json`: node/host healthy, `missing_analysis_engine` for the
  three. NOTE: Ghidra 12.1.x requires JDK 21 — deferred until a native-DLL
  analysis task actually needs it (our `rustcraft_ffi.dll` work has been
  served by export gates + Rust source so far).
- `rea --version` → 5.0.0; `npx` fallback documented (`npx -y rea-agents@5.0.0 mcp`).

### Capability map vs THIS project (deep-dive result)

- **JVM/Java-jar analysis: NOT present** (only Android/DEX via JADX). The
  JVM side of this repo stays with the purpose-built, SRG-ground-truthed
  `tools/symbols` index + runscope — they are strictly better for
  1.12.2/Forge/mod bytecode.
- **What REA adds here:** (a) native analysis of `rustcraft_ffi.dll` if an
  engine is later installed; (b) process-behavior capture of the live
  JVM+DLL hybrid; (c) package/bundle inspection; (d) its investigation
  workflow discipline (sessions, evidence, stated limitations — the same
  culture as our receipts).
- Context cost note: 133 tools is a large always-loaded surface; if it
  becomes noise, remove the `rea` block from `.mcp.json` (one edit, no
  other coupling).

## Part 2 — "Rewrite MC 1.12.2 in Rust with mods still working": honest assessment

### The constraint that decides the architecture

Mods are JVM classfiles loaded by Forge's LaunchClassLoader. "Mods find the
same classes in Rust" is not literally possible: they need real
`java.lang.Class` objects, FML lifecycle events, registries, and — hardest —
**coremods/mixins that patch VANILLA class bytecode at load time** (Phosphor
rewrites Chunk/WorldServer lighting internals; RustCraft's own hooks do the
same). If a vanilla class's body moves wholly into Rust, there is no Java
method left for a transformer to patch, and every such coremod breaks.

Therefore the only architecture that keeps the Revelation pack running is:
**Rust engine core + JVM mod island, where Java classes that transformation
contracts touch RETAIN patchable Java bodies with state/computation behind
them.** That is precisely RustCraft's existing seam architecture — the
rewrite is a continuation (flip ownership subsystem-by-subsystem), not a
replacement leap.

### What we already hold (this is the strongest position)

- Rust-owned today: chunk/section storage, full-width state IDs, block-light
  kernel (proven equal + −18% live), region read/write, packet
  encode/compression, mirrors + C2 sync (allocation-clean).
- THE DATA: symbol index across vanilla/Forge/live-transformed/73 mod jars;
  the audit's touch-point census; corpora + 100+ receipted campaigns; the
  benchmark + attribution machinery (MSPT, allocation, ablation flags).
- Pumpkin-MC (release 0.2.0+26.3-26.51): subsystem DESIGN reference (chunk
  storage, lighting, packet encode — the Starlight pattern), NOT a base —
  zero mod compat, modern protocol.
- REA: process/native-side investigations + evidence discipline (not the
  compat layer; see Part 1).

### Milestone ladder (each = the existing authority-milestone machinery)

- **M0 (next, one boot):** OPT-FS-004 — with capture off, light-SHADOW: is
  the residual MSPT gap the light-authority path? This tells us the first
  subsystem where Rust ownership must WIN the clock, not just be correct.
- **M1:** network/packet emission path (proven components exist; True
  Direct work) — the seam with no coremod exposure (Netty is excluded from
  transformation already).
- **M2:** region I/O end-to-end (read+write already authority-proven) —
  retire the remaining Java copy paths.
- **M3:** world tick/game-loop ownership behind patchable Java shells —
  the hard one; design doc first (which classes do the 73 jars' coremods
  touch — the symbol index can answer this exhaustively BEFORE writing
  code).
- Standing gates unchanged: digest/parity evidence, bounded authority,
  PRODUCTION_AUTHORITY=false until each milestone closes it.

### Honest bottom line

The vision is viable ONLY as the incremental flip-ownership program above;
a from-scratch Rust server with a "compat layer" that runs 73 mod jars is
not — the mod contract IS the JVM + patchable vanilla bytecode. The
measured reality check stands: the composed stack is currently SLOWER than
clean Java (FS-001/002/003 receipts), so each ownership flip must prove a
measured win at its milestone or the program should stop. M0 is cheap and
decides the first real target.
