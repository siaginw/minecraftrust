# M1 HANDOFF-A Design: GetPrimitiveArrayCritical Direct Encoding

## Problem (Checkpoint Z)

The original M1 native population path (`executeNativePopulation`, HANDOFF-0) copied
the Rust-encoded chunk payload **twice** before it reached the packet's `field_186949_d`
(byte[]) field:

1. Rust → pooled direct ByteBuf (Netty `PooledByteBufAllocator.DEFAULT.directBuffer`)
2. `directBuf.getBytes(0, payload)` → heap `byte[]`

Benchmark: SCOPE-C-N HANDOFF-0 was **slower** than Java on all 24 fixtures
(equal-scope methodology). Root cause: the double copy plus pooled-buffer
alloc/release overhead exceeded Java's single memcpy from precomputed byte[].

## Solution: HANDOFF-A

Eliminate both the intermediate direct buffer and the double copy by writing
Rust's encoded output directly into the final Java heap `byte[]`:

1. `predictOutputLen()` — pure scan of staging buffer, returns exact wire size
2. Java allocates `new byte[predicted]` — exact size, no truncation
3. `encodeSectionsIntoArray()` — Rust JNI export uses `GetPrimitiveArrayCritical`
   to pin the byte[] and writes encoded sections directly into it
4. `ReleasePrimitiveArrayCritical` — unpins; array is ready for
   `finishPacketPopulation` field assignment

### JNI Implementation Details

- Raw JNI vtable access (no `jni` crate dependency)
- JNIEnv* is `*const *const *const c_void` (double deref to reach function table)
- Slot indices verified against `jni.h`: GetArrayLength=171,
  GetPrimitiveArrayCritical=222, ReleasePrimitiveArrayCritical=223
  (4 reserved void* at slots 0–3, then GetVersion at slot 4 = 0x10008)
- Functions receive original `env` pointer (not table), unlike GetVersion
  which ignores its first arg

### GetPrimitiveArrayCritical Safety

Per JNI spec, between Get/Release the JVM may disable GC ("critical region").
Measured behavior on HotSpot 8u504:

| Fixture | Payload | Burst | p50 | p99 | p999 | Young GC | GC ms |
|---------|---------|-------|-----|-----|------|----------|-------|
| det0    | 256B    | 200k  | 0.1µs | 0.1µs | 0.3µs | 0 | 0 |
| det1    | 54.7KB  | 200k  | 1.0µs | 2.0µs | 2.6µs | 9 | 11 |
| det4    | 19.8KB  | 200k  | 0.4µs | 1.2µs | 1.5µs | 2 | 2 |
| det6    | 20.5KB  | 200k  | 0.4µs | 1.3µs | 1.6µs | 2 | 2 |
| det7    | 13.4KB  | 200k  | 0.3µs | 1.1µs | 1.5µs | 1 | 2 |

No measurable GC/safepoint regression observed in the tested workload.
Young GC events were driven by byte[] allocation churn external to the
critical section. Worst-case critical hold time ≈2.6 µs (det1 p999).

### Copy Count

| Metric | HANDOFF-0 | HANDOFF-A |
|--------|-----------|-----------|
| Copies to reach packet byte[] | 2 (Rust→direct, direct→heap) | 1 (Rust→heap via pinned array) |
| Intermediate direct buffer | Yes (pooled, 64KB+) | No |
| Direct memory alloc per packet | Yes | No |
| Heap alloc per packet | Yes (byte[]) | Yes (byte[], exact size) |

## Eligibility Gate

`M1_NATIVE_MIN_WIRE_BYTES = 8192` — packets with predicted wire size below
this threshold are routed to the Java path (vanilla ctor). Validated against
full 24-fixture corpus:

- TP=21 (native wins): all fixtures ≥8192 bytes
- FP=0: no fixture where gate selects native but Java is faster
- TN=3: det0 (319B), det2 (4133B), det3 (4640B) — correctly excluded
- FN=0: no fixture where gate excludes native but native would win

## Benchmark Defect Lessons

1. **Cross-scope comparison invalid**: SCOPE-B/A/C measure different work
   (ctor, serialize, ctor+serialize). Only same-scope comparisons are legal.
2. **Interleaved GC contamination**: running Java and native alternately per
   trial pollutes GC/TLAB state. Fix: separate per-scope passes.
3. **Buffer allocation inside timed region**: `Unpooled.buffer(262144)` costs
   5.6–5.9 µs — harness overhead, not path cost. Fix: reuse buffers.
4. **Insufficient warmup**: 500 trials with fixture-switching causes inline
   cache / branch profile pollution. Fix: ≥2000 trials per fixture with
   dedicated warmup pass (500 iters before timing).
