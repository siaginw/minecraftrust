# Stringified NBT (SNBT) & JsonToNBT Parser Model

## 1. Overview & Use Cases
Stringified NBT (SNBT) is the human-readable, JSON-like representation used by Minecraft commands (`/give`, `/summon`, `/testfor`, `/blockdata`, `/entitydata`).
The reference parser is `net.minecraft.nbt.JsonToNBT`:
```java
public static NBTTagCompound getTagFromJson(String jsonString) throws NBTException;
```

---

## 2. Literal Type Suffixes & Coercion Rules

When parsing literal values outside quotes, `JsonToNBT` applies regex patterns to infer NBT tag types:

| Regex Pattern | Suffix / Syntax | Inferred NBT Type | Example |
| :--- | :--- | :--- | :--- |
| `[-+]?(?:0\|[1-9][0-9]*)b` | `b` / `B` | `TAG_Byte` | `1b`, `-5B` |
| `[-+]?(?:0\|[1-9][0-9]*)s` | `s` / `S` | `TAG_Short` | `300s` |
| `[-+]?(?:0\|[1-9][0-9]*)` | None (digits) | `TAG_Int` | `42`, `-100` |
| `[-+]?(?:0\|[1-9][0-9]*)l` | `l` / `L` | `TAG_Long` | `10000000000L` |
| `[-+]?...f` | `f` / `F` | `TAG_Float` | `3.14f` |
| `[-+]?...d` | `d` / `D` | `TAG_Double` | `2.718d` |
| Decimal point no suffix | `.` | `TAG_Double` | `2.718` |
| `"true"` / `"false"` | Case-insensitive | `TAG_Byte` | `true` $\rightarrow$ `(byte) 1` |
| Non-matching token | None | `TAG_String` | `unquoted_string` |
| Quoted string | `"..."` | `TAG_String` | `"with spaces and \escapes\""` |

---

## 3. Structural Parsing Model

- **Compound Syntax:** `{ key: value, "quoted_key": value }`
  - Keys may be unquoted if matching regex `[A-Za-z0-9._+-]+`.
  - Nested compounds recurse via `readStruct()`.
- **List Syntax:** `[ elem1, elem2, elem3 ]`
  - Elements must be homogeneous in inferred tag type.
- **Trailing Data Rejection:**
  - After reading root compound, any remaining non-whitespace characters throw `NBTException("Trailing data found")`.

---

## 4. Minecraft 1.12.2 vs Modern (1.13+) Divergence

In Minecraft 1.13+, Mojang introduced typed array prefixes:
- `[B; 1, 2, 3]` (`TAG_Byte_Array`)
- `[I; 10, 20, 30]` (`TAG_Int_Array`)
- `[L; 100L, 200L]` (`TAG_Long_Array`)

### In Minecraft 1.12.2:
- **Typed array syntax (`[I; ...]`) DOES NOT EXIST.**
- Square brackets `[...]` always parse to `TAG_List`.
- Array types (`TAG_Byte_Array`, `TAG_Int_Array`, `TAG_Long_Array`) cannot be directly constructed from standard vanilla 1.12.2 command SNBT without binary mod deserialization.
