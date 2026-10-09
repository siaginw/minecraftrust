# §3 Trace: What the ~72 µs "Java" Figure Actually Measures

Evidence: `machine/raw/M14R-shadow-campaign.txt` (phase 2 decomposition),
produced by `LiveShadowHarness` lines 184–190 with
`M14RParityHarness.serialize()` (lines 271–282).

## Exact measured region

```java
NativeChunkPacket.setRuntimeMode("OFF");           // mode switch, OUTSIDE timer
long j0 = System.nanoTime();
SPacketChunkData refP = new SPacketChunkData(ch, mask);   // real vanilla ctor (OFF mode)
byte[] refBytes = M14RParityHarness.serialize(refP);     // real func_148840_b
long j1 = System.nanoTime();
```

`serialize()` internals:

```java
ByteBuf buf = Unpooled.buffer(262144);   // 256 KiB HEAP buffer allocation
PacketBuffer pb = new PacketBuffer(buf); // wrapper alloc
packet.func_148840_b(pb);                // full vanilla serialization
byte[] out = new byte[buf.readableBytes()]; // SECOND heap allocation
buf.readBytes(out);                      // copy OUT of Netty buffer
buf.release();
```

## Scope classification

The 72 µs figure is **SCOPE-C Java (construction + serialization) PLUS one
extra full-payload heap copy and a 256 KiB heap buffer allocation**. It is NOT
SCOPE-A (payload-only) and NOT pure SCOPE-B. It therefore MUST NOT be compared
against the native SCOPE-A number (8.5–9.19 µs).

Overhead beyond SCOPE-C: 262144-byte heap alloc + write+read of full payload +
byte[] alloc ≈ a few µs on typical hardware; exact split not yet isolated.

## Fixture parameters at measurement time

- Chunk: dummy-world fixture (M14RParityHarness fixture builder), 16 possible
  sections, `Chunk.field_186036_a` sentinel nulls present in sparse cases.
- Sections: mask-dependent (0xFFFF → 16 sections + biomes).
- fullChunk: `filter == 65535` → true for 0xFFFF rows.
- TileEntities: 0 (dummy-world chunk has no TEs) — real servers add TE cost.
- Biomes: present on full-chunk rows (staged/copied).
- Lighting: sky flag derived `true` in harness decomposed phase (NOTE: shadow
  phase uses dummy world provider → sky=false; decomposed table used sky=true).
- `func_148840_b`: INCLUDED (inside timed region).
- Second copy: INCLUDED (`buf.readBytes(out)`).

## Conclusion

Until §4 canonical benchmark isolates SCOPE-B Java (ctor only) and true
SCOPE-C (serialization without the diagnostic copy), the 72 µs number may be
quoted ONLY as "SCOPE-C + diagnostic copy, dummy-world chunk, 0 TEs".
"Java reference M1 cost" claims from it are VOID.
