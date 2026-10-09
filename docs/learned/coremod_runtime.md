# Learned: CoreMod Architecture & LaunchWrapper Bytecode Pipeline

## 1. Overview & Execution Pipeline
A "CoreMod" in Forge 1.12.2 is an advanced mod that modifies compiled Minecraft or Forge bytecode in memory before classes are loaded by the JVM. CoreMods bypass standard Forge API restrictions to implement low-level optimizations, custom rendering, event hooks, or architectural overhauls.

---

## 2. CoreMod Loading Lifecycle

```
JVM Start -> LaunchWrapper -> Discover IFMLLoadingPlugin -> Register IClassTransformer -> Class Loading Pipeline
```

### 2.1 Registration Interface (`IFMLLoadingPlugin`)
CoreMod entry points implement `net.minecraftforge.fml.relauncher.IFMLLoadingPlugin`:
- `getASMTransformerClass()`: Returns array of class names implementing `IClassTransformer`.
- `getModContainerClass()`: Returns accompanying mod container class (or null).
- `getSetupClass()`: Returns setup hook implementing `IFMLCallHook`.
- `injectData(Map<String, Object> data)`: Receives runtime environment flags (e.g. `coremodLocation`, `mcLocation`, `runtimeDeobfuscationEnabled`).

### 2.2 Class Transformation Pipeline (`IClassTransformer`)
When `LaunchClassLoader.findClass(name)` is called:
1. Raw class bytecode is retrieved from the classpath or JAR file.
2. Bytecode is piped sequentially through every registered transformer:
   ```java
   for (IClassTransformer transformer : transformers) {
       bytes = transformer.transform(untransformedName, transformedName, bytes);
   }
   ```
3. Final modified bytecode is passed to `defineClass()` to register the class in the JVM.

---

## 3. Common CoreMod Transformation Patterns

| Pattern | Typical Purpose | Examples | Impact on Native Runtime |
|---|---|---|---|
| **Method Head Hook** | Logging, profiling, canceling vanilla logic | SpongeForge, CraftTweaker | Requires method to exist; harmless if method runs |
| **Method Return Hook** | Altering return values (e.g. custom light level) | OptiFine, Dynamic Lights | Requires target method to execute in Java |
| **Method Replacement** | Completely replacing vanilla algorithms | FoamFix, Phosphor | Overrides entire Java implementation |
| **Field Addition** | Storing custom metadata directly on entities/chunks | Ender IO, Mixin | Adds fields to Java object; harmless to native |
| **Interface Injection** | Adding interface markers for fast `instanceof` | Thaumcraft, Astral Sorcery | Synthesizes interface implementation in bytecode |
| **Direct Field Rewriting** | Replacing `GETFIELD`/`PUTFIELD` instructions | SpongeForge, FoamFix | Intercepts field accesses |

---

## 4. Invariants & Migration Boundaries
1. **Never Remove Hooked Methods**:
   - If a method migrated to native code is called by vanilla Minecraft, the Java method declaration must remain as an entry point so CoreMod transformers find their injection targets.
2. **Handle Obfuscation & SRG Names**:
   - CoreMods written for production 1.12.2 search for Searge (SRG) names (e.g. `func_76597_o`, `field_76652_q`) or obfuscated Notch names (`a`, `b`).
   - Any Java stub or façade must retain standard SRG/Notch naming compatibility.
