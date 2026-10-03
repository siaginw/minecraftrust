"""Shared restart-cycle runner (goal §14).

Ten restart cycles means 10 INDEPENDENT BOOTS — not 10 × ten-minute idles.
Typical cycle: launch -> Done -> optional probe -> short stability -> stop.
Restart cycles never inherit main-session stability durations.
"""
from __future__ import annotations

import time
from typing import Callable

from .policy import Tier
from .session import MinecraftServerSession
from .waits import hold_stability

PROBE_MODES = ("none", "first_last", "every")


def run_restart_cycles(
    make_session: Callable[[int], MinecraftServerSession],
    cycles: int,
    *,
    probe_mode: str = "first_last",
    probe_fn: Callable[[int], bool] | None = None,
    tier: Tier | None = None,
    verify_fn: Callable[[MinecraftServerSession], bool] | None = None,
    fail_fast: bool = True,
) -> dict:
    """Run `cycles` independent boots. probe_fn(cycle) runs the headless
    probe when the cycle qualifies under probe_mode. Returns a result dict:
    {cycles_ok, cycles_attempted, probes_ok, failures[]}."""
    if tier is None:
        from .policy import STANDARD
        tier = STANDARD
    if probe_mode not in PROBE_MODES:
        raise ValueError(f"probe_mode must be one of {PROBE_MODES}")
    ok = 0
    probes_ok = 0
    failures: list[str] = []
    for cycle in range(1, cycles + 1):
        session = make_session(cycle)
        try:
            if not session.start():
                failures.append(f"cycle {cycle}: boot failure")
                session.stop(timeout_s=60)
                if fail_fast:
                    break
                continue
            # optional world/chunk verification
            if verify_fn is not None and not verify_fn(session):
                failures.append(f"cycle {cycle}: verification failed")
                session.stop(timeout_s=60)
                if fail_fast:
                    break
                continue
            # probe on first/last or every cycle
            want_probe = probe_fn is not None and (
                probe_mode == "every"
                or (probe_mode == "first_last"
                    and cycle in (1, cycles)))
            if want_probe:
                time.sleep(min(20, tier.post_target_stability_s))
                if probe_fn(cycle):
                    probes_ok += 1
                else:
                    failures.append(f"cycle {cycle}: probe failed")
                    session.stop(timeout_s=60)
                    if fail_fast:
                        break
                    continue
            # goal §14: 5-15s stability at most, never the main-session hold
            hold_stability(min(15, tier.post_target_stability_s))
            session.stop()
            ok += 1
        finally:
            # FakeSession (tooling tests) has no kill(); real sessions do
            if hasattr(session, "kill"):
                session.kill()  # no orphans on any path
    return {
        "cycles_ok": ok,
        "cycles_attempted": cycles,
        "probes_ok": probes_ok,
        "failures": failures,
    }
