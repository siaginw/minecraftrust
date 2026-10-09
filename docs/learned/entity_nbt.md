# Entity NBT Serialization Specification

## 1. Base Entity Hierarchy (`Entity.java`)

All entities serialize base spatial and physics properties through `Entity.writeToNBT(NBTTagCompound)`:

```yaml
id: String                    # Registry identifier, e.g. "minecraft:skeleton"
Pos: List<Double> [3]         # [X, Y, Z] world coordinates
Motion: List<Double> [3]      # [VX, VY, VZ] velocity vector
Rotation: List<Float> [2]     # [Yaw, Pitch] orientation in degrees
FallDistance: Float           # Distance fallen since last on ground
Fire: Short                   # Fire ticks remaining (-20 if immune/extinguished)
Air: Short                    # Air supply ticks remaining (300 max)
OnGround: Byte                # 1 if standing on a block, 0 if airborne
Dimension: Int                # Dimension ID (0: Overworld, -1: Nether, 1: The End)
Invulnerable: Byte            # 1 if immune to damage
PortalCooldown: Int           # Ticks until entity can use nether/end portals
UUIDMost: Long                # High 64 bits of entity UUID
UUIDLeast: Long               # Low 64 bits of entity UUID
CustomName: String (Opt)      # Nametag text
CustomNameVisible: Byte (Opt) # 1 if nametag displays through blocks
Silent: Byte (Opt)            # 1 if entity emits no sounds
Glowing: Byte (Opt)           # 1 if entity has spectral outline
Tags: List<String> (Opt)      # Scoreboard entity string tags
Passengers: List<Compound>    # Recursive list of entities riding this entity
ForgeData: Compound (Opt)     # Mod persistent arbitrary entity data
ForgeCaps: Compound (Opt)     # Forge capability dispatcher payload
```

---

## 2. Living Entity Hierarchy (`EntityLivingBase.java`)

Adds biological, health, and combat status:

```yaml
Health: Float                 # Current health points (20.0 max standard)
AbsorptionAmount: Float       # Golden apple absorption shield health
HurtTime: Short               # Invulnerability frames remaining
DeathTime: Short              # Ticks dead before despawning (0-20)
Attributes: List<Compound>    # Modifiable attributes
  - Name: String              # e.g. "generic.maxHealth", "generic.movementSpeed"
    Base: Double              # Unmodified baseline stat
    Modifiers: List<Compound> # Active buffs/debuffs
ActiveEffects: List<Compound> # Potion effects
  - Id: Byte                  # Potion numerical ID (1: Speed, 2: Slowness, etc.)
    Amplifier: Byte           # Level (0 = Level 1)
    Duration: Int             # Ticks remaining
    Ambient: Byte             # 1 if from beacon
    ShowParticles: Byte       # 1 if displaying swirl particles
```

---

## 3. Mob Hierarchy (`EntityLiving.java`)

Adds AI, inventory, and equipment:

```yaml
ArmorItems: List<Compound> [4]# [Feet, Legs, Chest, Head] ItemStacks
HandItems: List<Compound> [2] # [Mainhand, Offhand] ItemStacks
ArmorDropChances: List<Float> # Drop probability on death
HandDropChances: List<Float>  # Drop probability on death
CanPickUpLoot: Byte           # 1 if mob can collect items from floor
NoAI: Byte                    # 1 completely disables pathfinding and goals
PersistenceRequired: Byte     # 1 prevents despawning on distance
Leashed: Byte                 # 1 if attached to fence/lead
Leash: Compound               # Lead target entity UUID or fence block pos
```

---

## 4. Recursive Passenger Tree
The `Passengers` list is explicitly recursive: an entity can hold passengers, each of which can hold its own passengers (e.g. a Chicken Jockey: Skeleton riding a Spider, with a small Slime riding the Skeleton).
Vanilla recursion is bounded by standard JVM call-stack depth; Rust loaders must enforce recursion depth clamping ($\le 512$).
