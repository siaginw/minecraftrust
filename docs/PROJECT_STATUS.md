# RustCraft — qualification status and architecture

This is the canonical status document. It is updated when a qualification
milestone changes; historical detail lives in `docs/research/` and in the
evidence tree under `machine/`.

---

## CURRENT STATUS

| Runtime | Result | Authority | Live shadow |
|---|---|---|---|
| Clean Forge 14.23.5.2860 | `CLEAN_FORGE_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED | OFF | V1 completed in its own historical scope |
| FTB Revelation 3.4.0 | `REVELATION_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED | OFF | **NOT STARTED** under V2 |

Production authority is **OFF** in both. Java remains authoritative
everywhere; `M4NativeStatePayload.tryEncode` returns `null`;
`CaptureContract.productionAuthorityEligible()` returns `false`; no Rust chunk
packet reaches the production wire.

### Claim hygiene — read before quoting any number

- **Clean Forge V1 live shadow:** 2372/2372 byte-identical, within its own
  qualified historical scope. This is the only completed live shadow.
- **Revelation V1:** **no** completed live shadow and **no** parity claim. The
  prototypes are archived at `archive/revelation-v1-shadow`.
- **V2 OFFLINE_QUALIFIED** means: the transformation, identity, admission,
  chain and frame evidence for a pinned runtime has been proven in-process and
  independently witnessed. It does **not** mean production-authoritative, and
  it does **not** mean live-parity-qualified.

---

## ARCHITECTURE

RustCraft is a Rust-owned engine behind a Java/Forge compatibility shell. The
compatibility layer is deliberately a shell: it captures, admits and witnesses,
and it yields authority to Rust only where the Rust engine is qualified to hold
it, falling back to Java at a counted, fail-closed boundary everywhere else.

### Identity: exact vs session-bound

Every qualified class carries one of two identity contracts.

**`CANONICAL_ID_V2` (exact).** For classes whose bytes are reproducible across
launches. The profile pins raw, semantic and declaration-order digests; the
engine recomputes all three from the run's own bytes.

**`CANONICAL_ID_V2_SESSION_BOUND` (session-bound).** For classes a mixin
framework has stamped with a per-launch session id. Their exact bytes are a
property of *one* launch and cannot be pinned in a static profile. What does
survive a relaunch — measured, not assumed — is the session-INVARIANT identity
(a masked projection with the per-launch value normalised), the
declaration-order identity, and the structural set of masked sites.

A runtime is therefore described by a **mixed** profile: exact where bytes are
stable, session-bound where they are not. The split is derived by running
discovery twice and refusing to pin anything whose classification is not
stable across both launches.

### Static recipe vs dynamic observation

This is the layer boundary the whole architecture rests on.

- The **static recipe** identifies what was authorized *before* the run: the
  hook manifest pin, the hook inventory, target classes/methods/descriptors,
  the exact-class identity inventory, the session-bound inventory with its
  invariants and provenance, the admission policies, and the loader policy.
  Its canonical hash is `recipe_binding_sha256`.
- The **qualifying observation** proves what happened *during* the run:
  runtime-issued certificates, the same-process acquisition record, the
  transformation chain, the independent frame witness, and the bytes the loader
  finally defined.

The recipe hash deliberately excludes everything the run produces. Folding
launch evidence into a pre-launch authorization would make the authorization
unsatisfiable and the evidence self-certifying.

The policies are **not** covered by the recipe hash — each policy carries the
hash itself, so including them would be self-referential. A policy is bound at
the point of use instead: every certificate carries `policy_sha256`, and the
engine refuses a certificate naming another policy.

### Two-launch qualification

`tools/qualification-v2/qualify_runtime.py` implements the generic flow:

```
DISCOVERY LAUNCH x2   (writers OFF; cannot issue a certificate)
      -> stable facts only, derived by agreement between the two launches
   -> static mixed recipe + SessionBoundAdmissionPolicy documents
   -> generated LiveWriterPlan (determinism checked; no process facts)
QUALIFYING LAUNCH     (writers ON; fresh process + session identity)
      -> B admits against the static policy and issues B's own certificates
      -> same-process acquisition: PRE -> RUSTCRAFT_POST -> downstream -> FINAL
      -> independent FrameRelationWitness of what the loader defined
   -> QualificationEngine decides
```

