# ItemStack NBT Serialization Specification

## 1. Top-Level ItemStack Schema
When an `ItemStack` is serialized (e.g. in player inventory, chests, or entity equipment), it is represented as a compound tag with the following schema:

```yaml
id: String               # Registry name, e.g. "minecraft:diamond_sword"
Count: Byte              # Stack size (1-64; negative values represent empty/invalid)
Damage: Short            # Item damage value / metadata (0-32767)
tag: Compound (Optional) # Custom item dynamic properties and mod metadata
ForgeCaps: Compound (Opt)# Forge capability dispatcher payload
```

---

## 2. Standard `tag` Sub-Compound Tags

The optional `tag` compound contains item-specific metadata:

### `display` Compound
- `Name`: String (custom display name; supports formatting codes `§`).
- `Lore`: List of Strings (tooltip description lines).
- `color`: Int (RGB decimal color for leather armor).

### `ench` & `StoredEnchantments` Lists
A homogeneous `TAG_List` (type 10) containing enchantment definitions:
- `id`: Short (Enchantment numerical ID, e.g. 16 for Sharpness, 34 for Unbreaking).
- `lvl`: Short (Enchantment level, 1-32767).
- `StoredEnchantments`: Used specifically by `minecraft:enchanted_book`.

### `AttributeModifiers` List
A list of compounds modifying player stats when held or worn:
- `AttributeName`: String (e.g. `generic.attackDamage`, `generic.maxHealth`).
- `Name`: String (modifier descriptor).
- `Amount`: Double (numerical modifier).
- `Operation`: Int (0: Additive, 1: Multiplicative base, 2: Multiplicative total).
- `UUIDMost`, `UUIDLeast`: Long (modifier unique ID).
- `Slot`: String (`mainhand`, `offhand`, `head`, `chest`, `legs`, `feet`).

### Block Placement & Adventure Mode
- `CanDestroy`: List of Strings (block IDs permitted to break in Adventure mode).
- `CanPlaceOn`: List of Strings (block IDs permitted to place on).
- `BlockEntityTag`: Compound representing TileEntity state to restore when placed (e.g. Shulker box contents, banner patterns).
- `Unbreakable`: Byte (`1` prevents durability loss).

---

## 3. Forge Capabilities & Network Filtering

1. **`ForgeCaps` Compound:**
   - Forge attaches capabilities to ItemStacks (e.g. `IEnergyStorage`, `IFluidHandlerItem`).
   - Serialized under `"ForgeCaps"` at the root of the item compound.
2. **`Item.getShareTag()` Network Sanitization Hook:**
   - In vanilla, the entire `tag` compound is sent to the client.
   - Forge adds `public NBTTagCompound getShareTag(ItemStack stack)`:
     - Allows mods to intercept and filter out sensitive server-side tags before packet transmission.
     - Also attaches `ForgeCaps` to the packet tag so clients receive synchronized item capability data.
