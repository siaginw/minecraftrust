# Issue #1: live SHADOW gate plan — not executable yet

Prepared after preserving the accepted Clean Forge offline stage at
`735a2dab9ecaba6acbc7b88cf182f9125a312aa3`. **The live portion is
REQUIRES_ULTRA.** No live capture adapter, server run, SHADOW campaign, packet
transmission, or synchronization protocol is implemented by this plan.
Bounded offline hardening remains safe for Medium under the existing detached
exclusive ownership contract.

The accepted [offline report](issue1-clean-forge-offline-report.md),
[capture proof](issue1-clean-forge-capture-proof.md), and
[transformed writer audit](issue1-clean-forge-writer-audit.md) remain the basis.
Their classification does not establish ownership of a loaded live Chunk.
`M4NativeStatePayload.tryEncode` remains unconditional Java fallback, Issue #1
stays open, and native packet authority remains disabled.

## Intended flow and current blocking gate

The eventual diagnostic flow would be:

```text
actual live Java Chunk
  -> runtime / thread / lifecycle / complete-writer eligibility
  -> complete owned capture within a proven same-event boundary
  -> Rust owned-snapshot encode, one V2 {bytes_written, emitted_mask}
  -> independent comparison with that constructor's actual Java packet
  -> record result; Java packet alone continues to the network
```

The eligibility arrow is currently blocked. A live source must not be labeled
`REAL_CLEAN_FORGE_ORACLE` merely to enter the offline API. That scope describes
the detached factory and is not a capability or live publication permit.
Constructing an owned copy from a live graph would itself require the missing
capture boundary. A successful immutable copy does not prove that its source
reads were coherent.

## Candidate insertion points in current source

The existing packet hook is ASM, not a Mixin. These are inspection points for a
future separately reviewed integration, not authorization to activate it:

| Point | Exact current source | Meaning and limitation |
| --- | --- | --- |
| Constructor entry | `tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java`, `transformConstructor`, inserts `NativeChunkPacket.populatePacket(this, chunk, filter)` immediately after `Object.<init>` | This is before the actual Java size/section/body reads. A future eligibility/capture attempt must begin no later than those reads. The current hook can return early through older native-authority branches; the proposed Java-only gate must never take such a branch. |
| Current entry observer | `tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java`, `populatePacket`, calls `M4PacketCompare.onPacketConstructing` | The existing observer stores version/state observations. It does not acquire a complete Java writer/lifecycle boundary or produce the new owned capture contract. |
| Constructor completion | `SPacketChunkDataTransformer.transformConstructor` inserts `M4PacketCompare.onPacketBuilt(this, chunk, filter)` before constructor `RETURN` when the old comparator property is SHADOW | This is after the actual body/mask and TE processing. The candidate completion point can read the existing Java packet; constructing another reference packet would repeat TE callbacks and is not equivalent. Exceptional exits must yield no accepted attempt. |
| Current completion observer | `tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java`, `onPacketBuilt`, `processDeferred`, `compareCaptured` | These are legacy observation/refresh paths. They can read a later live graph or defer work to the flusher; they do not establish the detached proof for a live chunk. |

The actual transformed Java constructor writes the body and stores its writer's
returned mask before iterating the TE map and invoking virtual `getUpdateTag`.
The exact method/bytecode evidence is in the writer audit. Observing only
`writePacketData` or Netty output later cannot retroactively establish the state
that the constructor read.

The legacy comparator also calls length-only `NativeChunkBridge.encodePacket`
and independently calls `getPrimaryBitMask`. The accepted
[JNI call-site audit](issue1-jni-v2-call-site-audit.md) already classifies these
pairings as unsuitable for future authority. A future owned comparison must use
the one V2 result from the owned input, never that later mask query. This plan
does not migrate or enable the legacy comparator.

The qualified offline runtime intentionally excludes `RustCraftCoreMod`.
Loading the production bridge would add its transformers and change relevant
class definitions. Its actual final JVM definitions, hook positions, listener
inventory, classpath, registry and configuration therefore require a separate
qualification. The present offline pins must not be weakened to admit them.

## Required eligibility evidence

| Domain | Requirement before a live attempt can be admitted |
| --- | --- |
| Capture thread | The canonical server owner `Thread` object must come from a verified server lifecycle context. A thread named `ServerThread`, the current caller, or a numeric ID supplied by a caller is insufficient. The constructor must execute on that exact owner. Any background comparison may consume only already accepted owned bytes, never reread the live Chunk. |
| Lifecycle identity | Bind world/dimension, coordinates, the exact Chunk object, and renewed incarnation/generation. Prove safe provider-to-owner publication before the first read. Unload invalidates the attempt; same-object reload and same-coordinate replacement renew identity. Validate the current identity through completion of the Java constructor and publication of the diagnostic result. Native registry generation alone is not this Java lifecycle proof. |
| Writer participation | Enumerate every admitted writer to section references, state containers/palettes, refcounts, block light, sky light, biomes, registry representation, lifecycle and TE effects. Each must be confined by demonstrated ownership or participate in the same enforceable exclusion boundary. No audited live Forge writer currently participates in the foundation lease automatically. |
| Selection/storage | Preserve actual `fullChunk == (filter == 0xffff)` and the admitted provider's skylight flag. Validate exact supported classes, all selected sections, logical IDs before narrowing, registry resolution, refcounts and complete copied planes. Unknown storage, high bits or malformed state reject. |
| Biomes/lights | Account for both setters and direct writes through exposed arrays, plane replacement, generator/load paths and nominal biome lookups that lazily write. Matching endpoint arrays, a refresh lock or a dirty bit is not evidence that all such writers were excluded. |
| TileEntities | Initially require an empty TE map before Java construction and throughout the admitted interval. Unknown/nonempty TE graphs are rejected for native diagnostics while ordinary Java packet construction continues. Do not invoke callbacks twice, substitute native tags or infer read-only behavior from a getter name. |
| Result completion | Only a fully validated immutable owned snapshot plus the copied actual Java result may leave the capture boundary. Rejected attempts expose no transport/payload to an encoder or diagnostic queue. The Java packet must remain untouched. |

