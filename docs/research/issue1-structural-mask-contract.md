# Issue #1: structural section-mask contract

Date: 2026-09-24. Scope: native Rust serialization validated using the public
checkout. Issue #1 remains open; production retained-native packet authority
remains disabled.

Classification:

- `ROOT_CAUSE_CLASS_HARDENED`
- `HISTORICAL_EXACT_WRITER_UNRESOLVED`

## Defect and bounded correction

Previously, `NativeChunk::encode_packet_payload` iterated bits in
`primary_bit_mask`, but used `if let Some(...)` for each selected section. A
selected `None` therefore produced no section bytes and no error. The method
could return a successful shorter byte count while the selected mask still
included the absent section. It returned only a byte count, with no emitted mask
attached to that serialization operation.

The encoder now validates the starting output offset and the existence of every
selected section before writing. It retains one selected mask for the operation,
serializes those sections in ascending Y order, and accumulates an emitted bit
only after that section's serialization succeeds. Missing sections, capacity
errors and existing snapshot-check failures return `Err`, never a successful
partial result. Unselected resident sections remain excluded.

This fixes the source-level silent-skip defect. A synthetic `0x003f` mask with
only sections 0 through 4 reproduces the structural shape, not the historical
Java=63/native=31 event or its exact writer. This change does not explain how
those historical states arose.

## Rust result contract

`native_chunk` exports:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[must_use]
pub struct PacketEncodeResult {
    pub bytes_written: usize,
    pub emitted_mask: u16,
}
```

`encode_packet_payload` returns `Result<PacketEncodeResult, &'static str>`.
Both fields describe the same completed serialization operation. For a success
with starting offset `start`, the payload is
`out[start..start + result.bytes_written]`, and its section mask is
`result.emitted_mask`. Callers must preserve this pairing instead of deriving
metadata from later native state or separately observed Java state.

The caller's offset is committed only on success. Missing-section and invalid
starting-offset errors occur before any output write. A later failure can modify
scratch bytes at or after the starting offset; the caller must discard that
attempt's bytes. This is a transactional result/offset contract, not a promise
to roll back the output buffer or native encoding caches.

An empty selected mask is valid: it writes zero bytes for a non-full payload, or
the biome array for a full payload. An error cannot be confused with either
success because it carries no `PacketEncodeResult`. Existing section capacity
preflight behavior is unchanged; exact encoded-size buffers are not newly
guaranteed to be accepted.

## JNI boundary deliberately deferred

The existing `NativeChunkBridge.encodePacketPayload` JNI export returns one
integer byte count. Its signature and negative error convention are unchanged;
the Rust adapter now reads `result.bytes_written`. It necessarily discards the
emitted mask. A separate `getPrimaryBitMask` call is **not** result metadata and
cannot establish packet-header/payload coupling.

Consequently this stage completes the Rust-side contract, not a combined JNI
result or an end-to-end Java packet contract. The legacy export remains usable
only within its existing gated/diagnostic scope. Production
`M4NativeStatePayload.tryEncode` continues to return `null` with `CAPTURE_UNSAFE`,
including when experimental mode is requested. No Java source or authority
default is changed.

The next API stage must introduce a versioned combined-result JNI operation,
with an explicit success discriminator and byte count plus emitted mask carried
together. The JNI adapter must obtain both fields from the same Rust return
value while retaining the buffer's ownership through publication. Do not pack a
new meaning into the old integer ABI. Validate buffer/result lifetime, range,
failure, stale-handle and success semantics using public synthetic JNI fixtures
before migrating callers.

Migration must cover `NativeChunkBridge`, diagnostic calls in `M4Coherency` and
`M4PacketCompare`, immutable-fixture handling in `M4NativeStatePayload`, and the
packet-header assembly in `NativeChunkPacket`. Successful header construction
must use the emitted mask associated with those bytes; it must not call a mask
getter or re-walk mutable Java sections to create a second successful mask.
Shipping that API alone would still not authorize production native packets.

## Deterministic regression coverage

`crates/native-chunk/tests/packet_encode_contract.rs` adds 14 integration tests:

