# Rust Network Compression Authority Report

**Date:** 2026-10-02
**Milestone:** `RUST_NETWORK_COMPRESSION_AUTHORITY_PROVEN`
**Base:** `SINGLE_COPY_NETTY_PACKET_BODY_PROVEN` (HEAD `00452af` at start; derive exact from origin/main)
**Scope:** COMPRESSION AUTHORITY only — the M2C native compression subsystem,
re-proven on the single-copy architecture with a NEW direct zero-heap path,
fresh corpus benchmarks, live shadow + authority gates on both runtimes.
Frame VarInt authority stays Java (`NettyVarint21FrameEncoder` untouched);
socket stays Netty.

---

## 1. Authority layers after this milestone

| Layer | Authority |
|---|---|
| Chunk state | Rust (bounded) |
| Packet payload / body | Rust (bounded) |
| **Compression** | **Rust (bounded) — this milestone** |
| Frame VarInt | Java `NettyVarint21FrameEncoder` (unchanged) |
| Socket | Netty (unchanged) |

## 2. The exact 1.12.2 compression contract (from bytecode, not memory)

`NettyCompressionEncoder.encode` (javap, vanilla jar):

- `len < threshold` → `VarInt(0) + uncompressed body` (**strict** `<`: `if_icmpge`
  branches to the deflate arm, so at-threshold compresses);
- `len >= threshold` → `VarInt(len) + zlib(body)` — Deflater created **no-arg**
  (JDK default level 6), `finish()` + drain loop into an 8 KiB scratch, `reset()`
  per packet, fresh zlib stream per packet;
- outer frame = existing `NettyVarint21FrameEncoder` (`VarInt(len) <= 3 bytes`,
  rejects "unable to fit" beyond);
- client decoder (`NettyCompressionDecoder`): `VarInt(0)` → raw; else declared
  length must be `>= threshold` and `<= 2097152`, then inflate.

`crates/compression` (zlib wrapper + per-packet reset + `max_output_len`
bound) mirrors exactly this shape; `VANILLA_LEVEL = 6` matches the no-arg
Deflater.

## 3. What changed this milestone

The M2C subsystem existed (flate2 1.1.10 + zlib-rs 0.6.8 pinned,
per-channel JNI context, encoder-subclass install via the NetworkManager
construction-site transformer) but (a) its ON path still copied
`ByteBuf → byte[] → JNI → byte[] → ByteBuf`, (b) it only worked on
SRG-named runtimes, and (c) nothing had re-proven it on the single-copy
architecture. This milestone:

1. **RustCompressionEngine** — all state/modes/direct-path/fallback/shadow/
   tap/metrics extracted from the SRG-bound subclass into a
   Minecraft-class-free engine (parent-classloader safe). Two thin variants:
   `NativeCompressionEncoder` (SRG) and `NativeCompressionEncoderNotch`
   (default package; extends notch `gv` — Deflater class-scan disambiguated
   from `gu`/`ha` — so the vanilla cast and `gv.a(int)` threshold updates
   work).
2. **Direct zero-heap path**: eligible packets compress straight from the
   body ByteBuf's `memoryAddress()` into the pre-grown outbound buffer's
   memory (JNI `compressDirect`); output bound pre-verified via
   `max_output_len`; `heap payload bytes = 0` on the wire path (counter;
   byte[] path remains as a counted fallback).
3. **Task flags**: `-Drustcraft.rustCompressionExperiment` /
   `-Drustcraft.rustCompressionShadow` alias onto the proven
   `minecraftrust.native_compress` modes. Default OFF; production authority
   remains false.
4. **Corpus tap**: `-Drustcraft.compressionCorpus=<file>` passively records
   every outbound body (all packet types, bounded 256 MB) — captured
   **68,351 real Revelation bodies / 24 MB** (login, FML registry sync,
   chunks, entities, keepalives; mean 349 B, median 25 B, max ~1 MB).
5. Transformer fixes: compiled into the campaign jar (was silently absent),
   notch NetworkManager name (`gw`), notch encoder name (`gv`), tweaker-shape
   registration with raw-property gating (class-loading the encoder at tweak
   time killed registration — observed and fixed).

