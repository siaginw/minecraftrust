# H11 / H22.5.D — Bounded entity storage comparison

**Keep custom hot/cold SoA as the working baseline and hecs as a bounded
alternative. Park Bevy ECS and Shipyard adoption for this migration stage.**
All four remain experiments: no result authorizes replacing Java entity storage,
moving arbitrary behavior callbacks, changing semantic scheduling, or treating
blocks as ECS entities. The measurements do not establish a whole-server winner.

## Candidate selection and source scope

The isolated workspace pins `hecs = 0.11.1`, `bevy_ecs = 0.19.1` and
`shipyard = 0.11.5`, the stable releases examined on 2026-09-26. These three are
the only direct library dependencies. Each declares MIT OR Apache-2.0.
hecs declares Rust 1.81, while Bevy ECS declares Rust 1.95; the experiment used
Rust 1.98.1. [hecs registry metadata](https://crates.io/api/v1/crates/hecs/0.11.1),
[Bevy ECS versioned manifest](https://raw.githubusercontent.com/bevyengine/bevy/v0.19.1/crates/bevy_ecs/Cargo.toml),
[Shipyard versioned manifest](https://raw.githubusercontent.com/leudz/shipyard/v0.11.5/Cargo.toml)

hecs offers a storage/query library without prescribing an application scheduler;
its documentation explicitly describes entity iteration as unordered. Its
private handles can eventually repeat after sufficiently many reuse cycles.
Those properties make a separate logical identity and event-order contract
necessary. [hecs World API](https://docs.rs/hecs/0.11.1/hecs/struct.World.html)

Bevy was selected as a table-oriented comparator with cached query state and a
larger scheduling ecosystem. Shipyard adds a sparse component-storage comparator
and workload APIs. The experiment uses direct storage access, not either built-in
scheduler. Bevy's reflection/async defaults and Shipyard's parallel default are
disabled; the exact selected features are in the standalone manifest.
[Bevy ECS storage and query documentation](https://docs.rs/bevy_ecs/0.19.1/bevy_ecs/),
[Shipyard World API](https://docs.rs/shipyard/0.11.5/shipyard/struct.World.html)

## What the model actually stores

Hot columns/components contain integer position, velocity, bounds, lifecycle
state and behavior type. Cold payloads contain capability identifiers, 32 opaque
NBT-representative bytes, a Java-reference token and variable mod-extension
bytes. These are data-model tokens: the experiment performs no NBT parsing,
Java ownership, capability authorization or mod callbacks. An optional typed
extension component is added and removed to exercise structural churn. Cold
payloads must remain unchanged during hot movement and component migration.

The static/dynamic distinction is narrow: hot component types and the `Cold`
container type are fixed at compile time, while each cold record owns variable
capability/NBT/mod-extension vectors. The SoA cold column and ECS `Cold` component
hold the same representation; one quarter of initial records contain an opaque
mod-extension blob. Optional `Extension` is one additional fixed Rust type used
for structural churn. Arbitrary runtime-defined component schemas, reflection,
type-erased mod dispatch and Java reference semantics are **not** compared. This
is a physical-storage comparison under a shared extension representation.

The external identity is `(world, logical slot, u64 generation)`. The common
registry checks world and generation before translating into a private backend
handle. Despawn increments the external generation; generation exhaustion is
checked before mutation. Custom SoA uses dense columns, sparse slot lookup and
swap removal. Observable synthetic events are sorted by the external key, even
after shuffled spawning, archetype movement and slot reuse. This order is an
explicit experimental contract, not a claim about Minecraft's entity-list order.

All movement uses signed 64-bit wrapping integer addition. Paused entities do
not move. Spatial queries use inclusive axis-aligned bounds with widened
arithmetic. Rust builds a sweep index sorted by minimum x; the independent Python
reference uses brute-force pairs for small traces and separately checked grid
buckets for benchmark states.

## Correctness evidence

The final root-reviewed receipt is
`target/entity-storage-experiment/4927d3e1c7cb4b37aa0cc6a53cef7bc6/campaign.json`,
SHA-256 `bafe0bbcc8fdc5aca2ffedd990ff44b978dbf0907356b8a85632e486d5898daa`.
It reports PASS, no production authority and unchanged sources/tools/dependencies.
The original-checkout and production-gate guard passed before and after.

- Ten Rust tests cover registry behavior, stale private handles in all four
  backends, swap/migration survival, cold-state preservation and spatial edges.
- Seven Python tests independently check wrapping, a golden move, stale/cross-world
  identity, grid versus brute-force pairs, touching bounds, cold-state churn and
  combined-invalid-input rejection precedence.
- A 139-command ordered fixture produces 140 exact state/event boundaries per
  backend: **560 matching normal boundaries**. It includes shuffled insertion,
  duplicate spawn, extreme velocity, pause/resume, extension removal, slot reuse,
  invalid stale access, queries and collision pairs.
- Four injected defects alter a row's presence, identity, position or event
  order. Every control records its expected first mismatch. Separate controls
  reject replay under another session/challenge and missing/extra boundaries.
- All 40 timing samples match independently calculated benchmark results. The
  benchmark checksums are noncryptographic sanity checks; exact field comparison
  is used for the semantic trace.

The runner also passed format checking, locked offline Rust tests, clippy with
warnings denied and a locked offline release build, with 58 recorded subprocess
checks. Campaign elapsed time is not a storage benchmark result.

Root review reproduced a missing case in all four backends: a stale entity key
combined with lifecycle value 2 returned Rust `LIMIT` but reference `STALE`.
The Rust command now resolves identity first, consistent with the reference.
The first divergence and pre-fix campaigns remain retained; the full final
campaign and separate four-backend targeted rerun pass. Keys cover one registry
lifetime here; session/incarnation-safe production handles remain an H6/runtime
integration requirement, not a property of this comparator key.

## Measurement method

Two bounded populations, 1,000 and 10,000 entities, each receive five fresh
processes per backend. Backend order rotates between repetitions. Measurements
run sequentially; no GPU benchmark ran concurrently. The host reports Windows 11,
AMD64 Family 25 Model 97 Stepping 2 and 16 logical CPUs. CPU affinity, frequency,
thermal state and unrelated OS work were not controlled. Raw sample durations
and min/max ranges are retained; five samples do not support a tight confidence
interval or a tail-latency claim.

Initialization, storage operations, extraction, order restoration and spatial
maintenance have separate counters. All backends use the same sweep/query code,
so differing spatial times can reflect input order and noise rather than an ECS
spatial feature. The complete pipeline includes eight rounds of movement,
extraction, logical ordering, a hot-state checksum, index rebuilding, broadphase
and four queries. The checksum is synthetic observable work, not a Forge callback.
No library scheduler or parallel iteration is exercised.

The adapters use checked per-component point lookup. This is deliberately a
measurement of these concrete implementations, not the best achievable batched
lookup of each library. Query/view caching could materially change that result.
Bevy's iteration query state is cached. No implementation pre-reserves an entire
world or adapts capacity to the final population. Allocation growth policy and
handle width therefore affect memory and spawn results. In particular, SoA's
private handle stores the full logical key; smaller ECS handles reduce the
common registry's per-slot cost.

## Recorded times

The following values are medians in **milliseconds for the whole phase**, at
10,000 entities. Work counts are explicit; rows with different work counts must
not be compared as per-operation latency.

These tables retain the initial campaign `092f1f0106414f5a90ad68a368040b4c`
(receipt SHA-256 `b4d759ed106cf1a2975da078d993e2cc2260a2440391a088494545ca6f4cfac1`).
The final correction changes invalid lifecycle-command precedence and semantic
controls; the benchmark implementation and workload are unchanged. Both root
campaigns are retained separately. Their shared-host timings are exploratory,
including possible overlap with other correctness builds; they do not qualify
a performance claim or silently replace these measurements.

| Phase and work count | Custom SoA | hecs | Bevy ECS | Shipyard |
| --- | ---: | ---: | ---: | ---: |
| Spawn 10,000, including cold payloads | 1.849 | 3.379 | 2.532 | 3.456 |
| Hot movement, 32 × 10,000 | 0.302 | 0.328 | 0.399 | 0.776 |
| Checked logical lookup, 40,000 | 0.555 | 5.435 | 1.814 | 5.297 |
| Extension churn, 50,000 calls | 0.207 | 4.945 | 5.604 | 1.051 |
| Despawn/respawn 2,500 entities, 5,000 calls | 0.470 | 0.576 | 0.851 | 1.038 |
| Native hot-state extraction, 8 × 10,000 | 1.855 | 1.537 | 1.846 | 4.866 |
| Copy and restore logical order, 8 × 10,000 | 1.392 | 3.083 | 3.159 | 2.300 |
| Copy and build spatial index, 8 × 10,000 | 4.679 | 4.114 | 4.004 | 4.019 |
| Spatial queries, 32 | 0.603 | 0.547 | 0.533 | 0.551 |
| Broadphase, four complete scans | 0.741 | 0.716 | 0.712 | 0.713 |
| Complete pure pipeline, eight rounds | 16.163 | 16.911 | 17.472 | 17.930 |

The complete pipeline offers a different picture from a trivial iteration query.
Its median differences are modest relative to the observed ranges. The smaller
population even changes the median ordering. There is no robust overall winner
in this campaign.

| Population / backend | Eight-round median ms | Observed min–max ms |
| --- | ---: | ---: |
| 1,000 / SoA | 1.470 | 1.291–1.594 |
| 1,000 / hecs | 1.433 | 1.268–1.910 |
| 1,000 / Bevy ECS | 1.338 | 1.283–1.554 |
| 1,000 / Shipyard | 1.344 | 1.269–1.544 |
| 10,000 / SoA | 16.163 | 15.058–16.725 |
| 10,000 / hecs | 16.911 | 16.399–17.865 |
| 10,000 / Bevy ECS | 17.472 | 17.035–18.419 |
| 10,000 / Shipyard | 17.930 | 16.681–19.068 |

## Allocation accounting

These are **live requested allocation bytes**, not process RSS, committed pages
or allocator metadata. Rust's global allocator wrapper counts successful
allocation and reallocation requests and corresponding frees; reallocations
count their full new request even when they happen in place. The same counters
are active during timing, so allocation-heavy phase times include instrumentation
overhead. Per-phase requested totals, call counts and peak extra live bytes are
available in each raw sample.

At 10,000 entities every repeated sample reported the same requested live-byte
deltas for each implementation:

| Backend | Immediately populated | Retained after churn/pipeline |
| --- | ---: | ---: |
| Custom SoA | 4,741,856 | 4,742,816 |
| hecs | 4,087,896 | 5,680,468 |
| Bevy ECS | 5,545,649 | 8,818,714 |
| Shipyard | 5,464,960 | 5,681,456 |

hecs initially allocates less than this SoA implementation, but retains more
after archetype churn. Bevy retains the most in this fixture. The 1,280-byte
post-world-drop residual is the still-live phase-record vector in every backend;
it must not be presented as an engine leak or as proof of general leak freedom.
The “retained” measurement includes that small harness allocation. Cold payloads
are equal across implementations, and the sample does not represent large real
NBT/capability graphs or JNI global references.

## Decisions

| Candidate | Decision | Reason and migration value | Integration risk / next evidence |
| --- | --- | --- | --- |
| Custom hot/cold SoA | KEEP baseline; PROTOTYPE | Clear layout/identity control; low churn cost and retained bytes in this model | Bespoke safety and maintenance burden; compact handles, reservation policy and real entity traces still need evaluation |
| hecs 0.11.1 | KEEP alternative; PROTOTYPE | Hot traversal near this SoA baseline, lower initial allocation, small direct API surface | Archetype churn/retention and checked lookup cost need realistic workload weighting; test a batched lookup adapter before rejection on that metric |
| Bevy ECS 0.19.1 | PARK adoption; PROTOTYPE result retained | Cached table queries work correctly, but this stage does not use its richer scheduler/reflection ecosystem | Larger dependency/configuration surface and highest retained allocation here; no benefit shown that pays migration cost |
| Shipyard 0.11.5 | PARK adoption; PROTOTYPE result retained | Extension churn is materially cheaper than the other two ECS adapters in this fixture | Slower hot iteration/extraction in this adapter; workload scheduling and optimized borrowed views remain unmeasured |

These are project-scoped priorities, not universal library rankings. The chosen
event order, lifecycle rules, resource ownership and Java identity must remain
RustCraft contracts. Adopting a crate cannot define them implicitly.

## Provenance and production gates

The complete isolated lockfile contains 104 registry packages; the selected
native normal/build tree records 62 registry package/version entries. Optional
and target-specific lockfile packages are distinguished from that selected tree.
The retained inventory binds 7,170 dependency source files and 208 license/notice
files to registry archive checksums and exact VCS provenance. It preserves
Unicode-3.0's additional obligation and standalone Zlib license texts. Shipyard's
proc-macro notices come from its exact recorded upstream commit because the
archive omits them; `r-efi`'s AUTHORS carries its full MIT option and copyrights.
See `tools/entity-storage-experiment/third-party/inventory.json` and the
[reproduction instructions](../../tools/entity-storage-experiment/README.md).

The runner removes inherited build flags/wrappers, binds actual tool binaries and
Cargo configuration, verifies resolved dependency source paths, and checks input
drift after completion. Sources and outputs remain isolated. No root Cargo file,
production FFI, server installation, Issue #1 gate or original checkout was changed
by this experiment.

Live use remains blocked on qualified Java/Forge entity traces, exact observable
order, actual object/global-reference ownership, mod-extension and capability
semantics, lifecycle closure, state migration/rollback, and end-to-end performance.
No actual semantic scheduler has been measured here. No blocks are modeled as ECS
entities. Production native authority stays disabled, and Issue #1 remains open.
