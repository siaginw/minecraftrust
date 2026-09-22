# Graphy Evaluation & Tooling Comparison

## 1. Overview
Graphy (`rosshhun/graphy` on GitHub / `graphy` on crates.io) is a Rust-based graph-powered code intelligence engine with built-in MCP server support, tree-sitter parsing, Tantivy search, and an optional embedded web dashboard.

This evaluation examines whether adding Graphy alongside CodeGraph provides sufficient incremental value to justify dual-tooling complexity during the bootstrap and early phases of the Minecraft 1.12.2 Rust runtime project.

## 2. Feature Comparison: CodeGraph vs. Graphy

| Capability | CodeGraph (Installed v1.6.0) | Graphy (Crate v1.0.0) | Incremental Value of Graphy |
|---|---|---|---|
| **Symbol Search** | Yes (`codegraph query`) | Yes (`graphy search`) | None (redundant) |
| **Call Graph / Traversal** | Yes (`callers`, `callees`, `node`) | Yes (`context`, `callers`) | None (redundant) |
| **Blast Radius / Impact** | Yes (`codegraph impact`) | Yes (`graphy impact`) | None (redundant) |
| **Test Impact** | Yes (`codegraph affected`) | Limited | CodeGraph is superior |
| **Language Support (Rust/Java)** | First-class out-of-the-box (verified) | Tree-sitter grammars (LSP optional) | Parity |
| **Hotspot Analysis** | No | Yes (`graphy hotspots`) | Useful in P3/P4 for audit |
| **Dead-Code Detection** | No | Yes (`graphy dead-code`) | Moderate value |
| **Taint / Data-Flow** | No | Yes (`graphy taint`) | High value for security/untrusted mods |
| **Visual Dashboard** | Minimal local viewer | Yes (embedded web UI / graph) | High visual clarity |
| **Graph Diff (CI)** | No | Yes (`graphy diff <base> <head>`) | Useful for CI regression tracking |

## 3. Detailed Dimension Analysis

### A. Java & Rust Support
- **Rust Support:** Graphy is written in Rust, leveraging tree-sitter-rust and Rayon for fast multi-threaded parsing.
- **Java Support:** Supports Java via tree-sitter-java grammar. Deep type hierarchy resolution can be augmented with `--lsp`, which introduces language-server daemon dependencies.

### B. Hermes / MCP Compatibility
- Compatible with stdio MCP (`graphy serve`).
- Can be registered in Hermes `mcp_servers` configuration.

### C. Maintenance Status & Ecosystem Maturity
- **Graphy:** Version 1.0.0 on crates.io, ~100 lifetime downloads, nascent project with single primary maintainer.
- **CodeGraph:** Actively maintained, version 1.6.0 on npm, officially integrated installer for Hermes Agent, mature test suite, stable SQLite WAL backend.

### D. Overhead and Resource Footprint
- **CodeGraph:** Extremely lightweight (indexed repository in 445ms, 0.34MB SQLite file, zero background daemons required for CLI operation).
- **Graphy:** Bundles Tantivy full-text indexer, Rayon thread pool, Tokio runtime, and optional web server. Running both in parallel would double indexing overhead and create redundant `.mcp.json` / SQLite / Tantivy storage.

## 4. Architectural Decision
**Decision: DO NOT install Graphy during P0.**

### Justification:
1. **Structural Navigation Already Satisfied:** CodeGraph 1.6.0 already satisfies the required structural-navigation needs for P0/P1 (symbols, callers/callees, impact analysis, fast SQLite index).
2. **Tooling & Cognitive Cost:** An additional MCP server and tool surface carries maintenance overhead and cognitive cost for agents without commensurate benefit at this stage.
3. **Maturity & Re-evaluation:** Graphy can be reconsidered later in P3/P4 if it adds measurable capabilities (such as taint tracking or complexity analysis).

### Future Re-evaluation Trigger:
Re-evaluate Graphy in **Phase P3 / P4** if:
- Taint analysis (`graphy taint`) is required to trace untrusted byte flow across the FFI boundary.
- Visual architecture diagrams are needed for complex Forge modpack decompile graphs.
- Complexity hotspot scanning (`graphy hotspots`) is needed to prioritize TileEntity decompilation targets.
