# Minecraft 1.12.2 / Forge 14.23.5.2860 Boot Sequence

## 1. Evidence Convention
Every phase and invariant in this document cites empirical evidence:
- `[SOURCE]`: Explicit Java source code in `third_party_reference/forge/src` or `third_party_reference/minecraft/src`.
- `[BYTECODE]`: Bytecode inspection or patch diff (`third_party_reference/forge/src/patches/`).
- `[RUNTIME TRACE]`: Output from live instrumented execution (`third_party_reference/forge/server/boot_run.log`).
- `[EXPERIMENT]`: Controlled reproduction or isolated test run.
- `[INFERENCE]`: Deduced behavior from architectural structure where direct runtime logs are silent.

---

## 2. Chronological Boot Phases

### Phase 0: Process Entry & Bootstrap
- **Entry Point:** `net.minecraftforge.fml.relauncher.ServerLaunchWrapper.main(String[] args)` `[SOURCE: ServerLaunchWrapper.java:32]`
- **Thread:** `main`
- **Classloader:** `sun.misc.Launcher$AppClassLoader` (System / Application ClassLoader)
- **Prerequisites:** JVM initialized with Java 8 (`1.8.0_504`), classpath containing `forge-1.12.2-14.23.5.2860.jar`, `minecraft_server.1.12.2.jar`, and dependencies in `libraries/`.
- **What It Does:**
  1. Sets system property `log4j.configurationFile` to `log4j2_server.xml` if not already set. `[SOURCE: ServerLaunchWrapper.java:47]`
  2. Verifies availability of `net.minecraft.launchwrapper.Launch` and `org.objectweb.asm.Type`. `[SOURCE: ServerLaunchWrapper.java:52-53]`
  3. Prepend `--tweakClass net.minecraftforge.fml.common.launcher.FMLServerTweaker` to argument array. `[SOURCE: ServerLaunchWrapper.java:67-68]`
  4. Dynamically invokes `Launch.main(String[] allArgs)` via reflection. `[SOURCE: ServerLaunchWrapper.java:70]`
- **State Created/Mutated:** JVM system properties set; `Launch` entry invoked.
- **Transformers Active:** None.
- **Forge Hooks:** None.
- **Compatibility Significance:** Process entrypoint in production server scripts (`java -jar forge-...-universal.jar`).
- **Future Rust Significance:** Native wrapper or launcher must emulate or interface with LaunchWrapper before Java classes are transformed.

---

### Phase 1: LaunchWrapper Initialization & Tweaker Chain
- **Entry Point:** `net.minecraft.launchwrapper.Launch.main(String[] args)` `[SOURCE: Launch.java:35]`
- **Thread:** `main`
- **Classloader:** `AppClassLoader` constructs `net.minecraft.launchwrapper.LaunchClassLoader` (URLClassLoader subclass).
- **Prerequisites:** Phase 0 complete.
- **What It Does:**
  1. Creates `LaunchClassLoader` using URLs from `AppClassLoader`.
  2. Sets `Thread.currentThread().setContextClassLoader(launchClassLoader)`.
  3. Initializes `Launch.blackboard` (shared untyped property map).
  4. Instantiates primary tweaker: `net.minecraftforge.fml.common.launcher.FMLServerTweaker`. `[RUNTIME TRACE: line 2-3]`
  5. Calls `tweaker.acceptOptions(...)`.
  6. Calls `tweaker.injectIntoClassLoader(launchClassLoader)`:
     - Adds classloader exclusions: `com.mojang.util.QueueLogAppender`, `org.jline.`, `com.sun.jna.`, `net.minecraftforge.server.terminalconsole.`. `[SOURCE: FMLServerTweaker.java:56-61]`
     - Calls `FMLLaunchHandler.configureForServerLaunch(classLoader, this)`. `[SOURCE: FMLServerTweaker.java:62]`
