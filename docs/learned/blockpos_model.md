# Learned: BlockPos Memory Model and Packed Long Bitfields

## 1. Bitfield Layout in Minecraft 1.12.2 (Protocol 340)
Minecraft 1.12.2 packs `BlockPos` into a single 64-bit signed integer (`long` / `i64`):
- `NUM_X_BITS = 26`
- `NUM_Y_BITS = 12`
- `NUM_Z_BITS = 26`
- `X_SHIFT = 38`
- `Y_SHIFT = 26`
- `Z_SHIFT = 0`

Bit positions in 64-bit word:
```
[63 .................... 38][37 .......... 26][25 .................... 0]
          X (26 bits)              Y (12 bits)              Z (26 bits)
```

## 2. Packing and Unpacking Formulas
### Java Reference:
```java
// Packing:
public long toLong() {
    return ((long)this.getX() & 0x3FFFFFFL) << 38
         | ((long)this.getY() & 0xFFFL) << 26
         | ((long)this.getZ() & 0x3FFFFFFL);
}

// Unpacking (with sign extension):
public static BlockPos fromLong(long packed) {
    int x = (int)(packed << 0 >> 38);
    int y = (int)(packed << 26 >> 52);
    int z = (int)(packed << 38 >> 38);
    return new BlockPos(x, y, z);
}
```

### Rust Implementation (`core-types`):
```rust
#[inline]
pub const fn to_long(&self) -> i64 {
    (((self.x as i64) & 0x3FFFFFF) << 38)
        | (((self.y as i64) & 0xFFF) << 26)
        | ((self.z as i64) & 0x3FFFFFF)
}

#[inline]
pub const fn from_long(packed: i64) -> Self {
    let x = (packed >> 38) as i32;
    let y = ((packed << 26) >> 52) as i32;
    let z = ((packed << 38) >> 38) as i32;
    Self { x, y, z }
}
```

## 3. Boundary Values & Negative Coordinate Guarantees
- Two's complement sign extension via arithmetic shift:
  - `-1` (`0xFFFFFFFFFFFFFFFF`): Unpacks to `(-1, -1, -1)`. Verified.
  - `(30000000, 255, 30000000)`: Packed as `0x7270e003fdc9c380`. Verified.
  - `(-30000000, 0, -30000000)`: Packed as `0x8d8f200002363c80`. Verified.
- Range limits:
  - X: `[-33,554,432, 33,554,431]` (World border is `±30,000,000`, well within range).
  - Y: `[-2,048, 2,047]` (World height is `[0, 255]`).
  - Z: `[-33,554,432, 33,554,431]`.
- Performance: Packing/unpacking compiles to single-digit cycle instructions (shift + bitwise or), taking under 1 nanosecond on modern x86_64.
