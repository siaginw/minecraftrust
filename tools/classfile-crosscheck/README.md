# Independent classfile cross-check (H1.3)

This isolated tooling crate uses **cafebabe 0.9.0**, not ASM, to resolve Java 8
class files. It does not participate in the engine Cargo workspace or production
bridge. `crosscheck.py` compares its fresh observations with the actual
`CanonicalClassIdentityV2 --dump` output. Accepting a file in two parsers is not
enough: their symbolic facts must agree.

## Compared facts

- Class version, raw access flags, name, superclass, interfaces, signatures,
  enclosing/inner-class metadata, source and source-debug metadata.
- Field and method identities, raw flags, signatures, constants, declared
  exceptions, method parameters and raw parameter-annotation counts/presence.
- Ordinary, parameter and type annotations, defaults, nested values, enum/class
  values, paths and exact targets. Float and double values retain raw payload
  bits, including NaNs and negative zero.
- Every instruction, operand, resolved field/method owner/name/descriptor,
  method/interface reference bit, invokedynamic bootstrap and arguments.
- Branch/switch targets, exception ranges/handlers, uninitialized verifier
  values, expanded stack-map frames, local-variable metadata and line numbers.
- Original member declaration order, checked independently against V2's
  separate declaration-order digest. Sorting the member fact collection does
  **not** authorize ignoring that digest.

Instruction positions use ordinal IDs with an end-of-code sentinel for ranges.
`ldc`/`ldc_w`, wide locals and compressed frames normalize to their logical
forms. Annotation records distributed by ASM among instructions, handlers and
local-variable nodes are gathered by their complete target identity. Raw target
tags, member reference kinds and access flags are independently recovered by a
small bounds-checked scanner where cafebabe's public representation loses them.

## Scope and limits

This is an independent **projection cross-check**, not another implementation of
the complete V2 identity. Unused constant-pool entries and unused bootstrap
methods are excluded from the projection; the V2 identity still retains them.
Every generated semantic mutation must change independently parsed facts, and
every stability control must retain both its V2 semantic identity and its
independently parsed semantic facts. Explicit rejection controls must be rejected
by both parsers. Other rejections and disagreements fail the campaign.

Unknown attributes and post-Java-8 class files reject. cafebabe exposes some
ill-formed UTF-16 string literals as raw bytes; these reject rather than being
lossily converted to replacement characters. Parser agreement is neither JVM
bytecode verification nor evidence of behavior, writer closure, lifecycle closure
or live profile qualification. Structural mutation fixtures need not execute.

Historical transformed Clean Forge/Revelation class dumps are useful parser
inputs, but parsing them now does not make them fresh runtime observations. The
receipt explicitly records that limitation and cannot grant authority.

## Reproduction

First compile and generate the V2 controls with
`tools/qualification-v2-tests/run.py`, supplying an explicit Java 8 home and ASM
jar. Then, from the hardening checkout:

```text
cargo test --locked --manifest-path tools/classfile-crosscheck/Cargo.toml
cargo fmt --manifest-path tools/classfile-crosscheck/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path tools/classfile-crosscheck/Cargo.toml --all-targets -- -D warnings
cargo build --locked --manifest-path tools/classfile-crosscheck/Cargo.toml
python -m unittest discover -s tools/classfile-crosscheck -p test_crosscheck.py
python tools/classfile-crosscheck/crosscheck.py --java JAVA8_EXE --classpath V2_CLASSES_AND_ASM --rust-exe RUST_TOOL_EXE --root fixtures=target/qualification-v2/fixtures --controls target/qualification-v2/fixtures/controls.tsv --root clean=CLEAN_TRANSFORMED_DIRECTORY --root revelation=REVELATION_TRANSFORMED_DIRECTORY --out target/qualification-v2/independent-crosscheck.json
```

Classpath separators follow the host platform. Each root is required to contain
class files. All files under it are observed, with exact output row counts and
process statuses checked. Inputs are processed in batches of 16; a Java batch
rejection is isolated per file because V2 deliberately emits nothing unless the
whole requested batch succeeds. The Rust CLI emits one JSON line per input and
returns a nonzero exit status on any rejection.

The runner checks each class's raw hash across both parsers and a final read,
and hashes tool binaries, the V2 classes, ASM jar, Rust sources/manifest,
Cargo.lock, controls and runner before and after the campaign. It also binds the
complete recursively discovered class inventory before/after, including every
class hash. A tool or input change fails the receipt. Do not regenerate
fixtures or compile concurrently with final validation.

Disagreements retain complete observed values and structural diff paths under a
sibling `*-disagreements` directory. They are never suppressed by a known-issue
allowlist. An expected reject counts only as an explicit negative-control result;
an unsupported real class never counts as agreement.

## Recorded and resolved representation disagreement

The first raw parameter-count fixture campaign found **two disagreements**,
preserved in `target/qualification-v2/independent-fixtures-final.json` and its
disagreement details. Both parsers accepted the files, but ASM reported one
invisible `Ljava/lang/Synthetic;` annotation absent from the physical attribute:

| Fixture | Raw SHA-256 |
| --- | --- |
| empty-visible-parameter-annotations | `a924ec7d9c771f8af3aff25842f5837e2a22e67d23c1f1b7a33fb49a97f13281` |
| empty-invisible-parameter-annotations | `328b2958444698fcaf319ff4729c7c1a643302322df01075eb7369021e056ef5` |

Inspection of `ClassReader.readParameterAnnotations` in the pinned ASM 5.2 jar
with `javap -p -c` confirmed that ASM emits an invisible Synthetic marker for
each omitted leading parameter for **each present** visibility attribute. This
is a visitor adaptation, not an annotation in the file. The comparison now
inverts that exact adaptation using V2's raw visibility counts, which are
independently checked against cafebabe's physical attribute lengths. It requires
the exact marker prefix and count. Real Synthetic annotations following that
prefix remain compared. Unit controls cover missing markers and coincident real
Synthetic annotations. No file/hash allowlist suppresses a disagreement.

Empty MethodParameters attributes similarly cause no ASM visitor call; the
projection distinguishes absent/null from present/zero using the independently
compared count. V2 retains this metadata in its actual identity. Neither
correction changes the accepted identity to hide a disagreement.

## Dependency decision

cafebabe is a small, independent, passively maintained Java-class parser with a
0BSD license. It resolves symbolic constant-pool references and parses bytecode,
annotations and stack maps. That makes it appropriate for this bounded tooling
prototype, **not** evidence that it can replace a production verifier or JVM.
See `machine/external-reuse/classfile-crosscheck.json` for exact dependencies,
licenses, provenance, integration scope and decision.
