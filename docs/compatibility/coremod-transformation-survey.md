# Compatibility: CoreMod Transformation Ecosystem Survey

## 1. Executive Summary
CoreMods are prevalent in Forge 1.12.2 modpacks. This survey catalogs the exact bytecode transformation targets and mechanisms employed by the most influential 1.12.2 CoreMods.

Audited CoreMods:
- **FoamFix** (Bogdan-G / asiekierka)
- **Ender IO** (SleepyTrousers)
- **Astral Sorcery** (HellFirePvP)
- **Tinkers' Construct** (SlimeKnights)
- **Thaumcraft** (Azanor)
- **Phosphor** (jellysquid3)
- **MixinBootstrap / SpongeForge** (SpongePowered / mod authors)

---

## 2. Transformation Inventory

| Mod Name | Target Classes | Transformation Technique | Primary Purpose | Fatal Hazard Level |
|---|---|---|---|---|
| **FoamFix** | `BlockStateContainer`, `ExtendedBlockStorage`, `NBTTagCompound` | Replaces `BlockStateContainer` implementation with deduplicated single-array palette container; rewrites `cleanEmpty()` | RAM reduction via palette optimization and string deduplication | **CRITICAL**: Conflicts with any native replacement of `BlockStateContainer` unless coordinated |
| **Ender IO** | `Item`, `ItemStack`, `EntityLivingBase` | Injects method head calls to handle item capability transfers, custom entity rendering, and armor protection | Conduit item routing, dynamic capability attachment | **HIGH**: Injected methods must continue executing on Java thread |
| **Phosphor** | `World`, `Chunk`, `ExtendedBlockStorage` | Replaces lighting calculation methods with optimized graph-based propagation | Lighting calculation performance | **CRITICAL**: Replaces lighting loops in Java; incompatible with native lighting unless bypassed |
| **Astral Sorcery** | `World`, `EntityPlayer`, `EntityItem` | Injects hooks for custom rendering, starlight crafting raycasts, and constellation buffs | Starlight mechanics | **MEDIUM**: Method head/return injections |
| **Tinkers' Construct** | `Item`, `ItemStack` | Method return modification for attack speed and damage calculations | Tool modularity | **LOW**: Standard method return hooks |
| **SpongeForge / Mixin** | `MinecraftServer`, `WorldServer`, `PlayerList` | Mixin injectors modifying method bodies, injecting event posts, redirecting field access | Server API, async multi-threading, permission management | **FATAL**: Modifies top-level server control loop; incompatible with wholesale server loop replacement |

---

## 3. Analysis & Compatibility Boundaries
1. **The FoamFix / Native Storage Dilemma**:
   - FoamFix replaces `BlockStateContainer` bytecode with its own `DeduplicatedBlockStateContainer`.
   - If Rust replaces chunk block storage, it must either run with FoamFix disabled (since Rust native flat storage already provides superior memory efficiency) or provide an adapter that satisfies FoamFix's class transformers.
2. **The Phosphor Lighting Collision**:
   - If lighting calculations are moved to Rust (P3 target), Phosphor's bytecode transformer on `World` and `Chunk` will attempt to patch lighting methods that may no longer be authoritative.
   - Recommended strategy: Provide a configurable toggle to disable Java lighting transformers when native lighting is enabled.