An immutable historical snapshot for incarnation A can remain encodable after
replacement. It must retain A's event identity; it cannot be paired with B's
packet or represented as evidence about B. This diagnostic association is
separate from the still-disabled future native network-publication authority.

## Existing evidence that blocks live admission

These are limits of the current proof, not new claims that Forge has a
reproduced concurrency defect:

* Actual transformed `ChunkIOExecutor.queueChunkLoad` submits
  `ChunkIOProvider.run` to workers. The worker invokes
  `AnvilChunkLoader.loadChunk__Async`, constructs state/light/biome data, writes
  ordinary completion fields, and releases its monitor. `syncChunkLoad` waits
  under that monitor; the audited `tick -> runFinished -> syncCallback` path
  does not establish that same acquire edge. No alternative publication edge
  has yet been proven for live capture. `syncCallback` also publishes through
  provider insertion, events, load/populate calls and callbacks.
* Actual Chunk/EBS/container/NibbleArray methods expose mutable section arrays,
  packed storage and light planes. `Chunk.func_76605_m` exposes biome bytes;
  `func_177411_a` can write a missing biome value during lookup. The detached
  factory closes their caller inventory; a loaded world graph has no equivalent
  demonstrated no-escape inventory.
* `ChunkMutationTransformer` instruments selected Chunk method returns.
  `ChunkMutationTracker` synchronizes the bookkeeping array after the actual
  writes; this does not make those writes participate in a capture lease.
  Direct array/container writes bypass these selected return hooks. In
  particular, `onStorageReplaced` sets a refresh flag, and `versionOf` reads the
  ordinary version array slot without providing universal writer coverage.
* `M4Coherency.refreshChunkNow` can serialize refresh callers with its own lock.
  Its flusher and sync workers also read live graphs. That refresh lock is not
  acquired by all Forge/world writers. The legacy comparator's biome/light
  repush and endpoint checks remain observations, not a same-event proof.
* A constructor's virtual TE callbacks may reenter arbitrary code. The accepted
  offline subset avoids these callbacks through an empty map; it proves no
  nonempty live TE callback policy.

These findings require **REQUIRES_ULTRA** for the live portion. Medium stops at
the evidence and requirements. This document deliberately selects no new lock,
seqlock, snapshot-transfer, queue-publication or provider-instrumentation design.

## Diagnostic records and fallback counters for a future gate

These are required future records, not counters claimed to be implemented by
this stage. Every sampled constructor attempt needs one terminal outcome and a
shared event ID, original request, runtime/source identity and lifecycle token.

| Outcome category | Required recording |
| --- | --- |
| Seen / eligible / complete | Separate constructor attempts, admitted attempts and completely published owned snapshots. An empty sample or all-ineligible run is INCOMPLETE, never a parity PASS. |
| Admission fallback | Count `FALLBACK_OFF_THREAD`, `FALLBACK_UNKNOWN_WRITER`, `FALLBACK_ASYNC_WRITER`, `FALLBACK_OBSERVATION_ONLY`, `FALLBACK_MISSING_PARTICIPATION`, unsupported scope/storage, invalid input/width and unqualified TE conditions separately. Runtime mismatch and unproven publication need explicit distinct rejection labels; no label creates eligibility. |
| Capture rejection | Count replaced incarnation, missing section, changed input, incomplete conversion/source exception and failed Java-reference validation. Rejected rows contain a reason and provenance but no successful snapshot, V2 result or payload. |
| Encode rejection | Preserve the exact V2 error; discard scratch output. Never present shorter output as success or supplement it with an independent mask. |
| Comparison | Count independent semantic comparisons, semantic matches and mismatches; exact byte equality is a separate diagnostic because palette histories can differ. Compare all 4,096 IDs per selected section, lights, conditional biomes, section coverage and exact byte consumption. |
| Authority invariant | Assert Java is the sole transmitted packet and native-authored packet count remains zero. The fail-closed production entry, MCK6/frame authority, compression and defaults remain unchanged. |

If later approved, only accepted owned input and the copied Java packet may be
queued for independent decoding. The queue must never contain a live Chunk,
section, array, callback or a promise to refresh later. Resource limits and
sampling limits must be fixed in that later gate's reviewed run manifest; this
plan is not an executable run manifest.

## Stop conditions and next task

Do not start until Ultra has qualified the missing live ownership/publication
boundary and a separately authorized run has pinned the actual hooked runtime.
After that future approval, stop the diagnostic gate on the first unexplained
semantic mismatch, malformed successful V2 result, accepted incomplete builder,
invalid lifecycle association, unexpected writer/listener/transformed class,
unexpected TE callback, missing required evidence, or any native packet
transmission. Abort/error/resource-limited runs cannot become PASS by omitting
their failed events. Ordinary known ineligibility remains a counted Java-only
fallback; it must not be weakened to increase acceptance.

The next live task is an **Ultra evidence and architecture review**, not a server
launch: prove or reject safe provider-to-owner transfer and complete writer
exclusion for a narrowly identified loaded Chunk, then define how its event and
incarnation remain bound through the Java constructor and diagnostic publication.
If that cannot be proven, live capture stays ineligible. Medium can continue
noncanonical-input, partial-failure and publication regressions inside the
already approved detached model without resolving that new concurrency problem.
