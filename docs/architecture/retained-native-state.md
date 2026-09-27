# Retained native state foundation (H6)

Status: **H6_FOUNDATION — serialized model and layout prototype only**.
`native-state-vnext::production_authority_enabled()` is always false. This crate
does not connect to Forge, JNI, a real server, or the legacy chunk encoder. It
does not change profiles, defaults, packet gates, FFI, frame authority, or MCK6.
The original Issue #1 remains open; this does not identify its historical writer.

## Ownership and publication

`RetainedStore` owns the H4 `ModelRegistry`. H4 is the sole modeled mutable-owner
ledger; H6 adds retained data and lifecycle phases around its leases. The public
API is serialized by `&mut RetainedStore`. It is not a JVM safepoint protocol or
a proof about simultaneous threads. No raw pointers or unsafe code are used.

| Phase | Existing writer | New writer | Next successful action |
| --- | --- | --- | --- |
| `JavaOwned` | At most one Java lease | Java only | Begin native handoff |
| `Quiescing` | Already issued Java lease may finish | None | Drain, then stage immutable candidate |
| `Transferring` | None | None | Publish native owner and candidate |
| `RustOwned` | At most one Rust lease | Rust only | Mutate, derive views, or begin Java materialization |
| `MaterializingJava` | Already issued Rust lease may finish | None | Drain, stage, publish Java owner and candidate |
| `Revoked` | Cannot mutate; may acknowledge drainage | None | Destroy after drainage |
| `Retired` | Cannot mutate; may acknowledge drainage | None | Destroy without reusing an exhausted slot |

Handles include an origin-store nonce, session, world, incarnation, slot, slot
generation, and ownership generation. Private non-cloneable write and handoff
tickets prevent fabricating completion or crossing stores. Starting a handoff
increments ownership generation and invalidates caches immediately. Existing
writers are identified by lifecycle plus their private nonce, so they can drain
after this increment. Dropping a writer ticket does not silently release it;
the node remains busy and cannot publish or be destroyed.

Staging requires a drained writer and copies one coherent immutable candidate.
Within the serialized model, publication checks the candidate, releases the
previous H4 owner, claims the next owner, and publishes data without another API
operation interleaving. Real atomicity across the JVM/native boundary is absent.

| Failure point | Result |
| --- | --- |
| Invalid publication before staging | Error; consumed ticket is cleared and original safe owner restored |
| Before either owner switch | Typed `RolledBack`; previous owner/data preserved |
| Native claim rejects scope, dependencies, or observation | Java restored when possible; otherwise typed `Revoked` |
| After switching Java to native | Native lease ended; Java/data restored; failed native capability generation remains revoked |
| After switching native to Java | Old native generation cannot be recreated; Java released and typed `Revoked` with no mutable owner |

Every `PublicationOutcome` reports `production_authority() == false`. Re-entering
native ownership after materialization requires a newly installed capability
generation. Capability revocation propagates through H4 dependencies; queued
work and existing writer mutation then refuse. Destruction requires drainage,
invalidates the H4 resource, releases the modeled owner, cancels its jobs, and
increments the arena slot generation. Generation/nonce exhaustion retires the
node before mutation or ticket issuance and burns the slot instead of wrapping.

## Retained data and identity

H5 `rustcraft-core` supplies separate semantic keys, `DenseRuntimeStateId(u32)`,
wire IDs, persistence IDs, registry epochs, and signed world bounds. A local
palette index uses `u16` because a section has at most 4096 cells; that is not a
universal state ID or protocol width. Tests retain registry IDs 70000 and 70001;
the standalone section representation also preserves `u32::MAX`.

`NativeChunk` holds sparse sections in a sorted map bounded by explicit
`WorldBounds`. It has no 16-section world assumption or legacy packet bit mask.
Light and biome vectors are opaque model payloads, not a claim to implement
version-specific lighting/biome dimensions or layouts. State writes and initial
insertion validate dense IDs against an immutable registry table. Within a
store, one registry epoch cannot bind a different table even after destruction.
Epoch invalidation leaves a tombstone, including for an epoch not yet inserted.
Old read-only views retain their old table for semantic interpretation.

The current copy-on-write granularity is the complete `SnapshotData`, including
the chunk and its section allocations. It favors correctness and simple proofs;
this is not a low-copy or memory-efficiency claim.

## Immutable views and derived artifacts

Packet, lighting, persistence, query, and worldgen-dependency views expose only
immutable data through `Arc`. Each tag contains the full handle, ownership
generation, registry epoch, state/light/biome generations, view kind, and explicit
protocol/version, dimension-rule, and recipient-rule tokens. Snapshot data and
its tag are produced by the same operation after checking the current owner and
that no writer is active.

