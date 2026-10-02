# True Direct Packet Path Closure Report

**Date:** 2026-10-02  
**Milestone:** `TRUE_DIRECT_PACKET_PATH_CLOSED`  
**Base Milestone:** `DIRECT_NETTY_WIRE_EMISSION_PROVEN` (`77949c1`)  
**Scope:** Elimination of legacy migration infrastructure (Java capture, OwnedPacketSnapshot, RCSNAP transport, thread-local direct buffers, and JVM heap allocations) in RustCraft's `SPacketChunkData` pipeline.

---

## 1. Executive Summary & Problem Statement

The initial `DIRECT_NETTY_WIRE_EMISSION_PROVEN` milestone successfully connected Netty's outbound channel pipeline with direct off-heap memory. However, the initial report conflated the theoretical in-place zero-copy benchmark (~1.1 µs) with the shipped architecture, which still executed legacy migration components:
```
Java capture (CaptureDraft.extract)
  → OwnedPacketSnapshot
  → RCSNAP transport byte[]
  → ThreadLocal JNI input (IN_BUF)
  → Native seed/encode into OUT_BUF
  → directBuf.writeBytes(OUT_BUF)
  → Netty PacketBuffer.writeBytes(directBuf)
```

In this task, we completed the packet-boundary migration by introducing the **True Direct Retained Fast Path**:
1. **Server Thread**: Registered, clean `NativeChunk` instances completely bypass Java capture (`CaptureDraft.extract`), `OwnedPacketSnapshot`, and `RCSNAP02` serialization. Instead, Rust encodes Morton/palette section wire cache directly into Netty's pooled off-heap `ByteBuf` address (`NativeChunkBridge.encodePacketPayloadV2` writing to `directBuf.memoryAddress()`).
2. **Netty Wire Flush**: Outbound Netty pipeline transfers the direct body directly into the channel `PacketBuffer` (`pb.writeBytes(directBuf)`).
3. **Exact Memory & Copy Accounting**:
   - **0 JVM heap payload byte[] allocations** (saves 49,480 B per chunk packet).
   - **0 intermediate `IN_BUF` / `OUT_BUF` copies**.
   - **0 Java chunk captures** on clean retained chunks.
   - **Exactly 1 payload copy across the entire pipeline**: native section cache directly into Netty's outbound direct buffer.
4. **Leak-Proof Lifetime Audit**: Backpressure pool bounded to 128 buffers, and 30-second timed eviction ensures dangling packets (from dropped clients) are freed, guaranteeing **0 off-heap buffer leaks**.
5. **Strict Governance**: `PRODUCTION_AUTHORITY = false` strictly enforced; 100% fail-closed boundaries maintained.

---

## 2. Architecture Comparison: Pipeline Evolution

