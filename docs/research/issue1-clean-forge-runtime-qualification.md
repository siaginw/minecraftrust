# Clean Forge 2860 offline runtime qualification

This qualification loads the authorized Minecraft 1.12.2 server and Forge
14.23.5.2860 artifacts through their actual LaunchWrapper/FML transformation
pipeline. It runs Forge's normal load, preinitialization and initialization
phases for the four built-in containers. It does not invoke `MinecraftServer.main`,
start a server thread, accept an EULA, open a world on disk, or transmit packets.
The capture oracle constructs detached objects in this qualified process.

The profile is
`FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`. Registry-only bootstrap
results are explicitly rejected by the final helper. Successful runtime
qualification is a prerequisite for the semantic oracle; it is not by itself a
successful semantic comparison or permission to enable production authority.

## Pinned inputs

`tools/forge-capture/runtime-pins.json` records all 23 ordered classpath artifacts,
the embedded mapping and binary-patch resources, 51 required final class
definitions, the registry identity, 14 final Forge transformers, the ordered
built-in mod containers and the two registered coremod plugin classes.

| Input | SHA-256 |
| --- | --- |
| Minecraft server 1.12.2 | `fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e` |
| Forge 14.23.5.2860 | `cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64` |
| `deobfuscation_data-1.12.2.lzma` | `16dab5e08488d76e503cef215fc4000820f615fd6eaa1bd4450603fce1a0a2e6` |
| `binpatches.pack.lzma` | `ceebaefd4abca814aa0160e71e62c507d63733b7da1773c1268e04ac9a720882` |

The jar manifest supplies the official dependency order. None of the other 22
pinned jar manifests adds another `Class-Path`. Runtime names use FML's embedded
obfuscated-to-SRG mapping; no MCP development jar or independently remapped
Minecraft jar replaces the runtime inputs.

The qualified JVM reports Temurin/OpenJDK `1.8.0_504-b01`. Each receipt additionally
hashes `java.exe`, `javac.exe`, `jvm.dll`, `rt.jar`, `tools.jar`, the exact native
DLL, helper source, logging configuration, Java source files and compiled classes.
The Java bridge classes are compiled into the offline harness; RustCraft's
production coremod is not installed in this profile. The registered coremod
plugins are FMLCorePlugin and FMLForgePlugin from the pinned Forge jar.

## Final class definitions, not source assumptions

`OfflineTweaker` delegates to an actual `FMLServerTweaker` instance, preserving
Forge's own jar location and setup, and replaces only the final launch target.
The initial 13-transformer chain must match exactly. FML subsequently appends
`ModAPITransformer` during normal initialization, giving the qualified final
14-transformer chain.

A passive JVM instrumentation agent observes the actual `classfileBuffer` at
definition time after LaunchWrapper has run its current transformation chain.
It returns `null`, does not modify bytecode, and does not enable class redefinition
or retransformation. The helper supplies the sole Java agent and removes ambient
`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS` and `CLASSPATH`; it records
which variables were removed. This avoids treating an observer registered before
FML's final transformer as a final-byte observer. Required classes are checked
after FML initialization rather than forced to load early for observation.

| Required class | Final definition SHA-256 |
| --- | --- |
| Chunk | `0bd20899a444608e1f49f93a2a0a0b594a5cedb81d7202adfc54fd4e3417e7e3` |
| ExtendedBlockStorage | `b7445c7bd232e8debf2c6ed47438b890d6bd121e406455cdc3cb9e84b487cd23` |
| BlockStateContainer | `9720a0dede53bd654fc6704b4251499f1bf1d853b56cd52f75b465b7956716bc` |
| NibbleArray | `b5556356777ea7c17ecfd1ceec588da0aa8e183ee5305a16438215eaf297285f` |
| SPacketChunkData | `9e8abff74ea6266a4793bfa03bbff9731d2c486c9ff34e5a67b81752ec063354` |
| TileEntity | `5e952e960c2e82f17e5151a305440a7adb4286e69642f5dba3b48dc66eb2d8a0` |
| PacketBuffer | `4a1fbbf4796c068a4317bb15b317a963b2f1f9d0de2fc64cbd718fa98f2281f7` |

Class dumps remain in the local result directory. `qualification.json` binds the
definition hashes, defining classloaders, original jar locations, loaded mod
order, coremod registrations, transformer chain and JVM arguments. Only hashes
and original harness source are tracked in Git.

## Actual Forge initialization and constructor callbacks

