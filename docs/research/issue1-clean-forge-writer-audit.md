# Issue #1: transformed Clean Forge writer audit

This audit concerns the authorized Minecraft 1.12.2 / Forge 14.23.5.2860
artifacts and the offline LaunchWrapper qualification used by this stage. It
does not qualify a running server, arbitrary mods, Revelation or SevTech.
Production native packet authority remains fail-closed; Issue #1 remains open.

## Evidence and its limits

The initial profile `FORGE_2860_SERVER_TRANSFORMED_REGISTRY_ONLY_V1` was a
preliminary real-transform/vanilla-bootstrap probe. The final qualification
profile is `FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`, which
uses the official FML server load/initialization entry points offline. It must
bind the actual mod list, registry, added transforms and event listeners from
that lifecycle. It does not execute server main, create a server world, tick a
world, open a network listener or perform a provider load. It is not a started
dedicated server.

The evidence is the **post-transform class bytes**, disassembled with the pinned
Java 8 `javap -p -c`, rather than an MCP source checkout. Method locations below
are bytecode indices (BCIs), which remain meaningful only for the stated class
hash. The stage receipts retain the transformed classes and disassemblies
locally; proprietary binaries and disassembly bodies are not committed.

The audit receipts are under
`D:\rustcraft-bootstrap\issue1-forge-20260924T223951Z\writer-audit` and the
definitively observed classes under `runtimefull4\transformed` in that stage
root. A passive JVM instrumentation observer records `classfileBuffer` at class
definition and returns null; it does not transform, redefine or retransform
classes. This observes the bytes after LaunchWrapper's whole transform chain,
including `ModAPITransformer` registered during the official FML lifecycle.
It avoids assuming an earlier LaunchWrapper observer remains the final
transformer after initialization.
`reviewed-class-identities.json` binds each disassembly to the class SHA-256.
The reproducible runtime qualification records the complete transform chain,
artifacts, mappings, JVM, bridge/harness and DLL identities separately.
The completed `runtimefull4/qualification.json` preserves the same hashes for
all 12 previously disassembled classes below, with only `minecraft`, `mcp`,
`FML` and `forge` loaded. It records zero actual applicable capability/Load
listeners, the qualified Unload listeners, and an empty water-ticket map.

| Transformed class | SHA-256 |
| --- | --- |
| `net.minecraft.world.chunk.Chunk` | `0bd20899a444608e1f49f93a2a0a0b594a5cedb81d7202adfc54fd4e3417e7e3` |
| `net.minecraft.world.chunk.storage.ExtendedBlockStorage` | `b7445c7bd232e8debf2c6ed47438b890d6bd121e406455cdc3cb9e84b487cd23` |
| `net.minecraft.world.chunk.BlockStateContainer` | `9720a0dede53bd654fc6704b4251499f1bf1d853b56cd52f75b465b7956716bc` |
| `net.minecraft.world.chunk.NibbleArray` | `b5556356777ea7c17ecfd1ceec588da0aa8e183ee5305a16438215eaf297285f` |
| `net.minecraft.network.play.server.SPacketChunkData` | `9e8abff74ea6266a4793bfa03bbff9731d2c486c9ff34e5a67b81752ec063354` |
| `net.minecraft.tileentity.TileEntity` | `5e952e960c2e82f17e5151a305440a7adb4286e69642f5dba3b48dc66eb2d8a0` |
| `net.minecraft.world.gen.ChunkProviderServer` | `6eec9a968581e993495d79b9410e37d3e6b70da5b7de08ae72b3475311f63914` |
| `net.minecraft.world.chunk.storage.AnvilChunkLoader` | `24fff9a48dec4d82d08caab7484b8b53587ff2110703763675707369dcd91595` |
| `net.minecraftforge.common.chunkio.ChunkIOProvider` | `e2c76022ef993b0079f62b6a0671a17fd4bf232fa5bf83e01cb66196e6af50d1` |
| `net.minecraftforge.common.chunkio.ChunkIOExecutor` | `827d96f76d8b673ba66bb5e017c685900cf9472be18dc83888fe5297da6fcdd0` |
| `net.minecraft.world.World` | `5deb85ca3424a85c32b132c763e774a419e32676c8d520ef2677fa7bc695858a` |
| `net.minecraft.world.WorldServer` | `90d44f9f4e6417e186f96d4ee55fd669bdb928fe0c1cd1b096ca862997b56ca5` |

