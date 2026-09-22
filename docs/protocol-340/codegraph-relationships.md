# PROTOCOL CODEGRAPH RELATIONSHIPS (schema + how to query)

How the protocol knowledge layer maps onto the project's CodeGraph tooling.
Graph is a **helper, not the source of truth** (`09_CODE_GRAPH_AND_REPO_NAVIGATION.md`);
version-pinned yaml/md in `machine/` + `docs/protocol-340/` are the truth.

## Tooling

- Tool: `@colbymchenry/codegraph` v1.6.0 (global npm), DB `.codegraph/codegraph.db`.
- Index: `codegraph init` / `codegraph index` (rebuild) / `codegraph sync` (incremental).
- Query: `codegraph query <symbol>`; `codegraph callers/callees/impact <symbol>`; `codegraph explore <query...>`. Corpus refs: `--path third_party_reference/forge/src`.
- `codegraph serve --mcp` wires into agent config. Docs: `docs/tooling/codegraph.md`.

DB schema (SQLite): tables `nodes` (id `kind:hash`, kind, name, qualified_name,
file_path, language, start/end line/col, docstring, signature, return_type, …),
`edges` (source, target, kind ∈ contains/calls/references/imports/instantiates),
`files`, `nodes_fts` (FTS5 — must stay in sync; the indexer owns it),
`unresolved_refs`, `project_metadata`, `schema_versions`.

## Protocol node/edge vocabulary (conceptual)

Represent protocol concepts as **real symbols in workspace source** (searchable
via FTS) rather than hand-inserting DB rows, which risks FTS/WAL divergence.

Suggested node kinds (extend indexer `kind` set, or model as source symbols):

- ProtocolVersion, ProtocolState (Handshake/Status/Login/Play)
- Packet, WireField, DataType (VarInt, String, Position, UUID, NBT, Slot, …)
- JavaClass, JavaMethod, ForgePatch, ForgeHandler
- RustCrate, RustFunction, ParityTest, EvidenceArtifact

Suggested edge kinds (subset `edges.kind`, or Convention in docstrings):

- PACKET_IN_STATE, HAS_FIELD, SERIALIZES_AS, REGISTERED_AS
- IMPLEMENTED_BY, HANDLED_BY, TRANSFORMED_BY_FORGE, USES_DATATYPE
- RUST_IMPLEMENTATION, PARITY_TESTED_BY, DEPENDS_ON, COMPRESSED_BY, ENCRYPTED_BY
- VERSION_VALID_FOR

Do not over-generalize: keep to the concepts above.

## Practical embedding (recommended, low-friction)

1. Land protocol symbols in the workspace crates where they will live
   (`crates/transport`, `crates/contracts`, a future `crates/protocol`), with
   docstrings carrying `PROTO=340`, packet id, and Java class name. Then
   `codegraph sync` indexer picks them up and keeps FTS consistent.
2. Keep the authoritative registry in `machine/protocol-340-packets.yaml`
   (generated) and versioned docs under `docs/protocol-340/`; the graph indexes
   the Rust code + Java corpus (`--path third_party_reference`), not the yaml.
3. Cross-repo navigation: `codegraph query --path third_party_reference/minecraft/src SPacketChunkData`
   finds the Java class; `--path third_party_reference/forge/src` finds Forge
   patches. Pair node kinds with edges from the vocabulary in docstring
   "semantics" sections so future agents can link rust↔java↔forge via
   `IMPLEMENTED_BY`/`TRANSFORMED_BY_FORGE` conventions.

## Verification loop (per AGENTS.md)

Before/after any protocol migration: `codegraph sync`, then
`codegraph impact <symbol>` for the touched boundary; confirm no unexpected
dependency growth; re-check `machine/ownership.yaml` + subsystem status.

## Status today

Codegraph currently has **zero vanilla-protocol concepts** (no VarInt,
SPacketChunkData, ProtocolVersion nodes — only workspace `PacketBatch`/
`PacketFraming` shapes). The protocol layer is greenfield in the graph; this
schema + the docs are the seed.
