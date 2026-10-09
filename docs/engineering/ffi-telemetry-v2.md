# FFI telemetry schema V2 — H3 bounded implementation

The former `FfiMetrics.record_call(bytes)` received operation identifiers at all
54 call sites. Adding identifiers 300–320 to a `bytes_transferred` counter did
not measure bytes. `native_allocations` was never incremented. Schema V2 removes
both misleading fields and separates fixed operation identity from observations.
Historical transfer-volume claims derived from those values are invalid; raw
historical evidence remains unchanged. See the separate H3.1 claim review.

This implementation instruments 58 operation IDs, including four newly covered
boundaries: direct-array encoding, size prediction, packet V2 encoding and owned
snapshot encoding. The existing global C call-count getter still counts entries,
but its expanded coverage makes old and new totals **not directly comparable**.
Per-operation schema V2 call counts record completed observations.

## Measurement contract

| Field | Meaning and limits |
| --- | --- |
| `operation_id` | Fixed Rust enum value; never added to any byte field. |
| `call_count` | Completed RAII observations for that operation. |
| `input_bytes` | Bulk input span admitted by the boundary, even if parsing later rejects it. Excludes scalar ABI arguments, handles and return codes. |
| `output_bytes` | Successful published payload bytes, taken from the actual return/result. Failure is zero successful output, even when existing code modified scratch bytes first. |
| `copied_bytes` | Proven explicit bulk byte copies. Unknown when native internals or the JVM can add unaccounted copies. This is not CPU load/store traffic. |
| `borrowed_bytes` | Caller/JVM buffer spans exposed to Rust; includes offered output capacity. It is **not** transfer volume and may exceed successful output. |
| `retained_bytes` | Caller-owned buffer bytes retained beyond return. This is zero for the instrumented synchronous boundaries; it does not measure native owned copies, registry memory or RSS. |
| `allocation_bytes` | Requested native heap bytes for the entire operation, only when proven. It is not allocation events, object size, capacity or RSS. Most operations remain unknown. |
| `elapsed_ns` | Native guard interval, ending before counter flush. Includes instrumentation within the body and excludes the Java/JNI transition, Java allocation, and caller staging. |
| `fallback_reason` | Fixed observed native outcome category. `None` means native completion, without any production-authority implication. Unclassified legacy outcomes remain `Unknown`. |

Every numeric measurement has `sum_known`, `known_samples`, `unknown_samples`
and `overflowed`. `None` contributes an unknown sample, never a fabricated zero.
A publishable complete total requires at least one known sample, no unknown
samples and no overflow. Sums saturate and expose overflow. A contained panic
invalidates normal-path allocation/copy proofs, including when an outer catch
has already consumed the unwind. Known no-work rejections remain distinct from
unobserved measurements.

Counters are bounded by a 512-slot fixed table and finite enums. There are no
runtime labels or dynamically allocated measurement maps. Snapshot reads use
relaxed atomic observations; they are not transactionally coherent while calls
run concurrently. Exact test/benchmark deltas require quiescence.

`Instant` plus up to seven measurement updates, atomic CAS/adds, outcome and call
counts add overhead to every instrumented call. No low-overhead, latency gain,
throughput improvement or performance-neutrality claim is made. H15 must measure
that cost against a defined baseline. These tests establish accounting and
behavior, not performance.

## Covered boundaries and remaining gaps

The machine inventory is `machine/architecture-hardening/h3-telemetry-v2.json`.
It lists every operation, JNI/C export, alias and uncovered export.

| Boundary group | Proven bulk accounting | Explicit unknowns |
| --- | --- | --- |
| Direct staging encoder | Admitted staging bytes, output capacity borrowed, exact successful output; normal-path native heap allocation zero after source review | Serialization copies; panic allocation/copies |
| Output prediction | Admitted staging span, no payload output, normal-path native heap allocation zero | Panic allocation/copies |
| Heap-array encoder | Staging plus actual exposed array length; successful payload length | VM critical-array copies/allocation; no inference from `isCopy` |
| Native chunk | Primer/optional biomes; refresh states/optional light arrays; materialization, persistence, metadata/biome/light outputs; encoder actual result length | Registry, palette, serializer allocations/copies unless one explicit fixed copy is proven |
| Packet V2 / owned snapshot | Actual output length from the same packed serialization result; admitted owned transport; borrowed output capacity | Parser/serializer allocation/copies; no independent mask query |
| Noise / terrain | Actual input arrays, borrowed output spans and generated successful output sizes; explicit final copies for gen2d/gen3d | Other native internals and VM copies/allocation |
| Compression context | Admitted input, borrowed input/output spans, actual compressed successful length | Backend copying and allocation; backend unchanged |
| Spawn index | Admitted component array and fixed 48-byte stats output/copy | Index allocation/copies; scalar-query internals |

All other instrumented operations have no bulk ABI buffer, so those fields are
known zero; allocation remains unknown and 27 legacy scalar operation outcome
classifications remain unknown. Six `WorldgenShadow` JNI aliases call the same
instrumented base operations and count once, without separate operation IDs.

