# Issue #1: offline coherent-capture foundation

This stage implements a **SYNTHETIC_OFFLINE** owned Java snapshot, explicit writer
capabilities, acceptance checks and an additive snapshot-to-Rust JNI path. There
is no Minecraft/Forge capture adapter, production call site or publication
permit. `M4NativeStatePayload.tryEncode` remains fail-closed. Issue #1 remains
open; MCK6, compression, defaults and historical evidence remain unchanged.

Classification remains `ROOT_CAUSE_CLASS_HARDENED` and
`HISTORICAL_EXACT_WRITER_UNRESOLVED`. The synthetic historical structural shape
does not identify the historical Java=63/native=31 writer.

## Owned snapshot and single-event selection

`tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java` is schema
version 1. It records dimension, chunk coordinates, independent incarnation and
generation identities, requested filter, full-chunk/skylight flags, accepted
mask, per-section logical IDs, lights, refcounts/empty state, biome bytes,
storage model, runtime global-palette width, capture event/thread/context,
start/end mutation guards, writer inventory, provenance and integrity method.
TileEntity callback policy and completed revalidation are also recorded.

Logical IDs are Java `long` values and the binary schema carries unsigned
32-bit IDs. Current native support is still u16: validation rejects unsupported
width **before narrowing**. Capture owns every accepted array; all array getters
return defensive copies. An accepted snapshot retains no live chunk, section,
storage identity token, source view, thread object or mutable source array.
Its section objects and writer map are immutable.

Selection occurs within the capture operation from the requested filter and
captured section presence/isEmpty predicate. Full packets omit present-empty
sections; partial packets can select present-empty sections. An absent requested
section is normally omitted by this selection predicate; a section which was
selected and disappears during capture rejects. Every accepted bit has an owned
section and every unselected bit has no owned section. Native encoding serializes
only accepted bits and returns its actual emitted mask alongside bytes written.
A future packet header must consume that emitted mask, never a post-encode Java
rescan or retained-cache getter.

## Capture phases and acceptance

`CaptureSource` is a dependency-free extraction seam. Its only provided adapter
is `SyntheticCaptureSource`, whose mutable arrays and phase hooks support
deterministic tests. The phase hooks are fault-injection seams, not runtime
callbacks. No mod or backing-array writer is automatically intercepted.

The bounded algorithm is:

1. Check canonical `Thread` object identity and complete writer capabilities
   before reading state. If any domain needs participation, attempt the shared
   participation lease without waiting; a busy lease falls back. Retain an
   acquired lease through all capture/TE validation.
2. Read initial chunk identity, incarnation, generation and request metadata;
   validate storage, dimensions and full-width logical states/refcounts. Reject a
   stale initial incarnation/generation relative to the caller's expected pair.
3. Make a complete audit copy of the initial logical states, light planes,
   extended-ID planes and biomes. Retain initial reference identities only in
   this transient local audit state. This extra copy deliberately prioritizes
   observable correctness over unmeasured performance.
4. `CAPTURE_BEGIN -> IDENTITY -> SECTION_STRUCTURE -> SELECTION` derives the
   candidate mask from this event. Copy selected logical states, then block
   light, sky light, biomes and extended-ID state. Phase hooks run after each
   named step and can deterministically mutate the synthetic source.
5. Compare the copied output against the initial audit bytes. At `CAPTURE_END`,
   re-read and validate chunk/storage/section/array identities, coordinates,
   generation/incarnation, filter/flags, global-palette width, provenance,
   mutation epoch, refcounts/empty state and **exact complete array contents**.
   Changes reject with an explicit fallback result. No content hash substitutes
   for equality.
6. If a qualified synthetic TE-effects callback is requested, run it while the
   lease remains held and repeat complete validation. Any observed packet-state,
   identity or epoch change rejects as `FALLBACK_TE_MUTATION`.
7. Construct the immutable accepted snapshot, then release the participation
   lease. Later live changes cannot mutate the owned result. Rejection exposes
   no snapshot and cannot reuse an older accepted result implicitly.

The trustworthy part is **qualified exclusion/participation for a complete
writer inventory**, plus exact acceptance predicates. Method boundaries, equal
epochs, matching references and endpoint equality alone are not synchronization.
An unobserved ABA change may restore equal endpoints. Mutation epochs help only
when all relevant writers participate and their monotonic behavior is defined.
The implementation does not claim universal ABA detection or a mathematical
proof for arbitrary Java data races.

## ServerThread and writer classification

Canonical ServerThread identity proves ordering against ordinary owner-thread
tick/game logic in a correctly qualified environment. A same-named worker is
not the same thread. Off-thread capture returns `FALLBACK_OFF_THREAD`.

ServerThread execution does **not** exclude arbitrary asynchronous mod workers,
direct unsynchronized array writes, unknown coremods or mutating callbacks
invoked reentrantly during capture. Those require their own supported contract.

All nine domains are mandatory, including domains absent from this particular
wire body; this conservative foundation does not infer writer safety from a
skylight/full-chunk flag:

| Domain | Packet-visible state |
| --- | --- |
| `SECTION_REFERENCES` | Section existence and object/array replacement |
| `BLOCK_STATES` | Full logical block-state IDs and representation |
| `EMPTY_REFCOUNTS` | `isEmpty`/block count used for section selection |
| `BLOCK_LIGHT` | Block-light nibble plane |
| `SKY_LIGHT` | Sky-light nibble plane |
| `BIOMES` | Biome plane and representation |
| `EXTENDED_IDS` | High ID planes or alternate logical storage |
| `LIFECYCLE` | Unload/reload, incarnation, generation and chunk identity |
| `TILE_ENTITY_EFFECTS` | Callback changes to any packet-visible domain |

