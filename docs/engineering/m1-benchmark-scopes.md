# M1 Canonical Benchmark Scopes (M1.4-R)

Status: ACTIVE · Directive 2026-09-18 §2 · Supersedes all prior speedup ratios

## Rule

Only measurements taken under IDENTICAL scope may be compared as a speedup
ratio. Cross-scope ratios are VOID. Historical claims (7.25 µs vs 9.19 µs;
"56% faster"; "4.36 µs saved") compared SCOPE-A Java against a partially
defined native scope and are INVALIDATED.

## Named scopes

| Scope | Name | Definition |
|---|---|---|
| SCOPE-A | `JAVA_SECTION_PAYLOAD_ONLY` | Produce the M1 section-payload bytes (primary bit mask + sections + biomes) from an existing `Chunk` + filter. No packet object, no Netty, no framing. |
| SCOPE-B | `JAVA_FULL_SPACKET_CONSTRUCTION` | `new SPacketChunkData(chunk, filter)` complete: size calc, section filtering, BlockStateContainer serialization, lighting, biomes, TileEntity collection, packet field population. Excludes writePacketData/Netty. |
| SCOPE-C | `JAVA_CONSTRUCTION_PLUS_PACKET_SERIALIZATION` | SCOPE-B plus `func_148840_b(PacketBuffer)` into a Netty buffer: adds writerIndex advance, copy into PacketBuffer, varints, framing headers (not wire compression). |

Native counterparts:

| Scope | Name | Definition |
|---|---|---|
| SCOPE-A-N | `NATIVE_SECTION_PAYLOAD_ONLY` | staging → output buffer acquire → JNI → Rust encode → handoff. Produces the same bytes as SCOPE-A. This is `executeNativePopulation` minus packet-field writes. |
| SCOPE-B-N | `NATIVE_FULL_SPACKET_CONSTRUCTION` | SCOPE-A-N plus packet object allocation + native-produced fields written into the `SPacketChunkData` (buffer ref, mask, fullChunk flag, TE list from Java). This is the production replacement claim. |
| SCOPE-C-N | `NATIVE_CONSTRUCTION_PLUS_PACKET_SERIALIZATION` | SCOPE-B-N plus `func_148840_b` — same serialization as SCOPE-C since payload is prebuilt (copy path differs: Netty writeBytes of direct buffer vs streamed writes). |

## Attribute matrix

| Attribute | A (Java) | B (Java) | C (Java) | A-N | B-N | C-N |
|---|---|---|---|---|---|---|
| size calculation | partial (payload) | yes (`func_148837_a` loop) | yes | no | yes | yes |
| packet object allocation | no | yes | yes | no | yes | yes |
| section filtering (mask) | yes | yes | yes | yes (eligibility) | yes | yes |
| BlockStateContainer serialization | yes (`func_186991_a`/`func_186900_a`) | yes | yes | via Rust (staged bits) | via Rust | via Rust |
| lighting (block+sky) | yes | yes | yes | staged flags | staged | staged |
| biomes | yes (full chunk) | yes | yes | staged | staged | staged |
| TileEntity collection | no | yes | yes | no | yes (Java) | yes |
| staging (Java→C layout) | n/a | n/a | n/a | yes | yes | yes |
| output buffer acquisition | heap byte[] | internal | internal | pooled direct | pooled direct | pooled direct |
| JNI transition | n/a | n/a | n/a | yes | yes | yes |
| Rust encode | n/a | n/a | n/a | yes | yes | yes |
| writerIndex advance | no | no | yes | yes (native writes then set) | yes | yes |
| `SPacketChunkData.writePacketData` (`func_148840_b`) | no | no | yes | no | no | yes |
| copy into PacketBuffer | no | no | yes | no | no | yes (writeBytes direct) |
| Netty framing (length prefix etc.) | no | no | no* | no | no | no* |
| compression (zlib) | no | no | no* | no | no | no* |

(*) Framing/compression belong to the network pipeline, downstream of the
packet object for ALL scopes; no M1 scope includes them.

## Measured values (honest, post-fix)

| Scope | Path | p50 µs | p99 µs | Evidence |
|---|---|---|---|---|
| A | Java custom encoder (`encodeJavaReference`) | 6.80 | 13.70 | `machine/raw/M14R-apples-bench.log` |
| A | Java vanilla-ctor-derived payload | ≈ SCOPE-B minus packet/TE | — | derived, not measured |
| A-N | native | 8.50 | 17.30 | `machine/raw/M14R-apples-bench.log` |
| B | vanilla `new SPacketChunkData` | ~66 | — | `machine/raw/M14R-shadow-campaign.txt` (72 µs incl. serialize; see §3 note) |
| B-N | native + packet fields | not yet measured | — | TODO §4 |
| C | vanilla ctor + `func_148840_b` | ~72 | — | `machine/raw/M14R-shadow-campaign.txt` |
| C-N | not yet measured | — | — | TODO §4 |

## Directive for all future benchmarks

Every benchmark result file MUST record `scope:` as one of the names above.
A speedup ratio may only be printed for equal Java/native scope pairs.

## §2 Exact boundary definitions (directive 2026-09-18 22-35)

### JAVA SCOPE-C (authoritative production baseline)
Includes, in order:
1. `new SPacketChunkData(chunk, mask)` — packet constructor entry, effective
   section-mask calculation, Java section payload generation (palette +
   packed longs), lighting arrays, biomes, Java packet-owned payload storage,
   TileEntity NBT list.
2. `func_148840_b(PacketBuffer)` — writePacketData: header ints + payload
   byte[] copy into destination PacketBuffer/ByteBuf.
3. writerIndex changes performed by the writes above.

Stops immediately before: Netty framing (varint length prefix), compression,
encryption, socket IO.

Timing note: the retained copy for §3 byte-equality (`cBuf.readBytes`) is
performed OUTSIDE the timed region and excluded from SCOPE-C.

### NATIVE SCOPE-C-N (authoritative production native path)
Begins at the same semantic input (same Chunk state, same mask) and ends at
the same semantic output (complete packet payload in a destination ByteBuf,
ready for the downstream pipeline). Includes, in order:
1. Runtime-mode dispatch + native eligibility branch.
2. Staging (thread-local direct buffer: header, per-section long[] copy,
   lighting, biomes).
3. Native output buffer acquisition (pooled direct ByteBuf).
4. JNI crossing + Rust parse/encode into output buffer.
5. Native payload integration + Java packet shell work (field writes on the
   SPacketChunkData shell).
6. `func_148840_b(PacketBuffer)` on the native-populated packet (still
   required: header write + payload copy into destination).
7. writerIndex changes.

Common Java work (packet shell alloc, final serialize, writerIndex) is
included in BOTH scopes. Nothing is included in only one side.

Timing note: the verification read `nBuf.readBytes` is OUTSIDE the timed
region and excluded from SCOPE-C-N.

### Terminology (directive §1)
SCOPE-A vs SCOPE-A-N and SCOPE-B vs SCOPE-A-N are COMPONENT comparisons with
different measurement boundaries — decomposition evidence only, never quoted
as "equal-scope" results or production speedups.