An unguarded mutation API is not intrinsically `OWNER_THREAD_ONLY`. The
classification attaches to a qualified invocation path and ownership scope.
Finding a setter on an ordinary tick path does not prove that every caller uses
that path. A class hash is identity evidence, not proof that all callers or
event listeners are known.

## Packet writer actually observed

`SPacketChunkData(Chunk,int)` establishes `fullChunk = (filter == 65535)` at
BCI 20-32 and reads skylight from the chunk's world provider at BCI 35-45. It
allocates from `func_189556_a` at BCI 46-56, writes the body using
`func_189555_a` and stores **that call's returned mask** at BCI 59-78. Only then
does it iterate the chunk's TE map and invoke each selected TE's virtual
`func_189517_E_` (`getUpdateTag`) at BCI 88-197.

`func_189555_a` selects each non-null section when its filter bit is set and,
for a full chunk only, its `func_76663_a` (`isEmpty`) returns false. For every
selected section it writes the actual `BlockStateContainer`, then block-light
bytes, then conditional sky-light bytes; full chunks append biome bytes.
The sizing and writing methods are separate passes over mutable state. Neither
pass acquires a chunk-wide synchronization primitive. This is why merely
calling the constructor near snapshot capture is insufficient.

## Writer inventory

The following paths are verified in the transformed methods. Unless explicitly
stated, the method contains no capture lease, synchronized admission, canonical
thread check or monotonic packet-snapshot generation. Exact endpoint comparison
can detect a persistent change after these writes; it cannot prove absence of
races or an ABA history.

