# TileEntity NBT Serialization Specification

## 1. Base TileEntity Schema (`TileEntity.java`)

All TileEntities serialize spatial coordinates and identity through `TileEntity.writeToNBT(NBTTagCompound)`:

```yaml
id: String                    # Registry identifier, e.g. "minecraft:furnace"
x: Int                        # Block X coordinate
y: Int                        # Block Y coordinate (0-255)
z: Int                        # Block Z coordinate
ForgeData: Compound (Opt)     # Mod persistent arbitrary TileEntity data
ForgeCaps: Compound (Opt)     # Forge capability dispatcher payload
```

---

## 2. Container & Inventory Schema (`TileEntityLockable.java`)

Containers (Chests, Dispensers, Hoppers, Shulker Boxes, Furnaces) serialize internal item slots:

```yaml
CustomName: String (Opt)      # Custom GUI title
Lock: String (Opt)            # Item display name required to open container
LootTable: String (Opt)       # Unopened loot table identifier
LootTableSeed: Long (Opt)     # Seed for procedural loot generation
Items: List<Compound>         # Serialized ItemStack slots
  - Slot: Byte                # Container slot index (0-255)
    id: String                # Item registry ID
    Count: Byte               # Stack count
    Damage: Short             # Metadata
    tag: Compound (Opt)       # Item NBT
```

---

## 3. Specialized Vanilla TileEntities

### Furnace (`TileEntityFurnace.java`)
```yaml
BurnTime: Short               # Fuel ticks remaining
CookTime: Short               # Current item smelting progress ticks
CookTimeTotal: Short          # Required cooking ticks (usually 200)
```

### Mob Spawner (`TileEntityMobSpawner.java`)
```yaml
Delay: Short                  # Ticks until next spawn attempt
MinSpawnDelay: Short          # Minimum random delay
MaxSpawnDelay: Short          # Maximum random delay
SpawnCount: Short             # Number of entities spawned per cycle
MaxNearbyEntities: Short      # Density cap
RequiredPlayerRange: Short    # Activation radius (typically 16)
SpawnRange: Short             # Radial spawn distance
SpawnData: Compound           # Target entity full NBT template
SpawnPotentials: List<Comp>   # Weighted list of entity templates
```

### Command Block (`TileEntityCommandBlock.java`)
```yaml
Command: String               # Stored command string
SuccessCount: Int             # Redstone analog output strength
CustomName: String            # Output sender name (defaults to "@")
TrackOutput: Byte             # 1 if output history is recorded
LastOutput: String            # JSON-formatted execution result
auto: Byte                    # 1 if chain/repeating without redstone
conditionMet: Byte            # Conditional block predecessor result
```

---

## 4. Client Synchronization Boundary

TileEntity data is split into two synchronization surfaces:
1. **Full Storage NBT:** Written during chunk saves via `writeToNBT(new NBTTagCompound())`. Contains sensitive state like loot table seeds, command strings, and full inventory slots.
2. **Network Update NBT:** Sent to clients via `SPacketUpdateTileEntity` or embedded in `SPacketChunkData`. Controlled by:
   - `getUpdateTag()`: Compiles client-visible state (e.g. furnace burning animation, banner designs, chest opening angle).
   - Sensitive fields (inventories in closed containers, exact fuel counts) are excluded from basic update tags unless explicitly synchronized by custom container window packets (`SPacketWindowItems`).
