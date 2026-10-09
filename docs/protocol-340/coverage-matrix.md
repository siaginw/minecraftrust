# 1.12.2 RUSTCRAFT COVERAGE + TEST/PARITY MATRIX

Current repository state audit (2026-09-21). Covers what Rust implements today
networking-wise, plus the test/parity oracle map. **Do not treat "IMPLEMENTED"
here as complete protocol ownership — scope is documented per row.**

## Current Rustcraft networking coverage

| Component | Status | Where | Notes |
|---|---|---|---|
| VarInt writer | PARTIAL | `crates/chunk-packet/src/lib.rs` (`write_varint`,`varint_len`) | write-only, crate-private; no public read path |
| VarLong / String / UUID / Position / ItemStack / array / enum wire codecs | MISSING | — | see `datatypes-oracle.md`; NBT-only string variant in `crates/nbt` |
| NBT binary codec + MUTF-8 | IMPLEMENTED | `crates/nbt` (codec, tape, mutf8) | stream format; missing PacketBuffer `0x00`/2MiB wrapper |
| SPacketChunkData section payload encode | IMPLEMENTED | `crates/chunk-packet` | palette width/palette/global, bitarray words, light, biomes; M1.2 shipped |
| SPacketChunkData full packet header / decode | MISSING | — | header int x/z, full flag, mask, teCount TE-NBT decode all Java-side |
| zlib compression (M2-C) | IMPLEMENTED | `crates/compression` (flate2 zlib-rs, level 6, per-context) | vanilla threshold/framing stays Java |
| Framing (VarInt21 length prefix, decode/encode) | REFERENCE_ONLY | `crates/transport` (`PacketFraming` trait, `ConnectionState` enum) | trait only, no impl |
| Buffer primitive codecs (BE/LE, cursor) | MISSING | `crates/buffers` (bare NativeBuffer only) | no read/write primitives |
| Packet registry (state→id→class) | REFERENCE_ONLY | `crates/contracts` (`PacketBatch` shape) | real registry is the yaml (`machine/protocol-340-packets.yaml`) + docs |
| FML handshake / client | MISSING (Rust) | Python bots: `tools/targeta_client.py`, `tools/protocol-live-probe.py`, `tools/test_malformed_packets.py` | live wire oracle exists in Python, not Rust |
| Encryption (RSA/AES-CFB8) | MISSING | — | — |
| Java↔Rust interop | IMPLEMENTED | `crates/ffi` | only chunk encode + compression JNI surface |

## Reusable components (for future protocol core)

- `write_varint` / `varint_len` (lift out of `chunk-packet` into a shared codec crate).
- `crates/nbt` `NbtCursor` (zero-alloc read path), `mutf8.rs` (exact Java MUTF-8).
- `crates/compression` `ZlibPacketCompressor` + `CompressionCtx_*` JNI.
- `crates/core-types` `BlockPos::to_long/from_long` (correct 26/26/12 pack).
- Python wire harnesses (`tools/*.py`) as the reference read-side clients.

## Test / parity coverage matrix

`O`=Java oracle, `R`=Rust impl, `F`=offline fixture, `Fz`=fuzz, `L`=live test,
`P`=modpack test. N/A = not applicable / not present.

| Protocol component | O | R | F | Fz | L | P | Evidence |
|---|---|---|---|---|---|---|---|
| VarInt write | Java PacketBuffer | chunk-packet (private) | YES (golden in chunk-packet tests) | NO | YES (live probe framing) | N/A | crates/chunk-packet tests; tools/protocol-live-probe.py |
| VarInt read / VarLong / String / UUID / Position / ItemStack / arrays / enum | Java PacketBuffer | MISSING | NO | NO | partial (Python) | N/A | — |
| NBT | Java CompressedStreamTools | crates/nbt | YES | YES (1000-payload) | YES | YES | tests/test_nbt_oracle_differential.py |
| SPacketChunkData payload | Java serializer | crates/chunk-packet | YES | partially | YES | YES | tools/chunk-packet-oracle; M1.x machine/*.yaml |
| SPacketChunkData header/decode | Java | MISSING | NO | NO | NO | NO | — |
| Compression framing (M2-C) | Java Deflater | crates/compression | YES | YES | YES | YES | tools/compression-interop; M2C*.yaml |
| VarInt21 framing | Java NettyVarint21* | REFERENCE_ONLY | NO | NO | YES (Python) | N/A | network-pipeline.yaml runtime capture |
| FML|HS handshake | Forge NetworkDispatcher | MISSING (Python oracle) | NO | partial | YES | fml-handshake.yaml; targeta_client.py; fml-hs-capture.txt |
| SimpleNetworkWrapper messages | Forge SimpleNetworkWrapper | MISSING | — | — | — | — | docs/learned/simple_network_wrapper.md |
| Encryption | Java NettyEncrypting* | MISSING | NO | NO | NO | NO | — |

Legend interpretation: N/A rows (e.g. "live" for pure offline primitives) mean
the test column does not apply at that layer.

## What this means

The write/encode path for the two shipped components (chunk payload, compression)
is the strongest, battle-tested against real modpacks. The **read/decode path is
entirely greenfield in Rust**: no VarInt read, no framing impl, no packet decode,
no encryption. The Python bots are the de-facto wire oracle for read-side work.
