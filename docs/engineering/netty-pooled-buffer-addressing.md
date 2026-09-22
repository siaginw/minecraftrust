# Netty Pooled-Buffer Native Addressing Bug (M1.4-R)

Date: 2026-09-18 · Status: FIXED (commit `5097759`) · Severity: CRITICAL (silent cross-buffer corruption)

## BAD vs GOOD

BAD — returns arena/slab base for pooled buffers, not the buffer's own data address:

```java
long addr = buf.internalNioBuffer(0, buf.capacity()).address();
```

GOOD — exact per-buffer native address of the data region (works because pooled
direct ByteBuf backed by unsafe memory exposes its own offset-adjusted address):

```java
if (buf.hasMemoryAddress()) {
    long addr = buf.memoryAddress();
}
// else: NOT unsafe-backed — must fall back to the Java path; no raw pointer exists.
```

Current implementation: `NativeChunkPacket.getNettyBufferAddress(ByteBuf)`.

## Why this corrupts data

`PooledByteBufAllocator` carves many ByteBufs out of shared arena slabs. For a
pooled direct buffer, `internalNioBuffer(index, length)` returns a ByteBuffer
whose `address()` can correspond to the **slab base**, ignoring the buffer's
`offset` within the slab. Handing that address to Rust makes native code write
at the slab base — i.e. into whatever other buffer happens to own the start of
that slab — while the intended buffer stays untouched (observed as all-zero
payloads of correct length).

## Why shadow mode exposed it

`runShadowOnPacket` computes `encodeJavaReference` into one pooled buffer and
the native payload into a second pooled buffer in the same call. Two live
pooled buffers in one arena make the aliasing deterministic: the second buffer
allocates at a non-zero slab offset, its `internalNioBuffer` address still
resolves to slab base = the first buffer's data. The comparison therefore
mismatched every shadow call while each encoder was individually correct.

## Why single-buffer parity tests missed it

`M14RParityHarness` built the vanilla reference via the real constructor +
`func_148840_b` and never held a second pooled 64 KiB buffer while the native
path ran. With only one live pooled buffer of that size class, that buffer
itself owns the slab base, so slab-base == real data address and the wrong
formula returns the right number. The bug only manifests when **two or more**
pooled buffers share a slab — exactly the production configuration.

## Why fresh-JVM probes appeared correct

First pooled allocation in a fresh JVM occupies the slab base. One-buffer
probes in a fresh JVM therefore "passed" and the bug looked intermittent —
an artifact of allocator layout, not of timing.

## How it was reproduced and pinned

Probe chain (all in `tools/chunk-packet-oracle/src/com/rustcraft/oracle/`):

1. `ProbeShadowBytes` — manual staging→Rust bytes correct; shadow path zeros.
2. `ProbeSequence` — vanilla→shadow-fail→ON_EXPERIMENTAL-correct on the same
   chunk: encoder is fine, the shadow comparison harness path is broken.
3. `ProbeSkyFalse` — staging buffer contents correct while output stays zero:
   rules out staging, indicts output addressing.
4. `ProbePooledBuffer` — pooled output works when it is the ONLY live buffer.
5. `ProbeBisect` — decisive address dump:
   `db.memoryAddress() = 0x...536448` (where `getBytes` reads data) vs
   `db.internalNioBuffer(0,cap).address() = 0x...470912` (what Rust was told)
   — the latter equals the OTHER buffer's slab base. Native writes went to the
   wrong address; the intended buffer read back zeros.

## refCnt / lifetime interaction

- The raw `memoryAddress()` is valid only while the ByteBuf is alive
  (`refCnt > 0`) and while its `readerIndex/writerIndex` region is stable.
- Pass the address to native code ONLY within a single synchronous JNI call;
  never store it across calls — Netty may reuse the slab region after release.
- `release()` must happen only after the JNI call returns; on the error path
  the current bridge releases the buffer before executing the Java fallback.
- Double-release and use-after-release are guarded by the existing fallback
  design (release-on-error before fallback, single ownership in bridge).

## Regression test

`tools/chunk-packet-oracle/src/com/rustcraft/oracle/NettyAddressRegressionTest.java`
allocates multiple pooled direct ByteBufs sharing an arena, performs native
writes through the fixed address helper into each, and asserts each buffer
contains exactly its own pattern and all others remain untouched.

Run: `bash tools/run-shadow.sh com.rustcraft.oracle.NettyAddressRegressionTest`

## Blast radius (fixed call sites)

- `tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java` (2 sites)
- `tools/chunk-packet-oracle/src/com/rustcraft/oracle/LiveShadowHarness.java`
- `tools/bench-spacket/src/com/rustcraft/bench/ApplesToApplesBenchmark.java`

Probe classes retain the BAD call deliberately as living documentation of the
defect; they must never be used in production paths.
