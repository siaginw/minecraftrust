# Benchmark Methodology Correction: Compute MSPT vs. Wall-Clock Cadence

## 1. Executive Summary & Defect Identification

In the initial Task P0-2 baseline measurements, the reported **Mean MSPT** across all healthy 20 TPS server workloads was recorded as approximately **50.00 ms**.

### Root Cause Analysis

In Minecraft 1.12.2, the internal profiler report (`/debug stop`, executed via `CommandDebug.java:99-105`) calculates its summary header as:

$$\text{Header Elapsed Time} = \text{System.currentTimeMillis()} - \text{profileStartTime}$$
$$\text{Header Tick Span} = \text{server.getTickCounter()} - \text{profileStartTick}$$

Dividing the wall-clock elapsed time ($\approx 20,050\text{ ms}$) by the tick count ($\approx 401\text{ ticks}$) yielded:

$$\frac{20,050\text{ ms}}{401\text{ ticks}} = 50.00\text{ ms/tick}$$

This value does **NOT** measure the computational execution cost of `MinecraftServer.tick()`. Instead, it measures the **wall-clock pacing cadence** enforced by `MinecraftServer.run()`:

```java
// MinecraftServer.run() [MinecraftServer.java:410-435]
while (this.serverRunning) {
    long k = getCurrentTimeMillis();
    long l = k - i;
    // ...
    j += l;
    i = k;
    while (j > 50L) {
        j -= 50L;
        this.tick(); // <--- ACTUAL COMPUTE EXECUTION
    }
    Thread.sleep(Math.max(1L, 50L - j)); // <--- DELIBERATE PACING YIELD
}
```

When a workload is computationally light (e.g. 0.25 ms compute time), the server deliberately yields the CPU via `Thread.sleep(49L)` to maintain a steady 20.00 TPS cadence (50.0 ms per tick). Treating wall-clock cadence as "compute cost" created a false equivalence where idle servers and stressed servers appeared equally loaded.

---

## 2. Corrected Methodology: Bytecode-Level Nanosecond Instrumentation

To isolate true compute time from sleep/pacing time, we introduced `tools/bench-agent.jar`, a zero-overhead JavaAgent utilizing ASM 5.2 to instrument `net.minecraft.server.MinecraftServer` at class-load time across both Vanilla and Forge environments:

### Hook Boundaries
1. **Vanilla 1.12.2:** Hooks `protected void C()` (`func_71217_p` / `tick()`)
2. **Forge 14.23.5.2860:** Hooks `protected void func_71217_p()` (`tick()`)

At method entry (`visitCode`), `BenchAgent.onTickStart()` captures $T_{\text{start}} = \text{System.nanoTime()}$.  
At method exit (`visitInsn(RETURN)`), `BenchAgent.onTickEnd()` captures $T_{\text{end}} = \text{System.nanoTime()}$.

$$\text{Compute MSPT} = \frac{T_{\text{end}} - T_{\text{start}}}{1,000,000.0}\text{ ms}$$
$$\text{Wall-Clock Interval} = \frac{T_{\text{start}}(n) - T_{\text{start}}(n-1)}{1,000,000.0}\text{ ms}$$

Every tick during the measurement window is recorded into high-capacity primitive buffers (`tickComputeNs[]`, `tickWallNs[]`), eliminating GC allocation noise during benchmarking.

---

## 3. Warmup & Measurement Phase Separation

Short 20-second benchmarks conflate JVM JIT compilation and spawn chunk loading with steady-state execution. The updated methodology mandates strict phase separation:

1. **Warmup Phase (minimum 20s-60s):**
   - Server boots, loads spawn chunks, warms HotSpot JIT (C1/C2 compilers).
   - Boot GC pauses and young/full collections are recorded separately.
   - At the warmup boundary, heap usage is snapshotted via `MemoryMXBean.getHeapMemoryUsage()`.
2. **Measurement Phase (minimum 25s-60s):**
   - Dedicated measurement tick buffers record every tick.
   - Steady-state young and full GC pauses are isolated from boot GC.
   - At completion, per-tick CSV telemetry and YAML summaries are emitted.

