# Offline mmap and transactional sidecar experiment — H22.5.G

Decision: **PROTOTYPE** for memmap2 and redb in this isolated offline tool.
The experiment scans generated records and builds a replaceable index. It does
not modify MCA/NBT semantics, install a runtime service, grant native authority,
or qualify a Minecraft workload. Buffered file reading remains the baseline.

## Ownership and format

`Snapshot` creates a new destination with `create_new(true)` and Windows
`share_mode(0)` from the first open, copies at most 4 MiB + 64 bytes, then calls
`sync_all` before permitting a read-only map. Its private file handle remains
alive through the borrowed `View` lifetime. No file handle or mutable mapping is
returned. Existing destination files are rejected; the tool never maps an
arbitrary pre-existing file, whose writable mappings could outlive their handles.
Other platforms fail construction rather than assuming Windows guarantees.

The single unsafe call is the memmap2 read-only mapping constructor, with this
ownership argument at the call site. Bounds, nonzero length and checked end
offset are verified first; OS mapping failures propagate. Write, truncate and
rename probes must fail with Windows sharing violation 32 while ownership is
held, then succeed after release. Compile-fail controls reject dropping the
owner while a view is used and accessing its private file. These are ordinary
Windows filesystem semantics, not protection against privileged handle
duplication, kernel writes, broken filesystems or unsafe code inside the process.
[memmap2 safety contract](https://docs.rs/memmap2/0.9.11/memmap2/struct.MmapOptions.html),
[Rust sharing API](https://doc.rust-lang.org/std/os/windows/fs/trait.OpenOptionsExt.html),
[Windows creation/sharing rules](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew).

The fixture has a 64-byte explicit little-endian header followed by 32-byte
records: logical key, signed value, group, flags and generation. Its schema and
epoch are checked, keys must cover exactly `0..count`, reserved header bytes must
be zero, and short/trailing input is rejected. This is a deliberately small
offline indexing specification, not a substitute world format. The source copy
captures some bytes from the input; it does not promise a coherent live-file
capture. The runner hashes the generated source before and after, and compares
the private snapshot's actual SHA-256 with that expected source.

Both scanners use the same Rust record validator. Buffered scanning uses a
64 KiB `BufReader`; mmap scans immutable slices. Both preallocate the exact
record-count index vector, perform the same per-record validation, and sort
it by logical key. The Python oracle independently generates and parses bytes,
then hashes the complete sorted `(key, offset, value, group, flags, version)`
index and a fixed batch of 256 lookups. Rust recomputes source and index SHA-256
from actual bytes; claimed hashes are not substituted for parsing. The example
only emits a successful result after all these facts agree within its pipeline.

## Sidecar contract

The redb sidecar stores a full key-to-record index plus one metadata row binding
source SHA-256, schema, epoch, record count and full index SHA-256. A single
transaction replaces both tables. Readers check metadata and count, verify every
indexed fact, then perform queries in that same stable read transaction. An old
read transaction retains the old index while a new source/epoch commits. Caller
supplied metadata is only an expected comparison value; it is not an authority
token. The CLI derives it from the owned snapshot and parsed index.

The capped backend delegates reads, persistence and all lock operations to
redb's `FileBackend`, but rejects file growth or writes past 64 MiB. A database
uses an 8 MiB configured cache. Tests cover commit, explicit abort, replacing an
index while retaining an old read snapshot, close/reopen, wrong source/schema/
epoch/count/index metadata, missing metadata, modified records, cap failure and
retry. The workload retains at most two read transactions in the stability test;
it does not establish the behavior of unbounded long-lived snapshots.

A separate process exits with code 71 while an uncommitted write transaction is
alive, bypassing Rust destructors. A fresh process reopens the file and verifies
the entire last committed index. This is a specific process-teardown recovery
control. It is not a power-loss, torn-write, storage-controller or arbitrary
crash-point campaign. redb's broader upstream durability claims are not counted
as locally established evidence. [redb API](https://docs.rs/redb/4.3.0/redb/).

## Measurement protocol and limits

Default runs use 8,192 and 65,536 records (256 KiB and 2 MiB payloads, each plus a
64-byte header), five fresh processes per scanner and size, alternating method
order. Each complete sample measures private copy + sync, source hashing,
mapping setup where applicable, scanning, sorting/hashing the index, sidecar
creation and commit, full index verification, a lookup batch and reopen with
verification. Snapshot and source hash costs are separate named phases and are
also included in `complete`; mapping-only timings are not presented as the whole
pipeline.

An exploratory full campaign exposed unequal vector allocation policies
(buffered preallocation versus fallible collection in the mapping path). The
final implementation uses equal preallocation and is rerun before reporting
comparison results; the exploratory receipt is retained, not silently replaced.

The copy and source-hash pass warm the OS page cache before scanning. These are
not cold-disk or game-server measurements. Sorting and database work may dominate
the small difference between scanner APIs. The generated fixed-width records
exclude decompression, NBT traversal, chunk scheduling, real mod data and server
contention. Any scanner benefit is confined to this workload and host.

Binary processes run under the frozen H9 Windows Job helper: 512 MiB process
commit limit, one active process, 30-second deadline, bounded output and cleanup.
Reported `PeakProcessMemoryUsed` is peak process committed memory, **not RSS**.
Data and sidecars are capped independently; sample count is limited to five, and
the runner rejects a retained campaign larger than 2 GiB. Actual retained bytes
are reported. Compiler subprocesses have their own output and time bounds.

## Evidence and rerun

The campaign records 12 Rust unit tests, 2 compile-fail controls and 5 Python
controls, exact commands/raw outputs, input/source/tool/config/artifact hashes,
locked dependency provenance, isolation and closed-gate checks before/after,
per-sample facts and timings, negative format/epoch controls and recovery output.
The runner rejects wrong session/challenge/schema/type/hash facts through a
strict result contract. Failure gets a scoped receipt with available raw logs.

```powershell
& 'C:\Python314\python.exe' -B tools/offline-storage-experiment/run.py
```

Output must be a fresh child of this checkout's
`target/offline-storage-experiment`. Source/tool/config/dependency/input drift
invalidates the run. The inherited Java/Rust option environment is cleared and
the active Rust toolchain is pinned; this remains a host-specific measurement,
not a hermetic OS/linker image. No global toolchain or root Cargo change is made.

Measured values are recorded in each campaign's `summary_ns` and raw `samples`.
The final report accompanies the frozen receipt; this document defines the
protocol so subsequent reruns can be compared without rewriting source inputs.

The equal-allocation campaign `789fb97af47242a49b772f37734bd102` passed all 20
semantic samples and controls. Its retained receipt SHA-256 is
`e194af774cf4fe4467c6e6029addf97ce769bdd04c1b46642b95eee66e21c6ad`.
Values below are milliseconds, median with observed minimum–maximum in brackets:

| Payload / scanner | Scan only | Complete pipeline |
|---|---:|---:|
| 256 KiB / buffered | 0.091 [0.082–0.108] | 16.765 [15.575–17.862] |
| 256 KiB / mmap | 0.120 [0.095–0.140] | 16.774 [15.871–18.437] |
| 2 MiB / buffered | 0.696 [0.560–0.850] | 68.089 [61.432–70.854] |
| 2 MiB / mmap | 0.771 [0.692–0.908] | 68.885 [63.185–72.300] |

Mapping setup adds roughly 0.028–0.030 ms median. Private copy + sync costs
roughly 0.74–0.83 ms for the smaller input and 2.09–2.10 ms for the larger one;
source hashing adds roughly 0.14–0.17 and 1.11–1.14 ms respectively. Sidecar
creation/commit medians are about 6.2–6.6 and 39.3–39.9 ms. The 256-query batch
costs about 0.045 ms for the smaller index and 0.064–0.080 ms for the larger one,
after the separately charged full-index verification. Database files are
1,056,768 and 4,214,784 bytes. The full retained campaign, including build outputs,
is 283,249,498 bytes; observed limited-process peak commit is at most 7,864,320
bytes, which excludes file-backed working-set accounting.

This evidence does **not** establish a useful mmap benefit over the buffered
baseline. Complete ranges overlap, and indexing/persistence/verification costs
dominate. Retain buffered reading as the default for this proposed offline
workflow. The mapping wrapper remains a narrow ownership prototype; redb remains
a transactional sidecar prototype pending an actual persistent-query workload.
No performance inference is made for Fjall, RocksDB, real NBT or a running server.

## External decisions and provenance

| Candidate | Pin / license | Decision | Value and risk |
|---|---|---|---|
| memmap2 | 0.9.11, MIT OR Apache-2.0 | PROTOTYPE | Useful for repeated scans of genuinely owned immutable snapshots. Creation, hash and page-fault costs must be included. Unsafe mapping obligations exclude arbitrary live world files. |
| redb | 4.3.0, MIT OR Apache-2.0; MSRV 1.90 | PROTOTYPE | A bounded transactional, replaceable offline index with stable readers. Adoption needs representative query/update traces, retention tests, migration/version rules, recovery faults and workload evidence. |
| Fjall | 3.1.10, MIT OR Apache-2.0; MSRV 1.90 | STUDY, not installed | Rust LSM design offers range/prefix access and transactional variants. Its background maintenance, compression, retention and write amplification need a justified sustained-update workload; no evidence here favors adding it. [Primary API](https://docs.rs/fjall/3.1.10/fjall/). |
| RocksDB Rust wrapper | 0.25.0, Apache-2.0; MSRV 1.88 | STUDY, not installed | A mature C++ LSM engine is a comparison candidate for a demonstrated large persistent index workload. Native build/deployment, complete transitive licensing and compaction tuning add scope absent from this prototype. [Upstream repository](https://github.com/rust-rocksdb/rust-rocksdb). |
| sha2 | 0.10.9, MIT OR Apache-2.0 | ADOPT as auxiliary dependency inside this prototype | Recomputes SHA-256 with a maintained implementation, independently checked against Python hashlib. This version is explicitly pinned, not claimed newest. [Primary API](https://docs.rs/sha2/0.10.9/sha2/). |

`research-provenance.json` records primary crates.io metadata responses and pins.
`third-party/inventory.json` binds all **12 locked registry packages**, including
target-specific dependencies, to exact archive SHA-256 and complete cached-source
inventories. **24 license/notice files** are retained with provenance. No Fjall or
RocksDB dependency is installed. No replacement of MCA/NBT storage, memory-mapped
live world mutation or production native authority is proposed by this result.
