# Bytecode Symbol Index — Minecraft 1.12.2 / Forge navigation

`tools/symbols/` indexes the actual bytecode universe (vanilla, Forge-patched,
runtime-transformed, mods) plus the complete notch → SRG → MCP mapping chain
into one SQLite database, so bytecode/mapping questions become one-second
queries instead of jar tf / javap / grep / SRG-CSV archaeology.

- CLI: `python tools/symbols/rustcraft_symbols.py <command>`
- DB: `target/symbol-index/rustcraft-symbols.sqlite` (gitignored; ~365 MB)
- Config: `tools/symbols/sources.json` (candidate paths, committed; override
  with `--sources`)
- Tests: `python tools/symbols/self_test.py` (unit suite always; live
  sanity re-derives the canonical light identities when the index exists)
- Rebuild (about 35 s, all layers): `python tools/symbols/rustcraft_symbols.py build`
- Incremental rebuild: `build --incremental` — per-artifact SHA cache
  (goal §2): unchanged artifacts (file SHA-256 + spec + mappings digest)
  are copied with row ids preserved; only changed/new artifacts are
  re-parsed. Indexing a whole mod directory: add an artifact spec with
  `"mods_dir": "<path>"` — every jar under it (recursive) becomes its own
  MOD_JAR artifact, which is what makes per-artifact caching pay off on a
  195-jar pack.

Check availability/provenance first with `status` — if the index is missing or
stale (sources changed), rebuild it; the build only reads, it never touches
the engine or any campaign.

## Question → command cheat sheet

| Question | Command |
| --- | --- |
| What is `World.checkLightFor` in SRG/notch? | `map World.checkLightFor` |
| What descriptor does `func_XXXXX_a` have? | `method func_XXXXX_a` |
| Exact method card (all names, layers, hashes, counts) | `method net.minecraft.world.World.checkLightFor` |
| What calls this method? | `callers <ident>` (`--depth N` for transitive) |
| What does this method call? | `callees <ident>` |
| Which fields/methods does this class have? | `class <name> --members` |
| Which mod overrides this collision/light/tick method? | `overrides <method>` |
| What is the Forge-patched version? | `forge-changes <class>` (per-method hash vs vanilla), `method <ident>` shows every layer's code hash |
| What did the live JVM actually define? | `method <ident>` (LIVE_TRANSFORMED hash), `live-diff <class>` |
| Which classes reference `EnumSkyBlock`? | `refs net.minecraft.world.EnumSkyBlock` |
| Who reads/writes this field? | `field-readers` / `field-writers` / `field` |
| Where does the string `.mca` / constant 8192 appear? | `strings .mca`, `constant 8192` |
| Fuzzy "where is this implemented?" | `search "check light"` |
| Raw SQL escape hatch | `sql "SELECT ..."` (read-only) |
| Everything about the index itself | `status`, `sources` |

Identifier forms accepted: `pkg.to.Class.member`, `pkg.to.Class.func_XXXXX_x`,
`pkg.to.Class.member(pkg.to.Arg;...)Ret` (descriptor-qualified), bare
`func_XXXXX_x` (unique searge), or `Class.member` with the short class name
when it is unambiguous. Bare MCP names that hit several classes are reported
as AMBIGUOUS with the candidate list — always qualify. Descriptors are shown
on every result.

## Layers (never collapse them)

| Layer | Bytes | Names in bytecode | Provenance |
| --- | --- | --- | --- |
| `VANILLA_NOTCH` | shipped dedicated server jar | notch (`amu`, `a`) | Mojang 1.12.2; **anchor** for cross-layer resolution |
| `VANILLA_CLIENT_NOTCH` | shipped client jar | notch | Mojang 1.12.2 |
| `FORGE_PATCHED` | Forge 14.23.5.2847 binpatched merged jar | notch | vanilla bytes + Forge binary patches |
| `FORGE_PATCHED_MCP` | Forge 14.23.5.2864 mapped recompiled jar | MCP (`checkLightFor`) | human-readable; recompiled, so code hashes differ everywhere — do not use it for byte-level diffs |
| `FORGE_MOD` | Forge universal 14.23.5.2846 | SRG members | `net.minecraftforge.*` itself |
| `LIVE_TRANSFORMED` | dumped classes of a real JVM session | SRG classes + SRG members | what LaunchClassLoader actually defined: vanilla + Forge-2860 runtime-applied binary patches + mod coremods; partial coverage (only dumped classes); machine-local under `target/` |
| `MOD_JAR` | one artifact per mod | mod's own names, SRG refs to MC | Phosphor 0.2.7, JourneyMap 5.5.5, RustCraft campaign coremod by default; extend `sources.json` for more |

