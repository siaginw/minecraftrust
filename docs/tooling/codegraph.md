# CodeGraph Setup and Verification

CodeGraph is the primary structural code-intelligence and navigation layer for the Minecraft 1.12.2 Rust runtime repository. It indexes both Java and Rust codebases, supporting symbol search, reference navigation, caller/callee traversal, and blast-radius impact analysis.

## 1. Tool Metadata
- **Installed Version:** `1.6.0` (`@colbymchenry/codegraph`)
- **Runtime Requirement:** Node.js (tested on Node v22.23.2, npm 10.9.8)
- **Database Engine:** SQLite embedded (`node:sqlite` with WAL mode)
- **Database Location:** `.codegraph/` (auto-ignored via `.gitignore`)

## 2. Installation
CodeGraph was installed globally via npm:
```bash
npm install -g @colbymchenry/codegraph
```

Verification of CLI installation:
```bash
codegraph version
# Output: 1.6.0
```

## 3. RustCraft Integration
development tooling was configured using the official auto-wiring command:
```bash
codegraph install --target RustCraft --yes
```

This updated the active profile configuration at `C:\Users\Siagi\AppData\Local\RustCraft\profiles\rustdev\config.yaml` with the following MCP server entry:
```yaml
mcp_servers:
  codegraph:
    command: codegraph
    args:
      - serve
      - --mcp
    timeout: 120
    connect_timeout: 60
    enabled: true

platform_toolsets:
  cli:
    - RustCraft-cli
    - mcp-codegraph
```

In the current session, direct CLI integration is fully functional via `codegraph` commands; full MCP tool injection takes effect upon restarting the development tooling session.

## 4. Repository Initialization
Initialized the repository graph from the project root (`D:\minecraftrust`):
```bash
codegraph init
```
Initial indexing result:
- **Indexed Files:** 18 files (10 Rust, 5 Java, 3 YAML)
- **Graph Metrics:** 150 nodes, 180 edges
- **Time:** 445ms

## 5. Verification Steps & Results
All required capabilities were tested and verified against the live repository:

| Capability | Verification Command | Verified Result |
|---|---|---|
| **Status Check** | `codegraph status` | 18 files, 150 nodes, 180 edges, SQLite WAL |
| **Java Indexing** | `codegraph query ServerBootstrap` | Discovered class `ServerBootstrap` and method `main` |
| **Rust Indexing** | `codegraph query BlockPos` | Discovered struct `BlockPos` and method `new` |
| **Callee Traversal** | `codegraph callees main` | Traversed 3 callees: `isAvailable`, `startServerTick`, `endServerTick` |
| **Caller Traversal** | `codegraph callers record_call` | Traversed caller: `rust_runtime_ping` in `crates/ffi/src/lib.rs` |
| **Impact Analysis** | `codegraph impact BlockPos` | Identified affected symbols in `crates/core-types/src/lib.rs` |
| **Incremental Sync** | Added `to_chunk_pos` to `BlockPos`, ran `codegraph sync` | Synced in 179ms; `to_chunk_pos` immediately discoverable |

## 6. Daily Operating Workflow
1. **Before multi-file migration / refactoring:**
   - Run `codegraph impact <symbol>` or `codegraph callers <symbol>` to evaluate blast radius.
   - Cross-check `machine/ownership.yaml` to confirm subsystem authority.
2. **After making code edits:**
   - Run `codegraph sync` to update the graph incrementally.
   - Run `cargo check --workspace` and `javac -d bin $(find java -name "*.java")`.

## 8. Multi-Index Strategy for Reference Sources
To keep active development indexing lightning fast while allowing deep structural queries across 20,000+ upstream Forge classes, CodeGraph is deployed with a two-tier indexing strategy:

### Tier 1: Authoritative Workspace Graph (Root)
- **Location:** `D:\minecraftrust` (`.codegraph/`)
- **Scope:** Active Rust crates (`crates/`), Java bridge (`java/`), contracts, metrics, machine manifests.
- **Size:** ~18 files, 152 nodes, 0.39 MB database.
- **Sync Time:** <200ms.
- **Usage:** Daily development, refactoring blast-radius analysis, and cross-crate impact checks.

### Tier 2: Reference Corpus Graphs

#### Tier 2A: Forge Reference Graph
- **Location:** `third_party_reference/forge/src/` (`.codegraph/`)
- **Scope:** 880 Forge and FML source files.
- **Size:** 24,082 nodes, 48,501 edges, 15 MB database.
- **Query Method:**
  ```bash
  codegraph query --path third_party_reference/forge/src EventBus
  codegraph callers --path third_party_reference/forge/src register
  ```

#### Tier 2B: Mapped Minecraft 1.12.2 Server Graph
- **Location:** `third_party_reference/minecraft/src/` (`.codegraph/`)
- **Scope:** 1,413 decompiled, deobfuscated, and MCP-mapped Minecraft server classes.
- **Size:** 42,645 nodes, 135,329 edges, ~28 MB database.
- **Query Method:**
  ```bash
  codegraph query --path third_party_reference/minecraft/src MinecraftServer
  codegraph callers --path third_party_reference/minecraft/src DedicatedServer
  codegraph callees --path third_party_reference/minecraft/src init
  ```

This strategy completely isolates large upstream third-party sources from daily compilation targets while providing instant structural search across all three environments (our runtime, Forge, and Minecraft).
