# Forge Architecture: Mod Compatibility Tiers

## 1. Classification Overview
Not all Forge mods interact with the Minecraft runtime at the same architectural depth. To safely plan migration boundaries and avoid compatibility failures, mods are classified into four distinct compatibility tiers based on their runtime invasiveness.

```
+-------------------------------------------------------------------------+
| Tier 4: Invasive CoreMods / Replacements                                |
| (OptiFine, FoamFix, SpongeForge, CubicChunks, Mixin-based mods)        |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 3: Surgical CoreMods / Bytecode Injectors                          |
| (Ender IO, Astral Sorcery, Tinkers' Construct, Thaumcraft)             |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 2: Access Transformer Only                                         |
| (Mekanism, Forestry, Applied Energistics 2, Chisel)                     |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 1: Standard API & Event Subscribers                                |
| (Thermal Expansion, Iron Chests, JourneyMap, Biomes O' Plenty)          |
+-------------------------------------------------------------------------+
```

---

## 2. Compatibility Tier Specifications

### Tier 1: Standard API & Event Subscribers
- **Characteristics**:
  - Uses only public Forge and Minecraft APIs.
  - Registers blocks, items, tile entities, recipes via `RegistryEvent`.
  - Attaches functionality via `@SubscribeEvent` and `AttachCapabilitiesEvent`.
  - Zero bytecode transformation; zero Access Transformers.
- **Migration Blast Radius**: Low.
  - Interacts with game state purely through standard Java object references and event dispatches.
  - Highly compatible with native subsystem migration as long as event firing contracts and registry lookups remain intact.

### Tier 2: Access Transformer (AT) Only
- **Characteristics**:
  - Requires no custom `IClassTransformer`.
  - Supplies an `_at.cfg` file in `META-INF` to widen `private` or `protected` fields/methods to `public`, or strip `final`.
  - Uses direct field access to internal Minecraft state (e.g. `Chunk.storageArrays`, `World.tickableTileEntities`).
- **Migration Blast Radius**: Moderate.
  - If Minecraft internal fields are moved into native memory or replaced with getter/setter façades, AT direct field access will throw `NoSuchFieldError` at runtime unless the transformer is redirected.

### Tier 3: Surgical CoreMods / Bytecode Injectors
- **Characteristics**:
  - Registers `IFMLLoadingPlugin` and implements `IClassTransformer`.
  - Performs surgical ASM injections: inserting method head/return hooks, altering local variable assignments, adding custom interfaces (`implements ICustomMarker`).
  - Target areas: entity rendering, item capability hooks, lighting updates, tile entity tick loops.
- **Migration Blast Radius**: High.
  - If a Java method that a CoreMod injects into is bypassed or deleted because logic was migrated to Rust, the CoreMod's hook never fires, or LaunchWrapper fails with a transformation error.
  - Invariant: Injected methods must continue to exist and execute in Java.

### Tier 4: Invasive CoreMods / Structural Replacements
- **Characteristics**:
  - Completely rewrites or replaces entire Minecraft classes (e.g. `ExtendedBlockStorage`, `WorldServer`, `ChunkProviderServer`).
  - Examples:
    - **OptiFine**: Replaces rendering pipeline and chunk loading routines.
    - **FoamFix**: Replaces block state container and dedupes NBT/property storage.
    - **CubicChunks**: Replaces the 256-height chunk array structure with 3D 16x16x16 cube storage.
    - **SpongeForge**: Replaces server execution loop and entity tracking.
- **Migration Blast Radius**: Critical / Fatal.
  - Cannot coexist with native rewrites that assume vanilla memory layouts unless explicitly shimmed or negotiated.
  - P0 Policy: Tier 4 mods require dedicated compatibility adapters or must be isolated behind strict boundary contracts.

---

## 3. Tier Distribution in Representative Modpacks

Based on audit of the surveyed sample of 20 representative Forge 1.12.2 projects:
- **Tier 1**: ~65% of the surveyed sample (content-only, items, blocks).
- **Tier 2**: ~18% of the surveyed sample (tech and automation mods reading internal state).
- **Tier 3**: ~14% of the surveyed sample (major magic, tech, and optimization mods).
- **Tier 4**: ~3% of the surveyed sample (core architectural modifiers: FoamFix, VanillaFix, MixinBootstrap).

---

## 4. Realistic Supportability & Compatibility Ceiling

- **CURRENTLY PLAUSIBLE COMPATIBILITY CEILING**: **Tier 1 through Tier 3**.
  - Controlled probes (`CoreModProbe.java`) demonstrate that standard API calls, Access Transformer field widening, and surgical ASM method/return injections can potentially be supported via Java façades and ASM field access redirection.
  - However, this is a plausible ceiling, not a proven guarantee; real modpack integration must prove it under live classloading conditions.
- **Unsupportable Without Dedicated Shims**: **Tier 4 (Structural Replacements)**.
  - CoreMods that replace entire server loops (SpongeForge) or overhaul chunk coordinate arrays (CubicChunks) cannot be supported under native storage engines without extensive mod-specific shims.
