# NBT and region pipeline experiment (H9)

Status: **H9_BOUNDED_PROTOTYPE**, generated fixtures only. No production storage
authority, live world access, MCA replacement, root dependency change, or edits
to existing NBT/region/FFI/compression code. The prototype changes exactly one
uniquely addressed `Level/LastUpdate` long by a checked increment. It preserves
the existing version's complete byte representation outside those eight bytes;
it is not a general schema migration or universal mutable NBT codec.

## Findings and candidate decision

The current `crates/nbt/src/codec.rs` uses `HashMap<String, NbtTag>` compounds,
`List(Vec<NbtTag>)`, and Rust `String`. Compound order/duplicate names and the
element type of an empty list are therefore absent from its representation.
Its decoder allocates array/list storage from declared lengths and does not
check trailing root bytes. `crates/region-io` currently exposes a storage trait,
not a complete disk transaction implementation. These are source findings,
not production changes in this milestone.

Four adapters are compared with the same region, bounds, preservation gate,
compression, and write path:

| Adapter | Representation and interpretation |
| --- | --- |
| `lossless` | Custom bounded structural scanner plus raw-span edit. Opaque payloads are never decoded/re-encoded. |
| `existing` | Unchanged repository `NbtDecoder`/`NbtEncoder`, including its original root name. |
| `fastnbt` | Pinned 2.6.3 `Value`, explicit original root-name option, no sorting or loss repair. |
| `simdnbt` | Pinned 0.10.0 owned representation, retaining its raw root name and ordered fields. |

The expanded exploratory run (`target/architecture-hardening/h9-expanded-03`)
had 54 Java fixtures: 40 structurally admissible and 14 rejected by the shared
guard. The custom adapter and `simdnbt` preserved all 40 exactly. The existing
adapter had 16 exact results, 16 preservation losses, and 8 parser rejections;
`fastnbt::Value` had 1 exact result, 32 losses, and 7 rejections. HashMap iteration
means the existing/Value exact-versus-loss counts can vary between fresh runs;
the runner records actual results instead of normalizing order to force a pass.

Examples retained in that receipt include duplicate opaque fields, empty float
list type loss, rejection of a Java lone high surrogate, and raw-null string
normalization. Every first byte divergence is saved with expected/actual lengths,
offset/context and hashes. A rejected serialization is never published. Earlier
independently exact iterations, if any, are recorded separately. No candidate is
silently repaired with the custom output.

**Decision:** reject the existing model and `fastnbt::Value` as drop-in lossless
edit representations for this contract. Keep raw opaque spans as the reference
and fallback design. `simdnbt::owned` is a promising experimental candidate for
this bounded fixture scope, not approved for production. Its unsafe internals,
nightly dependency, fuzz coverage, full mod/world corpus, allocation behavior,
and real storage/lifecycle integration still need qualification. A custom
fastnbt visitor with a separate lossless representation was not implemented;
these findings apply to the tested `Value` adapter, not every possible use.

## Independent format and preservation contract

`NbtFixtureOracle.java` uses Java8 `DataOutputStream` for fixture construction,
`ByteBuffer`/explicit lengths for a separate raw-format parser, and real
`DataInputStream.readUTF` to classify string decoding. It independently locates
the unique editable long, checks overflow, and produces the exact expected
bytes. Raw float/double fields use integer bit writes, so Java's floating-value
serialization cannot canonicalize NaNs. No Rust candidate generates its oracle.

Fixtures cover every scalar and array tag, empty list types 0..12, nonempty list
types 1..12 (including nested lists/compounds/arrays), raw NaN payloads, signed
zero, infinities, duplicate ordered opaque fields, nested ForgeCaps, DataVersion
1343, NEID values through 120000, and a 180000-byte opaque array. Structural
controls include truncation, trailing bytes, unknown wire type IDs, negative and
excessive lengths, nonempty End lists, depth at/beyond the boundary, node/byte
budgets, ambiguous edit fields including Java-decoded overlong aliases, and long
increment overflow. Unknown *named mod fields* are preserved; unknown wire tag
IDs have no safely known framing and are refused.

