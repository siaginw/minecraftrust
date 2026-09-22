# Evidence Provenance System

## Purpose

Every numeric result reported in this repository must classify itself and
point to reproducible raw evidence. No final milestone number may exist only
in prose.

## Classification Levels

| Class | Meaning |
|-------|---------|
| MEASURED | Direct instrument observation (benchmark run, harness output) |
| DERIVED_FROM_MEASURED | Computed from MEASURED raw samples (percentiles, means) |
| PROJECTED | Estimated from measured data, not directly observed (extrapolation) |
| SYNTHETIC | Generated values, not from a real system run |
| ASSUMED | Constants adopted without measurement |

SYNTHETIC and ASSUMED values must never be cited as performance results.
They may only appear as placeholders explicitly awaiting measurement.

## Required MEASURED Provenance Fields

Every MEASURED entry in `machine/evidence-provenance.yaml` must record:

- tool: instrument that produced the observation
- command: exact reproduction command
- git_commit: commit the measurement ran against
- raw_artifact: path to raw sample data (CSV/JSONL)
- timestamp: ISO-8601 UTC
- machine: hardware + OS
- environment: JVM/Rust/Forge/MC versions
- sample_count: number of raw observations
- scope: what the number covers and excludes

## Machine-Readable Registry

`machine/evidence-provenance.yaml` is the authoritative registry. Reports and
milestone exits cite registry entry IDs, not inline numbers.

## Enforcement

`tools/verify_evidence_integrity.py` (M1.4-R) checks:

1. Report numbers without provenance entries → FAIL
2. Raw artifacts referenced but missing from disk/git → FAIL
3. Timing clamps or hardcoded benchmark outputs in tools → FAIL
4. "Real pack" claims without installed pack metadata → FAIL
5. Dirty working tree during milestone finalization → FAIL

Run: `python tools/verify_evidence_integrity.py`
Exit 0 = clean; non-zero = violations listed on stdout.
