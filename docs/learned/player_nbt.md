# Player Data NBT Serialization Specification

## 1. Storage Location & Encoding
Player persistent state is stored in individual GZIP-compressed binary NBT files under the world directory:
`world/playerdata/<UUID>.dat`
- Serialized during world autosaves and upon player logout via `SaveHandler.writePlayerData(EntityPlayer)`.
- Loaded upon player connection via `SaveHandler.readPlayerData(EntityPlayer)`.

---

## 2. Complete Player NBT Schema

Player NBT extends the complete **Entity** and **LivingEntity** schemas, adding inventory, capabilities, and survival statistics:

```yaml
DataVersion: Int              # DataFixer schema version (1343 for 1.12.2)
Dimension: Int                # Dimension player is currently in (0, -1, 1)
playerGameType: Int           # 0: Survival, 1: Creative, 2: Adventure, 3: Spectator
Score: Int                    # Legacy scoreboard score
SelectedItemSlot: Int         # Active hotbar index (0-8)
SleepTimer: Short             # Ticks sleeping in bed
SpawnX: Int (Opt)             # Bed spawn position X
SpawnY: Int (Opt)             # Bed spawn position Y
SpawnZ: Int (Opt)             # Bed spawn position Z
SpawnForced: Byte (Opt)       # 1 if bed spawn cannot be obstructed
enteredNetherPosition: Comp   # Coordinates when entering Nether portal

# Survival Hunger System
foodLevel: Int                # Food points (0-20)
foodExhaustionLevel: Float    # Energy spent before losing saturation
foodSaturationLevel: Float    # Food buffer level
foodTickTimer: Int            # Ticks until next hunger healing/drain tick

# Experience System
XpLevel: Int                  # Displayed experience level
XpP: Float                    # Progress bar fraction to next level (0.0 - 1.0)
XpTotal: Int                  # Total lifetime experience points collected
XpSeed: Int                   # PRNG seed driving enchantment table options

# Player Abilities Compound
abilities: Compound
  walkSpeed: Float            # Base walking velocity (0.1 standard)
  flySpeed: Float             # Base flying velocity (0.05 standard)
  flying: Byte                # 1 if currently in flight mode
  mayfly: Byte                # 1 if permitted to double-jump to fly
  instabuild: Byte            # 1 for creative block breaking
  invulnerable: Byte          # 1 for creative godmode
  mayBuild: Byte              # 1 if permitted to place/break blocks

# Recipe Book & GUI Tracking
recipeBook: Compound
  recipes: List<String>       # Unlocked crafting recipe IDs
  toBeDisplayed: List<String> # Toast notification queue
  isGuiOpen: Byte             # Recipe book UI state
  isFilteringCraftable: Byte  # Craftable-only filter toggle

# Inventories
Inventory: List<Compound>     # Main player inventory + armor + offhand
  - Slot: Byte                # Slot identifier
    id: String                # Item ID
    Count: Byte               # Stack count
    Damage: Short             # Metadata
    tag: Compound (Opt)       # Item dynamic NBT
EnderItems: List<Compound>    # Ender chest inventory (Slots 0-26)

# Mount State
RootVehicle: Compound (Opt)   # Vehicle entity state if player logged out riding
AttachFace: Byte (Opt)        # Gliding / Elytra attachment state
ForgeCaps: Compound (Opt)     # Player capabilities (e.g. Baubles, RPG stats)
```

---

## 3. Inventory Slot Index Mapping

Player inventory slots are addressed using non-contiguous `Slot` byte IDs:

| Slot Range | Destination |
| :--- | :--- |
| `0` to `8` | Hotbar (left to right) |
| `9` to `35` | Main Inventory (top-left to bottom-right) |
| `100` | Armor: Boots |
| `101` | Armor: Leggings |
| `102` | Armor: Chestplate / Elytra |
| `103` | Armor: Helmet |
| `-106` | **Offhand Slot** (Signed `int8` representation of `150`) |

### Parsing Hazard:
The offhand slot is encoded as byte `-106` (`0x96`). Codecs expecting strictly non-negative slot IDs will fail on valid player data files.
