# Forge Capability NBT Serialization Architecture

## 1. Unified Capability Framework
Minecraft Forge 14.23.5.x introduces the `Capability` system to replace legacy tile entity and entity interfaces.
Any object that can expose dynamic capabilities implements `ICapabilityProvider` and `ICapabilitySerializable`.

Host objects that serialize capabilities into NBT:
1. `net.minecraft.world.chunk.Chunk`
2. `net.minecraft.entity.Entity`
3. `net.minecraft.tileentity.TileEntity`
4. `net.minecraft.item.ItemStack`
5. `net.minecraft.world.World` / `WorldServer`

---

## 2. In-Memory & Wire Format (`CapabilityDispatcher`)

When an object has capabilities attached (via `AttachCapabilitiesEvent`), Forge instantiates a `CapabilityDispatcher` wrapping all registered providers:

```
Host Object NBT Compound
  └── "ForgeCaps": TAG_Compound
        ├── "enderio:conduit_network": TAG_Compound
        ├── "mekanism:radiation": TAG_Compound
        ├── "baubles:container": TAG_List
        └── "<modid>:<capability_id>": TAG_Compound | TAG_List | TAG_Byte_Array
```

### Serialization Implementation (`CapabilityDispatcher.java`):
```java
@Override
public NBTTagCompound serializeNBT() {
    NBTTagCompound nbt = new NBTTagCompound();
    for (int x = 0; x < writers.length; x++) {
        nbt.setTag(names[x], writers[x].serializeNBT());
    }
    return nbt;
}
```
- Keys are stringified `ResourceLocation` identifiers (`"<modid>:<name>"`).
- Values are arbitrary `NBTBase` tags returned by each provider's `INBTSerializable.serializeNBT()` implementation.

---

## 3. Deserialization & Unknown Capability Preservation

When loading NBT:
```java
@Override
public void deserializeNBT(NBTTagCompound nbt) {
    for (int x = 0; x < writers.length; x++) {
        if (nbt.hasKey(names[x])) {
            writers[x].deserializeNBT(nbt.getTag(names[x]));
        }
    }
}
```

### Critical Modpack Compatibility Hazard:
1. **Dangling Capability Data:** If a world is saved with a mod installed (e.g. `mekanism:radiation`), and then loaded when that mod is disabled or uninstalled, the `"mekanism:radiation"` key remains in the `"ForgeCaps"` compound.
2. **Preservation Obligation:** If the world is subsequently resaved, the unread `"mekanism:radiation"` tag **must not be discarded**. If discarded, re-enabling the mod later results in total data loss for that mod's capabilities across all chunks, entities, and items.
3. **Rust Runtime Seam Rule:** Any Rust chunk or entity codec must treat the `"ForgeCaps"` compound as a transparent polymorphic payload, roundtripping all unrecognized keys without validation or filtering.
