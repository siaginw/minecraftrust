# RUST_REGION_WRITE_AUTHORITY — Milestone Report

Status: **PROVEN (bounded live authority, Gate A; shadow-proven, Gate A+C).**
Gate C live authority PARKED pending external-writer coexistence (§5.2.3).
PRODUCTION_AUTHORITY stays false: live authority runs behind
`-Drustcraft.regionWriteMode=ON_EXPERIMENTAL`.

Goal: convert the proven offline region engine (RUST_REGION_IO_ENGINE,
feed7a5) into a REAL bounded in-server write authority. Java/Forge builds all
semantic data (ChunkDataEvent.Save, TE/entities/capabilities → chunk NBT →
Deflater); Rust owns ONLY the final region-file write (sector allocation,
framing, I/O, crash ordering).

## 1. Seam (bytecode-derived, unchanged from feed7a5)

- Vanilla chain: `AnvilChunkLoader.saveChunk` → `RegionFile.func_76710_b` →
  `RegionFile$ChunkBuffer.close` → **`RegionFile.func_76706_a(II[BI)V`**
  (synchronized; ACC_SYNCHRONIZED — the monitor is held before the first
  injected instruction).
- The 4-byte BE length prefix (`payload.len()+1`) and the compression-type
  byte (2) are written INSIDE the engine — the caller passes only the raw
  DEFLATE stream. `write_chunk` reproduces this framing exactly.
- Runtime names: launchwrapper passes the **notch** name at the tweaker
  chain position — RegionFile = **`ayj`** (javap-verified: the only
  Minecraft class holding a `java/io/RandomAccessFile`; fields b=File,
  c=RandomAccessFile, d=int[1024] offsets, e=int[1024] timestamps,
  f=List<Boolean> free sectors). FML's deobfuscation runs later in the
  chain; the loaded runtime class has SRG names (func_76706_a,
  field_76716_d, ...), which is what the hook's reflection targets.

## 2. Engine (`crates/region-io/src/live.rs`)

`LiveRegionFile`: sector map + per-chunk runs loaded from the CURRENT
on-disk location table at open (files pre-exist with thousands of vanilla
chunks); `write_chunk(x, z, payload, generation)` = validate ticket → free
old run → first-fit allocation (header sectors skipped, shortfall growth) →
payload sectors BEFORE location entry (vanilla crash ordering) → timestamp
at `HEADER_BYTES/2 + index*4` → mirror-safe rollback (disk resync) on IO
error. STALE_GENERATION rejection for non-advancing tickets.
`note_external_write`/`note_vanilla_fallback` resync the map after vanilla
fallback writes. `EngineRegistry`: one engine per canonical path;
`normalize_key` canonicalizes the PARENT dir + case-folds (Windows
`canonicalize` is asymmetric for not-yet-existing files — found live: the
same file got two engines and generation floors went missing).

**Generation tickets have a single source of truth: Java.** The hook's
per-region `AtomicLongArray` is seeded from the engine's committed floors
(`generationsSnapshot`) and advanced on every Rust admission AND every
vanilla fallback; the engine stores exactly the ticket it is told and never
invents one. Divergence → false STALE rejections is structurally impossible.

## 3. FFI (`crates/ffi/src/region_write.rs`)

`RegionWriteCtx`: `create/write/noteExternal/close/statsSnapshot/
generationsSnapshot/usedSnapshot` following the CompressionCtx handle model
(Box<Arc> as i64, single-owner close, panic-catch at the boundary, direct
Buffer addresses, no GetPrimitiveArrayCritical). Offline JNI-surface tests
prove create→write→stale→stats→close→shared-attach→fresh-attach without a
JVM.

## 4. Java bridge + transformer

- `RegionFileAuthorityTransformer` (registered by `LiveSessionAdmissionTweaker`
  under `-Drustcraft.regionWriteExperiment`; the tweaker launch shape does
  NOT read the manifest coremod transformer list): adds the public state
  field `rustcraft$rwState`; at the write-seam ENTRY injects the admission
  call (`true` → RETURN, vanilla body skipped); before every ORIGINAL
  return injects the fallback note; at close entry injects the native free.
  Idempotent (re-transform guarded), fail-open (any error → vanilla bytes).
- `RustRegionWriteHook`: per-instance state (lazy init through the injected
  field, race-free by the synchronized seam), grow-only direct payload
  scratch, SHADOW / ON_EXPERIMENTAL modes, admission cap, fail-every-N
  stress switch, per-status-code failure tallies.
- **Coherency (bytecode-evidenced):** vanilla `func_76704_a` returns null
  when `offset + count > field_76714_f.size()` and vanilla's fallback
  allocator allocates from that same free list. After every Rust write and
  every fallback note the hook therefore mirrors the engine's used-map into
  `field_76714_f`. Without this: Rust-grown files read as MISSING in-session
  and vanilla fallbacks clobber Rust records (both reproduced in the harness,
  both fixed).
- SHADOW mode: Rust commits to a mirror directory (sanitized absolute path
  under `-Drustcraft.regionWriteMirror`); vanilla always writes the real
  file; offline comparison judges the two.

