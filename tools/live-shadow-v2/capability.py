"""The V2 live-shadow capability state machine.

A shadow capability moves through explicit states, and the transitions are
the whole point: a campaign that closed cleanly is `LIVE_SHADOW_CLOSED`, and
there is NO edge from any shadow state to any authority state. Authority
eligibility is a separate judgement that this machine cannot express, reach,
or imply -- if represented at all it is a named state with NO incoming edge,
so the transition table itself refuses it.

Illegal transitions raise. Nothing is coerced, nothing is silently rebased
onto the nearest legal state: a state model that quietly repairs an illegal
jump is a state model that will quietly make the jump that mattered.
"""
from __future__ import annotations

import time


class State:
    UNAVAILABLE = "UNAVAILABLE"
    OFFLINE_QUALIFIED = "OFFLINE_QUALIFIED"
    LIVE_SHADOW_ELIGIBLE = "LIVE_SHADOW_ELIGIBLE"
    LIVE_SHADOW_ACTIVE = "LIVE_SHADOW_ACTIVE"
    LIVE_SHADOW_CLOSED = "LIVE_SHADOW_CLOSED"

    #: Deliberately unreachable. Listed so the refusal is a nameable fact
    #: rather than an omission: no edge in ALLOWED points here, and entering
    #: it raises AuthorityTransitionRefused. Any future authority path is a
    #: separate machine, defined and reviewed on its own.
    AUTHORITY_ELIGIBLE = "AUTHORITY_ELIGIBLE"
    AUTHORITY_APPROVED = "AUTHORITY_APPROVED"


#: The single source of truth for legality. Adding an edge here is a design
#: change requiring review; nothing else in the module can authorise one.
ALLOWED = {
    (State.UNAVAILABLE, State.OFFLINE_QUALIFIED): "offline qualification passed",
    (State.OFFLINE_QUALIFIED, State.LIVE_SHADOW_ELIGIBLE): "shadow scope review passed",
    (State.LIVE_SHADOW_ELIGIBLE, State.LIVE_SHADOW_ACTIVE): "campaign opened",
    (State.LIVE_SHADOW_ACTIVE, State.LIVE_SHADOW_CLOSED): "campaign closed",
    # A campaign that stopped for any reason returns to ELIGIBLE, not to
    # UNAVAILABLE: the runtime is still offline-qualified and the scope review
    # still holds; only the run ended.
    (State.LIVE_SHADOW_ACTIVE, State.LIVE_SHADOW_ELIGIBLE): "campaign stopped without closure",
}

AUTHORITY_STATES = (State.AUTHORITY_ELIGIBLE, State.AUTHORITY_APPROVED)
ALL_STATES = tuple(dict.fromkeys(
    [State.UNAVAILABLE, State.OFFLINE_QUALIFIED, State.LIVE_SHADOW_ELIGIBLE,
     State.LIVE_SHADOW_ACTIVE, State.LIVE_SHADOW_CLOSED, *AUTHORITY_STATES]))


class IllegalTransition(ValueError):
    """Raised for any transition not in ALLOWED, including every authority edge."""


class AuthorityTransitionRefused(IllegalTransition):
    """Raised specifically when an authority state is requested.

    A distinct type so a caller cannot catch the generic refusal and treat a
    refused authority jump as an ordinary mistake: it is the one transition
    this machine exists to make impossible.
    """


class ShadowCapability:
    """One capability's lifecycle. Transitions are recorded as evidence."""

    def __init__(self, name: str, state: str = State.UNAVAILABLE):
        if state not in ALL_STATES:
            raise ValueError("unknown state: " + str(state))
        self.name = name
        self._state = state
        self.history: list[dict] = [{
            "from": None, "to": state, "at": time.time(),
            "reason": "initial", "authority": False,
        }]

    @property
    def state(self) -> str:
        return self._state

    def transition(self, target: str, reason: str) -> dict:
        """Move to `target` or raise. Never coerces; never returns a different
        state than the one asked for."""
        if target not in ALL_STATES:
            raise IllegalTransition("unknown state: " + str(target))
        if target in AUTHORITY_STATES:
            raise AuthorityTransitionRefused(
                "%s: %s -> %s refused: authority is not reachable from the shadow "
                "state machine; it requires a separate reviewed approval"
                % (self.name, self._state, target))
        edge = (self._state, target)
        if edge not in ALLOWED:
            raise IllegalTransition(
                "%s: %s -> %s is not an allowed transition (allowed: %s)"
                % (self.name, self._state, target,
                   ", ".join("%s->%s" % e for e in sorted(ALLOWED))))
        self._state = target
        record = {"from": edge[0], "to": target, "at": time.time(),
                  "reason": reason, "authority": False}
        self.history.append(record)
        return record

    # Convenience wrappers that make intent readable at call sites, and make
    # an illegal jump impossible to write by accident.

    def offline_qualified(self, reason: str) -> dict:
        return self.transition(State.OFFLINE_QUALIFIED, reason)

    def shadow_eligible(self, reason: str) -> dict:
        return self.transition(State.LIVE_SHADOW_ELIGIBLE, reason)

    def shadow_active(self, reason: str) -> dict:
        return self.transition(State.LIVE_SHADOW_ACTIVE, reason)

    def shadow_closed(self, reason: str) -> dict:
        return self.transition(State.LIVE_SHADOW_CLOSED, reason)