Discovery may state what is stable; it may not authorize the qualifying run
with process-specific evidence. The qualifying run proves itself.

### Same-process chains

A session certificate is an **admission** certificate: it authorizes the
pre-writer buffer and says nothing about what happened afterwards. The chain
does. `TransformationChainEvidence` renders it from in-process state only, and
every gap stops the render rather than being filled in — an unnamed entry
buffer, an unobserved hook, a stage whose input equals its output, two
definitions of one binary name.

`FINAL_DEFINED` is a **definition event, not another transformer edge**. When
the writers are the last transformers the loader defines exactly the buffer
they returned, so the final stage is permitted to confirm rather than transform
— but only when an independent witness verifies those same bytes, the process,
session, class and loader agree, and hooks and exception paths survive. A final
stage that alters bytes with no downstream transformer to explain it is a FAIL;
one that confirms while a downstream transformer is disclosed is a FAIL too,
because the chain is concealing a stage.

### Exception contracts

Every injected hook carries an explicit, provable exception contract. The
producer reads it out of the emitted bytes rather than looking it up by hook
type:

- **SCOPED_RETHROW** — a begin/end lifecycle whose handler reports the failure
  and rethrows the original Throwable. Behaviour is in the game method itself.
- **ISOLATED_CALLEE** — a one-shot observation with no lifecycle to close. Its
  containment lives in a non-throwing qualified wrapper
  (`LiveWriterHooks.safe*`), so the game method keeps a single
  `INVOKESTATIC` and an untouched exception table. The wrapper's own bytecode
  is inspected: one non-self facade call, a catch-all actually containing it,
  no rethrow, no recursion. A `safe*` name with the catch deleted or narrowed
  to `Exception` reports as *no coverage*.
- **CALLER_ISOLATION** — a catch-all in the game method itself, where already
  established.

Every path carries `wrapper_body_sha256`, binding the callsite to the exact
implementation that ran.

### Independence

Nothing certifies itself. The chain is written by the transforming process;
the frame witness is written by a separate observation point; the engine
compares both and fails the run when they disagree.

---

## GENERIC MODPACK NORTH STAR

RustCraft must not require a modpack to be hand-profiled.

Desired experience:

```
normal Forge/modpack startup
  -> generic discovery
  -> generic static contract generation
  -> runtime-issued process-specific evidence
  -> automatic capability negotiation
  -> Rust-owned engine
  -> narrow Java fallback only where required
```

Revelation was the first difficult test case, not a permanent special case.
There is no per-pack table and none may be introduced: the driver, the
identity verifier, the chain producer and the exception-contract model contain
no runtime, mod or pack names.

The explicit two-launch flow is qualification infrastructure today. The
intended evolution is: first boot discovers and qualifies; subsequent
unchanged boots verify a runtime fingerprint, reuse the static contract, and
issue fresh runtime certificates automatically.

---

## REPOSITORY LAYOUT

```
crates/        Rust engine and support crates
versions/      version adapters
tools/bridge/  Java/Forge compatibility bridge (writers, identity, certificates)
tools/forge-capture/  offline harness: bootstrap, agents, oracles
tools/qualification-v2/  the generic two-launch qualification driver
tools/live-capture/      hook manifest, plan generator, pinned profiles
tools/testing/     QualificationEngine and its Python evidence model
tools/*-tests/     synthetic control suites (writer-plan, qualification-v2)
benchmarks/  benchmarks and performance research
machine/     committed qualification evidence, per milestone
docs/        architecture, research, engineering notes
```

Untracked, deliberately preserved parked research (not generated junk, not
ignored, not currently called by any lane):

- `tools/guarded-runtime-experiment/`
- `tools/retention-audit/`

---

## ROADMAP

1. ~~repository consolidation~~ *(this document)*
2. V2 live-shadow campaign design and review — next
3. controlled live shadow
4. authority-readiness work
5. retained Rust state expansion
6. subsystem authority migration
7. optimization and complete-server benchmarks
8. broader automatic modpack qualification
9. multi-version expansion
10. optional custom runtime / JVM research

Nothing past (1) has been started under V2.
