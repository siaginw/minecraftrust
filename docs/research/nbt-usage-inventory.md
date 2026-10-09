# NBT Subsystem Usage Inventory (Minecraft 1.12.2 & Forge 14.23.5.x)

## 1. Inventory Summary

NBT is the sole hierarchical serialization format used throughout Minecraft 1.12.2. A codebase-wide scan across the Minecraft 1.12.2 and Forge reference repositories reveals 12 distinct subsystem domains relying on NBT:

```
+-----------------------------------------------------------------------------------+
|                           MINECRAFT 1.12.2 NBT USAGE DOMAINS                      |
+---------------------+-------------------------------+-----------------------------+
| Persistence Subsystems | Runtime Dynamic Subsystems  | Cross-Boundary Interfaces   |
| - Chunk Storage     | - ItemStack Tag Compound      | - Network PacketBuffer      |
| - Player Data (.dat)| - Entity Serialization        | - CustomPayload Channels    |
| - WorldInfo (level) | - TileEntity Serialization    | - Command SNBT (JsonToNBT)  |
| - WorldSavedData    | - Forge Capabilities          | - Forge Event Hooks (Save)  |
| - Structure Blocks  | - DataFixer Schema Migrations | - JNI Migration Seams       |
+---------------------+-------------------------------+-----------------------------+
```

---

## 2. Detailed Subsystem Analysis

### 1. Chunk Storage & Region IO
- **Classes:** `AnvilChunkLoader`, `RegionFile`, `RegionFileCache`.
- **Scope:** Root compound containing `Level` (`Sections`, `Entities`, `TileEntities`, `TileTicks`, `Biomes`, `HeightMap`, `Capabilities`).
- **Frequency:** Every 900 ticks (autosave) or on chunk unload; high data volume (50 KB uncompressed per chunk).
- **Format:** ZLIB-compressed binary NBT.

### 2. ItemStack NBT
- **Classes:** `ItemStack`, `Item`, `EnchantmentHelper`.
- **Scope:** Field `stackTagCompound` containing `display`, `ench`, `AttributeModifiers`, potion effects, book pages, mod tags.
- **Frequency:** Continuous in hot loop (inventory manipulation, player attacks, block breaking).
- **Format:** In-memory object graph; uncompressed in packet buffers; GZIP when saved in inventory.

### 3. Entity State Serialization
- **Classes:** `Entity`, `EntityLivingBase`, `EntityPlayer`, `EntityList`.
- **Scope:** `Pos`, `Motion`, `Rotation`, `Attributes`, `ActiveEffects`, `ForgeData`, `ForgeCaps`.
- **Frequency:** Chunk save/load, entity dimension teleportation, client entity spawn packet.

### 4. TileEntity State Serialization
- **Classes:** `TileEntity`, `TileEntityLockable`, `TileEntityFurnace`, `TileEntityChest`.
- **Scope:** Coordinates `x`, `y`, `z`, inventory `Items`, custom machine state, energy/fluid storage.
- **Frequency:** Chunk save/load, block updates, client synchronization via `SPacketUpdateTileEntity`.

### 5. Player Persistence
- **Classes:** `SaveHandler`, `PlayerList`.
- **Scope:** `playerdata/<UUID>.dat`: Complete player inventory, ender chest, health, food, XP, achievements/recipes.
- **Frequency:** Player join, player quit, world autosave (synchronous flush).
- **Format:** GZIP-compressed binary NBT.

### 6. World Metadata (`level.dat`)
- **Classes:** `WorldInfo`, `SaveHandler`, `WorldServer`.
- **Scope:** World seed, game time, day time, gamerules, weather, spawn coordinates, data version.
- **Frequency:** Server boot, world save.
- **Format:** GZIP-compressed binary NBT.

### 7. WorldSavedData & Dimension Tables
- **Classes:** `WorldSavedData`, `MapStorage`, `MapData`, `VillageCollection`.
- **Scope:** `data/*.dat`: Map explorations, village states, scoreboard objectives, ID counts.
- **Frequency:** Server boot, scheduled save.
- **Format:** GZIP-compressed binary NBT.

### 8. Structure Templates
- **Classes:** `Template`, `TemplateManager`.
- **Scope:** `data/structures/*.nbt`: Block palettes, entity snapshots, relative bounding boxes.
- **Frequency:** Structure generation during worldgen, structure block save/load.
- **Format:** GZIP-compressed binary NBT.

### 9. Network Protocol & Packets
- **Classes:** `PacketBuffer`, Netty pipeline, `SPacketCustomPayload`, `SPacketUpdateTileEntity`, `SPacketSetSlot`.
- **Scope:** ItemStack transmission, TileEntity sync, mod network messages via `SimpleNetworkWrapper`.
- **Frequency:** High (hundreds per tick per active player).
- **Format:** Uncompressed binary NBT embedded in Netty buffers.

### 10. Forge Capabilities
- **Classes:** `CapabilityDispatcher`, `INBTSerializable`.
- **Scope:** Embedded under key `"ForgeCaps"` inside Chunks, Entities, TileEntities, ItemStacks, and Worlds.
- **Frequency:** Co-serialized with host objects.
- **Format:** Polymorphic NBT compound.

### 11. Command SNBT / String Parsing
- **Classes:** `JsonToNBT`, `CommandGive`, `CommandSummon`, `CommandBlockData`, `CommandEntityData`.
- **Scope:** Parsing human-readable JSON-like strings into `NBTTagCompound`.
- **Frequency:** On command execution or command block tick.
- **Format:** Text string $\rightarrow$ `NBTTagCompound`.

### 12. Mod Data Hooks & Events
- **Classes:** `ChunkDataEvent.Save`, `ChunkDataEvent.Load`, `PlayerEvent.SaveToFile`.
- **Scope:** Mod authors attaching arbitrary compounds to chunks or players.
- **Frequency:** Lifecycle-driven.
- **Format:** Direct Java object reference access.
