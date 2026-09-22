# NBT Binary Specification & Wire Format: Minecraft 1.12.2 / Forge 14.23.5.x

## 1. Specification Overview
Named Binary Tag (NBT) is a structured, hierarchical binary serialization format developed by Mojang for storing arbitrary nested game state. In Minecraft 1.12.2 (DataVersion 1343) and Forge 14.23.5.2860, NBT is the authoritative format for region files (`.mca`), player data (`.dat`), world metadata (`level.dat`), structure templates (`.nbt`), and Forge chunk ticket registrations (`forcedchunks.dat`).

All multi-byte numeric primitives in NBT are strictly **big-endian (network byte order)**.

---

## 2. Complete Tag Type System (13 Tag Types)

Minecraft 1.12.2 defines 13 tag types (Type IDs 0 through 12) in `net.minecraft.nbt.NBTBase`:

| Type ID | Type Name | Java Class | Payload Wire Format | Fixed Payload Size | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **0** | `TAG_End` | `NBTTagEnd` | None | 0 bytes | Closes a `TAG_Compound`. Also used as element type for empty `TAG_List`. |
| **1** | `TAG_Byte` | `NBTTagByte` | `int8` (signed) | 1 byte | Used for booleans (0/1) and 8-bit integers. |
| **2** | `TAG_Short` | `NBTTagShort` | `int16` big-endian | 2 bytes | 16-bit signed integer. |
| **3** | `TAG_Int` | `NBTTagInt` | `int32` big-endian | 4 bytes | 32-bit signed integer. |
| **4** | `TAG_Long` | `NBTTagLong` | `int64` big-endian | 8 bytes | 64-bit signed integer. Used for world time, random seeds. |
| **5** | `TAG_Float` | `NBTTagFloat` | IEEE-754 single `f32` | 4 bytes | 32-bit floating point. |
| **6** | `TAG_Double` | `NBTTagDouble` | IEEE-754 double `f64` | 8 bytes | 64-bit floating point. Used for entity motion & positions. |
| **7** | `TAG_Byte_Array` | `NBTTagByteArray`| `int32` length $L$, then $L$ bytes | $4 + L$ bytes | Used for section block light, sky light, biomes. |
| **8** | `TAG_String` | `NBTTagString` | `uint16` byte length $M$, then $M$ MUTF-8 bytes | $2 + M$ bytes | Java Modified UTF-8. $M \le 65535$. |
| **9** | `TAG_List` | `NBTTagList` | `int8` elemType, `int32` length $N$, then $N$ payloads | $5 + \sum \text{payloads}$ | Homogeneous sequence. All elements share `elemType`. |
| **10** | `TAG_Compound` | `NBTTagCompound` | Sequence of named entries terminated by `TAG_End` (0) | Variable | Unordered key-value map. Keys are MUTF-8 strings. |
| **11** | `TAG_Int_Array` | `NBTTagIntArray` | `int32` length $L$, then $L \times 4$ bytes | $4 + 4L$ bytes | Packed 32-bit integers. Used for chunk `HeightMap`. |
| **12** | `TAG_Long_Array`| `NBTTagLongArray`| `int32` length $L$, then $L \times 8$ bytes | $4 + 8L$ bytes | Added in Minecraft 1.12. Used for 64-bit bitmasks. |
| **99** | *"Any Numeric Tag"*| Pseudo-ID | N/A (Predicate only) | N/A | Returned by `NBTBase.getTypeName(99)`. Used in `hasKey(key, 99)`. |

---

## 3. Pseudo-Type 99: "Any Numeric Tag"
In `NBTTagCompound.java`:
```java
public boolean hasKey(String key, int type) {
    int actualType = this.getTagId(key);
    if (actualType == type) {
        return true;
    } else if (type != 99) {
        return false;
    } else {
        return actualType == 1 || actualType == 2 || actualType == 3 
            || actualType == 4 || actualType == 5 || actualType == 6;
    }
}
```
- Type 99 never appears in serialized binary streams on disk.
- It is an in-memory query mask matching `TAG_Byte` (1), `TAG_Short` (2), `TAG_Int` (3), `TAG_Long` (4), `TAG_Float` (5), and `TAG_Double` (6).
- When mods check `compound.hasKey("Count", 99)`, any integer or floating-point tag returns true.

