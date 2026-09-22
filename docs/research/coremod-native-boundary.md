# Research: CoreMod & Native Subsystem Boundary Interactions

## 1. The Core Problem
When moving an authoritative subsystem (such as Chunk Storage, Entity Simulation, or Lighting) from Java into native Rust, standard LaunchWrapper class transformers and CoreMods operating in the JVM cannot automatically "reach into" Rust compiled binaries.

If a CoreMod injects an ASM hook into a Java method, but the native runtime replaces the entire call path with a direct Rust function, the CoreMod's hook is silently bypassed.

---

## 2. Interaction Matrix

| Subsystem Candidate | CoreMod Examples | Failure Mode if Replaced Naively | Boundary Mitigation |
|---|---|---|---|
| **Chunk Storage & Block State** | FoamFix, AE2 (Spatial IO), BuildCraft | `NoSuchFieldError` on `ExtendedBlockStorage.data` or missing block change hooks | Retain Java `ExtendedBlockStorage` façade with ASM field accessor redirection |
| **Lighting Calculation** | Phosphor, Dynamic Lights | Bytecode transformation collisions on `World.checkLightFor()` | Allow disabling native lighting if Phosphor is detected, or provide Phosphor-equivalent Rust engine |
| **Entity Tick & Movement** | SpongeForge, CustomNPCs, Morph | Bypassed entity update hooks (`livingUpdate`, collision events) | Keep entity lifecycle loop in Java; offload pathfinding and collision math in coarse batches |
| **TileEntity Ticking** | Mekanism, Ender IO | Broken capability lookups, bypassed energy/inventory transfers | Tile entities remain authoritative Java objects; only raw inventory storage or state arrays move off-heap |

---

## 3. Principles for Native Boundary Placement

1. **Boundary Below Event & Hook Injection Points**:
   - Place native boundaries below the method layers where Forge events fire and CoreMods inject hooks.
   - Example: Instead of replacing `World.setBlockState()`, let `setBlockState()` fire `BlockEvent.BreakEvent` in Java, and delegate the underlying `ExtendedBlockStorage.set()` memory write to native code.
2. **Never Short-Circuit LaunchWrapper Transformers**:
   - Allow CoreMods to run their transformations unimpeded during startup.
   - If a CoreMod mutates a class signature or inserts an interface, the Java façade will retain that interface on the heap.
3. **Cooperative Mod Detection**:
   - The native bridge should inspect the active mod list via `Loader.instance().getActiveModList()`.
   - If a known incompatible Tier 4 CoreMod is present (e.g. CubicChunks), fall back to pure Java compatibility mode for that specific subsystem.
