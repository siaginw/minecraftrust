# NBT In-Memory Aliasing, Mutation, and Copy Semantics

## 1. Object Reference Sharing vs Copying

In the Java Minecraft and Forge runtime, `NBTBase` objects are standard heap-allocated Java references.
When calling `compound.setTag("foo", child)`:
```java
public void setTag(String key, NBTBase value) {
    this.tagMap.put(key, value);
}
```
**No copy is performed.** The `tagMap` holds the direct object pointer to `child`.

### Observable Consequences of Shared References:
1. **Multi-Parent Mutation:** If `child` is inserted into both `compoundA` and `compoundB`, modifying `child.setInteger("val", 10)` mutates the state observed through both parent compounds.
2. **`ItemStack` Mutation:** `ItemStack.getTagCompound()` returns the internal reference `this.stackTagCompound`. Mutating this compound directly updates the item's live properties without needing to call `stack.setTagCompound()`.
3. **`ChunkDataEvent.Save` Mutation:** Forge posts `ChunkDataEvent.Save` with the root chunk compound. Any mod modifying `event.getData()` mutates the exact object graph that is subsequently serialized to disk.

---

## 2. Deep Copy (`NBTBase.copy()`) Contract

Every NBT type implements `public abstract NBTBase copy();`:

| Class | `copy()` Implementation | Mutation Isolation |
| :--- | :--- | :--- |
| `NBTTagByte` | `new NBTTagByte(this.data)` | Isolated (Primitive copy) |
| `NBTTagShort` | `new NBTTagShort(this.data)` | Isolated (Primitive copy) |
| `NBTTagInt` | `new NBTTagInt(this.data)` | Isolated (Primitive copy) |
| `NBTTagLong` | `new NBTTagLong(this.data)` | Isolated (Primitive copy) |
| `NBTTagFloat` | `new NBTTagFloat(this.data)` | Isolated (Primitive copy) |
| `NBTTagDouble`| `new NBTTagDouble(this.data)` | Isolated (Primitive copy) |
| `NBTTagString`| `new NBTTagString(this.data)` | Isolated (String immutable) |
| `NBTTagByteArray` | `System.arraycopy` to new `byte[]` | **Isolated (Array clone)** |
| `NBTTagIntArray` | `System.arraycopy` to new `int[]` | **Isolated (Array clone)** |
| `NBTTagLongArray`| `System.arraycopy` to new `long[]` | **Isolated (Array clone)** |
| `NBTTagList` | Creates new list, recurses `.copy()` on every child | **Isolated (Recursive deep clone)** |
| `NBTTagCompound`| Creates new compound, recurses `.copy()` on every child | **Isolated (Recursive deep clone)** |

### Critical Rule:
In Minecraft Java Edition, **`copy()` is strictly recursive and deep**. There are no shallow copies in the NBT type hierarchy.

---

## 3. Rust Architectural Implications

1. **Rust Ownership Model:**
   - In Rust, `NbtTag` enum values have unique ownership (`Send` + `Sync`).
   - A sub-tree cannot have multiple parent owners unless wrapped in `Arc<RwLock<NbtTag>>` or reference-counted handles.
2. **Crossing the FFI Boundary:**
   - When transferring NBT trees between Java and Rust, representing the Java object graph in Rust either requires cloning the entire tree (matching Java's `copy()` behavior) or maintaining native handles with synchronization.
   - For chunk snapshots and network packets, this deep isolation is beneficial: snapshots are immutable work units that cannot be corrupted by subsequent Java main-thread mutations.
