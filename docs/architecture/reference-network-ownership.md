# Reference Network Ownership Map (P0-5)

## Purpose
Authoritative map of which Java component owns each network concern in the reference implementation, to identify legal Rust migration seams without violating single-owner invariants.

## Ownership Matrix

| Concern | Owning Class | Thread | Rust Migration Seams |
| --- | --- | --- | --- |
| TCP listener bootstrap | `NetworkSystem` | main (bootstrap), Netty boss (accept) | NET-4/5/6 (transport replacement) |
| Per-connection lifecycle | `NetworkManager` | Netty IO + server thread | NET-6 (compat proxy) |
| Frame encode (length prefix) | `NettyVarint21FrameEncoder` | Netty IO | NET-1/3/4 |
| Frame decode (split) | `NettyVarint21FrameDecoder` | Netty IO | NET-1/3/4 |
| VarInt/VarLong codec | `PacketBuffer` | any | NET-1 |
| Packet ID registry lookup | `EnumConnectionState` | Netty IO | NET-3 |
| Packet body encode | each `Packet.writePacketData` | Netty IO (serverbound: decode on IO; clientbound: encode on IO, construction on server thread) | NET-3/7/8 |
| Packet body decode | each `Packet.readPacketData` | Netty IO | NET-3 |
| Compression (zlib) | `NettyCompressionEncoder/Decoder` | Netty IO | NET-2/5 |
| Encryption (AES/CFB8) | `NettyEncryptingEncoder/Decoder` + `CryptManager` | Netty IO | NET-2/5 |
| Key exchange (RSA) | `CryptManager`, `NetHandlerLoginServer` | Netty IO + authenticator | NET-2 |
| Connection state machine | `EnumConnectionState` attr + handlers | mixed | NET-3 (codec-level only) |
| Login flow | `NetHandlerLoginServer` | server thread pump + IO | NET-2 |
| Status flow | `NetHandlerStatusServer` | Netty IO | NET-3 |
| FML handshake | `NetworkDispatcher` | Netty IO + server thread | NET-3 only (payload-level) |
| Registry sync payloads | `FMLHandshakeMessage.RegistryData` | server thread | NET-3 only |
| Mod channel dispatch | `SimpleNetworkWrapper` + codecs | embedded (encode) + IO/main (decode) | none — mod Java code |
| Player admission | `PlayerList` | server thread | out of scope |
| Play packet handling | `NetHandlerPlayServer` + `PacketThreadUtil` | server thread | none (game logic) |
| Chunk packet construction | `SPacketChunkData` ctor | server thread | **NET-8** (prime candidate) |
| Backpressure/writability | Netty defaults (none enforced) | Netty | must preserve no-drop semantics |

## Invariants
1. Every mutable structure has exactly one authoritative owner above; dual ownership forbidden.
2. Mod-visible surface (`SimpleNetworkWrapper`, `FMLEmbeddedChannel`, `ByteBuf`) is CONTRACT, not implementation.
3. Pipeline handler names (`splitter`, `prepender`, `packet_decoder`, `packet_encoder`, `timeout`, `legacy_ping`, `fml:packet_handler`) are de facto ABI for coremods.
4. Packet construction cost (server thread) and pipeline cost (IO thread) are separate budgets; benchmarks must attribute to the correct thread (directive §15).

## Evidence
- `machine/network-pipeline.yaml`, `machine/network-state-machine.yaml`, `machine/fml-handshake.yaml`
- `docs/learned/netty_server_bootstrap.md`, `packet_framing.md`, `login_pipeline.md`, `fml_handshake.md`, `outbound_packet_pipeline.md`, `simple_network_wrapper.md`
- `docs/compatibility/network-backpressure.md`, `mod-network-usage-survey.md`, `network-coremod-hazards.md`
