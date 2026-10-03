# RustCraft — project status

This is the canonical status document. It is updated when a qualification or campaign milestone changes; historical detail lives in [docs/research/](research/) and in the evidence tree under [`machine/`](../machine/). For the public overview, see the [README](../README.md); for the plan, the [roadmap](ROADMAP.md).

**Snapshot date:** 2026-10-02 · **Head at snapshot:** `RUST_REGION_WRITE_AUTHORITY_PROVEN` (Gate A bounded live region-write authority; see `git log` for the exact HEAD)

---

## Current evidence

| Runtime | Qualification | Authority | Live shadow & Retained State |
|---|---|---|---|
| Clean Forge 14.23.5.2860 | `CLEAN_FORGE_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED | **BOUNDED_AUTHORITY_EXPERIMENT (PASS, 32/32)** | Gate A bounded authority: 32 Rust / 137 Java fallback. Retained ChunkState active. **Direct Netty wire emission verified: 32/32 packets emitted directly into Netty pooled ByteBufs with 0 leaks (`outstanding_direct_buffers: 0`), 100% byte parity (`32/32 shadow matches`)**. **Single-copy encoder bypass: 120 packets client-visible with the complete pre-compression body built in ONE payload copy, `NettyPacketEncoder` bypassed (committed == bypassed == 120), 168/168 full-body shadow byte-exact, 1/4/8-client broadcast clean** |
| FTB Revelation 3.4.0 (219 mods) | `REVELATION_PROFILE_REQUALIFIED_V2` / **PASS** / OFFLINE_QUALIFIED · **real-launch admission: `REAL_FML_TRANSFORM_CAPTURE` / PASS** | **BOUNDED_AUTHORITY_EXPERIMENT (PASS, 64/64)** | **Closure CLOSED: 4,905 counted passes / 0 mismatches** (`V2_LIVE_SHADOW_CLOSURE_REPORT.md`); Gate B bounded authority: 64 Rust / 105 Java fallback. Retained ChunkState active. **Direct Netty wire emission verified: 64/64 packets emitted directly into Netty pooled ByteBufs with 0 leaks (`outstanding_direct_buffers: 0`), 100% byte parity under 219 mods (`64/64 shadow matches`)**. **Single-copy encoder bypass: 276 packets client-visible (committed == bypassed == 276), full-body shadow 339 byte-exact + 28 arbitrated (decode-equivalent / concurrent-mutation, 0 unexplained) under 219 mods** |

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
- **Storage engine, first boundary (`RUST_REGION_WRITE_AUTHORITY` engine + shadow reads):** exact .mca contract implemented in `crates/region-io` (reader/writer/integrity scanner, validated atomic commits). Shadow READ on a real 219-mod Revelation world copy: **76,404 chunks Rust-vs-vanilla byte-exact, 0 mismatches**. Write authority proven on a disposable Gate A world copy: 200 vanilla-final chunk records committed by the Rust sector allocator, worldscan 0 bad entries, **fresh vanilla-Forge process reloaded the Rust-written world cleanly (0 load errors)**. In-server write hook + save/load MSPT A/B are the next increment; production authority remains OFF (`docs/research/RUST_STORAGE_NBT_ANVIL_REPORT.md`).
- **Live region write authority (`RUST_REGION_WRITE_AUTHORITY_PROVEN`, bounded):** the region engine now takes the FINAL region-file write inside a live server behind an ASM hook at the notch seam `ayj`/`func_76706_a` (entry admission skips the vanilla body on success; fallback notes + free-list mirroring keep Java coherent; generation tickets single-sourced in Java, seeded from engine floors). Gate A live session: **3,928 real Rust region writes, 0 fallbacks/0 errors, then 25/25 fresh vanilla restart cycles with full FML probe PASS on the Rust-written world**. Shadow campaigns: Gate A 1,463 payload comparisons 0 mismatches; Gate C 5,089 shadow writes, 2,853 comparisons 0 engine mismatches; write bench p50 15.8µs vs 25.4µs vanilla (p90 2.1x). Gate C live authority PARKED with cause: mod-shaded Anvil writers bypass `func_76706_a` (timestamp-evidenced) and could collide with a live Rust allocator until external-write observation lands. PRODUCTION_AUTHORITY stays false (`docs/research/RUST_REGION_WRITE_AUTHORITY_REPORT.md`).
- **Rust Network Compression (`RUST_NETWORK_COMPRESSION_AUTHORITY_PROVEN`):** the M2C subsystem re-proven on the single-copy architecture with a NEW direct zero-heap path (body ByteBuf memory -> JNI -> outbound buffer memory, heap payload bytes 0). Fresh 68,351-body Revelation-corpus matrix: 1.27-2.16x per-packet compressor speedup vs the vanilla Deflater at a vanilla-equivalent ratio (live wire +1.5%). Live: Gate A authority 356 + Gate C 1,184 + multi-client 2,382 Rust-compressed packets (direct 100%, 0 fallbacks), shadow 6,523 comparisons 0 mismatches, 100k stress green. Frame VarInt authority stays Java. Status: **PROVEN, READY_OPTIONAL (default OFF)** (`docs/research/RUST_NETWORK_COMPRESSION_AUTHORITY_REPORT.md`).
- **Single-Copy Netty Packet Body (`SINGLE_COPY_NETTY_PACKET_BODY_PROVEN`):** the
  admitted `SPacketChunkData` path builds ONE complete immutable pre-compression
  ByteBuf per packet (native measure -> header -> ONE payload write straight into
  the final buffer -> TE trailer) and a dedicated outbound handler writes it past
  `NettyPacketEncoder` into the untouched compression/framing chain. Gate A direct
  120/120 and Gate C direct 276/276 client-visible (FML handshake, PLAY, chunk
  decode, KeepAlive, clean disconnect); full-body shadow 168/168 byte-exact on
  Gate A and 339 byte-exact + 28 arbitrated with 0 unexplained mismatches on Gate
  C; 100k lifecycle stress green (created == released, outstanding 0); multi-client
  1/4/8 broadcast green. Server-thread emission benchmark: 0.46-1.51 us vs b3df84c
  pooled two-copy 0.79-3.62 us (~2.2x). Status: **`SINGLE_COPY_NETTY_PACKET_BODY_PROVEN`**
  (`docs/research/SINGLE_COPY_NETTY_PACKET_BODY_REPORT.md`).
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
- **Semantic Engine Ownership Inversion (`ChunkState` API):** Rust NativeChunk
  is the authoritative source of truth for admitted block reads/writes (`Chunk.getBlockState`
  and `Chunk.setBlockState`). Zero-JNI direct memory reads via Unsafe yield 110.7M ops/sec
  with 2.76 ns p50 (raw direct read: 360M ops/s, 2.77 ns avg). Authoritative mutations commit in Rust first, dispatching Forge
  callbacks in exact sequence. 10,000 differential fuzz operations pass with 0 divergences.
  Status: **`RUST_CHUNKSTATE_API_OWNERSHIP_PROVEN`**
  (`docs/research/RUST_CHUNKSTATE_API_AUTHORITY_REVIEW.md`).
- **Zero-JNI Direct-Memory Architecture Hardened & Validated:** Pointer lifetime proof
  confirmed across multi-threaded concurrency (4 readers, 1 writer, 0 crashes), 100 rapid unload/reload
  cycles with immediate Java record eviction and pointer zeroing, and section-emptying retention (no UAF).
  Sub-microsecond batched profiling eliminates OS timer 0 ns artifacts.
  Status: **`ZERO_JNI_DIRECT_MEMORY_VALIDATED`**
  (`docs/research/ZERO_JNI_DIRECT_MEMORY_VALIDATION_REPORT.md`).
- **Section Compatibility Layer Authority Expansion (Phase 2):** Semantic ownership expanded
  from `Chunk` methods to section storage `ExtendedBlockStorage.get` (`func_177485_a`) and
  `ExtendedBlockStorage.set` (`func_177484_a`). Mod ecosystem audit of 193 FTB Revelation jars confirmed
  targeting `ExtendedBlockStorage` avoids ASM bytecode collisions with NotEnoughIDs, FoamFix, and Phosphor.
  Zero-overhead zero-JNI reads operate at 2.74-4.52 ns/op. Validated under Gate A (32/32) and Gate C (64/64)
  live smokes and 10,000 differential fuzzing operations (0 mismatches).
  Status: **`SECTION_STATE_AUTHORITY_EXPANDED`**
  (`docs/research/SECTION_STATE_AUTHORITY_EXPANSION_REPORT.md`).
- **Cross-Language Memory Model Formally Proven & Sound:** Replaced plain non-atomic `[u16; 4096]`
  with `[AtomicU16; 4096]`, eliminating Rust abstract machine undefined behavior on concurrent foreign reads.
  Proven under 17,226,590 direct reads and 500,000 writes with 0 errors/crashes. Measured latency: 5.06 ns raw
  read, 16.45 ns mapped read.
  Status: **`CROSS_LANGUAGE_MEMORY_MODEL_SOUND_AND_PROVEN`**
  (`docs/research/CROSS_LANGUAGE_MEMORY_MODEL_RESOLUTION_REPORT.md`).
- **Block Light & Sky Light State Ownership Migrated & Hardened:** Migrated block light and sky light data arrays into
  Rust `NativeSection`. Resolved mixed-width atomic hazards by aligning backing storage to `[AtomicU32; 512]` (2,048 bytes),
  matching Java's 4-byte `compareAndSwapInt` and Rust's `AtomicU32::compare_exchange_weak` on exact word boundaries.
  Tested under 20,000,000 concurrent reads and 2,000,000 writes across 6 threads with 0 corruption (59.2M ops/sec).
  Status: **`RUST_LIGHT_STATE_AUTHORITY_EXPANDED`**
  (`docs/research/RUST_LIGHT_STATE_AUTHORITY_REPORT.md`).
- **Biome & Heightmap State Ownership Migrated (`[u8; 256]` & `[u16; 256]`):** Biome and heightmap data arrays are now
  authoritatively stored and mutated in Rust `NativeChunk`. Zero-JNI direct memory pointers (`biomesPointer`, `heightmapPointer`)
  enable Java `Chunk.getBiome` (~2.7 ns) and `Chunk.getHeightValue` (~2.8 ns) lookups with zero boundary translation overhead.
  Rust `recompute_height` features data-oriented downward scan acceleration skipping empty 16-block vertical sections via
  `primary_bit_mask` testing. Validated with 100,000 biome differential fuzzing operations (0 mismatches), 100,000 heightmap
  differential fuzzing operations (0 mismatches), and live server smokes on Gate A (32/32) and Gate C (64/64).
  Status: **`BIOME_HEIGHTMAP_AUTHORITY_PROVEN`**
  (`docs/research/RUST_BIOME_HEIGHTMAP_AUTHORITY_REPORT.md`).
- **NativeChunk Core Performance Maximized & Workload Proven:** Completed a comprehensive engineering
  pass over the entire Rust `NativeChunk` core backed by real FTB Revelation 3.4.0 server profiles and MCA region surveys.
  - **Real Revelation Palette Survey (2,970 sections / 500 chunks):** Discovered that 62.2% of Revelation sections have
    cardinality 17..32 and 8.2% have 33..64 (mean 19.9, median 20.0). Built a 64-element direct lookup table in
    `NativeSection::pack_states_to_words`, moving **92.3% of modded sections into zero-allocation L1 cache fast paths**.
  - **Java Bridge Key Allocation Elimination:** Refactored `ChunkStateAuthorityBridge.chunkKey` from String concatenation
    (`dim + ":" + cx + ":" + cz`) to a packed 64-bit coordinate `((long) cx << 32) | (cz & 0xFFFFFFFFL)` with `Map<Long, ChunkAuthorityRecord>`.
    Eliminated all Young Gen GC heap churn from bridge queries.
  - **Future Consumer Benchmark Suite:** Added spatial neighbor queries (3x3: 210 ns, 5x5: 530 ns) and synthetic future-consumer
    workloads (Collision AABB: 26 ns, Lighting 6-neighbor: 24 ns, Pathfinding 32-step walk: 78 ns, Storage 65k scan: 10.2 µs,
    Worldgen 4,096 bulk fill: 1.67 µs).
  - **Live Server Smoke Validation:** Clean Forge 2860 (Gate A) passed 32/32; FTB Revelation 2846 (Gate C, 219 mods) passed 64/64
    with zero encode failures, zero packet desyncs, and immediate fail-closed Java fallback upon cap exhaustion.
  - Status: **`NATIVE_CHUNK_PERFORMANCE_PLATEAU_PROVEN`**
    (`docs/research/NATIVE_CHUNK_PERFORMANCE_CLOSURE.md`, `docs/research/NATIVE_CHUNK_PERFORMANCE_SCORECARD.md`, `docs/research/NATIVE_CHUNK_REAL_SERVER_PROFILE.md`, `docs/research/NATIVE_CHUNK_MEMORY_GC_REPORT.md`, `docs/research/NATIVE_CHUNK_JNI_COPY_AUDIT.md`).
- **True Direct Packet Path Closed (Network Boundary Milestone):** Completed the elimination of legacy migration infrastructure (`CaptureDraft.extract`, `OwnedPacketSnapshot`, `RCSNAP02` transport byte array, `IN_BUF`/`OUT_BUF` intermediaries). The retained fast path encodes Morton section wire cache directly into Netty's pooled off-heap direct buffer address (`encodePacketPayloadV2` writing to `directBuf.memoryAddress()`), which is transferred directly into the Netty socket `PacketBuffer`.
  - **Payload copies:** Exactly 1 payload copy across the entire pipeline (0 copies for synthetic in-place framing; down from 2-3 copies).
    *(Historical claim for THIS milestone's boundary. Superseded by the later single-copy proof, which builds the complete pre-compression body with ONE payload write into the final buffer — see `SINGLE_COPY_NETTY_PACKET_BODY_REPORT.md`. The two claims measure different boundaries and do not contradict.)*
  - **JVM Heap Allocation:** Exactly 0 B (saves 49,480 B per chunk packet, preventing ~9.9 MB/s of Young Gen GC churn).
  - **Latency:** 4,566 ns mean / 2,700 ns p50 (2.14x speedup over vanilla 9,749 ns baseline).
  - **Safety & Lifetime:** 14/14 unit contract tests passing (`DirectNettySafetyAndLifetimeTest.java`); backpressure bounded (`MAX_OUTSTANDING_DIRECT_BUFFERS = 128`); 30s timed eviction prevents dangling packet leaks.
  - **Live Verification:** Gate A (32/32 direct shadow matches, 32 committed, 0 leaks, 0 fallbacks) and Gate C (64/64 direct shadow matches under 219 mods, 64 committed, 0 leaks, 0 fallbacks). Strict governance preserved: `PRODUCTION_AUTHORITY = false`.
  - Status: **`TRUE_DIRECT_PACKET_PATH_CLOSED`**
    (`docs/research/TRUE_DIRECT_PACKET_PATH_CLOSURE.md`, `docs/research/DIRECT_NETTY_WIRE_EMISSION_REPORT.md`).

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
3. **Region I/O engine** (`RUST_STORAGE_NBT_ANVIL_REPORT.md`): exact .mca reader/writer/scanner, 76,404 shadow reads byte-exact, write durability proven.
4. **Bounded live region-write authority** (`RUST_REGION_WRITE_AUTHORITY_REPORT.md`): Gate A live authority (3,928 writes, 25/25 restarts) + writer-attribution-complete shadow proof on both gates; Gate C live authority gated on external-writer coexistence, now resolved by attribution (goal §5: 0 UNKNOWN writers) + verify-before-free disqualification.

**Next major engineering objective (in order):**
1. **Save-burst performance closure** for region authority (MSPT / save-completion A/B on eligible regions).
2. **Direct payload staging** at the region seam (eliminate the one heap→direct copy; correctness first, benchmarked separately).
3. Rust **ChunkState API Delegation**: progressive delegation of read/write queries (`getBlockState`, `setBlockState`, light updates) directly to native Rust memory.