- **State Created/Mutated:** `LaunchClassLoader` active; `FMLLaunchHandler` singleton created; `FMLInjectionData` populated (`major=14`, `minor=23`, `rev=5`, `build=2860`).
- **Transformers Active:** None yet registered.
- **Forge Hooks:** `FMLLaunchHandler.configureForServerLaunch`
- **Compatibility Significance:** LaunchClassLoader controls all subsequent class definition; transformers can intercept any class not excluded.
- **Future Rust Significance:** Rust runtime replacing JVM components will not pass through LaunchClassLoader unless bridging through JNI.

---

### Phase 2: CoreMod Discovery & Early Transformer Registration
- **Entry Point:** `net.minecraftforge.fml.relauncher.FMLLaunchHandler.setupHome()` -> `CoreModManager.handleLaunch(...)` `[SOURCE: FMLLaunchHandler.java:107]`
- **Thread:** `main`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 1 complete.
- **What It Does:**
  1. Checks if runtime is deobfuscated by attempting `classLoader.getClassBytes("net.minecraft.world.World")`. In production, returns null (`deobfuscatedEnvironment = false`). `[SOURCE: CoreModManager.java:204-209]`
  2. Injects cascading tweak: `FMLInjectionAndSortingTweaker`. `[SOURCE: CoreModManager.java:231]`
  3. Registers `PatchingTransformer` (applies binary diffs to vanilla Minecraft classes from `binpatches.pack.lzma`). `[SOURCE: CoreModManager.java:234]`
  4. Loads fundamental root plugins:
     - `FMLCorePlugin`: registers `SideTransformer`, `EventSubscriptionTransformer`, `EventSubscriberTransformer`, `SoundEngineFixTransformer`, and `AccessTransformer`. `[SOURCE: FMLCorePlugin.java:29-40]`
     - `FMLForgePlugin`: sets up runtime deobf flags and Forge code source location. `[SOURCE: FMLForgePlugin.java:35-58]`
  5. Scans `mods/` and `mods/1.12.2/` jar manifests for `FMLCorePlugin` and `FMLCorePluginContainsFMLMod`. `[SOURCE: CoreModManager.java:326-450]`
  6. Discovers coremod candidates, parses `AccessTransformers` jar manifest entries, and registers them.
  7. Injects cascading tweak: `FMLDeobfTweaker` (registers `DeobfuscationTransformer` with priority 1000). `[SOURCE: CoreModManager.java:632-633]`
  8. Injects `TerminalTweaker` for JLine console formatting. `[RUNTIME TRACE: line 16]`
- **State Created/Mutated:** Transformer pipeline fully assembled in `LaunchClassLoader.transformers`.
- **Transformers Active:**
  1. `PatchingTransformer`
  2. `AccessTransformer`
  3. `SideTransformer`
  4. `EventSubscriptionTransformer`
  5. `EventSubscriberTransformer`
  6. `SoundEngineFixTransformer`
  7. Third-party CoreMod ASM transformers
  8. `DeobfuscationTransformer` (runs last in chain to map Notch bytecode -> SRG before other transformers mutate it).
- **Forge Hooks:** `IFMLLoadingPlugin` callbacks; `IFMLCallHook.injectData` and `call()`.
- **Compatibility Significance:** **CRITICAL**. Any mod using ASM (e.g. Mixin, FoamFix, EnderCore) mutates vanilla classes during this phase.
- **Future Rust Significance:** Rust cannot directly consume ASM bytecode transformers. Any replacement of Java classes must either incorporate known transformer behaviors or preserve the transformed bytecode representation.

---

