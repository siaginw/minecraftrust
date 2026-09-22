# PROTOCOL-340 KNOWLEDGE REPORT

Minecraft Java Edition 1.12.2 · Protocol 340 · Forge 14.23.5.2860.
Built 2026-09-21. Research + documentation + CodeGraph + skill. **No production
protocol behavior was altered.** Deliverables live under `docs/protocol-340/`
plus skill `protocol-340-forge-network`. Registry of record gen'd from
`EnumConnectionState`.

## A. Source manifest
`docs/protocol-340/manifest-sources.md` — evidence hierarchy, local authoritative
artifacts (paths + hashes), main/version-history documentation URLs with target
version, explicit-340 flag, trust, concepts. No copyrighted pages copied.

## B. Packet registry statistics
`docs/protocol-340/packet-registry-wire.md` (generated ID tables) +
`machine/protocol-340-packets.yaml` (124-packet registry).
- 124 registered packets: 1 HANDSHAKING SB, 6 LOGIN (4 CB + 2 SB), 4 STATUS,
  113 PLAY.
- PLAY exact (generated): **79 CLIENTBOUND** 0x00–0x4E, **33 SERVERBOUND** 0x00–0x20.
- Every PLAY id generated programmatically from `EnumConnectionState.java`
  registration order — no hand-typed IDs, no invention.

## C. Java implementation map
`docs/protocol-340/java-implementation-map.md` — PacketBuffer, NetworkManager,
EnumConnectionState, NettyPacketEncoder/Decoder, NettyCompression*,
NettyEncrypting*, NettyVarint21*, PacketThreadUtil, datasync; outbound/inbound
encode paths; SRG/obf notes; existing Rust JNI boundaries.

## D. Forge/FML networking map
`docs/protocol-340/forge-fml-networking.md` + `machine/fml-handshake.yaml`.
Channel model (FMLEmbeddedChannel, FMLOutboundHandler), FML|HS handshake phases
+ discriminators, mod-list negotiation, registry sync, SimpleNetworkWrapper
discriminator/networking, rejection behavior, vanilla-client fallback. Forge
extensions labeled separately from vanilla.

## E. Data-type map
`docs/protocol-340/datatypes-oracle.md` — VarInt, VarLong, Boolean, BE
byte/short/int/long/float/double, String (VarInt prefix, 32767 cap),
Position (one BE long), UUID (2×long), NBT (0x00 sentinel, 2 MiB read cap),
ItemStack (short id), ByteArray, VarIntArray, Enum; plus Rust coverage status
per type and missing tests.

## F. Chunk-format deep dive
`docs/protocol-340/chunk-format-deep-dive.md` — SPacketChunkData header (x/z
are **Int**, not VarInt), section selection, palette rules (≤4 Linear, 5..8
HashMap, >8 Registry/global with length 0), BitArray words, block/sky light
(2048 bytes, sky iff `hasSkyLight()`), biomes 256 bytes on full chunks, TE NBT,
packet sizing, no heightmaps in 340, Forge/NEID note, cross-links to M1/M2-C.

## G. Current Rustcraft coverage
`docs/protocol-340/coverage-matrix.md` — IMPLEMENTED: NBT codec, SPacketChunkData
section payload encode, zlib compression, core coord types, JNI glue.
PARTIAL: VarInt writer (private, write-only), BlockPos math (no wire codec),
NBT packet wrapper. MISSING: VarInt read, VarLong, String/UUID/ItemStack/array/
enum wire codecs, framing impl, all packet decode, encryption, FML handshake
(Rust), buffer primitive codecs. Reusable: `write_varint`, `NbtCursor`,
`mutf8.rs`, `ZlibPacketCompressor`, Python wire bots.

## H. CodeGraph additions
`docs/protocol-340/codegraph-relationships.md` — schema (nodes: Packet,
DataType, ProtocolState, JavaClass, RustCrate, ParityTest, …; edges:
PACKET_IN_STATE, HAS_FIELD, SERIALIZES_AS, IMPLEMENTED_BY, TRANSFORMED_BY_FORGE,
USES_DATATYPE, …), tooling commands, embedding guidance (prefer source symbols
+ `codegraph sync` over hand-inserting DB rows to avoid FTS divergence). DB
currently has zero vanilla-protocol concepts — greenfield seed.

## I. Protocol skill
`local research notes (protocol-340-forge-network)`
— target version, evidence hierarchy, doc map, CodeGraph usage, Forge diffs,
parity methodology, never-rules.

## J. Parity/test coverage matrix
`docs/protocol-340/coverage-matrix.md` — Java oracle / Rust impl / offline
fixture / fuzz / live / modpack per component (VarInt, String, NBT, chunk
payload, compression, framing, FML|HS, SimpleNetworkWrapper, encryption).
Reflects actual repo state; no invented coverage.

## K. External implementation references
`docs/protocol-340/external-implementations.md` — Cuberite (1.12.2, best
full-server 340 ref), minecraft-data protocol.json for 1.12.2 (declarative
schema), node-minecraft-protocol (design), Velocity (340 codepath, ideas-only
GPL), Rust crates (azalea/valence/feather — latest-only, architecture only).
Oxide = unverified/ideas-only (repo removed). bedrock-protocol flagged out of
scope. License notes included.

## L. Important contradictions / discrepancies found

