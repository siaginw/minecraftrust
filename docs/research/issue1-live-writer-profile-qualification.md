# Issue #1: live writer profile qualification — Clean Forge 2860 (PROFILE ONLY, NO HOOKS)

Classification: **LIVE_WRITER_PROFILE_QUALIFIED_CLEAN_FORGE**. This stage qualifies the exact
class/method/hook surface the Ultra live-writer architecture intends to instrument. **No runtime
hook is inserted by this stage**: `SPacketChunkDataTransformer`, the coremod, `M4NativeStatePayload.tryEncode`,
MCK6 and every production boundary are untouched. This profile is evidence, not an arming switch.

Foundation: `a9cb171` "feat(issue1): add Java live writer protocol foundation" on `issue1-jni-v2`.

## A. Runtime identity binding (all exact)

| Identity | Value |
| --- | --- |
| Minecraft server jar | `fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e` |
| Forge jar | `cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64` (`.2860` only; `.2846` must not authorize) |
| JVM | Temurin `1.8.0_504-b01` (java/javac/rt.jar/tools.jar/jvm.dll hashed in the run receipt) |
| Qualification profile | `FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`, `production_authority=false` |
| Runtime pins | `tools/forge-capture/runtime-pins.json` @ `66b7c5e19fa1c7a84e53bdfee5b9e3dc1e34a60f5edbbd0d3b4590fb3b90cce1` (preserved unchanged) |
| Registry identity | `126bd48d66ba7533fd9ca5b3ce3203b53427075b44ca86d66ba401373eaf2007` |
| Descriptor manifest source | `issue1-ultra-final-20260924/writers/required-hook-descriptors.json` @ `47c8010a61b00bc1192471123c95098575b0f787d02f78e709d48abf6acdf4ed` |
| Foundation implementation | SHA-256 of the three committed protocol classes bound in the manifest |
| Manifest schema | `REQUIRED_LIVE_WRITER_HOOKS_V1` (schema_version 1, 66 required hooks) |

Changing **any** of these identities requires requalification: the verifier recomputes every
artifact/file hash and the aggregate is fail-closed.

## B. Descriptor qualification result

- Required hooks: **66** = the 60 audited writer descriptors + the handoff's 6 supplemental
  packet/source-path sites (`MinecraftServer.run`, `SPacketChunkData.<init>`,
  `AnvilChunkLoader.loadChunk__Async`/`checkedReadChunkFromNBT__Async`/`func_75823_a`,
  `ChunkGeneratorFlat.func_185932_a`).
- **QUALIFIED: 66/66.** Every descriptor has exactly one terminal status from the vocabulary
  (`QUALIFIED`, `MISSING_CLASS`, `MISSING_METHOD`, `DESCRIPTOR_MISMATCH`, `HASH_MISMATCH`,
  `FINGERPRINT_MISMATCH`, `AMBIGUOUS_MATCH`, `TRANSFORM_ORDER_MISMATCH`, `UNSUPPORTED_RUNTIME`).
- Hook types: 50 `WRITE_BEGIN` (each bracketed by `WRITE_END` in a catch-all finally), 10
  `PRIVATE_BUILD_BEGIN`, 1 `PRIVATE_BUILD_END` (`ChunkIOProvider.run` release ticket before the
  `ran` putfields at BCI 74/88), 1 `PUBLICATION_BEGIN` (`ChunkIOProvider.syncCallback`, bracketed
  `PUBLICATION_END`), 1 `LIFECYCLE_RETIRE` (`ChunkProviderServer.func_73156_b`, retire precedes
  onUnload 149 / dormant 167 / save 173/179 / remove 187), 1 `PACKET_CAPTURE`
  (`SPacketChunkData.<init>`, entry after `Object.<init>` BCI 1, commit at normal return 200),
  2 `DIAGNOSTIC_ONLY` (`MinecraftServer.run`, `ChunkGeneratorFlat.func_185932_a`).
- Thread contexts: 40 `CANONICAL_OWNER`, 22 `CANONICAL_OWNER_OR_IO_WORKER`, 4 `IO_WORKER`.
- Fingerprint evidence: **93 bytecode/structure assertions** verified per fresh JVM with `javap`
  on the actual transformed definitions — e.g. `ChunkIOProvider.run` putfields `nbt`@59, `chunk`@69,
  `ran`@74, `ran`@88; `SPacketChunkData.<init>` `Object.<init>`@1, first read@4, writer `func_189555_a`@75,
  mask `field_186948_c` store@78, `TileEntity.func_189517_E_`@180, `return`@200;
  `ChunkGeneratorFlat.func_185932_a` getter@184 / `bastore`@213 / skylight@222;
  provider map put@131 (`loadChunk`) / @125 (`func_186025_d`); unload sequence 149/167/173/179/187.
