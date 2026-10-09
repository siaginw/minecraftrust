# Research: Modpack Target Selection & Benchmark Corpus

## 1. Selection Criteria
To rigorously measure compatibility and performance across different stages of the Minecraft Rust runtime, representative Forge 1.12.2 modpacks are categorized by size, mod density, and architectural invasiveness.

Target modpacks must satisfy:
1. Pinned to Minecraft 1.12.2 and Forge 14.23.5.2847+ / 14.23.5.2860.
2. Widely played with reproducible server server packs.
3. Diverse coverage across Tech, Magic, World Generation, and CoreMods.

---

## 2. Selected Reference Modpacks (Provisional Candidates)

### Pack A: Minimal Benchmark Corpus (Fast CI Parity)
- **Target**: Clean Forge 14.23.5.2860 + 5 Anchor Mods:
  - Thermal Expansion (Tech / RF)
  - Applied Energistics 2 (Spatial storage, direct ATs)
  - Tinkers' Construct (Tool modifiers, fluid tanks)
  - Iron Chests (Basic tile entity capabilities)
  - JourneyMap (Entity tracking)
- **Mod Count**: ~15 mods (including dependencies).
- **CoreMod Count**: 1 (AE2).
- **Purpose**: Rapid automated regression testing and microbenchmark validation.

### Pack B: Primary General Modpack (Provisional: FTB Revelation 3.4.0)
- **Candidate Target**: FTB Revelation 3.4.0 (1.12.2).
- **Mod Count**: ~210 mods.
- **CoreMod Count**: ~18 CoreMods (Ender IO, Astral Sorcery, FoamFix, Phosphor, Chisel).
- **Status**: **PROVISIONAL** until exact server installation, Forge build pinning, coremod list, Mixins, AT files, automated headless launch, and reproducible execution are validated in P0-8.

### Pack C: Secondary Stress Modpack (Provisional: SevTech: Ages 3.2.3 or ATM3 6.1.1)
- **Candidate Target**: SevTech: Ages 3.2.3 or All The Mods 3 (ATM3 6.1.1).
- **Mod Count**: ~270 mods.
- **CoreMod Count**: ~26 CoreMods.
- **Status**: **PROVISIONAL** until exact server installation, custom dimension stability, CraftTweaker dependencies, and headless server reproducibility are validated in P0-8.

---

## 3. Workload Profiles for Benchmarking
1. **Startup / Load Phase**: Time from JVM launch to `SERVER_STARTED`. Measures class transformation, registry compilation, and OreDictionary initialization.
2. **Chunk Generation Storm**: 64 chunks requested concurrently at spawn. Measures chunk generation, lighting calculation, and block storage creation.
3. **High-Density TileEntity Farm**: 500 active ticking machines transferring items and energy via capabilities. Measures tick loop and capability lookup overhead.
4. **Player Exploration Loop**: Simulated bot player flying at speed 5 through unloaded terrain. Measures network packet serialization and chunk streaming throughput.
