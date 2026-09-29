# Documentation System

## Documentation Types

### `docs/architecture/`
Permanent design.

### `docs/learned/`
Facts discovered from studying Minecraft, Forge, mods, or benchmarks.

### `docs/migrations/`
Per-subsystem migration plans and postmortems.

### `docs/adr/`
Architecture Decision Records.

### `docs/benchmarks/`
Benchmark methodology and summarized results.

### `docs/compatibility/`
Mod/coremod compatibility notes.

## Required ADR Format

```markdown
# ADR-XXXX: Title

## Status
Proposed / Accepted / Replaced / Rejected

## Context

## Decision

## Alternatives

## Performance Impact

## Compatibility Impact

## Migration Impact

## Consequences
```

## Learned Document Rule

Whenever the agent learns a behavior that could affect future implementation, it must write it down.

Example:

```text
Finding:
Chunk save order matters for X because Y.

Evidence:
source file / method / test

Impact:
Rust region writer must preserve Z.

Test:
parity/chunk_save_014
```

## No Knowledge Only in Chat

Important project knowledge must be persisted into repository docs.
