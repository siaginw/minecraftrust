# Protocol 340 Packet Framing & VarInt Semantics (P0-5)

## Scope
Wire layout of packets, VarInt encoding rules, framing lengths, limits, and decoder exceptions in Minecraft 1.12.2 (Protocol 340). Direct source read: `PacketBuffer.java`, `NettyVarint21FrameDecoder.java`, `NettyVarint21FrameEncoder.java`, `NettyPacketDecoder.java`, `NettyPacketEncoder.java`.

## Wire layout
Every raw packet on the wire consists of:
```
+---------------------------+-----------------------+--------------------+
| Length (VarInt, 1-3 bytes)| Packet ID (VarInt)    | Payload bytes      |
+---------------------------+-----------------------+--------------------+
 \_________________________/ \__________________________________________/
     Frame Header                             Frame Payload
```
- **Frame header**: VarInt length of (Packet ID + Payload). 21-bit limit: encoded in at most 3 bytes. Max frame length = $2^{21}-1 = 2,097,151$ bytes (~2 MiB).
- **Packet ID**: VarInt registered per `EnumConnectionState` (HANDSHAKING, STATUS, LOGIN, PLAY) and direction (SERVERBOUND = 1, CLIENTBOUND = 2).
- **Payload**: packet-specific binary fields, serialized via `Packet.writePacketData(PacketBuffer)` and deserialized via `Packet.readPacketData(PacketBuffer)`.

## VarInt specification
VarInt encodes signed 32-bit integers in 1 to 5 bytes using LEB128 with MSB as continuation bit:
```
Byte format: [C][B6][B5][B4][B3][B2][B1][B0]
  C = 1: more bytes follow
  C = 0: terminal byte
Value: 7 bits per byte, little-endian accumulation.
```
- **Max length**: 5 bytes. A 6th byte throws `RuntimeException("VarInt is too big")` (`PacketBuffer.readVarInt`).
- **Framing length limit**: `NettyVarint21FrameDecoder` enforces a tighter limit:
  - Loop reads bytes up to 3.
  - If a 4th byte has continuation bit set, throws `CorruptedFrameException("length wider than 21-bit")`.
  - Frame length must be non-negative.
- **Negative integers**: encoded in full 5 bytes (e.g. `-1` = `0xFF 0xFF 0xFF 0xFF 0x0F`).
- **Canonical encoding**: Minecraft decoders do NOT enforce minimal encoding — e.g. `0x80 0x00` decodes as `0` without error in `readVarInt` (unlike Protobuf which permits non-minimal unless strict; Minecraft allows overlong VarInts up to the 5-byte clamp, but `NettyVarint21FrameDecoder` requires length to fit in 3 bytes).

## VarLong specification
- Encodes 64-bit integer using LEB128.
- Max length: 10 bytes. An 11th byte throws `RuntimeException("VarLong is too big")` (`PacketBuffer.readVarLong`).

## Framing pipeline classes
| Component | Class | Method | Direction | Behavior / Limit |
| --- | --- | --- | --- | --- |
| Length prepender | `NettyVarint21FrameEncoder` | `encode()` | Clientbound (out) | Checks `length > 3 bytes` (i.e. $> 2,097,151$); throws `IllegalArgumentException` |
| Length splitter | `NettyVarint21FrameDecoder` | `decode()` | Serverbound (in) | Buffers bytes; reads VarInt up to 3 bytes; waits for full frame; throws `CorruptedFrameException` if 3 bytes exceeded |
| Packet encoder | `NettyPacketEncoder` | `encode()` | Clientbound (out) | Looks up packet ID from `EnumConnectionState`; writes ID (VarInt) + calls `packet.writePacketData` |
| Packet decoder | `NettyPacketDecoder` | `decode()` | Serverbound (in) | Reads packet ID (VarInt); instantiates packet via `state.getPacket(direction, id)`; calls `packet.readPacketData`; checks `buf.readableBytes() == 0` |

