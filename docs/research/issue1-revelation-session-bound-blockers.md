# REQUIRES_ULTRA_REVIEW: §11 REVELATION_PROFILE_REQUALIFIED_V2 cannot be completed

Status: **stopped, not waived.** Two independent blockers were found, both verified
against real bytes from real captures. Neither was worked around. No gate was relaxed,
no identity mode was downgraded, and no session-bound normalization was made to depend
on class structure instead of acquisition evidence.

Clean Forge (§10) is unaffected and remains `PASS` at `OFFLINE_QUALIFIED` with
`production_authority: false`.

## Blocker 1 — a session certificate cannot satisfy both committed gates at once

The session-bound identity contract has two enforcement points, and they compare the
*same certificate field* against *different buffers*.

**Runtime gate (Java, inside the transforming JVM).**
`LiveChunkOwnershipTransformer.transform(name, transformedName, basicClass)` calls
`verifyPreHookIdentity(hooks, basicClass, ...)`, which for a session-bound profile runs
`verifySessionBoundIdentity` → `authorize(certificate, loader, basicClass, session, name)`.
`SessionBoundIdentityCertificate.authorize` requires
`exactSemanticSha256 == <exact CANONICAL_ID_V2 identity of basicClass>`, where
`basicClass` is the **pre-writer** buffer the transformer was handed.

**Offline gate (Python, the engine).**
`QualificationEngine.session_evidence` requires
`document["exact_semantic_sha256"] == classes[name]["semantic_sha256"]`, where
`classes[name]` is the identity the engine independently recomputed from the observed
**post-writer** classfile (`collector.py` copies `post_source` into `classes/`; the
pre-writer bytes go to `pre/` and `pre_classes`).

Measured on the committed Clean Forge requalification run
(`target/qc/c2860b/runs/b439b03e3bba48be86cb3ffce5e6fc5c`), class
`net/minecraft/world/chunk/Chunk`, using the real identity tool:

```
pre-writer   CANONICAL_ID_V2  semantic 85695f4d95f1a13b4c33502c03b168dbc0eef206eb5f9d3e2a83ef705cea27cd
post-writer  CANONICAL_ID_V2  semantic 143eea4b7be8bfa6a99aa0f8dc9993d6206e27f6580596d52170846d8dc2f20b
```

These differ by construction: the writers inject hook calls, so the canonical
projection of the class changes. No certificate can carry a single
`exact_semantic_sha256` that equals both. The engine's `session_evidence` node has no
test that reaches a PASS (`tools/testing/test_qualification_engine.py` only covers
exact-mode-carrying-session-evidence rejection and a malformed block), so this conflict
was never exercised end to end.

Resolving it requires changing one of two already-committed, already-controlled gates:

* point the engine's comparison at `pre_classes` (aligning it with the runtime gate), or
* issue two certificates — one describing pre-writer bytes for the runtime gate, one
  describing post-writer bytes for the engine.

The second option is a back door: it would mean the engine never checks the certificate
the runtime actually enforces, which is the entire point of the evidence binding. The
first changes the semantics of a committed qualification gate. Neither is a decision
this work is authorized to make — §12 forbids weakening the qualification contract to
force a PASS, and the certificate's meaning is exactly the contract.

This maps onto the §12 stop condition *"same-process derivation can't be established"*:
the derivation runs and produces evidence, but the evidence it produces cannot be
admitted by the qualification chain that is supposed to consume it.

## Blocker 2 — the pinned Revelation ASM jar has no generics, so the harness will not compile

`forge_runtime.execute` compiles the offline harness and the live-writer bridge sources
against exactly the artifacts its pins name, and then runs them on those same artifacts.
That coupling is deliberate.

The Clean Forge server ships `libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar`.
The FTB Revelation 3.4.0 server ships `libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar`,
which is the generic-stripped build. `javap` on the two:

```
asm-all-5.2.jar        public java.util.List methods;
asm-debug-all-5.2.jar  public java.util.List<org.objectweb.asm.tree.MethodNode> methods;
```

Compiling the harness sources against the Revelation-pinned ASM produces **100 errors
across 17 files**, all of the form "Object cannot be converted to MethodNode /
FieldNode / InnerClassNode / TryCatchBlockNode / LocalVariableNode / ParameterNode":

