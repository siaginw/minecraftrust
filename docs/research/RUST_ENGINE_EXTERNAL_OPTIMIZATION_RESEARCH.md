# RUST_ENGINE_EXTERNAL_OPTIMIZATION_RESEARCH

Research database for the external-research-first optimization workflow
(§44 policy: every OPT-* item researches externally BEFORE implementing).
All records researched 2026-10-09 via live web search (sources cited;
versions/status as observed at research time — refresh before relying on
them later; §35 research-caching).

## OPT-LIGHT-003 — section-local world access (active target)

### R-L003-01 — PaperMC Starlight (ALGORITHM_PORT_CANDIDATE)
- Project: PaperMC/Starlight — https://github.com/PaperMC/starlight
- Language: Java (patched into Paper/Fabric servers); license MIT —
  https://github.com/PaperMC/Starlight/blob/fabric/TECHNICAL_DETAILS.md
  studied (concepts only; no code copied)
- Status at research: mature/merged into Paper; THE reference MC light
  engine rewrite
- Transferable ideas:
  - Queue entries CARRY the light value being propagated → check ONE
    neighbor instead of six (~6–12× fewer block gets in their tests)
  - Conditional-opacity flags on queue entries skip shape lookups
  - Per-section precomputed metadata (opacity bitsets + heightmaps) to
    skip block reads during source setup
- Fit: IMPLEMENTED 2026-10-09 as OPT-ALG-001 after the operator
  explicitly authorized algorithm changes (equivalence + mod compat are
  the bar, not algorithm identity). Direct-apply carried entries in the
  addition pass; removal pass kept legacy-exact. Equivalence proven:
  100k fuzz PASSED, per-job differ 0/1,456, corpus digest + total_rc
  identical. First guard-based draft was UNSOUND on stale baselines —
  caught by the corpus digest, root-caused via a per-job differ, fixed
  by direct-apply + consistent-baseline corpus re-solve. Live: Gate A
  PASS (kernel time −18%: JNI 106.0s vs 129.3s at 45,732 committed),
  Gate C PASS (30,469 committed, 0 errors). The unsound-draft lesson is
  now a standing rule: guards over reads change the fail-closed
  boundary and baseline reach — only direct-apply schemes are sound on
  consistent baselines.

### R-L003-02 — slotmap 1.1.1 (DIRECT_DEPENDENCY_CANDIDATE — deferred)
- https://crates.io/crates/slotmap — maintained; generational keys;
  justified unsafe. Registry-key alternative if the global HashMap is
  ever the hotspot. NOT needed for OPT-LIGHT-003: the job-local cache
  removes the map from the hot path entirely (100% hit rate at 8 slots).

### R-L003-03 — associative-cache 3.0.1 (evaluated-equivalent)
- https://crates.io/crates/associative-cache — fixed-capacity N-way.
  Our 8-slot linear-scan IS this pattern; §17: a dependency would add
  cost without measurable gain at N=8. LOCAL IMPLEMENTATION WINS.

### R-L003-04 — slab (REJECTED for future use)
- RUSTSEC-2025-0047 (memory exposure / bounds check, Aug 2025). Avoid.

### R-L003-05 — voxel-engine practice (context)
- TwigCoder/voxel (Rust, GitHub), bevy-voxel devlogs ("500M voxels/sec"):
  all converge on section-resident data + neighborhood-local access;
  no crate importable for our registry seam. Confirms the access-locality
  hypothesis that the corpus then measured (92.8% same-section).

## Shortlists for future OPT items (record-only, §30–§32)

### OPT-COMP (compression)
- zlib-rs via flate2 (IN TREE already — flate2 `zlib-rs` feature):
  fastest maintained overall (rust-lang/flate2-rs README; Trifecta's
  "fastest WASM zlib" writeup)
- zune-inflate — fastest pure-Rust inflate (Reddit launch post)
- zenflate (imazen) — claims 13–15% over zlib-rs, ~5% off C libdeflate
- libdeflater — libdeflate bindings (C dep, SIMD)
- Status: research-only; production compression unchanged this phase.

### OPT-REG (region I/O)
- Positioned reads / mmap / append-only-store experiments: community
  experiment (r/Minecraft, 2025) measured mmap+index chunk stores —
  concept recorded; Windows-first policy makes mmap a careful candidate
  only with profile support.

### State storage (CLOSED by the §48 bake-off; reopen only with a
substantially better external candidate)
- Palette compression references: voxel.wiki palette-compression;
  longor.net voxel palette writeup; Minecraft Chunk format (direct
  global palette precedent — our direct-u32 mirrors it).
## OPT-SYNC-004 — bulk palette extraction (researched 2026-10-09)

