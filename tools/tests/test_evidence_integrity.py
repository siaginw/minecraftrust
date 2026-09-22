#!/usr/bin/env python3
"""Regression tests for the M1-final strengthened evidence integrity checker.

The critical case: machine/evidence-provenance.yaml shipped YAML-INVALID from
2026-09-18 to 2026-09-19 (TARGETA-* MEASURED entries mis-indented into the
`invalidated:` sequence; M14R2-* entries appended as mapping keys after it)
while tools/verify_evidence_integrity.py reported "clean", because it only
regex-scanned the text instead of parsing. These tests pin the strengthened
behavior.

Run:  python tools/tests/test_evidence_integrity.py   (stdlib unittest only)
"""
import os
import sys
import unittest

TOOLS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, TOOLS)

import verify_evidence_integrity as vei  # noqa: E402

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")

# Verbatim structure of the broken registry committed 2026-09-18: a valid
# entries sequence, an invalidated sequence that accidentally swallowed three
# MEASURED entries, then M14R2 mapping keys appended INSIDE the sequence.
BROKEN_YAML = """\
version: 1
entries:
  - id: "E1"
    class: MEASURED
    claim: "ok"
    git_commit: "7476105"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    raw_artifact_sha256: "%s"
    timestamp: "2026-09-18"
    sample_count: 1
invalidated:
  - id: "M13-PARITY-100K"
    reason: "shared semantics"
    artifact: "machine/M1.3-shadow-results.yaml"
  - id: "TARGETA-SHADOW-PARITY-LIVE-10K"
    class: MEASURED
    claim: "swallowed valid entry"
    git_commit: "ee1b646"
    raw_artifact: "machine/targetA/shadow-parity10k-final-metrics.txt"
    raw_artifact_sha256: "x"
    timestamp: "2026-09-18"
    sample_count: 11259
  M14R2-HANDOFF-A-FULLCORPUS:
    artifact: "machine/raw/M14R2-fullcorpus-deep.csv"
    git_commit: "pending"
""" % ("0" * 64)

DUPLICATE_KEYS_YAML = """\
version: 1
entries: []
extra: 1
extra: 2
"""

MEASURED_NO_HASH = """\
version: 1
entries:
  - id: "E2"
    class: MEASURED
    claim: "no hash"
    git_commit: "7476105"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    timestamp: "2026-09-18"
    sample_count: 5
"""

PENDING_COMMIT = """\
version: 1
entries:
  - id: "E3"
    class: MEASURED
    claim: "pending commit"
    status: valid
    git_commit: "pending"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    timestamp: "2026-09-18"
    sample_count: 5
"""

HASH_MISMATCH = """\
version: 1
entries:
  - id: "E4"
    class: MEASURED
    claim: "wrong hash"
    git_commit: "7476105"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    raw_artifact_sha256: "%s"
    timestamp: "2026-09-18"
    sample_count: 5
""" % ("f" * 64)

AWAITING_STATUS = """\
version: 1
entries:
  - id: "E5"
    class: MEASURED
    claim: "awaiting"
    status: awaiting_measurement
    git_commit: "7476105"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    raw_artifact_sha256: "x"
    timestamp: "2026-09-18"
    sample_count: 5
"""

VALID_MINIMAL = """\
version: 2
entries:
  - id: "V1"
    class: ASSUMED
    claim: "placeholder is allowed"
    status: superseded
    superseded_by: "V2"
  - id: "V2"
    class: MEASURED
    claim: "complete entry"
    git_commit: "7476105"
    raw_artifact: "machine/raw/M14R-cargo-test.txt"
    raw_artifact_sha256: "%s"
    timestamp: "2026-09-18"
    sample_count: 19
invalidated:
  - id: "OLD"
    reason: "superseded semantics"
    artifact: "machine/M1.3-shadow-results.yaml"
"""


class TestProvenanceValidation(unittest.TestCase):
    """Unit tests against validate_provenance_text with in-memory YAML."""

    def _run(self, text):
        return vei.validate_provenance_text(text, vei.ROOT if hasattr(vei, "ROOT") else ".")

    def test_broken_yaml_rejected(self):
        """The exact 2026-09-18 broken registry MUST now be rejected.

        Historical bug: this content passed the regex-based checker."""
        violations = self._run(BROKEN_YAML)
        self.assertTrue(any("PROVENANCE_INVALID_YAML" in v for v in violations),
                        "broken YAML must fail structural parse, got: %r" % violations)

    def test_duplicate_keys_rejected(self):
        violations = self._run(DUPLICATE_KEYS_YAML)
        self.assertTrue(any("duplicate key" in v for v in violations), violations)

    def test_measured_requires_sha256(self):
        violations = self._run(MEASURED_NO_HASH)
        self.assertTrue(any("RAW_HASH_MISSING" in v for v in violations), violations)

    def test_pending_commit_rejected(self):
        violations = self._run(PENDING_COMMIT)
        self.assertTrue(any("PENDING_PROVENANCE" in v for v in violations), violations)

    def test_hash_mismatch_rejected(self):
        violations = self._run(HASH_MISMATCH)
        self.assertTrue(any("RAW_HASH_MISMATCH" in v for v in violations), violations)

    def test_awaiting_status_rejected(self):
        violations = self._run(AWAITING_STATUS)
        self.assertTrue(any("PENDING_PROVENANCE" in v for v in violations), violations)

    def test_valid_minimal_passes_schema(self):
        # hash of the real artifact is required; compute it
        import hashlib
        p = os.path.join(vei.ROOT, "machine", "raw", "M14R-cargo-test.txt")
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        violations = self._run(VALID_MINIMAL % h)
        self.assertEqual([v for v in violations if not v.startswith(("INVALIDATED_AS_CURRENT",
                                                                     "ACTIVE_EVIDENCE"))],
                         [], violations)


class TestLiveRegistry(unittest.TestCase):
    """The real machine/evidence-provenance.yaml must parse and validate."""

    def test_live_registry_clean(self):
        vei_violations = vei.audit_provenance()
        self.assertEqual(vei_violations, [], vei_violations)

    def test_broken_fixture_file_rejected(self):
        """Fixture-on-disk variant of the historical broken registry."""
        broken = os.path.join(FIXTURES, "broken-provenance-2026-09-18.yaml")
        if not os.path.isfile(broken):
            self.skipTest("fixture not present")
        with open(broken, encoding="utf-8") as f:
            violations = vei.validate_provenance_text(f.read(), vei.ROOT)
        self.assertTrue(any("PROVENANCE_INVALID_YAML" in v for v in violations), violations)


if __name__ == "__main__":
    unittest.main(verbosity=2)
