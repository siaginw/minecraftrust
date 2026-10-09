# Outbound Packet Pipeline (P0-5)

## Scope
Server→client packet lifecycle: construction, queueing, serialization, pipeline traversal, flush. Evidence: `NetworkManager.java` (sendPacket, sendQueue, flushOutboundQueue), `NetworkSystem.networkTick`, `NettyPacketEncoder`, `PlayerList`/`ServerManagementEventHandler` call sites, `SPacketChunkData`.

## Lifecycle stages
1. **Construction (server thread)** — game logic builds packet object (e.g. `new SPacketEntityVelocity(...)`, or `SPacketChunkData` full serialization including block storage → network format on construction... verified: `SPacketChunkData` constructor extracts sections + tile entities into `this.chunkData` PacketBuffer — full serialization happens in CONSTRUCTOR on the calling (server) thread).
2. **`NetworkManager.sendPacket(packet)`** (server thread):
   - If `channel == null || !channel.isOpen() || sendQueue not empty`: append `InFlightPacket(packet, listener, future)` to `sendQueue` — pure queueing, NO serialization.
   - Else: `channel.writeAndFlush`... actually `channel.write(packet, voidPromise)`; flush deferred.
3. **`NetworkSystem.networkTick()`** (server thread, every tick):
   - Iterates ALL NetworkManagers: `manager.flushOutboundQueue()` — drains `sendQueue` into channel writes while channel active; then `channel.eventLoop().execute(...)`? no — flushOutboundQueue runs on server thread, issuing channel writes that are thread-safe (Netty marshals to IO thread).
   - `channel.flush()` is issued via `manager.flush()` — Netty queues actual flush on event loop.
4. **Pipeline (Netty IO thread, at flush)**:
   - `packet_encoder` (`NettyPacketEncoder`): looks up packet ID via channel attr state; allocates `PacketBuffer` (direct? heap — `ctx.alloc().buffer()` pooled); writes VarInt ID; `packet.writePacketData(buf)` — per-packet serialization HERE if not pre-serialized (ChunkData: `writePacketData` just writes pre-built buffer — constructor did the work).
   - `compress` (`NettyCompressionEncoder`): if threshold >= 0 and size >= threshold: `uncompressedSize` VarInt + zlib deflated; else `uncompressedSize=0` VarInt + raw.
   - `encrypt` (`NettyEncryptingEncoder`): AES/CFB8 over full frame.
   - `prepender` (`NettyVarint21FrameEncoder`): VarInt length prefix.
   - Wait — actual handler order outbound is ENCODER → (encrypt/compress inserted BEFORE encoder? no) — verified insertion points: compress addBefore("encoder")? `NetworkManager.setCompressionThreshold` uses `addBefore("encoder", ...)`, encrypt `addBefore("prepender",...)`. Hmm both directions: outbound order bottom-up = packet_encoder → compress → encrypt → prepender. Insertion `addBefore("packet_encoder")`? Correct final outbound order: `prepender` LAST (outermost, writes length), then `encrypt`, then `compress`, then `packet_encoder` innermost. (See `machine/network-pipeline.yaml` authoritative ordering table.)
5. **Socket write** — IO thread, `ChannelOutboundBuffer` → JDK socket channel. Backpressure: unflushed/unwritable bytes accumulate in Netty `ChannelOutboundBuffer` (see `network-backpressure.md`).

## Threads
| Stage | Thread |
| --- | --- |
| Packet construction | server thread (all vanilla packets; ChunkData serialization included) |
| Queue drain `flushOutboundQueue` | server thread (invokes channel.write, Netty moves to IO) |
| Serialization (writePacketData) | Netty IO thread — EXCEPT pre-serialized packets (ChunkData etc.) |
| Compression | Netty IO |
| Encryption | Netty IO |
| Length prefix + socket | Netty IO |

## State ownership
- `sendQueue`: NetworkManager instance, mutated server thread + read in flush — confined to server thread, handoff to Netty via thread-safe channel.write.
- Channel writability state: Netty internal, read via `channel.isWritable()`.
- `InFlightPacket.listener` (GenericFutureListener) fired on IO thread.

## Ordering guarantees
- FIFO per connection, preserved through queue + single IO thread.
- Send order within a tick = call order; cross-tick = tick order.
- `sendPacket` immediately after channel-active login flow bypasses tick batching (queue empty fast path writes at once) — login latency benefits; play packets batch per tick.

## Allocation profile (per outbound packet)
- 1 packet object (server thread).
- 1 `InFlightPacket` wrapper if queued.
- 1+ pooled ByteBuf (encoder) sized ID+payload.
- ChunkData-type: additional pre-built `PacketBuffer` + section arrays (server thread — the big cost).
- Compression: deflate output buffer (~input size worst case).
- Encryption: cipher output buffer (full frame copy).
- Typical small packet (KeepAlive): ~3 allocations, ~100–200 B total. ChunkData full chunk: 10–100+ KiB and hundreds of allocations (sections, TE NBT).

## Compatibility significance
- Pre-serialization-in-constructor pattern (ChunkData, maps) means server-thread cost is packet-construction-dominated; Rust transport (NET-4/5) would NOT remove that cost — only NET-3/NET-8 (codec/construction move) would.
- Flush cadence: once per tick via networkTick — clients observe ≤20 Hz discrete flush trains; a Rust transport flushing more often would change burst timing (mostly harmless but observable in bandwidth shaping).

## Future Rust significance
- NET-7 (selective serialization move) targets stage 4's `writePacketData` for heavy packets while keeping Netty: biggest TPS-relevant candidate if server-thread construction dominates.
- NET-4/5 remove stages 4–5 CPU from JVM but NOT stage 1 (construction).

## Evidence
- `third_party_reference/minecraft/src/net/minecraft/network/NetworkManager.java` L120–260
- `third_party_reference/minecraft/src/net/minecraft/network/NetworkSystem.java` L120–175
- `third_party_reference/minecraft/src/net/minecraft/network/play/server/SPacketChunkData.java` L18–128
- `machine/network-pipeline.yaml`
