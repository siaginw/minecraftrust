# History rewrite — 2026-10-09

At the operator's request, `main` was squashed from 372 granular commits into
3 cumulative commits (Rust engine core → Java bridge & tooling → docs &
repo guidance). The rewritten tree is byte-identical to the pre-squash tree.

Consequences for readers:

- **Commit SHAs cited in research receipts, audits, and backlogs**
  (e.g. `4c2dec3`, `80087fc`, `0f95b80`, `2bf3407`, `9dd4ec9`) refer to the
  PRE-SQUASH history line. They are preserved verbatim as historical
  evidence and are no longer reachable from `main`.
- The full pre-squash history is retained in the local branch
  `history/pre-squash-2026-10-09` and the bundle
  `target/rustcraft-history-pre-squash-2026-10-09.bundle` (gitignored path,
  machine-local). Archive tags on the remote (`archive/*`,
  `rustcraft/*`) still resolve the commits they name where applicable.
- The repo convention going forward remains granular conventional commits
  (AGENTS.md); this rewrite was a one-time display cleanup, not a policy
  change.

If a receipt needs to pin provenance after this date, cite run IDs and
artifact hashes (sha256-16) as the receipts already do — those are
independent of git history.
