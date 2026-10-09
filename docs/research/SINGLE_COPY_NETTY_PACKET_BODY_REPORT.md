# Single-Copy Netty Packet Body Report

**Date:** 2026-10-02
**Milestone:** `SINGLE_COPY_NETTY_PACKET_BODY_PROVEN`
**Base Milestone:** `TRUE_DIRECT_PACKET_PATH_CLOSED` (`fd5c459`, live at `b3df84c`)
**Scope:** The admitted `SPacketChunkData` path now builds one COMPLETE,
immutable, pre-compression Netty `ByteBuf` per packet — packet id, header,
payload and tile-entity trailer in a single pooled direct buffer — and writes
it past `NettyPacketEncoder` straight into the existing
`NettyCompressionEncoder` / `NettyVarint21FrameEncoder` chain. One payload
memcpy total. Client-visible on Gate A and Gate C.

---

## 1. Executive Summary

The b3df84c live path serialized a Rust payload through
`PacketBuffer.writeBytes(directBuf)` inside the vanilla `writePacketData`
hook — two payload copies, with `NettyPacketEncoder` still running the packet
through the full `Packet` -> varint framing machinery. This milestone moves
the ENTIRE packet body construction to the server thread at admission time:

```
b3df84c (two-copy, encoder runs):
  NativeChunk -> pooled directBuf          COPY #1
  writePacketData hook:
    PacketBuffer.writeBytes(directBuf)     COPY #2
  NettyPacketEncoder (packet framing)
  NettyCompressionEncoder -> prepender -> socket

NEW (single-copy, encoder bypassed):
  admission (server thread):
    measure (native, no write)
    header write into final pooled direct buffer
    NativeChunk wire cache -> memoryAddress()+writerIndex   COPY #1 (the ONLY one)
    TE trailer; buffer FROZEN
  Netty pipeline (per connection write):
    rustcraft_single_copy handler -> retainedDuplicate() view
    NettyPacketEncoder  BYPASSED (raw ByteBuf is not a Packet; MessageToByteEncoder
                        forwards non-matching messages untouched)
    rustcraft_body_capture (shadow only) -> NettyCompressionEncoder -> prepender -> socket
```

Server-thread emission cost (live classes, 4 honestly-sized chunks, 3x10k
iterations each): single-copy mean **0.46 us (12.3 KB) / 0.81 (30.3) / 1.03
(48.3) / 1.51 (78.4)** vs the b3df84c pooled two-copy path's **0.79 / 1.57 /
2.30 / 3.62 us** (~2.2x) and legacy heap at 1.8/4.1/5.5/8.1 us.

## 2. Live Pipeline Placement (proven in the server log)

Installed from an injected hook at the end of `NetworkManager.channelActive`
(event-loop thread; both notch `gw` and SRG names matched):

```
live pipeline.names() before install:
  [timeout, legacy_query, splitter, decoder, prepender, encoder, packet_handler, TailContext]
live pipeline.names() after install (shadow run):
  [timeout, splitter, decompress, decoder, prepender, compress,
   rustcraft_body_capture, encoder, rustcraft_single_copy,
   fml:packet_handler, packet_handler, TailContext]
```

- `rustcraft_single_copy` sits directly AFTER `encoder`: outbound traversal is
  handler -> encoder -> compress -> prepender, so for admitted packets the
  handler hands a `retainedDuplicate()` view of the body to the pipeline and
  `NettyPacketEncoder` never sees a `Packet`.
- `rustcraft_body_capture` (shadow mode only) sits directly BETWEEN the
  encoder and the compressor and byte-compares the real `NettyPacketEncoder`
  output against the frozen body. The compressor registers late
  (`addBefore("encoder")` at the login transition), so the capture is placed
  and re-positioned lazily from the event loop.
- Compression, framing, encryption and socket stages are untouched; Java
  packets are forwarded with `ctx.write(msg, promise)` unchanged.

## 3. Packet Semantic Freeze

At admission (`SPacketChunkData.<init>` entry hook -> `tryAuthority`):

1. `NativeChunkBridge.findGeneration` (the canonical native registry) provides
   the current generation id — the Java-side `REGISTERED_CHUNKS` record cache
   is DELETED (it could hold a dead generation after unload/reload and was
   the only stale-authority risk in the retained path).
