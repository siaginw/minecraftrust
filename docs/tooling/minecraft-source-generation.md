# Minecraft 1.12.2 Mapped Source Generation Pipeline

## 1. Overview & Specification
This pipeline extracts, deobfuscates, decompiles, and remaps the official Minecraft 1.12.2 dedicated server bytecode into a fully navigable, human-readable Java source tree for CodeGraph and architectural study.

## 2. Pipeline Parameters & Checksums
- **Input Jar:** `third_party_reference/minecraft/minecraft_server.1.12.2.jar`
- **Input Jar SHA1:** `886945bfb2b978778c3a0288fd7fab09d315b25f` (30,222,121 bytes)
- **Mapping Package:** `mcp_config:1.12.2-20200226.224830` (`joined.tsrg`)
- **Community MCP Mappings:** `stable_39-1.12` (`fields.csv`, `methods.csv`, `params.csv`)
- **Bytecode Remapper:** SpecialSource 1.8.5-shaded (`net.md-5:SpecialSource:1.8.5`)
- **Decompiler:** Vineflower 1.10.1 (`org.vineflower:vineflower:1.10.1`)
- **Generated Source Root:** `third_party_reference/minecraft/src/`
- **Output Statistics:** 1,413 Java classes (105,701 identifiers remapped from SRG to MCP)
- **CodeGraph Index:** 42,645 nodes, 135,329 edges

## 3. Four-Stage Pipeline Architecture

```text
[minecraft_server.1.12.2.jar] (Notch Obfuscated)
            │
            ▼ Stage 1: SpecialSource + joined.tsrg
[minecraft_server.1.12.2.srg.jar] (SRG bytecode names)
            │
            ▼ Stage 2: Filter net/minecraft/** classes
[minecraft_mc_srg.jar] (Excludes bundled 3rd-party libs)
            │
            ▼ Stage 3: Vineflower 1.10.1 (multi-threaded decompile)
[third_party_reference/minecraft/src/] (Raw Java with func_/field_ names)
            │
            ▼ Stage 4: Deterministic MCP Identifier Remap (methods.csv, fields.csv)
[Clean Mapped Minecraft 1.12.2 Source] (Navigable by CodeGraph)
```

## 4. Reproducibility Script
The pipeline is fully automated via `tools/generate-minecraft-source.py`:

```bash
# Execute end-to-end source regeneration:
python tools/generate-minecraft-source.py
```

## 5. Known Warnings & Decompiler Limitations
1. **Decompiler Heuristics:** Decompiled source reflects Vineflower AST reconstruction of control flow; bytecode remains the ultimate authoritative reference for JVM semantics.
2. **Inner Class Constructors:** Synthetic outer-this pointers (`this$0`) may appear in inner class constructors.
3. **Unicode Escapes:** Mojang code contained occasional `☃` placeholders for unmapped parameter names; stage 4 replaces these with `p_` standard prefixes.
4. **Legal / Redistribution:** Generated source is derived from Mojang proprietary bytecode. It is excluded from version control via `.gitignore` and must not be distributed.

## 6. Pipeline Audit & Regex Hardening (P0-2)
During the P0-2 audit, the textual remapping regex was audited against all 18,485 MCP methods and 21,572 fields:
- **Defect Identified:** The initial regex `\b(func_\d+_[a-z0-9]+)\b` only matched lowercase suffixes, missing uppercase SRG suffixes (e.g. `func_175694_M` -> `getSpawnPoint`, `func_72863_F` -> `getChunkProvider`) and trailing underscores (e.g. `func_71216_a_` -> `outputPercentRemaining`).
- **Hardened Regex:** `\b(func_\d+_[a-zA-Z0-9_]+|field_\d+_[a-zA-Z0-9_]+|p_\d+_\d+_[a-zA-Z0-9_]*)\b`.
- **Audit Results:**
  - 13,791 additional symbols remapped across 765 files.
  - 0 collisions with Java reserved keywords.
  - 0 parameter-name collisions.
  - All 1,413 source files updated and re-synced in CodeGraph (31,189 nodes updated).
