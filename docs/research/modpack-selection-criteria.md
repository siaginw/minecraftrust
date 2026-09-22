# Modpack Selection Framework & Workload Classification

## 1. Objective
To evaluate real-world performance improvements and prevent subtle behavioral regressions, our server runtime must be benchmarked against progressive workload tiers ranging from pure vanilla to massive tech/magic modpacks.

This document establishes the evaluation taxonomy and candidate scoring criteria for selecting future benchmark packs.

## 2. Workload Classification Tiers

```text
┌────────────────────────────────────────────────────────┐
│ Tier A: Vanilla Server Workload                        │
│ Baseline protocol, redstone, entity physics, persistence│
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Tier B: Forge Clean Baseline (0 to 5 mods)            │
│ FML lifecycle, Forge event bus, zero mod tick load     │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Tier C: Medium Representative Pack (~80–120 mods)      │
│ Standard tech (EnderIO, Thermal, AE2), basic dimensions│
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Tier D: Heavy / Kitchen-Sink Pack (200–300+ mods)      │
│ Severe TileEntity load, automation, multi-networks     │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Tier E: Pathological Stress Test Environment           │
│ Abusive redstone clocks, fluid lag, entity cramming    │
└────────────────────────────────────────────────────────┘
```

## 3. Candidate Evaluation Dimensions

When evaluating prospective modpacks for Tiers C, D, and E, each candidate will be scored across 12 criteria:

| Dimension | Description | Architectural Sensitivity |
|---|---|---|
| **1. Mod Count** | Total active jars in `mods/` | Classloading & registry volume |
| **2. Forge Target Build** | Target Forge build (e.g. 2854, 2859, 2860) | API lifecycle compatibility |
| **3. TileEntity Density** | Active machine & storage entities per loaded chunk | Hot tick loop dispatch |
| **4. Logistics & Pipes** | Item, fluid, and energy transfer conduits (EnderIO, Thermal) | Capability invocation frequency |
| **5. Storage Networks** | Applied Energistics 2, Refined Storage | Spatial queries and inventory changes |
| **6. Custom Dimensions** | Twilight Forest, Galacticraft, RFTools | Multi-world tick concurrency |
| **7. World Generation** | Biomes O' Plenty, RTG, Recurrent Complex | Async generation & palette mutation |
| **8. Mob & Entity Load** | Spawners, mob farms, AI pathfinding | Pathfinding & collision broadphase |
| **9. Coremod / ASM Count** | Mods with `FMLCorePlugin` and `IClassTransformer` | Bytecode transformation risk |
| **10. AccessTransformers** | Number of forced public field/method mutations | FFI encapsulation hazards |
| **11. Ecosystem Standing** | Long-term playerbase, server popularity, bug history | Parity validation value |
| **12. Reproducibility** | Availability of deterministic server zip & locked configs | CI benchmark reliability |

## 4. Candidate Pool for Future Study (Not Yet Selected)

### Tier C Candidates (Medium)
- **FTB Revelation (Lite mode):** Standard tech + magic baseline.
- **Crucial 2 (1.12 backport style) / Vanilla+:** Light gameplay tweaks.

### Tier D Candidates (Heavy Kitchen-Sink & Expert)
- **Enigmatica 2: Expert (E2E):** ~260 mods, heavy automation, deep tech gating, high server popularity.
- **Nomifactory / Omnifactory:** GregTech Community Edition (GTCE) based, astronomical machine tick count, extreme automation.
- **SevTech: Ages:** Dynamic progression, customized dimensions, heavy coremod presence.
- **RLCraft v2.9:** Combat, custom mob AI, lycanites, intensive entity load.

### Tier E Candidates (Pathological Stress)
- **Deterministic Redstone Stress World:** 10,000 hopper clocks + comparator logic.
- **Fluids & Piston Farm:** Massive moving water updates and sticky piston arrays.
- **AE2 Auto-Crafting Loop:** High-frequency spatial inventory mutation.

## 5. Next Steps for Modpack Research
- Modpack download and installation is strictly deferred until P0-8 / P0-10.
- Selection will be governed by reproducible server packaging and deterministic save fixtures.