## Trailing unread bytes check
`NettyPacketDecoder.decode` contains a critical strictness check:
```java
if (packetbuffer.readableBytes() > 0) {
    throw new IOException("Packet " + ((EnumConnectionState)channelhandlercontext.channel().attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get()).getId()
        + "/" + i + " (" + packet.getClass().getSimpleName() + ") was larger than I expected, found "
        + packetbuffer.readableBytes() + " bytes extra whilst reading packet " + i);
}
```
Any packet deserializer that fails to consume all declared payload bytes crashes the connection with an `IOException`, closing the TCP socket. Rust packet deserializers MUST consume exact byte counts — neither more nor less.

## Limits & capacities
| Construct | Limit | Enforcement Site | Exception |
| --- | --- | --- | --- |
| VarInt value | $2^{31}-1$ (5 bytes) | `PacketBuffer.readVarInt` | `RuntimeException("VarInt is too big")` |
| VarLong value | $2^{63}-1$ (10 bytes) | `PacketBuffer.readVarLong` | `RuntimeException("VarLong is too big")` |
| Frame length | $2^{21}-1$ (~2 MiB, 3 bytes) | `NettyVarint21FrameDecoder` | `CorruptedFrameException("length wider than 21-bit")` |
| Outbound frame | 2,097,151 B | `NettyVarint21FrameEncoder` | `IllegalArgumentException("unable to fit " + i + " into 3")` |
| String length | UTF-16 code units (param-dependent) | `PacketBuffer.readString` | `DecoderException("The received string length is longer than maximum allowed (" + i + " > " + maxLength + ")")` |
| Default chat string | 256 code units | `PacketBuffer.readString(256)` | `DecoderException` |
| Custom payload (C→S) | 32,767 bytes | `CPacketCustomPayload.readPacketData` | `IOException("Payload may not be larger than 32767 bytes")` |
| Custom payload (S→C) | 1,048,576 bytes | `SPacketCustomPayload.readPacketData` | `IllegalArgumentException("Payload may not be larger than 1048576 bytes")` |
| Compound tag (NBT) | 2,097,152 bytes (2 MiB) | `PacketBuffer.readCompoundTag` | `RuntimeException("NBTTagCompound too large: ...")` |

## Threads & buffer ownership
- Splitter runs on `Netty Server IO #N`. Reads raw TCP `ByteBuf`; retains sliced frame; releases original.
- Decoder runs on `Netty Server IO #N`. Allocates `PacketBuffer(slicedBuf)`; releases `slicedBuf` on exit.
- Prepender/Encoder runs on `Netty Server IO #N` during `flush()`. Allocates outbound `ByteBuf` via Netty allocator.

## Compatibility significance
- Exact byte-for-byte wire compatibility requires LEB128 VarInt, exact 21-bit framing clamp, strict zero-trailing-bytes verification, and parameter-dependent string length checks.
- Any Rust component touching framing must match Netty's `CorruptedFrameException` and `IllegalArgumentException` bounds exactly.

## Future Rust significance
- Framing (split/prepend) and VarInt (encode/decode) are purely algorithmic, stateless operations on contiguous byte slices. They have ZERO Java state dependencies.
- Candidate for Rust seam NET-1 (framing helpers in Rust, called from Java via direct `ByteBuffer` or JNI) OR NET-4 (Rust owns TCP socket + framing, hands complete frame slices to Java).
- Measured framing cost: VarInt decode is ~2–4 ns per call; framing split is ~15–30 ns. Alone, NET-1 cannot save substantial CPU; it only becomes valuable as part of a larger pipeline (NET-4/5).

## Evidence
- `third_party_reference/minecraft/src/net/minecraft/network/PacketBuffer.java` L40–180
- `third_party_reference/minecraft/src/net/minecraft/network/NettyVarint21FrameDecoder.java` L12–42
- `third_party_reference/minecraft/src/net/minecraft/network/NettyVarint21FrameEncoder.java` L10–28
- `third_party_reference/minecraft/src/net/minecraft/network/NettyPacketDecoder.java` L18–50
- `third_party_reference/minecraft/src/net/minecraft/network/NettyPacketEncoder.java` L15–50
- Golden corpus verification: `benchmarks/protocol/p0-5/golden_packets.txt`
