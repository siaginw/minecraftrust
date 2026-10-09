"""Evidence condition model (goal §10).

Conditions evaluate against a metrics provider (a callable returning a dict
of the latest counters, e.g. parsed from the campaign's periodic metrics
snapshot) and optionally the server log text.

Composition: ALL(...) / ANY(...). Campaigns wait until ALL targets hold,
hold the tier stability window, re-verify, and terminate (goal §10/§11).
"""
from __future__ import annotations

from typing import Callable, Iterable


class Condition:
    name: str

    def check(self, metrics: dict, log_text: str) -> bool:
        raise NotImplementedError

    def describe(self, metrics: dict) -> str:
        return self.name


class CounterAtLeast(Condition):
    def __init__(self, counter: str, minimum: int):
        self.name = f"{counter} >= {minimum}"
        self.counter = counter
        self.minimum = minimum

    def check(self, metrics: dict, log_text: str) -> bool:
        return int(metrics.get(self.counter, 0)) >= self.minimum

    def describe(self, metrics: dict) -> str:
        return f"{self.counter}={metrics.get(self.counter, 0)} / {self.minimum}"


class CounterEquals(Condition):
    def __init__(self, counter: str, value: int):
        self.name = f"{counter} == {value}"
        self.counter = counter
        self.value = value

    def check(self, metrics: dict, log_text: str) -> bool:
        return int(metrics.get(self.counter, -1)) == self.value

    def describe(self, metrics: dict) -> str:
        return f"{self.counter}={metrics.get(self.counter, '?')} / {self.value}"


class ZeroCounter(Condition):
    def __init__(self, counter: str):
        self.name = f"{counter} == 0"
        self.counter = counter

    def check(self, metrics: dict, log_text: str) -> bool:
        return int(metrics.get(self.counter, 0)) == 0

    def describe(self, metrics: dict) -> str:
        return f"{self.counter}={metrics.get(self.counter, 0)}"


class LogPatternSeen(Condition):
    def __init__(self, pattern: str):
        import re
        self.name = f"log~/{pattern}/"
        self.regex = re.compile(pattern)

    def check(self, metrics: dict, log_text: str) -> bool:
        return self.regex.search(log_text) is not None


class Predicate(Condition):
    def __init__(self, name: str, fn: Callable[[dict, str], bool]):
        self.name = name
        self.fn = fn

    def check(self, metrics: dict, log_text: str) -> bool:
        return self.fn(metrics, log_text)


class _Composite(Condition):
    def __init__(self, op: str, conditions: Iterable[Condition]):
        self.conditions = list(conditions)
        self.op = op
        self.name = f" {op} ".join(c.name for c in self.conditions)


class ALL(_Composite):
    def __init__(self, conditions: Iterable[Condition]):
        super().__init__("AND", conditions)

    def check(self, metrics: dict, log_text: str) -> bool:
        return all(c.check(metrics, log_text) for c in self.conditions)


class ANY(_Composite):
    def __init__(self, conditions: Iterable[Condition]):
        super().__init__("OR", conditions)

    def check(self, metrics: dict, log_text: str) -> bool:
        return any(c.check(metrics, log_text) for c in self.conditions)


class EvidenceTracker:
    """Tracks when a target condition first became satisfied (so the
    stability window can be timed from the evidence point, goal §10)."""

    def __init__(self, condition: Condition,
                 metrics_provider: Callable[[], dict],
                 log_provider: Callable[[], str]):
        self.condition = condition
        self.metrics_provider = metrics_provider
        self.log_provider = log_provider
        self.satisfied_at: float | None = None

    def poll(self, now: float) -> bool:
        ok = self.condition.check(self.metrics_provider(), self.log_provider())
        if ok and self.satisfied_at is None:
            self.satisfied_at = now
        elif not ok:
            self.satisfied_at = None  # evidence regressed: restart the window
        return ok

    def summary(self) -> str:
        metrics = self.metrics_provider()
        if isinstance(self.condition, _Composite):
            return "; ".join(c.describe(metrics) for c in self.condition.conditions)
        return self.condition.describe(metrics)
