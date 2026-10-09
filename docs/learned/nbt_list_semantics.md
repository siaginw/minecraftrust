# NBTTagList API Semantics & Homogeneous Type Rules

## 1. Internal Representation
`net.minecraft.nbt.NBTTagList` (Type ID 9) is an ordered, homogeneous sequence of `NBTBase` elements.
Internally, it holds:
```java
private List<NBTBase> tagList = Lists.newArrayList();
private byte tagType = 0;
```
- `tagType`: The type ID of elements currently contained.
- If the list is empty, `tagType == 0` (`TAG_End`).

---

## 2. Homogeneous Type Invariants & Insertion Rules

Minecraft enforces that all elements in a list share the exact same tag type:

```java
public void appendTag(NBTBase tag) {
    if (tag.getId() == 0) {
        LOGGER.warn("Invalid TagEnd added to ListTag");
    } else {
        if (this.tagType == 0) {
            this.tagType = tag.getId();
        } else if (this.tagType != tag.getId()) {
            LOGGER.warn("Adding mismatching tag types to tag list");
            return;
        }

        this.tagList.add(tag);
    }
}
```

### Key Behavioral Traits:
1. **Empty List Initialization:** When created with `new NBTTagList()`, `tagType` is `0`. The first non-End tag added pins `tagType` to that tag's ID.
2. **Type Mismatch Dropping:** Calling `appendTag(new NBTTagString("test"))` on an integer list logs a Log4j warning and **silently discards the element**. It does NOT throw `IllegalArgumentException`!
3. **`TAG_End` Insertion Rejection:** Attempting to insert `NBTTagEnd` logs a warning and discards the element.
4. **`set(int index, NBTBase tag)`:**
   - If `index < 0` or `index >= tagList.size()`: logs `"index out of bounds to set tag in tag list"` and returns without modifying.
   - If `tag.getId() != this.tagType`: logs `"Adding mismatching tag types to tag list"` and returns.

---

## 3. Safe Out-of-Bounds & Type Coercion Getters

Like `NBTTagCompound`, `NBTTagList` implements permissive accessors:

| Method | Normal Return | Out-of-Bounds Return | Wrong `tagType` Return |
| :--- | :--- | :--- | :--- |
| `get(int index)` | Element `NBTBase` | `new NBTTagEnd()` | Returns element |
| `getCompoundTagAt(int index)` | `(NBTTagCompound) elem` | `new NBTTagCompound()` | `new NBTTagCompound()` |
| `getIntAt(int index)` | `((NBTTagInt) elem).getInt()` | `0` | `0` (No type 99 coercion!) |
| `getDoubleAt(int index)` | `((NBTTagDouble) elem).getDouble()` | `0.0` | `0.0` |
| `getFloatAt(int index)` | `((NBTTagFloat) elem).getFloat()` | `0.0F` | `0.0F` |
| `getIntArrayAt(int index)` | `((NBTTagIntArray) elem).getIntArray()`| `new int[0]` | `new int[0]` |
| `getStringTagAt(int index)` | String value | `""` | **`elem.toString()`** |

### Noticeable Quirk on `getStringTagAt`:
If `getStringTagAt(0)` is invoked on a list of integers containing `[42]`, it checks:
```java
public String getStringTagAt(int index) {
    if (index >= 0 && index < this.tagList.size()) {
        NBTBase elem = this.tagList.get(index);
        return elem.getId() == 8 ? elem.getString() : elem.toString();
    } else {
        return "";
    }
}
```
Because the element is not ID 8 (`TAG_String`), it invokes `elem.toString()`, returning `"42"`.

---

## 4. Wire Protocol Invariants

On the binary wire, `TAG_List` consists of:
- `byte elemType` (1 byte)
- `int count` (4 bytes big-endian)
- Payload of `count` elements of type `elemType`.

### Wire Rules:
1. If `count == 0`, `elemType` on wire is typically `0` (`TAG_End`), although vanilla `write` outputs whatever `tagType` was set to.
2. If `count < 0`, `read` throws an `IOException` or negative allocation runtime exception.
3. Reading `TAG_List` with depth tracking: `depth` increments by 1 for the list itself, and if elements are `TAG_Compound` or `TAG_List`, increments again per element.
