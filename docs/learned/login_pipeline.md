# Login & Authentication Pipeline (P0-5)

## Scope
Connection state transitions from TCP accept through LOGIN state, encryption, compression, and the boundary into PLAY. Evidence: direct read of `NetHandlerHandshakeTCP.java`, `NetHandlerLoginServer.java`, `NetHandlerStatusServer.java`, `NetworkManager.java`, `CryptManager.java`, plus Forge `NetworkDispatcher.java` and `C00Handshake.java.patch`.

## Connection-state machine (vanilla)
`EnumConnectionState` (channel attribute `PROTOCOL_ATTRIBUTE_KEY`) drives decoder/encoder packet lookup:

```
TCP accept
  -> HANDSHAKING (id -1)
       serverbound 0x00 C00Handshake {protocolVersion=340, host, port, requestedState}
         requestedState == STATUS (1) -> STATUS
         requestedState == LOGIN (2)  -> LOGIN
         protocolVersion > 340  -> SPacketDisconnect("Outdated server! I'm still on 1.12.2")  [Java side: "multiplayer.disconnect.outdated_server"]
         protocolVersion < 340  -> SPacketDisconnect("multiplayer.disconnect.outdated_client")
  -> STATUS (id 1)
       0x00 CPacketServerQuery -> SPacketServerInfo (JSON ServerStatusResponse)
       0x01 CPacketPing -> SPacketPong -> channel close
  -> LOGIN (id 2)
       0x00 CPacketLoginStart -> (online) SPacketEncryptionRequest
                                -> (offline) skip to success path
       0x01 CPacketEncryptionResponse -> server derives shared secret via RSA
       server sends SPacketEnableCompression -> both sides insert compress/decompress
       server sends SPacketLoginSuccess (UUID + name as strings) -> state := PLAY
  -> PLAY (id 0)
```

## LOGIN substates (`NetHandlerLoginServer.CurrentLoginState`)
| Substate | Meaning | Set by |
| --- | --- | --- |
| `HELLO` | awaiting `CPacketLoginStart` | constructor |
| `KEY` | awaiting `CPacketEncryptionResponse` | `processServerLoginKey` path (online) |
| `AUTHENTICATING` | async Mojang session-server POST | `NetHandlerLoginServer.loginSuccess`-adjacent authenticator thread |
| `READY_TO_ACCEPT` | auth OK (or offline) | authenticator completion / offline fast-path |
| `DELAY_ACCEPT` | UUID collision — waiting for old player entity to be removed | `tryAcceptPlayer` when `PlayerList.getPlayerForUUID != null` |
| `ACCEPTED` | compression + LoginSuccess sent; `initializeConnectionToPlayer` begun | `tryAcceptPlayer` |

Key methods: `processLoginStart`, `processEncryptionResponse`, `update()` (drives state machine each tick, 600-tick `connectionTimer` timeout → `NetworkManager TEXT_IPRUNED_TIMEOUT`-style disconnect for stuck/slow logins), `tryAcceptPlayer()`.

## Encryption (online-mode)
1. Server generates RSA keypair (`CryptManager.generateKeyPair`, 1024-bit RSA) once per `NetHandlerLoginServer` via `new EncryptionThread`? — no: `NetHandlerLoginServer.processLoginStart`:
   - Generates `SecretKey` via `CryptManager.generateKeyPair()` (RSA 1024) if null.
   - `SPacketEncryptionRequest(serverId="", publicKey, verifyToken(4 random bytes))`.
2. Client responds `CPacketEncryptionResponse(sharedSecret, verifyToken)` both RSA-encrypted with server public key.
3. Server RSA-decrypts, checks verifyToken match, derives AES key = the client's shared secret.
4. **Cipher init BEFORE sending LoginSuccess**: `manager.enableEncryption(secretKey)` inserts `NettyEncryptingDecoder/Encoder` into pipeline (`addBefore("splitter")` / `addBefore("prepender")`). Cipher = AES/CFB8/NoPadding, IV = key bytes (same key both directions, IV=key).
5. Session-server auth (`hasJoined`) runs on `User Authenticator #N` thread — NEVER blocks server thread; result lands via `loginSuccess`→`READY_TO_ACCEPT` on next `update()`.

IMPORTANT ordering: encryption enabled immediately after receiving EncryptionResponse — meaning the very next clientbound bytes (SPacketEnableCompression, SPacketLoginSuccess) are already AES-encrypted on the wire.

## Compression
- `SPacketEnableCompression(threshold)` sent while still in LOGIN (after encryption, before LoginSuccess).
- Clientbound insertion: `NetworkManager.setCompressionThreshold`:
  - threshold >= 0 → `addBefore("decoder", "decompress", new NettyCompressionDecoder(threshold))` + `addBefore("encoder", "compress", new NettyCompressionEncoder(threshold))`.
  - Client sends its own EnableCompression acknowledgment implicitly — no separate ack packet; client inserts its own handlers when it receives the packet. Server pipeline edit happens when SENDING the packet (write-then-insert ordering handled by packet dispatch order).
