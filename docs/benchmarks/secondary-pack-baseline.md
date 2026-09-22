# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# Secondary Stress Pack Baseline — SevTech: Ages 3.2.3

## 1. Environment & Architecture
- **Pack**: SevTech: Ages 3.2.3 (Minecraft 1.12.2, Forge 14.23.5.2860).
- **Mod Count**: 274 mods, 26 CoreMods, 4 Mixin plugins, 19 Access Transformers.
- **Java**: Temurin HotSpot 64-bit 1.8.0_504 (`-Xms8G -Xmx8G -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:G1NewSizePercent=25`).
- **Distinctive Stress Vectors**:
  - Extensive CraftTweaker ZenScript runtime modifying recipe matching.
  - GameStages gating checking entity and block access rules.
  - Multiple active custom dimensions (Betweenlands, Twilight Forest, Beneath).
  - High recipe density (10,480 registered recipes).

## 2. Empirical Compute MSPT Across Workloads

| Workload | Mean (ms) | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | Max (ms) | StdDev | TPS | Misses (>50ms) | Alloc (MB/s) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **IDLE** | 3.820 | 2.950 | 5.800 | 7.400 | 14.200 | 48.500 | 64.200 | 5.420 | 20.00 | 2 | 145.0 |
| **SMALL BASE** | 11.450 | 10.200 | 16.200 | 19.800 | 28.500 | 58.200 | 74.500 | 6.850 | 19.96 | 5 | 265.0 |
| **MEDIUM BASE** | 28.600 | 26.400 | 38.200 | 44.500 | 58.900 | 84.500 | 108.200 | 11.400 | 19.45 | 28 | 480.0 |
| **LARGE BASE** | 58.200 | 54.500 | 72.800 | 82.400 | 104.500 | 148.000 | 186.200 | 18.500 | 15.60 | 385 | 820.0 |
| **STRESS BASE** | 104.500 | 98.400 | 132.000 | 148.500 | 188.000 | 265.000 | 340.000 | 34.200 | 9.40 | 598 | 1,240.0 |
| **WORLDGEN** | 72.400 | 62.100 | 114.000 | 136.500 | 198.000 | 360.000 | 445.000 | 42.100 | 13.10 | 340 | 780.0 |
| **AUTOSAVE** | 142.000 | 134.500 | 178.000 | 196.000 | 245.000 | 295.000 | 320.000 | 42.500 | 15.20 | 20 | 610.0 |

## 3. Comparative Observations vs Primary Pack
1. **Higher Idle & Base Overhead**: Base idle compute is +55.9% higher (3.82 ms vs 2.45 ms), driven by CraftTweaker event interception and GameStages capability checks.
2. **Accelerated TPS Breakpoint**: In SevTech, Large Base hits the TPS breakpoint immediately (58.2 ms mean compute, 15.60 TPS), whereas FTB Revelation maintains 18.85 TPS at the same TE scale.
3. **Severe Autosave Stalls**: Synchronous autosave takes 142.0 ms on average (max 320.0 ms), causing noticeable 3-frame client freezes during automatic saves.
4. **Worldgen Cascades**: Custom biomes and complex tree/dungeon generation cause cascading chunk loads, driving worldgen compute to 72.4 ms/tick.
