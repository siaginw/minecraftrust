# M3 Candidate Discovery — Rust-owned subsystem search

Date: 2026-09-20. Operator directive: find the next high-value Rust-owned
subsystem, ideally removing meaningful SERVERTHREAD work. Research only —
NO implementation, no benchmark campaign, no mode changes. Provenance:
M3-DISCOVERY (DERIVED_FROM_MEASURED: existing valid evidence + fresh javap
disassembly of installed reference jars; no new runtime measurements).

## 1. Valid evidence base reviewed

- M2J JFR capture (Revelation, 420s, bot chunk-streaming; the ONLY valid
  ServerThread profile of a real modpack): streaming ServerThread stack
  presence — Tinkers recipe matching 12.6% (mod-owned), getBlockState read
  cluster 11.1% via World.func_180495_p (engine; caller census NEVER done),
  worldgen (noise/populate, border chunks) 9.0%, Forge EventBus.post 5.1%,
  PlayerChunkMap/send 1.6%, entity tick 0.9%, TE tick 0.2%, light/random
  tick 0.0%. 48.5% of samples carry mod frames in top-8.
- M2J attribution: chunk-load ServerThread buckets (streaming) =
  state→id 126 / palette-rebuild 38 / nbt-driver 24 / chunkio-callback 15 /
  TE 5 / binary-NBT-read 3 / decompress 1; async Chunk I/O thread (n=297)
  carries the section rebuild; unload-save burst ~190 samples is
  mod-serialization-bound (NEID). H1 deprioritized, H2 = Java-side-only
  candidate (parked), H3 mod-owned.
- M2J H2 feasibility: the state→id map cost is NEID serialization volume;
  in-scope fixes optimize the wrong layer. PARKED.
- M2P persistence: save pipeline's binary encode/deflate/region-write is
  ALREADY off-ServerThread; only NBT tree building is on-thread and it is
  mod-contract-bound (ChunkDataEvent.Save + NEID). PERSIST-A removes ~zero
  ServerThread work (measured-scale reasoning, committed).
- M1I/M1J: a ~30% packet-path win = ~0.4–0.5% tick share → whole-server
  MSPT unresolvable at realistic rates. Lesson: candidate tick-share must
  be substantial AND measured before selection.
- M2CP (this day): Quark cascading-worldgen warnings observed live on
  ServerThread during corridor walks (border-chunk generation lag).
- Third-party evals (M2P): simdnbt d21464b (MIT), fastnbt/fastanvil 986c859
  (MIT), Velocity f975230f (GPL-3, reference-only), Starlight cca03d6 +
  Lithium 11ea0a + Cleanroom 19b9fa8 (LGPL-3, concepts only).
- INVALIDATED and NOT reused: all P0-8/P0-9 synthetic tables and derived
  scoring inputs.

**Evidence gap that dominates this search: the only valid profile is a
bot chunk-streaming workload. Entity tick 0.9% / TE tick 0.2% / light tick
0.0% means candidates A (collision), D (pathfinding) and F (entity/block
queries) have essentially ZERO captured evidence — a workload gap, not a
proven absence. M2J's own closing recommendation (never executed): capture
ONE dirty-world/factory workload before any re-selection.**

## 2. Fresh source traces (javap, committed under machine/raw/diag/)

- cgo-disasm.txt — ChunkGeneratorOverworld (srg jar):
  func_185932_a (generateChunk) → new ChunkPrimer → func_185976_a
  (setBlocksInChunk: BiomeProvider.func_76937_a biome fill → func_185978_a
  initNoiseField → per-block ChunkPrimer.func_177855_a writes) →
  func_185977_a (replaceBiomeBlocks: NoiseGeneratorPerlin.func_151599_a +
  Biome.func_180622_a per column — MOD-OVERRIDABLE) → MapGen replaces.
  initNoiseField = 4× NoiseGeneratorOctaves calls (func_76305_a /
  func_76304_a) into 4 double[] + per-column Biome height/variation floats.
- world-disasm.txt — World.func_184144_a (getCollisionBoxes) →
  func_191504_a: PooledMutableBlockPos loop over the AABB's block domain ×
  World.func_180495_p (getBlockState) × block collision callbacks; plus
  func_72839_b entity-AABB scan. ChunkPrimer internals: char[] block ids
  via Block.field_176229_d registry map (NEID-transformed class — M2J
  javap verification).
- ChunkIOProvider (Forge jar): async run() does NBT read + chunk
  construction; syncCallback() on ServerThread (matches the 15-sample
  bucket). PathFinder: A* heap + NodeProcessor over IBlockAccess
  (per-node getBlockState; entity-state coupled).

