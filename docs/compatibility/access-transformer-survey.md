# Compatibility: Access Transformer (AT) Ecosystem Survey

## 1. Survey Scope & Methodology
This survey audits Access Transformer (`_at.cfg`) directives across top-tier Forge 1.12.2 mods to determine which Minecraft classes and fields are most frequently exposed to direct mod access.

Audited mods include:
- Ender IO
- Applied Energistics 2 (AE2)
- Mekanism
- Tinkers' Construct
- Forestry
- Botania
- Astral Sorcery
- Immersive Engineering
- Chisel
- Twilight Forest

---

## 2. High-Frequency AT Targets

| Minecraft Class | Target Field / Method | Purpose in Mod Ecosystem | Migration Risk |
|---|---|---|---|
| `net.minecraft.world.chunk.Chunk` | `storageArrays` (`field_76652_q`) | Direct inspection and bulk extraction of chunk sections | **CRITICAL**: Breaking this array breaks AE2 spatial IO and chunk analysis tools |
| `net.minecraft.world.chunk.storage.ExtendedBlockStorage` | `data` (`field_177488_d`) | Direct block state container inspection | **CRITICAL**: Replaced by FoamFix, queried by world-gen mods |
| `net.minecraft.world.World` | `loadedEntityList` (`field_72996_f`) | Fast entity iteration without copying | **HIGH**: Mod spatial queries iterate loaded entities directly |
| `net.minecraft.world.World` | `loadedTileEntityList` (`field_147482_g`) | Fast tile entity indexing | **HIGH**: Tech mods inspect tile entities directly |
| `net.minecraft.world.WorldServer` | `allChunkCheck` (`field_73068_P`) | Chunk tick optimizations | **MEDIUM**: Chunk loading managers |
| `net.minecraft.entity.Entity` | `isImmuneToFire` (`field_70178_ae`) | Custom combat and damage handling | **MEDIUM**: Custom armor / effects |
| `net.minecraft.inventory.Container` | `inventorySlots` (`field_75151_b`) | GUI slot inspection and item routing | **LOW**: Purely client/server GUI logic |

---

## 3. Migration Architectural Rules
1. **Preserve `Chunk.storageArrays` Representation**:
   - Mods using ATs to access `Chunk.storageArrays` expect `ExtendedBlockStorage[]` to exist and be populated.
   - If block storage is moved off-heap to Rust, `storageArrays` must either contain Java façade objects implementing `ExtendedBlockStorage` or maintain a synchronized shadow array.
2. **Preserve `World.loadedEntityList` and `loadedTileEntityList`**:
   - Entities and TileEntities must remain accessible via standard Java `List` collections to prevent `NullPointerException` or `NoSuchFieldError` in Tier 2 mods.
