# 1.12.2 JAVA NETWORK IMPLEMENTATION MAP

Core classes in `third_party_reference/minecraft/src/net/minecraft/network/`
(1.12.2 deobf). Thread behavior and pipeline positions cross-referenced to
`machine/network-pipeline.yaml`.

| Class | Role | Key behavior |
|---|---|---|
| `Packet<P>` | packet interface | `readPacketData`/`writePacketData`/`processPacket`; extended by every protocol packet |
| `PacketBuffer` | wire cursor | all primitive/DataType codecs (VarInt, String, BlockPos, UUID, NBT, ItemStack, arrays, enum). See `datatypes-oracle.md`. |
| `EnumConnectionState` | state machine + registry | HANDSHAKING/STATUS/LOGIN/PLAY; `registerPacket(dir, cls)` assigns IDs in registration order; `createPacket` builds via `STATES_BY_CLASS`. Source of packet IDs (see `packet-registry-wire.md`). |
| `EnumPacketDirection` | direction enum | SERVERBOUND / CLIENTBOUND |
| `PacketThreadUtil` | thread enforcer | `checkThreadAndEnqueue` — if packet handler thread != current, enqueue to that thread's executor |
| `INetHandler` / `NetHandlerPlayServer` etc. | handler interface/impl | each packet's `processPacket` calls a handler method (`handleChunkData`, etc.) |
| `NetworkManager` | connection owner | holds pipeline, channels, packet handler; `enableEncryption`, `setCompressionThreshold`, `dispatchPacket`; SimpleChannelInboundHandler<Packet> |
| `NetworkSystem` | server acceptor | `ServerBootstrap`, per-connection initial pipeline, `addEndpoint` |
| `NettyVarint21FrameEncoder/Decoder` | framing | VarInt21 length-prefix framing (splitter/prepender) |
| `NettyPacketEncoder` | outbound encode | writes packet id (VarInt) then `writePacketData` into a `PacketBuffer` |
| `NettyPacketDecoder` | inbound decode | reads VarInt id, constructs via `EnumConnectionState.createPacket`, `readPacketData`, then dispatches |
| `NettyCompressionEncoder/Decoder` | compression | threshold-gated; `[VarInt uncompressedSize][zlib]` where 0 = stored; threshold<0 → removed |
| `NettyEncryptingEncoder/Decoder` + `NettyEncryptionTranslator` | encryption | AES-CFB8 (login key), spliced before framing |
| `LegacyPingHandler` | pre-1.6 ping | 0xFE server-list ping; passes through non-ping bytes; self-removes |
| `ServerStatusResponse` | status JSON | latency/version/players/motd model |
| `ThreadQuickExitException` | thread control | quick-exit from packet processing on wrong thread |
| `datasync/EntityDataManager` + `DataSerializers` | entity metadata | id byte + typeId varint + value; 0xFF terminator; serializer ids 0..13 (see datatypes-oracle) |
| `login/*`, `handshake/*`, `status/*` | login flow | NetHandlerLoginServer state substates HELLO→KEY→AUTHENTICATING→READY_TO_ACCEPT→ACCEPTED |

## Outbound encode path

1. Server thread builds packet, calls `NetworkManager.sendPacket`.
2. `dispatchPacket` → if on ServerThread, enqueue to the connection's task queue; worker invokes.
3. Netty outbound: `packet_handler`→(fml:packet_handler if Forge)→`NettyPacketEncoder` → writes `VarInt id` + payload into `PacketBuffer`.
4. `NettyCompressionEncoder` (if threshold active): `VarInt(0 or uncompressedLen)` + zlib; length under threshold → stored form.
5. `NettyVarint21FrameEncoder` length-prefixes → `NettyEncryptingEncoder` (if enabled) → socket.

## Inbound decode path (mirror)

socket → decrypt (if enabled) → `NettyVarint21FrameDecoder` (frame) →
`NettyCompressionDecoder` (decompress if flagged) → `NettyPacketDecoder`
(VarInt id → `EnumConnectionState.createPacket` → `readPacketData`) →
(fml:packet_handler if Forge) → `packet_handler` (NetworkManager) →
`PacketThreadUtil` enforces handler thread → `processPacket`.

## SRG / obfuscation

Local sources are MCP-deobf (readable names). Distribution jars use SRG/
obfuscated names; `third_party_reference/mappings/` (stable 39-1.12) translates
obf↔SRG↔MCP. Packet **IDs are registration-order assigned**, stable across
obfuscation — never infer an ID from a class name.

## Rust boundaries already wired

- `crates/chunk-packet` — replaced the section-payload encode path (JNI
  `Java_...NativeChunkPacket_encodeSections`), keeping Java framing.
- `crates/compression` — M2-C native per-packet zlib, screaming into the Java
  `CompressionCtx_create/compress/freeRaw` JNI; vanilla threshold/framing stays Java.
- `crates/ffi` — the only crate allowed JNI; exports chunk encode + compression.
- Tranform targets (outbound encode, compression) documented in
  `machine/coremod-targets.yaml` / `docs/research/network-rust-seams.md`.
