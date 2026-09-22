# Architecture Reference: Forge 14.23.5.x Runtime Architecture

## 1. Executive Summary
The Minecraft Forge runtime for version 1.12.2 (specifically build 14.23.5.2860) transforms Minecraft Java Edition from a closed monolith into an extensible modding platform. This document defines the authoritative architecture of the Forge runtime, its lifecycle stages, class-loading pipeline, event dispatch mechanics, registry architecture, capability subsystem, and inter-mod communication facilities.

In accordance with P0 invariants:
- The Forge runtime is authoritative and currently 100% Java-owned.
- Mod compatibility requires exact preservation of Forge bytecode transformations, event firing order, registry freeze semantics, capability injection, and object reference identity.

---

## 2. LaunchWrapper & CoreMod Loading Architecture
Forge 1.12.2 boots under `net.minecraft.launchwrapper.Launch`, a specialized classloading supervisor.

```
+-------------------------------------------------------------------------+
|                              JVM Startup                                |
+-------------------------------------------------------------------------+
                                     |
                                     v
                 +---------------------------------------+
                 | net.minecraft.launchwrapper.Launch    |
                 +---------------------------------------+
                                     |
             +-----------------------+-----------------------+
             |                                               |
             v                                               v
+-------------------------+                     +-------------------------+
| AppClassLoader (System) |                     | LaunchClassLoader (Tweak)|
| - launchwrapper.jar     |                     | - Transforms bytecode   |
| - asm-debug-all-5.2.jar |                     | - Applies AccessTrans.  |
| - jopt-simple           |                     | - Injects CoreMods      |
+-------------------------+                     +-------------------------+
                                                             |
                                                             v
                                                +-------------------------+
                                                | FMLServerTweaker        |
                                                | CoreModManager          |
                                                +-------------------------+
```

### 2.1 ClassLoader Topology
1. **AppClassLoader (Root)**:
   - Contains LaunchWrapper, ASM 5.2, and basic command-line parsers.
   - Does NOT contain Minecraft or Forge game classes.
2. **LaunchClassLoader (Delegating ClassLoader)**:
   - Loads all game classes, Forge patches, and mod classes.
   - Intercepts `findClass(String name)` calls and routes raw bytecode through an ordered chain of `IClassTransformer` instances.
   - Enforces class transformation exclusions (e.g., `net.minecraftforge.fml.`, `org.objectweb.asm.`).

### 2.2 CoreMod Execution Phases
1. **Discovery**: `CoreModManager` discovers `IFMLLoadingPlugin` classes via JAR manifests (`FMLCorePlugin` attribute).
2. **Tweak Sorting**: CoreMods inject custom `IClassTransformer` instances into `Launch.classLoader.transformers`.
3. **Bytecode Modification**: Every Minecraft class requested is transformed in memory prior to `defineClass`.

---

## 3. Mod Lifecycle State Machine
Mod lifecycle transitions are centrally coordinated by `net.minecraftforge.fml.common.Loader`.

```
           +--------------------+
           |    CONSTRUCTING    |
           +--------------------+
                     |
                     v
           +--------------------+
           |      PRE-INIT      |  <- Register Capabilities, Blocks, Items
           +--------------------+
                     |
                     v
           +--------------------+
           |        INIT        |  <- OreDictionary, Recipes, Network Handlers
           +--------------------+
                     |
                     v
           +--------------------+
           |     POST-INIT      |  <- InterModComms (IMC), Cross-Mod Integration
           +--------------------+
                     |
                     v
           +--------------------+
           |     AVAILABLE      |  <- World Server Startup / Game Loop
           +--------------------+
                     |
                     v
           +--------------------+
           |   SERVER_STARTING  |  <- ServerCommand Registration
           +--------------------+
                     |
                     v
           +--------------------+
           |   SERVER_STARTED   |  <- World Active
           +--------------------+
                     |
                     v
           +--------------------+
           |   SERVER_STOPPING  |  <- World Save / Resource Teardown
           +--------------------+
```

---

## 4. Forge EventBus Dispatch Subsystem
Events form the primary extensibility mechanism in Forge.

