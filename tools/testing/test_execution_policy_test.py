"""Tests for the central test-execution duration policy (goal §13)."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from testing.test_execution_policy import (  # noqa: E402
    DEFAULT_TIER, DEV, MILESTONE, SOAK, STANDARD, TIERS, add_tier_argument,
    get_tier, resolve,
)


def test_tier_ordering():
    assert DEV.hard_timeout_s < STANDARD.hard_timeout_s < MILESTONE.hard_timeout_s
    assert DEV.post_target_stability_s < STANDARD.post_target_stability_s
    assert STANDARD.post_target_stability_s <= MILESTONE.post_target_stability_s
    # soak must be strictly longer-lived than every non-soak tier
    assert SOAK.soak_s is not None
    assert SOAK.soak_s > MILESTONE.hard_timeout_s


def test_soak_never_default():
    assert DEFAULT_TIER != "soak"
    assert get_tier(None).name == "standard"
    assert get_tier("nonsense").name == "standard"


def test_explicit_overrides_win():
    t = resolve("standard", post_target_stability_s=45, boot_timeout_s=60)
    assert t.post_target_stability_s == 45
    assert t.boot_timeout_s == 60
    assert t.hard_timeout_s == STANDARD.hard_timeout_s  # untouched field keeps tier value
    t2 = resolve("dev", soak_s=900)
    assert t2.soak_s == 900


def test_region_campaign_default_not_590():
    """The normal region campaign must not resolve to a ~590s hold."""
    # historical bad default
    assert STANDARD.post_target_stability_s < 590
    # even milestone stability stays well below the old probe hold
    assert MILESTONE.post_target_stability_s < 590
    # a 590-second hold is only reachable via explicit SOAK or an explicit override
    assert resolve("soak").soak_s >= 600
    assert resolve(None).post_target_stability_s < 590


def test_add_tier_argument_defaults():
    import argparse

    parser = argparse.ArgumentParser()
    add_tier_argument(parser)
    args = parser.parse_args([])
    assert args.test_tier == "standard"
    args2 = parser.parse_args(["--test-tier", "soak"])
    assert args2.test_tier == "soak"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print(f"ok {name}")
    print("TEST_EXECUTION_POLICY_TESTS_PASSED")
