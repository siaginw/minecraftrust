# Compatibility: Mod Reflection Surface & ObfuscationReflectionHelper

## 1. Overview & Mechanics
In Minecraft 1.12.2, reflection is widely used by mods to access non-public fields and methods in vanilla Minecraft and Forge without requiring an Access Transformer or CoreMod.

Forge provides `net.minecraftforge.fml.common.ObfuscationReflectionHelper` to simplify reflective access across both development (deobfuscated MCP names) and production (Searge/SRG and Notch obfuscated names).

---

## 2. Common Reflection Patterns

### 2.1 Access via SRG Field Names
```java
Field f = ObfuscationReflectionHelper.findField(
    ExtendedBlockStorage.class, 
    "field_177488_d" // SRG name for 'data' (BlockStateContainer)
);
f.setAccessible(true);
BlockStateContainer bsc = (BlockStateContainer) f.get(storage);
```

### 2.2 Reflection Caching
Most high-performance mods do not invoke `Class.getDeclaredField()` repeatedly; they perform reflection lookups once during static initialization or Pre-Init and cache the resulting `Field` or `Method` objects in static variables.

### 2.3 Hot Targets for Mod Reflection
- `Chunk.storageArrays` (`field_76652_q`)
- `ExtendedBlockStorage.data` (`field_177488_d`)
- `World.worldInfo` (`field_72986_A`)
- `EntityPlayerMP.connection` (`field_71135_a`)
- `MinecraftServer.tickCounter` (`field_71315_w`)
- `TileEntity.pos` (`field_174879_c`)

---

## 3. Reflection Hazards Under Native Migration
1. **`NoSuchFieldError` on Renamed or Removed Fields**:
   - If internal fields are removed during native migration because logic moved to Rust, cached reflection lookups during mod startup will throw `ReflectionHelper.UnableToFindFieldException` or `NoSuchFieldError`.
2. **Type Cast Exceptions**:
   - If `field_177488_d` (`data`) is modified to point to a native pointer `long` instead of a `BlockStateContainer`, mods casting the reflected field value will throw `ClassCastException`.
3. **Field Mutation Disconnect**:
   - If a mod uses reflection to mutate a field (e.g. setting `field_177488_d` to a custom container), but the native runtime continues reading from an off-heap native buffer, the mod's change is silently ignored, causing subtle desynchronization bugs.

---

## 4. Invariants for Native Runtime
- **Preserve Field Types and Presence**: Any field targeted by common mod reflection must remain physically present on the Java object with its expected declared type.
- **Bi-Directional Synchronization**: If a field is mutated via reflection, the Java façade must detect or reconcile this mutation before passing state back to native buffers.
