"""V2 live-shadow outcome taxonomy and the parity denominator rule.

Fixed by docs/research/V2_LIVE_SHADOW_ARCHITECTURE.md §6 BEFORE any campaign
ran, because a taxonomy decided after seeing results is not a taxonomy, it is
a rationalisation.

The one rule that matters:

    parity denominator = COMPARE_PASS + COMPARE_MISMATCH

Nothing else is a pass. An excluded event is not a pass, a dropped event is
not a pass, and a campaign of ten thousand exclusions with fifty comparisons
reports "50 compared" -- never "10,050 events, 100% pass". Every non-compared
outcome exists precisely because the event could NOT honestly be counted, so
counting it would invert the meaning of the campaign.

Java remains the only authority in every one of these outcomes.
"""
from __future__ import annotations

from enum import Enum


class Outcome(str, Enum):
    """One terminal outcome per shadow event. No event has two."""

    COMPARE_PASS = "COMPARE_PASS"
    COMPARE_MISMATCH = "COMPARE_MISMATCH"
    EXCLUDED = "EXCLUDED"
    DROPPED = "DROPPED"
    DISQUALIFIED = "DISQUALIFIED"
    INCOMPLETE = "INCOMPLETE"
    INFRA_FAILURE = "INFRA_FAILURE"


#: The only outcomes that may enter the parity denominator. Both sides were
#: computed, the correspondence was proven, and the comparison happened.
#: MISMATCH is in the denominator on purpose: a mismatch is a completed
#: comparison that failed, and hiding it from the denominator would turn a
#: 50%-broken campaign into a 100%-passing one.
COMPARED = (Outcome.COMPARE_PASS, Outcome.COMPARE_MISMATCH)

#: Outcomes that exist because the event could NOT be honestly compared.
#: Counting any of these as parity would be the exact failure mode the
#: taxonomy exists to make impossible.
NOT_PARITY = (
    Outcome.EXCLUDED,
    Outcome.DROPPED,
    Outcome.DISQUALIFIED,
    Outcome.INCOMPLETE,
    Outcome.INFRA_FAILURE,
)

#: Outcomes that end a campaign immediately. Everything else is per-event.
CAMPAIGN_STOPPING = (Outcome.COMPARE_MISMATCH, Outcome.DISQUALIFIED, Outcome.INFRA_FAILURE)

ALL = tuple(Outcome)

#: Structured per-reason counters. A single "shadow_failed" bucket would hide
#: the difference between "the queue was full" (healthy backpressure) and "the
#: coherence contract broke" (a correctness finding).
SCOPE_REASONS = (
    "HIGH_STATE_ID",
    "TILE_ENTITY_PRESENT",
    "UNSUPPORTED_WORLD",
    "UNSUPPORTED_REGISTRY",
    "UNSUPPORTED_STORAGE",
    "UNSUPPORTED_GENERATOR",
    "NOT_FULL_CHUNK_MASK",
    "COHERENCE",
    "OTHER_KNOWN",
)


def parity_denominator(counts: dict) -> int:
    """pass + mismatch, and nothing else.

    Deliberately does NOT trust the caller's own `pass` total: it recomputes
    from the per-outcome counters so a drifted aggregate cannot smuggle an
    excluded event into the denominator.
    """
    return int(counts.get(Outcome.COMPARE_PASS.value, 0)) + int(
        counts.get(Outcome.COMPARE_MISMATCH.value, 0))


def parity_rate(counts: dict) -> float | None:
    """Compared-pass fraction, or None when nothing was compared.

    None -- not 0.0 -- because "no comparisons" and "all comparisons failed"
    are different facts, and collapsing them is how an empty campaign would
    read as a perfect one.
    """
    denominator = parity_denominator(counts)
    if denominator == 0:
        return None
    return int(counts.get(Outcome.COMPARE_PASS.value, 0)) / denominator


def validate_counts(counts: dict) -> None:
    """Refuse a counter set that could not have come from real events.

    Unknown outcome keys mean someone invented a new bucket without adding it
    to the taxonomy; negative counts mean someone decremented; compared
    exceeding observed means events appeared from nowhere. All three are
    receipt-corruption signals, and a receipt is evidence, so they raise
    rather than clamp.
    """
    # `observed` is a total, not an outcome bucket; everything else must be a
    # named taxonomy outcome.
    unknown = set(counts) - {o.value for o in ALL} - {"observed"}
    if unknown:
        raise ValueError("unknown outcome counter(s): " + ", ".join(sorted(unknown)))
    for key, value in counts.items():
        if int(value) < 0:
            raise ValueError("negative counter: " + key)
    observed = int(counts.get("observed", 0))
    accounted = sum(int(counts.get(o.value, 0)) for o in ALL)
    if observed and accounted > observed:
        raise ValueError(
            "outcome counters (%d) exceed observed events (%d): events cannot appear "
            "from nowhere, so the receipt is corrupt" % (accounted, observed))
