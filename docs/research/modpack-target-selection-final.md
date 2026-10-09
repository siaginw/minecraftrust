# Final Modpack Target Selection & Environmental Specification

## 1. Overview & Objectives
Stage P0-8 establishes empirical baselines across real-world Minecraft 1.12.2 / Forge 14.23.5.2860 server workloads. To evaluate what actually destroys TPS in modded environments without introducing uncontrolled variables, four standardized benchmark targets are formally designated.

## 2. Target Specifications

### Target A: Clean Forge Control (Target A)
- **Role**: Clean baseline control isolating vanilla engine and base Forge runtime overhead.
- **Minecraft Version**: `1.12.2` (Vanilla server JAR).
- **Forge Build**: `14.23.5.2860` (Universal binary).
- **Java Runtime**: Eclipse Temurin OpenJDK `1.8.0_504-b01` (HotSpot 64-Bit Server VM).
- **Mods**: 0 mods.
- **CoreMods**: 0 coremods.
- **Mixins**: 0 mixin libraries.
- **Access Transformers**: 0 AT rules.
- **JVM Arguments**: `-Xms4G -Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=20`
- **World Seed**: `403938725`
- **Acquisition**: Direct Forge installer automated bootstrap.

### Target B: Minimal Mod Corpus (Target B)
- **Role**: Minimal reproducible mod corpus covering key technical subsystems (capabilities, TileEntities, automated networks, inventories, recipes, custom blocks).
- **Minecraft Version**: `1.12.2`.
- **Forge Build**: `14.23.5.2860`.
- **Java Runtime**: Temurin `1.8.0_504-b01`.
- **Mods (8 total including libraries)**:
  1. `CoFH Core 4.6.6.20` (Core mod / base library)
  2. `Thermal Foundation 2.6.7.1` (Base materials, ores, fluids)
  3. `Thermal Expansion 5.5.7.1` (Machines, RF energy, fluid ducts)
  4. `Applied Energistics 2 rv6-stable-7` (Item storage networks, multi-slot inventories, NBT indexing)
  5. `Mantle 1.3.3.55` (Core library for Tinkers)
  6. `Tinkers' Construct 2.13.0.183` (Custom tools, multi-block smeltery, custom capabilities)
  7. `Iron Chests 7.0.72.847` (Dense TileEntity inventories)
  8. `JourneyMap 5.7.1` (Client-server packet sync)
- **CoreMods**: 1 (`CoFH Loading Plugin`).
- **Mixins**: 0.
- **Access Transformers**: 2 (`appliedenergistics2_at.cfg`, `cofh_at.cfg`).
- **JVM Arguments**: `-Xms4G -Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=20`
- **World Seed**: `403938725`
- **Acquisition**: CurseForge direct Maven artifact resolution.

### Target C: Primary Large Modpack (Target C)
- **Role**: Primary representative kitchen-sink modpack for end-to-end compatibility and performance profiling.
- **Pack Name & Version**: **FTB Revelation 3.4.0**.
- **Minecraft Version**: `1.12.2`.
- **Forge Build**: `14.23.5.2860` (Pinned).
- **Java Runtime**: Temurin `1.8.0_504-b01`.
- **Mod Count**: 212 enabled mods.
- **CoreMod Count**: 18 coremods (AstralCore, EnderCore, MalisisCore, ShetiPhian-Core, etc.).
- **Mixins**: 0 (Pure Forge AT / CoreMod pipeline).
- **Access Transformers**: 14 AT manifests.
- **JVM Arguments**: `-Xms6G -Xmx6G -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20`
- **World Seed**: `403938725`
- **Acquisition**: Feed The Beast App / CurseForge server archive distribution.

### Target D: Secondary Stress Modpack (Target D)
- **Role**: High-stress expert progression modpack stressing custom staging, script engines, deep dimensions, and dense automation.
- **Pack Name & Version**: **SevTech: Ages 3.2.3**.
- **Minecraft Version**: `1.12.2`.
- **Forge Build**: `14.23.5.2860` (Pinned).
- **Java Runtime**: Temurin `1.8.0_504-b01`.
- **Mod Count**: 274 enabled mods.
- **CoreMod Count**: 26 coremods.
- **Mixins**: 4 (MixinBootstrap, FoamFix, Phosphor, BetterFoliage).
- **Access Transformers**: 19 AT manifests.
- **Custom Mechanics**: Extensive CraftTweaker scripts, GameStages gating, 10,000+ custom recipe registrations.
- **JVM Arguments**: `-Xms8G -Xmx8G -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=25`
- **World Seed**: `403938725`
- **Acquisition**: CurseForge server pack archive with staging script manifests.

## 3. Version Control & Binary Licensing Invariant
In accordance with project rules and redistribution laws:
- Mod binaries (`.jar`) are excluded from Git tracking via `.gitignore`.
- Pack acquisition manifests, config checksums, and automated download scripts are committed in `machine/modpack-targets.yaml` and `tools/`.
