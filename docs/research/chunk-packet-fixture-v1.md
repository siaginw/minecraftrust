# Immutable chunk-packet fixture format, version 1

Status: format definition for future replay and capture work. This change does
not supply Forge/modpack fixtures, implement live capture, or certify any
existing historical record as a coherent snapshot. The companion JSON Schema
is [chunk-packet-fixture-v1.schema.json](../schemas/chunk-packet-fixture-v1.schema.json).
Its structural checks are necessary but not sufficient; the semantic checks
below are normative and require a future importer/replayer.

## One event, one immutable document

Each JSON document describes one accepted serialization event. `eventId`
identifies that event; retries, recaptures and later encodes get new IDs and new
documents. A result from one attempt must never be spliced onto sections,
flags, registry data or Java-reference bytes captured in another attempt.
The document is immutable after hashing. Store it by `fixtureSha256`; record
later analysis separately by that hash instead of editing the fixture.

`captureKind` is either `SYNTHETIC` or `LIVE_FORGE`. Synthetic inputs require a
deterministic generator name/version/seed and use no invented Forge identity.
Live fixtures require exact Minecraft/Forge/runtime identities, transformed
writer provenance, ownership evidence and actual Java-reference bytes from
the same event. A synthetic fixture passing replay is never live parity proof.
Failed capture/encode attempts belong in separate failure records, not success
fixtures with shortened payloads or guessed masks.

## Required contents

| Field | Representation and meaning |
| --- | --- |
| `schemaVersion` / `format` | Integer `1` / literal `rustcraft-chunk-packet-fixture`. Unknown versions fail closed. |
| `eventId` / `captureKind` | Unique stable event token and explicit provenance class. |
| `runtime` | Minecraft `1.12.2`, protocol `340`, exact Forge version or null for synthetic; ordered classpath, mods, coremods and configurations with SHA-256 identities. Ordering is retained rather than sorted away. |
| `registry` | Registry export artifact identity, entry count and exact global-palette bit width used at the event. A synthetic registry export must be labeled synthetic. |
| `chunk` | Signed 32-bit dimension/X/Z coordinates and generation identifier as an unsigned decimal string. |
| `requestedMask` | Unsigned 16-bit Java caller filter observed for this event. |
| `encoderSelectedMask` | Unsigned 16-bit selection actually handed to this encode. It may differ from the request because of Java section-selection semantics. |
| `emittedMask` | Unsigned 16-bit mask returned with `bytesWritten` by this exact successful operation; never a post-encode query. |
| `fullChunk` / `skylight` | Explicit booleans from the same event; do not derive one from a later mask or dimension lookup. |
| `sections` | Exactly 16 entries, indexed by section Y. Null means absent; an object preserves all 4096 logical state IDs, source palette metadata, refcounts and light arrays. Present but unselected sections may be retained. |
| `biomes` | 256 raw bytes when `fullChunk` is true; null otherwise. |
| `payload` / `bytesWritten` | Exact section-payload bytes, excluding outer packet framing, and their exact count. No output-buffer slack is included. |
| `decodedSectionCount` / `consumedByteCount` | Independent strict decoder's observed section count and total consumption, including biomes where applicable. |
| `javaReference` | Same-event Java-emitted mask and payload; mandatory for `LIVE_FORGE`, optional/null for synthetic native-only cases. |
| `provenance` | Sequence/thread IDs, actual source writer and transformation chain, capture implementation identity, ownership protocol/evidence, encoder build identity, independent decoder identity and synthetic generator when applicable. |
| `hashes` | Hash algorithm, canonicalization version and complete fixture digest. Embedded binary records also carry their own digest. |

Logical state IDs and palette IDs are JSON integers in `0..4294967295`, preserving
32 bits without JavaScript precision loss. Java readers must retain them in
`long` until range validation; native readers must reject unsupported values
before narrowing. The current native section stores `u16` IDs and supports
global widths only through 16: fixtures exceeding those limits are explicitly
unsupported for current native replay, never silently truncated. This schema
can preserve them for diagnosis without claiming the current encoder supports
them. Generation, sequence and thread IDs are canonical decimal strings in
`0..18446744073709551615`; semantic validation enforces that upper bound.

Logical states use `(localY << 8) | (z << 4) | x` order. Nibble arrays are
exactly 2048 bytes: cell `i` uses the low nibble of byte `i / 2` for even `i`
and the high nibble for odd `i`. Sky light is required for each present section
when `skylight` is true and must be null otherwise. Source palette metadata
records its actual mode, width, palette entry order, and contiguous packed
64-bit word bit patterns as 16 lowercase hexadecimal characters each. These
are memory bit patterns, not signed JSON numbers. Retain palette history where
exact byte replay depends on it; logical equality alone does not imply palette
byte equality. `sourcePalette` may be null only for a synthetic fixture that
starts directly from logical states.

## Binary records and hashes

