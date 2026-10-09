# Campaign Tooling

One shared toolkit for live RustCraft campaigns: `tools/campaign/`.
Future agents must use it instead of duplicating process/probe/wait/receipt
helpers (AGENTS.md has the standing rule).

## Modules

| module | owns |
|---|---|
| `policy.py` | duration tiers (dev/standard/milestone/soak), SOAK config guard |
| `session.py` | `MinecraftServerSession`: launch, Done detection, commands, save-all, graceful stop + kill fallback, context-manager cleanup |
| `waits.py` | bounded polling: `wait_for_log`, `wait_for_condition`, `hold_stability` (with re-verify), `wait_for_process_exit` |
| `evidence.py` | condition model: `CounterAtLeast`, `ZeroCounter`, `LogPatternSeen`, `Predicate`, `ALL`/`ANY`, `EvidenceTracker` |
| `restart.py` | restart-cycle runner (`probe_mode none/first_last/every`, fail_fast) |
| `receipt.py` | standard JSON receipt (`receipt.json`) + human summary |
| `telemetry.py` | JSONL events + metrics-snapshot parser |

## The four clocks (never conflate)

- **BOOT TIMEOUT** — max startup wait.
- **EVIDENCE DEADLINE / hard timeout** — safety ceiling; if evidence never
  arrives, the campaign ends `TIMEOUT` and FAILS.
- **POST-TARGET STABILITY** — short hold AFTER evidence succeeds.
- **SOAK DURATION** — explicit opt-in for time-dependent failure modes
  (`--test-tier soak --soak-seconds N --soak-reason "..."`); refuses to
  launch without both.

## Event-driven completion

Campaigns poll live counters (the JVM writes `regionRead.*` / `regionWrite.*`
snapshots every 2 s to `-Drustcraft.regionMetricsFile=<file>`) and terminate
when targets hold + the tier stability window passes:

```
if evidence_targets_met:
    hold_stability(tier.post_target_stability_s, verify=targets_still_hold)
    PASS_AND_STOP   # exit_reason = EVIDENCE_COMPLETE
```

Exit reasons: `EVIDENCE_COMPLETE` (the normal success), `TIMEOUT`,
`BOOT_FAILURE`, `SERVER_EXIT`, `PROBE_FAILURE`, `ASSERTION_FAILURE`,
`USER_INTERRUPT`, `TOOLING_ERROR`.

## Self-test

`python tools/campaign/self_test.py` — no Minecraft required (fake
sessions/logs). Run before launching any live campaign.

## Migrated runners

- `tools/authority-review/run_region_write_campaign.py` — read+write
  experiments, evidence-driven, receipts, tiers. Probe hold scales with the
  planned teleport-event schedule (no fixed 560/590 s default).

## Not yet migrated (used rarely; migrate on next touch)

`run_bounded_authority_smoke.py` (networking/PARKED territory),
`run_mspt_ab.py`, `run_multi_client_probe.py`, compression-era runners.
Do not refactor archived/historical scripts for completeness.


## Evidence accumulation (sqlite)

`campaign.evidence_db.EvidenceDB` records per-run counters keyed by
(campaign, gate, mode, git_sha). Two uses:

1. **Cumulative floors**: a big floor (e.g. 20k shadow reads) is met by the
   SUM across runs built from the same git_sha — several short runs replace
   one long run; the floor never weakens because the sha pins the code.
2. **Skip-if-evidenced**: before booting, the runner checks whether a prior
   run with the same signature already met the floor (with zero-guarded
   counters). If so it cites the stored receipt and skips the boot entirely
   (ccache-for-campaigns).

## Prior art (GitHub deep dive)

- [SpongePowered/McTester](https://github.com/spongepowered/mctester) —
  Minecraft integration tests against a real client; same shape as our
  probe campaigns.
- [RCON-driven CI](https://github.com/marketplace/actions/send-rcon-commands-to-minecraft-server)
  and [RCON client libraries](https://github.com/topics/rcon) — the standard
  way to drive an ALREADY-RUNNING server from CI without reboots.
- [testcontainers reuse](https://docs.pytest.org/en/stable/explanation/fixtures.html)
  / session-scoped fixtures — the warm-boot pattern: boot once per session,
  connect to the running instance thereafter.

## Next increment (designed, not yet built)

**Warm Gate A daemon + RCON**: boot the Gate A campaign server once with
RCON enabled; drive save-all/teleport via a tiny RCON client (~50 lines,
protocol in the links above) instead of stdin probes; spawn-chunk loading
alone yields ~600 RegionFile reads per boot cycle with zero probes (measured
625 in the DEV smoke). Remaining per-run taxes after warm reuse: probe
FML/PLAY (~60-90s, only needed where PLAY itself is the criterion) and the
world-copy for write campaigns (reads need no fresh copy).
