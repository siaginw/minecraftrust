# Network Backpressure & Flow Control (P0-5)

## Scope
What happens when a client reads slower than the server writes. Source basis: `NetworkManager` (sendPacket/flushOutboundQueue), `NetworkSystem.networkTick`, Netty 4.1.9 `ChannelOutboundBuffer`/`DefaultChannelConfig` defaults, `NettyCompressionEncoder`, plus P0-2 tick knowledge.

## Configuration (vanilla + Forge 14.23.5.2860)
| Setting | Value | Source |
| --- | --- | --- |
| Write buffer high watermark | 64 KiB (Netty default `WriteBufferWaterMark(32*1024, 64*1024)`) | `DefaultChannelConfig` — Minecraft does NOT override |
| Write buffer low watermark | 32 KiB | same |
| autoRead | true | default |
| SO_SNDBUF | OS default (not set) | no `childOption(SO_SNDBUF)` |
| Flush policy | explicit per tick (`networkTick` → `flush()`) + immediate flush for login-critical writes | `NetworkSystem` |

No plugin/observer for writability exists in vanilla/Forge: nothing toggles autoRead, nothing drops packets when `isWritable() == false`. The server keeps queueing.

## Queues in the write path
1. `NetworkManager.sendQueue` (`ConcurrentLinkedQueue<InFlightPacket>`) — unbounded. Drained only while channel open. If channel never activates, packets accumulate FOREVER (leak path for half-open connects).
2. Netty `ChannelOutboundBuffer` — unbounded between write() and flush()/socket drain. High watermark only flips `isWritable()` flag; no rejection.
3. Socket send buffer (OS).

Slow-reader effect: TCP window fills → `ChannelOutboundBuffer` grows → heap grows. There is NO disconnect on outbound queue size, NO timeout tied to writability. ReadTimeout (30 s) only fires on inbound silence — a client that keeps an idle ping alive but never drains reads... note TCP full-duplex: client can still send keepalives while not reading, keeping ReadTimeout satisfied while outbound backlog grows unboundedly. (Paper/spigot-style `packet-limit` fixes do not exist in vanilla/Forge 1.12.2.)

## Inbound direction
- Netty IO thread reads as fast as TCP delivers (autoRead=true).
- Decoded play packets enqueue to server `futureTaskQueue` (via `PacketThreadUtil.checkThreadAndEnqueue`) — unbounded as well; a flood of legal packets inflates per-tick task drain time (measured in section 16 work).
- Compression bomb guards exist (2 MiB decompressed cap, threshold check) — inbound memory bounded per packet, but packet COUNT unbounded.

## Memory growth math (worst case)
- ChunkData ~40–80 KiB compressed per full chunk. Sending 625 spawn chunks to a non-reading client: 25–50 MiB retained in ChannelOutboundBuffer per stalled client.
- With compression enabled this memory is POST-encode (compressed) for encoder output but the PRE-encode packet objects in `sendQueue` are also retained until written.

## Server-thread impact
- `flushOutboundQueue` drains the entire sendQueue every tick regardless of writability — a large backlog makes the server thread spend time in channel.write (allocation + serialization handoff... note: encoder work happens on IO thread; server-thread cost is queue drain + bookkeeping, but PRE-SERIALIZED payloads (ChunkData buffers) were already paid).
- No backpressure signal reaches game logic: `PlayerList.sendToAll` etc. never check `isWritable()` — the world simulation continues producing packets for stalled clients.

## Compatibility significance
- Mods assuming unbounded sends (`sendToAll` in loops) work today; a Rust transport with bounded queues + disconnect would break them.
- Watermark defaults are observable via Netty-reflecting mods; changing them alters `isWritable()` semantics mods may poll (rare but exists).

## Future Rust significance
- Any Rust transport MUST preserve: no packet-drop policy, watermark semantics (or document divergence), and tick-cadence flush. Introducing backpressure-driven disconnects is a behavioral change requiring operator sign-off.
- Rust can expose bounded-queue metrics without behavior change — pure observability win.

## Evidence
- `NetworkManager.java` sendPacket/flushOutboundQueue L120–260
- `NetworkSystem.networkTick` L120–175
- Netty 4.1.9 `DefaultChannelConfig` / `ChannelOutboundBuffer` (library defaults, version pinned by vanilla)
- `machine/network-pipeline.yaml`
