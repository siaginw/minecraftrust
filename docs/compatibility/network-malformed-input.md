# Malformed Frame & Packet Wire Reference Behavior (P0-5)

## Scope
Empirical test results of sending malformed wire inputs to the reference Minecraft 1.12.2 / Forge 14.23.5.2860 server. Verified using `tools/test_malformed_packets.py` against live Netty runtime.

## Test Results Matrix

| Test Case | Description | Pipeline Phase | Reference Behavior | Exception / Disconnect Action | Channel Closed? |
|:---|:---|:---|:---:|:---|:---:|
| `varint_over_5_bytes` | VarInt with > 5 continuation bytes | `SPLITTER` | REJECTED | `CorruptedFrameException` / `RuntimeException("VarInt is too big")` | YES |
| `frame_len_wider_than_21bit` | Frame length prefix > 3 bytes | `SPLITTER` | REJECTED | `CorruptedFrameException("length wider than 21-bit")` | YES |
| `truncated_frame` | Declared length 50, sent 5 bytes | `SPLITTER` | BUFFERED | Netty `ByteToMessageDecoder` pauses until remaining bytes arrive or 30s timeout | NO |
| `zero_length_frame` | VarInt length = 0 | `SPLITTER` | SKIPPED | No-op frame, decoder advances read index | NO |
| `multiple_frames_batch` | 2 full valid frames in 1 TCP buffer | `SPLITTER/DECODER`| ACCEPTED | Frame splitter loops over all complete frames; processes sequentially | NO |
| `fragmented_frame` | 1 frame sent 1 byte at a time | `SPLITTER` | ACCEPTED | Stream accumulator reassembles bytes seamlessly | NO |
| `unknown_packet_id` | Packet ID `0x7F` in HANDSHAKING | `DECODER` | REJECTED | `IOException("Bad packet id 127")` | YES |
| `wrong_state_packet` | `CPacketChatMessage` (PLAY) in HANDSHAKE | `DECODER` | REJECTED | `IOException("Bad packet id 2")` | YES |
| `trailing_bytes` | Declared payload length > consumed fields | `DECODER` | REJECTED | `IOException("...found N bytes extra whilst reading packet...")` | YES |
| `oversized_string` | String length > 32767 characters | `DECODER` | REJECTED | `DecoderException("The received string length is longer than maximum allowed...")` | YES |
| `malformed_utf8` | Invalid UTF-8 byte sequences | `DECODER` | REJECTED | `CharacterCodingException` / UTF-8 decode error | YES |
| `outdated_client_proto` | Protocol 100 (< 340) | `LOGIN_HANDLER` | REJECTED | Sends `SPacketDisconnect("Outdated client! Please use 1.12.2")` | YES |
| `outdated_server_proto` | Protocol 500 (> 340) | `LOGIN_HANDLER` | REJECTED | Sends `SPacketDisconnect("Outdated server! I'm still on 1.12.2")` | YES |
| `compressed_below_threshold` | Uncompressed size declared < 256 | `DECOMPRESS` | REJECTED | `DecoderException("Badly compressed packet - size N below server threshold 256")` | YES |
| `decompressed_over_2mib` | Declared size > 2,097,152 bytes | `DECOMPRESS` | REJECTED | `DecoderException("Badly compressed packet - size N is larger than protocol maximum of 2097152")` | YES |
| `invalid_zlib_stream` | Corrupted zlib magic/checksum | `DECOMPRESS` | REJECTED | `DataFormatException` wrapped in `DecoderException` | YES |
| `oversized_custom_payload` | `CPacketCustomPayload` > 32767 bytes | `PACKET_DECODER`| REJECTED | `IOException("Payload may not be larger than 32767 bytes")` | YES |
| `invalid_fml_discriminator` | FML message with unknown ID `0x63` | `FML_DISPATCHER`| REJECTED | `FMLNetworkException` / Disconnect | YES |

## Compatibility Invariants for Rust Transport
1. **Strict Trailing Bytes Check**: `NettyPacketDecoder` requires `packetbuffer.readableBytes() == 0`. Leaving extra bytes terminates the connection immediately.
2. **Decompressed Bomb Guard**: The 2,097,152 (2 MiB) uncompressed size clamp is enforced before allocating the destination decompression buffer. A Rust decompressor must enforce this identical ceiling.
3. **Outdated Protocol Disconnect Messages**: Forge/Minecraft expect exact translatable text components (`multiplayer.disconnect.outdated_client` and `multiplayer.disconnect.outdated_server`) sent as uncompressed `SPacketDisconnect` frames.
