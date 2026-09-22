# Forge Architecture: Global Mutable Static State Inventory

## 1. Executive Summary
Forge 1.12.2 relies heavily on static singletons and global mutable state across the JVM heap. For a native hybrid runtime, this global state represents critical synchronization and ownership boundaries. This document catalogs all primary global mutable state instances, their mutability windows, threading models, and migration hazards.

---

## 2. Global State Catalog

| Singleton / Field | Owning Class | Mutability Window | Primary Access Thread | Thread Safety |
|---|---|---|---|---|
| `MinecraftForge.EVENT_BUS` | `net.minecraftforge.common.MinecraftForge` | Registration: Pre-Init / Init. Dispatch: Tick / Network | Server Thread + Netty threads | Reads thread-safe via volatile arrays; mutations require monitor lock |
| `MinecraftForge.TERRAIN_GEN_BUS` | `net.minecraftforge.common.MinecraftForge` | Registration: Pre-Init. Dispatch: Chunk Generation | Async chunk gen worker threads | Same as EVENT_BUS |
| `MinecraftForge.ORE_GEN_BUS` | `net.minecraftforge.common.MinecraftForge` | Registration: Pre-Init. Dispatch: Chunk Populator | Server Thread | Same as EVENT_BUS |
| `RegistryManager.ACTIVE` | `net.minecraftforge.registries.RegistryManager` | Registration: Pre-Init. Frozen: Post-Init. Remap: World Load | Server Thread (Init / World load) | NOT thread-safe for mutation; thread-safe for reads once frozen |
| `CapabilityManager.INSTANCE` | `net.minecraftforge.common.capabilities.CapabilityManager` | Registration: Pre-Init only | Server Thread (Pre-Init) | Array-based token lookup; frozen after pre-init |
| `OreDictionary` static maps | `net.minecraftforge.oredict.OreDictionary` | Registration: Pre-Init / Init. Query: Game Loop | Server Thread / Mod workers | Synchronized methods on writes; unmodifiable lists returned |
| `GameData` static caches | `net.minecraftforge.registries.GameData` | Pre-Init -> Server Start | Server Thread | Synchronized mutation paths |
| `DimensionManager` | `net.minecraftforge.common.DimensionManager` | Pre-Init -> Server Teardown | Server Thread | Synchronized map structures (`Hashtable` / synchronized blocks) |
| `Loader.instance()` | `net.minecraftforge.fml.common.Loader` | Startup -> Teardown | Server Thread | Mod list frozen after initialization |
| `FMLCommonHandler.instance()` | `net.minecraftforge.fml.common.FMLCommonHandler` | Entire runtime | Server Thread + Async threads | Mixed volatile flags |
| `FluidRegistry` | `net.minecraftforge.fluids.FluidRegistry` | Pre-Init -> Init | Server Thread | Synchronized map structures; ID assignment |

---

## 3. Detailed Subsystem Analysis

### 3.1 `RegistryManager.ACTIVE`
- Stores all active `ForgeRegistry` instances (`blocks`, `items`, `potions`, `biomes`, `enchantments`, `sound_events`).
- **Freeze Invariant**: Once `freeze()` is executed, internal maps (`names`, `ids`, `availabilityMap`) are locked against modification.
- **World Load Remap**: During world loading, `RegistryManager` clones registries, processes world ID tags, remaps IDs, and restores active registries. If Rust assumes static numeric IDs across worlds, corruption occurs.

### 3.2 `CapabilityManager.INSTANCE`
- Stores registered `Capability<T>` tokens in a continuous flat array indexed by integer ID.
- Tokens are statically cached by mods in public static fields (e.g. `@CapabilityInject(IItemHandler.class) public static Capability<IItemHandler> ITEM_HANDLER_CAPABILITY`).
- Invariant: Capability IDs must remain monotonic and immutable after pre-init.

### 3.3 `OreDictionary`
- Contains:
  - `nameToId`: `HashMap<String, Integer>`
  - `idToName`: `ArrayList<String>`
  - `idToStack`: `ArrayList<NonNullList<ItemStack>>`
  - `stackToId`: `HashMap<Integer, List<Integer>>`
- All registrations take a global lock: `synchronized (OreDictionary.class)`. Reads on `getOres()` return `Collections.unmodifiableList()`.
- Thread safety hazard: If mods query `getOres()` on worker threads while another mod registers an ore dynamically during startup, synchronization is strictly required.

### 3.4 `DimensionManager`
- Tracks loaded `WorldServer` instances, dimension providers, and dimension spawn coordinates.
- Internal storage: `Hashtable<Integer, Class<? extends WorldProvider>> providers` and `Hashtable<Integer, WorldServer> worlds`.
- Invariant: Dimension IDs can be negative (Nether: -1, End: 1, Overworld: 0, Modded: arbitrary integers e.g. -2, 7, 100).
- World tick loops iterate over `DimensionManager.getWorlds()`.

---

## 4. Migration Guardrails for Rust Runtime
1. **Never Bypass Freeze Gates**: Any native registry interface must honor Forge's frozen state; late registrations must be rejected identically to Java.
2. **Handle Dynamic World Remaps**: World save files dictate numeric IDs. Rust state containers must accept dynamic ID remappings during world load.
3. **Preserve Volatile Memory Semantics**: `ListenerList` volatile pointer swaps must be respected if native worker threads fire Forge events.
