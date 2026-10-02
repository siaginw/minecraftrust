# Research index

RustCraft's primary output is evidence. This index organizes the research trail — 82 documents in [`docs/research/`](research/), plus the compatibility, engineering, and architecture collections — by theme, so a serious reader can find the reasoning behind every design decision.

## Qualification & proof model

| Document | What it establishes |
|:---|:---|
| [Qualification engine v2](engineering/qualification-engine-v2.md) | The two-launch engine: static recipe vs dynamic observation |
| [Canonical identity V2](research/canonical-identity-v2.md) | Exact vs session-bound class identity projections |
| [Evidence provenance](engineering/evidence-provenance.md) | The evidence chain and its invariants |
| [Evidence invalidation register](engineering/evidence-invalidation-register.md) | What evidence exists, and what would invalidate it |
| [Qualified frame relation](engineering/qualified-frame-relation.md) | The independent frame witness |
| [V2 writer plan admission](engineering/v2-writer-plan-admission.md) | How writer plans are generated and admitted |
| [V2 writer placement](engineering/v2-writer-placement.md) | Independent placement validation |

## Live shadow & campaigns

| Document | What it establishes |
|:---|:---|
| [V2 live-shadow architecture](research/V2_LIVE_SHADOW_ARCHITECTURE.md) | Campaign design, taxonomy, closure criteria |
| [Phase-D event contract](research/V2_PHASE_D_EVENT_CONTRACT.md) | Event identity, comparator contract, claim limits |
| [V2 live session admission](research/V2_LIVE_SESSION_ADMISSION.md) | How a real FML launch qualifies under the same engine |
| [Live-capture scope v1](research/live-capture-scope-v1.md) | The extractor policy model |
| [Issue-1 live-shadow gate plan](research/issue1-live-shadow-gate-plan.md) | The capture gate design that closed issue #1 |

## Compatibility surface (the 1.12.2/Forge survey)

The [compatibility collection](compatibility/) — 18 studies covering the boot flow, chunk and NBT capabilities, network security/backpressure semantics, coremod transformation hazards, access transformers, reflection surface, and tick compatibility. Start with [boot compatibility](compatibility/boot-compatibility.md) and the [chunk survey](compatibility/chunk-compatibility.md).

## Subsystem seams

Which parts of the engine Rust can take over, and what each boundary costs: [chunk seams](research/chunk-rust-seams.md) · [world seams](research/world-rust-seams.md) · [NBT seams](research/nbt-rust-seams.md) · [network seams](research/network-rust-seams.md) · [engine-vs-mod bottlenecks](research/engine-vs-mod-bottlenecks.md) · [lighting + collision](research/lighting-collision.md) · [worldgen fusion](research/worldgen-fusion.md).

## Native state & snapshots

| Document | What it establishes |
|:---|:---|
| [Native chunk state foundation](research/m4-native-chunk-state-foundation-report.md) | The `native-chunk` crate design |
| [Owned snapshot transport](research/issue1-owned-snapshot-transport.md) | RCSNAP01 design |
| [Snapshot fixtures](research/chunk-packet-fixture-v1.md) | The fixture corpus schema |
| [Differential event replay](research/differential-event-replay.md) | Offline Java-vs-Rust replay methodology |
| [Rust Light State Authority](research/RUST_LIGHT_STATE_AUTHORITY_REPORT.md) | Block and sky light data migration into `NativeSection` |
| [Rust Biome & Heightmap State Authority](research/RUST_BIOME_HEIGHTMAP_AUTHORITY_REPORT.md) | Biome `[u8; 256]` and heightmap `[u16; 256]` state ownership with AtomicU32 resolution |
| [NativeChunk Performance Research](research/NATIVE_CHUNK_PERFORMANCE_RESEARCH.md) | Ecosystem survey of Valence, FerrumC, Feather, simdnbt, and bitpacking |
| [NativeChunk Performance Scorecard](research/NATIVE_CHUNK_PERFORMANCE_SCORECARD.md) | Latency percentiles ($p50/p95/p99$), throughput, spatial neighbors, and memory layout cost model |
| [NativeChunk Real Server Profile](research/NATIVE_CHUNK_REAL_SERVER_PROFILE.md) | FTB Revelation 3.4.0 real server profiling, MCA palette distribution, and CPU stacks |
| [NativeChunk Memory & GC Report](research/NATIVE_CHUNK_MEMORY_GC_REPORT.md) | Native memory footprint, packed long keys, and Java Young Gen GC churn elimination |
| [NativeChunk JNI & Copy Audit](research/NATIVE_CHUNK_JNI_COPY_AUDIT.md) | Complete 18-method FFI boundary inventory and zero-copy packet lifecycle audit |
| [NativeChunk Performance Closure](research/NATIVE_CHUNK_PERFORMANCE_CLOSURE.md) | Rigorous optimization plateau proof, FTB Revelation JFR stack profiling, compiler matrix, and A/B server analysis |
| [NativeChunk Core Completion Report](research/NATIVE_CHUNK_CORE_COMPLETION_REPORT.md) | Optimization pass completion, formal memory hardening, and verification receipts |
| [Next Engine Subsystem Selection](research/NEXT_ENGINE_SUBSYSTEM_SELECTION.md) | Subsystem candidate evaluation and decision matrix |

## Protocol

| Document | What it establishes |
|:---|:---|
| [Packet buffer pipeline](research/packet-buffer-pipeline.md) | Wire I/O design |
| [Direct Netty Wire Emission Report](research/DIRECT_NETTY_WIRE_EMISSION_REPORT.md) | Elimination of heap buffer allocations and intermediate memcpys via off-heap pooled Netty ByteBufs |
| [Packet-mask semantics](research/m4-1-palette-semantics-research.md) | Palette behavior from real bytecode |
| [Protocol-340 collection](protocol-340/) | Wire-level decoders and the packet registry |

## Performance (scoped, historical)

| Document | What it establishes |
|:---|:---|
| [Performance claim audit](engineering/performance-claim-audit.md) | Which claims survive their evidence |
| [Benchmark scopes](engineering/m1-benchmark-scopes.md) | What each benchmark did and did not measure |
| [Compression v2 backend research](research/p3-compression-v2-backend-research.md) | The ~2.26× component result's scope |

## Historical planning

The original charter, curriculum, stage plan, and skeleton documents live in [`docs/foundation/`](foundation/) — preserved as historical artifacts of the project's reasoning, not as current status.

## Machine-readable evidence

[`machine/`](../machine/) holds the YAML evidence manifests backing the historical benchmarks; campaign receipts live with their runs and are indexed from [PROJECT_STATUS.md](PROJECT_STATUS.md#current-evidence).
