# Issue #1 JNI V2 call-site audit

Scope: the public checkout on `issue1-jni-v2`, whose accepted parent is
`baea1a1fa7069365d11b94395e27b5738387b191`. This is a source audit, not a live
Forge/modpack run. Neither the new result carrier nor this audit enables native
packet authority. The accepted structural-stage commits are unchanged.

The search covers these case-sensitive substrings throughout tracked files and
new, non-ignored working-tree files:

```text
getPrimaryBitMask|computeEffectiveMask|encodePacket|encodePacketPayload|field_186948_c|primary_bit_mask
```

`encodePacket` already matches the longer names. Inventory counts are matching
**lines**, not regex alternatives or runtime calls. Comments and historical
records are included and distinguished below. Build products, ignored external
artifacts and Git object storage are excluded; they are not public source.
The accepted parent had 116 matching lines. The inventory appendix records
current matches by file and line, including the additive V2 code/tests. This
audit document is explicitly excluded from its own recursive inventory; its
matches are documentation of the listed symbols, classified `SAFE` as audit
provenance, not new call sites. Source line numbers may move after this review.

## Classification and scope

| Classification | Meaning in this audit |
| --- | --- |
| `LEGACY` | Preserved length-only API, benchmark or historical record. It is not a combined-result API. |
| `FIXTURE_ONLY` | Offline/synthetic oracle or test; some old fixtures require external Minecraft jars and were not executed here. |
| `SHADOW_ONLY` | Observation, refresh validation or comparison; it does not establish production authority. |
| `PRODUCTION_BLOCKED` | The retained-native production entry remains unconditionally fail-closed. |
| `FUTURE_AUTHORITY_UNSAFE` | An independent mask observation/recomputation can be paired with bytes. It must not be promoted into future authority. |
| `SAFE` | Correctly bounded operation or provenance use for its stated purpose only; not certification of live Java capture. |

## Relevant source occurrences

Paths abbreviated as `bridge/` below mean `tools/bridge/src/com/rustcraft/bridge/`.
Every matching file has a row in the inventory appendix, including files whose
only match is a comment or historic result.

