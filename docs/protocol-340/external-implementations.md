# EXTERNAL PROTOCOL IMPLEMENTATION RESEARCH (protocol 340 / 1.12.2 relevance)

Research for algorithm/design reference only. Version claims verified from
project sources where possible. **No code copied into repo.** License flags
note what is borrowable (MIT/Apache/BSD) vs ideas-only (GPL).

| Project | MC version support | Lang | License | Directly applicable to 340? | Maturity | Useful concepts |
|---|---|---|---|---|---|---|
| **Cuberite** | 1.8 – 1.12.2 (`Protocol.h`: `v1_12_2 = 340`) | C++ | Apache-2.0 | **YES** | complete, maintained | per-version `Protocol` class hierarchy; reversible serializer per version; `cChunkDataSerializer` (chunk palette + send format). Best full-server 340 architecture reference. |
| **minecraft-data** (PrismarineJS) | full range; **`data/pc/1.12.2/protocol.json` exists**, `version.json` → 340 | JSON data | MIT | **YES** (schema) | complete | THE declarative schema: `types`, handshaking/status/login/play packet maps; 32 types (varint, varlong, pstring, u16, i64, buffer, bool, option, entityMetadataLoop, bitfield, container, switch, void, array, restBuffer, nbt, slot, position, entityMetadata…). Exact byte-level schema source. |
| **node-minecraft-protocol** (PrismarineJS) | multiplexed, **1.12.2/340 in range** | JS | BSD-3-Clause | **YES** (design) | complete | schema-driven parser/serializer; state machines; VarInt framing; compression toggle; (RSA + AES-CFB8). Codec-generator + state-transition design. |
| **mineflayer** | 1.12.2 supported | JS | MIT | partial | complete | bot layer (behavior; not wire). |
| **Velocity** | 1.8 → latest, **`ProtocolVersion.MINECRAFT_1_12_2(340)`** | Java | **GPL-3.0** (ideas-only) | **YES** (design) | complete | per-version packet state tables; compression framing + threshold toggle; AES-CFB8 + RSA (`EncryptionUtils`); one core spanning 1.8→latest. Multi-version framing + 340 codec path. |
| **azalea-rs** | latest only (viaversion for old) | Rust | MIT | NO (340-native) | active | derive-based packet registry (`azalea-protocol`), VarInt, chunk — borrow plumbing, supply your own 340 schemas. |
| **valence-rs** | latest only | Rust | MIT | NO | active | `packet` macro derive, "saved protocol"; one-version design explicitly. |
| **feather-rs** | 1.16.5 only | Rust | Apache-2.0 | NO | abandoned (2024) | legacy Rust server; not 340. |
| `minecraft-protocol` / `-derive` (crates.io) | latest | Rust | MIT | NO | experimental | packet derive macro — architecture for derive-based codegen. |
| **Oxide** | UNVERIFIED (repo removed from GitHub, no trace) | Rust | unknown | UNVERIFIED | incomplete/unfinished | the "readable_minecraft / protodef" idea — declarative human-readable packet-schema DSL → generated codec. **Architecture inspiration only, NOT an oracle.** |
| **bedrock-protocol** (PrismarineJS/pmmp) | Bedrock/RakNet | JS/PHP | MIT/LGPL | **NO** (different wire protocol) | — | out of scope — Java 340 ≠ Bedrock. |

## Synthesis

Best exact-340 design references:
1. **minecraft-data** `data/pc/1.12.2/protocol.json` — authoritative declarative schema; direct seed for a Rust codec table.
2. **Cuberite** — proven full-server 340 implementation; chunk serializer + per-version protocol-object pattern.
3. **Velocity** — multi-version framing/encryption/compression design with a 1.12.2 codec path.

Rust crates (azalea, valence, feather, derive-proto crates) are **architecture-only**:
all target latest versions; borrow the codegen/derive/framing ideas, supply 340
schemas yourself. Their packet data is non-transferable.

Oxide: treat as an idea (declarative protocol DSL) only; cannot be verified as a
340 reference; do not cite concrete claims from it.

## License notes for reuse

- MIT / BSD-3-Clause (minecraft-data, node-minecraft-protocol, azalea, valence, derive crates): copy permitted with attribution.
- Apache-2.0 (Cuberite, feather): copy permitted with attribution/notice.
- GPL-3.0 (Velocity): **ideas-only** — do not vendor code into a closed engine.
- Reimplementing from protocol.json is the recommended route (no copying of
  declaration payloads beyond license terms).
