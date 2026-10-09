# Learned: Forge Capability System Architecture & Runtime Performance

## 1. Overview & System Purpose
The Forge Capability System (`net.minecraftforge.common.capabilities`) provides a flexible, modular alternative to traditional monolithic class hierarchies and direct interface inheritance. It enables mods to attach functionality (inventories, energy systems, fluid tanks, mana, player statistics) to game carriers without modifying carrier class declarations.

---

## 2. Core Components

### 2.1 Carriers
The following core Minecraft objects implement `ICapabilityProvider` / `ICapabilitySerializable`:
- `TileEntity`
- `Entity`
- `ItemStack`
- `World`
- `Chunk`

### 2.2 Tokens & Registration (`CapabilityManager`)
- Capabilities are declared as unique typed tokens: `Capability<T>`.
- Registered during Pre-Init via:
  ```java
  CapabilityManager.INSTANCE.register(Class<T> type, IStorage<T> storage, Callable<? extends T> factory);
  ```
- Capabilities are assigned contiguous integer IDs (0, 1, 2...).
- Caches default implementations and NBT serialization handlers.

### 2.3 Carrier Attachment (`CapabilityDispatcher`)
- Carriers instantiate a `CapabilityDispatcher` if capabilities are attached via `AttachCapabilitiesEvent`.
- Internal structure of `CapabilityDispatcher`:
  - `ICapabilityProvider[] providers`: Array of attached providers.
  - `INBTSerializable<NBTBase>[] writers`: Subset of providers requiring NBT persistence.
  - `String[] names`: Namespaced keys for each attached capability provider.
- Query Resolution:
  ```java
  public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
      for (int i = 0; i < providers.length; i++) {
          T ret = providers[i].getCapability(capability, facing);
          if (ret != null) return ret;
      }
      return null;
  }
  ```
  - Linear scan across the `providers` array.
  - Returns the first non-null match.

### 2.4 Sided Capability Invariant
- Queries accept `@Nullable EnumFacing facing`.
- `facing != null`: Directional query (e.g. inserting into top of furnace vs pulling from bottom).
- `facing == null`: Internal or omni-directional query (e.g. GUI opening, internal machine logic).
- Verified in `CapabilityOracle.java` (Test 2): Querying `EnumFacing.NORTH` returned active energy capability; querying `SOUTH` returned null.

### 2.5 NBT Compound Serialization
- `CapabilityDispatcher.serializeNBT()` produces a compound tag containing individual NBT compound entries named after provider keys.
- `deserializeNBT()` iterates over keys and delegates reading to corresponding serializers.
- Verified in `CapabilityOracle.java` (Test 3): Energy compound stored and restored across save cycles.

---

## 3. Empirical Microbenchmark Data

From `tools/forge-bench/src/ForgeBenchmarks.java` (Java 8 HotSpot, 100,000 warmups, 500,000 iterations):

| Provider Count | `hasCapability` Latency | `getCapability` Latency | Scaling Status |
|---|---|---|---|
| **0 providers** | 7.55 ns/op | 2.84 ns/op | Immediate null return |
| **1 provider** | 19.80 ns/op | 19.95 ns/op | Single virtual call |
| **2 providers** | 15.74 ns/op | 1.55 ns/op | JIT monomorphic inline |
| **5 providers** | 17.87 ns/op | 18.84 ns/op | Linear scan across 5 providers |
| **10 providers** | 3.72 ns/op | 4.00 ns/op | NOT RESOLVED ABOVE BENCHMARK/JIT NOISE |
| **20 providers** | 7.37 ns/op | 7.70 ns/op | NOT RESOLVED ABOVE BENCHMARK/JIT NOISE |

### Sided Query Performance (5 providers)
- **Sided Hit (`EnumFacing.NORTH`)**: **7.34 ns/op**
- **Sided Miss (`EnumFacing.SOUTH`)**: **9.62 ns/op**

### Key Takeaways
1. **Architectural Mechanism**: Capability lookup is architecturally a linear Java scan across `providers[]`.
2. **Nanosecond Scaling Downgrade**: Exact per-provider nanosecond scaling is non-monotonic and **NOT RESOLVED ABOVE BENCHMARK/JIT NOISE** due to JIT loop unrolling, call-site monomorphism vs megamorphism, and micro-harness artifacts.
3. **FFI Hazard**: Regardless of exact in-JVM nanoseconds (3–20 ns range), a per-lookup JNI micro-crossing (~18.5 ns) would double or triple query latency. Lookups must remain in Java or be invoked in coarse batches.
