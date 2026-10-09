"""Bounded wait primitives (goal §9). No busy loops, no scattered sleeps:
~0.25 s polling for log/process readiness, ~0.5 s for slower evidence."""
from __future__ import annotations

import time
from typing import Callable, TypeVar

T = TypeVar("T")

FAST_POLL_S = 0.25
SLOW_POLL_S = 0.5


def wait_for_condition(description: str, predicate: Callable[[], T | None],
                       timeout_s: float, poll_s: float = SLOW_POLL_S,
                       ) -> T | None:
    """Poll `predicate` until it returns a non-None value or the ceiling
    expires (returns the value, or None on timeout)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        value = predicate()
        if value is not None:
            return value
        time.sleep(poll_s)
    return None


def wait_for_log(log_path, pattern: str, timeout_s: float,
                 process=None, poll_s: float = FAST_POLL_S) -> bool:
    """Poll a log file for a regex pattern (bounded)."""
    import re
    compiled = re.compile(pattern)
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            return False
        try:
            if compiled.search(log_path.read_text(encoding="utf-8", errors="replace")):
                return True
        except OSError:
            pass
        time.sleep(poll_s)
    return False


def hold_stability(seconds: float, verify: Callable[[], bool] | None = None,
                   poll_s: float = 1.0) -> bool:
    """Post-evidence stability window: hold `seconds` while `verify` (when
    given) keeps returning True. Returns False if verification failed."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if verify is not None and not verify():
            return False
        time.sleep(min(poll_s, max(0.0, deadline - time.monotonic())))
    return verify() if verify is not None else True


def wait_for_process_exit(process, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return True
        time.sleep(FAST_POLL_S)
    return process.poll() is not None