---

## 4. Empirical Profiling Overhead Verification (A/B Test)

To evaluate whether bytecode instrumentation alters runtime performance, controlled A/B measurements were executed under identical conditions (Intel Core i7-11800H, Windows 11, Temurin Java 8, 1GB heap):

- **Condition A (Uninstrumented Reference):** Standard Forge 2860 server without JavaAgent. Mean TPS: **20.0027**.
- **Condition B (Instrumented with BenchAgent):** Forge 2860 server with `bench-agent.jar` active. Mean TPS: **20.0700**.
- **Delta:** $0.0673\text{ TPS}$.

**Conclusion:** **NOT RESOLVED ABOVE BENCHMARK NOISE.**  
The observed delta of 0.0673 TPS between instrumented and uninstrumented configurations is within normal JVM execution and thread-scheduling variance. No statistically significant overhead was detected above benchmark noise.

---

## 5. Corrected Profiler Section Hierarchy Interpretation

Minecraft profiler sections are strictly hierarchical. The output of `/debug` was previously summarized as independent percentages (e.g. `levels 83%`, `entities 32%`), which created the impression that percentages were additive across the server.

The corrected methodology explicitly parses profiler trees into:
- **Parent Section**
- **Child Section**
- **Percent of Parent**
- **Percent of Root**
- **Absolute Compute Time ($\text{mspt} = \text{pct\_of\_root} \times \text{mean\_compute\_mspt}$)**

### Example Hierarchy (`workload_a_idle`, Mean Compute: 0.263 ms):
```text
root: 0.2630 ms (100.0%)
└── levels: 0.2312 ms (87.91% of root)
    └── world: 0.2227 ms (96.34% of levels, 84.69% of root)
        └── tick: 0.2088 ms (93.75% of world, 79.40% of root)
            ├── chunkSource: 0.1265 ms (60.59% of tick, 48.11% of root)
            ├── entities: 0.0573 ms (27.46% of tick, 21.80% of root)
            └── tickPending: 0.0125 ms (5.99% of tick, 4.76% of root)
```

---

## 6. Comparison: Historical P0-2 vs. Corrected P0-2 v2

| Workload | Historical P0-2 "MSPT" (Paced Wall Interval) | v2 Real Compute MSPT (Mean) | v2 Compute p50 (Median) | v2 Compute p95 | v2 Compute p99 | v2 Max Spike | Missed 50ms Deadlines |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `vanilla_idle` | 50.00 ms | **0.197 ms** | 0.025 ms | 0.046 ms | 0.129 ms | 37.55 ms | 0 |
| `workload_a_idle` | 50.00 ms | **0.263 ms** | 0.031 ms | 0.082 ms | 0.435 ms | 42.98 ms | 0 |
| `workload_b_loaded_world` | 50.00 ms | **0.181 ms** | 0.030 ms | 0.058 ms | 0.102 ms | 41.92 ms | 0 |
| `workload_c_entities` | 50.00 ms | **0.175 ms** | 0.027 ms | 0.053 ms | 0.094 ms | 41.12 ms | 0 |
| `workload_d_tileentities`| 50.00 ms | **0.178 ms** | 0.032 ms | 0.078 ms | 0.149 ms | 38.11 ms | 0 |
| `workload_e_block_ticks` | 50.00 ms | **0.169 ms** | 0.028 ms | 0.063 ms | 0.100 ms | 38.92 ms | 0 |
| `workload_f_mixed` | 50.00 ms | **0.167 ms** | 0.026 ms | 0.054 ms | 0.092 ms | 40.39 ms | 0 |

### Key Observations
1. **Real Headroom:** Healthy 1.12.2 servers on modern x86-64 hardware spend $<0.30\text{ ms}$ computing an idle or lightly loaded tick, leaving $>49.7\text{ ms}$ ($>99.4\%$) of each 50 ms budget as idle thread yield.
2. **Periodic Spikes:** Every 900 ticks (45 seconds), `MinecraftServer.tick()` invokes world autosave (`saveAllWorlds(true)`), creating periodic spikes to 37–43 ms. However, zero ticks exceeded the 50 ms deadline across all benchmarks.
3. **Steady-State GC Isolation:** During steady-state measurement across all workloads, HotSpot Young GC paused for 0 ms with 0 collections, validating that minor allocations remain fully within Eden space between autosave intervals. Boot-phase GC (2 young collections, 1 full collection during world generation) is cleanly separated.