Every class/method/field row belongs to exactly one artifact; the same method
in five layers is five rows sharing one `identity_key` (canonical class + SRG
member name + canonical descriptor). Code-array SHA-256s are recorded per
method, which is what makes `forge-changes` / `live-diff` / cross-layer
comparison possible.

## Naming model (1.12.2 specifics that bite)

- **Class names**: notch in shipped jars (`amu`); `net/minecraft/world/World`
  in SRG/MCP namespaces — SRG and MCP class names are identical for 1.12.2.
  The index stores both (`internal_name`, `canonical_name`, `notch_name`) and
  canonicalizes at build time via `joined.srg` CL lines.
- **Method/field names**: notch single letters (`amu.c`) → SRG
  (`func_180500_c`) → MCP (`checkLightFor`) via `joined.srg` MD lines +
  MCP snapshot `20171003` CSVs. Notch names are heavily overloaded, so the
  notch→SRG map is keyed by (class, name, **descriptor**).
- **Descriptors**: notch artifacts reference notch types (`Lana;`); the index
  stores the bytecode descriptor *and* the type-renamed canonical descriptor.
- **Enum constants / compiler-named fields** keep their names through SRG
  (`EnumSkyBlock.BLOCK` is `BLOCK` in every namespace; only the class remaps).
- **Worked example (ground truth, not folklore)**: `checkLightFor` is
  `func_180500_c` `(Lnet/minecraft/world/EnumSkyBlock;
  Lnet/minecraft/util/math/BlockPos;)Z`. `func_175638_a` is a *different*
  method: `getRawLight` `(Lnet/minecraft/util/math/BlockPos;
  Lnet/minecraft/world/EnumSkyBlock;)I` ("gets the light level at the
  supplied position"). Never assume a mapping from memory — query `map`.

## Honest limits

- Call/field edges are **direct bytecode references**. `invokevirtual`/
  `invokeinterface` edges may hit a runtime override at dispatch; the
  `overrides` command finds same-identity methods in subclasses as separate
  evidence. Unknown dispatch is not guessed.
- Methodref owners are compile-time receiver types; the builder resolves
  inherited targets through the class hierarchy (a second pass), and edges
  that still cannot resolve (mostly JDK classes) stay unresolved rather than
  being guessed.
- `LIVE_TRANSFORMED` diffing vs `VANILLA_NOTCH` mixes Forge-2860 runtime
  patches with mod coremods; pass `--base FORGE_PATCHED` to `live-diff` to
  isolate the residual delta (remaining differences include 2847→2860 Forge
  skew — treat pair-diffs as signals, per-layer hashes as ground truth).
- `search` is deterministic token ranking (names, class names, string
  constants, a small alias list), not semantic understanding.
- The index is derived, machine-local evidence for navigation — not an
  authority artifact; it does not certify runtime behavior and never replaces
  campaign receipts.

## Provenance and reproducibility

`status` prints, and the DB `meta`/`artifacts`/`mapping_sources` tables store:
every artifact's resolved absolute path, SHA-256, layer, runtime identity
(mc_version / forge_version), entry count, parse error count, and every
mapping file's SHA-256. `provenance` prints the per-layer runtime identity
table alone.

**Provenance guard (goal §31)**: `forge-changes` and `live-diff` compare the
two layers' runtime identities and print a PROVENANCE WARNING when both
carry a `forge_version` and the versions differ (the LIVE_TRANSFORMED dump
was captured under Forge 2860 while FORGE_PATCHED is built from 2847 —
differences may reflect the version skew, not real patches), or when both
carry campaign/session provenance and it differs (different capture
sessions are different runtimes). Vanilla baseline layers carry no
forge_version and are exempt. `--strict-provenance` turns the warning into
exit code 2. `live-diff <class> --base FORGE_PATCHED` remains the way to
isolate runtime-only deltas, but read its output with the skew in mind.

## Override signals (goal §17/§18 — opacity/emission audit)

`symbols signals <method>` lists every subclass override of a method with
its per-method evidence (deduped calls, field accesses, type refs, string
constants) and a SUGGESTED classification — `STATIC` / `WORLD_DEPENDENT` /
`TILEENTITY_DEPENDENT` / `UNKNOWN` — from deterministic keyword rules
(super calls excluded; world-aware calls like getLightOpacity/getTileEntity/
getBlock trigger the WORLD/TILEENTITY classes). The suggestions are
evidence-shaped hints, NOT semantic understanding — goal §18 requires
verification before admitting a state to Rust authority. Descriptors are
optional in the query (`Class.meth(...)` with no return type prefix-matches
overloads). Works on any method; run it on the full-Revelation index to see
mod overrides of `getLightOpacity` / `getLightValue`.

## Reflection contract (`reflect` — the shape-assumption killer)

`symbols reflect <class>.<field>` answers the question behind the
reflection-shape bug class (writing reflection that assumes List when the
runtime object is a registry): the field's DECLARED type resolved to
canonical (e.g. `BLOCK_STATE_IDS` is declared `ObjectIntIdentityMap` — a
List cast can never work), the type's hierarchy (supers + indexed
subclasses — Forge assigns subclass instances into vanilla-declared
fields), and the callable method inventory across the type family.

