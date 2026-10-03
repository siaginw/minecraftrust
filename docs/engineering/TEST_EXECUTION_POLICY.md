# Test Execution Policy

**TIME IS NOT COVERAGE. EVENTS ARE EVIDENCE.**
**A TIMEOUT IS A CEILING, NOT A TARGET.**
**NORMAL TESTS FINISH AS SOON AS THEIR EVIDENCE TARGETS ARE MET.**
**SOAK MODE MUST BE EXPLICIT AND JUSTIFIED.**

Central module: [`tools/testing/test_execution_policy.py`](../../tools/testing/test_execution_policy.py)
(`--test-tier dev|standard|milestone|soak`; explicit `--stability-s` /
`--boot-timeout-s` overrides always win over tier defaults).

## Tiers

| Tier | Typical hard ceiling | Stability after evidence | Use |
|---|---|---|---|
| `dev` | 60 s | ~8 s | Implementation loops; small evidence target; fail fast |
| `standard` | 120 s | ~15 s | Ordinary Gate A / Gate C validation; event-driven; stop when criteria pass |
| `milestone` | ~300 s | ~30 s | Final qualification; larger event/diversity/reload targets, one stability window |
| `soak` | 600 s+ (explicit) | ~30 s | ONLY for time-dependent failure modes: leaks, resource lifetime, backpressure, races, churn, sustained pressure |

**SOAK MUST NEVER BE THE DEFAULT.** Any run longer than the `milestone`
ceiling must state the time-dependent failure mode it tests.

## Distinct concepts — never one number for all four

- **BOOT TIMEOUT** — max wait for server startup (per boot/restart).
- **RUN HARD TIMEOUT** — safety ceiling if expected evidence never arrives.
- **STABILITY WINDOW** — small hold AFTER required evidence is achieved.
- **SOAK DURATION** — intentional long-run duration for time-dependent tests.

## Event-driven termination

```
if evidence_targets_met:
    hold_short_stability_window()
    PASS_AND_STOP
```

Evidence = event counts, comparisons, writes/reads, unique chunks, reload
cycles, client diversity — never elapsed time. Long duration alone is not
evidence.

## Restarts

A restart cycle proves boot → Done → FML/login/PLAY → target data loads →
short stability (10-20 s) → stop. For 10/25-cycle campaigns the evidence is
the number of successful independent restarts, not wall-clock idle time.

## For agents

Before adding or modifying a RustCraft test campaign, select the narrowest
tier that proves the requested property. Routine runs must not use SOAK.
Prefer event-driven completion. Any test exceeding the `milestone` tier must
state what time-dependent failure mode requires it. Do not shorten tests
whose purpose IS elapsed time (leaks, backpressure, races, GC accumulation) —
classify them as explicit soaks and say why.

## Intentional long windows (audit exceptions)

- JFR capture windows / profiling sessions (`--profile-jfr`) — measurement
  windows, not waits.
- Benchmark measurement passes (`RegionReadBench`, `RegionWriteBench`,
  save-burst A/B) — measurement, evidence-driven by record counts.
- `--stability-s 590`-style values remain available as EXPLICIT operator
  overrides only; no runner uses them as an unexplained default.