All embedded binary records contain `encoding: "base64"`, `data`, `byteCount`
and `sha256`. Base64 uses only the standard `A-Z a-z 0-9 + /` alphabet, required
`=` padding, no whitespace, and zero unused pad bits. Decode and re-encode to
verify canonical spelling. `byteCount` equals the decoded byte length, and
`sha256` is 64 lowercase hexadecimal characters computed over those raw bytes.
An empty payload is the empty string with byte count zero and the SHA-256 of
zero bytes; it is a valid non-full, zero-section success.

Artifact records contain a logical relative `name`, exact `version` and digest
of the original artifact bytes. They do not contain machine-specific absolute
paths. An external-artifact resolver must verify the digest before using the
artifact and report missing content as `NOT_RUN / MISSING_EXTERNAL_ARTIFACT`.
Do not substitute a similarly named jar or a regenerated registry dump.

The complete document digest uses `rustcraft-fixture-json-v1`, defined here
rather than relying on a language's default JSON pretty printer:

1. Parse UTF-8 JSON; reject duplicate object keys, invalid Unicode (including
   lone surrogates), BOMs, non-integer numbers and negative zero. Do not normalize
   Unicode strings. Schema unknown properties are rejected.
2. Remove only `hashes.fixtureSha256` from a deep copy. Keep all other hashes,
   fields and array order. The root digest is thus not self-referential.
3. Serialize objects with keys in ascending Unicode scalar-value order; arrays
   retain order. Use no insignificant whitespace. Serialize integers in shortest
   base-10 spelling, booleans as `true`/`false`, null as `null`.
4. Strings use double quotes, `\"` for quote, `\\` for backslash and lowercase
   six-character `\u00xx` escapes for all U+0000..U+001F controls. Do not use
   short control escapes or escape `/`. All other Unicode scalars are literal
   UTF-8. This rule includes keys.
5. Compute SHA-256 over exactly those UTF-8 bytes, with no BOM or final newline;
   put its lowercase hex representation in `hashes.fixtureSha256`.

Hashes attest to content identity. They do not show that Java/mod writers were
excluded, that the real packet writer consumed the captured state, or that a
retained native snapshot was fresh. Matching endpoint hashes cannot establish
a mutation-free interval or exclude an ABA change.

## Required semantic validation before replay acceptance

1. Validate the schema and every artifact/binary/fixture digest. Verify decimal
   identifier ranges, canonical base64 and exact binary lengths. Registry export
   contents must resolve every logical/palette ID used by the fixture.
2. Check `sections[y].y == y`, logical-state count 4096, captured section
   presence/refcounts and source palette consistency. `nonAirCount` records the
   captured source refcount; the registry's air predicate is needed to validate
   it. Do not assume all modded air states have ID zero.
3. Check source packed-word count against its recorded width (contiguous
   bit layout: `ceil(4096 * bitsPerEntry / 64)` words), palette index bounds,
   and decode every logical cell without narrowing. For global palette mode,
   entries are empty and words hold IDs; for local modes, words hold indices.
4. Require `(encoderSelectedMask & ~requestedMask) == 0` and
   `emittedMask == encoderSelectedMask`. Every selected bit has a non-null
   section. Evaluate the actual captured writer's selection rules; do not
   silently rebuild a new mask from current Java or native state.
5. Independently decode the entire payload using its recorded protocol,
   palette/registry width, mask and flags; reject truncation, extra sections,
   unsupported widths and trailing bytes. Require
   `decodedSectionCount == popcount(emittedMask)` and
   `consumedByteCount == bytesWritten == payload.byteCount`.
6. Compare decoded cells/lights/biomes to the same event's captured inputs.
   Validate the Java reference independently and require its actual emitted
   mask to equal the native result for parity acceptance. A mismatch is a
   failing fixture, not permission to alter either recorded mask. Semantic
   comparison and exact-byte comparison are distinct recorded outcomes.
7. For live capture, review and verify the ownership protocol evidence, actual
   transformed writer identity, supported writer inventory and same-event Java
   consumption. Schema validity and digest validity alone cannot pass this
   gate. Missing ownership evidence is not downgraded to a warning.

The current bounded JNI work defines this format but does not implement its
full importer, strict general decoder or live producer. Existing deterministic
Rust/Java in-code synthetic tests remain their own fixtures; they are not
retroactively labeled schema-v1 Forge captures. Replay infrastructure must
distinguish an empty/missing external corpus from tests that actually ran.

## Future packet-shell publication

`COHERENT JAVA SNAPSHOT -> RUST ENCODE V2 -> {bytes_written, emitted_mask}`
`-> Java packet shell consumes emitted_mask`.

Only the successful result paired with the owned payload may populate a future
packet shell. Keep the output buffer alive and unmodified until copied or
ownership is transferred; construct the shell only after complete validation.
No subsequent section walk or mask getter may establish its wire mask. Any
failure discards scratch bytes and leaves Java production state untouched.
This invariant is documented for later migration, not enabled by this schema.
Issue #1 remains open and production retained-native authority stays fail-closed.
