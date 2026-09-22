# Boot Invariants for Minecraft 1.12.2 / Forge 14.23.5.2860

## Invariant 1: CoreMod Transformer Registration Precedes Minecraft Classloading
- **Statement:** All bytecode transformers (`IClassTransformer`, `PatchingTransformer`, `AccessTransformer`, `DeobfuscationTransformer`) must be fully registered in `LaunchClassLoader` before `net.minecraft.server.MinecraftServer` or any other Minecraft class is loaded into the JVM.
- **Evidence:** `[SOURCE: Launch.java:135, CoreModManager.java:195-265]`, `[RUNTIME TRACE: lines 2-18]`
- **Reasoning:** Once a class is loaded and defined by `LaunchClassLoader.defineClass()`, its bytecode cannot be retransformed without JVM bytecode instrumentation agents (`java.lang.instrument`).
- **Architectural Consequence:** Any Rust native boundary that bypasses Java classloading must replicate the exact net effects of coremod transformations for that class.

---

## Invariant 2: Thread Hand-off from `main` to `Server thread` for Authoritative Server State
- **Statement:** Process bootstrap executes exclusively on thread `main`. Before `DedicatedServer.init()` runs, a dedicated `Thread("Server thread")` is spawned and started. `main` thread terminates immediately after. Authoritative game-world mutation, FML lifecycle dispatch during dedicated server startup, world loading, and the steady-state game loop execute on `Server thread`.
- **Observed Thread Distinctions:**
  - `Server thread`: Authoritative game-world mutation, tick events, scheduled tasks, and world state.
  - Netty EventLoop threads: Socket I/O, packet decoding/encoding, and initial channel pipeline.
  - `File IO Thread`: Asynchronous region file writes (`ThreadedFileIOBase`).
  - Auth threads: Asynchronous Yggdrasil session checks.
  - Async mod worker threads: Mod-created thread pools.
- **Evidence:** `[SOURCE: MinecraftServer.java:712-714]`, `[RUNTIME TRACE: lines 18-20]`
- **Reasoning:** Minecraft and Forge internal code check `Thread.currentThread() == MinecraftServer.getServer().getServerThread()` (or `isCallingFromMinecraftThread()`) to determine whether state access is safe or must be scheduled via `addScheduledTask()`.
- **Architectural Consequence:** When native code or background threads propose mutations to authoritative game-world state, they must be dispatched to `Server thread` via `addScheduledTask()` or a synchronized work queue.

---

## Invariant 3: Dependency-Ordered FML Lifecycle State Progression
- **Statement:** FML lifecycle states are strictly monotonic and sequential:
  `UNLOADED` -> `CONSTRUCTING` -> `PREINITIALIZATION` -> `INITIALIZATION` -> `POSTINITIALIZATION` -> `AVAILABLE` -> `SERVER_ABOUT_TO_START` -> `SERVER_STARTING` -> `SERVER_STARTED`.
  Skipping or reordering any state causes `LoaderException` or mod desynchronization.
- **Evidence:** `[SOURCE: Loader.java:559-806, LoadController.java:120-180]`
- **Reasoning:** Mods structure their initialization across these events:
  - `@Mod` constructor / `FMLConstructionEvent`: Language adapters, ASM data.
  - `FMLPreInitializationEvent`: Config files, network channels, capability registration.
  - `FMLInitializationEvent`: Item/block cross-references, recipes, world generators.
  - `FMLPostInitializationEvent`: Cross-mod IMC queries (e.g. JEI, Waila integration).
- **Architectural Consequence:** A hybrid runtime must emit every lifecycle event in order even if the underlying subsystem is already managed by Rust.

---

## Invariant 4: Registry Mutability Window Closes Before World Loading
- **Statement:** Registries (`Block`, `Item`, `Biome`, `Potion`, `SoundEvent`, `Enchantment`) are open for additions during `RegistryEvent.Register<T>` (in PreInit and Init). All registries are permanently locked by `GameData.freezeData()` at the end of `Loader.initializeMods()` before worlds are created.
- **Evidence:** `[SOURCE: Loader.java:758, GameData.java:230-260]`
- **Reasoning:** Dynamic block ID allocation must be finalized and immutable before chunk data and block palettes are parsed from disk or generated.
- **Architectural Consequence:** Rust subsystems can safely snapshot block/item/biome registries into immutable contiguous arrays upon reaching `LoaderState.AVAILABLE`.

---

## Invariant 5: Socket Binding Precedes FML Initialization and Player Login
- **Statement:** The Netty server endpoint (`NetworkSystem.addEndpoint`) binds to the TCP port in `DedicatedServer.init()` *before* `Loader.initializeMods()` and before any world is loaded. However, player connections are rejected with `Server is still starting up!` until `DedicatedServer.allowPlayerLogins` is set to `true` by `handleServerStarted()`.
- **Evidence:** `[SOURCE: DedicatedServer.java:156, NetHandlerLoginServer.java:75]`, `[BYTECODE: DedicatedServer.java.patch:7, MinecraftServer.java.patch:102]`, `[RUNTIME TRACE: lines 37, 55]`
- **Reasoning:** Server status queries (SLP / ping) must work while the server is loading worlds, but game packets and player logins must be blocked until ticking begins.
- **Architectural Consequence:** Rust network layer can bind and serve ping responses immediately, but must gate player join packets until the authoritative server loop is ready.

---

## Invariant 6: Spawn Chunk Region Loaded Synchronously Before Tick Loop
- **Statement:** `MinecraftServer.initialWorldChunkLoad()` synchronously loads/generates a 25x25 chunk area (625 chunks, -192 to +192 block offset inclusive, step 16) centered on the world spawn point for Dimension 0 before `DedicatedServer.init()` completes and before the tick loop begins.
- **Evidence:** `[SOURCE: MinecraftServer.java:271-282]`, `[RUNTIME TRACE: lines 52-54]`
- **Reasoning:** Entity spawns, spawn protection, and world baseline coordinates depend on spawn chunks existing in memory.
- **Architectural Consequence:** Rust chunk management must pre-populate or cache the spawn area before signalling readiness to the server orchestrator.

---

## Invariant 7: Command Registration strictly occurs during `FMLServerStartingEvent`
- **Statement:** Server console and player commands must be registered into `ServerCommandManager` during `FMLServerStartingEvent` (emitted by `FMLCommonHandler.handleServerStarting()`), which fires after worlds are loaded but before `handleServerStarted()`.
- **Evidence:** `[SOURCE: FMLCommonHandler.java:295, DedicatedServer.java.patch:56]`
- **Reasoning:** Mod commands frequently query world providers or dimension lists to configure subcommand trees.
- **Architectural Consequence:** Any command dispatch architecture must incorporate mod-registered commands established at this phase.
