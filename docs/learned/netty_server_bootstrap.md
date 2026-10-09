# Netty Server Bootstrap — Reference Behavior (P0-5)

## Scope
How Minecraft 1.12.2 / Forge 14.23.5.2860 binds TCP listeners, configures the event loop groups, and installs the base channel pipeline. Evidence: direct source read of `NetworkSystem.java` (L66–111), `NetworkManager.java`, `DedicatedServer.init` boot path, plus Forge `NetworkRegistry`/`FMLNetworkHandler` hooks.

## Bootstrap facts

### Listener creation — `NetworkSystem.addEndpoint`
- Server binds two ports:
  - `"Server thread"` listener — the RCON channel (deprecated `addLanEndpoint` path is client-side only).
  - Main game listener `0.0.0.0:serverPort` via `addEndpoint(InetAddress, port, accept)`.
- `ServerBootstrap` (not client `Bootstrap`) with:
  - `group(eventLoops, oThreads)`:
    - `eventLoops` = `NioEventLoopGroup(nThreads)` — boss group, default thread count (1 effective listener registrar).
    - `oThreads` = `NioEventLoopGroup(0)` — worker group; 0 = Netty default = 2×CPU cores.
  - `channel(NioServerSocketChannel.class)` on vanilla.
- Child options:
  - `TCP_NODELAY = true` — always set. Latency over throughput; disables Nagle.
  - `SO_KEEPALIVE = true` via `NetworkManager` init? — NO. Keepalive is application-level (`SPacketKeepAlive` every 15 s, 30 s timeout in `NetHandlerPlayServer.update`), not socket-level.
- Forge patch (`NetworkSystem.java.patch`) changes `channel(...)` to `getServerChannel()` in `FMLNetworkHandler` — attempts native epoll transport first (`EpollEventLoopGroup`, `EpollServerSocketChannel`) when `epollAvailable()`, falls back to NIO. On Windows reference runs, always NIO.
- `childHandler` = `ChannelInitializer` that calls `NetworkManager.initChannel` → `NetworkManager` constructor with direction SERVERBOUND, then `setReadOnly` etc.

### Handler installation at accept (`NetworkManager.initChannel` / constructor)
Exact initial order (from source L35–110):
1. `"packet_decoder"` — `NettyPacketDecoder`
2. `"packet_encoder"` — `NettyPacketEncoder`
3. `"timeout"` — `ReadTimeoutHandler` (30 s vanilla; dedicated server `NetworkSystem` uses `readTimeout` = 30)
4. `"legacy_ping"` — `LegacyPingHandler` (handles pre-1.7 `\xFE` ping before framing kicks in)
5. `"splitter"` — `NettyVarint21FrameDecoder`
6. `"prepender"` — `NettyVarint21FrameEncoder`
7. `"fml:packet_handler"` — Forge-added `NetworkDispatcher` (`NetworkManager` patch inserts before `packet_decoder`... verified order: `addBefore("packet_decoder", ...)`) — this handler intercepts ALL inbound traffic first and runs the FML handshake logic until connection completes.

Dynamic later insertions (documented in `machine/network-pipeline.yaml`):
- `"decrypt"` `NettyEncryptingDecoder` — `addBefore("splitter")`
- `"encrypt"` `NettyEncryptingEncoder` — `addBefore("prepender")`
- `"decompress"` `NettyCompressionDecoder` — `addBefore("decoder")` (i.e. before `packet_decoder`)
- `"compress"` `NettyCompressionEncoder` — `addBefore("encoder")`

### Connection bookkeeping
- `NetworkSystem.endpoints` = `List<ChannelFuture>`; `networkManagers` = `List<NetworkManager>` — ALL live connections, iterated every tick in `networkTick()`.
- `addEndpoint` registers `channel.closeFuture().addListener` → removes manager from list.
- Boss/worker threads named `Netty Server IO #N` — NOT daemon by default on dedicated server (they are `FastThreadLocal` threads via `DefaultThreadFactory`).

