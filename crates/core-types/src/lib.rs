//! Foundational Minecraft 1.12.2 primitive coordinate and spatial types.
//! Tested for 100% parity with net.minecraft.util.math.BlockPos and ChunkPos.

/// Protocol 340 / Minecraft 1.12.2 BlockPos bit-packing constants
pub const NUM_X_BITS: u32 = 26;
pub const NUM_Z_BITS: u32 = 26;
pub const NUM_Y_BITS: u32 = 12;

pub const Y_SHIFT: u32 = NUM_Z_BITS; // 26
pub const X_SHIFT: u32 = Y_SHIFT + NUM_Y_BITS; // 38

pub const X_MASK: u64 = (1u64 << NUM_X_BITS) - 1; // 0x3FFFFFF (26 bits)
pub const Y_MASK: u64 = (1u64 << NUM_Y_BITS) - 1; // 0xFFF (12 bits)
pub const Z_MASK: u64 = (1u64 << NUM_Z_BITS) - 1; // 0x3FFFFFF (26 bits)

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BlockPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl BlockPos {
    pub const ORIGIN: Self = Self::new(0, 0, 0);

    #[inline]
    pub const fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    #[inline]
    pub const fn to_chunk_pos(&self) -> ChunkPos {
        ChunkPos {
            x: self.x >> 4,
            z: self.z >> 4,
        }
    }

    #[inline]
    pub const fn to_section_pos(&self) -> SectionPos {
        SectionPos {
            x: self.x >> 4,
            y: self.y >> 4,
            z: self.z >> 4,
        }
    }

    #[inline]
    pub const fn chunk_local_x(&self) -> usize {
        (self.x & 15) as usize
    }

    #[inline]
    pub const fn chunk_local_y(&self) -> usize {
        (self.y & 15) as usize
    }

    #[inline]
    pub const fn chunk_local_z(&self) -> usize {
        (self.z & 15) as usize
    }

    #[inline]
    pub const fn section_index(&self) -> i32 {
        self.y >> 4
    }

    /// Exact 1.12.2 BlockPos.toLong() bit packing:
    /// ((x & X_MASK) << 38) | ((y & Y_MASK) << 26) | (z & Z_MASK)
    #[inline]
    pub fn to_long(&self) -> i64 {
        let x_bits = (self.x as i64 & X_MASK as i64) << X_SHIFT;
        let y_bits = (self.y as i64 & Y_MASK as i64) << Y_SHIFT;
        let z_bits = self.z as i64 & Z_MASK as i64;
        x_bits | y_bits | z_bits
    }

    /// Exact 1.12.2 BlockPos.fromLong(long) bit unpacking with sign extension:
    /// x = (val << 0) >> 38
    /// y = (val << 26) >> 52
    /// z = (val << 38) >> 38
    #[inline]
    pub fn from_long(val: i64) -> Self {
        let x = (val << (64 - X_SHIFT - NUM_X_BITS) >> (64 - NUM_X_BITS)) as i32;
        let y = (val << (64 - Y_SHIFT - NUM_Y_BITS) >> (64 - NUM_Y_BITS)) as i32;
        let z = (val << (64 - NUM_Z_BITS) >> (64 - NUM_Z_BITS)) as i32;
        Self { x, y, z }
    }

    #[inline]
    pub const fn add(&self, dx: i32, dy: i32, dz: i32) -> Self {
        Self {
            x: self.x + dx,
            y: self.y + dy,
            z: self.z + dz,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ChunkPos {
    pub x: i32,
    pub z: i32,
}

impl ChunkPos {
    #[inline]
    pub const fn new(x: i32, z: i32) -> Self {
        Self { x, z }
    }

    #[inline]
    pub const fn from_block_pos(pos: BlockPos) -> Self {
        Self {
            x: pos.x >> 4,
            z: pos.z >> 4,
        }
    }

    /// Packed chunk key matching ChunkPos.asLong(int x, int z)
    /// (x & 0xFFFFFFFF) | ((z & 0xFFFFFFFF) << 32)
    #[inline]
    pub const fn as_long(&self) -> i64 {
        (self.x as i64 & 0xFFFFFFFF) | ((self.z as i64 & 0xFFFFFFFF) << 32)
    }

    #[inline]
    pub const fn from_long(val: i64) -> Self {
        Self {
            x: (val & 0xFFFFFFFF) as i32,
            z: ((val >> 32) & 0xFFFFFFFF) as i32,
        }
    }

    #[inline]
    pub const fn min_block_x(&self) -> i32 {
        self.x << 4
    }

    #[inline]
    pub const fn max_block_x(&self) -> i32 {
        (self.x << 4) + 15
    }

    #[inline]
    pub const fn min_block_z(&self) -> i32 {
        self.z << 4
    }

    #[inline]
    pub const fn max_block_z(&self) -> i32 {
        (self.z << 4) + 15
    }

    #[inline]
    pub const fn region_x(&self) -> i32 {
        self.x >> 5
    }

    #[inline]
    pub const fn region_z(&self) -> i32 {
        self.z >> 5
    }

    #[inline]
    pub const fn region_local_x(&self) -> usize {
        (self.x & 31) as usize
    }

    #[inline]
    pub const fn region_local_z(&self) -> usize {
        (self.z & 31) as usize
    }

    /// ChunkPos.hashCode() from reference implementation
    #[inline]
    pub fn reference_hash(&self) -> i32 {
        let i = 1664525i32.wrapping_mul(self.x).wrapping_add(1013904223);
        let j = 1664525i32
            .wrapping_mul(self.z ^ -559038737)
            .wrapping_add(1013904223);
        i ^ j
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct SectionPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl SectionPos {
    #[inline]
    pub const fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    #[inline]
    pub const fn chunk_pos(&self) -> ChunkPos {
        ChunkPos {
            x: self.x,
            z: self.z,
        }
    }

    #[inline]
    pub const fn block_index(lx: usize, ly: usize, lz: usize) -> usize {
        (ly << 8) | (lz << 4) | lx
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct DimensionId(pub i32);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct BlockStateId(pub u16);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Direction {
    Down,
    Up,
    North,
    South,
    West,
    East,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_block_to_chunk_and_local() {
        let cases = [
            (0, 0, 0, 0, 0, 0, 0, 0),
            (15, 64, 15, 0, 0, 15, 0, 15),
            (16, 64, 16, 1, 1, 0, 0, 0),
            (-1, 64, -1, -1, -1, 15, 0, 15),
            (-15, 64, -15, -1, -1, 1, 0, 1),
            (-16, 64, -16, -1, -1, 0, 0, 0),
            (-17, 64, -17, -2, -2, 15, 0, 15),
            (-31, 64, -31, -2, -2, 1, 0, 1),
            (-32, 64, -32, -2, -2, 0, 0, 0),
            (-33, 64, -33, -3, -3, 15, 0, 15),
        ];

        for (x, y, z, cx, cz, lx, ly, lz) in cases {
            let bp = BlockPos::new(x, y, z);
            let cp = bp.to_chunk_pos();
            assert_eq!(cp.x, cx, "cx mismatch for x={x}");
            assert_eq!(cp.z, cz, "cz mismatch for z={z}");
            assert_eq!(bp.chunk_local_x(), lx, "lx mismatch for x={x}");
            assert_eq!(bp.chunk_local_y(), ly, "ly mismatch for y={y}");
            assert_eq!(bp.chunk_local_z(), lz, "lz mismatch for z={z}");
        }
    }

    #[test]
    fn test_blockpos_packing_roundtrip() {
        let test_coords = [
            (0, 0, 0),
            (1, 1, 1),
            (-1, -1, -1),
            (15, 255, 15),
            (-16, 0, -16),
            (-17, 128, -17),
            (30000000, 255, 30000000),
            (-30000000, 0, -30000000),
            (33554431, 2047, 33554431), // Max positive 26-bit and 12-bit
            (-33554432, -2048, -33554432), // Min negative 26-bit and 12-bit
        ];

        for (x, y, z) in test_coords {
            let bp = BlockPos::new(x, y, z);
            let packed = bp.to_long();
            let unpacked = BlockPos::from_long(packed);
            assert_eq!(
                bp, unpacked,
                "Roundtrip failed for ({x}, {y}, {z}), packed=0x{packed:016x}"
            );
        }
    }

    #[test]
    fn test_against_golden_coordinates_corpus() {
        let path = std::path::Path::new("../../benchmarks/world/p0-6/golden_coordinates.txt");
        if !path.exists() {
            eprintln!(
                "Warning: golden_coordinates.txt not found at {:?}, skipping",
                path
            );
            return;
        }

        let content = std::fs::read_to_string(path).expect("failed to read golden_coordinates.txt");
        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }

            let parts: Vec<&str> = line.split('|').map(|s| s.trim()).collect();
            assert_eq!(parts[0], "BLOCKPOS");

            // input_args: x,y,z
            let in_coords: Vec<i32> = parts[1].split(',').map(|s| s.parse().unwrap()).collect();
            let (x, y, z) = (in_coords[0], in_coords[1], in_coords[2]);
            let bp = BlockPos::new(x, y, z);

            // packed hex
            let packed_hex = parts[2].trim_start_matches("0x");
            let expected_packed = u64::from_str_radix(packed_hex, 16).unwrap() as i64;
            assert_eq!(
                bp.to_long(),
                expected_packed,
                "to_long mismatch for ({x}, {y}, {z})"
            );

            let unpacked = BlockPos::from_long(expected_packed);
            assert_eq!(
                unpacked, bp,
                "from_long mismatch for 0x{expected_packed:016x}"
            );

            // chunk=cx,cz
            let chunk_str = parts[4].trim_start_matches("chunk=");
            let chunk_coords: Vec<i32> = chunk_str.split(',').map(|s| s.parse().unwrap()).collect();
            let cp = bp.to_chunk_pos();
            assert_eq!(
                (cp.x, cp.z),
                (chunk_coords[0], chunk_coords[1]),
                "chunk_pos mismatch for ({x}, {z})"
            );

            // local=lx,ly,lz
            let local_str = parts[5].trim_start_matches("local=");
            let local_coords: Vec<usize> =
                local_str.split(',').map(|s| s.parse().unwrap()).collect();
            assert_eq!(
                (bp.chunk_local_x(), bp.chunk_local_y(), bp.chunk_local_z()),
                (local_coords[0], local_coords[1], local_coords[2]),
                "local coord mismatch for ({x}, {y}, {z})"
            );

            // sec=secIdx
            let sec_str = parts[6].trim_start_matches("sec=");
            let expected_sec: i32 = sec_str.parse().unwrap();
            assert_eq!(
                bp.section_index(),
                expected_sec,
                "sec_index mismatch for y={y}"
            );

            // ckey=chunkKey
            let ckey_str = parts[7].trim_start_matches("ckey=0x");
            let expected_ckey = u64::from_str_radix(ckey_str, 16).unwrap() as i64;
            assert_eq!(
                cp.as_long(),
                expected_ckey,
                "chunk key mismatch for chunk ({}, {})",
                cp.x,
                cp.z
            );

            // chash=hash
            let chash_str = parts[8].trim_start_matches("chash=");
            let expected_chash: i32 = chash_str.parse().unwrap();
            assert_eq!(
                cp.reference_hash(),
                expected_chash,
                "chunk hash mismatch for chunk ({}, {})",
                cp.x,
                cp.z
            );
        }
    }
}
