"""Sqlite evidence accumulation (goal: streamline long evidence floors).

Instead of one long campaign to hit a big floor (e.g. 20,000 shadow reads),
short runs ACCUMULATE: every finished run appends its counters keyed by
(campaign, gate, git_sha, mode), and `cumulative_target_met` checks the SUM
across runs built from the SAME code. `already_evidenced` lets a runner
short-circuit entirely when a prior run with the same signature already met
the floor — the sqlite equivalent of ccache/testcontainers-reuse.

Windows-safe: sqlite3 stdlib, WAL not required (single writer).
"""
from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    campaign TEXT NOT NULL,
    gate TEXT NOT NULL,
    mode TEXT NOT NULL,
    git_sha TEXT NOT NULL,
    tier TEXT,
    verdict TEXT,
    counters_json TEXT NOT NULL,
    receipt_path TEXT
);
CREATE INDEX IF NOT EXISTS idx_runs_key ON runs(campaign, gate, mode, git_sha);
"""


class EvidenceDB:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.path))
        self.conn.executescript(_SCHEMA)
        self.conn.commit()

    def record_run(self, campaign: str, gate: str, mode: str, git_sha: str,
                   counters: dict, tier: str = "", verdict: str = "",
                   receipt_path: str = "") -> int:
        cur = self.conn.execute(
            "INSERT INTO runs (ts, campaign, gate, mode, git_sha, tier, verdict,"
            " counters_json, receipt_path) VALUES (?,?,?,?,?,?,?,?,?)",
            (time.time(), campaign, gate, mode, git_sha, tier, verdict,
             json.dumps(counters, sort_keys=True), receipt_path))
        self.conn.commit()
        return cur.lastrowid

    def _sum(self, counter: str, campaign: str, gate: str, mode: str,
             git_sha: str) -> int:
        cur = self.conn.execute(
            "SELECT counters_json FROM runs WHERE campaign=? AND gate=? AND "
            "mode=? AND git_sha=?", (campaign, gate, mode, git_sha))
        total = 0
        for (cj,) in cur.fetchall():
            counters = json.loads(cj)
            total += int(counters.get(counter, 0))
        return total

    def cumulative_target_met(self, campaign: str, gate: str, mode: str,
                              git_sha: str, counter: str, minimum: int) -> tuple[bool, int]:
        total = self._sum(counter, campaign, gate, mode, git_sha)
        return total >= minimum, total

    def already_evidenced(self, campaign: str, gate: str, mode: str,
                          git_sha: str, counter: str, minimum: int,
                          required_zeros: tuple[str, ...] = ()) -> tuple[bool, int]:
        """True if a SINGLE prior run with this exact signature met the
        floor (and every required-zero counter was zero in that run)."""
        cur = self.conn.execute(
            "SELECT counters_json, receipt_path FROM runs WHERE campaign=? "
            "AND gate=? AND mode=? AND git_sha=? ORDER BY id DESC",
            (campaign, gate, mode, git_sha))
        for (cj, rp) in cur.fetchall():
            counters = json.loads(cj)
            if int(counters.get(counter, 0)) >= minimum and all(
                    int(counters.get(z, 1)) == 0 for z in required_zeros):
                return True, int(counters.get(counter, 0))
        return False, 0

    def recent_runs(self, campaign: str, gate: str, mode: str,
                    git_sha: str) -> list[dict]:
        cur = self.conn.execute(
            "SELECT ts, verdict, counters_json, receipt_path FROM runs WHERE "
            "campaign=? AND gate=? AND mode=? AND git_sha=? ORDER BY id DESC",
            (campaign, gate, mode, git_sha))
        return [{"ts": ts, "verdict": v,
                 "counters": json.loads(cj), "receipt": rp}
                for ts, v, cj, rp in cur.fetchall()]

    def close(self):
        self.conn.close()