Modified UTF-8 bytes are retained verbatim, including canonical null, surrogate
pairs, lone surrogates, raw null, overlong ASCII, malformed continuation bytes,
four-byte UTF-8 and truncated sequences. Java readUTF acceptance/rejection is
recorded separately. Structurally framed malformed string bytes can remain
opaque for an unrelated field edit; they are never declared valid Java strings,
decoded to replacement characters, or silently rewritten. Exact field identity
uses Java-compatible ASCII UTF-16 decoding, including accepted overlong forms,
so a second spelling of `LastUpdate` cannot bypass ambiguity refusal. This
behavior follows the distinction between raw preservation and Java's modified
UTF-8 character representation. [Java8 DataInput specification](https://docs.oracle.com/javase/8/docs/api/java/io/DataInput.html#modified-utf-8)

## Complete pipeline and bounded execution

Each successful iteration measures:

1. Actual region file read and complete location/length/overlap validation.
2. Bounded zlib decompression with complete stream consumption.
3. Shared structural preflight, candidate parse, native field extraction,
   checked modification, and candidate encode/raw-span emission.
4. Exact Java-oracle comparison followed by compression.
5. Ordered transaction preparation, full temp-file write, `sync_all`, rename.
6. Reopen, region/decompression validation, and exact output comparison.

The second region chunk, all timestamps, non-target header entries, old sector
allocations, opaque NBT data, and all unmodified bytes must remain unchanged.
This append-allocation prototype leaves the previous target allocation orphaned;
it is intentionally bounded and has no production free-sector allocator.

Raw NBT is limited to 2MiB, compressed chunks to 1MiB, regions to 16MiB, nesting
to 64, visited nodes to 200000, and sequence lengths to 1000000. Size checks and
the common structural scan run before candidate parsers. Decompression uses a
fixed output buffer and rejects overrun, incomplete or trailing zlib streams.
Malformed fixtures therefore qualify the guard, not the unsafe candidates on
arbitrary malformed input. Output encoding/compression/region capacity is also
bounded. Gzip, raw/external chunks, and unsupported compression flags are refused
in this explicitly zlib-only prototype; no broader MCA support is inferred.

Each parser process starts suspended and is assigned a Windows Job before it
runs: 512MiB process commit limit, one-process limit, kill on Job close, 30-second
deadline, and bounded 1MiB stdout/stderr capture. A separate 64MiB allocation
failure control and timeout control prove enforcement. Job peak process commit
is recorded; it is not allocator-only retained memory. Java fixture generation
uses `-Xmx256m`. Compilation is separate from parser processes. These are resource
limits, not a security sandbox. [Windows Job limit API](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-setinformationjobobject)

Root review hardened failure cleanup before acceptance. A failed Job assignment
leaves a suspended child outside Job ownership, so cleanup now directly terminates
that child as well as handling assigned children. It waits for the actual Windows
process handle to signal termination; a cached exit code alone can precede final
teardown. Four actual-process controls cover failure before assignment, failure
before resume, output overflow with a retained bounded prefix, and a child that
closes both output pipes while continuing to run. Resource failures preserve
partial output in a FAIL receipt. No output-limit or startup failure can be
counted as a successful parser or memory-limit control.

## Write ordering, atomicity and rollback scope

Private transactions contain origin-writer identity and a generation. Foreign
transactions, stale queued writes and generation wrap are refused. One serialized
writer checks that its destination still equals its captured bytes, creates a
new same-directory temporary file exclusively, writes and syncs it, then replaces
the destination with `std::fs::rename`. Only a successful rename advances its
generation. Tests inject partial writes, failure before sync, and failure before
rename; the original remains byte-identical and a valid retry succeeds. Other
controls cover foreign/stale transactions, external changes, temporary-name
collision, overlapping sectors, invalid chunk lengths, unsupported compression,
zip bombs, truncated/trailing streams, and encode/region capacity. An actual
Windows sharing-mode lock also forces rename failure; the destination remains
unchanged, the owned temporary file is removed, and retry after unlock succeeds.

Atomicity is the single-file replacement boundary under the tested serialized
fixture writer, not a multi-file transaction or durable journal. Rust documents
replacement rename behavior, including Windows implementation differences.
Directory durability across power loss, kill-at-instruction crash recovery,
external-writer locking, concurrent rename/read races, and real region authority
are not proved here. A noncooperating writer can race the comparison/rename;
production would need exclusive ownership and an appropriate durable protocol.
[Rust rename contract](https://doc.rust-lang.org/std/fs/fn.rename.html)

## Timing interpretation

The receipt records five complete samples per successful case and five batches
of 100 parse-only operations. Parse-only excludes the common safety preflight
for the external adapters; the custom parser's work is itself the preflight.
This asymmetry is explicit. Complete timings include each adapter's actual
cost and all safety, compression, synchronization, and verification stages.
There is no fabricated complete timing for a failed preservation case.

Destination fixture initialization is outside the timed interval; the actual
source read and destination transaction are inside it. OS cache state is warm
and uncontrolled, execution is single-threaded, host contention is recorded,
and these are generated files, not player/server throughput measurements.
The final receipt retains raw samples and process memory; it does not select a
parser from a parse-only number. In the expanded exploratory run, rich-fixture
parse costs were microseconds while full transactions were about 9ms, and the
large opaque array made skipping raw spans much cheaper than allocating an
owned tree. These observations motivate measuring the whole workload, not a
universal speed ranking or changed production defaults.

## Pinned provenance and reproduction

`tools/nbt-region-experiment/PROVENANCE.json`, its standalone Cargo.lock and copied
MIT notices bind candidate release/commit/license identity. The runner verifies
every registry archive against Cargo.lock, then compares every extracted source
file against that archive before building. The final receipt binds all local
sources, Java/Rust/Python executables, input fixture hashes and subprocess output
hashes; a fresh output directory is mandatory. Exploratory failures remain on
disk, including the initial receipt that discovered this Cargo version omits
`.cargo-checksum.json`; verification now checks the actual archive instead.

fastnbt documents its serde-oriented API and root-name handling; this experiment
uses an explicit root-name option. simdnbt documents its ordered compounds and
raw MUTF-8 representation, and explains why parser benchmarks need careful
interpretation. Both candidates are MIT licensed at the pinned commits.
[fastnbt source](https://github.com/owengage/fastnbt/tree/d71c04b2e73d1365c0d677e1f42cf573ccc2b281),
[simdnbt source](https://github.com/azalea-rs/simdnbt/tree/4cc67bcd980c752b412beee0ea1192f502333bf0).

simdnbt 0.10.0 requires `portable_simd` and `allocator_api`. An isolated
`RUSTUP_HOME` under target uses pinned nightly-2026-09-24; no `RUSTC_BOOTSTRAP` or
global toolchain change is used. All candidates share flate2 1.1.10, zlib-rs
0.6.8, compression level 6. simdnbt also enables flate2's default features;
the pinned flate2 feature-selection source gives zlib-rs priority when no C
backend is enabled, which the runner verifies. Production backend and root
dependency files remain untouched. Python zlib constructs independent input
fixtures and checks decompression, but is not the timed output compressor.
[flate2 backend selection](https://docs.rs/flate2/1.1.10/flate2/#ambiguous-feature-selection)

```powershell
python tools/nbt-region-experiment/run.py `
  --cargo C:/Users/Admin/.cargo/bin/cargo.exe `
  --java-home D:/rustcraft-toolchains/temurin8/jdk8u504-b01 `
  --rustup-home target/architecture-hardening/h9-toolchains
```

This needs the pinned isolated toolchain and cached locked dependencies. The
runner uses offline builds, strict tool-scoped clippy/formatting, and the existing
read-only isolation/production-gate guard. Final machine evidence summarizes the
actual run; none of these artifacts grants native packet or storage authority.
