"""Self-test for EvidenceDB (no Minecraft)."""
import sys
import tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from campaign.evidence_db import EvidenceDB

def test_accumulate_and_skip():
    with tempfile.TemporaryDirectory() as td:
        db = EvidenceDB(Path(td) / "ev.db")
        sha = "abc123"
        # run 1: 8000 reads
        db.record_run("region", "C", "SHADOW", sha,
                      {"regionRead.readSuccess": 8000, "regionRead.shadowMismatch": 0})
        met, total = db.cumulative_target_met("region", "C", "SHADOW", sha,
                                              "regionRead.readSuccess", 20000)
        assert not met and total == 8000
        assert not db.already_evidenced("region", "C", "SHADOW", sha,
                                        "regionRead.readSuccess", 20000)[0]
        # run 2: 12000 more -> cumulative 20000 met
        db.record_run("region", "C", "SHADOW", sha,
                      {"regionRead.readSuccess": 12000, "regionRead.shadowMismatch": 0})
        met, total = db.cumulative_target_met("region", "C", "SHADOW", sha,
                                              "regionRead.readSuccess", 20000)
        assert met and total == 20000
        # different sha -> isolated (code changed: floors reset)
        met2, total2 = db.cumulative_target_met("region", "C", "SHADOW", "other",
                                                "regionRead.readSuccess", 20000)
        assert not met2 and total2 == 0
        # skip-if-evidenced: single run meeting floor + zero-mismatch guard
        db.record_run("region", "C", "ON", sha,
                      {"regionRead.readSuccess": 9000, "regionRead.partialStreamAttempts": 0})
        ok, n = db.already_evidenced("region", "C", "ON", sha,
                                     "regionRead.readSuccess", 8000,
                                     required_zeros=("regionRead.partialStreamAttempts",))
        assert ok and n == 9000
        # a run with a nonzero guarded counter never qualifies
        db.record_run("region", "C", "ON", sha,
                      {"regionRead.readSuccess": 9500, "regionRead.partialStreamAttempts": 2})
        ok2, _ = db.already_evidenced("region", "C", "ON", sha,
                                      "regionRead.readSuccess", 8000,
                                      required_zeros=("regionRead.partialStreamAttempts",))
        assert ok2  # earlier clean run still qualifies (most-recent-first scan)
        db.close()
    db.close()
    print("EVIDENCE_DB_TESTS_PASSED")

test_accumulate_and_skip()
