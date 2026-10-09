#!/usr/bin/env python3
"""Qualify a profile from fresh, manifest-driven observations.

This entry point delegates all qualification to QualificationEngine. Embedded
historical observations and CANONICAL_ID_V1 cannot issue new certificates.
Use the explicitly historical recheck harness to reproduce old V1 receipts.
"""
from __future__ import annotations

try:
    from tools.testing.qualification_engine import main
except ModuleNotFoundError:
    from qualification_engine import main


if __name__ == "__main__":
    raise SystemExit(main())
