# Issue #1 focused guard audit and property expansion

This audit checks whether selected safety predicates are exercised by tests.
It does not prove concurrency, arbitrary-mod compatibility, a same-event Java
reference, or production authority. Production native packets remain fail-closed.

## Tool qualification

Pinned **cargo-mutants 27.1.0** was verified against its exact upstream
[manifest](https://github.com/sourcefrog/cargo-mutants/blob/v27.1.0/Cargo.toml),
[MIT license](https://github.com/sourcefrog/cargo-mutants/blob/v27.1.0/LICENSE),
and crates.io package metadata. Its minimum Rust is **1.88**; this audit used
Rust **1.98.1**, Windows MSVC. The upstream release commit is
`8ab1dc786a1f61a4e370416cc6c68b81a704e917`. The tool was installed in an isolated
tool directory with `cargo install cargo-mutants --version 27.1.0 --locked`;
it is not a project or production dependency.

The official crate SHA256 is
`07072e7bcdeb425d5e5fdbfd9f15a2c749e23cb2edf5ef40aee5876760ae1cf9`.
Machine-local receipts retain the installed binary/toolchain identities and
complete installation and audit output. No cargo-mutants configuration was added
that could silently broaden normal test execution or mutation scope.

## Bounded Rust scope

Only these four functions were selected, in disposable copies:

- `NativeChunk::encode_packet_payload_inner`: selected-section rejection,
  serialization/mask coupling and failure boundaries.
- `OwnedPacketSnapshot::from_transport`: owned admission predicates, widths,
  masks/counts, identity/epoch/thread guards, section/light/biome boundaries.
- `positive_java_id`: valid lifecycle/event/thread identity range.
- `pack_success`: V2 count/mask validation and nonoverlapping result fields.

The exact baseline and mutation test commands include integration tests;
`--lib` alone would have omitted the structural and owned-input regressions.
Only `native-chunk` and `ffi` packages are involved. Explicit package selection
also avoids the unrelated missing NBT binary target sources in this checkout.

```text
cargo mutants -p native-chunk -p ffi \
  --file crates/native-chunk/src/chunk.rs \
  --file crates/native-chunk/src/packet_snapshot.rs \
  --file crates/ffi/src/packet_encode_v2.rs \
  --re 'encode_packet_payload_inner|from_transport|positive_java_id|pack_success' \
  --test-workspace false -C --locked --timeout 30 --build-timeout 120 \
  --jobs 2 --copy-target false --output <new-receipt-directory>
```

The initial audit tested **117 distinct mutants**: **111 caught, 4 missed,
2 unbuildable, 0 timeouts**. Every missed/unbuildable result was investigated:

| Mutation | Classification and disposition |
| --- | --- |
| V2 success-tag/count OR changed to XOR | `EQUIVALENT`: range checks ensure bit 62 and count bits 46..16 never overlap. |
| V2 count/mask OR changed to XOR | `EQUIVALENT`: validated count bits 46..16 and u16 mask bits 15..0 never overlap. |
| Epoch `> i64::MAX` changed to `>=` | `TEST_GAP`: the allowed maximum nonnegative Java epoch lacked an explicit boundary assertion. Added one. |
| Declared section count `> expected` changed to `<` | `TEST_GAP`: malformed excessive count was not independently injected. Added rejection assertion. |
| Whole encode function replaced by `Ok(Default::default())` | `UNBUILDABLE`: `PacketEncodeResult` intentionally has no `Default` implementation. |
| Whole parser replaced by `Ok(Default::default())` | `UNBUILDABLE`: private `OwnedPacketSnapshot` intentionally has no `Default` implementation. |

The two test-gap mutants were rerun with the new deterministic regression:
**both were caught**, with a passing unmutated baseline. Across the original
117 distinct variants the resolved disposition is **113 caught, 2 equivalent,
2 unbuildable, no unexplained correctness survivor**. Their original
baseline/missed receipts are retained. No safety predicate is removed
or weakened to improve mutation outcomes. A mutation score is not a correctness
proof; equivalent and unbuildable variants are explicitly distinguished.

## Java/Python guard injection is a separate tool

`tools/testing/audit_capture_faults.py` uses exact source anchors and disposable
copies. It is **not cargo-mutants support for Java or Python**. It compiles a
standalone Java baseline and runs the independent decoder baseline, then applies
one selected fault at a time. It records source hashes, Java executable hashes,
compiler/test logs and outcomes. Source drift aborts interpretation. A timeout is
`NEEDS_INVESTIGATION`, never automatically a caught semantic defect.

```text
python -B tools/testing/audit_capture_faults.py \
  --java-home <qualified-jdk8> --output <new-receipt-directory>
```

**Nine Java faults were caught**: canonical thread identity, complete writer
inventory, unknown writers, asynchronous writers, observation-only writers,
lease requirement, initial incarnation/generation, TE callback qualification and
post-TE revalidation. These are standalone closed-world tests; they do not infer
which real Forge writers satisfy those contracts.

**Three Python decoder faults were caught**: exact final consumption, packed-word
count and selected-section traversal. A fourth, removing the redundant final
section-count assertion, is `EQUIVALENT`: a validated 16-bit mask controls one
fixed 0..15 loop, which appends exactly once per selected bit or throws before
returning. Exact byte consumption remains separately tested and its removal was
caught. Both language baselines passed; no unexplained correctness survivor
remained in this explicit fault set.

## Expanded properties and concrete regressions

`crates/native-chunk/tests/owned_snapshot_properties.rs` adds five generated
properties and two deterministic tests. Normal runs use 64 cases per property;
`PROPTEST_CASES=256` is an optional larger bounded run. The existing V2 round-trip
and malformed/error-result properties continue in `ffi`.

The independent model generates arbitrary requested/present/empty masks,
full/skylight flags, local/global palette transitions, position-varying light and
biome bytes, positive generation/incarnation values, capacity failure/retry,
admission corruption, missing selected sections, and fill/clear/remove/replace
operation sequences. An independent Rust test reader traverses the entire wire
body up to its exact permitted tail, decodes states, and compares lights/biomes
and emitted count/mask. It uses no encoder palette, packing or selection helper.

Concrete minimized failures are retained as numeric `.case` fixtures through the
existing shared property harness. The first expanded run exposed a **test
assumption**, not a production defect: a retry buffer with 32 extra bytes was
insufficient for the existing conservative five-byte-per-palette-entry capacity
preflight. The regression `owned-capacity-conservative.case` preserves that
minimized input; retry now reserves the supported conservative bound. Exact
actual-length buffers are not incorrectly assumed sufficient for all palettes.

The new deterministic boundary regression also fixes the two mutation-identified
test gaps above. These tests strengthen the offline contract and remain separate
from transformed Forge qualification, real fixture provenance and publication
eligibility.
