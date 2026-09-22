# Feasibility Analysis: Native-Backed Java NBT Facade

## 1. Architectural Concept
A recurring optimization hypothesis is to replace Java's heap-allocated `NBTTagCompound` and `NBTTagList` with lightweight Java facades backed by native Rust memory buffers (`jlong nativeHandle`).

Under this model:
- `compound.getInteger("xPos")` $\rightarrow$ calls JNI `nativeGetInt(this.handle, "xPos")`.
- `compound.setString("Name", "Steve")` $\rightarrow$ calls JNI `nativeSetString(this.handle, "Name", "Steve")`.
- The goal would be eliminating Java GC heap allocation for NBT object graphs.

---

## 2. Empirical Performance Evaluation

Our corrected JNI microbenchmark suite (v2) measured baseline invocation latency:
- **HotSpot JNI Call Overhead:** **$8.46\text{ ns}$** per crossing.
- **Java In-Memory `HashMap.get()`:** **$3.20\text{--}4.80\text{ ns}$** (when JIT-compiled with inline caching).

### Finding:
Replacing in-memory Java field access with JNI calls **doubles** the latency of single-tag lookups ($8.5\text{ ns}$ vs $3.5\text{ ns}$). In hot loops (e.g. querying block properties, checking entity attributes, ticking items), a native facade introduces significant CPU overhead rather than eliminating it.

---

## 3. Compatibility & Reflection Hazards

| Subsystem Area | Vanilla / Mod Practice | Impact of Native Facade |
| :--- | :--- | :--- |
| **Polymorphic Inheritance** | `NBTBase` extended by 13 concrete classes | Requires maintaining 13 facade subclasses matching exact binary layout. |
| **Direct Field Access / Reflection** | Mods access `private Map<String, NBTBase> tagMap` via reflection | **FATAL:** If `tagMap` is removed or replaced with a pointer, reflection throws `NoSuchFieldException`. |
| **Collection Operations** | Callers iterate `tagMap.keySet()` or call `tagMap.entrySet()` | Emulating `java.util.Map` interfaces over native memory requires allocating Java entry wrappers, negating zero-allocation goals. |
| **Memory Lifecycle & Leaks** | Java relies on GC for tree deallocation | Native buffers require manual freeing, `PhantomReference`, or `java.lang.ref.Cleaner`. Dropped NBT references in mods would cause silent off-heap memory leaks. |

---

## 4. Verdict & Architectural Decision

### Verdict: **REJECTED (Fine-Grained Native Facade)**

**Architectural Rationale:**
1. **Chatty JNI Hazard:** Violates Permanent Invariant 6 ("Fine-grained JNI/FFI in hot loops is forbidden").
2. **Breakage of Mod Reflection:** Breaks Forge mods that inspect `tagMap` or `tagList` directly.
3. **Negative Net Performance:** JNI boundary transitions on per-tag access are slower than JVM JIT-inlined HashMap lookups.

### Recommended Seam (Coarse Work Units):
Retain Java's native `NBTTagCompound` heap objects for live server-thread gameplay, while using **coarse-grained batch processing (`NbtCursor` and binary buffers)** for asynchronous chunk loading, decompression, saving, and network transport.
