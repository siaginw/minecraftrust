# ISSUE1 CLEAN-FORGE OFFLINE CAPTURE REPORT

Verified 2026-09-24. Classification: **CLEAN_FORGE_PACKET_CAPTURE_OFFLINE_VALIDATED**.
This classifies the actual initialized Forge writer operating on exclusively owned,
detached real Forge objects. It does not qualify live chunk capture or packet
publication. Production native packet authority remains fail-closed. Issue #1
remains open; MCK6, frame authority, compression, defaults and historical evidence
registry are unchanged. No push, rebase, history rewrite, live SHADOW or packet
transmission occurred.

## A. Foundation commit SHAs

Branch `issue1-jni-v2`; HEAD `7a0943ceb1f47b5251f2b1cbdbd4e6eda288f74c`. The accepted public/capture suites were rerun
successfully before preserving the foundation in these logical local commits:

- `5ce257acea94158d79644b0689c240a3312cbd6c` — feat(issue1): add owned coherent chunk snapshot foundation
- `2f1a1997904ca7cd2ac4b8d7ceef78811bc5e735` — test(issue1): add property and snapshot replay validation
- `7a0943ceb1f47b5251f2b1cbdbd4e6eda288f74c` — test(tooling): extend RustCraft runner for capture validation

All older accepted commits are preserved unchanged. Current Clean Forge stage
changes remain uncommitted and unstaged; only the accepted foundation was committed.

## B. Exact runtime identities

Minecraft 1.12.2 / protocol 340; Forge 14.23.5.2860; Temurin/OpenJDK
1.8.0_504-b01, OpenJDK 64-Bit Server VM. Original server runtime root:
`D:/rustcraft-runtime-targets/clean-forge-2860/server`.

All 23 ordered runtime artifacts and their exact hashes:

