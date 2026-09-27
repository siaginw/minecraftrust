# H22.5.E — JNI lifecycle and classfile tooling

The decision is **PROTOTYPE** for `jni-rs 0.22.4`, **STUDY** for `jbindgen 0.1.0`
and `ristretto_classfile 0.33.0`, and continued **PROTOTYPE** use of `cafebabe`
for the existing independent offline classfile cross-check. No production bridge,
Cargo workspace dependency, runtime installation, packet authority, or default
was changed. There is no comparative performance result and no recommendation to
replace a working bridge wholesale.

## What was exercised

The standalone `tools/jni-lifecycle-prototype` builds a small native library and
loads it into the explicitly configured Temurin Java 8u504 JVM. It uses
`EnvUnowned::with_env` before JNI work, lifetime-bound local references, bounded
local frames, owned globals and scoped native-thread attachment. The upstream
API documents initialization through `with_env`, panic containment and explicit
error policy selection; these are useful mechanisms, but callers still own
exception and object-identity semantics. [jni 0.22.4 documentation](https://docs.rs/jni/0.22.4/jni/)

The full fixture makes 66 assertions. Its local-frame workload creates and
compares 80,000 local references in 10,000 bounded frames. Thirty-two successful
native workers, an exception worker and its valid retry each assert that a newly
created native thread is detached before and after its attachment scope. Twelve
additional isolated modes separate library loading, JNI initialization and the
individual export paths.

| Area | Evidence | Limit |
| --- | --- | --- |
| Local references | Java identity comparisons; lower/upper round bounds; valid scalar-return compile control; escaping a frame-local reference is rejected by Rust | One concrete lifetime misuse control, not proof against all unsafe misuse |
| Callback exceptions | Direct and worker callbacks return the exact original Java `Throwable` object, checked with `==`; later calls succeed | Explicit worker forwarding policy is required |
| Panic and bad inputs | Caught native panic, invalid method signature and null callback all throw; valid retries pass | Panic stderr remains in the receipt; no arbitrary callback safety claim |
| Class identity | Exact class accepted; superclass rejected; same binary name from a second loader rejected | One isolated-loader fixture, not Forge loader qualification |
| Global references | One retained slot, occupied-slot rejection, release and idempotent release; retained object survives requested GC | Bounded GC observation after release is not leak-freedom proof |
| Native threads | Global passed to a new worker; attached/detached state asserted natively; global dropped while attached | No long-lived pool, shutdown race, or application scheduler qualification |

Cross-thread exception identity was the substantive policy finding. A callback
failure reaches Rust as `Error::CaughtJavaException` carrying the actual global
Throwable. Converting that error only to text can produce a different exception
on the caller. The worker export instead throws that object on the original
attached caller, preserving identity. This is fixture-tested behavior, not a
claim that the default error policy is sufficient for every bridge.

## Java 8 warning isolation

The chosen JVM prints `JNI local refs: 40, exceeds capacity: 39` during
`System.initializeSystemClass`, followed by one unchecked `CallStaticObjectMethod`
warning for each launcher argument. These warnings also occur in `BootstrapOnly`,
which does not load the native prototype. Controls with zero, two, four and five
arguments preserve the exact warning lines. A Java NIO-only control separately
checks path and file-reading setup.

All warnings in the prototype runs match the corresponding no-library control
and precede the first `BEFORE_PATHS` event. No additional warning appears after
fixture entry, library loading, JNI initialization, or the tested export calls.
The runner retains raw output and rejects extra or displaced warning lines.
The recorded success status is **PASS_WITH_JVM_BASELINE_WARNINGS**. This is not an
absolute clean `-Xcheck:jni` result, nor a suppression of dependency warnings.
The controlled panic's expected stderr is retained and checked separately.

## Reproducibility and dependency provenance

The [runner instructions](../../tools/jni-lifecycle-prototype/README.md) specify
explicit Java, Cargo, rustc and registry-cache paths. Runs use a fresh directory,
fresh session/challenge values, locked offline Cargo builds, and bounded process
output/lifetime. Every subprocess has raw stdout/stderr, command, exit code,
elapsed duration and hashes. The final receipt binds sources, executable tools,
173 JDK runtime files, artifacts and dependency provenance and verifies they did
not change during the run. Inherited Java options and Rust/Cargo flags and
wrappers are removed under a recorded environment policy. The resolved Rust
toolchain binaries and existing Cargo configuration are hashed; offline metadata
must resolve to the inspected source cache. Outputs must stay below the isolated
checkout's `target/`, and the original-checkout/production-gate guard runs before
and after. Unexpected failures retain a scoped FAIL receipt.
Elapsed campaign time measures the entire validation
workflow, including compilation; it is not a JNI benchmark.

The lockfile has 25 registry packages including `jni` itself, hence 24 transitive
packages. The retained inventory covers 1,109 dependency source files and 53
license/notice files. Cached source bytes are compared with the checksum-verified
crate archives before and after validation. For three archives that omit license
text, the copies come from their exact recorded upstream commits. Original
license expressions are preserved, including the additional Unicode-3.0
obligation in `unicode-ident`. The complete evidence is in
`tools/jni-lifecycle-prototype/third-party/inventory.json`; dependency resolution
remains confined to this standalone workspace.

The runner performs `cargo fmt --check`, locked offline `cargo test`,
`cargo clippy -- -D warnings`, a locked offline release build, Java fixture
compilation, one positive and one negative Rust compile control, four JVM
bootstrap controls, the NIO control and thirteen JNI probe processes. This
prototype has no separate Rust unit tests; `cargo test` compiling zero unit tests
must not be reported as additional lifecycle coverage.

The frozen campaign receipt is
`target/jni-lifecycle-prototype/877e6c4fd355449fa3c53ef956db9ab9/campaign.json`,
SHA-256 `a4749a3d54dc5a653dfe9477132bf92a240aa038dcde49ef92c80d524e731b41`.
It records 30 subprocess checks, 13 JNI probe processes, all 66 full-fixture
assertions and both compile controls. Sources, tools, runtime, dependency files
and artifacts remained unchanged; isolation guards passed before and after.
Collection after global release was observed in this run. Total validation
duration was 25.10 seconds, including fresh builds and JVM starts. The only
successful status claimed is `PASS_WITH_JVM_BASELINE_WARNINGS`.

The root review independently reran the frozen sources at
`target/jni-lifecycle-root-review/308e15b9fbcd491db073cd1f1af606ef/campaign.json`
and reproduced all 66 assertions and warning classifications. Its receipt hash
is `6d70de94cde471985dfc185e67b23d9e9a0b011c5a1645acaadd8bce77677fba`.
Both campaigns and their raw outputs are retained under
`machine/architecture-hardening/h22-5-jni-evidence`; the indexed machine report
is `machine/architecture-hardening/h22-5-jni-validation.json`.

## Other classfile and binding candidates

| Candidate | Decision and value | Integration cost and next evidence |
| --- | --- | --- |
| jni-rs 0.22.4 | PROTOTYPE: demonstrated local lifetime rejection, scoped attachment and precise exception handling | Review one real bridge seam, compare maintenance and measured cost, exercise its actual loader and shutdown behavior before adoption |
| jbindgen 0.1.0 | STUDY: generated typed bindings could reduce hand-maintained Java signatures | Generate a bounded real API and inspect changed signatures, loader initialization, exceptions and build reproducibility; no installation or local benchmark performed |
| ristretto_classfile 0.33.0 | STUDY: potential additional independently implemented parser | Re-run the entire H1 corpus and mutation projection, including malformed attributes, before treating it as another oracle; no installation or local test performed |
| cafebabe 0.9.0 | PROTOTYPE: existing H1 independent parser cross-check is useful offline evidence | Keep raw supplements where parser abstractions omit identity-relevant facts; parsing agreement cannot qualify live Forge behavior |

`jbindgen 0.1.0` is MIT OR Apache-2.0 and declares Rust 1.85. Its manifest uses
`cafebabe 0.9` and `jni 0.22.4`, and has Java compilation/JAR tooling dependencies.
It therefore **does not supply a third independent classfile parser oracle**.
Generated wrappers and parser independence are different benefits.
[Versioned jbindgen manifest](https://docs.rs/crate/jbindgen/0.1.0/source/Cargo.toml)

The Ristretto `v0.33.0` workspace declares Apache-2.0 OR MIT, Rust edition 2024
and minimum Rust 1.97.1. The classfile README describes class reading/writing
through Java 25 and explicitly calls verification unfinished. Those declarations
are candidate-selection facts, not locally validated compatibility or speed.
[Versioned workspace manifest](https://raw.githubusercontent.com/theseus-rs/ristretto/v0.33.0/Cargo.toml),
[versioned classfile README](https://raw.githubusercontent.com/theseus-rs/ristretto/v0.33.0/ristretto_classfile/README.md)

The existing frozen H1 receipt,
`target/qualification-v2/independent-crosscheck-frozen.json`, records 1,815 files:
1,610 historical Clean Forge classes, 129 historical Revelation classes and 76
fixtures. There were 1,809 agreements and six expected rejections; all 73 mutation
controls were evaluated. This evidence predates this experiment and is reused
only for its stated parser scope. It neither refreshes runtime qualification nor
proves verification or execution equivalence.

## Remaining integration gates

This experiment deliberately does not model class unloading, persistent native
thread pools, concurrent bridge shutdown, global-reference quotas across a whole
server, re-entrant Forge callbacks, crash recovery or Java memory-model closure.
Object/class identity and exact Throwable behavior must be carried into any real
bridge adapter instead of replaced by names or messages. Production adoption
needs its own performance/maintenance comparison and qualification evidence;
this report authorizes none.

The Issue #1 gate remains closed. These lifecycle checks do not establish
packet-time coherent capture or resolve the historical exact writer.
