# Independent V2 writer placement validation

This tool provides an independent, conservative **offline** relation between exact
pre-hook and post-hook class buffers. It imports no transformer and does not use
replaying a transformer as an oracle. A campaign passing its controls does not
mean the observed runtime classes pass placement proof. The retained Clean
classes currently produce **INCOMPLETE**, because existing verifier frames are
rewritten without an independently qualified equivalence relation.

Production native packet authority remains false. This work establishes neither
live writer closure, lifecycle closure, coherent capture nor a qualified loader
hierarchy. It cannot close Issue #1 or authorize Revelation live shadow.

## Inputs and parser independence

`tools/writer-placement-v2/validate.py` consumes the generic engine's
`RUSTCRAFT_PLACEMENT_REQUEST_V2`: session/challenge, acquisition/observation/profile/
manifest hashes, exact pre/post class observations, and the complete required-site
inventory. A separately pinned hook plan supplies method descriptors, hook kinds,
and source BCI assertions. The plan supplies no expected post-hook bytes.

Every class is copied once into an isolated private `.bin` input, with bounded
size and before/after source hashes. The existing Java V2 canonicalizer parses
the exact bytes; its full canonical dump and composite identity are recomputed.
The frozen H1 Rust `cafebabe` parser independently checks instruction operands,
reference kinds, annotations, frames, handlers and declaration order using the
existing complete supported projection. Raw hashes must agree. Unused symbolic
pool/bootstrap facts are retained by V2 and checked separately; the Rust
projection does not claim independent coverage of unused constants. Unknown
attributes and unsupported parsing shapes reject. The small Python raw scanner
only recovers byte offsets, independently checks instruction cardinality, and
does not supply semantic operands.

The adapter compares recomputed methods and all identity hashes against the
request. Claimed observation hashes are never sufficient. The engine must pin
the validator sources, hook plan, H1 projection source and parser executable,
Java executable, ASM jar and complete V2 class inventory. Tools, sources, inputs,
plan and request are checked for drift. This binds offline artifacts; it does not
attest which loader executes a class in a future process.

## Exact proof rules

The finite rule set covers ordinary writer brackets, nested writer/publication
brackets, retirement before the unload anchor, worker entry and success/failure
publication before `ran`, IO publication, session start/end, packet
observe/commit/abort, provenance markers and constructor ownership registration.
Declaration-only fingerprints still require the method and exact intended
instrumentation; they never exempt a method from preservation checks.

Each inserted instruction has an exact opcode and complete operands, including
local index, owner, name, descriptor, interface-reference bit, literal value and
instruction type annotations. Normal returns must have the appropriate inner
then outer end groups. Catch-all entries must have exact protected starts/ends,
targets, ordering and handler bodies, including the same exception local on the
end call and rethrow. Scope locals are initialized before their protected range.
Constructor registration must follow a supported `this`/super constructor call.
Retirement/release anchors cannot have an alternate original branch or handler
entry that bypasses their required insertion.

Removing only these exact groups leaves a one-to-one original instruction map.
Every original operand and branch/switch target must match under that map.
Original exception ranges and order, line positions, local-variable ranges and
local type-annotation ranges are relocated explicitly. Other method metadata,
class/field metadata, annotations, signatures, parameter counts and member order
are compared in full. Unhooked methods receive the same full V2 comparison.
Original symbolic constant-pool multiplicities and bootstrap facts must survive.
Only constants attributable to the finite inserted instructions are admitted;
duplicate or unexplained entries fail. Constants introduced only by unproved
frame rewrites are retained as blockers.

Added local counts are exact. A separate stack-height propagation over the
verified instruction/control-flow graph checks the post maximum stack; this is
not a complete JVM type verifier. JSR/RET and other unknown shapes reject.
Original frame positions, stack facts and original-local projections must match
exactly. Added token locals must have their required precise representation.
Added catch-all frames have a conservative argument-slot/TOP merge check for the
supported shape. Argument reassignment, original frame refinements, additional
frames or unexplained metadata never disappear through normalization.

