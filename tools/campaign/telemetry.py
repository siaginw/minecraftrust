"""File-based campaign telemetry (goal §15): JSONL events per session, plus
a tiny snapshot-format parser for the Java-side periodic metrics dump."""
from __future__ import annotations

import json
import time
from pathlib import Path


class JsonlEvents:
    """Windows-safe append-only JSONL event writer."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def emit(self, event: str, **fields) -> None:
        record = {"ts": round(time.time(), 3), "event": event}
        record.update(fields)
        # open/append/close per event: robust against concurrent readers on
        # Windows and against crashes losing the whole buffer
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, sort_keys=True) + "\n")


def tail_events(path: Path, limit: int = 200) -> list[dict]:
    """Read the last `limit` events from a JSONL file (best-effort)."""
    try:
        lines = Path(path).read_text(encoding="utf-8", errors="replace")\
            .splitlines()
    except OSError:
        return []
    out = []
    for line in lines[-limit:]:
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def parse_metrics_snapshot(path: Path) -> dict:
    """Parse the Java-side periodic metrics snapshot (key=value lines,
    emitted every few seconds by the region hooks' dumper thread)."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    metrics: dict = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
    # key=value per line; values stay strings (caller compares numerically)
    for line in text.splitlines():
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            metrics[k.strip()] = v.strip()
    return metrics