### 4.1 Bus Topology
- `MinecraftForge.EVENT_BUS`: Global game events (Entity, World, Block, Item, Chunk).
- `MinecraftForge.TERRAIN_GEN_BUS`: World generation and terrain populator events.
- `MinecraftForge.ORE_GEN_BUS`: Ore generation placement events.
- `FMLCommonHandler.instance().bus()`: Lifecycle and server tick events.

### 4.2 Listener List Compilation
- Subscribers declare `@SubscribeEvent`.
- `ASMEventHandler` compiles dynamic invoker bytecode via ASM (avoiding Java reflection in hot paths).
- Each distinct `Event` class owns a static `ListenerList`.
- `ListenerList` builds a flattened, priority-ordered array (`IEventListener[]`).
- Execution order:
  1. `HIGHEST` (Ordinal 0)
  2. `HIGH` (Ordinal 1)
  3. `NORMAL` (Ordinal 2, default)
  4. `LOW` (Ordinal 3)
  5. `LOWEST` (Ordinal 4)
- Array rebuild occurs during `bus.register()` / `bus.unregister()`.
- Dispatch is a tight sequential loop: `listeners[i].invoke(event)`.

---

## 5. Registry Subsystem (`ForgeRegistry`)
Forge replaces Minecraft's vanilla static registry maps with dynamic, remappable `ForgeRegistry` instances managed by `RegistryManager`.

### 5.1 Architecture & Numeric ID Invariants
- Keys are namespaced `ResourceLocation` (e.g. `minecraft:stone`, `thermalfoundation:ore`).
- Values are assigned integer IDs within allocated ranges (Blocks: 0..4095; Items: 0..31999).
- Modded entries receive dynamically assigned numeric IDs at runtime.
- Save files store string `ResourceLocation` identifiers in `level.dat` (`FML` tag) alongside the local numeric IDs.

### 5.2 Lifecycle Gates
1. **Registration Phase**: Active during `RegistryEvent.Register<T>`. Overrides and additions permitted.
2. **Freeze Phase**: Invoked at end of Pre-Init via `ForgeRegistry.freeze()`. The registry becomes strictly immutable; subsequent `register()` calls throw `IllegalStateException`.
3. **Remap Phase (`loadIds`)**: When loading a world, Forge reads the world's ID table and reconciles numeric mismatches, substituting missing blocks with dummy entries or re-indexing IDs to match the save.

---

## 6. Capability Subsystem (`CapabilityManager`)
Forge provides dynamic, decoupled interface attachment to core game objects (`Entity`, `TileEntity`, `ItemStack`, `World`, `Chunk`) via the Capability system.

### 6.1 Core Components
- `Capability<T>`: Unique typed token representing an interface (e.g. `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY`, `CapabilityEnergy.ENERGY`).
- `ICapabilityProvider`: Interface implemented by carriers to query capabilities.
- `CapabilityDispatcher`: Chained container aggregating multiple providers attached via `AttachCapabilitiesEvent`.
- `IStorage<T>`: Standard serializer/deserializer to/from NBT compound tags.

### 6.2 Sided Query Semantics
Capabilities support directional queries via `EnumFacing` (or `null` for internal/omni-directional access). This enables blocks to expose inventory on top, energy on bottom, and fluids on sides.

---

## 7. OreDictionary
The OreDictionary decouples crafting recipes from specific mod items by mapping generic names (e.g. `oreIron`, `ingotCopper`) to equivalent `ItemStack` entries.

- Keyed by string identifier.
- Wildcard metadata is `Short.MAX_VALUE` (32767).
- Caches bidirectional mappings: `name -> int ID`, `int ID -> List<ItemStack>`, `Item -> List<int ID>`.
- Read-heavy data structure queried repeatedly during crafting and inventory automation.

---

## 8. Authoritative Boundary Invariants
1. **LaunchWrapper Execution**: Rust cannot bypass LaunchWrapper; CoreMods expect standard JVM bytecode manipulation.
2. **EventBus Dispatch**: Firing events on native threads without synchronization corrupts Forge `ListenerList` state.
3. **Reference Equality**: Mods rely on `itemA == itemB` reference identity. Java wrappers around native handles cannot break reference equality.
