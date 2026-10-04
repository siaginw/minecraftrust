# RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — design-in-progress

Status: **IN PROGRESS** — kernel proven (100k differential fuzz, 0
divergences), Phosphor audit complete, live seam selected. Collision was
rejected by profile (`COLLISION_AUTHORITY_NOT_YET_JUSTIFIED`, see §Evidence
below).

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

## 3. Selected seam (goal §3)

`LightingEngine.processLightUpdatesForType(EnumSkyBlock.BLOCK)`:

- **SHADOW**: inject at HEAD — capture the scheduled positions and the
  pre-drain block-light grid over the affected bounds; let Phosphor drain
  (authoritative); at RETURN replay the same job through the Rust frontier
  from the captured pre-state and compare final block-light cell-exact over
  the bounds. Java values are never modified.
- **ON_EXPERIMENTAL**: inject at HEAD — capture the scheduled batch, run the
  Rust frontier over NativeChunk state, RETURN immediately (Phosphor's block
  drain is skipped; SKY drain untouched). One semantic owner per admitted
  job.
- Scheduling (`scheduleLightUpdate`) stays Phosphor's in both modes: Java
  keeps event scheduling/mod callbacks; only propagation ownership moves.
- Failure semantics: Rust failure BEFORE commit → clear captured batch,
  un-cancel, let Phosphor drain (fail closed). No partial state can escape —
  the engine mutates only NativeSection nibbles, which Java re-reads.

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