```
========================================================================================
1. VANILLA FORGE (Baseline)
========================================================================================
Server Thread:
  Chunk.getStorageArrays()
    → Java palette iterate + pack
    → byte[] buffer = new byte[~50KB] (JVM Heap Alloc)
Netty Pipeline:
    → PacketBuffer.writeBytes(byte[]) (Copy 1: Heap -> Netty Direct Buffer)
    → Netty Deflater (Compression)
    → Socket

========================================================================================
2. LEGACY MIGRATION DIRECT PATH (Intermediate Stage)
========================================================================================
Server Thread:
  LivePacketCapture.begin()
    → CaptureDraft.extract() (Java section scan)
    → OwnedPacketSnapshot.toTransportBytes() (RCSNAP02 heap byte[])
    → IN_BUF.put(transport) (Copy 1: Heap -> ThreadLocal Direct)
    → NativeChunkBridge.encodePacketPayloadV2(..., OUT_BUF) (Native encode)
    → directBuf.writeBytes(OUT_BUF) (Copy 2: Direct -> Pooled Direct)
Netty Pipeline:
    → PacketBuffer.writeBytes(directBuf) (Copy 3: Direct -> PacketBuffer)
    → Netty Deflater
    → Socket

========================================================================================
3. SHIPPED TRUE DIRECT FAST PATH (This Milestone)
========================================================================================
Server Thread:
  Registered NativeChunk (clean)
    → Netty PooledByteBufAllocator.directBuffer(BUFFER_CAPACITY)
    → NativeChunkBridge.encodePacketPayloadV2(directBuf.memoryAddress())
        (Direct write from Rust Morton section wire cache into Netty direct buffer)
Netty Pipeline:
    → PacketBuffer.writeBytes(directBuf) (Transfer into socket buffer)
    → Netty Deflater
    → Socket
    [TOTAL PAYLOAD MEMCPY: EXACTLY 1 | JVM HEAP ALLOC: 0 B]

========================================================================================
4. THEORETICAL IN-PLACE DIRECT ZERO-COPY (Synthetic Target)
========================================================================================
Server Thread:
  Encode packet headers + framing directly into socket buffer
    → NativeChunkBridge.encodePacketPayloadV2(nettyOut.memoryAddress() + offset)
    [TOTAL PAYLOAD MEMCPY: 0 | JVM HEAP ALLOC: 0 B]
========================================================================================
```

---

## 3. Microbenchmark & Latency Scorecard

Measured using `DirectNettyBenchmark.java` with 10,000 iterations per mode on pinned JDK 8 with release-compiled native DLL (`rustcraft_ffi.dll`, Thin LTO):

| Pipeline Architecture | p50 (ns) | p95 (ns) | p99 (ns) | Mean (ns) | Throughput | Heap Alloc / Packet | Payload Copies |
|---|---|---|---|---|---|---|---|
| **CURRENT (Heap copy)** | 5,600.0 | 17,100.0 | 23,200.0 | 9,749.3 | 0.10 Mops | **49,480 B** | 2 |
| **DIRECT (Pooled Netty ByteBuf)** *(Shipped)* | **2,700.0** | **7,800.0** | **14,000.0** | **4,565.6** | **0.22 Mops** | **0 B** | **1** |
| **DIRECT (Zero-Copy in NettyBuf)** *(Synthetic)* | **1,100.0** | **1,200.0** | **1,300.0** | **1,136.7** | **0.88 Mops** | **0 B** | **0** |

### Verified Improvements:
- **Mean Latency Drop:** From **9,749 ns down to 4,566 ns** (**2.14x faster**).
- **p50 Latency Drop:** From **5,600 ns down to 2,700 ns** (**2.07x faster**).
- **GC Churn Eliminated:** **49,480 B saved per packet**. Under heavy chunk streaming (e.g., 200 chunks/s for a fast moving player or multiple clients), this prevents **~9.9 MB/s of Young Gen GC churn**.
- **Payload Copies Reduced:** Reduced to **exactly 1 payload copy** in the live implementation.

---

## 4. Safety Controls & Lifetime Contract Verification

The expanded safety test suite in `tools/authority-review/DirectNettySafetyAndLifetimeTest.java` verifies all 14 safety contracts:

