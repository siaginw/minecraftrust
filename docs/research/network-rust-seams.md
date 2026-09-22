# Network Rust Seams — NET-1 .. NET-8 (P0-5 Research)

## Scope
Candidate Rust migration seams for the network stack, evaluated against measured/evidence-based criteria. Status: RESEARCH CANDIDATES ONLY. No ownership migration authorized in P0.

## Definitions
- **SR-cost** = server-thread time removed (TPS-relevant).
- **IO-cost** = Netty IO thread time removed (throughput/CPU).
- **ABI risk** = probability of breaking Forge mods/coremods.

## Seam Catalog

### NET-1 — Rust VarInt/framing helpers
- What: JNI calls for LEB128 encode/decode, length prefix calc.
- SR-cost: ~0. IO-cost: ~2–30 ns/packet (measured framing cost, `packet_framing.md`).
- FFI calls: per-packet (~1–2).
- ABI risk: none (internal helpers).
- Verdict: **REJECTED as standalone** — JNI crossing (~4.8 ns measured) plus call overhead eliminates any gain. Only viable embedded inside NET-3/4/5.

### NET-2 — Rust compression/encryption helpers
- What: zlib + AES-CFB8 via JNI on frame buffers.
- SR-cost: ~0 (already IO thread). IO-cost: moderate (zlib dominates large packets; potential IO-thread optimization via native hardware AES-NI or zlib-ng, but direct Netty transport/framing/compression/encryption Server-thread cost was negligible in the tested workloads because those phases execute on Netty IO threads).
- Key fact: Java `Deflater`/`Cipher` already call native code via JNI. Rust reimplementation removes one JVM boundary but adds another. Net gain ≈ 0 for zlib, negative for small packets.
- ABI risk: low. Verdict: **REJECTED** — no measurable win; JDK crypto already native.

### NET-3 — Rust packet byte codec (Java packet objects remain)
- What: `writePacketData`/`readPacketData` byte serialization in Rust for chosen packets; Java holds packet object, Rust fills/consumes ByteBuf content.
- SR-cost: moderate for packets serialized on server thread (none — most encode on IO thread); SR-cost only via constructor-pre-serialized packets (see NET-8).
- IO-cost: high for hot packets (movement broadcast, entity velocity, ChunkData encode).
- FFI: one coarse call per packet with direct ByteBuffer (O(1) ~15 ns measured).
- ABI risk: medium — must not alter `PacketBuffer` class (coremods); implement as helper producing bytes, Java wrapper writes into PacketBuffer.
- Verdict: **CANDIDATE — deferred pending §16/§18 measurements** (need proof IO-thread encode cost matters for TPS).

### NET-4 — Rust TCP + framing; Java receives framed bytes
- What: Rust owns accept loop + sockets + VarInt framing; hands complete frames to Java via shared ring/batch.
- SR-cost: ~0. IO-cost: high (entire IO loop in Rust).
- ABI risk: HIGH — replaces Netty channel/pipeline mods depend on (survey: 15% of surveyed sample LEVEL-3, coremods patch handlers; `Channel` attributes used by Forge itself).
- Status: **LOW-VALUE / HIGH-COMPATIBILITY-RISK RESEARCH CANDIDATE** — network cost is already off the server thread; replacing transport yields no direct TPS gain while imposing severe compatibility hurdles.

### NET-5 — Rust TCP + framing + compression + encryption
- Superset of NET-4; same ABI risk plus key exchange coupling (RSA secret must reach Rust via controlled handle).
- Status: **LOW-VALUE / HIGH-COMPATIBILITY-RISK RESEARCH CANDIDATE** — identical drawbacks to NET-4 plus complex cryptographic state sharing.

### NET-6 — Rust transport with Netty compatibility/proxy layer
- What: reimplement enough Netty API surface (`Channel`, `ChannelPipeline`, `ByteBuf`, `EventLoop`, `ChannelFuture`) for mods.
- Survey finding: 80% of surveyed sample touch `ByteBuf` methods; 15% deeper (`FMLEmbeddedChannel`, pipeline add). Netty 4.1 is ~300+ classes; faithful `ByteBuf` refcount + pooling semantics is a multi-quarter effort alone.
- Status: **REJECTED** — compatibility surface dwarfs performance benefit; violates "bridge is temporary" invariant (this would become permanent architecture).

