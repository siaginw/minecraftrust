# Learned: BlockStateContainer, BitArray, and Palette Transitions

## 1. Palette Tier Transitions
Minecraft 1.12.2 dynamically compresses block states within each 4,096-block section using adaptive palettes:
- **Tier 1: Linear Palette (`BlockStatePaletteLinear`)**:
  - Bits per entry: `4 bits`
  - Capacity: `16 unique states`
  - Backing storage: `IBlockState[16]` array, linear search for state ID.
  - Initial default state for all newly created sections.
- **Tier 2: Hash Map Palette (`BlockStatePaletteHashMap`)**:
  - Trigger: When the 17th unique state is added (`bits = 5`).
  - Bits per entry: `5 to 8 bits` (capacities: 32, 64, 128, 256).
  - Backing storage: `IntIdentityHashBiMap<IBlockState>` for fast hashing.
- **Tier 3: Global Registry Palette (`BlockStatePaletteRegistry`)**:
  - Trigger: When the 257th unique state is added (`bits > 8`).
  - Bits per entry: Dynamic: `MathHelper.log2DeBruijn(Block.BLOCK_STATE_IDS.size())`.
    - In unmodded vanilla (~8,192 states): **13 bits**.
    - In modded registries (8,193 to 16,384 states): **14 bits**.
    - In heavily modded registries (16,385 to 32,768 states): **15 bits**.
    - In maximum Forge registries (32,769 to 65,536 states): **16 bits** (capped by `GameData.MAX_BLOCK_ID = 4095`).
  - Backing storage: Direct global numeric ID from `Block.BLOCK_STATE_IDS`. No local palette array needed.

## 2. Palette Resizing Cost & Latency Spikes
When a palette transitions to the next bit tier, `onResize()` reallocates the backing `BitArray` and iterates through all 4,096 entries to remap old palette IDs to new palette IDs.
Measured latency spikes:
- **Linear (4-bit) -> HashMap (5-bit)**:
  - Mean: **44.98 µs** | p50: **34.30 µs** | p95: **86.90 µs** | Max: **833.0 µs**.
- **HashMap (8-bit) -> Global (13-bit)**:
  - Mean: **39.73 µs** | p50: **35.50 µs** | p95: **39.80 µs** | Max: **593.8 µs**.
Finding: A single block placement that triggers a palette resize causes a ~35–45 µs micro-stall on the server thread. While rare in vanilla, dense modded build areas frequently cross these thresholds.

## 3. BitArray Word Spanning in Protocol 340 (1.12.2)
Crucial format distinction:
- In Minecraft 1.12.2, `BitArray` packs bits continuously across 64-bit `long` boundaries without padding!
- For example, if entry $i$ ends at bit 61 of word 0, the remaining 3 bits of a 4-bit entry are placed in bits 0..2 of word 1!
- Total backing longs for 4,096 entries: `ceil(4096 * bits / 64) = 64 * bits`.
  - 4 bits = 256 longs
  - 5 bits = 320 longs
  - 8 bits = 512 longs
  - 13 bits = 832 longs
Modern Minecraft (1.16+) changed BitArray to NOT span across long words. Any 1.12.2 Rust implementation must strictly follow the spanning packing formula.
