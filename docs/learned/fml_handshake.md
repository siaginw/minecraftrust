# FML Handshake — Complete State Machine (P0-5)

## Scope
Forge 14.23.5.2860 mod handshake: both sides of the `FML|HS` CustomPayload conversation, registry synchronization, and vanilla fallback. Evidence: `NetworkDispatcher.java` L70–300, `FMLHandshakeMessage.java` L40–330, `FMLHandshakeServerState.java`, `FMLHandshakeClientState.java`, `FMLHandshakeCodec.java`, `FMLNetworkHandler.checkModList` L135–205, `NetworkModHolder.java` L44–340, `RegistryManager`/`GameData.injectSnapshot`.

## Where it runs: inside PLAY protocol state
Critical timing fact: the FML handshake is carried by `SPacketCustomPayload`/`CPacketCustomPayload` packets whose IDs live in the **PLAY** protocol state. Sequence:
1. LOGIN completes: server sends `SPacketEnableCompression`, then `SPacketLoginSuccess`.
2. `NetworkDispatcher` intercepts outbound LoginSuccess: does NOT let vanilla `PlayerList.initializeConnectionToPlayer` run yet. Instead fires the FML handshake, holding the player admission.
3. Connection state flips to PLAY **first** (`manager.setConnectionState(PLAY)` inside dispatcher when LoginSuccess is sent).
4. FML handshake messages flow as CustomPayload (`FML|HS` channel).
5. On completion (`DONE`), dispatcher releases: `PlayerList.initializeConnectionToPlayer` proceeds — JoinGame, dimension travel, player entity creation.

**Conclusion for "PLAY protocol state" vs "player fully admitted"**: they are SEPARATE. PLAY state begins at LoginSuccess; world admission begins at FML handshake completion. Any Rust transport must model this as two distinct milestones (see `machine/network-state-machine.yaml` `forge_fml_substate_machine`).

## Message discriminators (`FMLHandshakeCodec`)
| Discriminator byte | Message class | Direction |
| --- | --- | --- |
| 0 | `ServerHello` | S→C |
| 1 | `ClientHello` | C→S |
| 2 | `ModList` | both |
| 3 | `RegistryData` | S→C |
| 254 (wire -2) | `HandshakeReset` | both |
| 255 (wire -1) | `HandshakeAck` | both |

Discriminator is a single leading byte of the CustomPayload payload. `HandshakeAck` body = phase byte.

## Server state machine (`FMLHandshakeServerState` + `NetworkDispatcher`)
| # | Server state | Receives | Sends | Next |
| --- | --- | --- | --- | --- |
| 1 | START | — | `ServerHello(protocol=2, dim=player dimension)` + REGISTER payload (channel names incl. `FML|HS`) | HELLO |
| 2 | HELLO | `ClientHello(protocol)`; `ModList(clientMods)` | validates mod list; sends `ModList(serverMods)` | WAITINGCACK |
| 3 | WAITINGCACK | `HandshakeAck(2)` (client acked server ModList) | streams `RegistryData` per registry; then `HandshakeAck(3)` | COMPLETE |
| 4 | COMPLETE | `HandshakeAck(3)` (client injected snapshot) | `HandshakeAck(4)`; fires `CompleteHandshake(SERVER)` | DONE |
| 5 | DONE | — | `initializeConnectionToPlayer` proceeds; JoinGame | — |

Client mirrors (`FMLHandshakeClientState`): START → HELLO(wait ServerHello, reply ClientHello+ModList) → WAITINGCACK(consume ModList, ack 2) → WAITINGCACK... registry consume loop (ack per RegistryData? no—client acks 3 after all) → ACK(4) → DONE.

Error path: mod list rejection → state ERROR → `rejectHandshake(reason)` → dispatcher sends vanilla `SPacketDisconnect` with rejection text; channel closes.

## RegistryData chunked streaming
- `RegistryManager.takeSnapshot(false)` → `ForgeRegistry.makeSnapshot` per registry: ids, aliases, substitutions(blocked), dummied, overrides.
- Sent as MULTIPLE RegistryData messages (one per registry — blocks, items, potions, biomes, ..., plus modded registries).
- `hasMore` flag: client accumulates until `hasMore == false`, then `GameData.injectSnapshot`, replies `HandshakeAck(3)`.
- Largest single payloads of the whole handshake (item registry on modded packs: thousands of entries).

## Mod-list negotiation (`NetworkModHolder`)
Checker types:
- `IgnoredChecker` — `acceptableRemoteVersions="*"`: accepts anything.
- `DefaultNetworkChecker` — range/version compare against `remoteVersions.get(modId)`.
- `MethodNetworkChecker` — invokes mod's `@NetworkCheckHandler` method (arbitrary mod code, server side).
Missing required server mod on client → rejection. Extra client-only mods → accepted (unless checker says otherwise).

## Vanilla fallback
- Handshake lacks `\0FML\0` in C00Handshake address → `NetworkRegistry.isVanillaAccepted(Side.CLIENT)`.
- Accepted → connection type VANILLA; FML|HS skipped entirely; `initializeConnectionToPlayer` immediate.
- Rejected → disconnect with required-mods message.

## Threads
| Work | Thread |
| --- | --- |
| FML message decode (codec) | Netty IO |
| ServerHello/modlist/registry serialization | Server thread (during `initializeConnectionToPlayer`, i.e. tick context) |
| HandshakeAck processing / state advance | Netty IO (dispatcher `channelRead0`) |
| `@NetworkCheckHandler` invocation | server thread |
| Snapshot injection on client | Netty IO + main client thread (server side: only sends) |

## State / buffer ownership
- Handshake state: `NetworkDispatcher.serverState`/`clientState` enums — mutated from Netty IO thread (acks) AND server thread (initiation). Effectively single-threaded per phase but not formally confined; relies on channel event-loop serialization for inbound side.
- Registry snapshot: `RegistryManager.ACTIVE` frozen → per-connection serialized copy; client receives into fresh map then `injectSnapshot` writes global registries (client-side only).
- ByteBufs: `FMLHandshakeMessage.toBytes` writes into Netty `ByteBuf`; `FMLProxyPacket` wraps the payload; conversion to vanilla `SPacketCustomPayload` copies bytes into `PacketBuffer`. One extra full-payload copy vs vanilla path.

## Compatibility significance
- Channel name `FML|HS` string, discriminator byte values, ack phase numbers (2/3/4), protocol version 2 — all fixed wire constants; must match exactly for Forge clients.
- RegistryData `hasMore` accumulation protocol and `injectSnapshot` semantics (ID remap on client) are behavioral contract for modded clients.
- Rejected-modlist disconnect text shown to users — visible UX.

## Future Rust significance
- The handshake is packet-payload-level logic (CustomPayload payloads), NOT pipeline-level. A NET-4/5 Rust transport can carry it transparently. Only NET-3 (packet codec in Rust) would need FML codec reimplementation.
- RegistryData serialization is measurable CPU on server thread during login storms — quantify in section 7 work.

## Evidence
- `third_party_reference/forge/src/main/java/net/minecraftforge/fml/common/network/NetworkDispatcher.java` L70–300
- `.../network/handshake/FMLHandshakeMessage.java` L40–330
- `.../network/handshake/FMLHandshakeServerState.java`, `FMLHandshakeClientState.java`, `FMLHandshakeCodec.java`
- `.../internal/FMLNetworkHandler.java` L135–205
- `.../internal/NetworkModHolder.java` L44–340
- `net/minecraftforge/fml/common/registry/RegistryManager.java` L120–170; `GameData.java` L600–690
- `machine/fml-handshake.yaml`