| Test | Contract checked |
| --- | --- |
| `selected_missing_section_is_rejected_before_writes` | Each of the 16 selected-but-absent bits fails without changing output or offset. |
| `historical_003f_shape_with_only_sections_zero_through_four_is_rejected` | Mask `0x003f` with only sections 0..4 fails. |
| `valid_001f_emits_exactly_five_sections` | Mask `0x001f` succeeds with five section records. |
| `sparse_mask_excludes_unselected_resident_sections` | Sparse selection returns that exact mask, even with all sections resident. |
| `every_active_bit_matches_one_serialized_section` | Single-bit and mixed masks, including bit 15 and all bits, have exact section identity/order/count across sky/full flags. |
| `capacity_failure_has_no_success_result_or_published_offset` | Insufficient section capacity returns an error without publishing an offset. |
| `late_biome_capacity_failure_does_not_publish_a_shorter_payload` | Failure after section writes cannot become a successful shorter payload. |
| `retry_after_capacity_failure_succeeds` | A larger-buffer retry succeeds. |
| `retry_after_missing_section_is_restored_succeeds` | Restoring the selected section permits a successful retry. |
| `empty_nonempty_refresh_transitions_keep_mask_and_payload_consistent` | Existing refresh transitions remove and restore the corresponding mask bit and serialized section. |
| `result_pairs_byte_count_and_emitted_mask_for_that_operation` | Nonzero-offset count/mask describe the same bytes and survive later chunk mutation unchanged. |
| `empty_mask_supports_zero_bytes_or_biomes_only` | Empty successes remain explicitly distinct from errors. |
| `invalid_start_offset_is_rejected_without_panicking` | Out-of-bounds offsets, including `usize::MAX`, are rejected. |
| `failed_append_preserves_the_previous_successful_payload` | A failed subsequent structural encode preserves earlier published bytes and offset. |

The synthetic fixtures use distinct constant block/light markers per section.
A small independent scanner consumes the entire payload without using the
returned mask as its loop bound and without calling the production encoder or
decoder for expected bytes. It verifies palette entries, packed words, lights,
biomes, section identity/order and complete byte consumption. This avoids a
shared mask-selection error hiding missing or extra records. It is a scanner
for these fixtures, not a general Forge protocol oracle.

## Public-checkout validation

All of the following passed for this change on stable Rust/MSVC. Logs were
retained with the development-task receipt outside the tracked evidence
registry. No Minecraft/Forge/modpack runtime was needed or executed.

| Command | Result |
| --- | --- |
| `cargo test --locked -p native-chunk` | 10 existing unit tests plus 14 new integration tests passed. |
| `cargo test --workspace --lib --locked` | 67 library tests passed, including the existing 10 native-chunk tests. |
| `cargo test --locked -p protocol` | 10 integration tests passed. |
| `cargo build --release --locked -p ffi` | Release build passed; existing unrelated warnings remain. |
| `cargo test --locked -p native-chunk --test packet_encode_contract` | Explicit final rerun: all 14 passed. |

These commands exercise 91 distinct Rust tests; repeated suite membership is
not additional independent coverage. A full workspace test invocation without
`--lib` is not claimed: unrelated NBT targets in this checkout reference absent
source files. Java fail-closed behavior was preserved by leaving its source
unchanged, not by claiming an unavailable live Java/Forge test passed.

## Why live snapshot coherence remains unresolved

A complete native payload may still represent stale or incoherently captured
Java state. Rust borrowing and the native registry lock do not freeze Java/mod
writers. Existing native generation checks cannot establish the absence of
unhooked biome/light mutations, arbitrary mod worker writes or ABA changes.
Refresh selection, full-width state extraction, section presence/refcounts,
filter/full-chunk semantics and lifecycle ownership remain separate concerns.

No frame-engine authority code, MCK6 semantics, compression backend, defaults,
production gate or historical evidence registry was modified. No historical
artifact was manufactured, relabeled or replaced. Issue #1 must remain open.

## Exact next step for packet-time capture

Specify and review a capture/publication protocol at the actual Java packet
construction/serialization boundary before implementing a live campaign. The
protocol must enumerate the supported writers of blocks, section presence and
refcounts, palettes, light, biomes and lifecycle, and establish enforceable
ownership or writer participation. Unknown/nonparticipating writers must be
excluded explicitly or cause rejection; an equal generation or endpoint hash
alone is not a synchronization protocol.

For one accepted packet event, copy all required state into owned immutable
storage under that protocol. Bind request filter/full-chunk/skylight flags,
full-width state IDs and registry width, section presence/refcounts, lights,
biomes, lifecycle identity, Java-emitted mask and bytes, and actual transformed
writer/class origin to that same capture. Prove the real Java writer consumed
the same state, using a controlled immutable view or a demonstrated mutation-free
interval. Preserve exact runtime/mod/config identities and raw evidence.

Only then compare the reference with Rust output and its combined result, using
an independent complete decoder. Reject unsupported widths before narrowing,
and ensure failed refresh/capture cannot leave an eligible stale snapshot.
Synthetic tests of the proposed protocol and the versioned JNI result can
precede availability of the exact Forge/modpack artifacts; live proof cannot.
Historical exact-writer identification and authority approval remain separate,
unfinished gates.