2. Under the per-chunk refresh lock (shared with the async coherency worker):
   synchronous `refreshChunkNow` (freshness), then a full-rate FAITHFUL
   verification (per-cell vanilla read vs the native state).
3. `encodePacketPayloadV2Measure` (new native export) returns the exact wire
   length + emitted mask without writing — required because the wire format
   places the payload-length VarInt BEFORE the payload.
4. `SingleCopyChunkBody.build` writes packetId (looked up from the installed
   `EnumConnectionState.PLAY`/CLIENTBOUND via `func_179246_a` — never
   hardcoded), chunkX/Z, fullChunk, mask VarInt, length VarInt, encodes the
   payload ONCE directly at `memoryAddress()+writerIndex`, appends TE count 0,
   and freezes the buffer. The measure/encode pair is verified; any divergence
   (native state moved between the two) fails closed to Java.
5. The ticket registers against the packet — the one-time transition
   JAVA -> RUST_SINGLE_COPY. Never both.

## 4. Buffer Ownership Lifecycle

States: `CREATED -> COMPLETE -> QUEUED -> COMMITTED -> RELEASED`.

- The body is Netty-refcounted (refCnt 1 while registered).
- Every per-connection write serializes a `retainedDuplicate()` view; the
  accepting downstream `MessageToByteEncoder` (compressor / prepender / head)
  releases that view — pipeline-owned, promise-bound.
- Single-consumer bodies (player movement updates) invalidate via a 100 ms
  promise-bound quiescence check on the event loop: `releasedViaQuiescence`.