```
tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java
tools/bridge/src/com/rustcraft/coremod/ChunkMutationTransformer.java
tools/bridge/src/com/rustcraft/coremod/FrameShadowHookTransformer.java
tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java
tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java
tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java
tools/bridge/src/com/rustcraft/coremod/LiveShadowCoreMod.java
tools/bridge/src/com/rustcraft/coremod/NetworkManagerCompressionTransformer.java
tools/bridge/src/com/rustcraft/coremod/RustCraftCoreMod.java
tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java
tools/bridge/src/com/rustcraft/coremod/WorldCollisionProbeTransformer.java
tools/bridge/src/com/rustcraft/coremod/WorldgenShadowTransformer.java
tools/forge-capture/src/com/rustcraft/offline/bootstrap/OfflineTweaker.java
tools/forge-capture/src/com/rustcraft/offline/bootstrap/RevOfflineTweaker.java
tools/forge-capture/src/com/rustcraft/offline/oracle/QualifyRuntime.java
tools/forge-capture/src/com/rustcraft/offline/oracle/RevQualifyRuntime.java
tools/forge-capture/src/com/rustcraft/offline/oracle/RevServerInventoryAgent.java
```

The erasure is compile-time only — the bytecode API is identical — so the options are a
mechanical source refactor to raw-typed iteration, or compiling against a different ASM
than the one the runtime is pinned to. The second breaks the compile-against-the-pinned-
runtime property. The first touches `CanonicalClassIdentityV2` and `LiveHookSupport`,
the two files §7 and §12 specifically protect, and would need the full Clean Forge
control set re-proven. Neither was started.

## What was completed for §11 before stopping

* `tools/testing/forge_runtime.py` no longer hardcodes Clean-Forge facts. `PROFILE`,
  `TARGET`, the pins path, and every Clean-only assertion (including
  `chunk_load_listener_count == 0`, which a real modpack legitimately violates) are now
  read from the pins file. The assertions keep their teeth: each is still an exact
  comparison against a reviewed, hashed expectation, now in one reviewable place.
  Committed as `128be76`, with the fixture correction in `1b848c0`.
* `tools/forge-capture/runtime-pins.json` grew the seven previously-hardcoded keys, and
  the two committed manifests that pin its hash were re-pinned.
* Verified behaviour-preserving for Clean Forge: the full V2 requalification still
  returns `PASS` at `OFFLINE_QUALIFIED`, `production_authority: false`; `live-profile`,
  `live-transformer`, `live-capture`, `java-jni`, `property`, `fixture`, `decoder` and
  `forge` lanes are all green; identity controls 92 + 19 + 38 pass.
* Confirmed for Revelation: the runtime launches and produces a full qualification
  (129 transformed classes, 34 transformers, 19 registered coremod plugins, every
  required class present, all 129 defined by `net.minecraft.launchwrapper.LaunchClassLoader`,
  and the hooked classes genuinely carry `MixinMerged.sessionId`). Artifact identities
  for the Forge 2846 universal jar, the Minecraft server jar, `asm-all` and
  `launchwrapper` are resolved, as are the three embedded Forge resource digests.

## Resolution: both blockers cleared, measured

This section supersedes the "stopped, not waived" status above. Both blockers are
resolved, each by a measurement recorded in a tool rather than by a judgement.

### Blocker 1 — the certificate is an ADMISSION certificate, and the engine now reads it as one

The Java runtime gate was correct all along. `LiveChunkOwnershipTransformer` receives the
**pre-writer** buffer and `SessionBoundIdentityCertificate.authorize` checks
`exactSemanticSha256` against it, which is the only buffer it has. The engine was the
side that was wrong: it compared the same field against `classes[name]`, the recomputed
**post-writer** identity, which no single field can equal because the writers inject hooks.

`QualificationEngine.session_evidence` now resolves the certificate against `pre_classes`,
raises `Missing` if either identity was not recomputed, and adds a second check the old
code did not have: the post-writer identity must *differ* from the admitted one, so a
record claiming a certificate where the writers proved nothing is refused rather than
accepted. The certificate the engine checks is the same document the runtime enforces —
there is no second, offline-issued certificate.

A certificate that authorizes PRE says nothing about what any transformer did afterwards,
so §4 added a separate same-process chain — `PRE_WRITER → RUSTCRAFT_POST_WRITER →
DOWNSTREAM_TRANSFORMER → FINAL_DEFINED` — with dense ascending ordinals, adjacent-edge
hash continuity, per-stage transformer identity, hook call counts and exception handlers.
The FINAL stage is bound to the frame witness rather than to the chain document, because
a chain that only quotes itself proves nothing.

