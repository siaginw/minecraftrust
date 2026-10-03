# RUST_REGION_WRITE_AUTHORITY — Milestone Report

Status: **IN PROGRESS** (Gate A shadow PASSED; Gate C campaign + live-authority
restarts + verdict pending)

Goal: convert the proven offline region engine (RUST_REGION_IO_ENGINE,
feed7a5) into a REAL bounded in-server write authority. Java/Forge builds all
semantic data (ChunkDataEvent.Save, TE/entities/capabilities → chunk NBT →
Deflater); Rust owns ONLY the final region-file write (sector allocation,
framing, I/O, crash ordering). PRODUCTION_AUTHORITY stays false this
milestone: live authority runs behind `-Drustcraft.regionWriteMode=
ON_EXPERIMENTAL`, shadow mode is the default campaign shape.

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

### 5.2 In-server SHADOW campaigns (real Forge, headless probes + in-server
teleport corridor + `save-all flush` + graceful stop)
- **Gate A (Clean Forge 2860): PASSED** — seam hooked (ayj, count=1),
  entryCalls=4144, rustOk=4142 (2 fail-open CAPACITY_ERROR: payloads beyond
  the 255-sector location-format ceiling, where fail-open reproduces
  vanilla's own limit), 0 errors, **1463 chunk payloads compared
  mirror-vs-real: 0 mismatches** (floor 1000).
- Gate C (FTB Revelation 2846): running (floor 5000 comparisons).

### 5.3 Write bench (5000 real Revelation DEFLATE records through both arms)
| arm  | p50 | p90 | p99 | max | total |
|------|-----|-----|-----|-----|-------|
| Java vanilla seam | 25.4µs | 42.7µs | 74.6µs | 1391ms* | 1789ms |
| Rust engine       | 15.8µs | 20.6µs | 51.6µs | 2.98ms | 317ms |

*Java max is first-write warmup; steady-state percentiles 1.5–2.1x in
Rust's favor with a dramatically tighter tail.

## 6. Remaining (this milestone)
- Gate C shadow campaign completion (≥5000 comparisons, 0 mismatches).
- Gate A ON_EXPERIMENTAL live-authority session + 25 fresh vanilla restart
  cycles on the Rust-written world (experiment OFF; boot-to-Done + probe).
- Gate C live-authority session + restart verification.
- Verdict + docs + push.
