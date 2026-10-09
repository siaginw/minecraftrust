# Network Security & Resource Semantics (P0-5)

## Scope
Reference behavior and vulnerability characteristics of Minecraft 1.12.2 / Forge 14.23.5.2860 networking stack, separating observable reference contracts from future native hardening opportunities.

## Reference Behavior vs Future Hardening Matrix

| Attack / Abuse Vector | Reference Behavior (Minecraft 1.12.2 / Forge 2860) | Reference Vulnerability / Cost | Future Rust Hardening Opportunity |
|:---|:---|:---|:---|
| **Connection Flood** | Unbounded TCP accept; spawns `Channel` + `NetworkManager` for every SYN handshake | File descriptor / socket exhaustion; heap allocation per connection (~50-100 KB) | Rate-limiting at accept boundary; SYN cookie validation; IP connection caps |
| **Status Ping Flood** | Handshake -> StatusRequest -> full JSON status response generated per query | CPU spent serializing JSON server status response; Netty IO thread saturation | Cached pre-serialized status response buffer; token-bucket status rate limiter |
| **Slow Readers** | Server writes to `ChannelOutboundBuffer` without checking `isWritable()`; `NetworkManager.sendQueue` unbounded | Unbounded heap growth as outbound packets accumulate for stalled socket | Configurable high-watermark disconnect threshold; drop non-critical cosmetic packets |
| **Slow Senders** | 30-second `ReadTimeoutHandler` is the sole defense | Slowloris-style socket exhaustion holding connection open by trickling 1 byte every 29s | Minimum throughput enforcement (e.g. min 100 bytes/sec over rolling 5s window) |
| **Packet Task Flooding** | All 31 play packets unconditionally enqueued to `MinecraftServer.futureTaskQueue` | Unbounded queue growth; server-thread stall draining thousands of tasks per tick | Inbound packet quota per player per tick (e.g. max 500 packets/tick before kick) |
| **CustomPayload Flooding** | Max 32767 bytes per packet; no frequency cap in vanilla or Forge | Heavy GC pressure from `ByteBuf` copies; mod handlers stalling server thread | Per-channel bandwidth limiter; packet rate throttling on `CPacketCustomPayload` |
| **Compression Bomb** | Declared uncompressed size checked against 2,097,152 (2 MiB) cap | Deflater memory expansion bounded to 2 MiB per frame, but multiple frames can accumulate | Strict ratio check (`decompressed_size / compressed_size <= MAX_RATIO`) |
| **Invalid VarInt** | Splitter/Decoder throws exception and immediately closes channel | Negligible CPU cost; clean socket teardown | Zero-allocation reject path in native framing layer |
| **Oversized Packets** | Splitter caps at 2,097,151 bytes; `readString` caps per field | Throws `DecoderException` and closes channel | Fast-fail rejection before allocating buffers |
| **Outbound Queue Growth** | `NetworkManager.sendQueue` drains once per tick in `networkTick()` | Packets queued during long tick pauses consume heap until tick completes | Bounded ring buffer with backpressure signals |

## Compatibility Invariants
1. **Never Drop Packets Silently Without Disconnect**: Vanilla and Forge mod clients have no packet retransmission logic. Dropping packets causes client/server state desynchronization. If limits are exceeded, the reference behavior is disconnection (`disconnect(TextComponent)`).
2. **Preserve Status JSON Dynamic Query Hooks**: Forge fires `ServerStatusResponse` events (`FMLCommonHandler.handleServerStatusResponse`). A caching layer must respect event listeners that dynamically modify player counts or MOTD.
