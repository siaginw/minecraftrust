# tools/symbols — bytecode + mapping symbol index

Minecraft 1.12.2 / Forge bytecode and mapping navigation for RustCraft.
Builds once into SQLite (`target/symbol-index/rustcraft-symbols.sqlite`),
queries instantly forever.

```bash
python tools/symbols/rustcraft_symbols.py build          # ~35 s, all layers
python tools/symbols/rustcraft_symbols.py method net.minecraft.world.World.checkLightFor
python tools/symbols/rustcraft_symbols.py map func_180500_c
python tools/symbols/rustcraft_symbols.py callers func_180500_c
python tools/symbols/rustcraft_symbols.py field-readers net.minecraft.world.EnumSkyBlock.BLOCK
```

Full command reference, layer model, naming model, and honesty notes:
`docs/engineering/BYTECODE_SYMBOL_INDEX.md`.

## Layout

| Path | Purpose |
| --- | --- |
| `rustcraft_symbols.py` | CLI entry (`--json` for machine output, `--db` to select an index) |
| `symbol_index/classfile.py` | pure-Python JVM class-file parser (no javap, no regex, no deps) |
| `symbol_index/opcodes.py` | Java 8 opcode tables |
| `symbol_index/mappings.py` | joined.srg + MCP snapshot CSV parsers, descriptor renaming |
| `symbol_index/builder.py` | artifact ingestion, name canonicalization, call/field graphs, hierarchy |
| `symbol_index/query.py` | query engine (identity resolution, graphs, diffs, search) |
| `symbol_index/schema.py` | SQLite DDL |
| `symbol_index/sources.py` | default source discovery (Gradle caches + target dirs) |
| `sources.json` | committed source configuration (candidate paths, provenance notes) |
| `mods_dir` spec | a sources entry `{"name": ..., "format": "mods_dir", "paths": [<dir>], "layer": "MOD_JAR"}` — the dir is walked for `*.jar`; the spec MUST also carry the `mappings` block from the built-in `sources.json` (a mods-only spec file refuses: "member canonicalization would be dishonest"); build with `--append` (now fixed: append mode no longer re-runs the DDL) or `--incremental` |
| `tests/test_symbols.py` | unit tests (synthetic class files; no Minecraft assets needed) |

## Principles

- Method identity = (class, name, **descriptor**, layer); never name alone.
- Layers are provenance: `VANILLA_NOTCH`, `VANILLA_CLIENT_NOTCH`,
  `FORGE_PATCHED`, `FORGE_PATCHED_MCP`, `FORGE_MOD`, `LIVE_TRANSFORMED`,
  `MOD_JAR` — never merged.
- Every build records source paths + SHA-256s into the DB; queries always
  display the descriptor.
- Read-only over the ecosystem: the builder never writes outside
  `target/symbol-index/` and never touches campaigns or the engine.
