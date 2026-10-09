## What

One or two sentences: what this PR changes.

## Evidence

What proves the change is correct — test names run, campaign receipts, benchmark outputs. For docs: what claims were verified against. ("Looks right" is not evidence.)

## Scope check

- [ ] No change to Java-authoritative behavior or fail-closed authority gates (`tryEncode` → `null`, `productionAuthorityEligible()` → `false`)
- [ ] No proprietary game artifacts committed
- [ ] No unexplained parity claims (component benchmarks labeled as component benchmarks)
- [ ] No `git add .` — explicit paths only