| File | Symbol | Current role | Classification |
| --- | --- | --- | --- |
| `crates/native-chunk/src/chunk.rs` | `primary_bit_mask` | `primary_bit_mask`: retained selection, construction/refresh bookkeeping, occupancy/persistence metadata. Packet encoding preflights every selected section and returns byte count plus accumulated emitted mask together. Other metadata consumers do not certify an encode. | `SAFE` for the Rust structural contract |
| `crates/native-chunk/src/lib.rs` | `primary_bit_mask` | Mask assertions in existing native lifecycle/refresh tests. | `FIXTURE_ONLY` |
| `crates/native-chunk/tests/packet_encode_contract.rs` | `primary_bit_mask` | Deliberate mask corruption and deterministic strict-encode/refresh assertions. | `FIXTURE_ONLY` |
| `crates/native-chunk/examples/memory_model.rs` | `primary_bit_mask` | Counts/prints retained section occupancy; no packet publication. | `SAFE` |
| `crates/chunk-packet/src/lib.rs` | `primary_bit_mask` | `primary_bit_mask` in INPUT-A encoder and predictor: checks count, selected indices and duplicates in one staged input. Separate subsystem; does not prove Java staging or shell coherence. | `SAFE` for local staged-input validation |
| `crates/ffi/src/native_chunk.rs` | `encodePacketPayload`, `getPrimaryBitMask`, `primary_bit_mask` | `getPrimaryBitMask` returns a separate current-state observation. `encodePacketPayload` keeps its length-only signature and drops emitted-mask metadata. Occupancy comment describes an independent consumer. | `LEGACY`; getter/encode pairing is `FUTURE_AUTHORITY_UNSAFE` |
| `crates/ffi/src/packet_encode_v2.rs` | `encodePacketPayloadV2` | New `encodePacketPayloadV2` JNI export calls the native encoder once, packs only that result, and contains failures. Native map/chunk locks are not Java-writer synchronization. | `SAFE` for the V2 result boundary; no authority |
| `crates/ffi/tests/packet_encode_v2.rs` | `encodePacketPayload`, `encodePacketPayloadV2`, `primary_bit_mask` | Direct synthetic JNI ABI/contract regression calls, including negative paths and legacy parity. | `FIXTURE_ONLY` |
| `bridge/NativeChunkBridge.java` | `encodePacket`, `encodePacketPayload`, `encodePacketPayloadV2`, `encodePacketV2`, `getPrimaryBitMask` | Existing `getPrimaryBitMask`, `encodePacketPayload` and `encodePacket` are unchanged legacy surfaces. Additive `encodePacketPayloadV2` / `encodePacketV2` carry/decode one result with no mask getter. | Old APIs `LEGACY`; V2 helper `SAFE` |
| `bridge/PacketEncodeResultV2.java` | `PacketEncodeResultV2.decode` (supplementary review) | Pure Java decoder for the packed result, rejecting errors/reserved words as successful metadata. No search token need appear in this helper for it to be reviewed. | `SAFE` for representation validation |
| `bridge/NativeChunkPacket.java` | `computeEffectiveMask`, `field_186948_c` | Reflects `field_186948_c`. `finishPacketPopulation` sets the shell mask by calling `computeEffectiveMask` **after** receiving payload bytes. Staging also computes a mask and later re-walks sections. Diagnostic mask reconstruction is observational. | Shell/staging pattern `FUTURE_AUTHORITY_UNSAFE`; diagnostic reconstruction `SHADOW_ONLY` |
| `bridge/M4NativeStatePayload.java` | `encodePacket`, `getPrimaryBitMask` | `tryEncode` returns null unconditionally. Its private legacy `encodeOnce` and fixture body later query native mask and compare/refresh live-looking Java state. Those observations are not metadata for the earlier bytes. | Production `PRODUCTION_BLOCKED`; fixture body `FIXTURE_ONLY` and `FUTURE_AUTHORITY_UNSAFE` if promoted |
| `bridge/M4Coherency.java` | `encodePacket`, `getPrimaryBitMask` | `encodePacket` after refresh, then `getPrimaryBitMask`, then mask-driven decode validation. Comment calls it actual mask, but it is a separate observation. | `SHADOW_ONLY`; pairing `FUTURE_AUTHORITY_UNSAFE` |
| `bridge/M4PacketCompare.java` | `encodePacket`, `field_186948_c`, `getPrimaryBitMask` | Immediate comparison and checkpoint paths encode then query a mask; completed authoritative samples query a later mask; deferred comparison parses new native bytes using captured Java mask. Reflection reads Java packet mask for diagnostics. | `SHADOW_ONLY`; independent pairings `FUTURE_AUTHORITY_UNSAFE` |
| `bridge/WorldgenShadow.java` | `encodePacket` | Legacy packet encode benchmark/measurement within worldgen shadow. | `SHADOW_ONLY` |
| `bridge/M1DirectEncodeBenchmark.java` | `encodePacket` | Length-only native encode warmup/timing/parity probe. | `LEGACY` benchmark |
| `bridge/M4PacketPerfStudy.java` | `encodePacket` | Length-only native encode timing. | `LEGACY` benchmark |
| `bridge/M4RefreshBenchmark.java` | `encodePacket` | Length-only post-refresh encode timing and descriptive labels. | `LEGACY` benchmark |
| `bridge/M4AuthoritativeTest.java` | `getPrimaryBitMask` | Historical offline authority/fixture oracle queries masks. Its name/comments are not evidence that the production gate is enabled. | `FIXTURE_ONLY` |
| `bridge/M4DecoderGate.java` | `encodePacket`, `getPrimaryBitMask` | Queries mask before a separate legacy encode on controlled test state. | `FIXTURE_ONLY`; unsuitable future authority pattern |
| `bridge/M4LifecycleCases.java` | `encodePacket`, `getPrimaryBitMask` | Legacy encode/mask pairing and stale-generation test. | `FIXTURE_ONLY`; unsuitable future authority pattern |
| `bridge/M4ValidatorBoundary.java` | `encodePacket`, `getPrimaryBitMask` | Legacy encode followed by a mask query for validator tests. | `FIXTURE_ONLY`; unsuitable future authority pattern |
| `bridge/M4PacketParityHarness.java` | `encodePacket`, `field_186948_c` | Real-Java offline packet mask reflection and legacy native encode parity/decoder fixtures. | `FIXTURE_ONLY` |
| `bridge/M4WireParityHarness.java` | `encodePacket` | Legacy native encode compared with offline real vanilla section serialization. | `FIXTURE_ONLY` |
| `bridge/M5PipelineOracle.java` | `encodePacket` | Offline Java/native pipeline oracle and timings. No compression or MCK6 behavior changed by this stage. | `FIXTURE_ONLY` |
| `tools/ProbeNativeNull.java` | `field_186948_c` | Reflects the real Java packet's mask during a controlled null-section probe. | `FIXTURE_ONLY` |
| `tools/ProbeNullSection.java` | `field_186948_c` | Reflects the real Java packet's mask during a controlled null-section probe. | `FIXTURE_ONLY` |
| `tools/chunk-packet-oracle/src/com/rustcraft/oracle/M14RParityHarness.java` | `field_186948_c` | Comment identifies the actual packet mask field used by the offline harness. | `FIXTURE_ONLY` |
| `tools/native-chunk-jni-tests/src/com/rustcraft/bridge/NativeChunkJniV2Test.java` | `encodePacketPayload`, `encodePacketPayloadV2`, `encodePacketV2` | Owned synthetic buffers, real DLL V2 calls, retries, immutable result pairing and a separate legacy parity run. Never queries a mask for V2 bytes. | `FIXTURE_ONLY` |
| `tools/native-chunk-jni-tests/src/com/rustcraft/bridge/PacketEncodeResultV2Test.java` | `PacketEncodeResultV2.decode` (supplementary review) | Representation-only Java decoder tests, including reserved words and failures. Reviewed even if it contains no requested search token. | `FIXTURE_ONLY` |

