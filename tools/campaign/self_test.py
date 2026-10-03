"""Campaign toolkit self-tests (goal §26-§27): no Minecraft required.

Run:  python tools/campaign/self_test.py
Covers: tier ordering/defaults, SOAK configuration guard, evidence
conditions, stability window with re-verify, receipt writing/exit reasons,
telemetry append/tail, metrics snapshot parsing, restart loop with fake
sessions (first_last/every/fail_fast), event-driven completion semantics.
"""
from __future__ import annotations

import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))

from campaign.evidence import ALL, CounterAtLeast, LogPatternSeen, ZeroCounter, EvidenceTracker
from campaign.policy import SoakConfigurationError, resolve_campaign
from campaign.receipt import write_receipt
from campaign.restart import run_restart_cycles
from campaign.session import MinecraftServerSession
from campaign.telemetry import JsonlEvents, parse_metrics_snapshot
from campaign.waits import hold_stability, wait_for_condition


def test_tiers_and_soak_guard():
    t = resolve_campaign("standard")
    assert t.post_target_stability_s == 15 and t.hard_timeout_s == 120
    try:
        resolve_campaign("soak", soak_seconds=None, soak_reason=None)
        raise AssertionError("soak without config must fail")
    except SoakConfigurationError:
        pass
    t2 = resolve_campaign("soak", soak_seconds=900, soak_reason="leak hunt")
    assert t2.soak_s == 900
    print("ok tiers_and_soak_guard")


def test_evidence_conditions():
    metrics = {"readSelected": "5000", "shadowMismatch": "0", "partialStreamAttempts": "0"}
    cond = ALL([CounterAtLeast("readSelected", 2000),
                ZeroCounter("shadowMismatch"),
                ZeroCounter("partialStreamAttempts")])
    assert cond.check(metrics, "")
    metrics["shadowMismatch"] = "3"
    assert not cond.check(metrics, "")
    assert LogPatternSeen(r"Done \(").check({}, "[12:00] Done (34.5s)!")
    print("ok evidence_conditions")


def test_evidence_tracker_window():
    state = {"n": 0}
    tracker = EvidenceTracker(CounterAtLeast("n", 10), lambda: {"n": str(state["n"])},
                              lambda: "")
    assert tracker.poll(time.monotonic()) is False
    state["n"] = 12
    now = time.monotonic()
    assert tracker.poll(now) is True
    assert tracker.satisfied_at is not None
    # regression resets the window
    state["n"] = 5
    assert tracker.poll(now + 1) is False
    assert tracker.satisfied_at is None
    print("ok evidence_tracker_window")


def test_stability_with_reverify():
    flips = {"ok": True}
    assert hold_stability(0.5, verify=lambda: flips["ok"], poll_s=0.1)
    flips["ok"] = False
    assert not hold_stability(0.5, verify=lambda: flips["ok"], poll_s=0.1)
    print("ok stability_with_reverify")


def test_event_driven_completion_is_early():
    """Evidence reached quickly -> wait_for_condition returns well before a
    long ceiling (goal §6: timeout is a ceiling, not a duration)."""
    state["n"] = 12  # evidence already present: completion must be immediate
    t0 = time.monotonic()
    v = wait_for_condition("fast evidence", lambda: "done" if state["n"] >= 5 else None,
                           timeout_s=60, poll_s=0.05)
    assert v == "done" and time.monotonic() - t0 < 5
    state["n"] = 0
    print("ok event_driven_completion_is_early")
    # timeout path returns None at the ceiling (short ceiling here)
    t0 = time.monotonic()
    v = wait_for_condition("never", lambda: None, timeout_s=0.5, poll_s=0.05)
    assert v is None and 0.4 < time.monotonic() - t0 < 2.0
    print("ok timeout_is_ceiling")


state = {"n": 0}


def test_receipt_and_telemetry():
    with tempfile.TemporaryDirectory() as td:
        p = write_receipt(Path(td), campaign_name="unit", target="A", mode="SHADOW",
                          test_tier="dev", exit_reason="EVIDENCE_COMPLETE",
                          verdict="PASS", wall_time_s=3.2)
        import json
        doc = json.loads(p.read_text())
        assert doc["verdict"] == "PASS" and doc["exit_reason"] == "EVIDENCE_COMPLETE"
        assert doc["schema_version"] == 1
        ev = JsonlEvents(Path(td) / "events.jsonl")
        ev.emit("region_read", status="success", count=2001)
        ev.emit("campaign_target", name="read_floor", met=True)
        from campaign.telemetry import tail_events
        events = tail_events(Path(td) / "events.jsonl")
        assert len(events) == 2 and events[0]["event"] == "region_read"
        snap = Path(td) / "metrics.txt"
        snap.write_text("regionRead.readSelected=5000\nregionRead.shadowMismatch=0\n")
        m = parse_metrics_snapshot(snap)
        assert m["regionRead.readSelected"] == "5000"
    print("ok receipt_and_telemetry")


class FakeSession:
    """Fake process session exercising restart orchestration (no Minecraft)."""

    def __init__(self, behavior):
        self.behavior = behavior
        self.stopped = False
        self.killed = False

    def start(self):
        self.behavior["boots"] = self.behavior.get("boots", 0) + 1
        return self.behavior.get("boot_ok", True)

    def stop(self, timeout_s=None):
        self.stopped = True
        return True

    def kill(self):
        self.killed = True


def test_restart_cycles_fake():
    behavior = {"boot_ok": True}
    made = []
    def make_session(cycle):
        s = FakeSession(behavior)
        made.append(s)
        return s
    result = run_restart_cycles(make_session, cycles=3, probe_mode="none",
                                tier=resolve_campaign("dev"))
    assert result["cycles_ok"] == 3 and not result["failures"]
    assert all(s.stopped for s in made)
    # probe on first_last: probe_fn counts
    probes = {"calls": 0}
    def probe_fn(cycle):
        probes["calls"] += 1
        return True
    result = run_restart_cycles(make_session, cycles=3, probe_mode="first_last",
                                probe_fn=probe_fn, tier=resolve_campaign("dev"))
    assert probes["calls"] == 2 and result["probes_ok"] == 2
    # boot failure with fail_fast stops early
    behavior["boot_ok"] = False
    result = run_restart_cycles(make_session, cycles=5, probe_mode="none",
                                tier=resolve_campaign("dev"))
    assert result["cycles_ok"] == 0 and len(result["failures"]) == 1
    print("ok restart_cycles_fake")


def test_session_context_manager_never_orphans():
    """A non-started/failed session still cleans up (no Popen leak)."""
    with tempfile.TemporaryDirectory() as td:
        s = MinecraftServerSession(argv=["cmd"], working_dir=Path(td),
                                   log_path=Path(td) / "log.txt", boot_timeout_s=0.2)
        with s:
            started = s.start()  # cmd is not a real server; Done never appears
            assert not started
        assert s._log_handle is None  # log closed via stop()
    print("ok session_context_manager_never_orphans")


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
    print("CAMPAIGN_TOOLING_SELF_TEST_PASSED")