Uninstrumented exports are the global count reader, two V2 diagnostic readers,
four calibration/debug helpers (`jniNoop`, `testForcedPanic`, `fillPattern`,
`jniProbeVersion`), and three outbound-frame exports. The frame functions were
only reformatted with the owned file, without logic changes. Frame/compression
semantics and MCK6 remain unchanged. There is no `refreshSectionV2` export in this
checkout; the existing `refreshSection` is covered. H3 is not a claim that every
JNI export, every copy/allocation or every fallback reason has been measured.

## Additive diagnostic ABI

Existing JNI signatures and result/error behavior are unchanged. The additional
read-only symbols are `NativeFfiTelemetry.schemaVersion()` and
`NativeFfiTelemetry.readOperationV2(int, long, int)`. Test-only Java declarations
live under `tools/telemetry-tests`; they are not packaged into production.

`readOperationV2` accepts only a registered operation ID and a live exclusive
writable caller allocation of at least 312 bytes. It writes exactly 312 bytes,
leaving any remaining capacity untouched. All words are unsigned 64-bit little
endian; Java should treat them as unsigned where necessary.

| Word offsets | Content |
| --- | --- |
| 0, 1, 2 | Schema version (2), operation ID, completed calls |
| 3–30 | Seven groups of four: sum, known samples, unknown samples, overflow (0/1), in input/output/copied/borrowed/retained/allocation/elapsed order |
| 31–38 | Outcome counts: Unknown, None, InvalidArgument, Capacity, CorruptInput, MissingState, BackendError, Panic |

Success returns 312; invalid ID/address/numeric range/capacity returns -1 before
any write; contained diagnostic panic returns -2. The implementation uses fixed
stack arrays and allocates no variable-size buffer. Numeric checks cannot prove
pointer allocation provenance; callers retain the ordinary unsafe FFI contract.
Diagnostic reads do not instrument themselves.

## Verification and reproducibility

Run from the isolated checkout:

```powershell
python -B tools/telemetry-tests/run.py --java-home D:\rustcraft-toolchains\temurin8\jdk8u504-b01
```

Final run: `target/architecture-hardening/h3-telemetry-525976d708/receipt.json`.
Raw logs and that unmodified receipt are retained in
`machine/architecture-hardening/h3-telemetry-evidence/`. The machine inventory
binds each retained artifact to a SHA-256. The receipt binds 152 build/test input
files before/after and the DLL before build, after build and after Java tests.
Source/isolation checks and post-build DLL identity passed.

| Check | Result |
| --- | --- |
| Metrics unit tests | 8 pass |
| New FFI telemetry integration | 10 pass |
| Native-chunk | Original 10 unit tests + 37 integration/property tests pass |
| Workspace library tests | 86 pass, including existing 11 FFI tests |
| Protocol | 10 pass |
| Release FFI build | Pass |
| Java 8 telemetry / critical-array test | 5 groups, 1,809 assertions pass |
| Existing Java NativeChunk V2 | 6 groups, 568,392 assertions pass |
| Existing Java owned snapshot | 4 groups, 139 assertions pass |
| Existing Java result decoding / snapshot capture | 218 / 454 assertions pass |
| Root rerun of existing Clean Forge legacy foundation | 25 tooling tests + 201 Java foundation assertions pass; real captured/replayed payload mask 1, Java/Rust 4,358 bytes, exact byte equality |
| Formatting all eight changed Rust files | Pass |
| Strict metrics clippy | Pass |
| Unrestricted strict FFI clippy | Fails existing lint debt; raw failure retained |
| Strict FFI with five enumerated legacy lint exceptions | Pass |
| Workspace-wide `cargo fmt --check` | Fails existing formatting outside the eight changed files; raw failure retained |

Legacy lint exceptions are missing safety docs, unnecessary casts, manual
`unwrap_or`, the existing `AssertUnwindSafeClosure` name and pre-existing test
module placement in `owned_snapshot.rs`. The machine ledger retains 72 baseline
source evidence entries, including all 67 missing-safety-doc declarations, at
accepted commit `fd93e8d4cbc25af38a96962ce682093f8f6dab04`;
the new packed-result observer was placed before test modules. Dependency
warnings about worldgen assignments, spawn `REGION_SIZE`, and compression `have`
also remain. No out-of-scope warning cleanup was performed.

The two earlier runner failures are retained: the first selected an unnecessary
Java `CaptureDraft` source without its live dependencies; the second omitted
`java.library.path` required by the existing bridge loader. Neither is erased or
reported as a passing run. The final invocation corrected the runner inputs.

DLL SHA-256:
`7db5025ff7c09e6249f7000749a5cdb374681f0d4cb530047be667ccaab5abb2`.

The root's independent Clean regression receipt is retained at
`machine/architecture-hardening/h3-telemetry-evidence/h3-clean-regressions-final/receipt.json`
(SHA-256 `fd07090cc757f32086b8f34091935a9bfc3b4640878bfc7b3411020c982524f0`).
It binds the same DLL, confirms unchanged Java output, and reports no source or
input drift. That additional run preserves the existing Clean legacy foundation;
it does not issue V2 qualification or resume a live shadow campaign.

Production native packet authority remains disabled. No gate, defaults, payload,
compression backend or Issue #1 historical classification is changed. This
telemetry work neither closes live snapshot coherency nor resolves the historical
exact writer.

The initial H3 summary incorrectly counted the separate ten telemetry integration
tests in the workspace-library total. That command passed 86 tests; its raw log
is unchanged. H4 adds two unit tests, making its subsequent workspace total 88.
