# JNI Data-Transfer Microbenchmark Report (v2 Corrected Methodology)

## 1. Methodology & Corrections

In the initial P0-4 benchmark (v1), two methodological flaws were identified:
1. **Timer Granularity Floor:** Individual micro-calls timed with `System.nanoTime()` on Windows produced quantized measurements rounded to the OS timer granularity (~100 ns), resulting in synthetic `0 ns` or `100 ns` readings.
2. **Payload Touch Fidelity:** Pointer dereferences and direct buffer views were timed while only touching 1 byte per 64-byte cache line (`step_by(64)`), which reported an impossible RAM throughput (>130 GB/s).

**Corrections applied in v2:**
- **Batched Sampling:** Inner loops execute batches of 5 to 1,000 iterations per timing sample to ensure measured intervals substantially exceed timer resolution floor (>100 µs), dividing total elapsed nanoseconds by batch size.
- **Full Memory Scan Checksum:** Every mechanism (copy, critical pin, direct buffer, native pointer dereference) scans and checksums **100% of the payload bytes** sequentially.
- **Payload Sizes:** Standardized to 2 KiB, 8 KiB, 32 KiB, 64 KiB, 256 KiB, and 1,024 KiB.

Tested on **Java 8 Temurin HotSpot (`jdk-8.0.504.1-hotspot`)**, Windows 11, x86_64, Intel Core i7-11800H @ 2.30 GHz, Rust native library compiled with `rustc 1.94.0 -O`.

Raw results saved in `benchmarks/ffi/p0-4-v2/jni_microbenchmarks.yaml`.

---

## 2. Baseline Overhead

A baseline empty JNI call (`noopCall()`) was measured across 1,000,000 invocations (1,000 batches $\times$ 1,000 iters):
- **Mean:** **8.46 ns**
- **p50:** **4.30 ns**
- **p95:** **14.20 ns**
- **p99:** **20.60 ns**
- **Min:** **3.80 ns**
- **Max:** **68.40 ns**

This confirms that the bare JNI transition penalty is sub-10 nanoseconds on 64-bit HotSpot.

---

## 3. Data Transfer & Full Memory Scan Results

