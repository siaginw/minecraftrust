"""Central test-execution duration policy (goal §4-§6).

Four distinct clocks — never one number for all of them:
  hard_timeout_s          EVIDENCE DEADLINE ceiling (safety, not a target)
  boot_timeout_s          maximum startup wait (per boot/restart)
  post_target_stability_s small hold AFTER evidence succeeds
  soak_s                  intentional long-run duration (SOAK only)

SOAK fails configuration without an explicit duration + reason (goal §4/§23).
Implementation lives in tools/testing/test_execution_policy.py (existing
shared module); this module re-exports it as the campaign-policy surface and
adds the SOAK configuration guard.
"""
from __future__ import annotations

import sys
from pathlib import Path

_TOOLS_TESTING = Path(__file__).resolve().parents[1] / "testing"
if str(_TOOLS_TESTING) not in sys.path:
    sys.path.insert(0, str(_TOOLS_TESTING))

from test_execution_policy import (  # noqa: E402,F401
    DEV, MILESTONE, SOAK, STANDARD, DEFAULT_TIER, TIERS, Tier,
    add_tier_argument, get_tier, resolve,
)

# SOAK without an explicit duration+reason must fail configuration (goal §4).


class SoakConfigurationError(ValueError):
    pass


def require_soak_config(tier_name: str | None, soak_seconds: int | None,
                        soak_reason: str | None) -> None:
    """Raise unless an explicitly selected SOAK tier carries duration+reason."""
    if (tier_name or "standard") != "soak":
        return
    if not soak_seconds or not soak_reason:
        raise SoakConfigurationError(
            "SOAK tier requires --soak-seconds N and --soak-reason 'why "
            "elapsed time is the thing under test'")


def resolve_campaign(tier_name: str | None, *, hard_timeout_s: int | None = None,
                     boot_timeout_s: int | None = None,
                     post_target_stability_s: int | None = None,
                     soak_seconds: int | None = None,
                     soak_reason: str | None = None) -> Tier:
    """resolve() + the SOAK configuration guard."""
    require_soak_config(tier_name, soak_seconds, soak_reason)
    return resolve(
        tier_name,
        hard_timeout_s=hard_timeout_s,
        boot_timeout_s=boot_timeout_s,
        post_target_stability_s=post_target_stability_s,
        soak_s=soak_seconds,
    )
