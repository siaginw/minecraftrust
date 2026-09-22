# SimpleNetworkWrapper & Mod Channel Stack (P0-5)

## Scope
The supported mod networking API of Forge 1.12.2: channel registration, message encode/decode, thread dispatch. Evidence: `SimpleNetworkWrapper.java` L110–200, `SimpleIndexedCodec`, `FMLEmbeddedChannel.java`, `FMLIndexedMessageToMessageCodec.java` L75–145.

## The supported stack
```
Mod code
  -> SimpleNetworkWrapper.sendTo/SendToAll/SendToDimension/SendToAllAround (8 dispatch helpers)
     -> channel attr gets EmbeddedChannel pair (client+server side) from NetworkRegistry
        server-side FMLEmbeddedChannel.writeOutbound(msg)
          -> SimpleIndexedCodec.encode: IMessage.toBytes(ByteBuf)
          -> discriminator byte (from MessageBuilder registration)
          -> FMLIndexedMessageCodec -> FMLProxyPacket
     -> FMLProxyPacket injected into player's NetworkManager queue (thread-safe sendPacket)
Wire: SPacketCustomPayload("mymod", [discriminator][IMessage bytes])
Inbound: NetworkDispatcher routes registered channel to channel handler
  -> FMLProxyPacket -> SimpleIndexedCodec.decode -> IMessage.fromBytes
  -> IMessageHandler.onMessage invoked on the mod-registered thread context:
     1.12.2 default = main thread enqueue (NetworkRegistry.EVENT_BUS style scheduling);
     mods can opt into Netty-thread execution via EventContext (registerMessage overload w/ context enqueue flag)
```

## Discriminator allocation
- `registerMessage(handler, messageClass, discriminator, Side)` — mod-chosen byte per message type.
- `SimpleIndexedCodec` maps discriminator ↔ message class both directions.
- Collision within channel = mod bug, silent overwrite risk (last registration wins in HashMap).

## Thread dispatch — the critical detail
`IMessageHandler.onMessage` runs:
- If mod used `ctx.enqueueWork(...)` / default wrapper helpers — **server main thread** (via `MinecraftServer.addScheduledTask`), same queue as packet handlers (drained in `networkTick`).
- If mod executes directly in `onMessage` without enqueue — **Netty IO thread** (documented hazard: mods touching world state off-thread; Forge javadoc warns but does not enforce).
Real-world mods commonly do `ctx.getServerHandler().player.getServerWorld().addScheduledTask(...)` manually.

## FMLEmbeddedChannel
- Each registered channel gets an `FMLEmbeddedChannel` PAIR (client + server side embedded pipelines), created at network registration time (mod construction).
- Embedded channel = Netty `EmbeddedChannel` — in-process pipeline with no socket; `writeOutbound` runs the full encode pipeline synchronously on CALLING thread (server thread for sendToAll → **mod message serialization happens on the server thread**, then handed to real channel).
- `FMLEmbeddedChannel` also used by mods for intra-JVM messaging and by some mods as a raw Netty handle (de facto API surface — see compatibility survey).

## State ownership
- Channel registry: `NetworkRegistry.channels` map (enum singleton) — written at mod init (single-threaded), read per message.
- Handler instances: mod-owned singletons, invoked concurrently only if mod opts into Netty-thread execution.
- Per-connection: nothing in SimpleNetworkWrapper — all stateless dispatch.

## Buffer ownership
- `IMessage.toBytes(ByteBuf)`: mod writes into Netty buffer allocated by codec (heap, ~payload sized).
- `FMLProxyPacket` retains the payload ByteBuf; conversion to `SPacketCustomPayload` copies into `PacketBuffer` (copy #1), vanilla encoder writes into frame buffer (copy #2 via `writeBytes`), compression/encryption may transform (copy #3). Minimum two full-payload copies per mod message server→client.
- Inbound: `CPacketCustomPayload` holds `PacketBuffer`; dispatcher converts to `FMLProxyPacket` (retains, no copy), codec decodes into fresh `IMessage` (mod-allocated).

## Compatibility significance
- Entire mod ecosystem's networking funnels through this API — its wire format (`[discriminator][payload]` inside CustomPayload channel) and thread contract are the de facto modding ABI.
- Mods that keep `ByteBuf` references past `onMessage` return (retention bug) rely on Netty refcount behavior — Rust replacement of pipeline would change release timing and break them.

## Future Rust significance
- IMessage encode/decode = mod Java code — CANNOT move to Rust. Rust can only own transport + framing + CustomPayload envelope.
- The double-copy (FMLProxyPacket → SPacketCustomPayload) is a measurable allocation seam for NET-3 (codec-level copy elimination), not NET-4/5.

## Evidence
- `third_party_reference/forge/src/main/java/net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper.java`
- `.../simpleimpl/SimpleIndexedCodec.java`
- `.../network/FMLEmbeddedChannel.java`
- `.../network/FMLIndexedMessageToMessageCodec.java`