## Independent-mask hazards that remain

The retained-native branch in `NativeChunkPacket.populatePacket` calls the
production `M4NativeStatePayload.tryEncode`; that entry remains unconditional
`null`. If it ever returned bytes, the present shell helper would subsequently
recompute a mask from Java sections. That is forbidden for future authority.
The existing M1 experimental staging path also calls this shared shell helper;
it is a distinct pre-existing route, not converted to V2 or newly authorized
by this stage. The audit does not claim every native subsystem is globally
disabled. Defaults and the Issue #1 gate remain unchanged.

The getter after `M4Coherency` refresh validation, all later getters in
`M4PacketCompare`, the fixture-only body of `M4NativeStatePayload`, and mask
queries before/after old offline tests remain separate observations. Even with
the new Rust structural checks, none can be used as the successful emitted mask
of a legacy encode. A cached Java mask also cannot substitute for the native
result. Migrating a diagnostic caller requires carrying the V2 result alongside
the copied bytes all the way to its decoder and logs; migration is not implied
by merely adding the API.

`tools/audit_reference_independence.json` already records shared reference/native
selection helpers. It is preserved as provenance; its historical line numbers
are not current code locations. Reusing those helpers as expected-value oracles
can hide a shared selection error. Current V2 synthetic tests instead consume
complete fixture payloads and validate explicit section identities/counts.

## Future packet-shell contract, not enabled

```text
COHERENT JAVA SNAPSHOT
  -> RUST ENCODE V2
  -> {bytes_written, emitted_mask} for one owned output buffer
  -> Java packet shell consumes that emitted_mask with those bytes
```