## 5. Evidence

### 5.1 Unit + offline (all green)
- region-io 8/8 (write/readback/generation-order, fallback resync + cross-chunk
  protection + same-chunk in-place reuse, 200-rewrite no-leak, same-size
  in-place reuse, registry sharing); FFI 2/2.
- `RegionWriteLiveHarness` (real SRG RegionFile bytes → transformer → real
  synchronized seam on a real Gate A region copy): **SHADOW 24, ON 24,
  ON cap=10, ON failEvery=5 FULL-REGION (679 chunks, 544 Rust admissions +
  135 real vanilla fallbacks interleaved), MULTI (3 live regions, one JVM)**
  — in-session coherence + fresh-vanilla re-read + fresh-process (RV) hash
  verification, every produced .mca scans clean (bad=0, overlap=false).

### 5.2 In-server campaigns (real Forge, headless probes + in-server teleport
corridor + `save-all flush` + graceful stop)

#### 5.2.1 Gate A SHADOW — PASSED
Seam hooked (ayj, boot-print verified), entryCalls=4144, rustOk=4142
(2 fail-open CAPACITY_ERROR: payloads beyond the 255-sector location-format
ceiling, where fail-open reproduces vanilla's own limit), 0 errors,
**1463 chunk payloads compared mirror-vs-real: 0 mismatches** (floor 1000).

#### 5.2.2 Gate C SHADOW — PASSED (shadow-proven; floor met as write events)
Two runs: entryCalls 7477 / 5092, **rustOk 7475 / 5089** (≥5000 write floor;
3 fail-open capacity per run 2), 0 errors. Unique chunk payloads compared
mirror-vs-real: 1509 + 1344 = **2853 comparisons, 0 engine mismatches**.

#### 5.2.3 Gate C external-writer finding (drives the live-authority park)
The comparator classifies divergences by last-write timestamps: a real-side
record at the same second or newer than the mirror's last seam write cannot
be a Rust payload error (a seam event writes IDENTICAL bytes to both files
in SHADOW) — it is an **external write: a mod-shaded Anvil-format writer
bypassing `func_76706_a` entirely**. Gate C evidence: 1+2 such divergences
(Thaumcraft aura-ticked corridor chunks; real timestamps 100s newer than the
mirror's last seam write) plus 3-4 region files created entirely outside the
seam. Gate A (clean Forge): zero such events across 1463 comparisons.
Consequence: a modded runtime can write region files Rust cannot observe, so
a Rust allocator live on the SAME files (ON mode) could collide with those
writers' private free lists. **Gate C ON-mode is therefore PARKED** until
the engine gains external-write observation (file-watch resync or a
save-time journal). Gate A has no bypassing writers and took live authority.

### 5.2.4 Gate A ON_EXPERIMENTAL (LIVE AUTHORITY) — PASSED
`-Drustcraft.regionWriteMode=ON_EXPERIMENTAL`: Rust wrote the REAL region
files and the vanilla write body was skipped for every admitted write
(exitNotes=0). Session: entryCalls=3928, **rustOk=3928, 0 failures,
0 errors**; structural scan clean on all 31 touched region files (bad=0,
overlap=false). Then **25 consecutive fresh vanilla-Forge restart cycles
(experiment OFF, same world): 25/25 boot-to-Done**, full FML probe PASS at
cycle 1 and cycle 25 — the Rust-written world survived 25 real
Java/Forge restarts and served normal logins every time.

### 5.3 Write bench (5000 real Revelation DEFLATE records through both arms)
| arm  | p50 | p90 | p99 | max | total |
|------|-----|-----|-----|-----|-------|
| Java vanilla seam | 25.4µs | 42.7µs | 74.6µs | 1391ms* | 1789ms |
| Rust engine       | 15.8µs | 20.6µs | 51.6µs | 2.98ms | 317ms |

*Java max is first-write warmup; steady-state percentiles 1.5–2.1x in
Rust's favor with a dramatically tighter tail.

## 6. Verdict

**RUST_REGION_WRITE_AUTHORITY_PROVEN (bounded).**

- Live in-server authority (Rust owns the final region write, vanilla body
  skipped) is PROVEN on Gate A: 3928 real writes in one live session with
  zero fallbacks, zero errors, clean structural state, and a world that
  survived 25 fresh vanilla restarts with full FML logins.
- Shadow proof on both gates: 9229+ Rust shadow writes total, 4316 unique
  payload comparisons, 0 engine mismatches anywhere.
- Boundaries held: Java/Forge keeps ALL semantic data; Rust touches only the
  final region record; PRODUCTION_AUTHORITY remains false (ON_EXPERIMENTAL
  gate); capacity fail-opens reproduce vanilla's own 255-sector ceiling;
  Gate C live authority is parked with a precise mechanism-level cause
  (mod-shaded bypass writers) and a remediation path.

## 7. Remaining / next increments
- External-write observation for modded runtimes (file-watch resync or
  save-time journal) → unlocks Gate C ON-mode.
- Direct payload capture (eliminate the one heap→direct staging copy).
- NBT authority stays BLOCKED (H9 lossiness, unchanged from feed7a5).
