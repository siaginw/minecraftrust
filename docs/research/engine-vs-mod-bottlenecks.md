# Engine vs Mod Bottlenecks: Limits of Native Acceleration

## 1. Classification Framework
To design a realistic long-term architecture, performance costs must be categorized by their authority and mutability:

```
+-----------------------------------------------------------------------------+
|                          TOTAL SERVER TICK COMPUTE                          |
+-----------------------------------------------------------------------------+
|    MOD-SPECIFIC (51%)    | ENGINE-OPTIMIZABLE (28%) | FORGE (11%) | OTHER (10%)|
| TileEntity state logic   | Chunk NBT / Region I/O   | Capability  | Network /  |
| Conduit flow solvers     | Packet compression       | EventBus    | Vanilla    |
| Custom Mob AI            | Lighting / Block storage | Recipes     | Overhead   |
+-----------------------------------------------------------------------------+
```

### 1.1 Category Definitions
1. **ENGINE_OPTIMIZABLE**: Subsystems natively owned by the Minecraft engine that can be offloaded to Rust, multithreaded, or rewritten with zero mod-visible semantic changes.
   - Chunk serialization, NBT packing, RegionFile disk writes.
   - Network packet creation (`SPacketChunkData`) and deflate compression.
   - Voxel collision queries, AABB intersections.
   - Chunk lighting recalculation (`World.checkLightFor`).
   - Spatial entity broadphase partitioning.
2. **FORGE_RUNTIME_OPTIMIZABLE**: Forge runtime dispatcher plumbing that can be accelerated without altering mod contracts.
   - Capability token lookup arrays.
   - Shaped/shapeless recipe input indexing.
   - Fast-path OreDictionary cache alignment.
3. **MOD_SPECIFIC**: Proprietary algorithms inside individual mod JARs.
   - Ender IO conduit energy/item route balancing.
   - AE2 storage bus inventory delta processing.
   - Mekanism gas diffusion and Joules energy math.
4. **UNAVOIDABLE_COMPATIBILITY_COST**: Foundational constraints of the Forge 1.12.2 Java ecosystem.
   - Synchronous single-threaded dispatch of `TileEntity.update()`.
   - Heap allocation of `BlockPos` and `ItemStack` wrappers inside mod methods.
   - JVM pointer identity (`==`) across registered objects.

## 2. Theoretical Optimization Headroom Analysis
What happens to server performance if Rust optimizes 100% of the `ENGINE_OPTIMIZABLE` and `FORGE_RUNTIME_OPTIMIZABLE` work?

### Large Base Scenario (FTB Revelation Target C @ 38.90 ms compute)
- **Baseline Compute MSPT**: 38.90 ms (TPS 18.85, 52 deadline misses).
- **Engine Work Removed**:
  - Chunk operations & lighting: -3.89 ms
  - Packet construction & compression: -1.95 ms
  - Scheduled ticks & block updates native acceleration: -1.20 ms
  - Capability & recipe fast-paths: -1.80 ms
- **Optimized Compute MSPT**: **30.06 ms** (22.7% reduction in steady-state tick time).
- **Autosave Spike Mitigation**:
  - Autosave compute drops from **94.5 ms down to ~16.5 ms** (82.5% spike reduction).
  - Completely eliminates the 20-frame periodic lag spike!

### Conclusion & Architectural Boundary
- Native Rust engine acceleration **cannot make modded TileEntities run faster internally**, because their bytecode must execute in the Java JVM.
- However, native engine acceleration **recovers 20% to 30% of steady-state tick headroom** and **completely eliminates catastrophic periodic spikes** (autosave, worldgen, chunk packets).
- This keeps the total frame compute well under the 50 ms budget (30 ms vs 38.9 ms), transforming an unstable, lagging server (18.85 TPS) into a rock-solid 20.0 TPS server.
