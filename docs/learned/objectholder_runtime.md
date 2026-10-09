# Learned: Forge @ObjectHolder Runtime & Field Injection

## 1. Overview & Purpose
The `@ObjectHolder` annotation (`net.minecraftforge.fml.common.registry.GameRegistry.ObjectHolder`) allows Forge mods and vanilla Minecraft to declare public static final references to registry entries (Blocks, Items, Enchantments, Potions, Biomes) that are automatically populated at runtime by Forge's `ObjectHolderRegistry`.

---

## 2. Mechanical Behavior

### 2.1 Declaration Patterns
1. **Class-Level Annotation**:
   ```java
   @ObjectHolder("thermalfoundation")
   public class ModItems {
       public static final Item material = null; // Injected as thermalfoundation:material
   }
   ```
2. **Field-Level Annotation**:
   ```java
   public class ModBlocks {
       @ObjectHolder("minecraft:stone")
       public static final Block STONE = null;
   }
   ```

### 2.2 Reflection Magic & `final` Stripping
- In standard Java semantics, `public static final` fields cannot be mutated after class initialization.
- Forge's `ObjectHolderRegistry` uses JVM reflection and `Field.setAccessible(true)`:
  - It locates the field's `modifiers` field in `java.lang.reflect.Field`.
  - It clears the `Modifier.FINAL` bitmask (`field.setModifiers(modifiers & ~Modifier.FINAL)`).
  - It writes the active registry entry into the field.
- **Java 8 Compatibility**: This reflection hack succeeds without warning in Java 8 HotSpot. In Java 9+ (Jigsaw modules), modifying `modifiers` via reflection throws an exception unless `--add-opens` is supplied.

### 2.3 Injection Timing & Lifecycle
`ObjectHolderRegistry.applyObjectHolders()` is invoked multiple times during startup:
1. After vanilla bootstrap registration.
2. After mod pre-initialization registry events.
3. After registry freeze.
4. After world load remapping.

### 2.4 Delegate Reference Binding
- Under the hood, injected fields point directly to the object registered in the `ForgeRegistry`.
- If an override mod substitutes a block (e.g. replacing vanilla stone with a custom stone block), `ObjectHolderRegistry` updates all `@ObjectHolder` fields to point to the new replacement instance.

---

## 3. Compatibility Invariants for Native Runtime
1. **Mod Code Relies on Direct Static Field References**:
   - Mods frequently access `Blocks.STONE` or `ModItems.MY_ITEM` without calling `ForgeRegistries.BLOCKS.getValue()`.
   - Any hybrid runtime must ensure that all standard `@ObjectHolder` fields in `net.minecraft.init.Blocks` and `Items` are populated with valid Java instances.
2. **Reference Identity Preservation (`==`)**:
   - Because mods access objects directly via `@ObjectHolder`, checks like `stack.getItem() == Items.IRON_INGOT` use direct pointer comparison (`acmp_eq`).
   - Rust cannot substitute wrapper proxies that break Java reference identity.
