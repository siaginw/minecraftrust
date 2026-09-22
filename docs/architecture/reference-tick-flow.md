# Minecraft 1.12.2 / Forge 14.23.5.2860 Reference Tick Execution Flow

```text
MinecraftServer.run() [Thread: "Server thread"]
│
├── System.currentTimeMillis() lag accumulator calculation
│   ├── If lag delta > 2000 ms -> clamp to 2000 ms, log "Can't keep up!"
│   ├── If lag delta < 0 ms -> clamp to 0 ms, log "Time ran backwards!"
│   └── While accumulator > 50 ms:
│
└── MinecraftServer.tick()
    │
    ├── [Phase 1] FMLCommonHandler.onPreServerTick()
    │    └── Bus dispatch: TickEvent.ServerTickEvent(START)
    │
    ├── [Phase 2] this.tickCounter++
    │
    ├── [Phase 3] ChunkIOExecutor.tick()
    │    └── Polls completed async chunk disk tasks -> registers into ChunkProviderServer
    │
    ├── [Phase 4] synchronized (this.futureTaskQueue) ["jobs" — 0.22% CPU]
    │    └── While !futureTaskQueue.isEmpty():
    │         └── Util.runTask(futureTaskQueue.poll(), LOGGER)
    │              ├── Inbound Netty gameplay packets (dispatched via PacketThreadUtil)
    │              └── Scheduled mod callbacks
    │
    ├── [Phase 5] Dimension Loop ["levels" — 94.51% CPU]
    │    └── For each dimension id in DimensionManager.getIDs(tickCounter % 200 == 0):
    │         │
    │         ├── WorldServer worldserver = DimensionManager.getWorld(id)
    │         │
    │         ├── If tickCounter % 20 == 0:
    │         │    └── Broadcast SPacketTimeUpdate & difficulty to dimension players
    │         │
    │         ├── FMLCommonHandler.onPreWorldTick(worldserver)
    │         │    └── Bus dispatch: TickEvent.WorldTickEvent(Side.SERVER, START, worldserver)
    │         │
    │         ├── WorldServer.tick()
    │         │    │
    │         │    ├── super.tick() -> World.updateWeather()
    │         │    │    └── Decrement weather timers, interpolate rain/thunder strength
    │         │    │
    │         │    ├── Hardcore difficulty clamp (EnumDifficulty.HARD)
    │         │    ├── BiomeProvider.cleanupCache()
    │         │    │
    │         │    ├── If areAllPlayersAsleep():
    │         │    │    ├── If doDaylightCycle -> advance time to morning (time + 24000L - %24000L)
    │         │    │    └── wakeAllPlayers()
    │         │    │
    │         │    ├── WorldEntitySpawner.findChunksForSpawning() ["mobSpawner" — 0.33% CPU]
    │         │    │    └── If doMobSpawning -> evaluate chunk spawn caps and instantiate mobs
    │         │    │
    │         │    ├── ChunkProviderServer.tick() ["chunkSource" — 1.54% CPU]
    │         │    │    └── Iterate droppedChunksSet -> queue chunk serialization, unload objects
    │         │    │
    │         │    ├── calculateSkylightSubtracted(1.0F) -> adjust celestial ambient light
    │         │    │
    │         │    ├── World time advance:
    │         │    │    ├── worldInfo.setWorldTotalTime(totalTime + 1L)
    │         │    │    └── If doDaylightCycle -> worldInfo.setWorldTime(worldTime + 1L)
    │         │    │
    │         │    ├── WorldServer.tickUpdates(false) ["tickPending" — 25.93% CPU]
    │         │    │    ├── Extract up to 65,536 entries from pendingTickListEntriesTreeSet/HashSet
    │         │    │    └── For each entry:
    │         │    │         ├── If area loaded:
    │         │    │         │    └── block.updateTick(world, pos, state, rand)
    │         │    │         └── If area not loaded:
    │         │    │              └── Reschedule update for tick 0
    │         │    │
    │         │    ├── WorldServer.updateBlocks() ["tickBlocks" — 6.38% CPU]
    │         │    │    ├── playerCheckLight()
    │         │    │    └── For each chunk in playerChunkMap within 128 blocks of players:
    │         │    │         ├── chunk.enqueueRelightChecks()
    │         │    │         ├── chunk.onTick(false)
    │         │    │         ├── Thunder/Lightning check (1/100,000)
    │         │    │         ├── Ice and Snow freeze check (1/16)
    │         │    │         └── Random block ticks (randomTickSpeed per 16x16x16 storage block)
    │         │    │
    │         │    ├── PlayerChunkMap.tick() ["chunkMap" — 0.62% CPU]
    │         │    │    └── Flush SPacketBlockChange / SPacketMultiBlockChange to tracking players
    │         │    │
    │         │    ├── Village logic ["village" — 0.76% CPU]
    │         │    │    ├── VillageCollection.tick()
    │         │    │    └── VillageSiege.tick()
    │         │    │
    │         │    ├── Teleporter.removeStalePortalLocations() ["portalForcer" — 0.21% CPU]
    │         │    │
    │         │    └── WorldServer.sendQueuedBlockEvents()
    │         │         └── Flush SPacketBlockAction (chests, pistons, note blocks)
    │         │
    │         ├── WorldServer.updateEntities()
    │         │    │
    │         │    ├── Weather effect update: iterate weatherEffects (lightning)
    │         │    │
    │         │    ├── WorldServer.tickPlayers() ["players" — 0.03% CPU]
    │         │    │    └── For each player in playerEntities:
    │         │    │         ├── FMLCommonHandler.onPlayerPreTick(player) -> PlayerTickEvent(START)
    │         │    │         ├── player.onUpdate() [physics, food, inventory, potion effects]
    │         │    │         ├── FMLCommonHandler.onPlayerPostTick(player) -> PlayerTickEvent(END)
    │         │    │         └── If dead -> chunk.removeEntity(player), remove from loadedEntityList
    │         │    │
    │         │    ├── Regular Entities ["entities.regular" — 44.00% CPU]
    │         │    │    └── For i = 0 .. loadedEntityList.size() - 1:
    │         │    │         ├── If riding != null: skip (ticked by vehicle)
    │         │    │         ├── If !isDead:
    │         │    │         │    └── updateEntityWithOptionalForce(entity, true) -> entity.onUpdate()
    │         │    │         │         ├── Movement & collision (8.51% CPU)
    │         │    │         │         ├── Entity AI & goals (3.28% CPU)
    │         │    │         │         ├── Travel & physics (2.53% CPU)
    │         │    │         │         └── LivingBase ticking (1.55% CPU)
    │         │    │         └── If isDead:
    │         │    │              └── chunk.removeEntity(entity), loadedEntityList.remove(i--)
    │         │    │
    │         │    ├── TileEntities ["entities.blockEntities" — 1.04% CPU]
    │         │    │    ├── this.processingLoadedTiles = true
    │         │    │    ├── For each tile in tickableTileEntities:
    │         │    │    │    ├── If isInvalid(): remove from iterator and loadedTileEntityList
    │         │    │    │    ├── If chunk loaded: tile.update()
    │         │    │    │    └── If isInvalid(): remove from iterator and loadedTileEntityList
    │         │    │    └── this.processingLoadedTiles = false
    │         │    │
    │         │    └── TileEntity Queues:
    │         │         ├── Drain tileEntitiesToBeRemoved -> onChunkUnload(), remove
    │         │         └── Drain addedTileEntityList -> bind chunk, add to tickableTileEntities
    │         │
    │         ├── FMLCommonHandler.onPostWorldTick(worldserver)
    │         │    └── Bus dispatch: TickEvent.WorldTickEvent(Side.SERVER, END, worldserver)
    │         │
    │         └── EntityTracker.tick() ["tracker" — 3.10% CPU]
    │              └── For each EntityTrackerEntry:
    │                   └── Compute delta position/motion -> send SPacketEntityRelMove/SPacketEntityTeleport
    │
    ├── [Phase 6] DimensionManager.unloadWorlds(worldTickTimes) ["dim_unloading" — 0.55% CPU]
    │    └── Evaluates empty dimension unloads, closes chunk providers, fires WorldEvent.Unload
    │
    ├── [Phase 7] NetworkSystem.networkTick() ["connection" — 0.29% CPU]
    │    └── Iterates NetworkManager instances -> flushes outbound channels, polls direct packet queue
    │
    ├── [Phase 8] PlayerList.onTick() ["players" — 1.66% CPU]
    │    ├── Check player timeouts
    │    └── Send KeepAlive packet every 40 ticks (2 seconds)
    │
    ├── [Phase 9] FunctionManager.update() ["commandFunctions" — 0.40% CPU]
    │    └── Executes data-pack command functions registered for tick update
    │
    ├── [Phase 10] Custom ITickables ["tickables" — 0.06% CPU]
    │    └── For each ITickable in this.tickables: tickable.update()
    │
    └── [Phase 11] FMLCommonHandler.onPostServerTick()
         └── Bus dispatch: TickEvent.ServerTickEvent(END)
```
