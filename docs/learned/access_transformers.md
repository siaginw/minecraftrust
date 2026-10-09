# Learned: Forge Access Transformers (AT) Specifications & Mechanics

## 1. Overview & Function
Access Transformers (`net.minecraftforge.fml.common.asm.transformers.AccessTransformer`) are declarative bytecode transformation directives used by Forge and third-party mods to modify access modifiers of Minecraft fields, methods, and classes at classload time.

---

## 2. File Format & Directives

### 2.1 File Location
Mod AT files are placed inside the JAR under `META-INF/*_at.cfg` and declared in `META-INF/MANIFEST.MF` via the attribute:
```manifest
FMLAT: modid_at.cfg
```

### 2.2 Directive Syntax
```
<access-level> <target-type> [signature]
```
- **Access Modifiers**:
  - `public`: Widen access to `public`.
  - `protected`: Widen access to `protected`.
  - `default`: Widen access to package-private.
  - `private`: Set access to `private` (rarely used).
- **Final Modifiers**:
  - `-f`: Strip `final` modifier (makes field or method mutable / overrideable).
  - `+f`: Add `final` modifier (rarely used).

### 2.3 Directive Examples
1. **Class Access**:
   ```
   public net.minecraft.world.chunk.storage.ExtendedBlockStorage
   ```
2. **Field Access & Final Stripping**:
   ```
   public-f net.minecraft.world.chunk.Chunk field_76652_q # storageArrays
   ```
3. **Method Access**:
   ```
   public net.minecraft.world.WorldServer func_73046_t()V # resetUpdateEntityTick
   ```

---

## 3. LaunchWrapper Execution Flow
1. During LaunchWrapper startup, `AccessTransformer` parses all discovered `*_at.cfg` files into hash maps keyed by class name.
2. When `LaunchClassLoader` requests a class, `AccessTransformer.transform(String name, String transformedName, byte[] basicClass)` executes:
   - It reads the class bytecode using ASM `ClassReader`.
   - For every field and method matching an AT rule, it mutates the `access` bitmask using bitwise operations:
     ```java
     node.access = (node.access & ~0x7) | targetAccess;
     if (stripFinal) node.access &= ~Opcodes.ACC_FINAL;
     ```
   - It writes the modified bytecode back via ASM `ClassWriter`.

---

## 4. Architectural Impact on Native Migration
- Mods rely on ATs to read and mutate private Minecraft fields directly (e.g. reading `Chunk.storageArrays` or `Entity.isImmuneToFire`).
- If an internal Minecraft field is removed or replaced with an off-heap native pointer, any mod that targeted that field with an AT will fail with `NoSuchFieldError` upon loading or executing.
- **Rule**: Classes exposed to ATs must preserve their public field layout, even when backed by native memory façades.