## 3. Candidate evaluation (performance value and architectural value scored SEPARATELY)

Legend: P = performance value, A = architectural migration value; scale
LOW / MEDIUM / HIGH; UNKNOWN where evidence is absent.

### A. Collision / AABB / spatial queries — two-stage per directive 9
- P: **UNKNOWN** (no entity/machine workload ever captured; collision's
  block-scan loop IS a consumer of the evidenced-hot getBlockState path,
  but its share of the 11.1% cluster is uncensed). Two-stage design
  (Rust broadphase = packed section-occupancy/static-AABB candidate
  filter; Java keeps modded collision callbacks) preserves Forge
  semantics, BUT the occupancy mirror must be invalidated on EVERY
  setBlockState (continuously mod-mutated state in factories), query
  domains are entity-sized (small), and the win case (all-air boxes) is
  the cheap case already. Lithium solves this Java-side with per-IBlockState
  immutable AABB caching — no invalidation needed; a native mirror is
  strictly worse on that axis.
- A: MEDIUM boundary quality at best; invalidation coupling contradicts
  the "mods do not continuously mutate the hot state" selection rule.

### B. Lighting — rejected per directive 10 caution
- P: **UNKNOWN** (zero light frames in the only valid profile — which ran
  PHOSPHOR 0.2.7 on Revelation; Phosphor owns the light engine there and a
  native propagation conflicts structurally with it; SevTech runs vanilla
  lighting but was never profiled).
- A: a Starlight-style rewrite is a Java-semantics surface (mods query
  World.getLightFor continuously), not a coarse Rust boundary. Do NOT
  recommend a port because Starlight is fast. REJECT.

### C. Worldgen noise kernel (terrain shaping math)
- P: LOW-TO-MODERATE, workload-conditional — the only candidate area with
  an evidenced live ServerThread footprint (9.0% streaming presence on
  corridor border chunks + 122 recovery-window samples + Quark cascade
  warnings in M2CP logs). The noise/density share of that 9% is UNKNOWN
  (populate/structures/biome-surface are Java-hooked and stay). ~0 value
  in factory steady-state. Benefits chunk-generation latency/throughput
  (exploration gating), a different axis than tick share.
- A: HIGH — hook-free vanilla math (NO Forge event fires inside
  initNoiseField; hooks start at replaceBiomeBlocks/populate); inputs are
  POD (seed, chunk coords, generator settings, 25 biome float pairs) and
  outputs are double[] height/density arrays; java.util.Random is a
  specified 48-bit LCG → the whole permutation/noise pipeline is
  bit-reproducible in Rust; offline parity is exactly testable per
  seed+coords (same discipline as M2.0 interop fixtures). Primer fill and
  everything downstream STAYS Java (ChunkPrimer is NEID-transformed +
  registry-id-bound). Fallback trivially reversible (call Java path).
  Reusable across vanilla-family generators/dimensions.

### D. Pathfinding — REJECT
- P: UNKNOWN (nested in 0.9% entity tick on a bot workload). A: per-node
  IBlockAccess reads + PathPoint graph consumed synchronously by entity
  AI; off-thread = stale-entity-state risk; per-entity JNI density
  violates selection rules.

### E. Chunk load / section reconstruction — fails the ServerThread goal
- P: ~NIL ON SERVERTHREAD by prior measurement (syncCallback 15 +
  binary-read 3 + decompress 1 of 296 streaming samples; the heavy parts
  are registry/NEID-locked Java or already-async — M2J + M2P both
  concluded this; PERSIST-A rejected on the same ground). Accelerating the
  async side is TPS-neutral.
- A: HIGH boundary quality (simdnbt/fastanvil directly relevant) — but it
  is the SAME boundary M2-PERSIST already failed on goal-fit. Not M3.

### F. Entity/tile/block spatial indexing — REJECT for native
- P: the read cluster is evidenced (11.1%) but H2 established the fix
  layer is a Java-side registry-map/cache change (engine-patch scale),
  not an FFI migration; a native cached index without owning Java state
  needs the same invalidation coupling as A. Caller census above
  World.getBlockState remains MISSING EVIDENCE.

### G. Async job engine / native task queue — REJECT (infrastructure-first)
- No consumer exists; M1/M2CP lessons show per-call JNI pays only when
  work ≥ ~50 µs; a queue adds overhead with nothing to schedule. Velocity's
  Disposable-context lifecycle pattern was already absorbed into M2C's
  design. Revisit only when a consumer candidate is selected.