The shell must not re-walk sections, invoke a mask getter, or use a mask captured
at another time after native serialization. It must not publish output on an
error or malformed result. Buffer lifetime and exclusive ownership extend until
the successful payload is copied or ownership is transferred. Java-owned shell
fields and tile-entity behavior require their own reviewed event contract;
V2 alone proves neither coherent Java inputs nor shell publication safety.

Next coherent-capture work must first specify an enforceable ownership/writer
participation protocol at the actual transformed Java packet writer, enumerate
all block/palette/presence/refcount/light/biome/lifecycle writers, and reject
unknown writers. Capture owned immutable full-width input and actual Java
reference bytes under that same protocol. Generation equality, native locking
and endpoint hashes do not replace that proof. The fixture format records the
required provenance without fabricating a live capture.

Issue #1 stays open: `ROOT_CAUSE_CLASS_HARDENED`,
`HISTORICAL_EXACT_WRITER_UNRESOLVED`. No claim is made about the exact historical
Java=63/native=31 writer.

## Historical/documentary matches

| File | Symbol | Current role | Classification |
| --- | --- | --- | --- |
| `docs/engineering/m1-zero-section-semantics.md` | `field_186948_c` | Explanation of Java's actual wire-mask field versus raw request filter. | `LEGACY` provenance |
| `docs/migrations/M1-ffi-contract.md` | `primary_bit_mask` | INPUT-A schema mask description. | `LEGACY` provenance |
| `docs/migrations/M1.2-input-validation.md` | `primary_bit_mask` | INPUT-A mask/count validation specification. | `LEGACY` provenance |
| `docs/research/m4-1-refresh-design.md` | `encodePacket` | Historic encode benchmark table. | `LEGACY` provenance |
| `docs/research/m4-native-chunk-state-foundation-report.md` | `primary_bit_mask` | Historic native mask/serialization/occupancy description. | `LEGACY` provenance |
| `docs/research/m42a-checkpoint.md` | `encodePacket` | Historic refresh decoder checkpoint description. | `LEGACY` provenance |
| `machine/M42B-packet-parity-results.yaml` | `encodePacket` | Historical pipeline result summary; no new execution or evidence rewriting. | `LEGACY` provenance |
| `machine/M43-authoritative-packet-results.yaml` | `encodePacket` | Historical JNI cost/path summary; not current authority approval. | `LEGACY` provenance |
| `tools/audit_reference_independence.json` | `computeEffectiveMask`, `field_186948_c` | Historical shared-helper/packet-mask audit with old line locations. | `LEGACY` provenance |
| `docs/research/issue1-structural-mask-contract.md` | `encodePacketPayload`, `getPrimaryBitMask`, `primary_bit_mask` | Accepted preceding stage and its then-deferred JNI boundary; retained as the stage's report. | `SAFE` historical stage provenance |
| `docs/research/issue1-jni-v2-contract.md` | `computeEffectiveMask`, `encodePacketPayload`, `encodePacketPayloadV2`, `getPrimaryBitMask` | Current V2 format/boundary specification; not an executable caller. | `SAFE` contract documentation |

## Exhaustive matching-line inventory

The generated appendix below covers every matching line in the stated search
scope. Multiple occurrences on one line count once. Reproduce the baseline
with `git grep -n -E '<pattern above>' baea1a1` and the current tracked portion
with `git grep -n -E '<pattern above>'`. Include new files using
`git ls-files --cached --others --exclude-standard` before staging.

<!-- BEGIN GENERATED INVENTORY -->
Snapshot: **129 matching lines across 39 files**, excluding this audit document.

