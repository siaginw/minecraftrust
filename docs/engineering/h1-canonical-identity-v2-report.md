# H1: complete canonical identity V2

H1 implements and validates a conservative Java 8 class identity and explicit
composite admission API. It does not requalify a live profile. H2 and H23 remain
required before either profile can use V2 qualification evidence.

The baseline V1 projection omitted operands, constants, branch destinations,
exception ranges and observable metadata. `CANONICAL_ID_V2` retains those facts,
including symbolic constant-pool/bootstrap values, access flags, annotations,
frames, debug metadata, parameter metadata and declaration order. Unknown
attributes, unsupported class versions and unsupported lossy representations
reject. See `../research/canonical-identity-v2.md` for the schema and limits.

The semantic digest normalizes pool indices, code offsets and member ordering.
Admission additionally requires the declaration-order digest: reflection order
can be observable, so equal sorted members alone cannot admit a class. The
explicit V2 API rejects V1 aliases and incomplete composite identities.
Historical V1 calculation remains available unchanged for receipt reproduction.

## Validation

| Check | Result and scope |
|---|---|
| Java 8 compilation and V2 controls | PASS, 92 assertions; 63 changed, four stable and six rejected controls |
| Independent cafebabe projection | PASS, 1,815 files: 1,809 agreements, six expected paired rejections, zero disagreements |
| Input corpora | 1,610 preserved Clean Forge classes, 129 preserved Revelation classes, 76 generated fixture classes |
| Independent tooling checks | Five Rust tests and ten Python tests; formatting and Clippy with warnings denied pass |
| Fresh Clean Forge regression | Profile, transformer, real packet capture/native replay, unchanged Java output and independent post-hook verification pass |
| Foundation suites | 201 assertions: private tickets 60, writer gate 39, chunk bindings 57, end-to-end protocol 14, capture admission 31 |
| Existing Clean tooling | 25 Python tests pass |
| Historical Revelation regression | All 129 actual V1 identities equal the accepted baseline; ten existing controls pass with embedded class fallback disabled |
| Workspace libraries | 78 Rust tests pass |
| native-chunk | 47 tests pass, including the original ten unit tests and all integration suites |
| protocol | Ten oracle tests pass |
| Release FFI | `cargo build --release --locked -p ffi` passes |
| Isolation and authority | Original HEAD/status/paused source hashes preserved; both production gates remain closed |

Exact commands, source/tool hashes, output hashes and local full receipt paths
are bound by `../../machine/architecture-hardening/h1-validation.json`.
Root Rust production sources and dependencies are unchanged. Formatting and
Clippy apply to the new isolated classfile tool; existing workspace warnings
are retained. No benchmark gain is claimed for qualification tooling.

## Findings retained during validation

- Initial Clean verification rejected three foundation source hashes. Git's
  `core.autocrlf=true` had changed checkout bytes to CRLF. The committed blobs
  and original checkout matched the existing LF pins. Narrow `.gitattributes`
  rules now preserve those exact bytes; no hash expectation was changed.
- A first test command named a nonexistent Python test module. That import
  failure remains recorded; the corrected 25-test inventory passes.
- An initial Cargo harness could not resolve Cargo from a child environment.
  No test ran in that attempt. Absolute executable paths fixed the harness.
- Two parser disagreements exposed ASM5's synthetic annotation padding for
  omitted leading parameters. Pinned ASM bytecode inspection explained them;
  raw parameter counts and exact tested reconstruction preserve the facts.
  Disagreements were retained rather than added to an ignore list.
- ASM can lose an empty MethodParameters attribute and orphan generic local
  metadata. V2 retains the count/presence of the former and rejects the latter.

## Limits and next milestone

The independent parser compares a complete operational projection, but unused
constant-pool/bootstrap entries remain outside that independent projection.
V2 itself retains them. Structural identity is not JVM dataflow verification,
runtime behavior equivalence, writer closure or lifecycle closure. Some negative
fixtures intentionally contain verifier-invalid code and are never executed.

Historical class scans and Revelation controls are explicitly historical
regressions. They are not fresh V2 qualification. The legacy verifier's embedded
metadata, hardcoded paths and broken RAW path remain assigned to H2. Its tenth
control checks one real pinned mod hash; it is not a jar-substitution experiment.

The next milestone is the generic QualificationEngine and evidence dependency
certificates, followed by corrected extractor scopes. Fresh Clean Forge and
Revelation V2 qualification remains H23. Revelation live shadow remains gated.
Native packet authority stays disabled; MCK6 semantics and defaults are unchanged.

H22.5's replay, pure state-diff, GPU/offline, ECS, JNI/classfile, concurrency,
sidecar, hot-method and external-decision work remains in the active program.
This milestone does not satisfy those experiments or close Issue #1.
