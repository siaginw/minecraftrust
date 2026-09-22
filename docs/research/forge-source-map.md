# Forge 14.23.5.x Source Map & Structural Architecture

## 1. Source Repository Metadata
- **Repository:** `https://github.com/MinecraftForge/MinecraftForge.git`
- **Branch:** `1.12.x`
- **Checked-out Commit:** `3effde4f1fc9d14d6ed1dbf6bebc39c2b18780e1`
- **Build Target:** `14.23.5.2864` (tip of `14.23.5.x` release line, fully binary-compatible with reference `14.23.5.2860`)
- **License:** LGPL-2.1 (MinecraftForge) / GPL-3.0 (LaunchWrapper components)
- **Local Location:** `third_party_reference/forge/src/`
- **CodeGraph Status:** Indexed (880 files, 24,082 nodes, 48,501 edges in 1.4s)

## 2. Directory Layout & Module Responsibilities

```text
third_party_reference/forge/src/
├── src/main/java/net/minecraftforge/
│   ├── fml/
│   │   ├── common/             # FML core engine: Loader, ModContainer, EventBus, StateEngine
│   │   │   ├── event/          # FML lifecycle events (PreInit, Init, PostInit, ServerStarting)
│   │   │   ├── eventhandler/   # EventBus, Event, IEventListener, ListenerList, SubscribeEvent
│   │   │   ├── network/        # SimpleNetworkWrapper, FMLEmbeddedChannel, Packet Handshake
│   │   │   ├── registry/       # GameRegistry, EntityRegistry, VillagerRegistry
│   │   │   └── discovery/      # DirectoryDiscoverer, ContainerDiscoverer, ASMDataTable
│   │   ├── relauncher/         # CoreMod bootstrap, LaunchWrapper tweaker, CoreModManager
│   │   ├── server/             # FML dedicated server handler (FMLServerHandler)
│   │   └── client/             # FML client integration (GUI, splash, client handlers)
│   ├── common/                 # Forge server/world systems
│   │   ├── capabilities/       # CapabilityManager, Capability, ICapabilityProvider
│   │   ├── config/             # Configuration parser, Property, ConfigCategory
│   │   ├── util/               # BlockSnapshot, FakePlayer, WorldEvent hooks
│   │   ├── DimensionManager.java  # Multi-world & dimension lifecycle registration
│   │   ├── ForgeChunkManager.java # Chunk ticketing & persistent loading system
│   │   └── MinecraftForge.java    # EVENT_BUS, TERRAIN_GEN_BUS, ORE_GEN_BUS root instances
│   ├── event/                  # Gameplay event taxonomy
│   │   ├── entity/             # LivingUpdateEvent, EntityJoinWorldEvent, ItemTossEvent
│   │   ├── world/              # ChunkEvent (Load/Unload/Save), WorldEvent, BlockEvent
│   │   ├── terraingen/         # Biome decoration and terrain generation hooks
│   │   └── CommandEvent.java   # Server command interception
│   ├── registries/             # Forge dynamic registries
│   │   ├── ForgeRegistry.java  # Active backing store for Blocks, Items, Biomes, Sounds
│   │   ├── GameData.java       # Registry freeze/unfreeze, ID allocation, sync packets
│   │   └── RegistryBuilder.java# API for mods to register custom registries
│   ├── items/                  # ItemStack capability wrappers (IItemHandler)
│   ├── fluids/                 # Fluid registry, FluidStack, IFluidHandler capability
│   ├── energy/                 # Forge Energy capability (IEnergyStorage)
│   ├── oredict/                # OreDictionary string tag grouping for recipes
│   └── server/                 # Dedicated server console & permission system
└── patches/minecraft/          # Unified diffs injected into decompiled Mojang classes
    └── net/minecraft/
        ├── server/             # MinecraftServer.java.patch (tick hooks, save hooks)
        ├── world/              # WorldServer.java.patch, Chunk.java.patch, World.java.patch
        ├── entity/             # Entity.java.patch, EntityLivingBase.java.patch
        └── tileentity/         # TileEntity.java.patch (capability attachment, tick guards)
```

## 3. Key Subsystem Call Paths & Integration Points

### A. FML Lifecycle & State Engine
- **Class:** `net.minecraftforge.fml.common.LoadController` & `Loader`
- **Mechanism:** Transitions through state machine:
  `CONSTRUCTING` -> `PREINITIALIZATION` -> `INITIALIZATION` -> `POSTINITIALIZATION` -> `AVAILABLE` -> `SERVER_ABOUT_TO_START` -> `SERVER_STARTING` -> `SERVER_STARTED`
- **Hot Path Risk:** Low at runtime; critical during boot. Mods register blocks and capabilities during PreInit/Init.

### B. Event Bus Architecture
- **Class:** `net.minecraftforge.fml.common.eventhandler.EventBus`
- **Instance:** `net.minecraftforge.common.MinecraftForge.EVENT_BUS`
- **Mechanism:** Methods annotated with `@SubscribeEvent` are registered into `ListenerList`. Event dispatch uses generated dynamic invocations (`ASMEventHandler`) for speed.
- **Hot Path Risk:** High. Ticks, block updates, entity movements, and chunk events post to `EVENT_BUS`.

### C. Registries & ID Allocation
- **Class:** `net.minecraftforge.registries.ForgeRegistry` & `GameData`
- **Mechanism:** Bidirectional mapping between `ResourceLocation` names and integer IDs. Forge dynamically injects IDs into palettes to ensure client/server sync during login.
- **Hot Path Risk:** Block state and item lookups rely on `ForgeRegistry.getID(V)`.

### D. Capabilities System
- **Class:** `net.minecraftforge.common.capabilities.CapabilityManager`
- **Mechanism:** Attaches type-safe interfaces (`ICapabilityProvider`) to `TileEntity`, `Entity`, `World`, `Chunk`, and `ItemStack` without class inheritance.
- **Hot Path Risk:** Critical. Tech modpacks query capabilities (item transfer, energy, fluids) thousands of times per server tick.

### E. LaunchWrapper & Coremods
- **Class:** `net.minecraftforge.fml.relauncher.CoreModManager`
- **Mechanism:** Implements `net.minecraft.launchwrapper.ITweaker`. Loads coremods specified in manifest `FMLCorePlugin`, sorting bytecode `IClassTransformer` instances before normal classloading.
- **Hot Path Risk:** Severe compatibility risk. Coremods can alter any bytecode assumption.

### F. Server World Patches
- **Class:** `patches/minecraft/net/minecraft/world/WorldServer.java.patch`
- **Mechanism:** Injects Forge chunk manager tickets, entity activation range checks, and custom dimension callbacks into the standard Minecraft tick.
