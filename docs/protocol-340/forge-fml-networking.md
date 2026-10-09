# FORGE / FML NETWORKING LAYER (1.12.2, 14.23.5.2860)

Layered ABOVE the vanilla protocol (see `packet-registry-wire.md` /
`machine/network-pipeline.yaml`). Forge extensions are labeled Forge and never
mixed into the vanilla registry unlabeled.

Sources (locally pinned, authoritative):
`third_party_reference/forge/src/.../net/minecraftforge/fml/common/network/`
(`NetworkRegistry.java`, `FMLEmbeddedChannel.java`, `FMLOutboundHandler.java`,
`NetworkEventFiringHandler.java`, `simpleimpl/*`, `handshake/*`), plus in-repo
specs `machine/fml-handshake.yaml`, `machine/network-state-machine.yaml` (forge
substate machine), `docs/learned/fml_handshake.md`, `docs/learned/simple_network_wrapper.md`,
`docs/learned/intermod_communication.md`.

## Channel model

- `NetworkRegistry` juggles FML channel names; client/server register channels
  via a bootstrap `REGISTER`/`UNREGISTER` (vanilla plugin-message mechanism,
  channels other than `MC|` namespaces).
- `FMLEmbeddedChannel`: a **separate Netty channel** per player, spliced into
  the connection as `fml:packet_handler` (addBefore vanilla `packet_handler`).
  Outbound, a `FMLProxyPacket` is intercepted by `FMLOutboundHandler` and
  routed to the embedded channel rather than the vanilla encoder.
- `NetworkEventFiringHandler` fires incoming FML messages onto the mod event
  bus; per-channel type-bounded dispatch.
- Channels of interest: `FML|HS` (handshake), `FML|MP` (multiplayer /
  GameRegistry sync), `FML` (vanilla-mod interop / REGISTER), `FORGE` (Forge
  extension payloads), plus mod-own channels via `@Mod.EventBusSubscriber`.

## FML handshake (FML|HS channel, rides PLAY-state CustomPayload)

Wrapped as `SPacketCustomPayload("FML|HS", payload)` (CB) /
`CPacketCustomPayload("FML|HS", payload)` (SB). Payload = `FMLHandshakeCodec`:
a discriminator byte then message bytes.

Discriminators (wire byte):
- `0` ServerHello (CB)
- `1` ClientHello (SB)
- `2` ModList (BiDi)
- `3` RegistryData (CB, streamed, hasMore flag)
- `255` (-1) HandshakeAck (BiDi, carries phase)
- `254` (-2) HandshakeReset (BiDi)

Server state machine (`FMLHandshakeServerState`), see also `machine/fml-handshake.yaml`:

1. START → serverInitiateHandshake → send `REGISTER` + `ServerHello(protocol=2, overrideDimension)`. → HELLO.
2. HELLO → await `ClientHello(protocol=2)` + `ModList(clientMods)`. Validate via
   `FMLNetworkHandler.checkModList` (checkers: `IgnoredChecker` for `acceptableRemoteVersions='*'`,
   `DefaultNetworkChecker`, `MethodNetworkChecker` `@NetworkCheckHandler`).
   Reject → ERROR + rejectHandshake(reason). Accept → send `ModList(active)`. → WAITINGCACK.
3. WAITINGCACK → await `HandshakeAck(2)`. Then (if !isLocal) `takeSnapshot(false)`
   of `RegistryManager.ACTIVE`; stream `RegistryData` packets one per registry
   (blocks, items, potions, biomes, etc., each with hasMore/name/idMap/dummied/overrides);
   send `HandshakeAck(3)`; fire `NetworkHandshakeEstablished` server side. → COMPLETE.
4. COMPLETE → await `HandshakeAck(3)`. Send `HandshakeAck(4)`; fire CompleteHandshake
   (server). → DONE.
5. DONE → `NetworkDispatcher` → CONNECTED; `PlayerList` finishes
   `initializeConnectionToPlayer`; `SPacketJoinGame` sent.

Vanilla-client fallback: if `C00Handshake.host` lacks `\0FML\0`, server checks
`NetworkRegistry.INSTANCE.isVanillaAccepted(Side.CLIENT)`; if true, logs
"Connection received without FML marker, assuming vanilla", completes as
ConnectionType.VANILLA, skips FML|HS. If rejected, sends a disconnect listing
required client mods.

## SimpleNetworkWrapper / message networking

- `simpleimpl/SimpleNetworkWrapper` + `SimpleImpl` codec + `SimpleIndexedCodec`.
- Per-wrapper channel; mod registers `IMessageHandler` pairs with a discriminator
  index (0..255); wire: `discriminator byte` then `payload` (length-prefixed).
- `ChannelDirection` (TO_CLIENT / TO_SERVER) directs routing; type-bound handlers
  run on a chosen thread (ooks / event loop) via `EventNetworkHandler`.
- This is the modern-mod "mod messages" transport; distinct from raw
  `FML|MP`/`REGISTER`.

## Registry synchronization

- Server streams registry snapshots during handshake WAITINGCACK→COMPLETE
  (`RegistryData` with `hasMore`); client injects id maps. `ModList` precedes it.
- `FML|MP` used for post-handshake multiplayer registry/entity/tile payloads.
- Registry write on the server occurs on the server thread after handshake; the
  vanilla client's registry takeover happens during login state.

## Discriminator / thread behavior

- FML handshake messages decoded on the netty/worker thread; protocol pairing /
  validation enqueued. Packet-level thread handling for Forge messages is
  governed by `NetworkEventFiringHandler` + the mod's declared handler thread.
- FML handshake violation (bad discriminator, wrong phase, failed mod check) →
  `NetworkDispatcher.rejectHandshake` → `SPacketDisconnect` with reason →
  channel closed.

## Forge packet modifications to vanilla

- Forge patches `SPacketCustomPayload` handling and injects `NetworkDispatcher`
  (`fml:packet_handler`); reuses `SPacketLoginSuccess`/`SPacketJoinGame`
  unchanged. Vanilla packet IDs unchanged by Forge; nothing is renumbered.
- `PacketLoggingHandler` (optional debug) logs in/out packets without decoding.

## Rejection behavior

- Failed mod-list check / version mismatch → handshake ERROR → disconnect.
- Out-of-order FML state → decode error + disconnect.
- Vanilla client to modded-only server → rejected with required-mods message.
- Dependency-conflict / absent mod → rejection reason string surfaces the missing
  remote mods.

## Reusable structure

- Handshake phase discriminators and order are stable and version-locked to
  14.23.5.x. A Rust FML client/proxy implementing the FML|HS dialogue must
  replicate RegistryData streaming + ModList negotiation; the existing bots
  (`tools/targeta_client.py`, `tools/protocol-live-probe.py`) already drive this
  live against the reference server — use them as the wire oracle.
- Forge-specific knowledge splits cleanly from vanilla: vanilla
  registry/formats are unchanged; Forge adds channel layer + handshake
  dialogue on top, all inside PLAY-state CustomPayload.