For each domain:

| Writer class | Supported result |
| --- | --- |
| `OWNER_THREAD_ONLY` | Acceptable only with a complete qualified inventory and canonical owner-thread execution. |
| `WRITER_PARTICIPATING` | Requires the same lease for capture and every declared writer, covering this domain for the entire interval. |
| `DIRECT_BUT_OBSERVABLE` | Reject: endpoint observations/hashes/epochs alone do not exclude races. |
| `ASYNC_UNCOORDINATED` | Reject: no supported exclusion mechanism. |
| `UNKNOWN` or missing inventory entry | Reject before reading/copying. |

Capability declarations are qualification inputs, not facts inferred from a
boolean or an enum. Declaring an unknown writer owner-only does not make it safe.
The provided qualification scope is the enumerated synthetic adapter only; a
future live adapter needs an independently reviewed inventory and enforced
mechanisms for its exact runtime/mod transformations.

## Explicit eligibility/fallback result

`CaptureContract.Result` reports a reason, optional failing domain and detail.
Only `ELIGIBLE` exposes `snapshot()`. **Every result returns false from
`productionAuthorityEligible()`**, including accepted synthetic snapshots.

Fallback reasons distinguish off-thread execution, incomplete/unknown writers,
async writers, observation-only writers, missing participation, unsupported
scope/storage, extended IDs, chunk replacement, capture changes, missing section,
invalid input, TE mutation and source exceptions. They are suitable for future
metrics, but this stage adds no production metrics or authority routing.

**Can arbitrary unsynchronized legacy Forge mods which mutate backing arrays
from background threads be universally proven safe without writer participation?**
**No.** Endpoint copying/comparison cannot establish that exclusion. Such
environments remain ineligible unless another specifically qualified correctness
mechanism exists; none is implemented here. Silent acceptance based only on
matching hashes or epochs is forbidden.

## TileEntity boundary

No live TE callbacks or tag serialization are migrated. The future shell may
retain Java-authored TE tags with a Rust-authored chunk payload, but must establish
that tag production and chunk payload are compatible with one publication event.

The synthetic callback seam defaults to `UNQUALIFIED` and rejects before invoking
the callback. A qualified policy must explicitly be `QUALIFIED_READ_ONLY` or
`PARTICIPATING_MUTATION_EPOCH`. The latter requires every packet-visible callback
mutation, including ABA, to advance the supporting epoch; unknown callback
behavior cannot be blessed by ServerThread identity alone. Even qualified
callbacks are followed by exact full revalidation. Mutating callbacks must not
publish a stale pre-callback payload. Tags/output from a rejected attempt must be
discarded together, with Java fallback preserving the established callback
ordering; do not silently repeat arbitrary side-effecting callbacks.

For actual Forge integration, choose and qualify the callback ordering before
implementation. A snapshot accepted without a callback is not a proof that a
later Java callback leaves it publishable. Lifecycle/publication validity after
capture is also a separate future gate from owned-byte immutability.

## Extended IDs and native input transport

`VANILLA_U16` accepts only resolved nonnegative IDs <= 65535. `NEID_HIGH_BYTES`
requires a complete high-byte plane and rejects every nonzero high byte before
encoding. `JEID_INT` and `UNKNOWN` currently return unsupported-storage fallback;
the wide schema preserves room for later support without pretending current
native sections can hold them. No truncation, wrapping, mapping to air or mask
bit removal is used to conceal a width failure.

`OwnedPacketSnapshot.toTransportBytes()` writes the additive `RCSNAP01` input.
The fixed 128-byte big-endian header carries schema, flags, storage/scope,
global-palette width, coordinates, generation, requested/accepted masks, event
identity, owner/capture thread IDs, equal epoch guards, equal incarnation guards
and a 32-byte provenance identity digest. Incarnation and generation are separate
positive identities. The digest binds the declared provenance/inventory/context;
it is not a signature or an alternative to acceptance checks.

After the header: section count, ascending selected records (Y, reserved byte,
non-air count, 4096 u32 IDs, 2048 block-light bytes, conditional 2048 sky-light
bytes), and a conditional 256-byte full-chunk biome tail. No trailing bytes are
permitted. Accepted Java snapshot metadata remains richer than this compact
encoder input; copying a binary header cannot manufacture a live authority
permit. `OwnedSnapshotBridge.encodeOwnedV1` takes owned input/output allocations
and returns the existing V2 count/mask representation in one operation.

## Validation and next actual Forge stage

`SnapshotCaptureTest` is a Java 8 standalone synthetic suite. It exercises sparse
and all masks, partial present-empty selection, transitions, defensive ownership,
phase-by-phase mutations, all writer classes/domains, refcounts, reference/array
replacement, lights, biomes, lifecycle, storage, width rejection, canonical-thread
identity, a bounded participating writer, and TE mutation/epoch behavior.
`OwnedSnapshotJniTest` loads the actual release DLL and sends accepted owned
snapshots through the additive JNI method, checking masks/counts, flag combinations,
capacity failure/retry, post-capture source mutation and malformed transport.
Neither suite requires or claims Forge/modpack coverage. Strict independent
decoding and synthetic fixture replay are separate runner lanes.

The exact next live-capture step is to map these domains and phases onto the
actual pinned Forge packet writer and installed transformations: identify the
canonical owner, enumerate/exclude every writer, enforce leases where required,
extract full-width logical IDs without lossy staging, preserve actual selection
and TE callback order, and establish that the Java reference writer consumes the
same accepted owned state. Review lifecycle/publication eligibility, rejection
cleanup and buffer lifetime before implementing the live adapter. Then follow
the oracle -> short live SHADOW -> modpack SHADOW ladder. Production authority
requires a later, separate authorization and validation gate.