### Phase 3: Minecraft Server Process Entry
- **Entry Point:** `net.minecraft.launchwrapper.Launch.launch(String[] args)` -> invokes `net.minecraft.server.MinecraftServer.main(String[] args)` `[SOURCE: Launch.java:135]`
- **Thread:** `main` (transitions to `Server thread` at end of phase)
- **Classloader:** `LaunchClassLoader` (transforms `MinecraftServer` and `DedicatedServer` upon classloading)
- **Prerequisites:** Phase 2 complete. All tweakers processed.
- **What It Does:**
  1. Calls `Bootstrap.register()`:
     - Registers vanilla blocks, items, potions, enchantments, sound events, biomes, stats. `[SOURCE: Bootstrap.java:60-150]`
     - Registers dispenser behaviors.
  2. Parses command-line flags (`--nogui`, `--port`, `--world`, `--universe`). `[SOURCE: MinecraftServer.java:635-666]`
  3. Initializes authentication infrastructure:
     - `YggdrasilAuthenticationService`
     - `MinecraftSessionService`
     - `GameProfileRepository`
     - `PlayerProfileCache` `[SOURCE: MinecraftServer.java:668-671]`
  4. Instantiates `DedicatedServer` object:
     - Constructor passes `universe`, `DataFixesManager.createFixer()`, and auth repositories to `MinecraftServer` super-constructor. `[SOURCE: DedicatedServer.java:62]`
     - Spawns daemon `Thread("Server Infinisleeper")` with `Thread.sleep(Long.MAX_VALUE)`. `[SOURCE: DedicatedServer.java:66]`
  5. Calls `server.startServerThread()`:
     - Instantiates `this.serverThread = new Thread(this, "Server thread")`. `[SOURCE: MinecraftServer.java:712]`
     - Calls `serverThread.start()`.
  6. Registers JVM shutdown hook `Thread("Server Shutdown Thread")`. `[SOURCE: MinecraftServer.java:700]`
  7. `main` thread terminates; control continues solely on `Server thread`. `[RUNTIME TRACE: line 18-19]`
- **State Created/Mutated:** `DedicatedServer` object allocated; `Server thread` running `MinecraftServer.run()`.
- **Transformers Active:** Active during classloading of `MinecraftServer`, `Bootstrap`, `Blocks`, `Items`.
- **Forge Hooks:** `Bootstrap.register()` has patch hooks.
- **Compatibility Significance:** High. `MinecraftServer.main` creates foundational singletons and passes execution to `Server thread`.
- **Future Rust Significance:** Rust runtime entrypoint will replace `MinecraftServer.main` or execute as the authoritative server thread.

---