An end/abort facade call itself can have observable behavior or throw. This
validator establishes the exact static call/handler structure, including those
calls' protected ranges. It does not prove facade runtime behavior, absence of
throwing callbacks, or live transaction closure.

## Why Clean is incomplete

The current writer recomputes frames across the entire class. Original TOP locals
are sometimes retained as integers/references, and some interface/reference
types become more precise. For example, the unhooked
`BitArray.func_188142_a(I)I` changes two TOP local entries to INTEGER at instruction
ordinal 47. Both parsers observe this difference. Existing instruction and
operand preservation does not by itself qualify the changed verifier metadata.

The audit retains every original and observed frame difference as
`ORIGINAL_FRAME_REWRITE_UNPROVEN`, `HANDLER_FRAME_RELATION_UNPROVEN` or an explicit
unhooked-method difference. These are not automatically Java behavioral defects;
they are gaps in the conservative proof relation. No class/hash allowlist or
frame stripping turns them into PASS. A separately specified verifier-only
metadata relation, actual verification of complete classes and a qualified
loader/hierarchy environment are necessary before relaxing this gate. Merely
loading classes or invoking ASM's frame computation would not establish that.

## Controls and receipts

`PlacementFixtures.java` independently writes a small positive class pair. It
does not invoke production transformers or the Python rule generator. The pair
includes ownership registration, a conditional original branch, two normal
returns, exact debug ranges and a catch-all scope handler. Both parsers process
the pair and all 27 mutated classfiles.

Mutations cover missing/duplicate/moved begin, wrong owner/descriptor/interface
bit/operation string/local, missing/extra end, handler range/target/count, changed
original operand and branch target, extra instruction/method, metadata/member
order, max stack/locals, verifier-local mutation, constructor binding and an
unused pool addition. Every mutation must fail or remain explicitly incomplete;
none may pass. The verifier-local control is INCOMPLETE under the conservative
frame gate, not claimed to have been executed or rejected by HotSpot.

Python controls additionally cover every one of the 66 Clean required hooks with
a missing-call mutation, nested handler order, original annotations/debug facts,
pool multiplicity, unknown shapes, missing/duplicate inventory, exact switch
target relocation, variable-width BCIs, malformed input, JSON schema and output
isolation. Subprocess adapter controls cover positive proof, forged observation
identity, and retained Clean incompleteness.

Every command and bounded raw output is retained. Java option environment
variables are removed; command arguments contain explicit executable/classpath
paths and never use a shell. Source/tool/input hashes and isolation/gate checks
are recorded before/after. Per-stream output and process lifetime are bounded;
class observations are limited to 256 inputs, 8 MiB each and 64 MiB per side.
Campaign retention has a 256 MiB budget. The subprocess helper targets trusted,
pinned local parsers/compilers; it is not a sandbox for hostile process trees.

Reproduce from the isolated checkout:

```powershell
& 'C:\Python314\python.exe' -B tools/writer-placement-v2/run.py
```

Defaults reference the retained H23 Clean pre/post observations, the pinned Java
8 toolchain and ASM 5.2 artifact. The runner freshly compiles the V2 parser and
fixture generator into a unique target directory. It launches no game JVM or
server. The generic adapter requires explicit arguments:

```text
python -B tools/writer-placement-v2/validate.py --plan HOOK_PLAN --java JAVA8_EXE --classpath V2_CLASSES;ASM_JAR --rust-parser H1_RUST_PARSER --request REQUEST --out WITNESS
```

The adapter emits the existing `RUSTCRAFT_VALIDATOR_ACK_V2` and
`RUSTCRAFT_PLACEMENT_WITNESS_V2` contracts. Detailed proof results and raw parser
outputs are retained beside the witness. The engine must require all four
checks—anchor order, exception paths, undeclared edits and pre/post relation—to
be PASS. Current Clean returns INCOMPLETE for the full relation even though its
exact insertion/control-flow checks pass.
