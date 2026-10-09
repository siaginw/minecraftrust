# Synthetic capture fixtures and independent decoder

This corpus validates the offline capture/encode boundary using only the public
checkout. Every fixture is explicitly `SYNTHETIC`. None is a Revelation,
SevTech, Forge or Minecraft capture. Passing it does not establish live writer
participation, actual packet-writer parity or native authority eligibility.

## Corpus and provenance

The 16 immutable success documents in
[`tests/fixtures/issue1-capture`](../../tests/fixtures/issue1-capture) use the
accepted [chunk-packet fixture schema](../schemas/chunk-packet-fixture-v1.schema.json).
They preserve all 4096 logical cells per present section, full light arrays,
flags, masks, registry identity, payload bytes, byte counts, same-event identity
and source hashes. Their independent reference producer is a small Python
synthetic generator, explicitly identified as `encoderBuild`; no native or Java
writer attribution is invented. `javaReference` is null.

The synthetic registry is the explicit range 0..16383 with state 0 as its sole
air state and a 14-bit global palette. This air rule belongs only to this
registry; the validator makes no equivalent assertion about a mod registry.

| Cases | Coverage |
| --- | --- |
| `mask-zero`, `one-section` | Empty non-full payload and one local-4 section, skylight off |
| `sparse-high-sky`, `sparse-8421` | High Y and sparse section order; skylight on; full and partial chunks |
| `mask-001f` | Five present selected sections |
| `palette-local5`, `palette-local8`, `palette-global14` | Local and direct global palette paths, including cross-word entries and IDs above 8191 |
| `transition-empty`, `transition-nonempty`, `transition-cleared` | Mask sequence 0 -> 4 -> 0 for fresh owned events |
| `partial-empty-selected` | Explicitly selected all-air section in a partial payload |
| `direct-light-before`, `direct-light-after` | Identical cells with different block and sky light |
| `biome-before`, `biome-after` | Identical sections with different biome tails |

The versioned [`corpus.json`](../../tests/fixtures/issue1-capture/corpus.json)
manifest has its own [scenario schema](../../tests/fixtures/issue1-capture/corpus.schema.json).
Failure attempts are separate transport mutations referencing an immutable
success input, not invalid success fixtures. Eight scenarios expect explicit
rejection: `0x003F` with only sections 0..4; stale incarnation; logical ID 65536;
JEID storage; changed epoch; off-thread capture; unknown writer scope; and
insufficient capacity. Three named sequences check the transitions above.

These sequences compare distinct owned events. They do not pretend to observe
an async Java writer or prove an ABA-free capture interval. Actual mutation
ordering and writer participation are separate standalone Java model tests.

## Independent decoder

[`packet_decoder.py`](../../tools/testing/packet_decoder.py) imports no Rust
encoder, palette helper or section-selection helper. It reads the wire cursor
directly, uses bounded canonical unsigned-32-bit VarInts, and supports local
widths 4..8 and global widths 9..16 matching the supplied runtime width. Local
palette sizes, duplicate entries, each palette index and exact packed-word
count are checked. All 4096 entries are decoded using contiguous spanning
bit positions; palette IDs retain their full unsigned 32-bit values.

For each advertised section it consumes block light and optional sky light,
then the full-chunk biome tail. It requires complete payload exhaustion and
exactly `popcount(emittedMask)` decoded sections. A mask omitting a complete
extra section therefore fails at the final cursor check. Missing advertised
sections, malformed lengths, truncation and trailing bytes all fail.

The Python fixture generator uses a single arbitrary-precision bitstring to
construct expected synthetic bytes. This is independent of Rust's per-word
packing algorithm; it is not a second Minecraft implementation. Validation
never invokes the generator or regenerates expected output.

## Schema, hashes and semantic replay

[`fixture_replay.py`](../../tools/testing/fixture_replay.py) validates the actual
accepted schema with a standard-library evaluator for its exact keyword set;
unknown schema keywords fail closed. It rejects duplicate JSON keys, BOMs,
lone surrogates, non-integer numbers, negative zero, unknown fields, invalid
identifier bounds, noncanonical base64 and count/digest mismatches. Canonical
JSON follows the schema document's control-character and key-order rules.
Artifact hashes are checked against their original checkout bytes. Artifact
paths cannot escape the checkout. No hash normalization hides changed bytes.

The importer resolves the synthetic registry, validates source palettes when
provided, checks section indices/refcounts, validates all selected section
presence and mask coupling, then compares independently decoded states, lights
and biomes to the same document. Optional Java-reference data is separately
decoded. `LIVE_FORGE` input is explicitly rejected because its ownership and
writer-evidence gate has not been implemented.

Native replay constructs the versioned owned `RCSNAP01` transport in a temporary
directory, retaining logical IDs as 32-bit integers. The offline Rust CLI calls
the snapshot encoder and returns one V2 result. The replayer requires that
count, mask, packed V2 value and output length agree, then strictly decodes and
compares the actual output. It reports semantic equality and exact byte equality
separately; palette-history equivalence is not assumed from logical equality.
Native executable and output hashes are recorded in the replay receipt.

For a rejection, no output file may exist. The malformed input and scratch
output never become a successful fixture. Omitting the native executable yields
`INCOMPLETE / NATIVE_REPLAY_NOT_RUN`, not a passing replay result.

## Reproduction

```powershell
cargo build --locked -p ffi --example snapshot_replay
python -m unittest discover -s tools/testing -p test_packet_decoder.py -v
python tools/testing/fixture_replay.py --native-replay target/debug/examples/snapshot_replay.exe --report target/synthetic-replay.json
```

The decoder/importer suite contains 37 deterministic tests, including all local
widths 4..8, all global widths 9..16, full-width palette IDs, cross-word entries,
malformed payloads, canonicalization, hash tampering and source-palette checks.
The corpus adds 16 actual native replay cases and eight expected native
rejections; these are reported as cases rather than additional test functions.

Fixture maintenance is explicit:

```powershell
python tools/testing/generate_synthetic_fixtures.py
```

Changing generator/decoder source changes its provenance identity and therefore
the fixture hashes. Review those changes as new synthetic fixture revisions.
Never use regeneration to turn an unexplained failure into a passing baseline.
Scoped LF checkout rules preserve the exact original source/artifact bytes
across platforms without altering the hash verifier.
