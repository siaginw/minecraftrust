# Code Graph and Repository Navigation

## Goal

RustCraft should not rely on grep alone for a project this large.

the developer should maintain a searchable model of:
- symbols
- references
- call relationships
- ownership
- module boundaries
- tests
- docs
- Java <-> Rust bridge contracts

## Graph Tooling

If using Graphy or another code-graph/indexing tool, integrate it as a helper, not as the source of truth.

Desired capabilities:
- index Java and Rust
- symbol lookup
- reference lookup
- call graph
- dependency graph
- changed-files impact analysis
- cross-repo navigation if possible
- MCP or CLI access from RustCraft

## Required Agent Behavior

Before a multi-file migration:
1. inspect ownership manifest
2. query code graph for callers/callees
3. inspect tests
4. inspect docs
5. identify FFI boundaries
6. produce impact summary
7. only then edit

After the change:
1. re-index
2. inspect unexpected dependency growth
3. verify boundary crossings
4. update ownership and docs

## Hooks

Recommended hooks:

### Pre-task
- refresh index if stale
- load project charter
- load ownership manifest
- load subsystem docs

### Pre-verify
- run code graph impact scan
- run formatter/lints
- run unit tests
- run parity tests
- run benchmarks required by changed subsystem

### Post-task
- update learned docs
- update ownership manifest
- update subsystem status
- record benchmark delta

## Important

Do not hard-code the project around one graph vendor.

Define a thin `CodeGraphProvider` concept in agent instructions so Graphy can be replaced later.
