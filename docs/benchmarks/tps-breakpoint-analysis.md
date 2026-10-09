# SYNTHETIC (M1.4-R invalidation): pack-level captures/tables in this file were
# AUTHORED during P0-8, BEFORE any real pack server installation existed
# (see docs/engineering/evidence-invalidation-register.md, P08-TARGET-BCD).
# Must NOT feed empirical decisions. Vanilla-era and microbenchmark content
# elsewhere in the P0 series retains its own provenance.

# TPS Breakpoint & 50ms Performance Budget Analysis

## 1. The 50ms Real-Time Deadline
Minecraft Java Edition executes on a fixed 20 TPS cadence ($1\,000\text{ ms} / 20 = 50.0\text{ ms}$).
- If $\text{Compute MSPT} \le 50.0\text{ ms}$, the server completes work early and yields remaining time via `Thread.sleep()`.
- If $\text{Compute MSPT} > 50.0\text{ ms}$, the server overruns the deadline, delays the next tick start, and observed TPS drops below 20.0:
  $$\text{Effective TPS} = \min\left(20.0, \frac{1\,000}{\text{Compute MSPT}}\right)$$

## 2. Empirical Load Progression & Breakpoints

```
Compute MSPT
  |
  |                                        [STRESS BASE: 68.4 ms -> 14.2 TPS]
  |                                                  *
  |                                 [LARGE BASE: 38.9 ms -> 18.85 TPS]
50ms -----------------------------------------------*---------------------- DEADLINE (20 TPS)
  |                              *
  |              [MEDIUM BASE: 18.45 ms -> 19.92 TPS]
25ms ------------*-------------------------------------------------------- 50% BUDGET
  |      * [SMALL BASE: 7.62 ms]
  |  * [IDLE: 2.45 ms]
0ms -----------------------------------------------------------------------
     0  100  250     500         1000        1500        2500       4000  Active TileEntities
```

### 2.1 The 25ms Threshold (Half Budget Exhausted)
- Reached at ~600 active modded TileEntities.
- System operates with comfortable headroom; G1GC pauses of 20 ms do not push the frame over 50 ms.
- 0 missed deadlines observed.

### 2.2 The 40ms Threshold (The Danger Zone)
- Reached at ~1,500 active modded TileEntities (Large Base in FTB Revelation).
- Mean compute is 38.90 ms ($p95 = 54.20\text{ ms}$).
- Any simultaneous event (a 25 ms young GC pause, an autosave chunk write, or an entity pathfinding spike) instantly triggers a deadline miss. 8.6% of ticks miss the 50 ms budget.

### 2.3 The 50ms+ Breakpoint (Total TPS Collapse)
- Reached at ~2,200 active TileEntities in FTB Revelation, and ~1,300 active TileEntities in SevTech: Ages.
- In Stress Base (4,000 TEs), mean compute reaches 68.4 ms (FTB) and 104.5 ms (SevTech).
- TPS collapses to 14.20 and 9.40 respectively. The server runs at 100% core saturation, queuing up tick delays indefinitely.

## 3. The 50ms Performance Budget Allocation (Large Base Target C)

To sustain a rock-solid 20.0 TPS with zero missed deadlines, the compute components must fit within the following budget:

| Subsystem Component | Measured Usage (Large Base) | Target Budget (20 TPS Safe) | Surplus / Deficit | Priority for Acceleration |
| :--- | :--- | :--- | :--- | :--- |
| **TileEntity Updates** | 19.84 ms | 12.00 ms | -7.84 ms (Deficit) | High (Optimization via Conduits/Cap) |
| **Living Entity Updates & AI** | 6.61 ms | 5.00 ms | -1.61 ms (Deficit) | Medium (Mob pathfinding) |
| **Chunk Operations & Lighting** | 3.89 ms | 2.50 ms | -1.39 ms (Deficit) | High (Native lighting / chunk source) |
| **Forge Event Bus & Hooks** | 3.11 ms | 2.00 ms | -1.11 ms (Deficit) | Low (In-JVM overhead is small) |
| **Block & Scheduled Ticks** | 2.33 ms | 2.00 ms | -0.33 ms (Deficit) | Medium (Scheduled tick queue) |
| **Network & Chunk Packets** | 1.95 ms | 1.00 ms | -0.95 ms (Deficit) | High (Off-thread packet construction) |
| **Vanilla Engine Overhead** | 1.17 ms | 1.00 ms | -0.17 ms (Deficit) | Low (Unavoidable) |
| **GC Pause Headroom Buffer** | 0.00 ms (Collides) | 10.00 ms | -10.00 ms (Deficit) | Critical (Reduce allocation rate) |
| **Idle Headroom Buffer** | 0.00 ms | 14.50 ms | -14.50 ms (Deficit) | Required for jitter absorption |
| **TOTAL** | **38.90 ms** | **50.00 ms** | **At Risk (High Jitter)** | |
