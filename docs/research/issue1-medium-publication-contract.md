# Issue #1: bounded snapshot publication hardening

This stage preserves the accepted detached exclusive ownership architecture.
The real Forge adapter remains an offline oracle; no live Chunk admission or
production packet authority is added. The new contract is explicit:

```text
capture success -> complete immutable owned snapshot
capture failure -> no publishable snapshot or successful encode result
```

## Publication points

`SnapshotCapture.captureHeld` keeps extraction arrays and partially completed
owned sections local. Only after all selected sections, conditional planes,
end-view checks and TE validation succeed does its final return construct
`OwnedPacketSnapshot` and an eligible `CaptureContract.Result`. The owned
objects clone mutable arrays and expose copies. A thrown failure discards
temporary work. The Result constructor now enforces that eligibility and a
nonnull snapshot occur together; rejected `snapshot()` access throws.

The real oracle has a second boundary: `OwnedForgeCapture.capture` publishes a
`Pair` only after the complete owned capture and actual same-event Java packet
checks. Success requires snapshot, Java bytes and selected tick refcounts;
rejection requires all three absent. Its guarded `transportForEncoding()`
rejects before transport or JNI dispatch. This pairs the offline reference
operation, not a live network publication. Rust still accepts transport only
after complete parse/validation and returns the one existing
`PacketEncodeResult { bytes_written, emitted_mask }`; JNI V2 remains unchanged.

## Constructible noncanonical inputs

The oracle adds six admission controls using actual Forge objects:

| Case | Exact boundary |
| --- | --- |
| `malformed-state-properties` | An ordinary new block-state container omits a property required by the block's metadata accessor. Forge lookup throws; the new pre-setter catch records `FALLBACK_INVALID_STATE_INPUT` and poisons admission. No registry mutation or reflective impossible state is used. |
| `noncanonical-state` | A separately constructed state uses the real property definitions and resolves to an ID, but is not that registry ID's canonical object. It rejects before setters. |
| `unsupported-storage-subclass` | An ordinarily constructed ExtendedBlockStorage subclass violates exact supported-class admission. |
| `incompatible-registry-width` | A real canonical state exceeds the adapter's declared global width. Admission rejects before setters. |
| `invalid-palette-width` | Invalid declared storage width rejects without normalizing input. |
| `invalid-request-filter` | An invalid filter is rejected before an accepted capture can be published. |

Existing wrong-thread, lifecycle/replacement, unknown-writer, section and TE
controls remain required. Admission failures stay poisoned; a later ordinary
setter cannot silently clear a prior rejection.

## Partial failures and source preservation

Two rewrite controls use a normal throwing List and count 17 completed real
EBS setters before failure. A temporary-section rewrite never installs its
partially populated section, leaving the existing detached graph unchanged.
The in-place variant intentionally changes a separate disposable detached
graph, then poisons that source so it cannot be captured or reused. It is not
an assertion that in-place writes roll back. No existing registered world or
live graph is touched by either control.

Eight transient extraction controls fail after section metadata, during logical
conversion (after 17 copied IDs), after logical clone, after block-light clone,
after sky-light clone, after biome clone, during the end-view read, and at the
wide-ID decision. The last inserts 65536 into conversion-only scratch before
the existing width check. Actual EBS state is unchanged. Light/biome tests fail
after a completed clone; they do not claim to interrupt a JVM arraycopy.

Each of these 16 new rejection events checks absent snapshot/Java packet,
denied transport access, and zero failure-path JNI calls. A private witness
compares graph identities and packet-visible contents; only content digests
are exported, never mutable references. The sacrificial in-place case alone
expects a changed witness. Eight transient controls retry to a complete Java
reference pair and a successful native JNI result after removing the fault;
poisoned rewrite sources continue rejecting. These retry assertions are not
additional independently compared or replayed accepted fixtures.

## Evidence and generated tests

Accepted inputs use `REAL_CLEAN_FORGE_ORACLE_ACCEPTED`. Rejections use
`REAL_CLEAN_FORGE_EXPECTED_REJECTION` and the separate strict
`chunk-capture-rejection-v1.schema.json`. All records bind their canonical hash
and runtime/source artifacts. The new 16 records additionally require exact
stage, progress, retry outcome, graph witness and publication proof. The older
20 rejection controls retain their original semantics and null new proof:
some test rejection of an encode of an already valid input, so their evidence
must not be relabeled as capture failure before any input existed.

Three new Rust properties generate incomplete transport progress, late width
failures and lifecycle replacement/history sequences. Two deterministic tests
exercise every copy boundary and local/global width edges. Rejection is tested
through the public parse-then-encode chain with an unchanged output sentinel;
valid complete retries still encode. These are transport-model tests, not
evidence of constructible Forge concurrency. No unmutated counterexample was
found; any future minimized failure must become a deterministic regression.

The bounded Rust mutation follow-up targets existing lifecycle, width, exact
completion and reader-bound predicates newly exercised by those tests. The
separate Java fault audit removes only selected publication/admission/poison
guards in disposable copies. Neither mutation score establishes live ownership.

## Remaining boundary

Exact equality and private witnesses are diagnostics under established
detached ownership, not synchronization for arbitrary world writers. Live
provider publication and complete writer exclusion remain unproven. Nonempty
TE callbacks and modpack runtimes remain outside this contract. See the
[unexecuted live plan](issue1-live-shadow-gate-plan.md). Production
`M4NativeStatePayload.tryEncode` remains unconditional null; Issue #1 stays open.
The historical classification remains `ROOT_CAUSE_CLASS_HARDENED` /
`HISTORICAL_EXACT_WRITER_UNRESOLVED`.
