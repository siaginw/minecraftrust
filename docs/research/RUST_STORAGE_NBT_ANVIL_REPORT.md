# Rust Storage / NBT / Anvil Report

**Date:** 2026-10-02
**Starting HEAD:** `6731515` (after `RUST_NETWORK_COMPRESSION_AUTHORITY_PROVEN`)
**Scope:** map → profile → corpus → boundary selection → shadow → authority on
disposable copies → fresh-process restart verification. Networking is PARKED
(§41 marker in ROADMAP).

---

## 1. The exact 1.12.2 save pipeline (from bytecode)

`AnvilChunkLoader.func_75816_a` (saveChunk, SERVER thread): builds the root +
level NBTTagCompound (`func_75820_a`), fires Forge `ChunkDataEvent.Save`
(patched in by Forge; mods add keys/ForgeCaps), then `func_75824_a` = **queue**
(pos → `field_75828_a` map, register with `ThreadedFileIOBase`). The IO WORKER
thread later drains: `func_183013_b` → `RegionFileCache.func_76552_d`
(getChunkOutputStream → `RegionFile.func_76710_b`: `ChunkBuffer(8096)` wrapped
in `DeflaterOutputStream` — JDK default level 6) →
`CompressedStreamTools.func_74800_a` writes the NBT stream → `ChunkBuffer.close()`
→ `RegionFile.func_76706_a` (synchronized): free old sectors, first-fit
contiguous allocation, `writeInt(len)+writeByte(type)+payload` at the sector
offset, update location + timestamp tables. Errors mark `field_183014_e` and
are logged (save skipped, world continues).

## 2. The exact load pipeline

`func_75815_a` → `RegionFileCache.func_76550_a` → `RegionFile.func_76704_a`
(location lookup, sector read, `readInt+readByte`, InflaterStream) →
`CompressedStreamTools.func_74794_a` (NBT parse) → DataFixer →
`func_75822_a/func_75823_a` chunk/section/heightmap/biome construction →
TileEntity/Entity construction via their own NBT callbacks → Forge
`ChunkDataEvent.Load` (mods read their keys back) → Chunk object.

## 3. Forge/mod hook map

Java/MOD territory (never Rust): `ChunkDataEvent.Save/Load`, TileEntity
write/read NBT, Entity write/read NBT, capabilities (`ForgeCaps` compound),
`WorldSavedData`, ForgeChunkManager, mod dimensions. Rust MAY own: region
sector allocation/read/write bytes, NBT binary encode/decode of the FULL
compound (after Java callbacks produce it), and chunk decompression on read —
all byte-exact layers that preserve mod data by construction.

## 4. Fresh storage profiles (this milestone)

The compression milestone's JFR A/B (Gate A, identical workloads) shows
whole-process exclusive samples dominated by neither compression nor I/O at
probe-scale; the save/load picture from the traced pipeline: NBT object
construction + deflate run on the IO worker; the server thread only builds
the compound and queues. Region writes are 4 KiB-sector RandomAccessFile
writes buffered by the OS — mostly not disk-bound at these world sizes.
Detailed burst JFR (save-all floods, unload storms) is queued as follow-up
instrumentation on the S03–S05/W56–W58 hooks that already exist on the load
side; the save side has NO hooks yet (inventory §6 confirmed).

## 5. Revelation persistence corpus

- Live region corpus: **76,404 chunks** across 142 region files of a real
  post-authority Revelation world copy (machine/TE/entity-rich), all scanned.
- Gate A corpus: 3,182 chunks / 10 regions scanned pre- and post-write.
- Compression corpus overlap: the 68,351-body live packet corpus already
  covers serialization-shape diversity; the region corpus covers
  persistence-shape diversity (ForgeCaps, mod TEs).

## 6. TE / entity / capability coverage

Measured via the full-world NBT validation scan: every one of the 76,404
Revelation payloads parses as a valid root compound through the strict
validator (which walks ALL nested spans incl. ForgeCaps/TileEntities/Entities
sub-compounds). Distinct TE/entity class counts require the Java-side census
hook (ChunkDataEvent reflects them) — listed as follow-up instrumentation;
the semantic comparator operates on complete payloads, so mod data is covered
by construction rather than by class census.

## 7. NBT / region library research (§14)

- `crates/nbt`: full owned tree, true Modified UTF-8 (0xC0 0x80 null,
  surrogate pairs), 512-depth guard, big-endian — but compound keys are
  SORTED on encode and duplicates/empty-list element types are lost (H9):
  **not lossless for round-trips**. Its new zero-allocation tape cursor +
  `validate_root_stream` (added this milestone) is strict and allocation-free.
- `simdnbt` preserved all 40 H9 fixtures but is experimental; `fastnbt`
  worse. Neither adopted.
- `region-io` was a trait stub → now a full engine (§9). fastanvil evaluated
  previously (third-party-eval/anvil-eval); reimplementing exact vanilla
  layout semantics in-tree was preferred for the corruption-sensitive writer.

## 8. Boundary scorecard (measured basis)