| File | Matching line count | Every matching line |
| --- | ---: | --- |
| `crates/chunk-packet/src/lib.rs` | 6 | 73, 81, 99, 264, 269, 286 |
| `crates/ffi/src/native_chunk.rs` | 4 | 84, 101, 119, 156 |
| `crates/ffi/src/packet_encode_v2.rs` | 1 | 130 |
| `crates/ffi/tests/packet_encode_v2.rs` | 3 | 4, 5, 176 |
| `crates/native-chunk/examples/memory_model.rs` | 2 | 38, 39 |
| `crates/native-chunk/src/chunk.rs` | 9 | 46, 70, 149, 183, 203, 258, 275, 360, 371 |
| `crates/native-chunk/src/lib.rs` | 4 | 86, 194, 215, 222 |
| `crates/native-chunk/tests/packet_encode_contract.rs` | 9 | 112, 127, 146, 210, 227, 232, 236, 255, 313 |
| `docs/engineering/m1-zero-section-semantics.md` | 1 | 67 |
| `docs/migrations/M1-ffi-contract.md` | 1 | 38 |
| `docs/migrations/M1.2-input-validation.md` | 2 | 10, 12 |
| `docs/research/issue1-jni-v2-contract.md` | 3 | 15, 17, 167 |
| `docs/research/issue1-structural-mask-contract.md` | 3 | 15, 67, 70 |
| `docs/research/m4-1-refresh-design.md` | 1 | 55 |
| `docs/research/m4-native-chunk-state-foundation-report.md` | 3 | 132, 231, 242 |
| `docs/research/m42a-checkpoint.md` | 1 | 79 |
| `machine/M42B-packet-parity-results.yaml` | 1 | 29 |
| `machine/M43-authoritative-packet-results.yaml` | 1 | 77 |
| `tools/ProbeNativeNull.java` | 1 | 30 |
| `tools/ProbeNullSection.java` | 1 | 14 |
| `tools/audit_reference_independence.json` | 6 | 11, 24, 30, 45, 57, 59 |
| `tools/bridge/src/com/rustcraft/bridge/M1DirectEncodeBenchmark.java` | 3 | 86, 97, 127 |
| `tools/bridge/src/com/rustcraft/bridge/M4AuthoritativeTest.java` | 3 | 73, 386, 415 |
| `tools/bridge/src/com/rustcraft/bridge/M4Coherency.java` | 4 | 16, 805, 812, 819 |
| `tools/bridge/src/com/rustcraft/bridge/M4DecoderGate.java` | 2 | 130, 131 |
| `tools/bridge/src/com/rustcraft/bridge/M4LifecycleCases.java` | 3 | 74, 83, 212 |
| `tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java` | 2 | 64, 192 |
| `tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java` | 7 | 302, 330, 424, 656, 855, 931, 945 |
| `tools/bridge/src/com/rustcraft/bridge/M4PacketParityHarness.java` | 8 | 20, 186, 199, 288, 311, 359, 378, 664 |
| `tools/bridge/src/com/rustcraft/bridge/M4PacketPerfStudy.java` | 2 | 90, 96 |
| `tools/bridge/src/com/rustcraft/bridge/M4RefreshBenchmark.java` | 7 | 16, 123, 124, 127, 130, 135, 140 |
| `tools/bridge/src/com/rustcraft/bridge/M4ValidatorBoundary.java` | 4 | 28, 32, 45, 46 |
| `tools/bridge/src/com/rustcraft/bridge/M4WireParityHarness.java` | 1 | 111 |
| `tools/bridge/src/com/rustcraft/bridge/M5PipelineOracle.java` | 3 | 56, 115, 132 |
| `tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java` | 7 | 78, 80, 91, 97, 102, 168, 170 |
| `tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java` | 5 | 184, 604, 679, 734, 753 |
| `tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java` | 1 | 741 |
| `tools/chunk-packet-oracle/src/com/rustcraft/oracle/M14RParityHarness.java` | 1 | 37 |
| `tools/native-chunk-jni-tests/src/com/rustcraft/bridge/NativeChunkJniV2Test.java` | 3 | 62, 141, 248 |
<!-- END GENERATED INVENTORY -->
