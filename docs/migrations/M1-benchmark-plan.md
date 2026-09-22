# M1 Benchmark Plan: Performance & Regression Verification

## 1. Benchmark Methodology & Empirical Baselines
All performance evaluations follow Core Principle 9: *Performance claims require reproducible before/after benchmarks*.

### Verified Java Reference Baselines (Measured on Java 8 HotSpot)
- **16-Section Full Chunk Payload**: **11.65 µs** (BlockStateContainer 9.64 µs + Lighting 2.01 µs).
- **4-Section Normal Surface Chunk**: **3.50 µs**.
- **Intermediate Heap Allocation**: Up to 150 KB byte array per chunk packet in Java.

---

## 2. Workload & Target Execution Matrix
Each test runs for **1,200 ticks** (60 seconds) following a 600-tick JIT warm-up period.

| Target | Workload | Conditions Measured | Corrected Expected Gain |
| :--- | :--- | :--- | :--- |
| **Target A** (Clean Forge) | Medium Base | 2 Players, 441 Chunks | Baseline verification (zero regression). |
| **Target A** (Clean Forge) | Exploration | 32 chunks/sec worldgen burst | $\ge 0.15\text{ ms}$ compute MSPT reduction. |
| **Target B** (Anchor Mods) | Medium Base | 2 Players, 350 TEs | $\ge 0.03\text{ ms}$ compute MSPT reduction. |
| **Target C** (FTB Revelation)| Large Base | 5 Players, 1,500 TEs | $\ge 0.04\text{ ms}$ steady state compute reduction. |
| **Target C** (FTB Revelation)| Exploration / Login | 100 chunks/tick burst | $\ge 0.35\text{ ms}$ tail latency (p99) reduction. |
| **Target D** (SevTech: Ages) | Stress Base | 10 Players, 4,000 TEs | $\ge 0.06\text{ ms}$ compute MSPT reduction. |

---

## 3. Strict Performance Acceptance Gates

To gain approval for production enablement (`minecraftrust.native_chunk_packet=ON`), the implementation must satisfy all four gates:

### Gate 1: End-to-End Microbenchmark Acceleration
$$\text{Latency}_{\text{total native}} \le 8.5\,\mu\text{s} \quad (\text{Java Reference Section Payload: } 11.65\,\mu\text{s})$$
- Evaluates total native path: $\text{Java Input Prep} + \text{JNI Entry} + \text{Rust Encoding} + \text{Netty Wrap}$.
- Must demonstrate at least a **25% component CPU reduction** over the Java reference section payload path.

### Gate 2: ServerThread Compute Relief
$$\Delta \text{Compute MSPT}_{\text{Target C Steady State}} \ge 0.04\text{ ms}$$
$$\Delta \text{Tail MSPT (p99)}_{\text{Target C Exploration / Login}} \ge 0.35\text{ ms}$$

### Gate 3: Allocation Suppression
$$\Delta \text{Young-Gen Allocation Rate} \ge 15.0\text{ MB/s}$$
- Replaces intermediate `new byte[]` arrays with pooled off-heap Netty direct buffers.

### Gate 4: Zero Regression Rule
- Zero measurable increase in garbage collection pause time.
- Zero decrease in tick rate (TPS).
- Zero increase in lock contention or blocked threads.