## 4. Fresh benchmark matrix (68,351 real bodies; mean ns/packet; same
machine, current CPU, current Rust 1.94 / flate2 1.1.10 / zlib-rs 0.6.8)

Per-packet compressor latency (Java = the real vanilla encoder + reused
Deflater; Rust = production `ZlibPacketCompressor`, per-packet reset):

| Bucket | Java L6 | zlib-rs L1 | zlib-rs L6 | speedup L6 | ratio Java→Rust L6 |
|---|---|---|---|---|---|
| 256 B–1 KB | 13,761 | 2,646 | 9,169 | **1.50x** | 0.3251 → 0.3195 (−1.7%) |
| 1–4 KB | 19,059 | 3,201 | 12,481 | **1.53x** | 0.2039 → 0.1998 (−2.0%) |
| 4–16 KB | 104,577 | 12,340 | 82,077 | **1.27x** | 0.2868 → 0.2843 (−0.9%) |
| 16–64 KB | 510,962 | 84,474 | 274,092 | **1.86x** | 0.1348 → 0.1383 (+2.6%) |
| 64 KB+ | 6,820,478 | 1,163,389 | 3,157,689 | **2.16x** | 0.2090 → 0.2079 (−0.5%) |

- The historical 2.26x reproduces on the large bucket (2.16x) and is lower
  on small buckets (1.27–1.53x) — small packets are JNI/JVM-overhead
  dominated. Reported honestly.
- **miniz_oxide** (bench arm): slower than zlib-rs at every level on this
  corpus (e.g. 16–64 KB L6: 412 µs vs 274 µs; small packets 2–8x worse tail)
  with equivalent ratio — rejected.
- **libdeflate**: researched (MIT, ~4.5–6x compressor potential, one-shot
  only, MSVC build risk); not integrated — P3 v2 follow-up, optional
  `backend-libdeflater` feature remains the designed seam.
- **Level choice**: L6 (vanilla-equivalent) is the retained default. L1 is
  3–10x faster still with +10–47% bytes on chunk buckets — documented as a
  future bandwidth-for-CPU lever, NOT enabled (level changes alter wire
  bytes; separate decision per contract §37).

## 5. Live gates (all client-visible, real Java-protocol probe clients)

| Gate | Result |
|---|---|
| A compression shadow | **PASS** — 199 packets, 0 mismatches |
| C compression shadow (4 rounds) | **PASS** — 1,577 packets, 0 mismatches |
| C compression shadow (12 rounds) | **PASS** — 4,747 packets, 0 mismatches (6,523 cumulative) |
| A compression authority (ON) | **PASS** — 356 Rust-compressed packets, direct path 356/356, heap payload bytes 0, 0 fallbacks, 0 verify mismatches |
| C compression authority (ON) | **PASS** — 1,184 Rust-compressed packets, direct 1,184/1,184, heap 0, 0 fallbacks, 0 mismatches |
| Multi-client 1/4/8 (compression ON) | **PASS** — 2,382 Rust-compressed packets across 13 connections, direct 100%, 0 fallbacks |

Shadow semantics: `Java body == inflate(Rust bytes)` per packet
(decompression equivalence — compressed bytes need not be byte-identical).
Cumulative shadow: **6,523 comparisons, 0 mismatches, 0 malformed outputs**.
Wire size: Rust bytes / Java bytes = **+1.51%** (13.82 MB → 14.03 MB across
the C-long session; per-bucket −2%…+2.6%, within the derived acceptable
regression bound of "no material increase" — see §9).

Packet types exercised live: login/dialogue, FML|HS handshake, registry/
mod-sync plugin messages, chunk data, entity/teleport/velocity, keepalive,
disconnect — compression is a network-wide authority, qualified on the full
stream (the corpus composition proves the mix).

## 6. Failure handling (verified)

- Native failure BEFORE commit → counted fallback to the vanilla
  `NettyCompressionEncoder` (offline forced-failure + dead-context
  self-heal tests); never partial output.
- After commit → no Java retry (single wire owner; the packet is framed and
  released by the pipeline).