| Artifact path | SHA-256 |
| --- | --- |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\forge-1.12.2-14.23.5.2860.jar` | `cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\ow2\asm\asm-debug-all\5.2\asm-debug-all-5.2.jar` | `254b82bec9da4f8efbc8b1f93ab2b87f7465227a82b36cf3d05d9e77a0e8dd2e` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar` | `57f402b626d16cc2705bf2a37add7adbb074f0ca3b3102fa6e23aa303dae682f` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\jline\jline\3.5.1\jline-3.5.1.jar` | `7285f4a93d4205674e328a3e28e4d7cec10429f3ab5a0d7024b85746e958aeda` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\com\typesafe\akka\akka-actor_2.11\2.3.3\akka-actor_2.11-2.3.3.jar` | `4ccbc821a417f0174c9734fc721f078177cacef94ded09c3775ebf40c6a7bdfd` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\com\typesafe\config\1.2.1\config-1.2.1.jar` | `c160fbd78f51a0c2375a794e435ce2112524a6871f64d0331895e9e26ee8b9ee` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-actors-migration_2.11\1.1.0\scala-actors-migration_2.11-1.1.0.jar` | `b89d0beb76df20c7b2c2bee59ba53b802cc48566bc3e89ac96ce6361c5c2ee38` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-compiler\2.11.1\scala-compiler-2.11.1.jar` | `3aa616f5c56d2052fc5e3231f3e8cf1736f876caff0142a66096fc5b4dc87ae5` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\plugins\scala-continuations-library_2.11\1.0.2_mc\scala-continuations-library_2.11-1.0.2_mc.jar` | `8dd0a4941f4564117cc991734ca36aa53dff6bb0be329f39eab46d8819f60d1a` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\plugins\scala-continuations-plugin_2.11.1\1.0.2_mc\scala-continuations-plugin_2.11.1-1.0.2_mc.jar` | `79329971a31ede7b6823f21a6ab765506b4093d9276debdc9dbe17b996884f2b` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-library\2.11.1\scala-library-2.11.1.jar` | `088bcb80f71b6e6a2a31d2fe7288c1fd64f19663ce281b924cdfab3fb105f4f3` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-parser-combinators_2.11\1.0.1\scala-parser-combinators_2.11-1.0.1.jar` | `19495ce701fd9ba3c499c137e3ad5364acee8f87a01ef99e912f995a23fd4cb1` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-reflect\2.11.1\scala-reflect-2.11.1.jar` | `41938d1e89670979dd783102777a8b879999f4fb00a5b811b51c105b02a9d4f8` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-swing_2.11\1.0.1\scala-swing_2.11-1.0.1.jar` | `4a7c757d4d87dd37382bd777ad611b5875748aef148277cf25bb39eebb496d07` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\scala-lang\scala-xml_2.11\1.0.2\scala-xml_2.11-1.0.2.jar` | `6c3338a4062c13dc2ce61630d9854d361a81656a4050e5080302f500dd4a8832` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\lzma\lzma\0.0.1\lzma-0.0.1.jar` | `0c3085cb93c3ef95d731c274b6f2dd195aa0405fe9328d69cab7f5e899d5e124` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\java3d\vecmath\1.5.2\vecmath-1.5.2.jar` | `09adf7955baa6bddf57a4bdbf9a91cf690261314969b59a18085ece1e05eccf9` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\net\sf\trove4j\trove4j\3.0.3\trove4j-3.0.3.jar` | `3c8616203d61a12a7e3487e8b34f3c198c2b5ba9e90da0c7ea32d99cd4958012` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\apache\maven\maven-artifact\3.5.3\maven-artifact-3.5.3.jar` | `30d066e27379a51c8d9c67b1ab0b7e55d930c9721161b07ef1666ab9fc24c629` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\net\sf\jopt-simple\jopt-simple\5.0.3\jopt-simple-5.0.3.jar` | `6f45c00908265947c39221035250024f2caec9a15c1c8cf553ebeecee289f342` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\apache\logging\log4j\log4j-api\2.15.0\log4j-api-2.15.0.jar` | `c8c33e7e8e05496dae69cf0caac8c3092cffd937a164526e92922d2d566d0a55` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\libraries\org\apache\logging\log4j\log4j-core\2.15.0\log4j-core-2.15.0.jar` | `419a8512895971b7b4f4f33e620d361254e5c9552b904b0474b09ddd4a6a220b` |
| `D:\rustcraft-runtime-targets\clean-forge-2860\server\minecraft_server.1.12.2.jar` | `fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e` |

| Tool or harness input | Local path | SHA-256 |
| --- | --- | --- |
| java | `D:\rustcraft-toolchains\temurin8\jdk8u504-b01\bin\java.exe` | `65ebee06ec47df0c41c4483ac5aa408e55adb47a1613bad56be30bccc4317e85` |
| javac | `D:\rustcraft-toolchains\temurin8\jdk8u504-b01\bin\javac.exe` | `9f1dae15ffb49c7363c72351cf61f4889ac6896e3df87c1c5553b81745ae8a07` |
| jvm_dll | `D:\rustcraft-toolchains\temurin8\jdk8u504-b01\jre\bin\server\jvm.dll` | `d3f1564b31a25f280d633d33234bdf88fd12dbe0ae34e15e2e89db3f0ca2d407` |
| rt_jar | `D:\rustcraft-toolchains\temurin8\jdk8u504-b01\jre\lib\rt.jar` | `1166665601d9b1587682e2540cff6a31b0c7028211d2e07adb9ad30e1699eca3` |
| tools_jar | `D:\rustcraft-toolchains\temurin8\jdk8u504-b01\lib\tools.jar` | `7325904e8de35b7376bbed7080a1fce7d9b886d3dddb5bfdbc00cefe7b248987` |
| dll | `D:\minecraftrust\target\release\rustcraft_ffi.dll` | `fcc1506d73475aa067037a7b74cb2398b54d14b062e65115aac99810c524956e` |
| observer_jar | `D:\minecraftrust\target\rustcraft-tests\runs\20260924T225842Z-forge-c5e54c1b\clean-forge\observer.jar` | `86d16f51c34bd87e3b9b735a249bb551a57660c5752483608b3e8b1d5d1eb4e4` |
| pins | `D:\minecraftrust\tools\forge-capture\runtime-pins.json` | `66b7c5e19fa1c7a84e53bdfee5b9e3dc1e34a60f5edbbd0d3b4590fb3b90cce1` |
| helper | `D:\minecraftrust\tools\testing\forge_runtime.py` | `1f193075f5e6681544735e7771db64f0faa3f35e553822e0af48aea29b784303` |
| logging | `D:\minecraftrust\tools\forge-capture\log4j2.xml` | `022aa5305c3740c52bbf520312365e8295eb8a4fc882aa6b88a0a2bec48631ec` |

Forge embedded SRG remapping data SHA-256:
`16dab5e08488d76e503cef215fc4000820f615fd6eaa1bd4450603fce1a0a2e6`.
Forge binary patches SHA-256:
`ceebaefd4abca814aa0160e71e62c507d63733b7da1773c1268e04ac9a720882`.
The original obfuscated jar goes through Forge's own mapping; no development jar
substitutes for runtime semantics. Compiled bridge/source identities and actual
generated configuration hashes are in [forge-runtime-result.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T225842Z-forge-c5e54c1b/clean-forge/forge-runtime-result.json).
RustCraft's production coremod is not loaded. Only the Forge FMLCorePlugin and
FMLForgePlugin are registered; loaded containers are minecraft, mcp, FML and forge.

Registry: 1,681 exported iterator rows, 1,656 unique decoded IDs, 5,485 logical-map
entries, maximum decoded ID 4,083. Actual map size selects **13 global bits**.
Composite registry identity:
`126bd48d66ba7533fd9ca5b3ce3203b53427075b44ca86d66ba401373eaf2007`.
Decoded-ID and full logical-alias exports are both hash-bound.

## C. Transformed-class qualification

Profile: `FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`.
Actual FML load/preinit/init completed without starting a server. A passive Java
instrumentation agent observes the bytes passed to JVM definition after all
LaunchWrapper transformations, returns null, and never retransforms/redefines.
The final chain has 14 Forge transformers, including the late ModAPITransformer.
There are 51 required pinned classes and 1,619 observed definitions in the oracle.

| Required packet class | Final-definition SHA-256 |
| --- | --- |
| net.minecraft.world.chunk.Chunk | `0bd20899a444608e1f49f93a2a0a0b594a5cedb81d7202adfc54fd4e3417e7e3` |
| net.minecraft.world.chunk.storage.ExtendedBlockStorage | `b7445c7bd232e8debf2c6ed47438b890d6bd121e406455cdc3cb9e84b487cd23` |
| net.minecraft.world.chunk.BlockStateContainer | `9720a0dede53bd654fc6704b4251499f1bf1d853b56cd52f75b465b7956716bc` |
| net.minecraft.world.chunk.NibbleArray | `b5556356777ea7c17ecfd1ceec588da0aa8e183ee5305a16438215eaf297285f` |
| net.minecraft.network.play.server.SPacketChunkData | `9e8abff74ea6266a4793bfa03bbff9731d2c486c9ff34e5a67b81752ec063354` |
| net.minecraft.tileentity.TileEntity | `5e952e960c2e82f17e5151a305440a7adb4286e69642f5dba3b48dc66eb2d8a0` |
| net.minecraft.network.PacketBuffer | `4a1fbbf4796c068a4317bb15b317a963b2f1f9d0de2fc64cbd718fa98f2281f7` |

All remaining required identities are in the tracked
[runtime-pins.json](D:/minecraftrust/tools/forge-capture/runtime-pins.json); all observed identities,
classloaders and locations are in [qualification.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T225842Z-forge-c5e54c1b/clean-forge/oracle-jvm/qualification.json).
See [issue1-clean-forge-runtime-qualification.md](D:/minecraftrust/docs/research/issue1-clean-forge-runtime-qualification.md).

## D. Writer inventory

The evidence-backed [issue1-clean-forge-writer-audit.md](D:/minecraftrust/docs/research/issue1-clean-forge-writer-audit.md)
records actual transformed class/method/BCI, caller context, exclusions, leases
and detectability for every requested domain. Public setters and exposed arrays
are **DIRECT_BUT_OBSERVABLE**, not intrinsically owner-thread-only. Async Anvil
construction is a verified worker path; live lifecycle/listener closure remains
**UNKNOWN**. No Forge writer automatically participates in a native capture lease.
The admitted detached factory's enumerated writes are **OWNER_THREAD_ONLY** through
actual no-escape ownership. **WRITER_PARTICIPATING** is only an available contract
category; no live coverage is inferred. Declared **ASYNC_UNCOORDINATED** and
**UNKNOWN** sources reject. Refcounts, palette replacement, lights, biome lookup
writes, TE effects, constructor escape, and unload/reload are explicitly audited.

## E. Formal eligibility contract

Exact pinned initialized runtime and registry; canonical owner Thread object; unescaped detached Chunk/section/container/light/biome graph; admitted lifecycle incarnation and generation; exact storage classes and canonical full-width IDs; complete owner-confined writer inventory; no unsupported async/unknown writer; fullChunk equals (filter == 0xffff); selection derived during capture; empty TE/entity graph; exact qualified constructor/load/unload dispatch and empty water-ticket map; strict identity/content/epoch end checks; immutable accepted output and one V2 byte-count/mask result. Live authority additionally requires safe provider publication and a current identity-token check through packet publication; those conjuncts are not satisfied by this offline stage.

`NATIVE_PACKET_AUTHORITY_ELIGIBLE_CLEAN_FORGE` is defined conjunctively in
[issue1-clean-forge-capture-proof.md](D:/minecraftrust/docs/research/issue1-clean-forge-capture-proof.md). It is a necessary
future authority contract, not an enabled switch. Offline oracle eligibility
excludes publication; live authority remains false.

## F. Offline capture adapter

`OwnedForgeCapture` creates real Chunk/World/EBS/container objects through actual
constructors. It never wraps a published world/provider chunk. The initial
NEW_DETACHED state is explicit and is not a claim that Forge marks it loaded.
Actual Thread identity admits access. It retains full logical IDs before bounds
checks, derives selected sections while copying, captures lights/biomes and
lifecycle/epoch/provenance, and strictly revalidates before returning owned bytes.
Transient views stay inside the audited copier call graph. Returned Pair/snapshot
objects retain no mutable graph references. Partial setter failure poisons the
source. Scope byte 2 identifies REAL_CLEAN_FORGE_ORACLE; neither scope byte nor
provenance digest authenticates ownership or grants authority. Existing JNI V2
layout is unchanged.

## G. Same-event Java-reference proof

The chosen mechanism is exclusive construction/ownership of the detached graph,
with both consumers inside one closed owner-thread interval. Constructors cannot
publish the graph through unqualified listeners. No provider, worker, caller or
arbitrary callback receives it; accepted TE maps are empty. All population writes
precede capture on the same Thread, and no graph writer can run between accepted
snapshot reads and the actual SPacketChunkData constructor/body writer. Program
order supplies happens-before. End-state equality supports this exclusion proof;
it is not the proof by itself. Owned snapshot and Java packet bytes therefore
represent the same logical event. Rust reads only that owned input. Detailed proof
and rejected alternative designs are in the capture-proof document.

## H. Accepted real fixtures

**21 REAL_CLEAN_FORGE_ORACLE fixtures**, stored locally in
`D:/minecraftrust/target/rustcraft-tests/runs/20260924T225842Z-forge-c5e54c1b/real-forge-fixtures`. They bind actual runtime/class/registry,
source/build/contract, owned input, Java packet, native payload and fixture hashes.
No proprietary binaries or generated real fixture contents are committed.

Accepted cases: zero-sections, one-section, sparse-high, terrain-001f, sky-off, empty-before, empty-to-nonempty, nonempty-to-empty, local-palette-expansion, local-to-global, light-before, block-light-change, sky-light-change, biome-change, storage-before, storage-replacement-between-events, partial-present-empty, present-empty-full, inplace-empty-to-nonempty, inplace-nonempty-to-empty, reloaded-current.

## I. Semantic comparison results

**21/21 semantic matches and 21/21 native CLI replays**. The independent decoder
checks every selected section, all 4,096 IDs, palette semantics and packed words,
block light, conditional skylight, biome tail, returned mask/count and exact
consumption of both Java and native payloads. The complete Java packet envelope
also consumes exactly, including its zero TE-tag count. All 21 happened to match
bytes exactly; semantic equivalence is the required result. A separate audit
verified persisted schema/canonical hashes and all 1,765 referenced artifacts.

Receipt: [results.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T225842Z-forge-c5e54c1b/real-forge-fixtures/results.json).

## J. Adversarial rejections

**20/20 explicit safe rejections**, exact expected reasons, with no successful
payload fields on rejected rows. Cases cover mid-capture state/light/sky/biome/
storage/removal changes, replacement/unload, extended IDs, unknown/async writers,
stale generation/incarnation, same-coordinate replacement, off-thread capture,
nonempty TE map, missing selected native section, unsupported native ID and
capacity failure. All 41 accepted/rejected names are required by replay; missing
or duplicate cases cannot pass. Invalid full/filter relationships and tampered
mask/count/payload/registry/artifact metadata also have public replay regressions.

## K. TileEntity ordering

Actual transformed SPacketChunkData serializes body and obtains its mask before
iterating the TE map and calling virtual getUpdateTag. Those calls can reach
arbitrary subclass/capability behavior; read-only behavior is not assumed.
The admitted subset rejects any nonempty TE map before constructing the packet.
Thus no TE callback occurs during accepted attempts. Future TE support must
qualify callbacks and reject/revalidate mutations under the same exclusion and
publication boundary. Callbacks and tags remain Java-owned.

## L. Lifecycle/incarnation proof

Requests bind Chunk identity plus incarnation and generation, not coordinates
alone. Unload rejects, reload renews both tokens even for the same object,
same-coordinate replacement renews identity/tokens, and a lifecycle change during
capture rejects. Current-incarnation reload succeeds. Historical owned snapshot A
remains encodable as A; it is not a publication permit for B. A future live
publication API must validate the current token atomically through publication.
No such live publication claim is made here.

## M. Proptest and existing suites

Five new generated owned-snapshot properties plus two deterministic tests pass.
Normal budget is 64 cases/property; a separate 256-case run of the new properties
also passed. Coverage includes masks/presence, fill-clear transitions, palette
transitions, lights/biomes, capacities/retries, identity/admission corruption and
independent full-body decoding. Existing V2 round-trip properties remain green.
Minimized retry-capacity test-model failure is retained as a concrete `.case`;
no production behavior was changed to hide it.

Final validation:

| Check | Result |
| --- | --- |
| cargo test --locked -p native-chunk | 41 passed, including all original 10 unit tests |
| cargo test --workspace --lib --locked | 78 passed |
| cargo test --locked -p protocol | 10 passed |
| cargo build --release --locked -p ffi | PASS |
| ffi packet_encode_v2 integration | 9 passed |
| structural packet_encode_contract targeted rerun | 14 passed |
| owned packet_snapshot targeted rerun | 8 passed |
| runner / runtime / real-fixture Python regressions | 27 / 10 / 15 passed |
| independent decoder / available evidence regressions | 37 / 5 passed |
| standalone Java V2 / retained JNI / capture / owned JNI | All 4 mains passed |
| public / property / fixture / decoder / java-jni lanes | All PASS |

Counts overlap between workspace/package/target reruns and are not added together
as independent tests. Existing compiler warnings remain; no correctness lane
failed. Exact test logs are in the lane receipts listed below.

## N. cargo-mutants results

Pinned cargo-mutants 27.1.0 ran only four selected Rust safety functions in
disposable copies: strict section encoding, snapshot parser, positive identities,
and V2 coupling. **117 distinct mutants: resolved 113 caught, 2 equivalent,
2 unbuildable, zero timeouts and zero unexplained correctness survivors.** Two
initial test gaps gained deterministic assertions and their exact mutants were
rerun and caught. Equivalent OR/XOR cases operate on provably disjoint fields.
Unbuildable defaults cannot fabricate the private/non-Default result types.

Separate source-anchored Java/Python fault injection caught nine Java and three
Python guard faults; one redundant Python count guard is equivalent. This is not
cargo-mutants support for those languages and no mutation score is treated as
proof. Audit source hashes still match the current mutated Rust functions.
See [issue1-focused-guard-audit.md](D:/minecraftrust/docs/research/issue1-focused-guard-audit.md) and
`D:/rustcraft-bootstrap/issue1-forge-20260924T223951Z/mutation/resolved-audit.json`.

## O. Forge runner result

Two complete official invocations passed:

- [results.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T225842Z-forge-c5e54c1b/results.json) — fresh compilation, fresh qualification/oracle JVMs.
- [results.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T225947Z-forge-7b8542ef/results.json) — both compilation cache hits, still two fresh JVMs
  and full 21-fixture replay.

Wrong-artifact invocation: **INCOMPLETE / ARTIFACT_MISMATCH**, exit **2**, with
no oracle replay; receipt:
[results.json](D:/minecraftrust/target/rustcraft-tests/runs/20260924T230012Z-forge-caa618eb/results.json).
Semantic FAIL exits 1 and incomplete evidence exits 2; neither is PASS. Reused
compilation never reuses a test result. No performance claim or JMH adoption.

## P. Unsupported limitations

Arbitrary live chunks, the async ChunkIOProvider-to-tick publication path, escaped mutable arrays, unknown writers, nonempty TE callbacks, additional listeners/coremods/mods, NEID/JEID/unsupported wide IDs, raw palette corruption, provider/world lifecycle integration, and network publication remain unsupported. No live SHADOW or modpack campaign ran. Immutable historical A snapshots remain encodable as A data; a future publication operation must prevent their use for replacement B.

Unrelated all-target workspace tests still reference absent NBT bench/oracle
sources. Four historical evidence tests and the strict evidence audit still lack
historical raw artifacts; they were not relabeled green or repaired by changing
the registry. World/socket/live tests are outside these bounded lanes.
`ROOT_CAUSE_CLASS_HARDENED` and `HISTORICAL_EXACT_WRITER_UNRESOLVED` remain unchanged.
This does not establish the cause of the historical java=63/native=31 event.

## Q. Files changed and review state

Modified 14 existing files and added 20 text files, all uncommitted:

- [owned_snapshot.rs](D:/minecraftrust/crates/ffi/src/owned_snapshot.rs)
- [packet_snapshot.rs](D:/minecraftrust/crates/native-chunk/src/packet_snapshot.rs)
- [packet_snapshot.rs](D:/minecraftrust/crates/native-chunk/tests/packet_snapshot.rs)
- [issue1-owned-snapshot-transport.md](D:/minecraftrust/docs/research/issue1-owned-snapshot-transport.md)
- [chunk-packet-fixture-v1.schema.json](D:/minecraftrust/docs/schemas/chunk-packet-fixture-v1.schema.json)
- [corpus.json](D:/minecraftrust/tests/fixtures/issue1-capture/corpus.json)
- [CaptureContract.java](D:/minecraftrust/tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java)
- [CaptureSource.java](D:/minecraftrust/tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java)
- [OwnedPacketSnapshot.java](D:/minecraftrust/tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java)
- [SnapshotCapture.java](D:/minecraftrust/tools/bridge/src/com/rustcraft/bridge/capture/SnapshotCapture.java)
- [run-rustcraft-tests.ps1](D:/minecraftrust/tools/run-rustcraft-tests.ps1)
- [README.md](D:/minecraftrust/tools/testing/README.md)
- [run_tests.py](D:/minecraftrust/tools/testing/run_tests.py)
- [test_runner.py](D:/minecraftrust/tools/testing/test_runner.py)
- [owned_snapshot_properties.rs](D:/minecraftrust/crates/native-chunk/tests/owned_snapshot_properties.rs)
- [issue1-clean-forge-capture-proof.md](D:/minecraftrust/docs/research/issue1-clean-forge-capture-proof.md)
- [issue1-clean-forge-offline-report.md](D:/minecraftrust/docs/research/issue1-clean-forge-offline-report.md)
- [issue1-clean-forge-runtime-qualification.md](D:/minecraftrust/docs/research/issue1-clean-forge-runtime-qualification.md)
- [issue1-clean-forge-writer-audit.md](D:/minecraftrust/docs/research/issue1-clean-forge-writer-audit.md)
- [issue1-focused-guard-audit.md](D:/minecraftrust/docs/research/issue1-focused-guard-audit.md)
- [issue1-real-forge-fixtures.md](D:/minecraftrust/docs/research/issue1-real-forge-fixtures.md)
- [owned-capacity-conservative.case](D:/minecraftrust/tests/fixtures/issue1-properties/owned-capacity-conservative.case)
- [log4j2.xml](D:/minecraftrust/tools/forge-capture/log4j2.xml)
- [runtime-pins.json](D:/minecraftrust/tools/forge-capture/runtime-pins.json)
- [ObservationAgent.java](D:/minecraftrust/tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java)
- [OfflineTweaker.java](D:/minecraftrust/tools/forge-capture/src/com/rustcraft/offline/bootstrap/OfflineTweaker.java)
- [QualifyRuntime.java](D:/minecraftrust/tools/forge-capture/src/com/rustcraft/offline/oracle/QualifyRuntime.java)
- [CleanForgeCaptureOracle.java](D:/minecraftrust/tools/forge-capture/src/com/rustcraft/oracle/CleanForgeCaptureOracle.java)
- [OwnedForgeCapture.java](D:/minecraftrust/tools/forge-capture/src/com/rustcraft/oracle/OwnedForgeCapture.java)
- [audit_capture_faults.py](D:/minecraftrust/tools/testing/audit_capture_faults.py)
- [forge_fixture_replay.py](D:/minecraftrust/tools/testing/forge_fixture_replay.py)
- [forge_runtime.py](D:/minecraftrust/tools/testing/forge_runtime.py)
- [test_forge_fixture_replay.py](D:/minecraftrust/tools/testing/test_forge_fixture_replay.py)
- [test_forge_runtime.py](D:/minecraftrust/tools/testing/test_forge_runtime.py)

The tracked pre-existing-file diff is 14 files, 160 insertions and 40 deletions;
new source/test/document files are additional and appear above. No binaries are
tracked. `git diff --check` passes. Changes are ready for review as a bounded
local stage; no authorization for committing this new stage is inferred from the
foundation commit approval.

## R. Final classification

**CLEAN_FORGE_PACKET_CAPTURE_OFFLINE_VALIDATED** — exact initialized clean Forge,
exclusively owned detached input graphs, empty TE callbacks, complete required
semantic/adversarial matrix, resolved focused mutations and reproduced runner.
**Live native packet authority remains ineligible and disabled.**

## S. Medium handoff path

[MEDIUM_HANDOFF_ISSUE1.md](D:/minecraftrust/.rustcraft-local/MEDIUM_HANDOFF_ISSUE1.md)

## T. Exact next task for Medium

Add bounded real-Forge regression cases for the already implemented input-admission and failed-rewrite guards: reject a noncanonical/unregistered logical state before the real container setter, and prove a partially failed in-place rewrite leaves the source poisoned so a later capture cannot succeed. Extend the exact case inventory and tamper tests, then rerun public and forge lanes. Reuse the existing detached ownership contract; do not introduce a live adapter or publication API in this task.

This is a proposed bounded follow-up, not a missing acceptance requirement for the
completed 41-case stage. Live integration first needs an Ultra-reviewed safe
provider-to-owner transfer plus writer exclusion and incarnation-bound publication
primitive; an optimistic copy or endpoint check cannot supply it.

## U. Conditions requiring Ultra again

A new coherence model, unknown writer, transformed-runtime contradiction,
concurrency/happens-before ambiguity, architectural compatibility decision,
unexplained semantic mismatch or any authority-boundary redesign. Medium may
perform tests and mechanical plumbing inside the proven detached scope; it must
not infer live eligibility or begin SHADOW from these fixtures.
