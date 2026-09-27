# CANONICAL_ID_V2

This identity replaces the lossy instruction-anchor projection for **new**
qualification. V1 remains byte-for-byte available only to reproduce historical
receipts. Existing V1 profiles are historical inputs, not V2-qualified evidence.
This milestone changes no production authority gate or profile expected hash.

## Contract

`CanonicalClassIdentityV2.identify(bytes)` returns one result with schema,
internal class name, canonical JSON, semantic SHA-256, declaration-order SHA-256,
and raw classfile SHA-256. CLI success requires every supplied file to succeed;
it never prints an error disguised as a class hash. `--dump` emits canonical JSON
instead of receipts. SHA-256 input is the exact UTF-8 JSON without a trailing
newline. Strings escape every non-ASCII UTF-16 code unit, including unpaired
surrogates; primitive values have explicit type tags and floating-point raw bits.

`LiveHookSupport.verifyCanonicalIdentityV2` requires explicit `CANONICAL_ID_V2`,
the expected internal class name, and **both** semantic and declaration-order
hashes. It computes and checks them from the supplied bytes in one operation.
V1 schema names, missing hashes, reordered declarations, and identity mismatches
fail closed. It does not enable native packet authority.

### Member-order correction to the audit recommendation

The Java reflection API does not specify declaration enumeration order, but a
mod can observe and depend on the order returned by a particular JVM. Therefore
it is not justified to silently drop declaration order from qualification.

The semantic digest sorts fields and methods by name/descriptor, satisfying the
requested member-emission stability control. A separate declaration-order hash
retains the original sequence. **The semantic hash alone is insufficient for
admission.** The default API requires both; it currently offers no waiver.
A future waiver requires explicit profile evidence, never automatic inference.
Interface, annotation, exception-table, inner-class, parameter and other list
orders are retained.

## Coverage and failure policy

The supported classfile range is Java 1.1 through Java 8 (major 45 through 52;
minor 0, plus historical 45.3). Newer classfiles, nest metadata introduced after
Java 8, and any unknown attribute fail closed rather than silently disappearing
through ASM 5.2. This is conservative compatibility, not a claim to parse modern
classfiles. Adding a version or attribute requires a new reviewed implementation
and controls; changes to the serialized schema require a distinct schema ID.

The representation retains:

- Class version/access/name/superclass/interfaces/signature, source/debug,
  enclosing/inner metadata and visible/invisible ordinary/type annotations.
- Field owner/name/descriptor/signature/access/value and annotations.
- Method name/descriptor/signature/access/exceptions, parameter names/flags,
  all annotation kinds/defaults, max stack/locals, code/debug/frame metadata.
- Every instruction kind and operand, including member owner/name/descriptor,
  interface bit, local indices, constants, IINC, array type/dimensions,
  invokedynamic bootstrap handle/arguments, switch keys and all targets.
- Ordered exception ranges/targets/types and their annotations.
- Expanded verification frames, including uninitialized allocation labels.
- Local variable scopes/signatures, line tables, instruction and local-variable
  type annotations.
- A sorted symbolic inventory of **all** pool constants (including unused
  entries) and bootstrap entries. Pool references resolve recursively to values;
  floating NaN payloads and signed zero are not canonicalized away.

Labels become the ordinal of the next real instruction; the end label becomes
the instruction count. Multiple labels at one offset therefore share an ID.
Compressed stack frames expand to the same explicit verifier state. Pool
numeric indices, byte offsets, label object identity, and member order in the
semantic sub-digest cannot affect that sub-digest. Debug metadata is deliberately
retained rather than assuming it is unobservable.

A raw structural pass runs before ASM. It rejects unsupported pool tags,
unknown/misplaced/duplicate attributes, truncated/oversized bodies, trailing
bytes, invalid reference types and unsupported versions. It inspects nested
Code attributes as well as class/member attributes. This is an identity tool,
**not a replacement for JVM bytecode verification**: stack/type legality and
runtime linkage must be verified in their separate qualification lanes.

## Canonical JSON schema

Top-level array indices:

```text
0 schema; 1 version; 2 access; 3 name; 4 signature; 5 super; 6 interfaces;
7 sourceFile; 8 sourceDebug; 9 outerClass; 10 outerMethod; 11 outerDescriptor;
12 annotations; 13 typeAnnotations; 14 innerClasses; 15 fields; 16 methods;
17 sorted symbolic constant JSON strings; 18 sorted symbolic bootstrap JSON strings
```