### Phase 4: DedicatedServer Initialization & FML Mod Discovery
- **Entry Point:** `MinecraftServer.run()` calls `DedicatedServer.init()` `[SOURCE: MinecraftServer.java:540, DedicatedServer.java:85]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 3 complete.
- **What It Does:**
  1. Spawns daemon `Thread("Server console handler")` for stdin CLI reading. `[SOURCE: DedicatedServer.java:86-102]`
  2. Forge patch hook: `FMLCommonHandler.instance().onServerStart(this)`: `[BYTECODE: DedicatedServer.java.patch:23]`
     - Invokes `FMLServerHandler.instance().beginServerLoading(this)`. `[SOURCE: FMLServerHandler.java:95]`
     - Invokes `Loader.instance().loadMods(...)`:
       - `progressBar.step("Constructing Mods")`
       - Transitions `modController` to `LoaderState.LOADING`. `[SOURCE: Loader.java:567]`
       - Calls `identifyMods(...)`: scans `mods/` directory for `@Mod` annotations and `mcmod.info`. `[SOURCE: Loader.java:568]`
       - Identifies mods (in clean run: `minecraft`, `mcp`, `FML`, `forge`). `[RUNTIME TRACE: line 25]`
       - Reads `@Mod` metadata, builds dependency graph, topologically sorts mod list. `[SOURCE: Loader.java:572]`
       - Creates `ModContainer` instances (`FMLModContainer`, `MCPDummyContainer`, etc.).
       - Loads config data: `ConfigManager.loadData(discoverer.getASMTable())`. `[SOURCE: Loader.java:592]`
       - Transitions `modController` to `LoaderState.CONSTRUCTING`.
       - Emits `FMLConstructionEvent` to all mod containers. Mod `@Mod.EventHandler` constructors execute. `[SOURCE: Loader.java:595]`
- **State Created/Mutated:** `LoaderState.CONSTRUCTING`; Mod containers created; config directories created.
- **Transformers Active:** Classloading new mod classes.
- **Forge Hooks:** `FMLCommonHandler.onServerStart`, `Loader.loadMods`.
- **Compatibility Significance:** **CRITICAL**. Mod classes are loaded into memory and mod dependency order is established.
- **Future Rust Significance:** Rust mod discovery or FFI must preserve dependency ordering and `@Mod` container semantics.

---

### Phase 5: FML Pre-Initialization & Early Registry Events
- **Entry Point:** `FMLServerHandler.beginServerLoading()` -> `Loader.instance().preinitializeMods()` `[SOURCE: FMLServerHandler.java:99, Loader.java:618]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 4 mod construction complete.
- **What It Does:**
  1. Transitions to `LoaderState.PREINITIALIZATION`.
  2. `GameData.fireCreateRegistryEvents()`: fires `RegistryEvent.NewRegistry` allowing mods to register custom `IForgeRegistry`. `[SOURCE: Loader.java:625]`
  3. `ObjectHolderRegistry.INSTANCE.findObjectHolders(asmTable)`: locates `@ObjectHolder` annotations. `[SOURCE: Loader.java:626]`
  4. `ItemStackHolderInjector.INSTANCE.findHolders(asmTable)`: locates `@ItemStackHolder` annotations. `[SOURCE: Loader.java:627]`
  5. `CapabilityManager.INSTANCE.injectCapabilities(asmTable)`: registers all `@CapabilityInject` interfaces. `[SOURCE: Loader.java:628]`
  6. Emits `FMLPreInitializationEvent`: mod `@EventHandler` methods receive config directory, mod metadata, and suggested configuration file. `[SOURCE: Loader.java:629]`
  7. `GameData.fireRegistryEvents(rl -> !rl.equals(GameData.RECIPES))`:
     - Fires `RegistryEvent.Register<Block>`
     - Fires `RegistryEvent.Register<Item>`
     - Fires `RegistryEvent.Register<Potion>`
     - Fires `RegistryEvent.Register<Biome>`
     - Fires `RegistryEvent.Register<SoundEvent>`
     - Fires `RegistryEvent.Register<Enchantment>`
     - Fires `RegistryEvent.Register<VillagerProfession>`
     - Fires `RegistryEvent.Register<EntityEntry>` `[SOURCE: Loader.java:630]`
  8. `ObjectHolderRegistry.INSTANCE.applyObjectHolders()`: populates static block/item fields from registry entries. `[SOURCE: Loader.java:632]`
  9. `ItemStackHolderInjector.INSTANCE.inject()`. `[SOURCE: Loader.java:633]`
  10. Transitions to `LoaderState.INITIALIZATION`.
- **State Created/Mutated:** All Forge registries instantiated and populated with mod/vanilla blocks and items.
- **Transformers Active:** Active during classloading of mod item/block definitions.
- **Forge Hooks:** `RegistryEvent.NewRegistry`, `FMLPreInitializationEvent`, `RegistryEvent.Register<T>`.
- **Compatibility Significance:** **CRITICAL**. The vast majority of mod items, blocks, capabilities, and configurations are registered here.
- **Future Rust Significance:** Rust world and block subsystems must reflect this exact registry mapping and ID allocation.

---

