# M4.1 Research: Canonical 1.12.2 BlockStateContainer Palette Semantics

**Date**: 2026-09-21  
**Source**: `third_party_reference/minecraft/src/net/minecraft/world/chunk/`

---

## 1. Palette Type Transitions

```java
private void setBits(int bits) {
    if (bits != this.bits) {
        this.bits = bits;
        if (this.bits <= 4) {
            this.bits = 4;
            this.palette = new BlockStatePaletteLinear(this.bits, this);
        } else if (this.bits <= 8) {
            this.palette = new BlockStatePaletteHashMap(this.bits, this);
        } else {
            this.palette = REGISTRY_BASED_PALETTE;
            this.bits = MathHelper.log2DeBruijn(Block.BLOCK_STATE_IDS.size());
        }
        this.palette.idFor(AIR_BLOCK_STATE);
        this.storage = new BitArray(this.bits, 4096);
    }
}
```

### Transition Rules:
| Local Palette Size | bits_per_block | Palette Implementation | Wire Format |
|---|---|---|---|
| 1–16 states | 4 | BlockStatePaletteLinear | Local palette written |
| 17–256 states | 5–8 | BlockStatePaletteHashMap | Local palette written |
| >256 states | ceil(log2(global_registry_size)) | BlockStatePaletteRegistry | **No local palette** (writes 0), uses global registry IDs |

**Key**: The global registry palette writes `palette_len=0` on wire and uses global `Block.BLOCK_STATE_IDS` integer IDs directly in the BitArray.

---

## 2. BlockStatePaletteLinear (4-bit)

- **Backing**: `IBlockState[] states` with capacity `1 << bits` (16 for 4-bit)
- **idFor**: Linear search `O(n)` where n = palette size (max 16)
- **Insertion**: Appends to array when `arraySize < states.length`
- **Resize**: Calls `resizeHandler.onResize(bits + 1, state)` when full

---

## 3. BlockStatePaletteHashMap (5–8 bit)

- **Backing**: `IntIdentityHashBiMap<IBlockState>` (Forge's identity map)
- **idFor**: `O(1)` lookup via identity hash
- **Insertion**: Adds to bimap, checks if `id >= 1 << bits`
- **Resize**: Triggers when new ID would exceed bit width capacity

---

## 4. BlockStatePaletteRegistry (Global Palette)

- **idFor**: Returns `Block.BLOCK_STATE_IDS.get(state)` (global registry ID)
- **getBlockState**: Returns `Block.BLOCK_STATE_IDS.getByValue(id)`
- **Wire write**: `writeVarInt(0)` — **no local palette entries written**
- **Storage**: BitArray stores global registry IDs directly (up to 16+ bits)

---

## 5. BitArray (Packed Storage)

```java
public BitArray(int bitsPerEntry, int arraySize) {
    this.arraySize = arraySize;
    this.bitsPerEntry = bitsPerEntry;
    this.maxEntryValue = (1L << bitsPerEntry) - 1L;
    this.longArray = new long[MathHelper.roundUp(arraySize * bitsPerEntry, 64) / 64];
}

public void setAt(int index, int value) {
    int bitPos = index * bitsPerEntry;
    int word0 = bitPos / 64;
    int word1 = ((index + 1) * bitsPerEntry - 1) / 64;
    int offset = bitPos % 64;
    // Single-word case
    if (word0 == word1) {
        longArray[word0] = (longArray[word0] & ~(maxEntryValue << offset)) | ((value & maxEntryValue) << offset);
    } else {
        // Cross-word spanning
        int highBits = 64 - offset;
        int lowBits = bitsPerEntry - highBits;
        longArray[word0] = (longArray[word0] >>> lowBits << lowBits) | ((value & maxEntryValue) >> highBits);
        longArray[word1] = (longArray[word1] & ~((1L << lowBits) - 1)) | ((value & maxEntryValue) << highBits);
    }
}
```

### Cross-Word Spanning Examples:
| bits_per_entry | entries/word | spans_boundary? | 4096 entries → longs |
|---|---|---|---|
| 4 | 16 | No (64 % 4 = 0) | 256 |
| 5 | 12 | Yes | 320 |
| 6 | 10 | Yes | 384 |
| 7 | 9 | Yes | 448 |
| 8 | 8 | No (64 % 8 = 0) | 512 |
| 9 | 7 | Yes | 576 |
| 10 | 6 | Yes | 640 |
| 12 | 5 | Yes | 768 |
| 13 | 4 | Yes | 1331 |
| 16 | 4 | No (64 % 16 = 0) | 1024 |

---

## 6. Protocol 340 Wire Encoding (BlockStateContainer.write)

```java
public void write(PacketBuffer buf) {
    buf.writeByte(this.bits);                           // 1 byte: bits_per_block
    this.palette.write(buf);                            // VarInt palette_len + VarInt[] global_state_ids
    buf.writeVarInt(this.storage.size());               // VarInt: data length in longs
    buf.writeLongArray(this.storage.getBackingLongArray());  // Big-endian longs
}
```

### Global Palette Wire Behavior:
- When `bits > 8`: `palette.write(buf)` writes `VarInt(0)` — **zero palette entries**
- BitArray stores global `Block.BLOCK_STATE_IDS` values directly
- Decoder reads `bits` from packet, if `bits > 8` it knows to use global registry

---

## 7. Implications for M4.1 Generalized NativeSection

### Current M4 Limitation:
- Assumes fixed 4-bit palette `[u32; 16]` + `[u64; 256]`
- Cannot represent 5-8 bit local palettes or global palette

### Required Generalization:
1. **Dynamic bit width**: 4–16+ bits per entry
2. **Variable storage size**: `roundUp(4096 * bits, 64) / 64` longs
3. **Three palette modes**:
   - Linear (array, capacity 1<<bits) for 4-bit
   - HashMap for 5-8 bit  
   - Global registry mode (no local palette) for >8 bit
4. **Wire parity**: Must produce bit-exact Protocol 340 encoding

### Design Options:

#### Option A: Packed Local Palette (Wire-Matching)
- Store exactly what goes on wire: local palette IDs + packed data
- Must handle palette resize → full re-encode
- Memory efficient for typical sections

#### Option B: Canonical Native State IDs (Engine-First)
- Store global `Block.BLOCK_STATE_IDS` (u16/u32) per block
- Build local palette only on demand for wire encoding
- Simpler mutation, better for collision/lighting consumers
- More memory (4096 * 4 = 16 KB vs ~2 KB for 4-bit)

#### Option C: Hybrid (Recommended)
- **Engine representation**: Global state IDs (u16) + optional local palette cache
- **Wire representation**: Derived on-demand matching Protocol 340 exactly
- Fast random access, efficient wire path, memory moderate (~8 KB/section)

---

## 8. NEID / FoamFix / Modpack Impact

### NEID:
- Extends NBT block ID to 32-bit for persistence
- **Does not change Protocol 340 wire format** — still uses BitArray + palette
- Base terrain (Stone/Water/Air) uses standard IDs

### FoamFix:
- Replaces palette implementations with deduplicated variants
- Does not affect ChunkPrimer or network wire encoding
- Internal optimization only

### Revelation / SevTech:
- Large modded registries (4000+ block states)
- Generated terrain typically uses only vanilla states in base terrain
- Decoration/structures introduce modded states → palette expansion
- Must handle transition from 4-bit → 5-8-bit → global palette correctly

---

## 9. Next Steps

1. Research Valence/Lithium/Oxide/Cuberite internal representations
2. Design Generalized NativeSection (Option C Hybrid)
3. Build dynamic palette parity harness
4. Re-validate M1 direct-native path with generalized sections