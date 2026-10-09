# RUST_REGION_WRITE_AUTHORITY — Revelation Bounded (Writer Discovery & Coherency)

Status: **`RUST_REGION_WRITE_AUTHORITY_PROVEN_REVELATION_BOUNDED`**
(declared per goal §33; every criterion met — see §Verdict below)

Previous milestone: `RUST_REGION_WRITE_AUTHORITY_PROVEN` (Gate A bounded;
Gate C live authority PARKED because "real Revelation region files receive
writes outside the hooked vanilla `RegionFile.func_76706_a` seam").

This milestone set out to discover, identify, and coherently integrate EVERY
writer that can modify `.mca` region files. The decisive finding:

## 1. The "external writer" did not exist. It was our own engine bug.

The Gate C shadow divergences that motivated the park (real-side records
newer than the shadow mirror) are fully explained by a growth-path defect in
`LiveRegionFile::write_chunk`: the expansion shortfall was computed as
"free sectors anywhere in the last `needed`-sector window", but only the
TRAILING contiguous free run merges with newly added sectors. A partially
free window under-grew the file; the post-growth scan then failed with
`CAPACITY_ERROR` on **any payload size** (live evidence: an 8,188-byte
stream, `needed=3`). Every campaign `err4` event traces to this bug. Each
failed event fail-opened to vanilla (real file written, mirror stale) —
producing exactly the "external writer" timestamp signature.

The bug was pinned with a deterministic capture at the return site
(`needed=7, used_len=4594, tail_free=3`) and fixed (grow by
`needed - trailing_free_run`), with a 14,400-operation capacity fuzz
(60 reopen cycles, interleaved coordinated fallbacks) that reproduces the
failure on the old code and completes with **zero capacity errors** on the
fixed engine (`crates/region-io/tests/capacity_fuzz.rs`).

## 2. Writer discovery (goal §3-§5)

**Static survey** (`tools/authority-review/survey_mod_writers.py`): all 193
Revelation mod jars + the vanilla jar scanned for Anvil-writer bytecode
fingerprints (RandomAccessFile/FileChannel + Deflater + sector arithmetic +
.mca/region literals + relocated RegionFile copies). Result: **0 writer
suspects in 193 mod jars**; the detector was validated by flagging exactly
one class in the vanilla jar — `ayj` (RegionFile). `.mca` path literals
appear only in client-side mods (journeymap, moreoverlays,
BetterAdvancements) with no write path.

**Dynamic attribution** (`rustcraft-attribution.jar`, goal §4, test-only):
a premain agent instruments `java.io.RandomAccessFile` write entry points
(bootstrap-attached helper) and journals every Java-side write to a region
`.mca` path with timestamp, thread, file position, length, and caller
frames, classified `VANILLA_REGIONFILE` vs `UNKNOWN` (the Rust engine owns
an OS handle and never appears in java.io — any UNKNOWN stack would be a
true bypass writer).

**Campaign-scale attribution (Gate C, teleport corridor + save bursts):**

| Run | Journal events | VANILLA_REGIONFILE | UNKNOWN |
|-----|----------------|--------------------|---------|
| Shadow (12,262 Rust writes) | 114,687 | 114,687 | **0** |
| ON authority (10,321 Rust writes) | 22,439 | 22,439 | **0** |

**Every Java-side region write in a live 219-mod Revelation server flows
through the hooked vanilla seam.** The writer universe is exactly
{hooked vanilla RegionFile, Rust engine}. Goal §5's `UNKNOWN = 0` is met at
campaign scale.

## 3. Bidirectional coherency (goal §8-§13)

Writers sharing live region files: exactly two, and both are coordinated.

- **vanilla → Rust**: every vanilla body completion is noted inside the same
  critical section (`regionWriteExit` runs under the RegionFile monitor —
  save PREPARATION is server-thread work, but `func_76706_a` itself is
  reached from `AnvilChunkLoader`'s `IThreadedFileIO` path too, so the
  per-instance synchronized seam, not any thread identity, is what
  serializes writers) → `note_external_write` resyncs the engine from disk.
- **Rust → vanilla**: every Rust commit mirrors the entry/timestamp arrays
  AND the engine's used-map into `field_76714_f` (vanilla's free list), so
  vanilla's allocator can never select Rust-occupied sectors.
- **Race window (§12)**: impossible for the coordinated pair by
  construction — `func_76706_a` is ACC_SYNCHRONIZED and every writer
  (server-thread save preparation and the `ThreadedFileIOBase`/IO-thread
  flush alike) executes under the SAME per-instance RegionFile monitor, so
  admission, commit, mirror, and note are serialized per file regardless of
  thread. A post-hoc file watcher cannot close a race
  it observes too late — so the engine does not rely on one:
- **Verify-before-free (§10 generic detection, §11 fail-closed)**: before
  freeing a chunk's previous run the engine reads the on-disk location entry
  and compares it with its belief. Any divergence (an UNCOORDINATED writer)
  resyncs, marks the file **disqualified**, and refuses authority
  (`NOT_ELIGIBLE`) for the session — no re-promotion. The Java hook maps
  that to JAVA_ONLY for the region file (`regionDisqualified`,
  `externalWriterObserved` counters).
