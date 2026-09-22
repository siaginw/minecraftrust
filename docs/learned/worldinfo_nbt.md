# WorldInfo (level.dat) NBT Serialization Specification

## 1. Storage Location & Atomic Write Pipeline
World global configuration is stored in:
`world/level.dat` (with automatic fallback backup in `world/level.dat_old`).
- **Codec:** GZIP-compressed binary NBT via `CompressedStreamTools.readCompressed` and `writeCompressed`.
- **Atomic Rename Protocol:**
  1. Serialized to temporary file `level.dat_new`.
  2. If `level.dat_old` exists, delete it.
  3. Rename current `level.dat` $\rightarrow$ `level.dat_old`.
  4. Rename `level.dat_new` $\rightarrow$ `level.dat`.

---

## 2. Complete `Data` Compound Schema

The root compound contains a single sub-compound named `"Data"`:

```yaml
Data: Compound
  DataVersion: Int            # DataFixer schema version (1343)
  version: Int                # NBT format version (19133 for 1.12.2)
  initialized: Byte           # 1 if world spawn has been generated
  LevelName: String           # Human-readable world name
  generatorName: String       # "default", "flat", "largeBiomes", "amplified", "customized"
  generatorVersion: Int       # Generator version (1)
  generatorOptions: String    # JSON string configuring flat or customized presets
  RandomSeed: Long            # 64-bit PRNG world seed
  MapFeatures: Byte           # 1 if structures (villages, strongholds) generate
  LastPlayed: Long            # Milliseconds since Unix epoch
  SizeOnDisk: Long            # Approximate world size in bytes
  allowCommands: Byte         # 1 if cheats/operator commands enabled
  hardcore: Byte              # 1 if permadeath hardcore mode
  GameType: Int               # Default gamemode (0: Survival, 1: Creative, 2: Adv, 3: Spec)
  Time: Long                  # Cumulative world age in ticks
  DayTime: Long               # Current cycle time in ticks (0-24000)
  SpawnX: Int                 # World spawn coordinate X
  SpawnY: Int                 # World spawn coordinate Y
  SpawnZ: Int                 # World spawn coordinate Z
  raining: Byte               # 1 if currently raining
  rainTime: Int               # Ticks until rain state toggles
  thundering: Byte            # 1 if storming with lightning
  thunderTime: Int            # Ticks until thunder state toggles
  clearWeatherTime: Int       # Ticks remaining for forced clear weather
  Difficulty: Byte            # 0: Peaceful, 1: Easy, 2: Normal, 3: Hard
  DifficultyLocked: Byte      # 1 if difficulty cannot be altered

  # World Border Parameters
  BorderCenterX: Double       # Center X
  BorderCenterZ: Double       # Center Z
  BorderSize: Double          # Current diameter in blocks
  BorderSizeLerpTarget: Double# Target size during expansion/contraction
  BorderSizeLerpTime: Long    # Milliseconds remaining for size transition
  BorderSafeZone: Double      # Buffer distance before border damage applies
  BorderDamagePerBlock: Double# Damage dealt per block outside safe zone
  BorderWarningBlocks: Double # Proximity warning distance in blocks
  BorderWarningTime: Double   # Warning time in seconds

  # Gamerules Compound (All values are stored as Strings!)
  GameRules: Compound
    keepInventory: String     # "true" or "false"
    doDaylightCycle: String   # "true" or "false"
    mobGriefing: String       # "true" or "false"
    doMobSpawning: String     # "true" or "false"
    randomTickSpeed: String   # "3" (numerical stored as string!)

  # Dimension & Boss State
  DimensionData: Compound     # Per-dimension state (e.g. The End Dragon Fight)
    1: Compound               # End dimension state
      DragonFight: Compound
        DragonUUIDMost: Long
        DragonUUIDLeast: Long
        ExitPortalLocation: Compound
        Gateways: List<Int>
        DragonKilled: Byte
        PreviouslyKilled: Byte

  # Mod & Capability Extensions
  ForgeCaps: Compound (Opt)   # Global world-level capabilities
  Player: Compound (Opt)      # Single-player client player state snapshot
```

### Critical Gamerule Invariant:
In Minecraft 1.12.2, **all game rule values inside `GameRules` are serialized as `TAG_String`**, even boolean and numerical values (e.g. `randomTickSpeed: "3"`, `doFireTick: "true"`). A parser attempting to read integer tags directly from the `GameRules` compound will receive a type mismatch default (0).