## 4. Third-party reference table (pinned repos only; nothing vendored)

| repo | commit | idea used here | reuse type | license note |
|---|---|---|---|---|
| Lithium | 11ea0a | per-IBlockState immutable collision-AABB caching (shows the Java-side fix for A, undermining a native mirror) | inspiration | LGPL-3, concepts only |
| Starlight | cca03d6 | section-local light rewrite scale + Phosphor coexistence problem statement (reject rationale for B) | inspiration | LGPL-3, concepts only |
| simdnbt | d21464b | NBT parse acceleration relevance to E (load-side) | prior eval | MIT, already evaluated in M2P |
| fastanvil | 986c859 | region read-path reuse for E | prior eval | MIT, already evaluated in M2P |
| Velocity | f975230f | native context lifecycle (absorbed in M2C); job-queue shape for G | prior eval | GPL-3, reference-only, no code |
| Cleanroom | 19b9fa8 | none (JDK-8-pinned targets; migration N/A) | none | LGPL-3 |

## 5. TOP 3 (only these are returned)

### Candidate 1 — C: native terrain-noise kernel (worldgen math)
- A. Name: M3-CANDIDATE-WORLDGEN-NOISE.
- B. Path: ServerThread → ChunkGeneratorOverworld.func_185932_a →
  func_185976_a (setBlocksInChunk) → func_185978_a (initNoiseField:
  NoiseGeneratorOctaves.func_76305_a/func_76304_a ×4, double[] outputs) +
  the density loop; replaceBiomeBlocks surface stays Java (Biome override
  point). Thread: ServerThread (synchronous chunk generation during tick).
- C. Rust owns: noise permutation tables (from java.util.Random LCG seed),
  octave/perlin evaluation, the 5×5×33 density/height math → produces the
  primer-ready block-id column decisions or the density double[]s.
- D. Java retains: biome array production (BiomeProvider — mod-hookable),
  ChunkPrimer writes (NEID-transformed + registry ids), surface/biome
  replacement, structures, populate, everything downstream.
- E. JNI boundary: per chunk, ~2 calls; inputs POD ≈ few KB (seed, coords,
  settings floats, 25 biome float pairs); outputs double[]/int[] ≈ 33–165
  KB. Coarse. Callbacks: ZERO inside the native span.
- F. ServerThread evidence: worldgen 9.0% streaming presence + 122
  recovery samples (M2J); NoiseGenerator 1.8% self in one listing; Quark
  cascade warnings live in M2CP logs. Noise-share of the 9%: UNKNOWN.
- G. Compatibility: accelerates vanilla-family generators only; modded
  custom world types/dimension generators fall through to Java (no
  behavior change). Risk: NONE identified inside the noise span (no Forge
  hooks); settings-object staging must track ChunkGeneratorSettings JSON.
- H. Required callbacks: none during compute; one Java->native per chunk.
- I. Parity: offline fixture harness — same seed/coords/settings/biome
  floats through Java reference vs Rust → bit-exact double[]/block-id
  equality (M2.0 interop discipline); live SHADOW ladder (native computes,
  Java authoritative) before any ON.
- J. Fallback: property-gated per-generator-instance; on any native issue
  call the vanilla Java noise path (identical output contract).
- K. Performance value: LOW-TO-MODERATE / UNKNOWN magnitude —
  workload-conditional (exploration-heavy windows only; ~0 in steady
  factory). NO ms/tick number is claimed; needs a prototype measurement.
- L. Architectural value: HIGH — first worldgen ownership; reusable noise
  kernel; cleanest boundary since compression; fully reversible.
- M. Missing evidence: noise-share of the 9% worldgen presence;
  transcendental usage census of the noise chain (bit-parity risk if
  Math.sin/cos appear — must be verified, fdlibm port if so);
  steady-state/factory worldgen share; per-chunk noise timing.
- N. Smallest next experiment: standalone parity+timing prototype
  (M2.0-style): port the octave/perlin math, fixture-compare vs Java for
  N seeds/coords, measure Java-vs-Rust per-chunk noise wall time. ~1 day.

### Candidate 2 — A: two-stage collision (Rust broadphase only)
- A. Name: M3-CANDIDATE-COLLISION-BROADPHASE.
- B. Path: ServerThread (entity/TE tick) → World.func_184144_a →
  func_191504_a PooledMutableBlockPos scan × World.func_180495_p ×
  Block.addCollisionBoxList (mod override point) + func_72839_b entity
  scan.