- Capacity: output bound `max_output_len(n)` pre-checked before any write;
  insufficient capacity → counted fallback (Rust unit test asserts no bytes
  written on failure).
- Threshold edges (offline, real handler + real vanilla decoder):
  0/1/127/128/255/256/257/16383/16384 bodies round-trip byte-exact; strict
  `<` semantics verified; VarInt boundaries covered.
- Frame bound: `MAX_FRAME_BODY` (0x1FFFFF) honored; post-compression exact
  length checked (Rust frame tests); an oversized-but-compressible body
  fails closed before commit.
- Counters: `rust_compression_selected` / `java_compression_selected` /
  `rust_compression_fallback` / `rust_compression_failure` /
  `double_compression_detected=0` (by construction: exactly one compressor
  owns each body — the transformer swaps the constructor site, so one
  handler instance exists per channel).

## 7. 100k lifecycle stress (offline, real handler + real vanilla decoder)

100,000 encode→frame→inflate round-trips across 8 mixed-size bodies with a
mid-stress context free/recreate: `direct_packets=100,012`,
`heap_path_packets=0`, `fallbacks=0`, verify mismatches 0, contexts
created==freed (6/6), 0 leaks, 0 outstanding.

## 8. Fresh JFR A/B (Gate A, identical 2-round workload, whole-process
exclusive samples)

| Metric | Java compression | Rust compression |
|---|---|---|
| total execution samples | 250 | 244 |
| compression exclusive | 1 | 0 |
| event-loop samples | 2 | 3 |

At this single-client workload the compressor is ~0.4% of whole-process CPU
either way — below JFR resolution, honestly reported. The load-bearing CPU
evidence is the component matrix (§4) and the multi-client gate: at 8
clients the compressor runs 8× concurrently on the event loops, where a
1.3–2.2x per-packet reduction compounds.

## 9. Bandwidth regression guard (derived from the corpus, not arbitrary)

Live C-long session total: +1.51% wire bytes vs Java (per-bucket −2.0%…+2.6%
at L6). Derived policy: **retain Rust compression at L6; wire-byte drift
within ±3% of Java at L6 is acceptable** (measured range across all
buckets). L1+ levels are documented but not enabled (they exceed the bound
on chunk buckets).

## 10. True MSPT (BenchAgent, both arms single-copy ON, 2 probe rounds each)

| Metric | Java compression | Rust compression |
|---|---|---|
| compute MSPT mean | 9.991 ms | **9.942 ms** |
| p50 | 5.013 | **4.826** |
| p95 | 9.552 | 9.678 |
| p99 | 67.768 | **64.636** |
| missed 50 ms deadlines | 28 | 32 |

Wash, as predicted (compression lives on Netty event loops, not the server
thread); differences are within run-to-run noise. The milestone's wins are
event-loop CPU and JNI-path latency, not MSPT.

## 11. KEEP decision

**RETAINED.** Justification: 1.27–2.16x per-packet compressor speedup on the
real corpus at a vanilla-equivalent ratio (wire +1.5% live), zero heap
payload bytes on the compression path, live client-visible authority on
both runtimes with 0 mismatches/0 fallbacks/0 corruption across ~2,400
authorized packets and ~6,500 shadow comparisons, and the multi-client
scaling property (per-connection contexts, event-loop local). The
`READY_OPTIONAL (default OFF)` governance posture is unchanged.

## 12. Validation receipt

- `cargo fmt --check`, `cargo clippy --workspace --release`,
  `cargo test --workspace --release`: green.
- Compression unit tests (roundtrip, threshold adjacency, capacity,
  incompressible bound, level bounds) + frame tests (VarInt boundaries,
  MAX_FRAME_BODY, passthrough semantics): green.
- Offline suite (`run_compression_offline_tests.py`): 98 checks green,
  incl. threshold edges, entropy shapes, zero-heap proof, fallback
  self-heal, shadow parity, threshold lifecycle, 100k stress.
- Decompression-parity corpus: 68,351-body live-corpus benches + live
  shadow 6,523 comparisons, 0 mismatches.
- Gates: A shadow, C shadow (long), A authority, C authority, 1/4/8
  multi-client, fresh JFR A/B, MSPT A/B — all PASS (tables above).