After vanilla `Bootstrap.func_151354_b`, qualification invokes the official
`FMLServerHandler.instance().beginServerLoading(null)` and
`finishServerLoading()`. These execute `Loader.loadMods`, `preinitializeMods`
and `initializeMods` without starting a server. The loaded containers are:

1. `minecraft` 1.12.2
2. `mcp` 9.42
3. `FML` 8.0.99.99
4. `forge` 14.23.5.2860

A fresh local `forge.cfg` explicitly disables Forge's version-check mechanism
before this initialization. Its exact bytes are checked before invocation.
This is an offline harness setting; project and production defaults are unchanged.
The receipt inventories the actual generated `forge.cfg` and
`forgeChunkLoading.cfg` afterward.

The detached capture boundary requires the actual inherited
AttachCapabilitiesEvent listener list to be empty. The ChunkEvent.Load listener
list must also be empty. ChunkEvent.Unload has exactly the normal-priority marker
and ForgeInternalHandler.onChunkUnload. Admission checks the actual wrapper class,
its cached `Method` identity and its target reference against
`MinecraftForge.INTERNAL_HANDLER`, in addition to the recorded description.

The entire private `FarmlandWaterManager.customWaterHandler` map must be empty.
Under that condition the transformed unload handler's lookup returns null and
does not invoke virtual water-ticket callbacks or retain the chunk. These guards
run before and after the offline oracle, and the capture adapter invokes them
around its relevant operations. A generic claim that "there are no mods" is not
used as a substitute for inspecting these registrations and state.

## Registry identity and global palette width

This runtime's state identity map has **5,485 logical entries**. Its iterable
contains **1,681 rows**, which collapse to **1,656 decoded ID keys**, with maximum
ID **4,083**. These counts are different because logical states alias decoded IDs.
The actual `func_186804_a()` size is 5,485, giving a global palette width of 13.
Inferring width from the iterable or unique-key count would be incorrect.

Two exact UTF-8 artifacts bind the registry:

- `registry-state-ids.tsv`: sorted decoded ID to canonical state text.
- `registry-logical-states.tsv`: sorted full logical state text to ID, preserving
  all identity-map entries.

The composite identity is SHA-256 of
`RUSTCRAFT_REGISTRY_V1\n<decoded-file-sha256>\n<logical-file-sha256>\n`.
For the qualified target it is
`126bd48d66ba7533fd9ca5b3ce3203b53427075b44ca86d66ba401373eaf2007`.
Oracle fixture metadata also preserves the complete exported iterable and its
hash, rather than inventing a dense ID range or silently dropping aliases.

## Reproduction and result handling

The machine-local `.rustcraft-local/forge-runtime.json` supplies only the restored
server directory:

```json
{"schema_version": 1, "server_root": "D:\\rustcraft-runtime-targets\\clean-forge-2860\\server"}
```

The normal entry point is `tools/run-rustcraft-tests.ps1 forge`. The lower-level
runtime helper accepts:

```text
python -B tools/testing/forge_runtime.py --output <new-result-directory> --java-home <jdk8-home> --dll <exact-built-DLL>
```

`--qualification-only` omits the capture oracle and cannot demonstrate packet
equivalence. The full helper runs a fresh qualification JVM, compiles the oracle
against those locally observed SRG class definitions, then runs the oracle in a
second fresh JVM. Runtime classpath inputs remain the original pinned jars;
observed class dumps are compile-time inputs only. Compiled classes may be reused
after content-hash verification. A DLL change changes the cache identity, and a
fresh JVM is always used even on a cache hit.

The helper's `PASS` means the qualified oracle JVM completed; the runner must
still perform independent semantic replay before reporting its Forge lane as
passed. Missing files produce `INCOMPLETE/MISSING_ARTIFACT`. Incorrect jar,
mapping, class, registry, mod or transformation identities produce
`INCOMPLETE/ARTIFACT_MISMATCH`, never a green substitute runtime.

Ten artifact-independent helper tests cover artifact rejection, mapping identity,
path containment, full-profile admission, unsupported callbacks, cache tampering
and reproducible observer packaging. An actual helper invocation against a
deliberately incorrect Forge jar returned `INCOMPLETE/ARTIFACT_MISMATCH`, exit 2,
before any JVM launch. Earlier failed exploration receipts are retained outside
the repository rather than overwritten or promoted to qualification evidence.

This runtime profile does not prove live-world ownership, exclusion of arbitrary
mod writers, asynchronous chunk-I/O publication, or production packet authority.
Those limits are addressed separately in the writer inventory and capture
contract. Issue #1 and the historical exact-writer investigation remain open.