### Phase 6: Properties, Keypair & Network Socket Binding
- **Entry Point:** `DedicatedServer.init()` resumes after `FMLCommonHandler.onServerStart` `[SOURCE: DedicatedServer.java:108]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 5 complete.
- **What It Does:**
  1. Loads `server.properties` (`PropertyManager`). `[SOURCE: DedicatedServer.java:109]`
  2. Verifies `eula.txt` (`ServerEula.hasAcceptedEULA()`). Exits if false. `[SOURCE: DedicatedServer.java:110-115]`
  3. Configures server settings: `online-mode`, `pvp`, `spawn-animals`, `spawn-npcs`, `difficulty`, `gamemode`. `[SOURCE: DedicatedServer.java:119-140]`
  4. Generates RSA 1024-bit keypair (`CryptManager.generateKeyPair()`). `[SOURCE: DedicatedServer.java:152]`
  5. Network socket binding:
     - `this.getNetworkSystem().addEndpoint(inetAddress, port)`. `[SOURCE: DedicatedServer.java:156]`
     - Creates Netty `ServerBootstrap`, configures channel type (NIO / Epoll on Linux), attaches Netty channel initializer with `LegacyPingHandler` and `NetworkManager`. `[SOURCE: NetworkSystem.java:66-100]`
     - Binds socket to specified IP and port (e.g. `*:25565`). `[RUNTIME TRACE: line 37]`
- **State Created/Mutated:** TCP listening socket open; Netty event loops active; RSA keypair stored on server.
- **Transformers Active:** None.
- **Forge Hooks:** None in this slice.
- **Compatibility Significance:** High. Socket is open and will accept raw TCP handshakes, but players cannot yet authenticate.
- **Future Rust Significance:** CANDIDATE — UNVALIDATED. Potential target for Rust network transport replacement (`transport` crate), but unvalidated until dedicated networking research examines Netty pipeline, NetworkManager, encryption, compression, FML handshake, SimpleNetworkWrapper, and mod channel handler injection.

---

### Phase 7: FML Initialization, Post-Initialization & Data Freeze
- **Entry Point:** `DedicatedServer.init()` calls `FMLCommonHandler.instance().onServerStarted()` `[BYTECODE: DedicatedServer.java.patch:32]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 6 complete (socket bound).
- **What It Does:**
  1. Calls `FMLServerHandler.instance().finishServerLoading()`. `[SOURCE: FMLServerHandler.java:106]`
  2. Calls `Loader.instance().initializeMods()`:
     - `CraftingHelper.loadRecipes(false)`: loads JSON and code crafting recipes. `[SOURCE: Loader.java:747]`
     - Fires `RegistryEvent.Register<IRecipe>`.
     - Emits `FMLInitializationEvent`: mods register world generators, event handlers, and inter-mod integrations. `[SOURCE: Loader.java:749]`
     - Transitions to `LoaderState.POSTINITIALIZATION`. `[SOURCE: Loader.java:751]`
     - Emits `FMLInterModComms.IMCEvent`: mods exchange cross-mod messages. `[SOURCE: Loader.java:752]`
     - Emits `FMLPostInitializationEvent`: final cross-mod state checks (e.g., checking if Forestry/Thermal are loaded). `[SOURCE: Loader.java:754]`
     - Transitions to `LoaderState.AVAILABLE`. `[SOURCE: Loader.java:756]`
     - Emits `FMLLoadCompleteEvent`. `[SOURCE: Loader.java:757]`
     - Calls `GameData.freezeData()`: **freezes all registries**. Registry additions after this call throw exceptions. `[SOURCE: Loader.java:758]`
  3. Instantiates `DedicatedPlayerList(this)`. `[SOURCE: DedicatedServer.java:217]`
  4. Reads user whitelist, ops list, IP banlist, and player banlist from JSON files.
- **State Created/Mutated:** `LoaderState.AVAILABLE`; all Forge registries frozen; `PlayerList` active.
- **Transformers Active:** None.
- **Forge Hooks:** `FMLInitializationEvent`, `IMCEvent`, `FMLPostInitializationEvent`, `FMLLoadCompleteEvent`.
- **Compatibility Significance:** **CRITICAL**. Mod setup is complete; registries are immutable.
- **Future Rust Significance:** Marks the point where read-only registry snapshots can safely be transferred across FFI to Rust.

---