`RUSTCRAFT_POST_WRITER` output is independently recomputed by the engine from the
observed class, and the FINAL stage's bytes and defining loader must match what the
launch itself observed. Absent chain evidence is `INCOMPLETE`, not `FAIL` — the run is
unpromoted, not condemned. Evidence that is present and wrong is `FAIL`.

Fourteen end-to-end controls now reach a real `PASS / OFFLINE_QUALIFIED` with
`production_authority: false`, including the two separately compiled classfiles that
share a binary name and differ by a declared writer effect, so the pre/post distinction
is exercised on real bytes rather than asserted. Engine suite 29/29.

### Blocker 2 — the ASM runtime ABI is equivalent; the sources were the problem

Measured, not assumed, by `tools/testing/asm_abi.py`. Every symbol RustCraft links
against is read out of the constant pools of its own compiled classfiles — what the JVM
resolves at link time, not what the source said — and classified against both jars.

```
verdict: RUNTIME_ABI_EQUIVALENT
referenced_symbols: 191
IDENTICAL_RUNTIME_ABI       149
SIGNATURE_ONLY_DIFFERENCE    42
REAL_ABI_DIFFERENCE          0
MISSING                      0
```

All 42 signature-only differences are `List<Foo>` against the erased `List` — a
`Signature` attribute, which erasure removes and the linker never reads. Members resolve
along the superclass chain, because a reference to an inherited member names only the
subclass; nine of the initial `MISSING` results were that lookup bug, not a finding. The
join key is name plus parameter list with the return type compared separately, because a
changed return type is the difference that matters and keying on the full descriptor makes
it unfindable.

The verdict is worth nothing unless the classifier can detect a break, so
`tools/testing/test_asm_abi.py` builds a real jar with one entry replaced by a real
compiled classfile and asserts the classifier names that exact break: widened field →
`REAL_ABI_DIFFERENCE`, removed field → `MISSING`, severed superclass chain → `MISSING` on
the inherited members, lost type argument → `SIGNATURE_ONLY`, untouched member →
`IDENTICAL`, and a broken jar flips the verdict. 10/10.

The twelve real compile errors were therefore what §7 said they were. `AsmTreeCompat`
holds the only raw casts in the tree and fifteen call sites route through it. No
reflection, no second ASM on the classpath, no `Object` loops: the casts insert a
`checkcast` where the compiler emits one and collapse where it does not, so a list that
does not hold its declared type fails at the use rather than producing a silently wrong
identity.

Same sources, both pins (§10): the identity tool builds against each target's own ASM
with receipts recording the jar and its hash, and the harness bootstrap compiles 116
classfiles against both jars with zero errors.

Runtime linkage, which a static comparison cannot establish (§11).
`tools/testing/asm_linkage_proof.py` enumerates ASM jars in each runtime tree and
**refuses to report anything** if a Clean Forge jar is present in the Revelation tree,
including one merely downloaded — a jar on disk is a jar someone can put on a classpath.
Revelation holds one distinct ASM build, `asm-all-5.2`, in two identical copies. The
identity tool built against each jar then runs on twelve real transformed Minecraft
classfiles with that target's ASM as the only ASM present, and the receipts are
byte-identical: `RUNTIME_LINKAGE_EQUIVALENT`.

## A pre-existing failure found while re-running the controls, not caused by this work

`tools/writer-plan-v2-tests/run.py` fails at HEAD, before any of the above. Its
`historical-raw` control regenerates `LiveWriterPlan.java` from the committed profile and
manifest and asserts the result is byte-identical to the committed file. It is not:

```
committed   REQUIRED_HOOKS_MANIFEST_SHA256 = 738f672dd699ffb1…
regenerated                              = 9fa1e033ef715b33…   (the committed manifest)
```

Reproduced from `HEAD` blobs alone, with the working tree's modified files set aside, so
it is independent of the ASM work. The generated plan records a manifest hash that the
committed `required-live-writer-hooks.json` does not have.

It was not regenerated here. `LiveWriterPlan.java` is the plan the live transformers
enforce, and changing it is a scope change that requires its own requalification — not a
cleanup to fold into an ASM compatibility fix. It is reported for a decision.
