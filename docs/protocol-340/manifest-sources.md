# PROTOCOL-340 SOURCE MANIFEST

Minecraft Java Edition 1.12.2 · Protocol 340 · Forge 14.23.5.x (ref build 2860).
Version-pinned evidence registry. Retrieval date ranges: 2026-09-19..2026-09-21.

## Evidence hierarchy (apply in this order)

1. Actual 1.12.2 server/client bytecode or source (local `third_party_reference/`)
2. Forge 14.23.5.x source/patches (local `third_party_reference/forge/`)
3. Version-pinned protocol-340 / archived wiki.vg docs
4. Minecraft Wiki historical / version-specific pages
5. External protocol libraries/projects (minecraft-data, Cuberite, etc.)
6. Community posts

When sources disagree, the installed 1.12.2 + Forge implementation wins
(`third_party_reference/minecraft/` and `third_party_reference/forge/` are
locally pinned and authoritative).

## Local authoritative artifacts (trust = AUTHORITATIVE)

| Source | Path | Version | Notes |
|---|---|---|---|
| Vanilla server jar SHA1 | `third_party_reference/minecraft/` | 1.12.2 / 340 | sha1 886945bfb2b978778c3a0288fd7fab09d315b25f |
| Decompiled / MCP-deobf network sources | `third_party_reference/minecraft/src/net/minecraft/network/` | 1.12.2 | `PacketBuffer.java`, `EnumConnectionState.java`, `NettyPacketEncoder.java`, `NettyPacketDecoder.java`, `NettyCompressionEncoder.java`, `NettyCompressionDecoder.java`, `NetworkManager.java`, `NetworkSystem.java`, `NettyVarint21FrameDecoder.java`, `NettyVarint21FrameEncoder.java`, `Packet.java`, all `play/server/*`, `play/client/*`, `login/*`, `handshake/*`, `status/*`, `datasync/*` |
| Forge reference | `third_party_reference/forge/` + `src/.../fml/common/network/` | 14.23.5.2860 | commit d3f01843f7e7a4f613b5e8113d381fd8747b4343, branch 1.12.x tag 14.23.5.2860 |
| MCP mappings | `third_party_reference/mappings/` | stable 39-1.12 | channel `stable`, alt `snapshot_20171003` |
| In-repo protocol registry | `machine/protocol-340-packets.yaml` | 340 | 124 packets, generated from runtime reflection over `EnumConnectionState` |
| In-repo capture | `fml-hs-capture.txt` | 340 + Forge | 925 MB live FML|HS wire capture |

## Main documentation

| URL | Title | Target ver | Retrieval | Explicit 340? | Trust | Concepts |
|---|---|---|---|---|---|---|
| https://minecraft.wiki/w/Protocol#Minecraft_Java_Edition_1.12.2 | Protocol (anatomy, sub-articles) | current + historical | 2026-09-21 | Historical section | REFERENCE (verify everything) | packet layouts, states, VarInt, chunk, entity meta |
| https://wiki.vg/Protocol_version_numbers | Protocol version numbers | history | 2026-09-21 | 340 = 1.12.2 | REFERENCE | version history, 1.8+ numbering |
| https://wiki.vg/Data_types | Data types | 1.8+ (340-era architecture) | 2026-09-21 | Partial (pre-1.13 type era) | REFERENCE | VarInt, VarLong, Position, String, UUID, NBT, Slot |
| https://wiki.vg/VarInt_And_VarLong | VarInt / VarLong | 1.8+ | 2026-09-21 | Yes (format unchanged in 340) | HIGH | 7-bit MSB encoding |
| https://minecraft.wiki/w/Chunk_format | Chunk format | varies | 2026-09-21 | No (check 1.12.2) | REFERENCE | section/palette history |
| https://minecraft.wiki/w/Java_Edition_1.12.2 | 1.12.2 release | 1.12.2 | 2026-09-21 | Yes | HIGH | release context, version pin |
| https://mwgit.github.io/minecraft-data/ | minecraft-data derived docs | versioned | 2026-09-21 | Yes | HIGH (schema, not Java source) | per-version protocol.json |
| https://github.com/PrismarineJS/minecraft-data | minecraft-data repo | all | 2026-09-21 | Yes | HIGH schema | `data/pc/1.12.2/protocol.json`, types, packet maps |
| https://github.com/cuberite/cuberite | Cuberite | 1.8–1.12.2 | 2026-09-21 | Yes (`Protocol.h` v1_12_2=340) | HIGH impl | full-server 340 packet serializer, `cChunkDataSerializer` |
| https://github.com/PaperMC/Velocity | Velocity | 1.8 → latest | 2026-09-21 | Yes (`ProtocolVersion.MINECRAFT_1_12_2(340)`) | HIGH impl | framing, AES-CFB8, compression threshold, per-version tables |
| https://github.com/PrismarineJS/node-minecraft-protocol | node-minecraft-protocol | multiplexed, 340 in range | 2026-09-21 | Yes | HIGH impl | schema-driven codec, state machines |

