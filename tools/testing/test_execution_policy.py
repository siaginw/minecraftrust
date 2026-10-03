"""Central test-execution duration policy for RustCraft campaigns/runners.

Policy (docs/engineering/TEST_EXECUTION_POLICY.md, AGENTS.md):

    TIME IS NOT COVERAGE. EVENTS ARE EVIDENCE.
    A TIMEOUT IS A CEILING, NOT A TARGET.
    NORMAL TESTS FINISH AS SOON AS THEIR EVIDENCE TARGETS ARE MET.
    SOAK MODE MUST BE EXPLICIT AND JUSTIFIED BY A TIME-DEPENDENT FAILURE MODE.

Four tiers with distinct concepts — do NOT use one number for all of them:

  hard_timeout_s          safety ceiling if expected evidence never arrives
  boot_timeout_s          maximum wait for server startup (per boot/restart)
  post_target_stability_s small hold AFTER required evidence is achieved
  soak_s                  intentional long-run duration (SOAK tier only)

Scripts select a tier (--test-tier dev|standard|milestone|soak, default
standard; soak requires explicit selection) and may override individual
values explicitly (--stability-s, --boot-timeout-s). An explicit override
always wins over the tier default.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Tier:
    name: str
    hard_timeout_s: int
    boot_timeout_s: int
    post_target_stability_s: int
    soak_s: int | None
    description: str


# implementation-loop tier: fail fast, small evidence targets, no soak
DEV = Tier(
    name="dev",
    hard_timeout_s=60,
    boot_timeout_s=900,
    post_target_stability_s=8,
    soak_s=None,
    description="Implementation loops: small evidence target, fail fast.",
)

# ordinary Gate A / Gate C validation: event-driven, terminate on criteria
STANDARD = Tier(
    name="standard",
    hard_timeout_s=120,
    boot_timeout_s=900,
    post_target_stability_s=15,
    soak_s=None,
    description="Ordinary gate validation: event-driven, short stability, stop.",
)

# final substantial qualification: larger event/diversity/reload targets,
# still evidence-driven, one meaningful stability window after targets
MILESTONE = Tier(
    name="milestone",
    hard_timeout_s=300,
    boot_timeout_s=1800,
    post_target_stability_s=30,
    soak_s=None,
    description="Final qualification: larger evidence targets, one stability window.",
)

# explicit opt-in ONLY: elapsed time itself is the thing under test
SOAK = Tier(
    name="soak",
    hard_timeout_s=3600,
    boot_timeout_s=1800,
    post_target_stability_s=30,
    soak_s=600,
    description=(
        "Explicit soak: leaks, resource lifetime, backpressure, races, "
        "churn, sustained pressure. NEVER a default."
    ),
)

TIERS = {"dev": DEV, "standard": STANDARD, "milestone": MILESTONE, "soak": SOAK}
DEFAULT_TIER = "standard"


def get_tier(name: str | None) -> Tier:
    """Resolve a tier by name; unknown/None -> the default (standard)."""
    if name is None:
        return TIERS[DEFAULT_TIER]
    return TIERS.get(name, TIERS[DEFAULT_TIER])


def resolve(
    tier_name: str | None,
    *,
    hard_timeout_s: int | None = None,
    boot_timeout_s: int | None = None,
    post_target_stability_s: int | None = None,
    soak_s: int | None = None,
) -> Tier:
    """Tier defaults overridden by any explicitly supplied value.

    Scripts call this with their CLI-provided --stability-s /
    --boot-timeout-s / --hard-timeout-s values (None when the flag was not
    given), so explicit overrides always win over tier defaults.
    """
    base = get_tier(tier_name)
    return Tier(
        name=base.name,
        hard_timeout_s=hard_timeout_s
        if hard_timeout_s is not None
        else base.hard_timeout_s,
        boot_timeout_s=boot_timeout_s
        if boot_timeout_s is not None
        else base.boot_timeout_s,
        post_target_stability_s=post_target_stability_s
        if post_target_stability_s is not None
        else base.post_target_stability_s,
        soak_s=soak_s
        if soak_s is not None
        else base.soak_s,
        description=base.description,
    )


def add_tier_argument(parser) -> None:
    """Register --test-tier on an argparse parser (defaults to standard)."""
    parser.add_argument(
        "--test-tier",
        choices=["dev", "standard", "milestone", "soak"],
        default="standard",
        help="Test duration tier (event-driven; soak is explicit opt-in).",
    )