Fields: `[owner,name,descriptor,signature,access,value,annotations,typeAnnotations]`.

Methods:

```text
0 name; 1 descriptor; 2 signature; 3 access; 4 exceptions; 5 parameters;
6 annotations; 7 typeAnnotations; 8 parameterAnnotations; 9 annotationDefault;
10 maxStack; 11 maxLocals; 12 instructions; 13 handlers; 14 frames;
15 lines; 16 localVariables; 17 localVariableAnnotations;
18 raw parameter annotation counts [visible|null,invisible|null];
19 raw MethodParameters count|null
```

Instructions are `[opcode, operands, typeAnnotations]`. Handlers are
`[startOrdinal,endOrdinal,handlerOrdinal,catchType,typeAnnotations]`. Frame
entries retain the ordinal, expanded frame kind, local values and stack values.
Bootstrap/instruction constants use explicit tags; the source implementation is
the full normative field/type definition.

Raw parameter-annotation counts remain distinct from ASM5's synthetic-parameter
padding; an empty MethodParameters attribute remains distinct from its absence.
A LocalVariableTypeTable entry with no corresponding local-variable
entry is rejected: ASM5 otherwise drops that metadata. These conservative
limitations are explicit rather than silently omitted from a successful digest.

## Reproducible controls

Run the parameterized harness from this isolated worktree:

```text
python tools/qualification-v2-tests/run.py --java-home <Java8-home> --asm <ASM5.2-jar>
```

It compiles the production identity/admission classes and deterministic controls,
then writes classfiles, canonical dumps, receipts and a control manifest under a
fresh `target/qualification-v2/runs/<timestamp-unique-id>` directory. An explicit
`--out` must also name a nonexistent directory within this worktree; existing
evidence is never silently overwritten. The receipt binds toolchain, harness and
source hashes before/after each subprocess, checks the selected runtime/compiler
are Java 8, validates exact assertion/control counts and artifact sets, and
captures process status/stdout/stderr. Tests include every requested H1.2
mutation, bootstrap/annotation/frame/NaN controls, conservative rejection
controls, normalization controls, and composite admission controls. Operand
binding swaps preserve the entire symbolic pool, so the pool inventory cannot
mask missing instruction operands in those tests. An LDC/LDC_W relocation
control also proves code type-annotation offsets are normalized. The TSV control
manifest explicitly names the reference fixture for every control.

The generated mutations test structural identity sensitivity. Some deliberately
produce verifier-invalid methods; those are not executed and are not described
as behaviorally valid Java programs. Parser cross-checks and real transformed
class scans are separate evidence. Preserved old classfiles are useful corpus
inputs, but scanning them does **not** regenerate fresh runtime qualification.

## Historical Revelation regression preservation

`tools/qualification-v2-tests/recheck_legacy_revelation.py` accepts explicit
`--dump`, `--runtime`, `--java-home`, `--asm`, and fresh `--out` paths. It reads
the c4b868d sources using Git objects and compiles them into the isolated output;
it never executes or writes in the paused original worktree. It compares the
baseline/current V1 helper outputs against every supplied preserved class and
the committed historical expected hashes.

It then runs the existing ten `test_profile_verify` controls using explicit
test-only environment injection. A temporary profile names the actual corpus
and has an **empty** embedded transformed-class map, making the legacy fallback
unavailable. A test adapter redirects only the hardcoded Java helper invocation
to the newly compiled current helper; it validates process success, exact unique
class/hash output count and identity against the actual input inventory. The
production verifier is not changed. Receipts retain source/tool/runtime/corpus
hashes before and after, compiled artifact hashes, subprocess output and each
temporary control profile.

This lane proves historical behavior preservation only. V1 remains incomplete;
the legacy verifier's runtime hardcoding, RAW bug and embedded transformer/coremod
metadata are not repaired here. The existing tenth test checks a real pinned mod
hash but does not physically substitute a jar; receipts state that limitation.
No result from this harness qualifies a fresh Revelation runtime under V2 or
resumes live shadow. H2 and H23 own those separate requirements.
