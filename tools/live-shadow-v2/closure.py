"""Closure evaluator for the V2 live-shadow campaign (machine-checkable).

The predeclared criteria are authoritative and are encoded EXACTLY once
here, as numbers -- never prose. Every boundary in the goal's section 21
has a control in test_closure.py; the evaluator itself has no
interpretation latitude: it refuses production authority, refuses an
empty denominator, and reports the first unmet criterion by name.
"""
from __future__ import annotations

SCHEMA = "RUSTCRAFT_V2_LIVE_SHADOW_CLOSURE_V1"

MIN_DENOMINATOR = 2000
MAX_DROP_RATE = 0.10
MAX_EXCLUSION_RATE = 0.60
MIN_IO_ORIGIN = 200
MIN_INCARNATIONS = 300
MIN_RELOADS = 20

PARITY_OUTCOMES = ("COMPARE_PASS", "COMPARE_MISMATCH")


class ClosureInput:
    """The campaign totals, recomputed from event journals by the runner."""

    def __init__(self, *, compare_pass: int, compare_mismatch: int,
                 unexplained_mismatch: int, dropped: int, excluded: int,
                 disqualified: int, infra_failure: int,
                 integrity_threatening_infra: int,
                 io_origin_comparisons: int, distinct_incarnations: int,
                 reload_cycles: int, observed_events: int,
                 production_authority: bool):
        self.compare_pass = compare_pass
        self.compare_mismatch = compare_mismatch
        self.unexplained_mismatch = unexplained_mismatch
        self.dropped = dropped
        self.excluded = excluded
        self.disqualified = disqualified
        self.infra_failure = infra_failure
        self.integrity_threatening_infra = integrity_threatening_infra
        self.io_origin_comparisons = io_origin_comparisons
        self.distinct_incarnations = distinct_incarnations
        self.reload_cycles = reload_cycles
        self.observed_events = observed_events
        self.production_authority = production_authority

    @property
    def denominator(self) -> int:
        return self.compare_pass + self.compare_mismatch

    @property
    def drop_rate(self) -> float:
        return self.dropped / self.observed_events if self.observed_events else 0.0

    @property
    def exclusion_rate(self) -> float:
        return self.excluded / self.observed_events if self.observed_events else 0.0


def evaluate(totals: ClosureInput) -> dict:
    """Returns the closure verdict; every criterion is named, never implied."""
    receipt = {
        "schema": SCHEMA,
        "production_authority": False,
        "criteria": {
            "denominator": {
                "value": totals.denominator,
                "required": MIN_DENOMINATOR,
                "met": totals.denominator >= MIN_DENOMINATOR,
            },
            "unexplained_mismatch": {
                "value": totals.unexplained_mismatch,
                "required": 0,
                "met": totals.unexplained_mismatch == 0,
            },
            "drop_rate": {
                "value": totals.drop_rate,
                "required_max": MAX_DROP_RATE,
                "met": totals.drop_rate <= MAX_DROP_RATE,
            },
            "exclusion_rate": {
                "value": totals.exclusion_rate,
                "required_max": MAX_EXCLUSION_RATE,
                "met": totals.exclusion_rate <= MAX_EXCLUSION_RATE,
            },
            "io_origin_comparisons": {
                "value": totals.io_origin_comparisons,
                "required": MIN_IO_ORIGIN,
                "met": totals.io_origin_comparisons >= MIN_IO_ORIGIN,
            },
            "distinct_incarnations": {
                "value": totals.distinct_incarnations,
                "required": MIN_INCARNATIONS,
                "met": totals.distinct_incarnations >= MIN_INCARNATIONS,
            },
            "reload_cycles": {
                "value": totals.reload_cycles,
                "required": MIN_RELOADS,
                "met": totals.reload_cycles >= MIN_RELOADS,
            },
        },
    }
    if totals.production_authority:
        # Refuse regardless of everything else: closure is evidence for a
        # review, never an authority grant.
        receipt["verdict"] = "REFUSED_PRODUCTION_AUTHORITY"
        receipt["unmet"] = ["production_authority"]
        return receipt
    if totals.denominator == 0:
        receipt["verdict"] = "REFUSED_EMPTY_DENOMINATOR"
        receipt["unmet"] = ["denominator"]
        return receipt
    unmet = [name for name, criterion in receipt["criteria"].items()
             if not criterion["met"]]
    # An integrity-threatening INFRA_FAILURE stops closure even when every
    # counted criterion passes: it is not parity evidence and must not be
    # buried in statistics.
    if totals.integrity_threatening_infra > 0:
        unmet.append("integrity_threatening_infra")
    receipt["unmet"] = unmet
    receipt["verdict"] = "CLOSED" if not unmet else "NOT_CLOSED"
    # LIVE_SHADOW_CLOSED is the highest state this evaluator may confer.
    # AUTHORITY_ELIGIBLE/AUTHORITY_APPROVED remain unreachable without the
    # separate authority-review decision.
    receipt["state"] = ("LIVE_SHADOW_CLOSED" if receipt["verdict"] == "CLOSED"
                        else "LIVE_SHADOW_OPEN")
    return receipt
