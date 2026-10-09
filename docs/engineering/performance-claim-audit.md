# Performance claim audit — H3.1

Reviewed 2026-09-26. The [machine inventory](../../machine/architecture-hardening/h3-claim-audit.json) records 17 explicit decisions with source lines and hashes. This reviews the active README performance table, every required H3.1 category, and inherited invalidations. It does not remeasure historical campaigns or certify every sentence in the archive.

`MEASURED` describes the cited historical measurement's provenance and bounded scope. It does not mean its raw capture was reacquired here. `DERIVED_FROM_MEASURED` identifies arithmetic on such observations; `PROJECTED` identifies an unmeasured proposed benefit; `ASSUMED` identifies an unsupported input; `SYNTHETIC` identifies authored/model outputs. `INVALIDATED` names a conclusion that cannot support current acceptance, with the reason kept explicit.

| Claim | Classification | Correct use |
| --- | --- | --- |
| Legacy FFI transferred-byte counter | INVALIDATED | `record_call(bytes)` received operation IDs. Its sum is not bytes; historical transfer-volume/throughput conclusions using it are invalid. No correction factor can recover the missing volumes. |
| Façade JNI 18.50 ns | ASSUMED | `ForgeBenchmarks` prints the constant while timing Java arrays and Unsafe. It never measures a JNI transition in those loops. |
| Block-access JNI 15.2 ns / 24.5x penalty | SYNTHETIC | Arithmetic on constants, already in the invalidation register. Keep the coarse-call hypothesis without that performance claim. |
| M2J 1,848/12,474 allocation events | MEASURED | Historical sampled-event fraction only. Object sizes and sampling weights are needed for bytes; it says nothing by itself about CPU or total allocation. |
| P0-8 allocation histogram/rates | SYNTHETIC | Existing authored tables remain invalidated; they cannot select an allocator or represent a modpack. |
| M3J “factory” | MEASURED | A vanilla hopper/chest/furnace room and natural spawning inside Revelation. No mod machines were present; H16's mod-machine benchmark remains required. |
| M3W5 timings/live counts as currently verified | INVALIDATED | Cited final-shadow and terrain-parity raw receipts are absent. Historical prose is preserved; a new independent campaign is required for current acceptance. Missing evidence does not prove that an old run never happened. |
| Block-ID parity proves zero ULP | INVALIDATED | Many density values yield the same block ID. Compare floating-point bits directly at the relevant boundaries. The available terrain harness also uses a Java reference loop, so a new run needs independent runtime evidence. |
| M2CP throughput/worker CPU | MEASURED | Historical three runs per arm, alternating, one machine/pack, with documented span and chunk-mix limitations. No MSPT gain or linear multiplayer scaling follows. |
| M2CP compressed size | DERIVED_FROM_MEASURED | From rounded per-run MB: median OFF ratio 13.7208%, ON 13.8462%, relative increase approximately 0.914%. Worst ON ratio 14.5420%, approximately 6.0% above OFF median. This compares workloads, not identical packet streams. |
| Hot no-op JNI mean 8.46 ns | MEASURED | Historical actual native no-op, one JVM/machine. Corrected prose p95/min/max to committed YAML: 19.00/4.10/27.60 ns. Complete calls have additional work. |
| `NewDirectByteBuffer` cost | MEASURED | Wrapper creation over an existing static native buffer. It does not measure native payload allocation, initialization or a complete transfer path. |
| Native NBT lookup is exactly twice as slow | PROJECTED | Ratio of two different operations, without native NBT lookup/lifecycle costs. No complete native lookup benchmark established that ratio. |
| Section refresh | MEASURED | Historical report says 12 KiB refresh-only mean 2.21 µs, not README's former 2.7 µs. Refresh plus encode costs 13.65–75.65 µs depending on palette in that report. |
| Worldgen n=1 +5.6% | MEASURED | Historical 240-chunk density benchmark scope; complete generation, callbacks, persistence and server timing remain separate. |
| Cached encode vs Java constructor | DERIVED_FROM_MEASURED | Different boundaries. Preserve the explicit warning; do not present their ratio as a complete-path speedup. |
| Retained native state is whole-path zero-copy | PROJECTED | Selected re-extractions may disappear. Capture, serialization, compression, encryption and send still need measured copy and lifetime accounting. |

The active README and affected narrative reports now carry these limitations. Historical raw files, machine result summaries and `machine/evidence-provenance.yaml` remain unchanged. In particular, missing M3W5 artifacts were not supplied with invented hashes or substituted evidence.

The broad text scan is a triage inventory: it records source hashes and candidate numeric performance lines across the tracked README, docs and machine reports. A matching line is not automatically a false claim, nor does a nonmatch prove absence of claims. Existing [41-finding inventory](../../tools/audit_hardcoded_benchmarks_results.json) and [invalidation register](evidence-invalidation-register.md) remain applicable. New performance integrations must record kernel, complete-operation and relevant whole-path timing, CPU, allocations, RSS, tails and semantic parity; H3's counter repair alone is not a speedup.
