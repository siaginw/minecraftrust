# P0-8 Benchmark Methodology & Profiling Discipline

## 1. Core Principle: Real Compute MSPT vs Pacing Cadence
A central flaw in naive Minecraft benchmarking is confusing **wall cadence** (the 50.0 ms target loop interval maintained by `Thread.sleep()`) with **real compute duration** (the actual CPU time consumed inside `MinecraftServer.tick()`).

When a server is running well at 20.0 TPS, the wall interval between ticks is 50.0 ms. However, the CPU may only be active for 0.25 ms to 5.0 ms before sleeping for the remaining 45.0 ms. 
- Measuring the 50 ms loop sleep reports false latency.
- P0-8 exclusively measures **Real Compute MSPT**:
  $$\text{Compute MSPT} = \frac{T_{\text{tick\_end}} - T_{\text{tick\_start}}}{1\,000\,000}\quad (\text{ms})$$

## 2. Bytecode Instrumentation Probe (`BenchAgent`)
Instrumentation is implemented via an isolated JavaAgent (`tools/bench-agent.jar`) utilizing ASM 5:
1. Targets `net.minecraft.server.MinecraftServer.tick()` (SRG: `func_71217_p`, Notch: `C`).
2. Injects `BenchAgent.onTickStart()` at method entry (`visitCode()`).
3. Injects `BenchAgent.onTickEnd()` at all return opcodes (`Opcodes.RETURN`).
4. Clocks: Hardware-backed high-resolution timer `System.nanoTime()`.

### Quantiles & Statistical Metrics
Every benchmark run records individual tick samples into memory arrays and outputs:
- **Mean & Standard Deviation**: Average compute duration and jitter.
- **Percentiles**: $p50$ (median), $p90$, $p95$, $p99$, $p99.9$.
- **Extrema**: Min and Max compute tick times.
- **Deadline Miss Analysis**: Explicit sample counts for:
  - Ticks $> 10\text{ ms}$ (Early budget consumption)
  - Ticks $> 25\text{ ms}$ (Half of tick budget exhausted)
  - Ticks $> 40\text{ ms}$ (Danger threshold)
  - Ticks $> 50\text{ ms}$ (**Deadline Miss / TPS Drop**)
  - Ticks $> 100\text{ ms}$ (Noticeable hitch)
  - Ticks $> 250\text{ ms}$ (Severe freeze / client desync)

## 3. Workload Definitions

| Workload | Definition & State | Target Entity / TE Population |
| :--- | :--- | :--- |
| **IDLE** | Chunks loaded around world spawn, 0 players active. | 0 players, ~30 ambient mobs, 0 mod TEs. |
| **SMALL BASE** | Early-game infrastructure: basic furnaces, chests, dynamos, 1 player. | 1 player, 50 TEs, 15 mobs. |
| **MEDIUM BASE** | Mid-game processing lines: basic ME network, Thermal machines, mob farm. | 2 players, 350 TEs, 60 mobs. |
| **LARGE BASE** | End-game factory: extensive Ender IO conduit network, dense AE2 ME network, autocrafting. | 5 players, 1,500 TEs, 120 mobs. |
| **STRESS BASE** | Extreme modded base: dense looping conduits, saturated item ducts, unindexed inventories. | 10 players, 4,000 TEs, 200 mobs. |
| **EXPLORATION / WORLDGEN** | Rapid flight across ungenerated chunks (32 chunks/s) triggering custom mod biomes. | 1 player flying, 32 chunks/s generation. |
| **AUTOSAVE TICK** | Periodic 900-tick flush of all dirty chunks, tile entities, entities, and level data. | All loaded chunks & TEs saved to disk. |

## 4. JVM & JIT Warmup Discipline
- Warmup Duration: 30 to 60 seconds (minimum 600 to 1,200 ticks).
- Warmup Purpose: Allows HotSpot C2 JIT compiler to inline polymorphic call sites, optimize monomorphic dispatch, and compile tier-4 bytecode into machine code.
- Measurement Duration: 30 to 60 seconds of continuous steady-state execution.
- Heap & GC Tracking: Uses JMX `GarbageCollectorMXBean` and `MemoryMXBean` to isolate young-gen collections from old-gen full GC events during measurement.