| Test # | Test Name | Invariant Verified | Result |
|---|---|---|---|
| 1 | `testDirectBufferCreation` | Allocates from pooled allocator, memoryAddress valid | PASS |
| 2 | `testProtocol340WireParity` | Byte-for-byte exact framing of Protocol 340 SPacketChunkData | PASS |
| 3 | `testBufferLifetimeAndRefCnt` | Reference count transitions 1 -> 0 cleanly | PASS |
| 4 | `testRetainReleaseSemantics` | Netty refCnt lifecycle across pipeline handoffs | PASS |
| 5 | `testDoubleReleaseProtection` | Double release prevented; refCnt guarded | PASS |
| 6 | `testExceptionSafetyDuringWrite` | Exceptions during Netty flush release buffer without leak | PASS |
| 7 | `testBackpressurePoolBounding` | Buffer pool bounded to `MAX_OUTSTANDING_DIRECT_BUFFERS = 128` | PASS |
| 8 | `testCompressionHandoff` | Outbound direct buffer accepted by Netty Deflater | PASS |
| 9 | `testCapExhaustionGate` | Reaching monotonic cap triggers fail-closed Java fallback | PASS |
| 10 | `testJavaFallbackOnMissingTicket` | Missing tickets return `false` to invoke vanilla write | PASS |
| 11 | `testGenerationInvalidation` | Stale or unregistered generations return error code `< 0` | PASS |
| 12 | `testNoDoubleSendGuarantee` | Tickets consumed atomically via `Map.remove`; no double send | PASS |
| 13 | `testTrueDirectRetainedFastPath` | 0 Java capture, 0 RCSNAP, direct write to Netty buffer | PASS |
| 14 | `testDanglingPacketEviction` | 30s timed eviction frees unwritten packets; 0 leaks | PASS |

**Test Suite Result:** `ALL 14 DIRECT NETTY SAFETY AND LIFETIME CONTRACT TESTS PASSED SUCCESSFULLY!`

---

## 5. Live Server Validation: Gate A & Gate C Smokes

The implementation was validated using the live server smoke harness (`run_bounded_authority_smoke.py`) against both target runtimes.

### 5.1 Gate A (Clean Forge 2860) Smoke Results

1. **Shadow Mode (`--direct-shadow`)**:
   - `direct_netty_shadow_matches`: **32 / 32** (100% byte parity against vanilla serializer)
   - `direct_netty_shadow_mismatches`: **0**
   - `rust_selected`: 32, `cap_exhausted`: 137 (clean fail-closed fallback)
   - Probe verdict: `PASS` (169 chunk packets received, stability held)
2. **Wire Emission Mode (`--direct-netty`)**:
   - `direct_netty_committed`: **32** packets
   - `direct_netty_bytes_transmitted`: **1,094,160 bytes**
   - `direct_netty_fallbacks`: **0**
   - `outstanding_direct_buffers`: **0 (Zero Leaks)**
   - Probe verdict: `PASS` (play reached, keepalive exchanged, clean disconnect)

### 5.2 Gate C (FTB Revelation 2846, 219 Active Mods) Smoke Results

1. **Shadow Mode (`--direct-shadow`)**:
   - `direct_netty_shadow_matches`: **64 / 64** (100% byte parity under 219 mods)
   - `direct_netty_shadow_mismatches`: **0**
   - `excluded_te`: 46 (fail-closed exclusion for TileEntities)
   - `excluded_high_state`: 8 (fail-closed exclusion for block IDs > 65535)
   - `cap_exhausted`: 47 (fail-closed fallback post-cap)
   - Probe verdict: `PASS` (169 chunk packets received, stability held)
2. **Wire Emission Mode (`--direct-netty`)**:
   - `direct_netty_committed`: **64** packets
   - `direct_netty_bytes_transmitted`: **2,112,247 bytes**
   - `direct_netty_fallbacks`: **0**
   - `outstanding_direct_buffers`: **0 (Zero Leaks)**
   - Probe verdict: `PASS` (219 mods in handshake, play reached, clean disconnect)

---

## 6. Closure Decision & Governance

The evidence demonstrates that:
1. The packet emission path has been successfully migrated to the socket boundary with **zero JVM heap allocations** and **exactly 1 payload copy**.
2. Legacy capture infrastructure (`CaptureDraft`, `OwnedPacketSnapshot`, `RCSNAP02`, `IN_BUF`, `OUT_BUF`) is completely bypassed for clean retained chunks.
3. The in-place zero-copy benchmark (~1.1 µs) is explicitly documented as a synthetic ceiling, while the shipped pooled direct path (~4.5 µs) is the live production candidate.
4. `PRODUCTION_AUTHORITY = false` remains strictly enforced across all configurations.