Known limit (stated in the output): the subclass closure covers indexed
artifacts only; Forge-side branches may be incomplete. The runtime class
of a field is provable via `field-writers` -> `body` of the writer (what
gets PUT into the field — e.g. Block.<clinit> for BLOCK_STATE_IDS, whose
Forge-patched body constructs the registry).

## Method body inventory (`body` — the javap replacement)

`symbols body <method>` re-parses the one class file from the indexed
artifact and prints the ordered instruction inventory — calls, field
accesses, type ops, string/numeric constants — with canonical names
(`--raw` for bytecode names; `--layer` to pick whose bytes). This is what
every `javap -c -p ... > /tmp/x.txt` + awk session in the light work was
actually after, e.g. the discovery that `checkLightFor` profiles under
"getBrightness", reads `getLightFor`, then nests `getRawLight` as its value
kernel:

    python tools/symbols/rustcraft_symbols.py body net.minecraft.world.World.func_180500_c

Field listings (`fields <class>`, `class --members`) now show static-final
ConstantValue — `fields ...LightingEngine` prints `lX=26 lY=8 lZ=26 sX=26
sY=52 sZ=0` directly (the bit-width-vs-mask discovery that cost two boots,
in one second).

## Mixin wiring (goal §10 — "who mixins into this class")

`symbols mixins <target-class>` answers the §10 question from the index:
every `@Mixin` class targeting it (annotation targets captured from the
indexed jars, any namespace), with priority, kind (class reference vs
string target), jar provenance, and whether the mixin class itself is
indexed — plus the registered `mixins.<modid>.json` configs (the
registered-vs-present distinction that the §10 Phosphor chase kept
needing). Validated on the real pack: Phosphor's `mixins.phosphor.json`
(9 mixins) and `MixinWorld → net.minecraft.world.World` resolve directly:

    python tools/symbols/rustcraft_symbols.py mixins net.minecraft.world.World

Schema v3 adds the `mixins` and `mixin_configs` tables (a v2→v3 rebuild is
one full re-parse; afterwards `--incremental` reuses unchanged artifacts).

Rebuilding from the same sources yields the same facts; if a source changes
(e.g. a re-captured transformed dump), the hashes in `status` make that
visible. Generated DBs are not committed (AGENTS.md: no generated machine
evidence in git).
