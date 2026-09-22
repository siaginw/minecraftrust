# Rust Engine Optimization Research — algorithm mining pass

Date: 2026-09-20. Operator directive: mine external optimization projects for
ALGORITHMS (data structures, caching, indexes, batching, invalidation,
SIMD, asymptotics) toward the preferred end-state — Java/Forge/mod
compatibility over a coarse boundary into Rust-optimized engine subsystems.
Research only: no implementation, no runs, no mode changes. Provenance:
M3R-ALGORITHM-MINING (DERIVED_FROM_MEASURED + source inspection; the
performance evidence cited is the project's own M2J/M3J profiles).

## A. Repositories examined

| repo | commit/ref | how examined | license | server relevance |
|---|---|---|---|---|
| Lithium | 11ea0a (pinned local) | source read (collision sweeper, BlockStateFlags/BlockCountingSection, LithiumEntityCollisions, shapes/*, hopper/*, MobSpawnSettingsMixin) | LGPL-3 | HIGH (exact-semantics algorithm goldmine) |
| Starlight | cca03d6 (pinned local) | structure scan (light engines, section-local state) | LGPL-3 | MEDIUM (lighting; Phosphor conflict) |
| FoamFix | 0.10.5 (installed on targets) | known + prior campaign contact | MIT | MEDIUM (mostly Java memory/dedup) |
| UniversalTweaks | GitHub survey (README/tweak list) | web | LGPL-3 | LOW-MEDIUM (mostly behavior-changing; caching patterns) |
| VintageFix | GitHub survey (repo + CHANGELOG) | web | MIT | LOW (model/load-time focused) |
| FermiumASM | GitHub survey | web | LGPL-2.1 | LOW (fork metadata; lineage = AI/tick tweaks) |
| AI-Improvements | known + repo | web | MIT | REJECT (behavior-changing) |
| BetterFps | GitHub survey | web | LGPL-2.1 | CAUTION (non-exact sin) |
| FastFurnace | 1.12 branch source (TileFastFurnace) | raw source read | MIT | PATTERN (memo + negative cache) |
| FastWorkbench | repo survey | web | MIT | PATTERN (same family) |
| Paper/Spigot | web search | web | — | no structure-query patch found (M3 novelty) |

## B. Behavior-changing optimizations REJECTED

All change observable 1.12.2/Forge semantics — banned from the core runtime
regardless of popularity:

- **AI tick gating / AI removal** (AI-Improvements; UT "Watching AI removal",
  concurrent AI sets): changes AI execution frequency/ordering = mob behavior.
- **UT Entity Radius Check**: shrinks AABB query bounds = different RESULT
  SETS for mods relying on query semantics.
- **UT Attack Radius** (raytrace instead of range check): different hit
  semantics.
- **UT Chunk Gen Limit / No-Pathfinding-Chunk-Loading / spawn-chunk skips**:
  change tick timing and chunk-load semantics.
- **UT Disable Redstone Lighting / particle caps / despawn-rule changes**:
  visible-state and persistence semantics.
- **Paper per-player mob spawning**: changes spawning RULES (their answer to
  the same hot pipeline M3J measured — rejected here for exactness).
- **BetterFps alternate sin/cos**: different precision = different OUTPUTS.
  Banned from any exact path (worldgen noise, physics); it usefully CONFIRMS
  that transcendental drift is real — any Rust port of noise must reproduce
  fdlibm Math.sin bit-exactly (project's prior M3-discovery flag).
- **Starlight full adoption**: rewrites light internals with same final
  values in modern versions, but on 1.12.2 targets it structurally conflicts
  with Phosphor (installed on Revelation) — rejected for THIS environment,
  algorithm mined instead (K).

## C. Exact-semantics optimization ideas (the mined core)

1. **Section-sweep collision iteration + per-section predicate counters**
   (Lithium ChunkAwareBlockCollisionSweeper + BlockStateFlags +
   BlockCountingSection): iterate the AABB's blocks ONE SECTION AT A TIME;
   each section keeps INCREMENTALLY-MAINTAINED counters (e.g.
   "oversized-shape count", "any-collision count"); sections with zero
   counter are skipped wholesale. Original: O(volume) position-by-position
   getBlockState + virtual callback per block. Optimized: O(nonempty
   sections × their volume) + O(1) counter maintenance per setBlockState.
   EXACT: identical blocks are eventually consulted, identical callbacks
   fire, identical box list returns. Invalidation: O(1) counter update on
   every block write (this is the KEY invalidation model — no mirror, no
   scan-rebuild).
2. **Immutable-state-keyed memoization** (Lithium PathNodeCache /
   BlockStatePathingCache / OffsetVoxelShapeCache; FastFurnace's
   recipeKey/failedMatch): blockstates are interned+immutable → any pure
   function of a state can be cached keyed by state identity, computed once,
   with a NEGATIVE cache for misses (FastFurnace's failedMatch) and
   revalidate-on-key-mismatch invalidation. EXACT when the predicate is pure.
3. **Long-key chunk spatial indexing + region occupancy prefilter** (vanilla
   already Long2Object; the ALGORITHmic upgrade is two-level: region→bitset
   of occupied chunk-slots → O(candidates) lookup instead of O(N) map
   iteration). This is what M3-SPAWN-QUERY-INDEX needs and what no surveyed
   project implements for MapGenStructure (verified: Paper/Spigot have no
   such patch — they went behavior-changing instead).
4. **Queue-based propagation with dirty-region tracking** (Starlight): light
   updates propagate through section-local queues with per-block dirty
   marking instead of vanilla's recursive relight scans. EXACT final values
   (modern-verified); section-local state layout enables packed iteration.
5. **Enum-keyed map specialization** (Lithium MobSpawnSettingsMixin:
   EnumMap): trivial, exact, Java-bound lesson.

## D. Algorithms/data structures worth adopting

- per-section incremental predicate counters (invalidation model for ALL
  dynamic block state)
- region→bitset occupancy grids over long keys (M3's index; also the
  substrate for collision skip tests and spawn-validation prefiltering)
- negative-caching memo pattern with key-revalidation (FastFurnace)
- append-only generation-time index maintenance (structure maps only grow
  during worldgen events — cheaper invalidation class than counters)
- queue+dirty-region propagation (future lighting)
- SIMD-able packed arrays for noise kernels — only with bit-exact
  transcendental reproduction (fdlibm; BetterFps is the counter-example)

## E. RUST_STRONG candidates

1. **M3-SPAWN-QUERY-INDEX** (unchanged; now with algorithm backing: native
   region→bitset occupancy + long-key start index; queries coarse, zero
   callbacks, parity oracle = identical StructureStart/spawn-list result).
2. **Section-occupancy substrate as a Rust utility crate** (packed bitsets +
   counters over long keys): native-owned, packed memory layout, reusable.
   Infrastructure, not a subsystem (see O).
3. **Worldgen noise kernel** (SIMD + exact reproduction; PARKED — M3J showed
   ~zero settled-workload presence; exploration-latency framing only).
4. **Native light propagation engine** (Starlight-model; BLOCKED by Phosphor
   coexistence on Revelation; only viable on no-Phosphor targets like
   SevTech; zero current ServerThread evidence).

## F. RUST_HYBRID candidates

1. **Collision two-stage** (directive-9 shape): Rust owns coordinate
   iteration + section-occupancy skip decisions; Java keeps every
   addCollisionBoxToList callback (mod shapes). Lithium's counter model maps
   directly; the Java-only version is ALREADY good (counters, no mirror) —
   Rust adds value only via the shared occupancy substrate and packed
   iteration. STILL BLOCKED on skippable-share measurement (M3J: 5.4-5.6%
   presence; how much of the scan volume is skippable is unmeasured).
2. **Spawn-location validation prefilter** (canSpawnAt block probes — M3J
   showed 101-root getBlockState leaf under SpawnPlacementType): Rust could
   validate packed section grids ("any solid in this column band?") before
   Java probes. Requires section staging; only worth it combined with M3's
   index (same queries).
3. **Entity-query prefilter** (Rust filters per-chunk candidate lists; Java
   applies predicate + callbacks): LOW evidence (1.8-2.1% M3J) and entity
   state mutates per-move (mirror maintenance ≈ query cost — the directive-7
   rejection criterion). Park.

## G. JAVA_BOUND findings — learn, do NOT port

- FastFurnace/FastWorkbench recipe memoization: ItemStack/OreDictionary
  identity, container object graphs — chatty JNI if moved; the PATTERN
  (memo + negative cache + key revalidation) is the transferable lesson.
- Lithium hopper inventory caching (HopperCachingState): inventory object
  graphs, Java identity.
- Lithium EnumMap swap, UT crafting/oredict caches: pure Java data-structure
  wins.
- EntityDataManager map tuning (M3J 3.9% leaf): per-entity datamanager maps —
  object identity bound.

## H. Reusable Rust infrastructure ideas

1. `rustcraft-spatial`: long-key (chunkXZ packed) region→bitset occupancy +
   append-only entries + point/range queries. Consumers: M3 now; collision
   skip tests later; NOT a general "engine" (see O).
2. Section counters utility: per-section packed counters updated at the
   single vanilla setBlockState seam (coremod hook) — substrate for any
   section-level predicate skip (collision, tick scans).
3. Negative-memo helper for pure state predicates (if a Java-side consumer
   ever needs one).

## I. Spatial-index opportunities (ranked by evidence)

structures: MEASURED 12.2% (M3J #1 cluster) → M3. static block occupancy:
MEASURED-adjacent (collision 5.4-5.6% + spawn probes ~2%) → substrate +
hybrid. entities: 1.8-2.1%, mirror-cost-dominated → no. chunks/sections:
vanilla Long2Object already O(1)-ish; the M3J fastutil leaf was the
structure map's VALUE ITERATION, not the chunk map lookup.

## J. Collision opportunities

Lithium's answer (counters + section sweep, all-Java) is the strongest
known exact algorithm; a Rust port adds packed iteration + shared substrate
but pays JNI per query. RUST_HYBRID, second-priority after M3; needs the
scan-domain/skippable-share measurement before any build decision.

## K. Lighting opportunities

Starlight's queue/section-local algorithm is the model; on 1.12.2 Forge
Revelation it conflicts structurally with Phosphor (installed, async, and
measured-silent on ServerThread in M3J). No current evidence of lighting
ServerThread cost on either target (Phosphor on C; D uncaptured but bot
workloads showed nothing). PARK. If ever revisited on a no-Phosphor target:
native section-local light storage + queue propagation is a genuinely
RUST_STRONG boundary (packed nibble arrays, dirty queues, zero callbacks,
parity oracle = identical final light values via full-relight differential).

## L. Worldgen opportunities

SIMD noise kernel remains the only worldgen Rust shape; M3J measured ~zero
presence in settled/entity workloads → exploration-latency framing only.
Hard requirements if ever built: java.util.Random LCG reproduction (spec'd),
fdlibm-exact sin/cos if the chain uses them (BetterFps proves drift breaks
bit-parity), bit-identical double[] outputs as the oracle. PARKED.

## M. Entity/pathfinding opportunities

Pathfinding: ~0 in M3J; Lithium's PathNodeCache (immutable-state-keyed) is
the exact algorithm if it ever matters — Java-bound shape. Entity queries:
small and mirror-cost-dominated. Entity movement/physics (10% presence) is
behavior-critical simulation — not a Rust boundary candidate under the
exactness rule. No action.

## N. Implications for M3-SPAWN-QUERY-INDEX

1. **Novelty confirmed**: no surveyed project (Paper/Spigot included) has an
   exact-semantics patch for the MapGenStructure spawn-query scan; Paper's
   route was behavior-changing. The algorithm must be designed in-house.
2. **Invalidation class identified**: structure maps are APPEND-ONLY during
   generation events (MapGenBase.generate populates; nothing mutates starts
   post-generation) — a cheaper invalidation class than Lithium's per-write
   counters. A native index maintained at generation events is stable
   between them; no per-tick work.
3. **Algorithm shape**: two-level region→bitset occupancy + long-key start
   entries; query = O(region-bits + candidates-in-region) instead of O(N)
   full-map iteration. FastFurnace's negative-cache idea also applies at the
   biome level (biome spawn lists are static post-init → memoizable).
4. **Boundary unchanged**: Rust returns candidate start IDs; Java resolves
   SpawnListEntry objects and all spawning decisions. Zero callbacks inside
   the query; parity oracle = identical StructureStart per query + identical
   spawn-list results.

## O. Specialized M3 vs shared spatial-query engine

**Recommendation: A. SPECIALIZED SPAWN INDEX** — plus one shared Rust
UTILITY (the region-bitset/long-key crate), not a shared query ENGINE.
Rationale: the four potential consumers have DIFFERENT invalidation models —
structures: generation-event appends; collision occupancy: per-setBlockState
counter updates; entity queries: per-move updates (rejected on mirror cost);
static block scans: per-write. Sharing a bitset/index HELPER crate is free;
sharing an engine abstraction now would be premature generalization (the
directive's own bar: ≥2 consumers with the SAME indexing/invalidation model
— not met; only the utility layer is common).

## P. Top 5 behavior-preserving Rust subsystem candidates

1. **M3-SPAWN-QUERY-INDEX** — source inspiration: none exists (novel; the
   need measured in-house); adjacent: vanilla Long2Object + Lithium's
   data-structure discipline. Problem: O(N) full-map iteration per
   getPossibleCreatures query (M3J: 12.0/12.4% ServerThread #1 leaf).
   Optimized: native region→bitset + long-key start index, append-only
   maintenance at generation events. Exactness: identical StructureStart
   and spawn-list results per query (differential oracle: replay every query
   Java-vs-index over generated worlds). Rust owns: index + query; Java:
   SpawnListEntry objects, biome provider, spawning decisions. Invalidation:
   generation-event append + world-load rebuild. Compatibility: zero mods
   touch func_177458_a (censused); BOP's subclass inherits the query path —
   hook at the ITERATION level covers it. Reuse: long-key region-bitset
   utility. Evidence: M3J 12.2% + zero-presence for competitors. Missing:
   per-query N/skip factor on real packs; hook-point choice (iteration-level
   vs call-level) needs a source decision.
2. **Section-occupancy + collision hybrid** — inspiration: Lithium
   ChunkAwareBlockCollisionSweeper + BlockStateFlags/BlockCountingSection
   (LGPL-3, concepts only). Problem: O(volume) collision scans (M3J:
   collision 5.4-5.6% presence + ~2% of getBlockState leaf). Optimized:
   per-section incremental counters + packed occupancy → skip empty
   sections; Rust owns counters/iteration, Java keeps shape callbacks
   (RUST_HYBRID). Exactness: identical visited-block set modulo skips of
   provably-empty sections; oracle = identical collision box lists.
   Invalidation: O(1) counter update per setBlockState (single vanilla
   seam). Compatibility: NEID/FoamFix transform section classes — counters
   must hook their writes too (coremod seam verification needed). Reuse:
   occupancy substrate shared with M3 index. Evidence: presence measured;
   MISSING: skippable share (scan domain/non-air rates) — one bounded
   offline measurement gates this. Java-side Lithium-style counters remain
   the competing simpler answer — Rust justified only via substrate reuse +
   packed iteration.
3. **Worldgen noise kernel** — inspiration: none needed (vanilla math);
   BetterFps as the precision CAUTION. PARKED (M3J: ~zero settled-workload
   presence). Boundary: POD in → double[] out, zero callbacks; oracle =
   bit-identical outputs. Missing: any workload that needs it.
4. **Native light propagation engine** — inspiration: Starlight cca03d6
   (queue-based, section-local). BLOCKED: Phosphor on Revelation; zero
   measured cost. Only viable shape: no-Phosphor targets, full differential
   relight parity. Park.
5. **Entity-query prefilter** — LOW: 1.8-2.1% evidence, per-move mirror
   costs ≈ query costs (directive-7 rejection criterion already met).
   Documented for completeness; no action.

## STOP

Research only — no implementation, no server runs, no coremod changes, no
mode changes, no dependency integration. External code neither vendored nor
integrated (LGPL-3 projects: concepts only, per standing policy).