| Transfer Mechanism | Payload Size | Mean Latency ($\mu\text{s}$) | p50 ($\mu\text{s}$) | p95 ($\mu\text{s}$) | Throughput (MB/s) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **A: byte[] Copy** | 2 KiB | 0.289 µs | 0.240 µs | 0.260 µs | 6,750 MB/s |
| (`GetByteArrayRegion`) | 8 KiB | 0.921 µs | 0.910 µs | 0.937 µs | 8,484 MB/s |
| | 32 KiB | 3.903 µs | 3.898 µs | 3.972 µs | 8,007 MB/s |
| | **64 KiB** | **7.775 µs** | **7.782 µs** | **7.876 µs** | **8,039 MB/s** |
| | 256 KiB | 31.205 µs | 30.960 µs | 32.410 µs | 8,011 MB/s |
| | 1,024 KiB | 126.516 µs | 124.840 µs | 141.340 µs | 7,904 MB/s |
| **B: byte[] Pin** | 2 KiB | 0.269 µs | 0.226 µs | 0.245 µs | 7,253 MB/s |
| (`GetPrimitiveArrayCritical`)| 8 KiB | 0.877 µs | 0.858 µs | 1.057 µs | 8,910 MB/s |
| | 32 KiB | 3.343 µs | 3.338 µs | 3.414 µs | 9,349 MB/s |
| | **64 KiB** | **6.854 µs** | **6.790 µs** | **7.002 µs** | **9,119 MB/s** |
| | 256 KiB | 27.350 µs | 26.980 µs | 27.870 µs | 9,141 MB/s |
| | 1,024 KiB | 108.156 µs | 107.900 µs | 109.340 µs | 9,246 MB/s |
| **C: DirectByteBuffer** | 2 KiB | 0.222 µs | 0.220 µs | 0.241 µs | 8,793 MB/s |
| (`GetDirectBufferAddress`) | 8 KiB | 0.856 µs | 0.853 µs | 0.877 µs | 9,126 MB/s |
| | 32 KiB | 3.379 µs | 3.370 µs | 3.460 µs | 9,249 MB/s |
| | **64 KiB** | **9.476 µs** | **10.356 µs**| **10.960 µs**| **6,595 MB/s** |
| | 256 KiB | 26.958 µs | 26.700 µs | 27.220 µs | 9,274 MB/s |
| | 1,024 KiB | 107.900 µs | 107.920 µs | 108.760 µs | 9,268 MB/s |
| **D: Rust Buffer Wrap** | 2 KiB | 0.145 µs | 0.126 µs | 0.213 µs | N/A (Alloc only) |
| (`NewDirectByteBuffer`) | 8 KiB | 0.128 µs | 0.122 µs | 0.181 µs | N/A (Alloc only) |
| | 32 KiB | 0.117 µs | 0.116 µs | 0.128 µs | N/A (Alloc only) |
| | **64 KiB** | **0.118 µs** | **0.116 µs** | **0.148 µs** | **N/A (Alloc only)** |
| | 256 KiB | 0.122 µs | 0.110 µs | 0.180 µs | N/A (Alloc only) |
| | 1,024 KiB | 0.123 µs | 0.120 µs | 0.160 µs | N/A (Alloc only) |
| **E: Persistent Handle** | 2 KiB | 0.262 µs | 0.216 µs | 0.232 µs | 7,454 MB/s |
| (`jlong ptr` direct read) | 8 KiB | 0.863 µs | 0.848 µs | 1.052 µs | 9,055 MB/s |
| | 32 KiB | 3.431 µs | 3.394 µs | 3.550 µs | 9,107 MB/s |
| | **64 KiB** | **6.777 µs** | **6.752 µs** | **6.832 µs** | **9,222 MB/s** |
| | 256 KiB | 27.142 µs | 27.090 µs | 27.520 µs | 9,211 MB/s |
| | 1,024 KiB | 108.798 µs | 107.520 µs | 120.580 µs | 9,191 MB/s |

---

## 4. Architectural Analysis

1. **Measured Sequential Memory-Scan Ceiling:**
   - Single-threaded sequential scan bandwidth tops out at **$\approx 8.0\text{--}9.3\text{ GB/s}$**, representing measured sequential memory-scan throughput in this harness.
   - For a standard 64 KiB chunk payload, full memory reading consumes **$\approx 6.8\text{--}7.8\ \mu\text{s}$**.
2. **JNI Transition vs Transfer Cost:**
   - JNI call overhead ($8.46\text{ ns}$) represents a hot batched JNI lower-bound observed on this machine/JVM (pure no-op boundary transition). It represents $< 0.13\%$ of the total data access cost for 64 KiB. Actual JNI cost in production depends on argument shapes, reference management, and GC interaction.
   - Mechanism A (`GetByteArrayRegion`) involves an intermediate `memcpy` into native memory before accessing, which costs an additional $\approx 1.0\ \mu\text{s}$ over zero-copy pinned or handle reads at 64 KiB, widening to $18.4\ \mu\text{s}$ at 1 MiB.
3. **GC Hazards:**
   - Mechanism B (`GetPrimitiveArrayCritical`) is fast ($6.85\ \mu\text{s}$) but temporarily disables HotSpot GC while pinned. Long or frequent pins in hot paths cause stop-the-world GC latency spikes.
   - Mechanism D (`NewDirectByteBuffer`) has a constant cost of **$118\text{ ns}$** regardless of payload size. It wraps a pointer to Rust memory without allocating Java heap byte arrays.
4. **Conclusion:**
   - For streaming chunk data between Rust background loaders and the Java server thread, **Mechanism D (`NewDirectByteBuffer`) combined with Mechanism E (persistent handle dereferencing)** is the optimal zero-copy architecture.