### Phase 8: Server About To Start & World Loading
- **Entry Point:** `DedicatedServer.init()` calls `FMLCommonHandler.instance().handleServerAboutToStart(this)` `[BYTECODE: DedicatedServer.java.patch:40]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 7 complete.
- **What It Does:**
  1. Emits `FMLServerAboutToStartEvent`. Mods may inspect server parameters before world generation starts. `[SOURCE: Loader.java:292]`
  2. Calls `this.loadAllWorlds(saveName, saveName, seed, worldType, generatorOptions)`: `[SOURCE: MinecraftServer.java:311]`
     - Instantiates `AnvilSaveHandler` for world directory.
     - Loads `level.dat` (`WorldInfo`).
     - Constructs Overworld: `worlddata = new WorldServer(this, saveHandler, worldInfo, 0, profiler)`. `[SOURCE: MinecraftServer.java:343]`
     - Fires `WorldEvent.Load(worldServer)`.
     - Initializes dimension providers: `DimensionManager.initDimension(0)`.
     - Loads secondary dimensions: Dim -1 (Nether) via `WorldServerMulti`, Dim 1 (The End) via `WorldServerMulti`. `[RUNTIME TRACE: line 49-51]`
     - Calls `this.initialWorldChunkLoad()`: `[SOURCE: MinecraftServer.java:380]`
       - Generates/loads spawn chunk area (25x25 chunk grid, 625 chunks around spawn point `[x-192..x+192, z-192..z+192]` block offset, step 16). `[SOURCE: MinecraftServer.java:271-282]`
       - Emits progress logs: `Preparing spawn area: 29%`, `66%`. `[RUNTIME TRACE: line 53-54]`
- **State Created/Mutated:** `WorldServer` instances created for dims 0, -1, 1; spawn chunks loaded in memory; chunk providers running.
- **Transformers Active:** None.
- **Forge Hooks:** `FMLServerAboutToStartEvent`, `WorldEvent.Load`, `ChunkEvent.Load`.
- **Compatibility Significance:** **CRITICAL**. World objects, dimension managers, and chunk storage now exist.
- **Future Rust Significance:** Boundary where Rust world simulation / chunk storage (`region-io`, `world-storage`) replaces Java `WorldServer` / `ChunkProviderServer`.

---

### Phase 9: Server Ready & Transition to Tick Loop
- **Entry Point:** `DedicatedServer.init()` finishes; control returns to `MinecraftServer.run()` `[SOURCE: DedicatedServer.java:303, MinecraftServer.java:543]`
- **Thread:** `Server thread`
- **Classloader:** `LaunchClassLoader`
- **Prerequisites:** Phase 8 complete.
- **What It Does:**
  1. Calls `FMLCommonHandler.instance().handleServerStarting(this)`. `[BYTECODE: DedicatedServer.java.patch:56]`
     - Emits `FMLServerStartingEvent`: mods register custom server commands (`/forge`, mod commands) into `ServerCommandManager`. `[SOURCE: Loader.java:797]`
  2. Logs completion message: `Done (4.244s)! For help, type "help" or "?"`. `[RUNTIME TRACE: line 55]`
  3. Returns `true` from `DedicatedServer.init()`.
  4. Inside `MinecraftServer.run()`:
     - Prepares `ServerStatusResponse` (MOTD, protocol 340, player count). `[SOURCE: MinecraftServer.java:547-550]`
     - Injected Forge hook: `FMLCommonHandler.instance().handleServerStarted()`. `[BYTECODE: MinecraftServer.java.patch:102]`
     - Emits `FMLServerStartedEvent`. `[SOURCE: Loader.java:804]`
     - Injected Forge hook: `DedicatedServer.allowPlayerLogins = true;`. `[SOURCE: FMLCommonHandler.java:303]`
  5. Enters main execution loop:
     ```java
     while (this.serverRunning) {
         long k = getCurrentTimeMillis();
         long j = k - this.currentTime;
         // Tick logic...
         this.tick();
     }
     ```
     `[SOURCE: MinecraftServer.java:565-600]`
- **State Created/Mutated:** `DedicatedServer.allowPlayerLogins = true`; Server ready for player handshake and packets; tick timing loop running.
- **Transformers Active:** None.
- **Forge Hooks:** `FMLServerStartingEvent`, `FMLServerStartedEvent`.
- **Compatibility Significance:** This exact point marks the transition from static/boot initialization to dynamic game loop ticking.
- **Future Rust Significance:** **AUTHORITATIVE BOUNDARY BETWEEN P0-1 (BOOT) AND P0-2 (TICK PIPELINE)**.