| Domain and actual writer path | Runtime/thread context and classification | Exclusion and after-the-fact observation |
| --- | --- | --- |
| Section allocation: `Chunk(World,ChunkPrimer,int,int)` BCI 84-135; `Chunk.func_177436_a` BCI 124-203; light-setting path `func_177431_a`; Anvil read `func_75823_a` BCI 138-284 | Executed on the calling thread. Construction also occurs on Forge chunk-I/O workers. Public chunk setters are `DIRECT_BUT_OBSERVABLE` without a qualified caller inventory. | No shared capture barrier. Allocation changes slot identity/presence; end validation detects a surviving replacement. Detached factory excludes other callers by retaining ownership. |
| Section removal/replacement: direct array returned by `Chunk.func_76587_i`; `func_76602_a` BCI 37-49 copies supplied slots into the final array | Direct array writes and public bulk setter are `DIRECT_BUT_OBSERVABLE`. A declared owner-only call is insufficient if the array escaped. | No barrier and no automatic epoch. Same array identity does not detect replaced slots; compare every slot reference and contents. No automatic server-side removal on block count becoming zero is inferred. |
| Block states: `World.func_180501_a` invokes `Chunk.func_177436_a`; the latter calls `ExtendedBlockStorage.func_177484_a` BCI 192-203 | Synchronous call chain on caller's thread. The chunk path also invokes block/TE hooks, so reentrant mutation is possible unless those calls are excluded. APIs are `DIRECT_BUT_OBSERVABLE`; the closed detached harness has enumerated `OWNER_THREAD_ONLY` invocations. | No capture lease. Full logical-state comparison detects persistent mutations; content equality alone is not exclusion. |
| Container resize/replacement: `BlockStateContainer.func_186013_a` -> `func_186014_b` -> palette `func_186041_a`; resizer `func_186008_a` -> `setBits` | Caller thread. `setBits` replaces palette and packed `BitArray`; direct container mutation bypasses EBS refcounts. Public/protected storage API is `DIRECT_BUT_OBSERVABLE`; unknown subclasses remain `UNKNOWN`. | No lease. Container reference can remain unchanged while palette/storage references and packed content change. Strict admission uses exact supported classes and validated registry states. |
| Refcounts/empty selection: `ExtendedBlockStorage.func_177484_a` BCI 47-116 adjusts `field_76682_b` and `field_76683_c`; `func_76672_e` rescans; `func_76663_a` reads block count only | Caller thread; `DIRECT_BUT_OBSERVABLE` absent ownership qualification. Counts are ordinary nonvolatile fields. | No barrier. Recount validated logical states and compare exact block count/isEmpty. Low-level container writes can leave refcounts inconsistent even without concurrency. |
| Block light: `Chunk.func_177431_a` -> EBS `func_76677_d` -> `NibbleArray.func_76581_a`; EBS `func_76659_c` replaces plane; NibbleArray `func_177482_a` writes backing byte | Caller thread; raw getter `func_177481_a` permits direct writes and NibbleArray(byte[]) aliases its argument. `DIRECT_BUT_OBSERVABLE`; an external uncoordinated writer would be `ASYNC_UNCOORDINATED`. | No barrier or reliable universal hook. Copy and compare nibble object, array identity and all 2048 bytes; unknown external access rejects. |
| Sky light: chunk lighting methods `func_76603_b`, `func_76615_h`, `func_177431_a`; EBS `func_76657_c` and plane setter `func_76666_d`; same NibbleArray APIs | Same caller-thread and exposed-array limitations as block light. | No barrier. Compare plane presence, identity and exact bytes. Skylight flag comes from the actual qualified world provider. |
| Biomes: getter `Chunk.func_76605_m` returns backing bytes; setter `func_76616_a` copies; `func_177411_a` lazily fills a missing biome entry; Anvil read calls setter BCI 310-319 | A nominal biome lookup can write. Arrays can also be written by generators/callers; not intrinsically owner-only. `DIRECT_BUT_OBSERVABLE`. | No barrier. Compare all 256 bytes and identity. The owned oracle does not call biome-provider lookup while capturing. |
| Extended-ID/registry storage: `BlockStateContainer.setBits` BCI 73-99 uses `Block.field_176229_d` size for global width; `func_186019_a` resolves NBT state IDs through that map | Registry identity/content are outside section ownership. NEID/JEID or unknown transformed storage are `UNKNOWN` and unsupported here. | Pin registry identities and verify exact resolution before narrowing. Normal logical getter falls back to AIR for unresolved palette entries, so readback alone cannot legitimize malformed storage. |
| Unload/reload and same-coordinate replacement: `ChunkProviderServer.loadChunk` BCI 120-149 inserts by coordinate then loads/populates; `func_186025_d` BCI 118-141 inserts generated chunk; `func_73156_b` BCI 147-192 unloads, places dormant object, saves and removes map entry | Provider methods run on their caller's thread, expose a public mutable `field_73244_f` map, and invoke lifecycle events. The live caller/listener inventory is not proven closed: `UNKNOWN` for capture admission. | No capture incarnation token. Coordinates may name another object; dormant reload can reuse the same object. Need identity plus monotonically renewed lifecycle incarnation and generation, with atomic validation through publication. |
| Async construction/publication: `ChunkIOExecutor.queueChunkLoad` BCI 66-74 submits `ChunkIOProvider.run`; run calls `AnvilChunkLoader.loadChunk__Async` BCI 6-34; NBT read constructs states/light/biomes/capabilities | **Verified worker path**, not speculative mod behavior. `syncChunkLoad` waits under the same provider monitor; `tick` takes a different completion path described below. No native capture lease participates. | Worker construction is excluded entirely from the detached oracle. Its existence prevents classifying all clean Forge mutation as ServerThread-only. Live publication requires separate proof. |
| TE map and callbacks: `Chunk.func_177426_a`, `func_177425_e`, returned mutable map `func_177434_r`; actual packet constructor invokes virtual `TileEntity.func_189517_E_` after bytes | Getter exposes map; callback runs synchronously on constructor's thread but can reenter or be overridden. Unknown TE behavior is `UNKNOWN`, not read-only merely because it is a getter. | Default offline admission rejects nonempty TE maps before packet construction. For future support, qualify concrete callbacks and revalidate all packet state/lifecycle after tags; unknown or mutating callbacks reject. |
| Constructor escape: `Chunk(World,int,int)` BCI 150-155 calls `ForgeEventFactory.gatherCapabilities(this)` | Synchronous event listener invocation can receive the new graph before factory construction returns. `UNKNOWN` until actual applicable listeners are inspected. | Factory must verify the exact listener inventory and reject unsupported listeners before construction; `new Chunk` by itself does not establish exclusive ownership. |
| Qualified Forge unload callback: `ForgeInternalHandler.onChunkUnload` -> `FarmlandWaterManager.removeTickets` | Full FML registers this concrete callback. The private graph's owner invokes it only through the actual unload hook; the admitted path is `OWNER_THREAD_ONLY` with the global water-ticket map proven empty. | Under that precondition `getTicketManager` returns null and `removeTickets` returns without writing or retaining the graph. A nonempty ticket map invokes virtual ticket callbacks and is not admitted. |

