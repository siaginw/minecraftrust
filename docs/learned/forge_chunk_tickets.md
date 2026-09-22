# Forge Chunk Ticket Subsystem & forcedchunks.dat Architecture

## 1. Overview
In vanilla Minecraft 1.12.2, the only chunks that remain permanently loaded without player presence are the $25 \times 25$ spawn chunks in the Overworld.
Forge provides `ForgeChunkManager` (`net.minecraftforge.common.ForgeChunkManager`) to allow mods (e.g. chunk loaders, quarries, automated farms, dimensional anchors) to request and maintain persistent chunk loading tickets across all dimensions.

## 2. Ticket Types & Hierarchy
Tickets are represented by `ForgeChunkManager.Ticket`:

1. **`Type.NORMAL`:**
   - Standard mod-owned ticket.
   - Bound to a specific Mod ID (e.g. `"extrautils2"`, `"ic2"`).
   - Can force up to `ticket.maxDepth` chunks (default 25 chunks per ticket).
2. **`Type.PLAYER`:**
   - Player-associated mod ticket.
   - Requires both a Mod ID and a player UUID/username.
   - Bounded by per-player ticket quotas configured in `forge.cfg`.
3. **`Type.ENTITY`:**
   - Bound to a live `Entity` instance (e.g. a chunkloader cart, quarry robot, or guided projectile).
   - Automatically tracks entity position. As the entity moves across chunk boundaries, the ticket forces the chunk containing the entity and releases chunks behind it.

## 3. Persistent Storage Format (`forcedchunks.dat`)
Tickets are persisted per-dimension in an NBT file located at:
$$\text{<world\_save\_dir>/forcedchunks.dat}$$

The file is written via `CompressedStreamTools.write()` onto the `File IO Thread` during world save.

### Binary NBT Structure:
```
Root (TAG_Compound)
└── "TicketList": TAG_List of TAG_Compound
    └── [Mod Holder Entry] (TAG_Compound)
        ├── "Owner": TAG_String (Mod ID, e.g. "enderio")
        └── "Tickets": TAG_List of TAG_Compound
            └── [Ticket Entry] (TAG_Compound)
                ├── "Type": TAG_Byte (0 = NORMAL, 1 = ENTITY)
                ├── "ChunkListDepth": TAG_Byte (Maximum forced chunks allowed)
                ├── "ModId": TAG_String (Present if player ticket)
                ├── "Player": TAG_String (Player UUID string)
                ├── "ModData": TAG_Compound (Optional mod custom metadata)
                └── [If Type == ENTITY]:
                    ├── "chunkX": TAG_Int
                    ├── "chunkZ": TAG_Int
                    ├── "PersistentIDMSB": TAG_Long (Entity UUID Most Significant Bits)
                    └── "PersistentIDLSB": TAG_Long (Entity UUID Least Significant Bits)
```

## 4. World Load & Mod Callback Lifecycle
Because tickets are saved as abstract mod identifiers rather than active references, Forge requires mods to register callbacks to re-validate tickets when a world loads:

1. Mod registers callback during FML initialization:
   ```java
   ForgeChunkManager.setForcedChunkLoadingCallback(Object mod, ForgeChunkManager.LoadingCallback callback);
   ```
2. During world boot (`WorldServer.init()` / `ForgeChunkManager.loadWorld(world)`):
   - Forge reads `forcedchunks.dat`.
   - Reconstructs `Ticket` instances.
   - Invokes the mod's `LoadingCallback.ticketsLoaded(List<Ticket> tickets, World world)`.
   - **Crucial Rule:** The mod must explicitly inspect the ticket list and call `ForgeChunkManager.forceChunk(ticket, chunkPos)` for each chunk it wishes to keep loaded. Any ticket not confirmed by the mod callback is discarded!
3. If an entity ticket was saved, Forge places it into `pendingEntities`. When the entity loads from chunk NBT, `ForgeChunkManager.loadEntity(entity)` matches the entity's UUID and re-binds the ticket.