Semantic writes clear cached artifacts and increment their corresponding
generation; no-ops do neither. A queued ticket contains its immutable view plus
an H4 validation ticket. Publishing consumes the pending job only after matching
its store and tag, then revalidates current lifecycle, all generations, owner,
capability, observation, and dependency closure. Foreign results cannot consume
a colliding local job number. Cancellation likewise takes a private `WorkId`
containing origin store and a never-reused store nonce, whose pending entry binds
the full lifecycle/view tag. A bare numeric nonce is not accepted. The root
review reproduced foreign cancellation removing local work before this fix;
the regression now checks both stores can still publish their own results.
Cancelled, stale, revoked, and unloaded results
cannot publish. Immutable old snapshots may stay readable but cannot commit.

The cache stores opaque byte artifacts. It does not generate or verify packet
bodies, lighting, persistence, or worldgen results. Distinct recipient, protocol,
dimension, and view-kind keys are tested. This is part of the H8.1 key/invalidation
foundation, not its many-player/high-view-distance or retained-memory benchmark.
There is no eviction budget yet. Registry tombstones/tables and abandoned leases
are also conservative retained state; bounded lifecycle cleanup remains future
work. The serialized model makes no concurrency or Loom claim (H7).

## Section representations and measured probe

Policies are explicit; there is no production default. `UNIFORM` stores one u32.
`LOCAL_PALETTE` stores dense IDs, 4096 local u16 indices, counts, and an optional
hash lookup. Small palettes use linear lookup; larger ones use a hash table.
Canonical palette output follows first spatial occurrence, so allocation and
hash iteration order cannot affect emitted local indices. Unused historical
entries are compacted. `DENSE/HOT` stores 4096 u32 IDs.

Promotion occurs at an explicit unique-count threshold or write-count threshold
within a maintenance window. Demotion needs a strictly lower unique threshold
and repeated cold windows. A hot window resets the cold streak. Tests compare
120000 seeded mutations/maintenance operations against a separate dense vector,
exercise compaction, and verify both cardinality and write-heat hysteresis.

`examples/layout_probe.rs` measures 42 combinations: promotion 64/128/512,
linear-lookup threshold 8/32, and cardinality 1/4/16/64/128/512/2048. Each has one
discarded warm-up and five samples of 200000 reads, 50000 writes, and 32 canonical
local-index materializations. Baseline and prototype use identical prepared
operations, alternate measurement order, use `black_box`, and compare exact final
states/checksums. Three separate hot thresholds (64/512/2048) time promotion and
cold-sweep demotion. Pure lookup cases disable heat promotion explicitly.

The independent baseline is a u32 `Vec`, **not the current production dense
chunk implementation**. Canonical local-index materialization is **not wire
bit-packing or packet encoding**. Memory fields count vector payload only and
exclude allocator metadata, hash buckets/control bytes, and resident memory.
The probe does not measure real packets, compression, JNI, Java aliases, cache
retention/invalidation throughput, players, server latency, or end-to-end cost.
Its timing samples support comparison of this prototype on this host only;
they do not select universally optimal thresholds or justify production defaults.

## Qualification gap

`CapabilityBinding` explicitly associates an H4 modeled capability/observation
with an H5 registry epoch. It is not a trusted certificate issuer or a verifier
that an opaque H4 registry token represents that actual live table. H4 receiver,
classloader, state restrictions, and writer/lifecycle evidence are model inputs;
H6 validates H4 observations but does not instrument live Java objects, tile
entities, state-set changes, or an external registry. The ownership capability
also does not qualify the real algorithm for each of the five view consumers.

Before runtime integration, H23 must establish operation-specific issuer and
certificate bindings, exact loaded-class/loader/object identities, table epoch
instrumentation, complete writer/alias closure, real quiescence/drainage and
publication barriers, compatibility-object identity/materialization, bounded
retention, destruction/unload ordering, and independent output validation.
Actual packet/light/persistence algorithms and their qualification must be wired
separately. Neither a bare digest nor this store can grant production authority.

## Reproduction

From the isolated checkout, run:

```powershell
python crates/native-state-vnext/verify.py --cargo C:/Users/Admin/.cargo/bin/cargo.exe
```

The runner uses a new target directory, binds source and tool hashes before and
after, checks the isolation/production gates, demands exact test result counts,
runs strict crate clippy and scoped formatting, builds/runs the release probe,
and independently verifies every probe checksum and numeric result shape. H6
is a root workspace member so its tests are discoverable by workspace tooling;
the production bridge has no dependency on it. Scoped validation is 38 tests:
four counter-retirement unit tests, seven layout tests and 27 lifecycle tests.
