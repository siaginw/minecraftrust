# Version-neutral core and the bounded 1.12.x adapters

`crates/rustcraft-core` defines new version-neutral identities and spatial
contracts. `versions/mc_1_12_x` owns the deliberately narrow 1.12.1 and 1.12.2
format specializations. Both are members of the root workspace and share its
locked dependency graph. Existing accepted consumers are unchanged by this stage.

This is executable architecture and fixture work. It does not implement a
Minecraft server, full 1.12.1 gameplay, modern Minecraft, Forge loading, modpack
support, region persistence, or production packet authority.

## Contract boundaries

| Concept | New core contract | Legacy adapter constraint |
| --- | --- | --- |
| Vertical coordinates | `SectionCoord(i32)`, floor division for negative block Y | Section-mask conversion requires the exact 0–15 legacy section range |
| World height | Validated `WorldBounds`, signed minimum and explicit section count; block boundaries calculated in i64 | 0–255 block Y / 16 sections is adapter metadata |
| Selected sections | Sorted, unique, checked `SectionSelection` coordinates | `LegacySectionMask(u16)` exists only in the adapter |
| Semantic state | Exact qualified resource identity plus sorted named property pairs | Caller supplies an admitted semantic-to-legacy mapping; parsing/defaults/case folding belong to the version boundary |
| Runtime state | `DenseRuntimeStateId(u32)` within immutable `RegistryEpoch` | Runtime indices never become wire or disk IDs by casting |
| Network state | Distinct `WireStateId(u64)` at the core boundary | Legacy adapter explicitly checks the nonnegative Java-int range |
| Persistence state | Distinct adapter-scoped `PersistenceStateId(u64)`; named formats can use semantic keys | `LegacyStoredState` explicitly checks 12-bit block ID and four-bit metadata; these are not network registry IDs |
| Biomes | Named `BiomeKey`, separate column/volume layout metadata | `LegacyBiomeMap` translates a complete named 256-column input to `[u8; 256]`, rejecting absent/out-of-range mappings |
| Position | `BlockPosition` coordinates, without wire packing | Legacy 26/12/26 signed fields, big-endian encoding, checked range |
| Palette width | No core global setter or singleton | Width is derived from the maximum admitted wire ID in each immutable `LegacyWorldAdapter`, minimum five; no process-global mutation |

Sixteen-cubed sections remain a useful storage primitive. Tests exercise a
24-section world with negative Y, extreme checked coordinate boundaries and
volume-biome metadata. Those tests establish that the contracts can represent
different shapes; they do not provide any modern-version adapter or gameplay.

`VersionCapabilities` describes format shape only. It is separate from H4's
evidence-backed native capability admission. Neither an epoch nor an adapter
selection grants ownership, writer quiescence, coherent capture, or authority.

`RegistryEpoch` contains an issuer-assigned registry identity and nonzero
generation. The issuer must avoid identity reuse within its session/world domain;
generation advancement rejects exhaustion. H6 adds the ownership/session/world
identity envelope. `StateRegistry` is immutable after construction, rejects
duplicate semantic keys and resolves only references from its exact epoch.
Rebuilding the registry may change dense indices without changing semantics.
Raw newtype IDs are values, not independently meaningful external handles.

Mappings supplied to `LegacyWorldAdapter::bind` must cover the registry exactly.
Duplicate semantic mappings, missing/extra entries and out-of-range wire IDs
fail. The caller still has to prove that the mapping reflects the actual admitted
registry: completeness is not a semantic oracle or a profile certificate.
Persistence aliases and network aliases are not assumed to be reversible.

`ResourceKey` preserves exact UTF-8 namespace/path text, split at the first colon.
Only an absent separator is rejected in the core. Case, Unicode, punctuation and
empty components remain representable; this is identity storage, not resource
admission or a filesystem path. Distinct exact text never silently collides
through trimming, replacement or normalization. The current mapping prototype
requires already admitted keys and does not implement a Java-name parser.