The candidate was ALREADY implemented (OPT-SYNC-002's packed transfer)
but DEAD in production — dv2-probe proved `initFastExtract`'s
`Class.forName` cross-loader failure had disabled the packed path on
every section since it shipped, so the per-entry reflective path
(≤256 M_PAL_BYID invokes/section) was the only thing running. The fix
(live-object-graph class derivation) made the "candidate" live; the
dv2-vs-dv4 same-workload A/B settled the comparison empirically
(extraction 151→24 µs/section, registration 638→254 ms). External
survey (post-hoc confirmation the chosen design matches the ecosystem):

- R-S004-01 — [Minestom Palette](https://github.com/Minestom/Minestom)
  (`net.minestom.server.instance.palette.Palette`; [javadoc](https://javadoc.minestom.net/net.minestom/server/instance/palette/package-summary.html)):
  three storage models (single-value / indirect palette / direct),
  bulk long-array `networkRead` + EntryConsumer iteration — the SAME
  packed-words + palette-table transfer shape our packed path uses.
  Apache-2.0. Design-reviewed (not benchmarked — our own packed path
  already existed and won on the real workload).
- R-S004-02 — [Minecraft chunk wire format](https://minecraft.wiki/w/Java_Edition_protocol/Chunk_format)
  ([SO: reading the data section](https://stackoverflow.com/questions/76440587/how-do-i-read-the-data-section-in-chunk-data-minecraft)):
  the authoritative LSB-first packed long array + palette — identical to
  our `pack_states_to_words`/expansion contract.
- R-S004-03 — [FerrumC chunk-data refactoring](https://github.com/ferrumc-rs/ferrumc/issues/229):
  Rust Minecraft server pursuing the same native-side palette expansion;
  validates moving decode across the JNI boundary. Issue thread
  reviewed; no importable code (different engine).
- Prior research reused: JNI bulk-transfer patterns (Oracle spec /
  Shipilev / SO trade-offs — OPT-MIRROR-001 entry); zero new JNI was
  added (the packed transfer was already one crossing).

Rejections: per-entry reflective (the incumbent — lost the A/B);
palette identity cache (OPT-SYNC-003: first-touch reuse ≈ 0).

## OPT-SYNC-005 — chunk-envelope attribution: verification vehicle + JNI batching + allocation hygiene (researched 2026-10-09)

Confirmed hotspots (live Gate C, CPU-bound ops, receipts `docs/research/OPT-SYNC-005-receipt.json`):
independent verification = 37% of sync-op wall (DV sampling 21% + readback validate 15%),
JNI transfer 25%, allocation/reflection hygiene ~13%. Research targeted the specific classes:

1. JDK8 reflective-invoke vehicle (DV per-cell comparator):
   - PVS-Studio (https://pvs-studio.com/en/blog/posts/java/1266/): `invokeExact` ~2× reflection after warmup — benchmark-dependent.
   - raphw JMH gist (https://gist.github.com/raphw/881e1745996f9d314ab0): exact invocation wins mainly on primitive signatures; boxing kills it — our `M_REG_GET_ID` returns boxed `Integer`.
   - Timefold (https://timefold.ai/blog/java-reflection-but-much-faster): on OpenJDK 8 MH measured ~136% slower than reflection vs direct; gains arrive on newer JDKs.
   - Hazelcast (https://hazelcast.com/blog/turbocharging-java-reflection-performance-with-methodhandle/): accessibility at creation-time — rationale only.
   Verdict: vehicle swap is evidence-WEAK on our pinned JDK8 → parked as bake-off candidate C2 with expected REJECT.
2. JNI boundary (https://stackoverflow.com/questions/13973035/what-is-the-quantitative-overhead-of-making-a-jni-call;
   https://developer.ibm.com/articles/j-jni; https://arxiv.org/html/1412.6765v1;
   https://medium.wix-engineering/a-java-exploring-jni-performance-via-decoding-base64-4388683102a2):
   per-call floor 10 ns–few µs; our crossings average 3.8 µs but carry proportional
   memcpy/expansion work — `markSectionAbsent` ×7056 costs only 4.4 ms (~0.6 µs/call).
   Verdict: batching REJECTED as the headline target (boundary tax is not what we pay).
3. Engine practice (https://github.com/PaperMC/starlight — light sections tied to ChunkAccess;
   Minestom `DynamicChunk` https://javadoc.minestom.net + chunk-management wiki):
   production engines trust their codec (tests offline, no per-sync independent verification).
   Verdict: verification cost is the price of the authority claim — keep, do not amortize semantics.

Shortlist: C0 current | C1 allocation/reflection-hygiene pack (TL readback-buffer reuse,
per-thread section scratch, cached `isEmpty` Method, single resolve per op — no external dep)
| C2 = C1 + MethodHandle DV vehicle (JDK8-weak, expected REJECT). Common-corpus bake-off per
receipt §bakeoff_design; promotion via live Gate C A/B.

## OPT-SYNC-006 — targeted: reuse/lease + runtime-class caches + MH vehicle (researched 2026-10-09)

Reuses the OPT-SYNC-005 survey; only the four implementation-relevant primaries were inspected:

1. Buffer reuse & view/address lifetime:
   - sanj.dev ByteBuffer performance guide (https://sanj.dev/e2e-lessons/bytebuffers-performance-guide, Sep 2022): reuse direct buffers, slice over re-allocate.
   - Klipspringer JNI byte-buffer notes (https://klipspringer.avadeaux.net/moving-native-data-in-byte-buffers.html, Apr 2025): direct-buffer addresses are valid for the duration of the native call; no native-side retention after return — the lease model only needs Java-side exclusivity.
   - Oracle ByteBuffer javadoc (Java SE 8): clear() resets position/limit, does NOT zero contents — the light slots zero-fill on lease exists precisely because of this.
2. Runtime-class caches: "ClassValue, the cache that dies with the class" (https://belief-driven-design.com/classvalue/): per-Class entries evicted when the defining loader is collected — chosen over any Map<Class,Method> global (no LaunchClassLoader retention). JDK7+, fine on pinned JDK8.
3. MethodHandle vehicle (C2): JDK8-specific evidence from the 005 survey stands (PVS-Studio ~2x warm best-case; raphw gist — boxing hurts; Timefold measured JDK8 MH SLOWER than reflection). Additional primary: Oracle blog "Method handles: a better way to do Java reflection" (https://blogs.oracle.com/java/method-handles-in-java, 2023) — modern-JDK framing, explicitly NOT a JDK8 claim. Verdict: benchmark the real shape (unreflected virtual get(int,int,int) + boxed-ID registry lookup), no prediction.
4. JNI boundary: 005 survey (SO/IBM/arXiv/Wix) — per-call floor 10ns–µs; refresh/readback crossings carry proportional work; not the target here.

No new dependencies. C1 is local-only; C2 is local-only (java.lang.invoke, JDK8-bundled).

## OPT-FS-002 — transformer/classload attribution + Pumpkin reference (researched 2026-10-09)

Upstream facts (primary sources):
- LaunchWrapper LegacyLauncher @ a4801b7 (LaunchClassLoader.java): transformers run
  EXACTLY ONCE per class (transformed Class cached in cachedClasses keyed by
  transformedName); raw bytes cached (resourceCache + negativeResourceCache);
  classLoaderExceptions delegate to parent, transformerExceptions skip
  transformation. No repeated-transform or repeated-defineClass defect is
  available to fix; exclusions change which transformers execute (different
  justification than a local fast path).
- Pumpkin (tagged release 0.2.0+26.3-26.51, 20 Sep — latest; distinct from
  Nightly/Canary rolling builds; prior review pinned commit 204a94e):
  - PR #2335 (merged 2026-07-11, output-preserving, fixed-seed parity tests):
    (a) memoize structure placements keyed by start chunk + seed — "context
    only built on a cache miss"; (b) light-propagator maps aliased to FxHash;
    (c) REMOVE a hashed shadow cache + batched write buffer over light
    storage — storage array is the single source of truth (65→22ms lighting
    stage; 2-3.3× on gen benchmarks).
  - packet_encoder.rs @ 204a94e: persistent scratch Vecs (clear + capacity
    hint, never per-packet alloc), persistent flate2 Compress reused via
    reset() (rebuilt only on level change), below-threshold packets skip
    zlib entirely, fixed stack array for VarInt headers.
  Applicability: the patterns (memoize stable inputs; make storage the single
  source of truth instead of per-event shadow bookkeeping; reuse per-caller
  scratch; skip irrelevant work by predicate) map onto Java-side hot paths.
  Pumpkin itself is a from-scratch engine with no Forge/mod compatibility
  constraint — no crate or code is importable for a LaunchWrapper/Forge
  transformer problem; no Rust dependency is warranted for a Java-path cost.

Measured reality that reframed the target (receipt OPT-FS-002): the Java arm
allocates ~70GB at boot too — the defineClass/ZipFile/ASM bucket is NORMAL
Forge+mod classloading (51,192 classes at boot; only ~121 classes load during
streaming). RustCraft's boot delta is +15-18GB (extra transformer passes +
agent + registration). The remaining rust-specific regression is the
STREAMING-phase server-thread allocation delta (~20GB vs 1.2GB) — attribution
in progress via the phase-aware sampler.
