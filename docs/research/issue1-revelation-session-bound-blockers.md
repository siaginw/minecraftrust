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
