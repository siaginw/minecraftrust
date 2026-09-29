# RustCraft — project status

This is the canonical status document. It is updated when a qualification or campaign milestone changes; historical detail lives in [docs/research/](research/) and in the evidence tree under [`machine/`](../machine/). For the public overview, see the [README](../README.md); for the plan, the [roadmap](ROADMAP.md).

**Snapshot date:** 2026-09-29 · **Head at snapshot:** `ed9e778`

---

## Current evidence

| Runtime | Qualification | Authority | Live shadow |
|---|---|---|---|
| Clean Forge 14.23.5.2860 | `CLEAN_FORGE_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED | **OFF** | V1 completed in its own historical scope; **V2 Phase-D smoke 32/32 semantic, 0 mismatch** |
| FTB Revelation 3.4.0 (219 mods) | `REVELATION_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED · **real-launch admission: `REAL_FML_TRANSFORM_CAPTURE` / PASS** | **OFF** | **V2 Phase-D smoke 32/32 semantic, 0 mismatch; first closure campaign 903 counted passes / 0 mismatches — coverage open** |

Production authority is **OFF** everywhere. Java remains authoritative;
`M4NativeStatePayload.tryEncode` returns `null`;
`CaptureContract.productionAuthorityEligible()` returns `false`; no Rust chunk
packet has ever reached the production wire.

### Claim hygiene — read before quoting any number

- **Counted closure campaign (Revelation, V2):** 903 COMPARE_PASS / 0
  COMPARE_MISMATCH / 0 INFRA_FAILURE / 0 DISQUALIFIED. Closure is **not met**:
  the predeclared criteria require ≥2,000 comparisons, ≥300 distinct chunk
  incarnations, and ≥20 reload cycles; measured 903 / 135 / 0. The measured
  limiter was workload-client chunk diversity under the server's normal
  movement handling — not an observed parity failure. A stopped hop-traversal
  experiment added 559 passes (0 mismatches) and is **not** merged into the
  counted denominator; its receipts are preserved separately.
- **Clean Forge V1 live shadow:** 2,372/2,372 byte-identical within its own
  qualified historical scope.
- **Revelation V1:** no completed live shadow and no parity claim; prototypes
  archived at `archive/revelation-v1-shadow`.
- **OFFLINE_QUALIFIED** (V2) means the transformation, identity, admission,
  chain and frame evidence for a pinned runtime was proven in-process and
  independently witnessed. It does not mean production-authoritative, and until
  the Phase-D smokes and closure campaign above it did not mean live-parity
  either.

### What each proof is

| Proof | What actually happened |
|:---|:---|
| Real-launch admission | A real FML JVM (full mod lifecycle, real Phosphor mixins writing launch-scoped `MixinMerged#sessionId` provenance) was admitted by the *same* engine used offline: 12 definitions bound, no downstream transformers after the writers, fresh per-process certificates. |
| 219-mod compatibility | A headless client joins the Revelation server: FML handshake complete against the pack's real `NetworkCheckHandler` (client inventory derived from the runtime's own jars by FML's exact version-resolution order), PLAY reached, JoinGame, KeepAlive, 20 s stability, clean disconnect. |
| Coherent capture | acquire → clone → seal → release under a single-writer gate; the gate is never held during Rust work; end-view equality checked at commit. |
| RCSNAP02 | The Revelation registry (157,010 states / 18-bit width) is decoupled from snapshot representation: chunks are admitted per *actual* state IDs, carried as section-local u16 palettes (~873 B/section measured vs 8,192 raw). Cross-language fixture from a real chunk: Rust encode byte-identical to Java's authoritative packet (37,159 bytes). |
| Closure campaign | 3 admitted JVM sessions over a persistent pre-generated world; deterministic movement; disconnect/reconnect; taxonomy counted from the shadow journal and reconciled. Receipts: `target/closure-campaign/rev-closure-8` (counted) and `rev-closure-7/9` (evidence). |

---

## ARCHITECTURE

RustCraft is a Rust-owned engine behind a Java/Forge compatibility shell. The
compatibility layer is deliberately a shell: it captures, admits and
witnesses, and it yields authority to Rust only where the Rust engine is
qualified to hold it, falling back to Java at a counted, fail-closed boundary
everywhere else. The public overview with diagrams is
[docs/ARCHITECTURE.md](ARCHITECTURE.md); the mechanisms:

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
declaration-order identity, and the structural set of masked sites. The
canonical profile generator now carries these invariant rows natively; the
real-launch runner consumes it unchanged (runtime completion remains only as a
documented legacy fallback for historical contracts).

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

Launch-shape tolerance is explicit in the engine: the offline profile is
lifecycle-free, so a real launch may register additional FML-owned
transformers (measured: `ModAPITransformer`) and reports coremod jars from its
own game directory. The profiled chain must appear **in order** inside the
observed chain (additions recorded with origin artifact and hash); coremods
compare by plugin class + artifact basename + artifact content SHA-256 —
disposable launch paths are provenance, never identity.

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

A **real** FML server launch participates in the same architecture via the
`LiveSessionAdmissionTweaker` launch shape (session environment from launch
properties, entry observer at the chain front, writers at the tail, one shared
evidence producer). The engine accepts exactly two capture kinds — the offline
oracle's and the real launch's — with every downstream node unchanged.

### Same-process chains

A session certificate is an **admission** certificate: it authorizes the
pre-writer buffer and says nothing about what happened afterwards. The chain
does. `TransformationChainEvidence` renders it from in-process state only, and
every gap stops the render rather than being filled in — an unnamed entry
buffer, an unobserved hook, a stage whose input equals its output, two
definitions of one binary name. Definition claims bind to the passive agent's
observed final definitions: only the attempt whose output bytes are what the
loader actually defined may claim the definition, one claim per class.

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

