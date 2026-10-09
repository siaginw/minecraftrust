# H8 / H8.1 section layout and packet-body cache experiment

Status: **PASS_BOUNDED_PROTOTYPE_ONLY**. Keep this work isolated. The results support further work on immutable body reuse and a small/large palette lookup split; they do not justify replacing the current dense section or enabling native packet authority. The cache loses badly under one tested slow-reader/budget combination. Live Forge snapshot coherence, actual packet submission and JVM ownership remain unresolved.

The implementation is in `tools/section-packet-cache-experiment`, a separate Cargo workspace with seven existing local library dependencies and no new external crate. Frozen H6 `native-state-vnext::NativeSection` provides the u32 layouts. The current `native-chunk` public encoder provides the dense comparison. Neither is changed. The [source-bound methodology](../../tools/section-packet-cache-experiment/README.md) defines admitted inputs, timers, memory columns and limitations.

## Correctness and evidence

Final receipt: `machine/architecture-hardening/h8-section-packet-cache-evidence/h8-section-packet-cache-d02f73e6e58c/receipt.json`, SHA-256 `cf1c6bc96e883843cfbe7361c89217b8e0cf6b6aa7474124046c218d4870fd79`. The same raw receipt remains under `target/architecture-hardening/h8-section-packet-cache-d02f73e6e58c`. The [machine summary](../../machine/architecture-hardening/h8-section-packet-cache.json) retains raw-file hashes and earlier runs without rewriting them.

- Ten Rust regression tests pass: exact dense/body parity, all local widths/direct boundaries, sparse/all/empty masks, missing selected section, capacity failure and retry, wide runtime mapping, every cache-key identity component, stale publish, oversized admission, registry remap, no-op generation stability, eviction, slow-reader retention, state/light/biome changes and immutable old readers.
- Independent Java 8 bit-by-bit packing matches both Rust body encoders for eighteen fixtures, **305602 bytes per encoder**. The Java path constructs the body from semantic fixture input; it does not read Rust's expected bytes. A deliberate change to packet byte zero fails at `FIRST_DIVERGENCE file=uniform.prototype.bin offset=0`.
- Scoped `cargo fmt --check`, strict `cargo clippy --all-targets --no-deps -- -D warnings`, locked/offline Rust tests and a fresh release build pass. The four reused Windows Job process controls pass.
- Forty source inputs, sixteen tool/runtime files and the release executable are bound before/after. Eight local packages include this experiment. The original-worktree and production-gate guards pass before/after. No full-host/toolchain immutability claim is made.
- The native benchmark child completes in 16.728 seconds with 30437376 bytes peak process commit. This is whole-child committed memory, including fixture setup; it is not per-layout RSS or the cache payload counter.

The first source-bound attempt failed because the JVM's default initial heap exceeded the 512 MiB Job limit. Explicit `-Xms16m -Xmx128m` and equivalent javac flags resolved that harness issue. That failed receipt is preserved, along with the first successful correctness-only run and the earlier 64 KiB-only timing campaign. Development clippy findings were fixed without suppressions. The old timing campaign was superseded because its budget could not retain either full view, so it observed zero actual resident invalidations. Its raw results remain available.

## Layout measurements

These are median elapsed times from five measured samples after one warmup, on a shared host. Each read column covers 100000 reads, each write column 10000 prepared setters, and each packing column sixteen complete unframed body encodes. Order alternates. Values are microseconds; raw min/max and every sample remain in evidence. Small intervals, no-op writes and host noise limit inference.

| Layout | Dense reads | H6 reads | Dense writes | H6 writes | Dense warm packing | Dense cold packing | Experimental packing |
|---|---:|---:|---:|---:|---:|---:|---:|
| Uniform | 40.1 | 20.1 | 4.4 | 21.0 | 137.2 | 319.1 | 454.8 |
| Eight-state local palette | 40.0 | 45.3 | 14.0 | 36.2 | 119.8 | 355.7 | 531.4 |
| 64-state local palette | 40.3 | 45.5 | 14.0 | 52.4 | 176.0 | 1662.4 | 1199.7 |
| Eight-state DenseHot | 40.2 | 45.8 | 13.9 | 25.2 | 121.8 | 389.3 | 500.4 |
| 1024-state DenseHot/direct wire | 40.1 | 45.8 | 13.9 | 25.2 | 207.5 | 5214.7 | 2408.3 |

The current warm packed-word cache remains substantially cheaper than rebuilding through the experimental runtime-ID mapping path. The large/direct cold cases point to palette construction as a useful optimization target, but do not isolate its contribution: mapping, allocations, envelope and checksum are all included. Uniform saves state storage and has a cheap read path; H6 setters are slower in these samples. DenseHot's u32 identities provide migration capacity rather than a demonstrated performance win over legacy u16 state.

Legacy whole-section inline size is 12352 bytes. The experimental wrapper is 4320 bytes inline, including the same two 2048-byte light arrays. Additional H6 vector payload capacities are 0, 8288, 8960, 16384 and 16384 bytes in the table's order. The 64-state palette also has hash capacity for 112 entries. Legacy private palette allocations, actual hash buckets/control bytes, allocator headers and fragmentation are unmeasured. These are partial storage components, not total memory comparisons; small palettes with u16 local indices do not automatically beat dense u16 state.

Lookup-only changed-write tests perform 32768 changes for each variant. At cardinality eight, linear median is 217.0 microseconds and hash median 365.8 microseconds; min/max ranges are 186.5–383.0 and 304.2–498.5. At cardinality 64, linear median is 563.8 and hash median 325.2; ranges are 371.6–747.4 and 305.5–528.9. This supports retaining the hybrid hypothesis. It does not establish sixteen as an optimal crossover or justify adding SIMD/unsafe code. Hash construction is outside these timers.

