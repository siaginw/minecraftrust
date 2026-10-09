# Issue #1: Clean Forge offline same-event contract

The qualified target is the actual transformed Minecraft 1.12.2 / Forge
14.23.5.2860 packet writer. The accepted scope is a **privately owned, detached
real Forge graph**, created in the offline oracle; it is not an arbitrary live
chunk copied optimistically. Production native packet authority remains false.
The initial registry-only probe was preliminary. The final full FML offline
profile binds the actual initialized registry and applicable Forge listeners.
Neither profile is evidence of a started dedicated server.
The accompanying [writer audit](issue1-clean-forge-writer-audit.md) gives the
runtime evidence and records the live publication gaps.

## Designs considered

| Design | What is provable in this stage |
| --- | --- |
| Owned graph consumed independently by actual Java packet writer and Rust snapshot encoder | A closed, no-escape graph with qualified constructors/readers permits a same-event argument. This is the chosen offline scope. |
| Owner-thread live capture followed by Java serialization | Insufficient alone: exposed arrays, async chunk I/O, callbacks and publication have no universal owner-thread admission in the audited methods. |
| Shared participating lease | Sound only if every relevant writer and lifecycle/publication operation participates. No audited Forge writer automatically participates in the foundation lease. |
| Endpoint hashes, matching generations or repeated reads | Useful rejection checks, not an exclusion or happens-before proof. ABA and uncoordinated writes remain possible. |

Copying arbitrary live state into a private graph does not solve the initial
capture race. That initial copy would require its own demonstrated coherent
publication/exclusion boundary. The current factory constructs the input under
exclusive ownership from validated values, so it does not make that inference.

## Exact eligibility predicate

`NATIVE_PACKET_AUTHORITY_ELIGIBLE_CLEAN_FORGE` is a **necessary contract for a
future authority decision**, not a switch enabled by this stage. It requires
all of the following; a missing or unknown conjunct is false:

1. Exact pinned Minecraft/Forge artifacts, remap data, transform chain, JVM,
   relevant class bytes, registry mapping and supported bridge/native contract.
   An unexpected coremod, listener, subclass, storage mode or registry mutation
   invalidates the qualification.
2. Canonical intended owner `Thread` object, not a name/id supplied by an
   untrusted caller. Off-thread capture rejects before graph access.
3. A live identity token binding dimension, coordinates, **Chunk object**,
   lifecycle incarnation and generation. The token is current at capture begin,
   capture end and publication. Unload invalidates it; reload, including a
   dormant same-object reload, gets a new incarnation. A same-coordinate new
   object can never inherit the previous object's permit.
4. A demonstrated closed writer inventory for section references, states,
   refcounts, block light, sky light, biomes, extended-ID/registry representation,
   lifecycle and TE effects. Every admitted writer is owner-thread-only under
   enforced no-escape ownership or participates in the exact exclusion boundary
   held through validation/publication. Unknown/async/observation-only writers
   fail closed.
5. Exact supported storage semantics; full-width logical IDs resolve to the
   qualified registry and fit the current native encoding limit before any
   narrowing. Unsupported NEID/JEID/high bits, malformed palettes and unresolved
   states reject rather than becoming air or a shorter mask.
6. Real constructor semantics: `fullChunk == (requestedFilter == 0xffff)`;
   filter is a 16-bit value; skylight is read from the admitted world provider.
   Selection is made from the captured section existence/refcount state during
   copying. Every accepted bit has exactly one owned section and every emitted
   section belongs to the returned mask.
7. A same-event Java reference relationship proven by ownership or a shared
   exclusion window. The Java reference is the actual `SPacketChunkData`
   constructor, not a hand-written imitation of its section loop.
8. TE tags remain Java-owned. The callback order is accounted for and all
   concrete callbacks are excluded, read-only under qualification, or participate
   in a supported revalidation scheme. Unexpected TE mutation invalidates the
   entire attempt; do not reuse its payload or repeat arbitrary callbacks
   silently.
9. Strict end checks establish unchanged identities, lifecycle, capabilities,
   exact packet-visible contents and selection. The checks support the
   exclusion proof; they do not replace it.
10. The future publication operation consumes the immutable accepted result,
    validates its identity token within the same supported boundary, and builds
    its header from the single Rust encode result. No independent live-mask
    query or a different generation supplies successful metadata.

The offline suite can satisfy an analogous **oracle eligibility** predicate by
excluding world/provider publication completely. It cannot satisfy the live
publication conjunct merely by returning a successful fixture. No new authority
routing is installed and `M4NativeStatePayload.tryEncode` remains fail-closed.

## Ownership / happens-before argument

Let `O` be the exact oracle owner thread, `G` the privately held real Forge Chunk
graph, and `E` its currently accepted logical packet state. The graph consists
of the Chunk, its section references, supported containers/palettes/packed
storage, light planes, biome bytes and the admitted provider context. Registry
states are used only after exact supported registry qualification.