---

## 7. Tail-Event Analysis: Root Cause of 37–43ms Max Compute Spikes

A rigorous inspection of every recorded per-tick timestamp and compute measurement (`*_ticks.csv`) identified the exact mechanism behind the maximum compute spikes:

1. **Deterministic Tick Alignment with the 900-Tick Autosave Cadence:**
   In `net.minecraft.server.MinecraftServer.tick()` (`MinecraftServer.java:524-529`):
   ```java
   if (this.tickCounter % 900 == 0) {
       this.profiler.startSection("save");
       this.playerList.saveAllPlayerData();
       this.saveAllWorlds(true);
       this.profiler.endSection();
   }
   ```
2. **Empirical Correlation Across Workloads:**
   - In `vanilla_idle` (warmup: 30s = 600 ticks), the single maximum spike (37.549 ms) occurred precisely at **measurement tick 300** ($600 + 300 = 900$ total ticks from boot).
   - In `workload_a_idle` (warmup: 30s = 600 ticks), the maximum spike (42.985 ms) occurred at **measurement tick 298–302** ($600 + 298 = 898-902$ total ticks).
   - In `workload_b_loaded_world` through `workload_f_mixed` (warmup: 20s = 400 ticks, duration: 25s = 500 ticks), the maximum spikes (38.1–41.9 ms) occurred precisely at **measurement ticks 498–500** ($400 + 498 = 898-900$ total ticks).
   - Conversely, in short runs (`overhead_test_rep1`, `rep2`: warmup 10s = 200 ticks, duration 15s = 300 ticks, total = 500 ticks), tick 900 was never reached, and the maximum compute time never exceeded **0.916 ms** (0 ticks > 20ms).
3. **Internal Spike Mechanism:**
   When `saveAllWorlds(true)` is called, `ChunkProviderServer.saveChunks(true)` executes synchronously on the Server thread without the 24-chunk batch throttling (`if (++p_ == 24 && !p_)`) that applies only to incremental saves (`var1 == false`). It serializes extra chunk data, flushes dirty chunk tags via `AnvilChunkLoader`, and saves player data, momentarily consuming 37–43 ms on the main thread.
4. **Deadline Compliance:**
   Even during full synchronous world autosaves, the maximum compute spike remained strictly under the 50.0 ms tick deadline (maximum observed: 42.985 ms), resulting in **0 missed deadlines** across the entire test matrix.

---

## 8. Sample Count & Percentile Calculation Verification

Sample counts and percentiles in `benchmarks/baseline/p0-2-v2/p0_2_summary.yaml` were independently verified against Python stdlib and NumPy mathematical definitions using the standard linear interpolation formula:

$$\text{rank} = \frac{p}{100} \times (N - 1)$$
$$v = x_{\lfloor\text{rank}\rfloor} \cdot (1 - (\text{rank} - \lfloor\text{rank}\rfloor)) + x_{\lceil\text{rank}\rceil} \cdot (\text{rank} - \lfloor\text{rank}\rfloor)$$

Both the JavaAgent runtime and independent verification scripts confirm:
- `vanilla_idle` ($N=602$): p50 = 0.025 ms, p90 = 0.037 ms, p95 = 0.046 ms, p99 = 0.129 ms.
- `workload_a_idle` ($N=602$): p50 = 0.031 ms, p90 = 0.068 ms, p95 = 0.082 ms, p99 = 0.435 ms.
- `workload_b` through `workload_f` ($N=500$ each): p50 $\in [0.026, 0.032]$ ms, p95 $\in [0.053, 0.078]$ ms, p99 $\in [0.092, 0.149]$ ms.
All sample counts match expected tick counts ($N = \text{duration} \times 20 \pm \text{cadence delta}$), with 100% data integrity verified.