## Forge asynchronous publication is not a blanket barrier

The transformed `ChunkIOProvider` has ordinary fields `chunk`, `nbt` and
`boolean ran`. Its `run` method enters the provider monitor, performs async load,
assigns fields, sets `ran`, calls `notifyAll` and releases the monitor.
`ChunkIOExecutor.syncChunkLoad` acquires that same monitor and waits for
`runFinished`; that path has a demonstrable monitor-release/acquire edge.

The transformed `ChunkIOExecutor.tick` instead calls the unsynchronized
`runFinished` getter and then `syncCallback`. Neither method acquires the
provider monitor on this path, and `ran` is not volatile. This bounded audit
does **not** establish an alternative worker-to-tick publication edge. It does
not claim a reproduced Forge defect, and it does not alter Forge. It records an
unresolved publication requirement for any future live adapter accepting chunks
from that path. A shared Rust lock or a Java snapshot endpoint check cannot
retroactively establish that Java happens-before edge.

`syncCallback` loads entities, posts `ChunkDataEvent.Load`, performs generator
recreation, inserts the chunk into the provider map, invokes chunk load/populate
and finally callbacks. Thus safe ownership transfer must also account for
those event/callback recipients; a completed disk read is not itself a complete
packet capture/publication permit.

The ordinary tick caller is visible in transformed
`MinecraftServer.func_71190_q`: BCI 71 calls `ChunkIOExecutor.tick`. This is a
verified normal owner-thread call path in the server loop; it does not add a
thread check to the public executor/provider APIs or a memory barrier to the
completion flag.

## Constructor and lifecycle callback closure

Transformed `ForgeEventFactory.gatherCapabilities(Chunk)` creates an
`AttachCapabilitiesEvent<Chunk>` and its private helper posts it on
`MinecraftForge.EVENT_BUS`. Transformed `EventBus.post` obtains the actual
event listener list for that bus ID and invokes each listener. The qualification
checks that same inherited dispatch list, including generic listeners, rather
than assuming it is empty because no mod directory is present. The recorded
capability listener list is empty. The factory must repeat that admission
before construction; no unqualified code may register listeners concurrently.

`Chunk.func_76631_c` (`onLoad`) sets the loaded flag, passes TE values to
`World.func_147448_a`, passes each entity collection to `func_175650_b`, and
posts `ChunkEvent.Load`. `func_76623_d` (`onUnload`) handles player/entity and TE
unload calls, clears the loaded flag, passes entity collections to
`func_175681_c`, and posts `ChunkEvent.Unload`. Empty TE/entity graphs avoid the
per-object callbacks, but do **not** avoid the final lifecycle event posts.
The full FML lifecycle registers the actual Unload dispatch list:
`EventPriority.NORMAL` followed by
`ForgeInternalHandler.onChunkUnload(ChunkEvent.Unload)`, wrapped by the known
`ASMEventHandler`. Load's dispatch list is empty. The earlier blanket
"all Forge listeners empty" constraint is not the final full FML contract.
Instead the exact relevant dispatch targets and effects are qualified, and any
additional listener rejects.

The guard checks more than the printable listener description: it requires the
exact `ASMEventHandler` class, resolves its generated handler class through the
method-keyed cache for `ForgeInternalHandler.onChunkUnload`, and verifies that
the generated wrapper's `instance` is `MinecraftForge.INTERNAL_HANDLER`.
These checks bind the dispatched method and singleton target in the pinned
runtime; no unqualified listener-registration code runs during the oracle.