- Transformed-class hashes: 14 hook/context classes pinned in
  `tools/live-capture/live-shadow-profile.json`; the 11 classes shared with the accepted Ultra
  table match its published hashes exactly (e.g. `SPacketChunkData 9e8abff7…`, `ChunkIOProvider e2c76022…`,
  `PlayerChunkMapEntry c92f9844…`).
- Context definitions (send path, recorded + hash-pinned, not arming-gating):
  `PlayerChunkMap.func_72693_b()V`, `PlayerChunkMapEntry.func_187272_b()Z`,
  `func_187278_c(EntityPlayerMP)V`, `func_187280_d()V` — all QUALIFIED.

### Runtime corrections the verifier forced (handoff prose vs actual bytes)

- `PlayerChunkMapEntry.func_187272_b` returns `Z`, not the `()V` the handoff prose implied.
- The audit descriptors for object-returning methods (e.g. `func_177436_a`, `func_186025_d`,
  `func_75823_a`, `func_185932_a`) were confirmed correct against the runtime; the verifier's
  first draft had parser bugs (constructor naming, `throws` clauses, trailing-`;` stripping) that
  produced spurious mismatches — fixed in the verifier, not by weakening the profile.
- Three classes the offline FML-initialized profile never loads (no world/players exist:
  `ChunkGeneratorFlat`, `PlayerChunkMap`, `PlayerChunkMapEntry`) are now forced-definition
  requests in the offline qualification harness (`QualifyRuntime.REQUIRED`, `Class.forName(name,
  false, Launch.classLoader)`): the passive observer records the real transformed bytes without
  executing any static initializer, world, tick, or generator code. The pinned 51 transformed
  hashes are unchanged by this extension and all six existing lanes stay green.

## C. Writer-domain coverage matrix

| Domain | Known writers | Qualified hooks | Unhooked paths | Thread context | Eligibility consequence |
| --- | --- | --- | --- | --- | --- |
| section existence | Chunk/EBS/BSC/NibbleArray/BitArray constructors; `Chunk.func_177436_a` allocation | 8 (W12, W23, W34–W36, W43, W50, W55) | none known in the qualified profile | owner or IO worker (constructor) | allocation without registration = unregistered write → fail closed before mutation |
| block states | `World.func_180501_a` → `Chunk.func_177436_a` → `EBS.func_177484_a` → container `setBits`/resize → `BitArray.func_188141_a` | 10 (W01, W12, W37, W44–W49, W54) | direct raw array writes stay UNKNOWN | owner (+ private build on worker) | participating writers only; unknown raw writers keep the profile Java-only |
| blockRefCount/isEmpty | `EBS.func_177484_a` count writes (62/80/98/116), `func_76672_e` recount | 2 (W37, W40) | `func_76663_a` is a read (no hook needed) | owner or IO worker | refcount/count must move inside the same guard as cells |
| block light | `World.func_175653_a` → `Chunk.func_177431_a` → `EBS.func_76677_d` → `NibbleArray` setters; propagation | 15 | raw `func_177481_a` array exposure stays excluded | owner (private build on worker) | whole-operation guards; exposed-array writes remain UNKNOWN → excluded |
| sky light | `Chunk.func_76603_b`/`func_76615_h`/`func_150809_p`, `EBS.func_76657_c`, plane replacement `func_76666_d` | 19 | same raw exposure exclusion | owner (private build on worker) | no permanent "lighting finished" latch; all post-readiness updates participate |
| biomes | `Chunk.func_76616_a` (arraycopy49), lazy `func_177411_a` (bastore92), raw generator store `func_185932_a` (213) | 3 (W24, W25, S06) | none in the flat-generator profile | owner (S06 verifies the raw store is inside the owner generation transaction) | raw store eligible only inside the qualified owner generation |
| storage/backing replacement | `Chunk.func_76602_a` (arraycopy49), `EBS.func_76659_c`/`func_76666_d` plane replacement, `BSC.setBits` (73–99), `BSC.func_186019_a`, `NibbleArray([B)` aliasing | 6 (W23, W41, W42, W45, W49, W51) | none known | owner or IO worker | aliasing constructor records prior provenance; new wrappers never launder storage |
| private I/O construction | `ChunkIOProvider.run` (release before `ran` 74/88), `AnvilChunkLoader` provenance branch (17/29/59/62/69/78), `checkedReadChunkFromNBT__Async`, `func_75823_a`, constructors | 7 (W34, W35, W59, W60, S03–S05) | pending-NBT branch 17/29 is recorded as SHARED_PENDING_NBT, never private | IO worker | only BUILDING-ticket private writes; sealing revokes before the volatile completion |
| owner publication | `ChunkProviderServer.loadChunk` (put131), `func_186025_d` (put125), `ChunkIOProvider.syncCallback` (acquire ≤1, put136, onLoad146, populate164, callbacks168, completion171), `Chunk.func_186030_a`/`func_186034_a`/readiness flags | 7 (W20, W21, W31, W32, W56, W57, W60) | none known | owner | READY only after normal outermost owner transaction completion |
| unload/reload | `ChunkProviderServer.func_73156_b` (onUnload149/dormant167/save173/179/remove187), `Chunk.func_76623_d`/`func_76631_c` | 4 (W18, W19, W56, W58) | none known | owner | retire precedes unload callbacks; same-object reload gets a fresh incarnation |
| same-coordinate replacement | provider map puts 131/125 with identity recording | 2 (W56, W57) | direct map mutation is excluded (public mutable map stays owner-only in the profile) | owner | coordinates never authorize; binding identity is session+object+incarnation |
| TileEntity restrictions | `World.func_175690_a`/`func_175713_t`, `Chunk.func_177424_a`/`func_150813_a`/`func_177426_a`/`func_177425_e`/`removeInvalidTileEntity`, packet TE loop (`func_189517_E_`@180) | 8 (W03, W04, W14–W17, W33, S02) | TE-bearing chunks remain Java-only regardless of filter | owner | capture checks the empty TE map before and after the original constructor |
| owner session | `MinecraftServer.run` (field `field_175590_aa`, getter `func_175583_aK`, tick `func_71217_p`) | 1 (S01) | — | owner | diagnostic session bootstrap/shutdown; shutdown revokes bindings |
| packet capture | `SPacketChunkData.<init>(Chunk,int)` | 1 (S02) | — | owner | one `tryLock` admission at BCI 1; commit at return 200; catch-all rethrows |