## Version-history sources

| URL | Title | Target ver | Explicit 340? | Trust | Concepts |
|---|---|---|---|---|---|
| https://wiki.vg/Protocol_version_numbers | Protocol version numbers | history | yes (340=1.12.2) | REFERENCE | version lineage, 1.12.2 = 340 |
| `machine/reference-versions.yaml` | In-repo version pin | 1.12.2 | yes | AUTHORITATIVE | artifacts + hashes + licenses |

## Per-concept primary sources (local authoritative)

| Concept | Primary local source |
|---|---|
| VarInt / VarLong / String / Position / UUID / NBT-inside-packet / ItemStack / arrays / enum | `third_party_reference/minecraft/src/net/minecraft/network/PacketBuffer.java` |
| State machine + packet-ID maps | `.../network/EnumConnectionState.java` |
| Frame splitter/prepender | `.../NettyVarint21FrameDecoder.java`, `.../NettyVarint21FrameEncoder.java` |
| Compression framing | `.../NettyCompressionDecoder.java`, `.../NettyCompressionEncoder.java` |
| Encryption | `.../NettyEncryptingDecoder.java`, `.../NettyEncryptingEncoder.java`, `.../NettyEncryptionTranslator.java`, `.../NetworkManager.java` (enableEncryption) |
| Login flow | `.../NetHandlerLoginServer.java`, `login/server/*`, `login/client/*` |
| Handshake | `.../NetHandlerHandshakeTCP.java`, `handshake/client/C00Handshake.java` |
| Chunk packet | `.../play/server/SPacketChunkData.java` + `Chunk.java` / `BlockStateContainer.java` / `ExtendedBlockStorage.java` |
| Entity metadata | `.../datasync/EntityDataManager.java`, `.../datasync/DataSerializers.java` |
| Outbound dispatch | `.../NetworkManager.java`, `.../NettyPacketEncoder.java` |
| Inbound dispatch | `.../NettyPacketDecoder.java`, `.../PacketThreadUtil.java` (thread enqueue) |
| FML handshake | `third_party_reference/forge/src/.../fml/common/network/handshake/FMLHandshakeServerState.java`, `FMLHandshakeClientState.java`, `.../NetworkDispatcher.java`, `.../FMLHandshakeCodec.java`, `.../FMLHandshakeMessage.java` |
| SimpleNetworkWrapper | `.../fml/common/network/simpleimpl/` |
| FMLEmbeddedChannel | `.../fml/common/network/FMLEmbeddedChannel.java` |

## Do NOT copy

Do not copy entire copyrighted wiki.vg/Minecraft-Wiki pages into the repo.
Store concise technical summaries, mappings, citations. This manifest + the
accompanying knowledge docs are summaries with references, not content dumps.
