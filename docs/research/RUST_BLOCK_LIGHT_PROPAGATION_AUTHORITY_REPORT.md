# RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — live shadow proven, ON design selected

Status: **IN PROGRESS (shadow phase proven)** — kernel proven (100k
differential fuzz, 0 divergences), Gate A live shadow PROVEN (169,907
cells / 0 mismatches / 0 errors + 15-step mutation driver), launch-shape
provenance classified (Phosphor ACTIVE in both Revelation shapes — no
divergence), Gate C Phosphor seam live (LIGHT_HOOK_PLACED, jobs counting;
drain-replay parity in qualification). Coarse ON seam selected:
`World.checkLight`. Collision rejected by profile
(`COLLISION_AUTHORITY_NOT_YET_JUSTIFIED`).

## 0. Live runtime truth (proven 2026-10, Gate A evidence light-cell-diag4..13, light-shadow-A-full2)

- Canonical identities (tools/symbols, joined.srg): `checkLightFor` =
  `func_180500_c` = notch `amu.c` `(Lana;Let;)Z`; `getRawLight` =
  `func_175638_a` = notch `amu.a` `(Let;Lana;)I` (PRIVATE — it is
  checkLightFor's own value kernel; the disassembled body calls it).
- Opacity/emission enter via Forge's world-aware
  `Block.getLightOpacity/getLightValue(state, world, pos)` at the cell.
- EnumSkyBlock: SKY = ordinal 0, BLOCK = ordinal 1 — discrimination keys
  on the enum NAME, never the ordinal.
- Runtime naming: classes are deobf-named (net.minecraft.world.World),
  members SRG; launchwrapper hands transformers NOTCH bytes at the
  tweaker-chain position; hook classes load on the APP classpath —
  reflection surfaces MUST be derived from live objects (Class.forName by
  deobf names fails; notch names bind the wrong loader's classes).

## 0d. AUTHORITY MILESTONE (2026-10-05/06, main=dc32659)

**RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY_PROVEN_BOUNDED** — the coarse
`World.checkLight` (func_175664_x / notch amu.w) seam owns live BLOCK-light
propagation for admitted jobs on BOTH gates:

- Architecture: HEAD injection at checkLight; admitted jobs stage a flat
  per-cell descriptor (state id + current light, radius-24 box + 2 halo)
  into ONE direct buffer, ONE JNI crossing (`LightAuthorityBridge.runJob`)
  runs the frozen kernel, the hook commits the diff grid via
  World.setLightFor. SKY half: untouched, routed through each shape's own
  checkLightFor (vanilla / Phosphor's cancelled scheduler).
- §12 whole-job eligibility: the §12 pre-scan (Rust) + staging-loop
  classification (Java) require EVERY staged state id to be classified
  STATIC (3-byte session table [classified, opacity, emission]); anything
  dynamic/unknown fails the WHOLE job to Java. No per-cell callbacks.
- §15 bounds: malformed headers, frontier escape beyond the staged box,
  and writes outside the declared interior are hard failures (fail closed
  before commit). Worldgen relight cascades DO exceed 15-24 (observed at
  origin-18/-21/-60); capped-radius jobs fall back to vanilla.
- §22 sampling: lightSampleRate defers eligible jobs to vanilla with the
  shadow comparator active.

Evidence (receipts in target/authority-review/):
- Gate A ON micro (light-A-ON-dev31): mutations 15/15, admitted=10,
  committed=1,148,634 cells, SKY=10, errors=0.
- Gate A ON STANDARD (light-A-ON-std5): mutations PASS, admitted=13,
  committed=2,932,497 cells, SKY=13, errors=0, probe PASS.
- Gate C ON micro (light-C-ON-dev2): admitted=21, committed=1,913,625
  cells, SKY=21 (Phosphor's own scheduled path), errors=0.
- Gate C ON STANDARD (light-C-ON-std1): admitted=25, committed=2,278,125
  cells, SKY=25, errors=0, probe PASS.
- Save/restart (light-A-ON-restart1): authority ON -> save -> fresh OFF
  process -> reload -> probe PASS, no relight corruption.

Honest accounting (§29): on the mutation workload the fallback mix is
§15-cap OOB escapes (worldgen-scale cascades) + §22 sampled deferrals;
admission of SMALL mutation jobs is ~100% (0 errors across all runs).
Performance (§27-§28): the staged-box staging via reflection costs
~2-3 s/job (49³ cells) — the dominant per-job cost, with kernel propagate
second. Vanilla checkLightFor for the same mutations is sub-ms. The
milestone is OWNERSHIP; closing the staging gap (direct NativeChunk state
scans per goal §6/§7 full version) is the next performance lever and is
expected to bring Rust under vanilla for large cascades while staying
comparable for small ones. Keep decision: KEEP.

## 0e. NATIVECHUNK FASTPATH (2026-10-05/06, main=7ab7ec3)

**RUST_BLOCK_LIGHT_NATIVECHUNK_FASTPATH_PROVEN** — the reflection-staged
cube is REPLACED by frontier-driven traversal over bulk section snapshots:

- Profile (§2, light-A-ON-prof1 + counters): old job time split ≈ 89% JNI
  (kernel propagate over the whole 117k-cell staged cube incl. huge
  worldgen regions), 10% Java reflection staging, ~1% commit. The staged
  CUBE was the bottleneck — exactly as the goal suspected, but proven,
  not assumed.
- New architecture (§3-§12): Java snapshots the box's chunk sections into
  bulk buffers (palette-decoded global state ids u16 per cell + raw
  block-light nibbles, cached per chunk mutation version), ONE JNI call
  (`runJobNative`) runs the frozen kernel FRONTIER-DRIVEN over the section
  slices — only cells light physics touches are visited — and returns the
  changed-cell diff. Java commits via setLightFor (the semantic
  notification: marks chunks dirty for save + client updates).
- Fail-closed: reads outside the section directory, unknown table ids,
  capacity caps — all abort BEFORE commit (§15).
- Evidence: Gate A fastpath STANDARD PASS (light-A-FAST-std1: 6,176
  admitted / 23,291 committed / 0 errors); Gate C fastpath STANDARD PASS
  (light-C-FAST-std2: 6,148 admitted / 27,998 committed / 0 errors over
  Phosphor); per-job JNI cost 27µs (from 1.6s — the kernel path is
  ~60,000x cheaper); staging ~4-7ms/job (from 2.75s — ~400-600x).
- Honest limits: §16 combined light+packet — one composition run with
  packet-authority properties armed passed with light authority green,
  but the packet authority's bounded rollout did not ENGAGE (cap
  threshold not reached), so "Rust-encoded packets carry the new light"
  is NOT yet proven. §18/§20 full bench + MSPT A/B deferred.

## 0c. Gate C shadow evidence (Phosphor oracle — STANDARD, light-shadow-C-full2)

- INNER drain seam (`processLightUpdatesForTypeInner(type, queue)`): every
  invocation is a real job; the batch arrives as a parameter. The OUTER
  ForType stayed uninstrumented (dev23: 4,836 schedules drained past its
  HEAD read with an apparently-empty queue).
- Position decode: Phosphor's packed longs are BIAS-encoded
  (encodeWorldCoord: y<<52 | (x+2^25)<<26 | (z+2^25); lX/lY/lZ are BIT
  WIDTHS 26/8/26) — width-as-mask and two's-complement sign-extension both
  decode to garbage (dev13-21).
- Comparison discipline: mutation-window + chunk-local + near-anchor
  admission; worldgen cascades counted as skippedCascade, never compared.
- Receipt: verdict=PASS probe=PASS; jobs=2,775 compared=592 mismatches=0
  errors=0 (mutation window: 64 compared, 0 mismatches); all 15 mutation
  steps settled with real deltas. The same-session STANDARD run
  (light-shadow-C-full1) logged 5,946 jobs / 668 compared / 0 mismatches —
  its FAIL verdict was a gate-parsing artifact (fixed).

## 0a. Gate A shadow evidence (vanilla oracle) (vanilla oracle)

- WORLD_CHECK_LIGHT_HOOKED returns=2 method=`c(Lana;Let;)Z`
- DEV: 169,907 cells / 0 mismatches / 11 settledLate / 0 errors
- STANDARD full shadow: 4,772 comparisons, 15-step setblock mutation
  driver (torch/glowstone/stone, two-source, chunk-boundary x=16,
  section-boundary y=112, rapid add/remove/add) — LIGHT_MUTATIONS_PASSED,
  0 mismatches, 0 errors, probe PASS (receipt
  light-shadow-A-full2/receipt.json)

## 0b. Launch-shape provenance (goal §8-§12)

- NORMAL Revelation (forge-2846 `-jar` launch, probe agent only):
  `LightingEngine` DEFINED (phosphor jar), MixinTweaker + 3 mixin proxies,
  World final bytes 98,991B.
- CUSTOM Revelation (RustCraft tweaker launch, same pack files):
  `LightingEngine` DEFINED; vanilla `checkLightFor` instrumented yet
  cells=0 — Phosphor's MixinWorld CANCELS it and routes into the engine.
- Prior "Phosphor inert" readings came from the Gate A runtime (no mods
  at all — no phosphor jar present). **§10 classification: Phosphor is
  ACTIVE in both Revelation shapes; the custom launch is representative;
  §11/§12 (launch fix, other-coremod audit) are NOT applicable.**
- Oracles: Gate A = vanilla `checkLightFor` (WorldLightHook);
  Gate C = Phosphor `processLightUpdatesForType(BLOCK)` drain
  (PhosphorLightHook).


## 1. Collision park (goal §27)

JFR on real Gate C (collision-profile-C, Server thread, 3,477 samples):
collision **inclusive CPU 0.0%** (0 samples touching func_191504_a /
func_185908_a). Mod audit: 361 collision-touching classes across 73 jars,
majority callback-dominant (TE/capability/multipart). H13's scalar batch is
retained as a candidate; production integration was already parked there.
Milestone record: `COLLISION_AUTHORITY_NOT_YET_JUSTIFIED`.

## 2. Phosphor audit (goal §2 — transformed runtime, jar-verified)

Phosphor 0.2.7 (phosphor-forge-mc1.12.2-0.2.7-universal.jar):

- `MixinWorld` (implements `ILightingEngineProvider`) constructs a private
  `LightingEngine` per World on construction and **cancels**
  `World.checkLightFor(EnumSkyBlock, BlockPos)` — vanilla's per-block check
  becomes `lightingEngine.scheduleLightUpdate(type, pos)`.
- `LightingEngine` binds ONE World + `ownedThread` (the server thread —
  construction thread). Queue state: `PooledLongQueue[] queuedLightUpdates`
  plus per-type `queuedDarkenings` / `queuedBrightenings` (packed long
  positions).
- Public drain: `processLightUpdates()` / `processLightUpdatesForType(
  EnumSkyBlock)` → private `processLightUpdatesForTypeInner(type, queue)`.
  **One invocation of the BLOCK drain = one propagation job.**
- Kernel internals: `calculateNewLightFromCursor`, `spreadLightFromCursor`,
  `fetchNeighborDataFromCursor`, `getPosOpacity(pos, state)`,
  `getCursorLuminosity(state, type)`, `getCachedLightFor(chunk, storage,
  pos, type)` — opacity/emission come from `IBlockState` at the cursor;
  light reads/writes go through Chunk/ExtendedBlockStorage directly.
- `MAX_SCHEDULED_COUNT` bounds the queue (Phosphor's own job cap).
- Save path: `MixinAnvilChunkLoader` / `MixinChunk` keep vanilla persistence
  semantics over the same storage.

## 3. Selected seams (goal §3 — updated by live proof)

**Per-cell shadow (diagnostic only, goal §13):** the live hooks above —
`World.checkLightFor` on Gate A, Phosphor's drain on Gate C. Proven live on
Gate A; Gate C drain-replay parity in qualification.

**Coarse ON seam (goal §14-§15):** `World.checkLight` =
`func_175664_x` = notch `amu.w` `(BlockPos)Z`, public — called ONCE per
block mutation from `World.setBlockState` (plus the relight schedulers);
body = `checkLightFor(SKY) | checkLightFor(BLOCK)` with each frontier
drain internal to the method. One call = one logical propagation job =
**one JNI crossing per job** (the §15 conceptual target):

    Java/Forge schedules the job (setBlockState → checkLight)
      → one crossing at the BLOCK half
      → Rust owns the full BLOCK frontier for the job
      → Rust writes NativeSection block-light
      → completion semantics returned; SKY half stays Java untouched

Scheduling and mod callbacks stay Java in both modes: only BLOCK
propagation ownership moves.

## 4. Opacity/emission source (goal §6)

Phosphor queries `IBlockState` per cursor (`getPosOpacity`,
`getCursorLuminosity`). For Rust admission the session builds a
runtime-derived table `state identity -> (opacity, emission)` populated
from the real Forge runtime after registry completion; states observed to
return varying values under varied position/neighbor/TE probes are
DYNAMIC → whole-job fallback to Phosphor (goal §21). No hand-written block
tables.

## 5. Kernel proof (goal §1 — frozen)

`crates/light-engine`: two-queue vanilla-ordering frontier, world-addressed
CellKey with chunk-crossing neighbors, opacity/emission via `LightWorld`
trait, Y bounds from world. 6 kernel tests + 100,000 differential fuzz
scenarios vs independent recomputation oracle: **0 divergences** (8
workers, 610s). Do not rerun unless the kernel changes.