1. **SPacketChunkData chunkX/chunkZ are BE Int (4 bytes), NOT VarInt** — verified
   in the unpacked 1.12.2 source (`writeInt`/`readInt`). Modern wiki shows
   VarInt chunk coords (that's post-1.14 style). The in-repo registry yaml and
   any Rust header reader must use Int for 340.
2. **Entity metadata terminator is `0xFF` (one unsigned byte), NOT `0x7F`/VarInt**
   (later versions use 0xFF VarInt sentinel; 1.8-era used byte id list). Verified
   in `EntityDataManager`.
3. **UUID is two BE longs (MSB, LSB), NOT 16 little-endian bytes** — 1.12.2
   method names `writeUniqueId`/`readUniqueId`; 16-LE begins in 1.16.
4. **ItemStack id is `short` BE, NOT VarInt** — VarInt item ids arrive in 1.13.2.
5. **String length prefix is VarInt in 1.12.2** (not the short used pre-1.7 and
   still inside NBT). Write cap literal 32767 bytes; read cap `max*4` then
   `str.length()>max`.
6. **No heightmaps in 1.12.2 SPacketChunkData** (added 1.15).
7. **Entity rel-move/re-look are separate packet IDs** (0x26/0x27/0x28) — no
   1.8-style bit-flag byte on 0x25.
8. **Serverbound order differs from common lore**: CPacketCloseWindow=0x08
   (before CustomPayload 0x09), PositionRotation=0x0E/Rotation=0x0F,
   CPacketAnimation=0x1D, HeldItemChange=0x1A. Confirmed from registration order.

## M. Missing knowledge (open items)

- Exact `SPacketMapChunkBulk` semantics for 340 (play bulk path; low relevance —
  vanilla uses per-chunk `SPacketChunkData`).
- Full `SPacketAdvancementInfo`/`SPacketTeams`/`SPacketWorldBorder`/`SPacketTitle`
  per-action payload detail (complex switches; not yet field-audited).
- Complete entity `DataWatcher` serializer value encodings for every type
  (ids 0..13 known; per-serializer byte layout for OPTIONAL_*/COMPOUND needs
  source confirmation at implementation time).
- Oxide project cannot be verified (repo removed) — treat as idea only.
- RegistryData streaming exact byte layout per registry (hasMore/idMap framing)
  — captured live but not yet normalized into a spec doc.
- Encryption (RSA key exchange + AES-CFB8) Rust parity — zero coverage.

## N. Future Rust networking architecture recommendation

Requested: path from M1 packet encoder + M2-C compression toward a larger
Rust-owned networking subsystem. **Recommended, not implemented.**

Recommended layering (bottom-up), each layer gated before the next:

1. **Protocol primitives crate (`crates/protocol`)**: lift `write_varint` out
   of `chunk-packet`; add VarInt read, VarLong, String(VarInt prefix, cap),
   Position wire, UUID, ItemStack, ByteArray/VarIntArray, Enum — endian-aware
   cursor over a `PacketBuffer`-equivalent. This is the load-bearing missing
   layer and the highest value-per-effort.
2. **Packet registry (data-driven)**: a static registry derived from the
   generated ID tables (79 CB / 33 SB) — `(state, dir) -> id -> decoder/encoder`.
   Seed layouts from `packet-registry-wire.md`; keep `machine/protocol-340-packets.yaml`
   as the of-record source.
3. **Framing + compression + encryption**: implement `crates/transport`
   `PacketFraming` (VarInt21), wire `crates/compression` decompressor +
   threshold framing (currently encoder-only/Java-side), AES-CFB8 + RSA.
4. **Outbound encoding engine**: reuse M1 chunk payload encode +
   `ZlibPacketCompressor`; add per-packet encoders driven by the registry.
   **Key opportunity:** encode directly from native chunk state
   (`crates/chunk-packet` already stages section data) → true zero-copy chunk
   packet without Java round-trip — the natural M1↔native-state merge.
5. **Netty boundary**: keep a narrow JNI interface (like `crates/ffi` today);
   the server stays Forge/Netty-hosted (per existing compatibility charter).
   Rust owns encode/decode + state, Java owns pipeline/channel assembly.
6. **Forge compatibility**: implement FML|HS + REGISTER/UNREGISTER +
   SimpleNetworkWrapper discriminators as a layer ABOVE the vanilla registry
   (`forge-fml-networking.md`); Python bots (`tools/targeta_client.py`,
   `protocol-live-probe.py`) stay as the live wire oracle.

Ordering: (1) primitives → (3) framing/compression → (4) encode → (2)+registry
→ (5) boundary → (6) Forge. Decode (read path) and encryption are later.

**Gate**: this is a full networking subsystem = separate operator approval.
This task built only the knowledge layer. Do not begin the rewrite without that gate.

## Files created
- `docs/protocol-340/manifest-sources.md`
- `docs/protocol-340/packet-registry-wire.md`
- `docs/protocol-340/packet-wire-layouts.md`
- `docs/protocol-340/datatypes-oracle.md`
- `docs/protocol-340/chunk-format-deep-dive.md`
- `docs/protocol-340/forge-fml-networking.md`
- `docs/protocol-340/java-implementation-map.md`
- `docs/protocol-340/coverage-matrix.md`
- `docs/protocol-340/external-implementations.md`
- `docs/protocol-340/codegraph-relationships.md`
- `docs/protocol-340/README.md` (index)
- Skill: `local research notes (protocol-340-forge-network)`

No changes to `crates/*`, `tools/*`, or production protocol behavior.