## Threads
| Thread | Role | Count |
| --- | --- | --- |
| `Netty Server IO #0..N` (boss `eventLoops`) | accept | ~1 |
| `Netty Server IO #0..M` (worker `oThreads`) | read/write/encode for all connections, 2×cores | 2×cores (16 on reference) |
| `Server thread` | `networkTick()` every tick: flush queued outbound, read packets, dispatch handlers | 1 |
| `User Authenticator #N` | Mojang session check (online-mode only) | transient |

## State ownership
- Listener channels: owned by `NetworkSystem` (server lifetime).
- Per-connection `Channel`: owned by Netty; `NetworkManager` holds reference + packet queue + `INetHandler` (state handler interface).
- Connection state (`EnumConnectionState`): stored as Netty channel **attribute** (`attr(key_connection_state)`) — mutable, switched by `setConnectionState` on LOGIN→PLAY transition. This is a de facto thread-shared variable between IO threads and server thread (writes happen on IO thread during handshake, reads happen both sides).

## Buffer ownership
- Inbound: `ByteBuf` allocated by Netty heap allocator (`PooledByteBufAllocator.DEFAULT` in 4.1 default... vanilla pins Netty 4.1.9; pooled ON). Slice passed to `NettyPacketDecoder` → `new PacketBuffer(buf)` → packet `readPacketData` copies scalars out; buffer released by decoder after handler returns.
- Outbound: server thread constructs packet object; `NetworkManager.sendPacket` either writes directly (if channel ready and queue empty) or queues `NetworkManager.InFlightPacket` (packet + listener + future) — NO serialization on server thread at this point. Serialization (PacketBuffer encode) happens on IO thread inside `NettyPacketEncoder.write`.

## Ordering guarantees
- Single IO thread per channel → strict FIFO per connection both directions.
- Outbound: server thread enqueues in tick order; `flush()` called from `networkTick()` (once per tick) AND immediately for high-priority paths (login flow). Ordinary play packets can batch in channel queue between ticks.
- Inbound: decode on IO thread; play packets enqueue to `futureTaskQueue` via `PacketThreadUtil.checkThreadAndEnqueue` → executed next `networkTick()` in arrival order.

## Forge hooks / mod visibility
- `NetworkDispatcher` in every pipeline — mods' channels can't bypass it.
- `NetworkRegistry` (enum): fires `NetworkHandshakeEstablished`, holds per-channel `FMLEmbeddedChannel` pairs for embedded (in-process) communication.
- No Forge event allows mods to add custom pipeline handlers at bootstrap. Mod pipeline interaction happens only via `FMLEmbeddedChannel` or `NetworkManager` reflection.

## Compatibility significance
- TCP_NODELAY, 30 s read timeout, legacy ping handler, and the NIO (not epoll) fallback are all observable by old clients/tools. Rust transport must replicate all four.
- Legacy `LegacyPingHandler` must precede splitter — pre-1.7 pings are raw bytes without VarInt framing; this constrains any framing rewrite to be sniffable.

## Future Rust significance
- If Rust owns TCP (NET-4/5/6), it must reproduce: accept backlog behavior, TCP_NODELAY, 30 s idle disconnect, legacy ping sniff, per-channel FIFO, and the tick-coupled flush cadence of `networkTick()`.
- Worker thread count (2×cores) shapes CPU contention with server thread — any benchmark comparing transport must pin equal worker counts.

## Evidence
- `third_party_reference/minecraft/src/net/minecraft/network/NetworkSystem.java` L66–111 (bootstrap), L120–175 (`networkTick`)
- `third_party_reference/minecraft/src/net/minecraft/network/NetworkManager.java` L35–310
- `third_party_reference/forge/src/patches/minecraft/net/minecraft/network/NetworkSystem.java.patch`
- `machine/network-pipeline.yaml`