No known required writer in the documented Clean Forge eligibility contract is left without a
qualified hook or an explicit exclusion. Direct raw-array writes and unknown mod writers remain
UNKNOWN and are excluded by policy (no best-effort bypass), as the Ultra handoff requires.

## D. Fail-closed verifier and negative controls

- `tools/testing/live_profile.py` — pure decision layer + qualification driver. Every required
  descriptor gets exactly one vocabulary status; identity failures mark the whole profile
  `UNSUPPORTED_RUNTIME`; a fresh observation equal to the vanilla jar entry is
  `TRANSFORM_ORDER_MISMATCH`; wildcards/duplicate requirements are `AMBIGUOUS_MATCH`; nothing
  falls back to best-effort.
- `tools/testing/test_live_profile.py` — 15 deterministic negative controls (no JVM, no sleeps):
  wrong method name, wrong JVM descriptor, wrong class hash, altered insertion fingerprint,
  shifted BCI, missing required class, wildcard descriptor, wrong Forge build (.2846 rejected),
  untransformed vanilla substitution, committed-profile drift, absent class-structure markers,
  `return`-family opcode discrimination, aggregate arming rule, parser round-trip.
  All fail closed. Result: 15/15 OK.

## E. Runner integration

`tools/run-rustcraft-tests.ps1 live-profile` (and `python -B tools/testing/run_tests.py live-profile`):
verifies exact runtime artifact identities, runs the fresh-JVM qualification, checks every required
descriptor, writes machine-readable results (`live-profile-results.json` + receipt), returns nonzero
when any REQUIRED hook is not QUALIFIED, and reports wrong/missing runtime as
`INCOMPLETE`/`ARTIFACT_MISMATCH` (`NOT_RUN`), never PASS.

Reproduction (verify mode against the committed profile):
run `20260925T034253Z-live-profile-71e5a6bb` — **PASS, 66/66 QUALIFIED, 0 nonqualified**.

## F. Existing regressions

All six lanes PASS with the harness extension in place (public `20260925T034322Z-public-2d68d95e`,
property `…e87bf262`, fixture `…f51fff1d`, decoder `…5c27a8f7`, java-jni `…bc3d1c3f`,
forge `…22a3be7e` with 21 accepted / 36 rejected fixtures). The 167 Java writer-protocol checks
re-run green (39/57/57/14). `runtime-pins.json` and the historical evidence files are unchanged;
the historical integrity-checker FAIL remains reported as-is.

## G. Files

New: `tools/live-capture/required-live-writer-hooks.json`, `tools/live-capture/live-shadow-profile.json`,
`tools/testing/live_profile.py`, `tools/testing/test_live_profile.py`, this document.
Modified: `tools/forge-capture/src/com/rustcraft/offline/oracle/QualifyRuntime.java` (forced-definition
list +3 classes), `tools/testing/run_tests.py` (+ `live-profile` lane, live-capture JSONs in the
source snapshot), `tools/run-rustcraft-tests.ps1` (+ lane choice).