Hot promotion plus its 64 writes takes median 24.7 microseconds. Restoring 4096 cells and running three maintenance sweeps takes median 78.5 microseconds and returns to uniform. Both transition counters are exactly one per sample. These timings include mutation/maintenance work, not just representation conversion.

## Packet cache measurements

The workload has one or sixteen recipients sharing 25 or 289 single-section chunks. All recipients for a chunk are served consecutively, with two recipient-rule classes. Two rounds model either no mutation or one changed cell in every chunk per round. All rows compare identical delivery counts and accumulated body checksums. This checksum comparison supplements the independent fixtures; it is not a full independent byte oracle for every timed request.

Representative median whole-workload times are milliseconds. Each case has five samples. Full evidence contains all 32 cache combinations across both budgets, including both mutation settings and slow/fast consumers.

| Resident/live budget KiB | Recipients | Chunks | Mutation | Slow consumer | Dense path ms | Cache path ms | Hits / misses | Resident entries invalidated |
|---|---:|---:|---|---|---:|---:|---:|---:|
| 64 / 96 | 1 | 25 | None | No | 0.798 | 1.829 | 0 / 50 | 0 |
| 512 / 768 | 1 | 25 | None | No | 0.833 | 0.883 | 25 / 25 | 0 |
| 512 / 768 | 1 | 25 | Every chunk | No | 1.198 | 1.655 | 0 / 50 | 25 |
| 64 / 96 | 16 | 289 | None | No | 85.779 | 40.133 | 8092 / 1156 | 0 |
| 64 / 96 | 16 | 289 | None | Yes | 85.246 | 316.692 | 196 / 9052 | 0 |
| 512 / 768 | 16 | 25 | None | No | 6.836 | 1.828 | 750 / 50 | 0 |
| 512 / 768 | 16 | 25 | Every chunk | No | 8.012 | 3.776 | 700 / 100 | 50 |
| 512 / 768 | 16 | 289 | None | Yes | 84.318 | 41.296 | 8092 / 1156 | 0 |

Immediate fan-out sharing helps the sixteen-recipient fast-consumer cases. A one-recipient workload usually does not recover the cost of building the experimental body. The small budget cannot retain a complete view; mutation invalidation therefore scans but removes no old entries in those cases. The larger budget makes actual invalidation visible: fifty removals in the small sixteen-recipient/high-mutation/fast-consumer case take median 21.1 microseconds in total. Larger cache scans can themselves cost more; invalidation currently scans the bounded ordered map.

The slow-consumer result is a meaningful counterexample to “cache always wins.” With the 64/96 KiB budget and sixteen recipients/289 chunks, retained readers leave too little headroom for the alternating recipient bodies. There are 564 producer-triggered drains and severe eviction churn. Peak unique adopted payload is 96405 bytes, below 98304; peak payload held outside the resident map is 89978 bytes. Eviction never hides those bytes from the live counter. With the 512/768 KiB budget, the same no-mutation case peaks at 674555 adopted bytes and 154248 external bytes, with no budget-triggered drain. There is still no real network delay in this model: draining releases a pending reader immediately. All cache accounting returns to zero after final reader/cache drop.

The benchmark includes cache lookup, body building on misses, adoption/eviction, setter/invalidation and checksum/length delivery accounting. Source world and legacy chunk construction are outside the timer. Hits also avoid recomputing the stored body checksum, so the ratio is not purely serialization savings. Packet framing, compression, TLS/encryption, socket copies, Java JNI costs, server scheduling and nonoverlapping player views are absent. These samples must not become a Minecraft throughput claim.

## Reuse and next decision

[Valence's pinned loaded-chunk implementation](https://github.com/valence-rs/valence/blob/c4177cab47ae109c848a431756d5d7c0207c05ed/crates/valence_server/src/layer/chunk/loaded.rs) motivates invalidating initialization packet bytes on observable mutations. Its code is MIT; its logo is not used. [Hyperion's pinned indirect palette](https://github.com/hyperion-mc/hyperion/blob/85abe336514e507b72a38e351b5df5a641a4bf30/crates/hyperion-palette/src/indirect.rs) is studied under Apache-2.0. [Issue 938](https://github.com/hyperion-mc/hyperion/issues/938) is a chunk-channel/cached-subscription proposal, not evidence of shipped implementation or measured performance. The experiment independently implements the relevant ideas, copies no upstream implementation and installs no external dependency. Exact references, source hashes and retained notices are in `provenance.json`.

The [pinned PrismarineJS protocol schema](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/1.12.2/protocol.json) establishes the 0x20 packet envelope under its documented MIT license. Its versioning does not qualify Forge or modded registry behavior. This prototype explicitly maps u32 runtime identities to the admitted 13-bit legacy domain and rejects missing/unrepresentable states rather than truncating them.

Recommended disposition: **PROTOTYPE / BORROW_ALGORITHM**, with no production wiring. Keep uniform storage, a measured small/large lookup split and immutable generation-bound body reuse as candidates. Preserve the current warm native encoder as the baseline. The next bounded step is to derive the cache key and immutable source snapshot from H6's actual WorkTicket/view identity, then adopt only through its validated publish boundary. Encapsulation must prevent generation-free mutations; concurrent builders need reservation limits in addition to adopted-byte limits. Test stale owner/registry/lifecycle results and slow-reader admission against that integrated boundary before considering JNI or a live packet path. Do not change compression or packet authority as part of that step.

This work does not close Issue #1, establish historical exact-writer identity, provide live snapshot coherence or prove broad 1.12.x gameplay support. `M4NativeStatePayload.tryEncode` remains production fail-closed.
