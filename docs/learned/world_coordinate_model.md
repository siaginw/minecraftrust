# Learned: World Coordinate Model and Spatial Math

## 1. ChunkPos and Region Spatial Hierarchies
Spatial coordinate division in Minecraft 1.12.2 follows a strict power-of-two hierarchy:
- **Block**: Base unit (1x1x1).
- **Chunk Section**: 16x16x16 blocks (4,096 blocks).
- **Chunk**: 16x256x16 blocks (16 vertical sections, 65,536 blocks).
- **Region**: 32x32 chunks (512x256x512 blocks, 1,024 chunks).

## 2. Arithmetic Shift & Signed Two's Complement Invariants
In Java and Rust, integer right shift (`>>`) on signed types is arithmetic (sign-extending):
- **Block to Chunk**:
  - `chunk_x = block_x >> 4`
  - `chunk_z = block_z >> 4`
- **Chunk-Local Block Coordinate**:
  - `local_x = block_x & 15`
  - `local_y = block_y & 15`
  - `local_z = block_z & 15`
- **Section Index**:
  - `section_index = block_y >> 4` (valid range `[0, 15]`).
- **Chunk to Region**:
  - `region_x = chunk_x >> 5`
  - `region_z = chunk_z >> 5`
- **Region-Local Chunk Coordinate**:
  - `local_chunk_x = chunk_x & 31`
  - `local_chunk_z = chunk_z & 31`

## 3. Negative Coordinate Verification
Because `-1 >> 4 == -1` and `-1 & 15 == 15`:
- Block `(-1, 64, -1)`:
  - Chunk: `(-1, -1)`
  - Chunk start: `(-16, -16)`
  - Local coord: `(15, 64, 15)`
  - Reconstructed world block: `-16 + 15 = -1`. (Parity verified).
- Block `(-16, 64, -16)`:
  - Chunk: `(-1, -1)`
  - Local coord: `(0, 64, 0)`
  - Reconstructed world block: `-16 + 0 = -16`. (Parity verified).
- Block `(-17, 64, -17)`:
  - Chunk: `(-2, -2)`
  - Local coord: `(15, 64, 15)`
  - Reconstructed world block: `-32 + 15 = -17`. (Parity verified).

## 4. Chunk Key Packing & Hash Function
- **Packed 64-bit Long Key** (`ChunkPos.asLong`):
  `((long)x & 0xFFFFFFFFL) | (((long)z & 0xFFFFFFFFL) << 32)`
- **Reference Hash Function**:
  ```java
  int i = 1664525 * x + 1013904223;
  int j = 1664525 * (z ^ -559038737) + 1013904223;
  return i ^ j;
  ```
  Uses Numerical Recipes linear congruential constants to scramble grid coordinates, preventing bucket collisions in hash tables.