- Frame-in-frame format (documented in `packet_framing.md` and `machine/network-pipeline.yaml`):
  ```
  [len][packetId][payload]  becomes  [len][uncompressedSize VarInt][zlib data]
  ```
  - `uncompressedSize == 0` → payload not compressed (below threshold).
  - Decompressed size > 2 MiB → `DecoderException("Bad compressed size")`.
  - UncompressedSize < threshold (but non-zero) → `DecoderException("Badly compressed packet")`.

## LoginSuccess → PLAY transition
1. `SPacketLoginSuccess(UUID string, username string)` clientbound.
2. `NetworkManager.setConnectionState(PLAY)` — called by `NetworkDispatcher` (Forge) or vanilla `NetHandlerLoginServer.tryAcceptPlayer` at send time; PLAY packet IDs apply from this packet onward.
3. Vanilla: `PlayerList.initializeConnectionToPlayer(manager, player)` — creates `EntityPlayerMP`, loads/moves player data, sends JoinGame etc.
4. Forge: **initialization is split**. `NetworkDispatcher` intercepts LoginSuccess outbound — firing the FML handshake FIRST (mod list + registry sync inside PLAY via `FML|HS` CustomPayload), THEN completes `initializeConnectionToPlayer`. See `fml_handshake.md` and `network-state-machine.yaml`.

## Threads
| Stage | Thread |
| --- | --- |
| Handshake/status decode+reply | Netty IO |
| Login packet decode | Netty IO |
| RSA decrypt, cipher insert | Netty IO (in `processEncryptionResponse`) |
| Session-server hasJoined POST | `User Authenticator #N` (transient, named thread) |
| Login state machine pump `update()` | Server thread (via `NetworkSystem.networkTick`) |
| Compression threshold set, LoginSuccess send | Server thread |

## State ownership
- Protocol state: Netty channel attribute — mutated on IO thread (handshake/status) and server thread (login→play boundary).
- Login substate enum: `NetHandlerLoginServer.currentLoginState` — server thread only.
- RSA keypair: per-connection? NO — per `NetHandlerLoginServer` instance, generated lazily (`getKeyPair`), reused across logins within that handler lifetime; shared secret always per-connection.
- Player admission: `PlayerList` (server thread).

## Buffer ownership
- `SPacketEncryptionRequest` copies public key into `PacketBuffer.writeByteArray`.
- Encryption/decryption allocates fresh `ByteBuf` per packet (cipher `update` output) — heap buffers, no pooling for the cipher output in vanilla impl (`NettyEncryptingDecoder` uses `Unpooled.wrappedBuffer`? — uses `manager.decrypt` → `cipher.update(in)`.
- Compression uses JDK `Deflater`/`Inflater` per connection (`new` per encoder/decoder instance), NOT shared — each connection carries its own zlib state (windows ~256 KiB per direction).

## Forge hooks / mod visibility
- `C00Handshake` patch: `\0FML\0` marker appended to hostname field (before port): vanilla clients send host without marker; Forge clients embed it. Server strips marker, sets channel attr `FML_MARKER` — drives later vanilla-vs-Forge branch.
- `NetworkDispatcher` sits before `packet_decoder`: intercepts ALL inbound frames until handshake completes; outbound `sendPacket` interception for FML handshake replies.
- No other mod-visible login hooks beyond the vanilla-mode fallback check (`NetworkRegistry.isVanillaAccepted`).

## Compatibility significance
- Disconnect messages ("Outdated server!...") text and ordering (compression-then-success) are observable.
- 600-tick login timeout (`NetHandlerLoginStatus` error path) observable by slow clients.
- AES/CFB8 + IV=key is a legacy Minecraft quirk that MUST be preserved (any client implements it).
- `SPacketLoginSuccess` UUID sent as STRING (not raw bytes) — protocol 340 quirk.

## Future Rust significance
- RSA decrypt + AES init are pure CPU, off server thread — candidates NET-2 but low frequency (per login).
- LoginSuccess-as-strings and compression-before-success ordering constrain wire codec (NET-3).
- The login substate machine is a small finite machine — trivially portable, but its `update()` pump couples to the server tick; any Rust transport must keep per-tick pump or decouple with explicit handshake thread.

## Evidence
- `third_party_reference/minecraft/src/net/minecraft/server/network/NetHandlerHandshakeTCP.java`
- `third_party_reference/minecraft/src/net/minecraft/server/network/NetHandlerLoginServer.java` L40–310
- `third_party_reference/minecraft/src/net/minecraft/util/CryptManager.java` L110–150
- `third_party_reference/forge/src/patches/minecraft/net/minecraft/network/handshake/client/C00Handshake.java.patch`
- `machine/network-state-machine.yaml`
