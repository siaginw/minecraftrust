# Compatibility: Java Reference Identity & Pointer Comparison Hazards

## 1. The Reference Identity Invariant
In Java, object reference equality (`==` / `acmp_eq`) checks whether two reference pointers address the exact same object location on the Java virtual machine heap.
```java
if (stack.getItem() == Items.DIAMOND) { ... }
if (blockState.getBlock() == Blocks.AIR) { ... }
```
Across the Forge 1.12.2 ecosystem, hundreds of mods and vanilla systems use `==` instead of `.equals()` for checking blocks, items, tile entities, entities, dimensions, and capability tokens.

---

## 2. Why Naive Wrappers / Proxies Break
If a native hybrid runtime attempts to represent game objects by generating ephemeral Java proxy objects on each JNI call:
```java
// Anti-pattern: Allocating new proxy wrapper on query
public Item getItem() {
    return new ItemProxy(nativeGetItemId(this.nativeHandle));
}
```
1. **Broken Identity Checks**:
   - `stack.getItem() == Items.DIAMOND` evaluates to `false` because the newly allocated `ItemProxy` instance does not match the canonical singleton stored in `Items.DIAMOND`.
2. **Broken Collection Lookups**:
   - `IdentityHashMap` (heavily used in Forge internal caches and mod inventory systems) fails to locate keys because it hashes on `System.identityHashCode()`.
3. **Broken Synchronization**:
   - Synchronizing on an object monitor (`synchronized(tileEntity)`) locks distinct wrapper instances, eliminating thread mutual exclusion.

---

## 3. Empirical Verification (`tools/coremod-probe/src/CoreModProbe.java`)
In Part 2 of the probe suite:
- Verified `FACADE TEST 4 [Reference Identity Preservation]`: Caching and reusing canonical facade objects ensures `ref1 == ref2` evaluates to `true` (PASS).
- Subclassing and polymorphic dispatch (`FACADE TEST 3`) succeed cleanly without breaking virtual call tables.

---

## 4. Architectural Rules for Native Subsystem Migration
1. **Canonical Singleton Caching**:
   - For all registry-backed types (`Block`, `Item`, `Biome`, `Enchantment`), exactly one canonical Java object instance must exist on the JVM heap.
   - Any native query returning a block or item ID must look up the canonical singleton in a flat Java array or pre-populated table.
2. **Long-Lived Carrier Handles**:
   - Dynamic objects (`TileEntity`, `Entity`, `World`, `Chunk`) must retain a persistent Java object reference that maps 1-to-1 with the underlying native data structure (`long nativeHandle`).
   - Ephemeral proxy wrappers are strictly forbidden.
