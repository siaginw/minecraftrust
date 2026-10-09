# NBTTagCompound API Semantics & Type Coercion Model

## 1. Architectural Overview
`net.minecraft.nbt.NBTTagCompound` (Type ID 10) is the ubiquitous associative container across Minecraft 1.12.2 and Forge.
Internally, it wraps a standard Java hash map:
```java
private final Map<String, NBTBase> tagMap = Maps.newHashMap();
```
All entries consist of a Modified UTF-8 string key and a polymorphic `NBTBase` value pointer.

---

## 2. Missing Key & Type Mismatch Contract

Unlike standard Java collections that return `null` or throw `NoSuchElementException`, `NBTTagCompound` getter methods implement a strict "Safe Default / No Throw" contract for missing keys and mismatched types:

| Getter Method | Target Type ID | Missing Key Behavior | Wrong Type Behavior | Throwing Conditions |
| :--- | :--- | :--- | :--- | :--- |
| `getByte(key)` | 1 (or 99) | Returns `(byte) 0` | If numeric (99), coerces; else returns `0` | None |
| `getShort(key)` | 2 (or 99) | Returns `(short) 0` | If numeric (99), coerces; else returns `0` | None |
| `getInteger(key)` | 3 (or 99) | Returns `0` | If numeric (99), coerces; else returns `0` | None |
| `getLong(key)` | 4 (or 99) | Returns `0L` | If numeric (99), coerces; else returns `0L` | None |
| `getFloat(key)` | 5 (or 99) | Returns `0.0F` | If numeric (99), coerces; else returns `0.0F`| None |
| `getDouble(key)` | 6 (or 99) | Returns `0.0D` | If numeric (99), coerces; else returns `0.0D`| None |
| `getString(key)` | 8 | Returns `""` (empty) | Returns `""` (does not coerce) | None |
| `getByteArray(key)`| 7 | Returns `new byte[0]` | Returns `new byte[0]` | None |
| `getIntArray(key)` | 11 | Returns `new int[0]` | Returns `new int[0]` | None |
| `getCompoundTag(key)`| 10 | Returns `new NBTTagCompound()` | Returns `new NBTTagCompound()` | None |
| `getTagList(key, elemType)`| 9 | Returns `new NBTTagList()` | If list elemType mismatches, returns empty list | None |
| `getTag(key)` | Any | Returns `null` | N/A (returns raw `NBTBase`) | None |

### Critical Invariant:
**`getCompoundTag(key)` NEVER returns `null`.** Calling `compound.getCompoundTag("NonExistent").setString("foo", "bar")` will silently mutate a detached temporary compound without throwing a `NullPointerException`. Any Rust replacement or JNI facade must preserve this exact non-null allocation behavior.

---

## 3. Pseudo-Type 99 ("Any Numeric")

Minecraft defines synthetic type ID `99` as a match filter in `hasKey(String key, int type)`:
```java
public boolean hasKey(String key, int type) {
    int id = this.getTagId(key);
    if (id == type) {
        return true;
    } else if (type != 99) {
        return false;
    } else {
        return id == 1 || id == 2 || id == 3 || id == 4 || id == 5 || id == 6;
    }
}
```
All numeric types extend `NBTPrimitive`:
```java
abstract class NBTPrimitive extends NBTBase {
    public abstract long getLong();
    public abstract int getInt();
    public abstract short getShort();
    public abstract byte getByte();
    public abstract double getDouble();
    public abstract float getFloat();
}
```
When `getInteger("Health")` is called on a `TAG_Float(20.0f)`, Java invokes `((NBTPrimitive) tag).getInt()`, truncating the float to `20`. This coercion is relied upon heavily by Forge mods that serialize configuration numbers as bytes or shorts while consumers query them as ints.

---

## 4. Key Set Ordering & Serialization

- **In-Memory Order:** `tagMap` is a `java.util.HashMap`, meaning key order depends entirely on `String.hashCode()` and bucket distribution.
- **Debug Order:** In `toString()`, if `LOGGER.isDebugEnabled()` is true, keys are sorted alphabetically via `Collections.sort()`. In standard production runs, `toString()` prints in hash bucket order.
- **Wire Serialization Order:** `write(DataOutput)` iterates `tagMap.keySet()`. Because `HashMap` iteration order is arbitrary, **wire serialization order of compound keys is non-deterministic between JVM runs and non-deterministic between Java and Rust**.
- **Parity Requirement:** Parsers must treat `TAG_Compound` as an unordered map. Two compounds with identical key-value pairs serialized in different key order are semantically identical.

---

## 5. `merge(NBTTagCompound other)` Semantics

`NBTTagCompound.merge` performs recursive union with overwrite:
```java
public void merge(NBTTagCompound other) {
    for (String key : other.tagMap.keySet()) {
        NBTBase child = other.tagMap.get(key);
        if (child.getId() == 10) { // TAG_Compound
            if (this.hasKey(key, 10)) {
                this.getCompoundTag(key).merge((NBTTagCompound) child);
            } else {
                this.setTag(key, child.copy());
            }
        } else {
            this.setTag(key, child.copy());
        }
    }
}
```
- Non-compound tags overwrite existing keys with a deep `copy()`.
- Nested compound tags are recursively merged in place.
- Tag lists are **completely replaced**, not concatenated.