`EventPriority.invoke` only updates the event phase. The transformed
`ForgeInternalHandler.onChunkUnload` checks the world remote flag and invokes
`FarmlandWaterManager.removeTickets`. Its `getTicketManager` reads
`customWaterHandler` by **dimension**, then chunk coordinates; when the global
map is empty it returns null. `removeTickets` then returns at BCI 98 without
retaining the Chunk or invoking ticket callbacks. For a nonempty map, its
`removeIf` predicate can invoke a virtual `SimpleTicket.unload`, so the oracle
must assert the entire global water-ticket map empty before and after hooks.
No live water-ticket compatibility is inferred.

The reviewed full-FML callback class hashes are:

| Class | SHA-256 |
| --- | --- |
| `net.minecraftforge.common.ForgeInternalHandler` | `91db5538e777c486d08392257c732b1b93e4c907aed5e8ea95b346f0aea20206` |
| `net.minecraftforge.common.FarmlandWaterManager` | `e9de27b0f12c9f6956ec16c187b4c0d166a3d6ea790db36d6a0a8bd28fcd9692` |
| `net.minecraftforge.fml.common.eventhandler.EventPriority` | `544861cfcd354096f17c10a925e1f27532ebc229dcf2090b29de3ea7ee1793fd` |

The offline lifecycle tests also require empty TE/entity collections. The actual
World constructor initializes the lists used by the empty collection calls;
there is no World capability event or provider publication in that constructor.
This tests the real Chunk hook behavior without asserting that a
provider cache or live server publication boundary was exercised.

## Registry identity and global-palette width

The actual transformed `BlockStateContainer.setBits` uses
`MathHelper.func_151241_e(ObjectIntIdentityMap.func_186804_a())`; the latter
returns the underlying `IdentityHashMap.size`. That quantity is **not** the
number of exported iterator rows or the number of unique decoded IDs. The
official run `20260924T225842Z-forge-c5e54c1b` recorded **1,681 exported rows**,
**1,656 unique IDs**, and **5,485 logical-map entries**, with maximum ID 4,083.
The actual map size selects a global width of **13 bits**. The indexed mapping
digest, after grouping by decoded ID, is
`449059d5a2b512040e982fb4763849d62e4ae586c73da38b191af0c25585cc00`.
The qualification also hashes the complete logical-state alias mapping and
combines both mappings in the registry identity; the indexed digest alone is
not a complete identity for the map whose size selects the global width.
Inferring 11 bits from either exported rows or unique IDs would be incorrect;
the adapter must read the actual method result. The final observer does not
preload required target classes before normal Bootstrap/FML initialization.

The actual `BitArray.func_188141_a` also checks that a written value is within
its bit mask, so unsupported growth can throw during Java container construction
rather than simply producing a truncated packet. Such a failed construction
must not be admitted as a successfully populated graph.

The oracle must preserve IDs as wide logical values and explicitly reject an
otherwise-global section containing an ID outside the actual width.
It must not widen Java's bits, silently truncate, renumber the registry or claim
that a registry-only probe is the full FML registry. Local palettes can represent
high IDs through explicit VarInts when they remain local. Runtime profiles need
distinct identities and requalification.

## Thread and classification conclusion

The offline owner is an exact `Thread` object chosen before graph construction;
its name is diagnostic only. It is not presented as a real running server's
ServerThread. In the admitted detached graph, all graph-mutating methods are
called only by the bounded harness before/after capture and no mutable graph
reference escapes. They are therefore `OWNER_THREAD_ONLY` **in that scope**.
There is no claim that Forge's setters intrinsically enforce this class.

No audited Forge writer participates in the foundation's `ParticipationLease`.
`WRITER_PARTICIPATING` is therefore not assigned to those methods. Known direct
writers without exclusive ownership are `DIRECT_BUT_OBSERVABLE`; an observed
concurrent writer outside any supported boundary is `ASYNC_UNCOORDINATED`;
unqualified callbacks, transformations and callers are `UNKNOWN`. Those three
classes reject unless a separately proven mechanism closes their gap.

The live requirement still missing is an enforceable closed writer/publication
boundary covering all packet-visible domains, constructor/callback escape,
registry, lifecycle, and use of the resulting packet. Endpoint equality, a
ServerThread name, a coordinate pair, a cache generation or a mask agreement is
not that boundary. The owned offline graph can validate the packet architecture
without asserting that this live requirement has been satisfied.