The existing qualified Clean Forge 1.12.2 `ResourceLocation` bytecode was inspected
with Java 8 `javap`: its constructor lowercases both components with `Locale.ROOT`
and defaults an empty namespace, but does not impose the modern ASCII character
whitelist. The single-string splitter has its own legacy delimiter behavior.
This corrects the preliminary suspicion that this particular constructor retains
mixed case. [Forge's 1.12 resource documentation](https://docs.minecraftforge.net/en/1.12.x/concepts/resources/)
also describes lowercase resource conventions; it is not a complete specification
of constructor acceptance or registry admission. The precise observed facts,
local class identity and evidence links are recorded in
`versions/mc_1_12_x/fixtures/resource-location-review.json`.

Future extraction must preserve the runtime's already resolved components and
apply its version-specific admission rules before building a registry. Java
strings can contain unpaired UTF-16 surrogates, which this UTF-8 core cannot yet
represent. Such strings must be explicitly rejected, never replacement-normalized,
unless a future lossless code-unit identity representation is introduced. These
representation tests do not qualify arbitrary expanded names for production.

## Specialize outside the inner loop

`SelectedVersion::for_protocol` admits exactly 338 or 340. It never picks a nearby
version. The runnable `dispatch_once` example matches that selection once and
calls a concrete `LegacyWorldAdapter<Mc1_12_1>` or `<Mc1_12_2>` job.

```text
session/world version + immutable registry epoch
       -> exact adapter selection and mapping validation
       -> job admission: epoch and index checks
       -> monomorphized dense-ID mapping loop
       -> distinct wire output or persistence output
```

No trait object, version switch or semantic-string lookup is required per block.
The prototype validates indices when admitting a job and performs ordinary safe
indexed lookup while writing the output. This does not claim eliminated bounds
checks, zero allocation, a speedup or measured negligible abstraction cost.
Mapping-table construction, admission scans and output copying remain costs to
measure in the later end-to-end performance work. Capacity failures publish no
result and leave the destination unchanged; a valid retry is independent.

## Verified 1.12.x differences and fixtures

| Release | Protocol | DataVersion | Keepalive token | Clientbound / serverbound packet IDs |
| --- | --- | --- | --- | --- |
| 1.12.1 | 338 | 1241 | Signed Java int encoded as VarInt | `0x1f` / `0x0b` |
| 1.12.2 | 340 | 1343 | Signed 64-bit big-endian integer | `0x1f` / `0x0b` |

These facts were checked in the primary
[release metadata](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/common/protocolVersions.json),
[1.12.1 schema](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/1.12.1/protocol.json), and
[1.12.2 schema](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/1.12.2/protocol.json)
at the pinned minecraft-data `3.112.0` commit. Both packet directions were
checked. This is primary project schema evidence, not a newly captured Mojang
or Forge runtime session.

The 18 static keepalive vectors cover zero, one/two-byte VarInts, positive and
negative int boundaries, and long values that 1.12.1 cannot represent. Expected
bytes are committed inputs, not generated by the Rust implementation under test.
A separate Java 8 oracle checks them using Java integer arithmetic and JDK
`DataOutputStream.writeLong`. The fixtures include the packet ID but exclude
length framing and compression. Three additional static position vectors check
field placement; Rust tests cover signed bounds and overflow rejection.

The old-version decoder mirrors Java int-shift behavior for up-to-five-byte
VarInts, including non-minimal encodings and discarded excess high bits in the
fifth byte. The field boundary rejects truncation, a continuing fifth byte and
trailing bytes. The 340 decoder requires exactly eight payload bytes. These
small codecs do not form a complete packet state machine or compatibility claim.

Provenance and license details are in
`versions/mc_1_12_x/fixtures/NOTICES.md` and `primary-source-review.json`.
Upstream declares MIT with an extracted-data licensing caveat; no upstream
implementation, complete schema or Minecraft binary is vendored. No new
registry crate or downloaded runtime dependency is installed.

## Validation and evidence scope

From the isolated checkout:

```powershell
python -B versions/mc_1_12_x/tools/run_validation.py --java-home D:\rustcraft-toolchains\temurin8\jdk8u504-b01
```

The runner checks formatting and strict clippy for both named workspace packages, runs
their locked/offline tests (including compile-fail ID separation), runs both
specialized 4,096-state example jobs and verifies unknown-version rejection,
then compiles/runs the independent Java fixture oracle. It checks source,
manifest, fixture and selected tool hashes before/after, retains and hashes raw
output logs, and derives
each test count from its own command log. Machine results are retained under
`machine/architecture-hardening/h5-version-adapters.json` and its evidence directory.
It does not build/load the production FFI DLL or mutate the accepted bridge.
The selected actual Rust toolchain executables are bound separately from rustup
proxies, alongside Python, Git and the named Java executables/runtime files.
This inventory does not claim every system library, MSVC linker/SDK dependency
or environment variable is pinned. The original standalone receipt is retained
as superseded evidence; it hashed only three tool files before execution and
did not establish after-run tool identity.

## Migration wiring still required

1. Root workspace discovery is implemented. Keep the fixture runner in CI as
   well: the Java oracle, examples and compile-fail doctests require their own
   commands and are not all covered by `cargo test --workspace --lib`.
2. Keep existing `core-types` as the explicitly 1.12.2 compatibility surface until
   each consumer is migrated. Existing `BlockStateId(u16)`, native chunk section
   arrays, masks, biomes and palette globals are still present there. This stage
   creates the replacement contract; it does not silently reinterpret old IDs.
3. Extract exact semantic/runtime/wire/persistence/biome mappings from qualified
   registries at the world/session boundary, bind epochs and couple invalidation
   to H4/H6 evidence and ownership. Synthetic mappings are not that extraction.
4. Migrate new native storage and jobs to the core, then implement adapter-bound
   chunk serialization and world-format codecs. The current prototype maps state
   identities; it does not serialize full chunks, palettes or Anvil NBT.
5. Give the existing `protocol` 340 primitives explicit adapter-facing wrappers
   before any consolidation. Retain parity tests and coherent result contracts.
   Implementing a keepalive field does not enable 338 login or gameplay.
6. Move loader/mod-runtime concerns into separate admission and compatibility
   modules when implemented. They do not belong in the core, and no empty facade
   is claimed as their implementation here.

Production gates, MCK6, compression, defaults and the Issue #1 historical
classification remain unchanged. No performance or live coherency conclusion
follows from these architecture fixtures.