## Closure campaign

The campaign asks a different question from the pipeline proofs: *does the
Rust full-chunk encoder remain semantically equivalent over enough real state
diversity, lifecycle churn, I/O origin, incarnations and reloads to justify an
authority review?*

Predeclared criteria (encoded once in `tools/live-shadow-v2/closure.py`,
pinned by `test_closure.py`): ≥2,000 comparisons · 0 unexplained mismatches ·
drop ≤10% · exclusion ≤60% · ≥200 I/O-origin · ≥300 incarnations · ≥20 reload
cycles. The parity denominator is exactly COMPARE_PASS + COMPARE_MISMATCH;
EXCLUDED and DROPPED never count as passes.

**First campaign (counted, receipts in `target/closure-campaign/rev-closure-8`):**

| Metric | Result | Criterion |
|:---|:---|:---|
| COMPARE_PASS | **903** | ≥ 2,000 — unmet |
| COMPARE_MISMATCH | **0** | 0 unexplained — met |
| I/O-origin comparisons | 903 | ≥ 200 — met |
| Distinct incarnations | 135 | ≥ 300 — unmet |
| Reload cycles | 0 | ≥ 20 — unmet |
| Drop rate | 0.0% | ≤ 10% — met |
| Exclusion rate | 39.9% (73 HIGH_STATE_ID, rest TE-bearing) | ≤ 60% — met |
| INFRA_FAILURE / DISQUALIFIED | 0 / 0 | — met |

Every session issued its own session-bound certificates and passed
real-launch admission. The limiter was measured precisely: the headless
client's long-distance movement is rejected by the server's normal
anti-cheat (rubber-banding to spawn), so chunk diversity stays bounded at the
~46-chunk spawn area per session. Extending duration does not fix this; the
same-semantics remedies are real-speed walking or a server-side teleport
orchestration outside the permitted generic client actions. **Parity evidence
stayed clean; coverage came up short** — reported as not met, criteria
unmoved.

A stopped hop-traversal experiment (`rev-closure-9`: 559 passes, 0
mismatches) and the earlier fresh-world runs (`rev-closure-7`, where one
session tripped the coherence gate's off-owner containment during worldgen
churn — the gate refusing to compare incoherent state, by design) are
preserved as evidence but excluded from the counted denominator.

---

## Abandoned & fail-closed

- **V1 retained Rust snapshots** (`M4NativeStatePayload.tryEncode`): returns
  `null` permanently (M5.8-HOLD). Cross-thread biome/light writers made the
  live retained snapshot CAPTURE_UNSAFE; rather than risk it, the path is
  closed forever. V2 coherent capture (seal-then-release) replaces the
  approach, not the gate.
- **Rust production authority**: no code path enables it without a separate
  recorded authority review. Even on perfect closure, authority promotion is
  its own decision.

---

## REPOSITORY LAYOUT

```
crates/        Rust engine and support crates (21)
versions/      version adapters
tools/bridge/  Java/Forge compatibility bridge (writers, identity, certificates)
tools/forge-capture/    offline harness: bootstrap, agents, oracles
tools/qualification-v2/  the generic two-launch qualification driver
tools/live-capture/      hook manifest, plan generator, pinned profiles
tools/live-shadow-v2/    headless probes, comparator, closure evaluator, campaigns
tools/testing/     QualificationEngine and its Python evidence model
tools/*-tests/     synthetic control suites (writer-plan, qualification-v2)
benchmarks/  benchmarks and performance research
machine/     committed qualification evidence, per milestone
docs/        architecture, research, engineering notes, this status
docs/foundation/  the original charter and planning documents (historical)
```

Untracked, deliberately preserved parked research (not generated junk, not
ignored, not currently called by any lane):

- `tools/guarded-runtime-experiment/`
- `tools/retention-audit/`

### Branches and worktrees

`main` is the active development line. Historical lines are kept as archival
references, not deleted:

- `archive/issue1-jni-v2` — the Issue #1 / Revelation V1 committed line.
- `archive/revelation-v1-shadow` — the two uncommitted Revelation V1
  prototypes, preserved byte-for-byte in one commit.
- `archive/pre-hardening-main` (tag) — local `main` before the promotion.
- `rustcraft/revelation-v2-offline-qualified` (tag) — the V2 checkpoint.

`D:\minecraftrust` is retained as an **archived** worktree: its ignored
`target/` and `.rustcraft-local/` directories hold roughly a thousand files
of V1 campaign evidence that exists nowhere else.

Issue #1 (live capture coherence + the section-5 packet-mask mismatch) was
closed 2026-09-29 as superseded by the V2 architecture, with the evidence
trail in the issue.

---

## Research environment

Qualification, capture and campaign work requires externally obtained,
hash-pinned artifacts (none distributed by this repository): Temurin JDK 8
(`8.0.504`), the Minecraft 1.12.2 server jar, Forge `14.23.5.2846` /
`14.23.5.2860`, and the modpack jars. The build scripts compile the bridge
against *that runtime's own* SRG jar and ASM, refuse substitutes, and the
probes check the port and pins before every launch.

---

## Current blocker and next objective

**Blocker:** closure coverage — workload generation under the server's
movement handling (see [the campaign section](#closure-campaign)). This is a
workload-design decision for review, not a parity or measurement failure.

**Next major engineering objective (in order):**

1. Rust **packet authority review** (after closure coverage is met)
2. Retained Rust **ChunkState** (Phase 2)