- A dropped/closed connection releases through the write promise lifecycle
  (`channelInactive` drops this connection's stakes); the sweep is NOT
  required (verified by test 7 and the disconnect stress branch).
- Multi-consumer bodies (vanilla `PlayerChunkMapEntry.func_187280_d` sends ONE
  changed-chunk packet to EVERY watcher — verified in bytecode) cannot know
  their last consumer; the 30 s defensive sweep is their backstop. The
  8-client broadcast probe (one packet object -> 8 channels) passed clean.
- `buffers_created == buffers_released`, `outstanding == 0` in every gate,
  the offline suite, and the 100k stress.

## 5. Resource Bounds

Hard bounds: **128 outstanding bodies / 32 MB outstanding bytes**, checked
before allocation. Breach -> `DIRECT_BUFFER_PRESSURE_FALLBACK` -> untouched
Java path. High-water marks tracked (single-client campaigns peaked at 1
outstanding / ~40 KB; the bound exists for bursts).

## 6. Guard Rails (no malformed empty-payload packet)

An admitted packet's payload field is the shared EMPTY shell. If the outbound
handler is absent (install failure) `writePacketData` serves the correct
bytes via the b3df84c two-copy emission; if the body is gone the write
PROMISE FAILS — vanilla serialization of the empty shell is unreachable.
Install is fail-open: any pipeline surprise leaves the channel vanilla.

## 7. Proof of Encoder Bypass (instrumented)

`NettyPacketEncoder.encode` entry counter (transformer-injected, per class
name from both runtimes):

| Campaign | committed | vanilla_encoder_bypassed | packet_encoder_invocations |
|---|---|---|---|
| Gate A direct | 120 | 120 | Java packets only |
| Gate C direct | 276 | 276 | 96,508 (Java fallbacks) |

`PacketBuffer.writeBytes(chunkPayload)` executes ZERO times on the admitted
path (counter `packetbuffer_payload_copy_count=0`; the fallback serve path
counts its one copy).

## 8. Shadow Byte-Equality (body vs real encoder output)

| Gate | committed | byte-exact | decode-equivalent | concurrent-mutation | unexplained mismatch |
|---|---|---|---|---|---|
| A (Clean Forge, 2 rounds) | 168 | **168** | 0 | 0 | **0** |
| C (Revelation 219 mods, 4 rounds) | 367 | **339** | 16 | 12 | **0** |

Every non-byte-exact comparison is ARBITRATED, never waved through:
- **decode-equivalent**: `SingleCopyBodyCodec` parses both bodies (vanilla
  1.12.2 contiguous BitArray packing, global/local palettes, strict framing)
  and compares the delivered world state cell-by-cell through each side's own
  palette + light + biomes. Cause: vanilla's hashmap palette never shrinks —
  block churn leaves stale palette entries the native minimal palette omits.
- **concurrent-mutation**: the `ChunkMutationTracker` version moved between
  freeze and capture, or the live faithful re-extraction matches the
  Java-transmitted payload (proving the frozen body was superseded, not
  wrong — off-tracker in-place writes exist on modded servers; this project
  previously proved the same phenomenon as "coal_ore-vs-stone").

## 9. Live Gates (client-visible, REALT L client)

| Gate | Rust single-copy packets | Client checks |
|---|---|---|
| A direct (Clean Forge 2860) | **120** | FML handshake, PLAY, 169+169 chunk packets decoded, KeepAlive, stability, clean disconnect |
| C direct (FTB Revelation 2846, 219 mods) | **276** | same, 3 rounds, 169x3 chunk packets decoded |

No decoder exceptions, no compression errors, no double sends, no leaks
(outstanding 0 at shutdown in every run).

## 10. Multi-Client (1 / 4 / 8)

Parallel headless probes against the direct boundary: **1/1, 4/4, 8/8 PASS**
(169 / 676 / 1352 chunk packets received). The 8-client round exercises the
vanilla broadcast (ONE admitted packet object serialized to EIGHT channels
through per-connection duplicate views) with clean lifecycle accounting.

## 11. Failure-Injection Matrix (offline suite, EmbeddedChannel + real wire classes)

| Injection | Behavior |
|---|---|
| bounds pressure before allocation | no buffer created; `DIRECT_BUFFER_PRESSURE_FALLBACK`; Java path |
| stale generation before measure | refusal (-2/-3), no allocation, Java path |
| native measure/encode divergence | buffer released; Java path |
| downstream (compression) write failure | view released by failing handler; body released via promise; promise fails |
| channel closed before consume | handler releases stake; promise failure; quiescence release |
| admitted but never written (cancel) | defensive sweep releases exactly once |
| double release | CAS-guarded: second invalidate() returns false |
| admitted packet with dead body at writePacketData | guard fails the write (never empty-shell serialization) |
| reflection/pipeline surprises at install | fail-open: vanilla channel; two-copy fallback serves correct bytes |

## 12. 100k Lifecycle Stress

100,000 events, mix 70% committed / 10% cancelled / 5% generation-invalid /
5% pool-pressure / 5% disconnect / 5% injected downstream failure:
`committed=70067 cancelled=9882 stale=4975 pressure=5040 disconnect=5013
downstream=5023 unexpected=0`; `buffers_created == buffers_released`;
`outstanding == 0`; zero double-release, zero double-send.

## 13. Same-Coords Unload/Reload (generation lifecycle)

50 cycles: load A -> packet from generation A -> unload (`findGeneration -> 0`,
stale measure refuses -2/-3) -> reload same (x,z) -> strictly newer generation
B -> old handle STILL refused (-3 against the new entry) -> packet from B
carries zero bytes of A. The native registry is the only authority; the
deleted Java record cache cannot resurrect a dead generation.

## 14. Benchmarks (live classes; 3 runs x 10k iters; steady state = run 2-3)

| Bucket (measured wire) | A legacy heap | B b3df84c pooled | C single-copy LIVE |
|---|---|---|---|
| Small 12.3 KB | 8092/2266/1779 ns | 2088/825/788 ns | **878/460/469 ns** |
| Median 30.3 KB | 3528/3473/5145 ns | 1586/1590/1551 ns | **793/798/869 ns** |
| p95 48.3 KB | 5529/6084/5197 ns | 2328/2301/2300 ns | **1031/1024/1041 ns** |
| Max 78.4 KB | 8823/8100/8179 ns | 5175/3628/3621 ns | **1768/1516/1510 ns** |

(steady-state p50/p95/p99/stddev for C: 12.3 KB 500/500/600/65 ns; 30.3 KB
800/800/900/46 ns; 48.3 KB 1000/1100/1100/51 ns; 78.4 KB 1500/1600/1600/120 ns)

The synthetic 1.16 us prototype figure was NOT a target; the live
architecture lands at 0.46-1.51 us depending on size, materially faster than
b3df84c's 5.8 us-class path and sound. Payload copies: 2 -> 1. Java heap
payload allocation per admitted packet: ~49 KB -> 0.

## 15. Fresh JFR (post-final-implementation, Gate A, identical 2-round workloads)

Whole-process EXCLUSIVE samples (profile setting, sampled-threads-only):

| Category | baseline (authority off) | single-copy |
|---|---|---|
| total execution samples | 289 | 187 |
| compression (exclusive) | 4 (1.4%) | 2 (1.1%) |
| event-loop samples | 2 | 2 |
| event-loop compression | 0 | 0 |
| GC collections | 289 | 305 |
| sampled allocation bytes | 499,931 | 371,026 |

Event-loop and whole-process shares are reported separately and must not be
conflated: at this workload the network stages (packet encode, compression,
frame) are ~1% of whole-process CPU either way, and the single-copy handler's
exclusive share is below sampling resolution (<= 1 sample) — consistent with
a sub-microsecond per-packet cost. The load-bearing measurements for this
milestone are the server-thread benchmarks (section 14) and the eliminated
allocations, not a whole-process JFR delta.

## 16. True MSPT (BenchAgent bytecode instrumentation on `func_71217_p`)

Identical workloads (2 probe rounds, 60 s measurement after 30 s warmup):

| Metric | single-copy | baseline |
|---|---|---|
| compute MSPT mean | **9.407 ms** | 9.569 ms |
| p50 | **4.730** | 4.761 |
| p95 | **8.800** | 9.173 |
| p99 | **50.380** | 61.497 |
| missed 50 ms deadlines | 13 | 19 |
| TPS | 19.69 | 19.91 |

Chunk streaming at 1-2 clients is a small tick share, so MSPT moves little;
the p99/deadline tail improves. GC counts are identical (120 young / ~120
"full" scheduled collections both sides — a VM-config artifact, A/B-valid).
RSS A/B: NOT PERFORMED (no baseline RSS capture tooling on this host);
heap-after is the proxy (364 vs 327 MB, dominated by boot cache, not packet
paths).

## 17. Why Shadow Mismatches Were Chased to Zero

The full-body shadow exposed three REAL issues that the b3df84c payload-level
shadow structurally could not see:
1. **Post-reload light drift** (Gate A, 11/200): registration light is a
   native DEFAULT until the chunk's first full sync, and post-registration
   Java light-engine edits leave no work bits. Fixed by the synchronous
   freshness refresh at admission.
2. **Coherency reflection never loaded on a real server** (loader-visible
   only in the SRG offline harness): `M4Coherency.initReflection` resolved
   runtime classes through the bridge's own loader. Fixed loader-aware.
3. **Sync-worker race** (Gate C, single-cell + light diffs): the async
   worker's unlocked `fullSync` push could interleave with the admission
   encode. Fixed by routing all pushes through the per-chunk refresh lock and
   encoding under it, plus a full-rate faithful per-cell admission
   verification and live-state arbitration for the residual off-tracker
   writes (proven benign, not assumed).

## 18. Governance

`PRODUCTION_AUTHORITY` remains strictly false; all modes default OFF
(`-Drustcraft.singleCopy` / `-Drustcraft.singleCopyShadow`); the experiment
remains bounded by cap + receipt; every failure path falls closed to the
untouched Java constructor.

## 19. Validation Receipt

- `cargo fmt --check` (tree reformat commit), `cargo clippy --workspace
  --release`, `cargo test --workspace --release`: green.
- Offline single-copy suite: 18 tests / 10,000+ assertions GREEN
  (`tools/authority-review/run_single_copy_tests.py`).
- Rust measure-export parity tests (cold/warm/reloaded/stale): green.
- Gate A shadow / Gate C shadow / Gate A direct / Gate C direct: PASS.
- 100k stress, generation reload, failure matrix: PASS.
- Multi-client 1/4/8: PASS.
- Fresh JFR A/B + true-MSPT A/B: captured post-final-implementation.
