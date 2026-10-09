# Documentation index

Everything worth reading, organized by intent.

## Start here

| Document | For |
|:---|:---|
| [README](../README.md) | The public front page — what RustCraft is, what is proven, where it is going |
| [PROJECT_STATUS](PROJECT_STATUS.md) | **The authoritative status snapshot** — every qualification and campaign result, with receipts and claim hygiene |
| [ARCHITECTURE](ARCHITECTURE.md) | How the system is built: the compatibility shell, the migration ladder, the subsystem map |
| [ROADMAP](ROADMAP.md) | The nine phases from proof infrastructure to a Rust-hosted Java runtime |

## Deep research

| Collection | Contents |
|:---|:---|
| [RESEARCH_INDEX](RESEARCH_INDEX.md) | Thematic index into the 82 research documents |
| [`research/`](research/) | The research trail: qualification design, live-shadow architecture, subsystem seams, snapshot transport, protocol |
| [`compatibility/`](compatibility/) | 18 studies of the real 1.12.2/Forge surface |
| [`engineering/`](engineering/) | Hardening reports, evidence provenance, benchmark scopes, qualification engine design |
| [`architecture/`](architecture/) | Reference models: boot flow, chunk/network/world ownership, capabilities |
| [`protocol-340/`](protocol-340/) | Wire-level protocol work |
| [`schemas/`](schemas/) | Machine-readable schemas |
| [`migrations/`](migrations/), [`adr/`](adr/), [`learned/`](learned/), [`tooling/`](tooling/), [`benchmarks/`](benchmarks/) | Migration notes, decisions, learning notes, tooling docs, benchmark records |

## Historical planning

[`foundation/`](foundation/) — the original project charter (01), architecture notes (02), stage plan (03), curriculum (04), boundary rules (06), benchmarking methodology (07), crate references (08), code-graph rules (09), documentation system (10), and initial skeleton (11). Preserved as the record of the project's reasoning; superseded in status by PROJECT_STATUS and in plan by ROADMAP.

## Evidence

- [`../machine/`](../machine/) — committed YAML evidence manifests per milestone
- Campaign receipts — indexed from [PROJECT_STATUS](PROJECT_STATUS.md#current-evidence)