---

## 4. Java Modified UTF-8 (MUTF-8) Encoding Contract
All strings in NBT (`TAG_String` payloads and `TAG_Compound` entry keys) are serialized via `DataOutput.writeUTF()` and deserialized via `DataInput.readUTF()`:

1. **Length Header:** Big-endian 2-byte unsigned short ($0 \dots 65,535$). Strings whose encoded MUTF-8 length exceeds 65,535 bytes throw `java.io.UTFDataFormatException`.
2. **NUL Byte Encoding (`\u0000`):** Encoded as the 2-byte sequence `0xC0, 0x80`, never as a single `0x00` byte. This ensures no null bytes appear in string payloads.
3. **Supplementary Characters ($> \text{U+FFFF}$):** Encoded as **surrogate pairs** (`\uD83D \uDE00`), with each 16-bit surrogate unit encoded separately as a 3-byte sequence (total 6 bytes: `0xED 0xA0..0xAF, 0xED 0xB0..0xBF`). Standard UTF-8 uses a single 4-byte sequence (`0xF0 0x9F 0x98 0x80`).
4. **Rust Compatibility Hazard:** Rust's standard `String::from_utf8` rejects surrogate pairs as invalid UTF-8. A custom MUTF-8 decoder or `cesu8` conversion is strictly mandatory in Rust.

---

## 5. Recursion Depth Limits & Nesting Hazards
1. **Deserialization Limit (`read`):** Hardcoded in `NBTTagCompound.java:42` and `NBTTagList.java:36`:
   ```java
   if (depth > 512) {
       throw new RuntimeException("Tried to read NBT tag with too high complexity, depth > 512");
   }
   ```
   Payloads nested deeper than 512 levels throw `RuntimeException` and abort chunk loading.
2. **Serialization Hazard (`write`):** Vanilla Minecraft and Forge have **no depth check on `write()`**. Serialization is recursive (`writeEntry` $\rightarrow$ `tag.write()`). Constructing deep in-memory trees ($> 1,000$ levels) causes `StackOverflowError` in Java during autosave. Rust implementations must bound recursion to $\le 512$ during serialization to prevent native stack overflow.

---

## 6. NBTSizeTracker Accounting Model
`NBTSizeTracker` prevents decompression bombs and unbounded memory allocation when reading untrusted network packets or corrupted disk files.

In vanilla + Forge (`NBTSizeTracker.java` and `NBTSizeTracker.java.patch`):
- `read(long bits)` accounts for allocation in **bits**:
  $$\text{bytesRead} = \text{read} + \frac{\text{bits}}{8}$$
- **Tag Overhead Accounting:**
  - `TAG_Compound` start: `tracker.read(384L)` (48 bytes overhead).
  - Compound entry key: `tracker.read(224 + 16 * key.length())` bits.
  - Replaced key: `tracker.read(288L)` (36 bytes overhead).
  - `TAG_String`: `tracker.read(288L)` + `tracker.read(16 * string.length())` bits.
  - `TAG_List`: `tracker.read(288L)` bits.
  - Arrays (`byte[]`, `int[]`, `long[]`): `tracker.read(size_in_bits)`.
  - Forge Patch addition: `NBTSizeTracker.readUTF(tracker, string)` counts modified UTF-8 string bytes plus `32` bits (4 bytes) object overhead.
- When `read > max`, it immediately aborts with `RuntimeException("Tried to read NBT tag that was too big...")`. Default for disk I/O is `NBTSizeTracker.INFINITE` (unbounded). Default for network packets is $2,097,152$ bytes ($2\text{ MiB}$).