1. `O` creates `G` through the controlled factory. Constructors capable of
   publishing `this` through Forge events must have their exact applicable
   capability listener inventory checked before construction. Unsupported
   listeners reject. Qualified lifecycle hooks may execute the exact Forge
   internal unload listener only while the audited global water-ticket map is
   empty; this path neither retains nor mutates the graph. No world/provider
   map, executor, event recipient or external caller retains a mutable graph
   reference. The extraction seam deliberately passes transient `View` objects
   containing graph identities and live arrays to the audited
   `SnapshotCapture` copier. Those references remain inside this closed call
   graph: the copier retains them only for the attempt, and the accepted
   `OwnedPacketSnapshot` copies values without retaining the source or views.
   The wrapper's returned `Pair` contains only that snapshot and copied bytes
   and tick-count values. This is call-graph confinement, not a claim that
   `readView()` could safely be exposed to arbitrary callers.
2. All graph population writes occur on `O` before capture. No operation callable
   by another thread is allowed to mutate `G`; off-thread calls reject. When
   capture runs, the closed owner-thread call graph invokes no ordinary wrapper
   mutator and admits no arbitrary callback that could invoke one reentrantly.
   There is no claim that a general capture-in-progress lock intercepts every
   setter. The only deliberate in-capture mutation calls are the enumerated
   adversarial phase faults; every such attempt must reject.
3. During the accepted capture interval, `O` performs only audited reads and
   calls the actual Java packet constructor. The default admitted TE map is
   empty, so this constructor cannot invoke a TE callback. Exact supported
   classes exclude adversarial getter/container overrides. Registry mutation,
   constructor escape and provider lifecycle operations are excluded by the
   closed scope.
4. Therefore no graph writer exists between the snapshot's first accepted read
   and Java's final body read. Intra-thread program order provides the
   happens-before relation between population, capture and Java encoding. The
   graph retains one logical value `E` throughout that interval. This is stronger
   than two endpoint copies that happen to compare equal.
5. The adapter makes owned copies of `E`, checks selection/counts/IDs and exact
   end state, and publishes only an immutable snapshot plus copied Java result
   bytes and one shared event/lifecycle identity. Neither result retains mutable
   `G` references. Later mutations to `G` cannot change either accepted value.
6. JNI transfers the owned serialized snapshot into Rust-owned storage. Rust
   encodes only that snapshot and returns bytes written plus emitted mask from
   one successful serialization. Independent decoding compares Java and Rust
   semantics against `E` and enforces exact payload consumption.

This proof relies on actual no-escape ownership and qualified read/callback
behavior. A caller-set enum, endpoint fingerprint or `synchronized` block that
unknown writers ignore cannot manufacture it. Class and source identities must
bind the factory and harness that enforce the assumptions.

## TileEntity order and rejection

The transformed Java packet constructor finishes its body and mask before
calling `TileEntity.getUpdateTag`. The base method is public and virtual; its
base implementation producing NBT does not establish that every subclass or
capability serializer is read-only. It can call arbitrary Java code through
overrides/capabilities in a different runtime.

The admitted offline subset rejects a nonempty TE map before invoking that
constructor. TE negative controls prove the rejection; they do not count as
accepted TE parity. A separate controlled constructor probe may demonstrate
that a mutating callback executes after body serialization, but such a probe
does not grant eligibility. Future TE support needs concrete callback
qualification and post-TE validation under the same writer/lifecycle boundary;
mutating or unknown callbacks reject. On failure, tags and body belong to the
same rejected attempt and are discarded together.

## Lifecycle and publication

The offline wrapper maintains the explicit identity/incarnation/generation
contract for its owned graph. Unload, reload and replacement tests exercise
rejection even at equal coordinates. These are real Forge graph objects with a
controlled lifecycle model, not a claim that a running `ChunkProviderServer`
has been instrumented. The wrapper's monotonically renewed tokens are distinct
from Forge coordinates and Rust handle-generation checks.

A fresh graph is admitted in a **new detached** state before Forge's loaded
flag is set; the wrapper's `active` flag means eligible for this owned oracle,
not present in a live provider. Explicit unload/reload controls invoke the real
Forge hooks on the empty entity/TE graph. A successful reload changes the
wrapper's incarnation and generation even when the Chunk object is reused.

An already accepted immutable snapshot for incarnation A remains encodable as
historical A data after replacement; encoding does not revoke immutable values.
The tested rule is that a request carrying A's token cannot capture replacement
B, including B at the same coordinates, and accepted output retains A's own
identity metadata. There is no current publication API which could authorize
attaching that old body to B. Preventing such a future use requires the explicit
publication-token validation below, not just a successful offline encode.

Live integration must add a lifecycle/publication primitive, including the
asynchronous load path identified in the writer audit. It must establish that
the Java state was safely published before capture and that the accepted event
still addresses the intended current incarnation when the packet is published.
This stage publishes no packets and starts no live SHADOW campaign.

## Scope of a successful classification

`CLEAN_FORGE_PACKET_CAPTURE_OFFLINE_VALIDATED`, if all recorded checks pass,
means the exact transformed writer has a defensible offline owned-graph
same-event contract and accepted fixtures semantically match the Rust result.
It does not mean live native authority is eligible, arbitrary mods are safe, or
the historical Java=63/native=31 event's writer has been identified.

The historical classifications remain `ROOT_CAUSE_CLASS_HARDENED` and
`HISTORICAL_EXACT_WRITER_UNRESOLVED`. Issue #1 remains open.
