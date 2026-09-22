# Forge 1.12.2 Boot Compatibility Hazard Catalog

## 1. Overview
During the boot process, mods exploit deep JVM capabilities including classloader substitution, ASM bytecode mutation, runtime reflection, and static initialization hooks. Replacing any boot subsystem with Rust requires understanding where compatibility can break.

---

## 2. Hazard Catalog

### Hazard 1: CoreMods & LaunchWrapper Classloader Assumption
- **Severity:** **CRITICAL**
- **Description:** CoreMods expect all game classes to be loaded by `net.minecraft.launchwrapper.LaunchClassLoader`. They assume they can inspect and rewrite bytecode via `IClassTransformer.transform(String name, String transformedName, byte[] basicClass)`.
- **Historical Example:** Mixin library (SpongeForge, Astral Sorcery, Phosphor backports), FoamFix, EnderCore, FastCraft.
- **Risk to Rust Migration:** If Rust replaces a Java class directly (e.g. implementing `Chunk` or `World` in Rust), Java bytecode transformers registered by mods will never see those class bytes or will fail with `ClassNotFoundException`.
- **Mitigation Strategy:** Do not replace Java core classes at the bytecode level during P0/P1. Retain the Java class structure as an FFI facade or pass transformed class metadata to Rust.

---

### Hazard 2: AccessTransformers & Field/Method Visibility Widening
- **Severity:** **HIGH**
- **Description:** Mods specify `_at.cfg` files in their jar manifests. FML's `AccessTransformer` parses these files during boot and mutates the access flags of vanilla classes (e.g. changing `private final` fields to `public` or non-final).
- **Historical Example:** GregTech Community Edition, BuildCraft, Applied Energistics 2 widen internal fields of `Container`, `Slot`, `WorldServer`, and `Chunk` for fast direct field access.
- **Risk to Rust Migration:** If Java fields are moved to Rust native memory, mods that perform direct field reads (`slot.inventory` or `chunk.storageArrays`) will crash with `NoSuchFieldError`.
- **Mitigation Strategy:** Keep authoritative Java field mirrors synchronized or use coarse batch boundaries where field mutation is handled in Java before batching to Rust.

---

### Hazard 3: Static Initializer Ordering (`<clinit>`)
- **Severity:** **HIGH**
- **Description:** Minecraft and Forge classes rely on fragile static initialization order. For example, `Blocks.__<clinit>` calls `Bootstrap.register()`, which populates `Block.REGISTRY`. Referencing `Blocks.AIR` before `Bootstrap.register()` results in null pointer dereference.
- **Historical Example:** Mods referencing `Items.DIAMOND` or `Blocks.STONE` in static fields of classes loaded prematurely by coremods or configuration managers before `Bootstrap.register()`.
- **Risk to Rust Migration:** Calling JNI functions that trigger classloading of Minecraft classes out of order can trigger premature static initialization and corrupt vanilla registries.
- **Mitigation Strategy:** Strictly respect the phase boundary: no Minecraft game classes may be accessed via JNI until Phase 3 (`Bootstrap.register()`) is complete.

---

### Hazard 4: FML Event Ordering Expectations
- **Severity:** **CRITICAL**
- **Description:** Mods register event subscribers on `MinecraftForge.EVENT_BUS` and rely on strict lifecycle event ordering: `NewRegistry` -> `PreInit` -> `Register<T>` -> `Init` -> `IMC` -> `PostInit` -> `Freeze` -> `ServerAboutToStart` -> `ServerStarting` -> `ServerStarted`.
- **Historical Example:** Tinkers' Construct registers materials in PreInit, tool parts in Register<Item>, recipes in Register<IRecipe>, and cross-mod traits in PostInit. If `Register<IRecipe>` fires before `Register<Item>`, recipe construction throws `NullPointerException`.
- **Risk to Rust Migration:** Any Rust subsystem managing items or recipes must synchronize with this exact event cadence and cannot bypass intermediate Java event dispatches.
- **Mitigation Strategy:** Java retains authoritative ownership of lifecycle event dispatch; Rust listens to lifecycle transitions via native bridge hooks.

---

### Hazard 5: Registry Freezing (`GameData.freezeData`)
- **Severity:** **HIGH**
- **Description:** At the conclusion of `Loader.initializeMods()`, Forge invokes `GameData.freezeData()`. All `IForgeRegistry` instances become immutable. Any attempt to register blocks or items after this point throws `IllegalStateException("The registry is frozen")`.
- **Historical Example:** Buggy mods attempting to lazily register items or dimensions during world load or player join.
- **Risk to Rust Migration:** Rust must not attempt to register new block/item IDs after Phase 7. All ID mappings transferred to Rust must be treated as immutable snapshots.
- **Mitigation Strategy:** Take an immutable snapshot of all registries immediately after `GameData.freezeData()` in Phase 7.

---

### Hazard 6: Early Socket Binding vs. Player Connection Handling
- **Severity:** **MEDIUM**
- **Description:** The TCP listening port binds in Phase 6 (`DedicatedServer.init()`), but player logins are blocked by `allowPlayerLogins = false` until Phase 9 (`handleServerStarted()`).
- **Historical Example:** Server query bots, proxy pings (BungeeCord / Waterfall), and monitoring tools hit port 25565 during world generation. If the server drops connections instead of handling status queries, proxy pingers mark the server as offline.
- **Risk to Rust Migration:** If Rust takes over the network socket, it must implement Server List Ping (status response 340) immediately upon binding, while holding or cleanly rejecting login packets until world readiness is signalled.
- **Mitigation Strategy:** Separate status ping packet handling from login packet handling in the Rust transport layer.

---

### Hazard 7: Reflection into Private DedicatedServer Fields
- **Severity:** **HIGH**
- **Description:** Utility and performance mods (e.g. FastFurnace, TickCentral, LagGoggles) reflect into `DedicatedServer` fields such as `pendingCommandList`, `settings`, `rconConsoleSource`, and `networkSystem`.
- **Historical Example:** RCON and console administration mods reflectively inspect `settings` or replace `rconConsoleSource`.
- **Risk to Rust Migration:** If `DedicatedServer` or `MinecraftServer` is replaced with a stub or native proxy, mods attempting reflection will fail with `NoSuchFieldException`.
- **Mitigation Strategy:** Retain standard Java `DedicatedServer` class structure and fields; delegate internal operations rather than deleting fields.

---

### Hazard 8: CoreMod Manifest Attribute Parsing
- **Severity:** **MEDIUM**
- **Description:** FML inspects the `META-INF/MANIFEST.MF` of jars in `mods/` for specific attributes: `FMLCorePlugin`, `FMLCorePluginContainsFMLMod`, `ForceLoadAsMod`, `TweakClass`.
- **Historical Example:** Mods packaging both a coremod and a regular mod in the same jar depend on `FMLCorePluginContainsFMLMod: true`. If omitted or misparsed, the regular mod is ignored.
- **Risk to Rust Migration:** Any mod scanning or discovery mechanism in Rust must adhere byte-for-byte to FML jar manifest parsing rules.
- **Mitigation Strategy:** Keep mod discovery in Java FML during P0/P1; export the discovered mod manifest metadata to Rust via JSON/YAML.