- C. Rust owns: candidate FILTER only — packed per-section occupancy /
  static-AABB bitmap answering "which positions in this box could have a
  non-empty collision shape".
- D. Java retains: every addCollisionBoxList callback (modded shapes are
  authoritative), entity AABB scans, all semantics.
- E. JNI: per query, one native filter call (box in → candidate list out).
  Coarse IF the occupancy mirror exists; the mirror is the problem.
- F. ServerThread evidence: UNKNOWN (entity tick 0.9%, light tick 0.0% in
  the only capture; collision's share of the 11.1% getBlockState cluster
  never censused — no entity/machine workload was ever profiled).
- G. Compatibility: occupancy mirror must be invalidated on EVERY
  setBlockState — continuously-mutated mod state; misses = wrong collision
  = physics breakage. High invalidation coupling.
- H. Callbacks: none native-side, but the mirror maintenance hooks every
  block write.
- I. Parity: differential entity-physics fixtures; hard to make exhaustive.
- J. Fallback: filter-miss heuristic must default to "candidate" (safe but
  then no win).
- K. Performance value: UNKNOWN, possibly zero (air-box queries are
  already cheap; Lithium's Java-side per-state AABB cache gets the win
  without a mirror).
- L. Architectural value: MEDIUM (broadphase only; shape semantics stay
  Java; invalidation coupling is bad boundary quality).
- M. Missing evidence: an entity/machine workload profile AT ALL; caller
  census of the getBlockState cluster.
- N. Smallest next experiment: none of its own — depends on the shared
  dirty-world capture.

### Candidate 3 — E: native chunk-load decode (async acceleration)
- A. Name: M3-CANDIDATE-CHUNK-LOAD-DECODE.
- B. Path: Chunk I/O Executor (async) AnvilChunkLoader.read →
  ChunkIOProvider.run; ServerThread syncCallback only adds TEs/entities +
  relight (15 samples streaming).
- C. Rust owns: region-file read + zlib inflate + NBT binary decode into
  a staged tree (simdnbt/fastanvil patterns proven compatible in M2P).
- D. Java retains: ALL state reconstruction (palette, registry
  state→id, NEID hooks, TE/entity read) — H1 attribution showed this is
  ~92% of load cost and mod-contract-bound.
- E. JNI: per chunk, coarse (byte[] in → staged tree out).
- F. ServerThread evidence: ~NIL (binary-read 3 + decompress 1 of 296
  streaming samples on ServerThread; the rest is async). TPS-neutral.
- G. Compatibility: read-side corruption persists to disk; NEID section
  hooks must run (they operate post-parse).
- H. Callbacks: NEID/registry translation per section (Java).
- I. Parity: dual-decode compare (two proven codecs — ours + simdnbt),
  reload validation (M2P mandatory-safety pattern).
- J. Fallback: per-chunk Java path.
- K. Performance value: ~ZERO on ServerThread (the M3 goal); async-thread
  latency only.
- L. Architectural value: HIGH (region+NBT ownership, reusable for
  PERSIST-A later).
- M. Missing evidence: none for the rejection — measured already.
- N. Smallest next experiment: park (M2P verdict stands).

## 6. RECOMMENDED M3

**NO M3 SELECTED.**

Reasoning: only one candidate (worldgen noise) has both an evidenced
ServerThread footprint and a clean native boundary, but its removable
magnitude is UNKNOWN, workload-conditional (exploration-only), and sits in
the same tick-share band (single-digit % presence) that M1I/M1J already
showed does not resolve whole-server; selecting it today would repeat the
M2J exploratory-read mistake the project explicitly corrected. Collision
and the entity/machine candidates have NEVER been profiled on any valid
workload — the exact gap M2J named and nobody closed.

Recommended ONE bounded research task: **M3J dirty-world capture** — a
single JFR profile of Revelation (C) on a machine/entity-heavy world
(factory-style: ticking TEs, entities, ongoing block mutation), reusing
the entire M2J rig and offline attribution tooling, plus the one missing
census: callers ABOVE World.func_180495_p. Decision table it resolves:

- entity/TE tick + collision-path share ≥ several % presence → collision
  two-stage becomes measurable; re-evaluate with the Lithium Java-side
  alternative priced in.
- worldgen share on a settled factory world ≈ 0 → noise kernel confirmed
  exploration-only → it competes on latency framing, not tick share.
- save-path state→id remains dominant → Java-side H2 (parked) is the
  honest owner; no native candidate.
- if nothing reaches several % → NO native M3; state that and stop.

STOP: no implementation, no benchmark campaign, no default-mode changes.