- **Chosen strategy**: (A) hook every writer — tractable because there is
  exactly one other writer and it is already hooked — backed by (D)
  fail-closed exclusion as the defense-in-depth for any future uncontrolled
  writer. Proof tests: `crates/region-io/tests/mixed_writer.rs` —
  coordinated interleave (2,000 rounds) stays consistent; adversarial
  uncoordinated relocation is detected with the adversary's record intact
  and the region excluded; **100,000 mixed same-region operations** end
  structurally clean (0 overlaps / 0 bad / 0 decompress failures); four
  parallel regions confirm per-path isolation.

## 4. Provenance registry & live counters (goal §14, §22)

Per canonical region path the hook accumulates
`rustSelected / rustCommitted / rustFailed / exitNotes / disqualified`
(dumped at shutdown as `regionProvenance` lines) plus the session counters
`regionDisqualified`, `externalWriterObserved`. Campaign gates require
`unknown_writer = 0`, `double_writer_detected = 0` (comparator mismatched=0),
`coherency_failure = 0`.

## 5. Gate C evidence (post-fix)

### Shadow (goal §15)
- **12,262 mediated Rust shadow writes** (floor ≥5,000), 0 hook errors.
- Comparator: 1,709 unique chunk payloads compared, **0 mismatches,
  0 external-classified divergences** (the previous runs' 1-3 external
  divergences are gone with the growth fix — they were engine failures, and
  the campaign-level journal shows `UNKNOWN = 0` regardless).
- Attribution journal: 114,687 events, 100% `VANILLA_REGIONFILE`.

### ON authority on a disposable world copy (goal §21-§26)
- **10,321 real Rust writes into real Revelation region files, 0 failures,
  0 vanilla fallbacks, 0 hook errors** — the vanilla write body was skipped
  for every admitted save.
- Structural scan after the session: **156 region files, all bad=0,
  overlap=false** (goal §24).
- **10 consecutive fresh vanilla-Forge restart cycles** (experiment OFF,
  same world): 10/10 boot-to-Done; full FML probe PASS at cycle 1 and
  cycle 10 against the Rust-written world (goal §25-§26; 25 cycles were
  proven on Gate A in the previous milestone).
- Attribution during the ON session: 22,439 events, `UNKNOWN = 0`.

### Eligibility (goal §20)
With `UNKNOWN = 0` at campaign scale and verify-before-free as the runtime
guard, **100% of Revelation world-region files are Rust-eligible**; the
fail-closed mechanism exists for any file where an uncoordinated writer is
ever observed, and partial authority remains valid if one appears.

## 6. Performance (goal §27-§28)

Save-burst A/B (`run_save_burst_ab.py`, identical Gate C workloads: full FML
probe join, 96-teleport-leg corridor, 4 `save-all flush` bursts, graceful
stop):

| metric | Java-only | Rust ON authority |
|--------|-----------|-------------------|
| chunk packets streamed | 7,525 | 6,504 |
| first save-burst completion | 6,008 ms | **6,007 ms** |
| subsequent flushes | 1.0-1.4 ms | 1.2-1.7 ms |
| process CPU delta (save window) | 118.7 s | 112.0 s |
| Rust writes in session | — | 9,593 (0 failed) |
| disk growth (regions) | 94 KB | 233 KB (more unique chunks) |

Burst-level parity: the first flush is disk-bound (full region sweep) and
the Rust authority adds nothing measurable at burst scale. The intermediate
measurement that motivated a fix: the original coherency mirror rebuilt
vanilla's whole free list per write (14.0 s first flush) — replaced by
incremental O(run) publication into vanilla's own list instance, after
which parity holds. The physical-write engine win (Rust p50 15.8µs vs
vanilla 25.4µs, p90 2.1x — previous milestone's bench) still holds for the
engine itself; §29's direct payload staging remains future work, correctly
sequenced after coherency.

## 7. Verdict (goal §33)

- [x] All world-region writers identified (0 UNKNOWN at campaign scale)
- [x] Allocator coherence bidirectional (note + mirror + verify-before-free)
- [x] Eligible files cannot race uncontrolled allocators (monitor
      serialization + fail-closed disqualification; race window proven
      impossible for the coordinated pair, excluded otherwise)
- [x] Rust writes real Revelation region files (10,321 ON writes)
- [x] Fresh Java/Forge process reload succeeds (10/10 restart cycles)
- [x] Region scans clean (156/156)
- [x] unknown writer count = 0
- [x] double writer / collision count = 0

**`RUST_REGION_WRITE_AUTHORITY_PROVEN_REVELATION_BOUNDED`** declared.
PRODUCTION_AUTHORITY remains false (ON_EXPERIMENTAL gate). NBT authority
remains blocked (H9). Networking remains PARKED.