| Boundary | CPU/alloc value | Complexity | Mod risk | Corruption risk | Verdict |
|---|---|---|---|---|---|
| A. REGION_READ (Rust sectors+decompress, Java parses) | low-moderate (deflate + read syscalls off Java) | LOW | none (byte-exact) | none (read-only) | **safe, proven** |
| B. REGION_WRITE (Java bytes, Rust sector alloc + write) | low-moderate (I/O off Java worker; Java still serializes) | moderate | none (Java bytes) | manageable (validated atomic commit) | **safe, proven** |
| C. NBT_BINARY_ENCODING | HIGH (alloc churn is in tag objects) | high (needs lossless model first) | high | high (H9 losses) | blocked: `crates/nbt` proven lossy (H9); raw-span redesign required |
| D. VANILLA_CHUNK_NBT_SERIALIZATION | highest | highest | highest (field interleave with mods) | highest | blocked (same lossless prerequisite) |
| E. CHUNK_LOAD_NBT_PARSE | moderate | high | moderate (parse must be perfect) | moderate | deferred until C/D solved |

Measured-driver selection: deflate+I/O is a real but modest cost; NBT object
construction dominates. Since NBT authority is blocked on the lossless
representation, the first boundary is the byte-exact I/O layer: **region
read + write**, which requires no NBT representation change at all.

## 9. Implemented: `crates/region-io` engine (exact .mca contract)

- Reader: header/location parse, sector bounds, strict single-call bounded
  zlib inflate (`StreamEnd` + `total_in` + 1 MiB cap), byte-exact `raw` and
  decompressed `payload` per chunk.
- Writer: exact vanilla semantics — free old run, first-fit contiguous
  allocation skipping the header, grow-by-shortfall, payload write, location
  + timestamp update; atomic commit (temp + sync_all + rename) after full
  image validation (bounds, header-sector exclusion, overlap).
- Scanner: per-file + whole-world reports — bounds, overlap, compression
  type, decompression, strict NBT root-stream validation.
- Fixes found by real data: list-of-END NBT rejection (unbounded-walk bug —
  vanilla rejects it too), single-call inflate (loop pattern hang — replaced).

## 10. Shadow READ campaign (Revelation world copy, 219 mods)

| Reader | Chunks | Method |
|---|---|---|
| Rust `region-io` (sector read + decompress) | 76,404 | SHA-256 per decompressed payload |
| Vanilla `RegionFile.func_76704_a` drained streams | 76,404 | SHA-256 per drained payload |

**Result: 76,404 / 76,404 identical, 0 missing, 0 mismatched.** Whole-world
scan: 142 regions, 0 bad entries, 0 overlaps, 0 decompression failures,
0 NBT parse failures.

## 11. Write authority proof (disposable Gate A world copy)

1. Full world copy + SHA-256 inventory (10 regions, 3,182 chunks).
2. Vanilla-authoritative bytes produced by the REAL vanilla reader + the
   vanilla writer frame (`Deflater` default level, `writeInt+writeByte`):
   200 chunk records.
3. **Rust region writer committed all 200 records** into `r.0.0.mca` (sector
   allocation + header update by Rust).
4. Post-write worldscan: 3,182 chunks, **0 bad entries, 0 NBT failures**.
5. World diff: exactly `r.0.0.mca` changed; the other 9 regions byte-identical.
6. **FRESH-PROCESS verification**: a new JVM, NO Rust anything, boots the
   world through normal Forge (`ServerLaunchWrapper`): `Done (1.260s)`, 0
   chunk-load errors; post-reload worldscan: 3,182 chunks, 0 bad, 0 NBT
   failures.

## 12. Benchmarks (component)

- Rust zlib (zlib-rs) vs Java Deflater at level 6: measured on the 68k-body
  packet corpus in the compression milestone (1.27–2.16x per packet) — the
  region payload codec is the same shape; region read/write differ only by
  sector framing.
- Region scanner: full 142-file world scan (76,404 chunks incl. inflate +
  strict NBT walk) completes in minutes single-threaded with per-chunk
  validation — suitable as a pre/post authority gate.
- Save/load MSPT A/B: deferred to the in-server hook phase (the engine is
  offline; MSPT movement is expected ~0 until then).

## 13. Failure / corruption matrix (covered by tests + design)

short-write → atomic rename never exposes partial files; serialization/
compression exception → counted fallback to vanilla (writer is invoked only
with vanilla-final bytes); truncated region / invalid header / entry past EOF
→ scanner `bad_entries`; duplicate/overlapping sectors → writer refuses
(`Overlap`) and scanner reports; process killed before commit → temp file
only, original untouched; killed after rename → file is the validated image.
Region-io unit tests: 3 green (roundtrip+scan, overwrite reuse,
corruption detection).

## 14. KEEP / REVERT verdict

**Region engine: KEEP (proven).** In-server live wiring of
`RUST_REGION_WRITE_AUTHORITY` (a transformer at `RegionFile.func_76706_a`
feeding the engine, with the save-generation ticket ordering of §31 and the
bounded queue of §32) is the next increment and is NOT wired in this
milestone — so the overall storage authority remains
**`STORAGE_AUTHORITY_NOT_YET_JUSTIFIED` for production** until that hook
lands and the save/load MSPT A/B is measured in-server. The engine, shadow
reads, and the durability proof are done and kept.

## 15. Next subsystem recommendation

1. Land the in-server `RUST_REGION_WRITE_AUTHORITY` hook (transformer +
   JNI, generation tickets, bounded queue), then Gate A/C in-server with
   save/load MSPT A/B and the 25-cycle round-trip campaign.
2. Instrument the save-side hooks (S0x for saveChunk/FileIO) to complete the
   burst profile needed for the NBT-boundary decision.
3. Then re-evaluate C/D (NBT authority) against a lossless representation
   (raw-span strategy from H9).
