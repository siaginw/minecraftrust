# Learned: Forge Registry Runtime, ID Management & Lifecycle

## 1. Overview & Core Architecture
Forge 1.12.2 replaces vanilla Minecraft's hardcoded numeric and static map registries with `ForgeRegistry<T>`, managed by `RegistryManager`. This subsystem governs blocks, items, biomes, enchantments, potions, sound events, villager professions, and recipe types.

---

## 2. Structural Design

### 2.1 Internal Data Structures
Inside `ForgeRegistry<V extends IForgeRegistryEntry<V>>`:
- `names`: `BiMap<ResourceLocation, V>` — Namespaced string identifier to object.
- `ids`: `BiMap<Integer, V>` — Numeric integer ID to object.
- `availabilityMap`: `BitSet` — Bitmask tracking allocated numeric ID slots.
- `aliases`: `Map<ResourceLocation, ResourceLocation>` — String aliases mapping legacy names to modern names.
- `dummies`: `Set<ResourceLocation>` — Tracks dummy entries created for missing world entries.
- `overrides`: `ListMultimap<ResourceLocation, V>` — History of mod overrides replacing an existing registry name.
- `owners`: `BiMap<OverrideOwner, V>` — Tracks which mod container owns which entry/override.

### 2.2 Numeric ID Allocation & Invariants
- **Bounds**:
  - Blocks: Range `0..4095` (12 bits). Upper bound capped by `GameData.MAX_BLOCK_ID = 4095`.
  - Items: Range `0..31999` (can extend to `32767`).
- **Deterministic ID Assignment**:
  - Vanilla blocks and items occupy hardcoded low ID ranges (0..255 for vanilla blocks).
  - Modded entries receive sequential IDs assigned dynamically during startup.
- **Uniqueness Invariants**:
  1. No duplicate `ResourceLocation` names allowed unless `allowOverrides` is enabled and the registrant provides an explicit owner.
  2. Only one `ForgeRegistry` per superclass type across the entire JVM (enforced by `RegistryManager.createRegistry`, throwing `IllegalArgumentException`).
  3. Every registry entry is assigned a `RegistryDelegate<V>` that provides indirection for `@ObjectHolder` injection.

---

## 3. Registry Lifecycle States

```
[CONSTRUCT] -> [REGISTERING] -> [FROZEN] -> [WORLD_LOAD_REMAP] -> [ACTIVE]
```

### 3.1 Registration Phase (`RegistryEvent.Register<T>`)
- Mod lifecycle event where mods register blocks, items, etc.
- Overrides are allowed during this stage if `allowOverrides` is true.

### 3.2 Freeze Gate (`freeze()`)
- Executed during Post-Init.
- Locks the registry.
- Invariant: Calling `reg.register()` or `reg.addAlias()` on a frozen registry immediately throws `IllegalStateException`. Verified via `RegistryOracle.java` (Test 3).

### 3.3 World Load Remapping (`loadIds`)
- When loading a world save, Forge reads `level.dat` (`FML -> Registries`).
- Worlds store a snapshot: `ResourceLocation -> Integer ID`.
- Forge compares the world snapshot with the runtime registry:
  1. **Matches**: ID mapping preserved.
  2. **ID Mismatches**: Forge swaps local numeric IDs to match the world file (`remapped`).
  3. **Missing Entries (Mods Removed)**:
     - Missing mappings fire `RegistryEvent.MissingMappings<T>`.
     - Mods can ignore, remap, fail, or warn.
     - If unhandled, Forge inserts a **dummy entry** via `dummyFactory` or marks the slot in `dummies` so blocks in the world do not shift or corrupt other mod IDs.
  4. **Substitutions**: Active overrides replace references via `RegistryDelegate.changeReference()`.

---

## 4. Empirical Oracle Findings (`tools/forge-oracle/src/RegistryOracle.java`)
- **Test 1**: Basic registration correctly allocates monotonic IDs (ID 0, ID 1).
- **Test 2**: Duplicate name registrations without override permissions throw runtime exception.
- **Test 3**: Registry freeze immutability prevents post-freeze modifications (`IllegalStateException`).
- **Test 4**: Alias remapping transparently resolves legacy names to new entries.
- **Test 5**: Missing / removed entries return `null` on name and ID queries.
- **Test 6**: Dummy entries hold numeric ID slots without initializing full game blocks.
- **Test 7**: `makeSnapshot()` produces consistent, serializable snapshots of active IDs.
- **Test 8**: Substitutions allow replacing object implementations while preserving original numeric ID.

---

## 5. Invariants for Rust Hybrid Architecture
1. **Never Assume Static Global Numeric IDs Across Worlds**:
   - Because Forge remaps numeric IDs per-world on load, Rust data structures (block palettes, entity IDs) must receive and apply remappings on world load.
2. **Honor Delegate Indirection**:
   - Mod code caches `IRegistryDelegate<Item>`. If a block or item is substituted by a mod, the delegate's internal pointer updates dynamically.