### NET-7 — Keep Netty/Forge networking; move expensive packet construction/serialization selectively
- What: Rust ChunkData serializer (and later entity/movement batch builders) producing wire bytes; Java packet object wraps prebuilt buffer, vanilla pipeline unchanged.
- SR-cost: HIGH — targets exactly the server-thread construction cost (ChunkData ctor serializes sections+TE NBT on server thread — measured P0-3: 847 payloads, ~0.5–2 ms per full chunk serialize).
- IO-cost: unchanged (bytes just pass through).
- ABI risk: LOW — `SPacketChunkData` class stays, `writePacketData` writes prebuilt array; coremods unaffected; mods never see internals.
- FFI: coarse batch (one call per chunk, direct ByteBuffer ~15 ns).
- Status: **LEADING RESEARCH CANDIDATE** — addresses direct TPS bottleneck on server thread while maintaining 100% Netty/Forge mod compatibility.

### NET-8 — Native ChunkData Sub-Variants (Detailed Decomposition)
ChunkData construction is not monolithic. To avoid repeating the P0-4 NBT parity pitfall, it is split into four distinct candidates:
- **NET-8A: Native vanilla section packing only**
  - Serializes 16x16x16 block ID / metadata nibbles into network bit-stream format.
  - Zero mod hooks; pure arithmetic over byte/short arrays. Lowest risk.
- **NET-8B: Native packet payload serialization from a coarse Chunk snapshot**
  - Snapshot passes block arrays, biome bytes, and pre-extracted TileEntity NBT blobs in one coarse JNI crossing.
  - Preserves all Java-side TileEntity lifecycle hooks.
- **NET-8C: Native TileEntity-NBT encoding**
  - High risk: `TileEntity.getUpdateTag()` and `TileEntity.getUpdatePacket()` are widely overridden by Forge mods to inject custom network NBT. Native code cannot bypass these Java virtual calls without breaking mod synchronization.
- **NET-8D: Native full ChunkData packet bytes**
  - End-to-end wire packet generated in Rust, bypassing `SPacketChunkData` object creation.
  - Requires reconciling with any coremods intercepting `SPacketChunkData`.
- Status: **NET-8A and NET-8B are LEADING RESEARCH CANDIDATES**. NET-8C is HIGH RISK due to mod dynamic update tags.

## Decision Framework (directive §21/§24)
| Seam | SR-cost removed | IO-cost removed | Java allocs removed | FFI calls/packet | ABI risk | Complexity | Reversible |
| --- | --- | --- | --- | --- | --- | --- | --- |
| NET-1 | 0 | trivial | few | 1–2 | none | low | yes |
| NET-2 | 0 | ~0 | few | 1 | low | low | yes |
| NET-3 | 0 | high | many | 1 | medium | medium | yes |
| NET-4 | 0 | high | many | n/a | **high** | **high** | hard |
| NET-5 | 0 | high | many | n/a | **high** | **high** | hard |
| NET-6 | 0 | high | many | n/a | **extreme** | extreme | no |
| NET-7 | **high** | 0 | many | 1 (coarse) | low | medium | yes |
| NET-8A/B| **high** | 0 | many (per chunk) | 1 (coarse) | low | low | yes |
| NET-8C | **high** | 0 | many | dynamic | **high** | high | yes |

## §25 Explicit Question: Would replacing Java Netty transport improve TPS?
**No.** Evidence:
1. All Netty pipeline work (encode/compress/encrypt/frame/socket) runs on Netty IO threads, not the server thread (ownership matrix).
2. TPS is governed by server-thread tick loop; network appears there only as (a) packet construction (ChunkData etc. — addressed by NET-7/8) and (b) futureTaskQueue drain of inbound handlers (§16).
3. Mod ABI depends on Netty classes (survey: 80% ByteBuf, 15% deeper; coremod hazards doc).
Full transport rewrite = poor early investment. Selective server-thread construction offload = high-value, low-risk.

## §26 Netty classification
**C. STRONG DE FACTO MOD ABI** (bordering A internally): Netty classes are internal to vanilla but exposed through Forge's supported APIs (`ByteBuf` in `IMessage`, `FMLEmbeddedChannel`) and patched by coremods. Replacing requires recreating the API surface, eliminating benefit.

## Evidence
- `docs/compatibility/mod-network-usage-survey.md` (20 mods)
- `docs/compatibility/network-coremod-hazards.md`
- `docs/architecture/reference-network-ownership.md`
- `docs/benchmarks/jni-data-transfer.md` (direct ByteBuffer ~15 ns O(1); noop 4.8 ns)
- P0-3 chunk payload measurements (`docs/research/chunk-rust-seams.md`)
