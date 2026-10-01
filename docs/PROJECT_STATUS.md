# RustCraft — project status

This is the canonical status document. It is updated when a qualification or campaign milestone changes; historical detail lives in [docs/research/](research/) and in the evidence tree under [`machine/`](../machine/). For the public overview, see the [README](../README.md); for the plan, the [roadmap](ROADMAP.md).

**Snapshot date:** 2026-10-01 · **Head at snapshot:** `9d1fe2b`

---

## Current evidence

| Runtime | Qualification | Authority | Live shadow & Retained State |
|---|---|---|---|
| Clean Forge 14.23.5.2860 | `CLEAN_FORGE_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED | **BOUNDED_AUTHORITY_EXPERIMENT (PASS, 32/32)** | Gate A bounded authority: 32 Rust / 137 Java fallback. **Retained ChunkState active: 32/32 packets served from native memory (`retained_rust_selected: 32`)** |
| FTB Revelation 3.4.0 (219 mods) | `REVELATION_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED · **real-launch admission: `REAL_FML_TRANSFORM_CAPTURE` / PASS** | **BOUNDED_AUTHORITY_EXPERIMENT (PASS, 64/64)** | **Closure CLOSED: 4,905 counted passes / 0 mismatches** (`V2_LIVE_SHADOW_CLOSURE_REPORT.md`); Gate B bounded authority: 64 Rust / 105 Java fallback. **Retained ChunkState active: 64/64 packets served from native memory (`retained_rust_selected: 64`)** |

Unconstrained production authority remains **`false`** everywhere (`PacketAuthorityExperiment.PRODUCTION_AUTHORITY = false`). Rust authors chunk packets strictly within the bounded experiment (`-Drustcraft.packetAuthorityExperiment=true`) under an explicit operator cap (`-Drustcraft.packetAuthorityCap`). All out-of-scope, TileEntity, high-state, or post-cap chunks fail closed to pure Java serialization.

### Claim hygiene — read before quoting any number

- **Live-Shadow Closure Campaign (Revelation, V2):** 4,905 COMPARE_PASS / 0
  COMPARE_MISMATCH / 0 DROPPED / 475 EXCLUDED across 2 independent JVM sessions.
  Predeclared criteria exceeded: denominator 4,905 (threshold 2,000), 4,546
  incarnations (threshold 300), 491 reload cycles (threshold 20), 4,885 I/O-origin
  comparisons (threshold 200). Status: **`LIVE_SHADOW_CLOSED`**.
- **Formal Authority Review:** Executed and documented in
  `docs/research/PACKET_AUTHORITY_CONTRACT.md`. Binding receipt verified in
  `target/authority-review/closure-input-receipt.json`. Status: **`AUTHORITY_REVIEWED`**.
- **Bounded Authority Experiment:** 96 total Rust-authored packets reached real
  clients (Gate A: 32/32 on Clean Forge 2860; Gate B: 64/64 on FTB Revelation 2846).
  0 Rust encode failures; 100% fail-closed Java fallback observed once cap was
  exhausted. Status: **`BOUNDED_AUTHORITY_EXPERIMENT`** (Receipts: `target/authority-smoke/`).
- **Retained Rust `ChunkState` Engine:** Persistent native chunk memory layout
  with flat Morton-indexed arrays and static section wire byte caching. Seeded once
  from RCSNAP02 transport, 0.70 µs static re-encode latency (261x speedup over
  ephemeral baseline). Gate A (32/32) and Gate B (64/64) smoke passed under live
  client probe with zero desyncs. Status: **`RETAINED_RUST_CHUNKSTATE_PROVEN`**
  (`docs/research/RETAINED_CHUNKSTATE_COMPLETION_REPORT.md`).
- **Next Milestone:** **`READY_FOR_RUST_CHUNKSTATE_API_DELEGATION`**.

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

## Current state and next objective

**Completed milestones:**
1. Rust **packet authority review** and bounded fail-closed authority experiment (`BOUNDED_AUTHORITY_EXPERIMENT_REPORT.md`).
2. Retained Rust **ChunkState** engine ownership migration (`RETAINED_CHUNKSTATE_COMPLETION_REPORT.md`): flat Morton memory layout, one-time RCSNAP02 seeding, static section wire byte caching (0.70 µs, 261x speedup), live smoke Gate A (32/32) and Gate B (64/64) passed.

**Next major engineering objective (in order):**
1. Rust **ChunkState API Delegation**: progressive delegation of read/write queries (`getBlockState`, `setBlockState`, light updates) directly to native Rust memory.
2. Direct native Anvil/Region I/O ingest bypassing Java chunk primer allocations.
