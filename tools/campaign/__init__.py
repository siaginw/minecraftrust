"""RustCraft shared campaign toolkit.

One home for the primitives every live campaign needs: duration tiers
(policy), server session management (session), bounded wait primitives
(waits), an evidence condition model (evidence), restart loops (restart),
machine-readable receipts (receipt), and file-based telemetry (telemetry).

Rules (AGENTS.md, docs/engineering/TEST_EXECUTION_POLICY.md):
  TIME IS NOT COVERAGE. EVENTS ARE EVIDENCE.
  A TIMEOUT IS A CEILING, NOT A TARGET.
  Campaigns terminate when their evidence targets are met + a short
  stability window passes. SOAK is explicit and justified.
"""

from .policy import Tier, TIERS, DEFAULT_TIER, get_tier, resolve, add_tier_argument
from .waits import (
    wait_for_log, wait_for_condition, hold_stability, wait_for_process_exit,
)
from .evidence import (
    CounterAtLeast, CounterEquals, ZeroCounter, LogPatternSeen, Predicate,
    ALL, ANY, EvidenceTracker,
)
from .session import MinecraftServerSession
from .receipt import write_receipt
from .telemetry import JsonlEvents, tail_events
from .restart import run_restart_cycles

__all__ = [
    "Tier", "TIERS", "DEFAULT_TIER", "get_tier", "resolve", "add_tier_argument",
    "wait_for_log", "wait_for_condition", "hold_stability", "wait_for_process_exit",
    "CounterAtLeast", "CounterEquals", "ZeroCounter", "LogPatternSeen", "Predicate",
    "ALL", "ANY", "EvidenceTracker",
    "MinecraftServerSession",
    "write_receipt",
    "JsonlEvents", "tail_events",
    "run_restart_cycles",
]
