# Forge 14.23.5.2860 Dedicated Server Boot Call Flow

```text
========================================================================================================================
THREAD: [main] | CLASSLOADER: sun.misc.Launcher$AppClassLoader
========================================================================================================================

[PROCESS ENTRY]
       │
       ▼
ServerLaunchWrapper.main(args)
       │  Sets "log4j.configurationFile" = "log4j2_server.xml"
       │  Validates Launch and ASM presence on AppClassLoader
       │  Prepends: "--tweakClass net.minecraftforge.fml.common.launcher.FMLServerTweaker"
       ▼
net.minecraft.launchwrapper.Launch.main(allArgs)
       │  Creates LaunchClassLoader(urls)
       │  Sets Thread contextClassLoader = launchClassLoader
       │  Loads and initializes tweak classes
       ▼
FMLServerTweaker.acceptOptions(args, gameDir, assetsDir, profile)
       │
       ▼
FMLServerTweaker.injectIntoClassLoader(launchClassLoader)
       │  Adds classloader exclusions (org.jline., com.sun.jna., QueueLogAppender)
       │  Calls FMLLaunchHandler.configureForServerLaunch(launchClassLoader, this)
       │         │
       │         ▼
       │  FMLLaunchHandler.setupServer() -> setupHome()
       │         │  Redirects STDOUT / STDERR to Log4j TracingPrintStream
       │         │  LibraryManager.setup(minecraftHome)
       │         │  CoreModManager.handleLaunch(mcDir, launchClassLoader, tweaker)
       │         │         │
       │         │         ├─ Injects FMLInjectionAndSortingTweaker
       │         │         ├─ Registers PatchingTransformer (binpatches.pack.lzma)
       │         │         ├─ Loads root plugins: FMLCorePlugin, FMLForgePlugin
       │         │         │    └─ Registers: SideTransformer, EventSubscriptionTransformer,
       │         │         │                 EventSubscriberTransformer, AccessTransformer
       │         │         ├─ Discovers coremods in mods/ and mods/1.12.2/ (manifest scan)
       │         │         ├─ Injects FMLDeobfTweaker -> DeobfuscationTransformer (SRG runtime map)
       │         │         └─ Injects TerminalTweaker
       │         ▼
       │  FMLLaunchHandler.appendCoreMods() -> injectTransformers(launchClassLoader)
       ▼
Launch.launch(args)
       │  Finds target class: "net.minecraft.server.MinecraftServer"
       │  Transforms MinecraftServer & DedicatedServer bytecode through LaunchClassLoader
       │  Invokes MinecraftServer.main(args) via reflection
       ▼
MinecraftServer.main(args)
       │  Bootstrap.register() (vanilla block/item/biome registries)
       │  Parses CLI arguments (--port, --world, --universe, nogui)
       │  Initializes YggdrasilAuthenticationService, SessionService, PlayerProfileCache
       │  Instantiates DedicatedServer object
       │  Calls DedicatedServer.startServerThread()
       │         │  Spawns new Thread("Server thread") running MinecraftServer.run()
       │         │  Starts "Server thread"
       │  Registers JVM shutdown hook ("Server Shutdown Thread")
       │
[main THREAD EXITS]

========================================================================================================================
THREAD: [Server thread] | CLASSLOADER: net.minecraft.launchwrapper.LaunchClassLoader
========================================================================================================================

MinecraftServer.run()
       │
       ▼ Calls this.init() (overridden in DedicatedServer)
DedicatedServer.init()
       │  Spawns daemon Thread("Server console handler")
       │  FORGE HOOK: FMLCommonHandler.instance().onServerStart(DedicatedServer)
       │         │
       │         ▼
       │  FMLServerHandler.instance().beginServerLoading(DedicatedServer)
       │         │
       │         ├─ Loader.instance().loadMods(...)
       │         │    │  Transitions: LoaderState.LOADING
       │         │    │  identifyMods(): scans mods/ for @Mod annotations
       │         │    │  Topologically sorts mod dependency graph
       │         │    │  Instantiates ModContainers (FMLModContainer, ForgeModContainer, etc.)
       │         │    │  Transitions: LoaderState.CONSTRUCTING
       │         │    └─ Emits FMLConstructionEvent to all mods
       │         │
       │         └─ Loader.instance().preinitializeMods()
       │              │  Transitions: LoaderState.PREINITIALIZATION
       │              │  GameData.fireCreateRegistryEvents() -> RegistryEvent.NewRegistry
       │              │  ObjectHolderRegistry.findObjectHolders()
       │              │  CapabilityManager.injectCapabilities()
       │              │  Emits FMLPreInitializationEvent
       │              │  GameData.fireRegistryEvents(Register<Block>, Register<Item>, etc.)
       │              │  ObjectHolderRegistry.applyObjectHolders()
       │              │  Transitions: LoaderState.INITIALIZATION
       │              ▼
       │  Loads server.properties (PropertyManager)
       │  Checks ServerEula (eula.txt)
       │  Generates RSA KeyPair (CryptManager.generateKeyPair())
       │  Binds Network Endpoint (NetworkSystem.addEndpoint(host, port))
       │         │  Creates Netty ServerBootstrap
       │         │  Binds TCP listening port (25565)
       │         ▼
       │  FORGE HOOK: FMLCommonHandler.instance().onServerStarted()
       │         │
       │         ▼
       │  FMLServerHandler.instance().finishServerLoading()
       │         │
       │         └─ Loader.instance().initializeMods()
       │              │  CraftingHelper.loadRecipes() -> RegistryEvent.Register<IRecipe>
       │              │  Emits FMLInitializationEvent
       │              │  Transitions: LoaderState.POSTINITIALIZATION
       │              │  Emits FMLInterModComms.IMCEvent
       │              │  Emits FMLPostInitializationEvent
       │              │  Transitions: LoaderState.AVAILABLE
       │              │  Emits FMLLoadCompleteEvent
       │              │  GameData.freezeData() [REGISTRIES BECOME IMMUTABLE]
       │              ▼
       │  Instantiates DedicatedPlayerList(DedicatedServer)
       │  FORGE HOOK: FMLCommonHandler.instance().handleServerAboutToStart(DedicatedServer)
       │         │  Emits FMLServerAboutToStartEvent
       │         ▼
       │  DedicatedServer.loadAllWorlds(...)
       │         │  Creates AnvilSaveHandler for "world" directory
       │         │  Creates WorldServer for Dimension 0 (Overworld)
       │         │  Fires WorldEvent.Load(worldServer)
       │         │  Loads Dimension -1 (Nether) and Dimension 1 (The End)
       │         │  MinecraftServer.initialWorldChunkLoad()
       │         │    └─ Generates/loads 25x25 spawn chunk area (625 chunks) around world spawn
       │         ▼
       │  FORGE HOOK: FMLCommonHandler.instance().handleServerStarting(DedicatedServer)
       │         │  Emits FMLServerStartingEvent (mods register commands here)
       │         ▼
       │  Prints: "Done (4.244s)! For help, type \"help\" or \"?\""
       │  DedicatedServer.init() returns true!
       │
[RETURN TO MinecraftServer.run()]
       │
       ▼
MinecraftServer.run() (post-init setup)
       │  Updates ServerStatusResponse (motd, protocol 340, max-players)
       │  FORGE HOOK: FMLCommonHandler.instance().handleServerStarted()
       │         │  Emits FMLServerStartedEvent
       │         ▼
       │  FORGE HOOK: DedicatedServer.allowPlayerLogins = true;
       │
========================================================================================================================
STEADY-STATE GAME LOOP: while (this.serverRunning) [MinecraftServer.java:500]
========================================================================================================================
       │
       ▼
   ┌──► Loop Start: measure currentTime, calculate delta (j)
   │    │
   │    ├─ FMLCommonHandler.instance().onPreServerTick()  -> TickEvent.ServerTickEvent(START)
   │    │
   │    ├─ MinecraftServer.tick()
   │    │    ├─ NetworkSystem.networkTick() (poll Netty packets, dispatch packet handlers)
   │    │    ├─ PlayerList.onTick() (send world time, process player keepalives)
   │    │    └─ For each WorldServer in worlds:
   │    │         ├─ FMLCommonHandler.onPreWorldTick()  -> TickEvent.WorldTickEvent(START)
   │    │         ├─ WorldServer.tick() (block updates, entity ticks, tile entity ticks)
   │    │         └─ FMLCommonHandler.onPostWorldTick() -> TickEvent.WorldTickEvent(END)
   │    │
   │    ├─ FMLCommonHandler.instance().onPostServerTick() -> TickEvent.ServerTickEvent(END)
   │    │
   │    └─ Thread.sleep(Math.max(1L, 50L - elapsed)) (target: 20 TPS / 50ms tick budget)
   └───
```
