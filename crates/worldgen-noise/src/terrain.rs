//! M3W5: ChunkPrimer base terrain generation (RUST_PARITY & RUST_OPTIMIZED).
//!
//! Owns 3D trilinear interpolation of the 5x33x5 density field (825 doubles)
//! into a 16x16x256 voxel ChunkPrimer buffer matching vanilla Minecraft 1.12.2
//! `ChunkGeneratorOverworld.func_185976_a`.

pub const CHUNK_PRIMER_SIZE: usize = 65536; // 16 * 16 * 256
pub const DEFAULT_SEA_LEVEL: i32 = 63;

// Canonical Minecraft 1.12.2 block state IDs: (block_id << 4) | meta.
pub const STATE_AIR: u16 = 0;       // Blocks.AIR.getDefaultState()
pub const STATE_STONE: u16 = 16;    // Blocks.STONE.getDefaultState() (id 1, meta 0)
pub const STATE_BEDROCK: u16 = 112; // Blocks.BEDROCK.getDefaultState() (id 7, meta 0)
pub const STATE_WATER: u16 = 144;   // Blocks.WATER.getDefaultState() (id 9, meta 0)

/// Compute the flat index in ChunkPrimer: (x << 12) | (z << 8) | y.
///
/// Matches bytecode in `net.minecraft.world.chunk.ChunkPrimer.func_186137_b`.
#[inline(always)]
pub const fn chunk_primer_index(x: usize, y: usize, z: usize) -> usize {
    (x << 12) | (z << 8) | y
}

/// Managed ChunkPrimer: owns a flat 65536-element array of blockstate IDs.
///
/// Matches Java `net.minecraft.world.chunk.ChunkPrimer` memory layout.
#[derive(Clone, Debug)]
pub struct ChunkPrimer {
    pub data: Box<[u16; CHUNK_PRIMER_SIZE]>,
}

impl Default for ChunkPrimer {
    fn default() -> Self {
        Self::new()
    }
}

impl ChunkPrimer {
    pub fn new() -> Self {
        Self {
            data: vec![STATE_AIR; CHUNK_PRIMER_SIZE]
                .into_boxed_slice()
                .try_into()
                .unwrap(),
        }
    }

    #[inline(always)]
    pub fn get_block_state(&self, x: usize, y: usize, z: usize) -> u16 {
        self.data[chunk_primer_index(x, y, z)]
    }

    #[inline(always)]
    pub fn set_block_state(&mut self, x: usize, y: usize, z: usize, state: u16) {
        self.data[chunk_primer_index(x, y, z)] = state;
    }

    #[inline(always)]
    pub fn clear(&mut self) {
        self.data.fill(STATE_AIR);
    }
}

/// RUST_PARITY: Straightforward exact port of `ChunkGeneratorOverworld.func_185976_a`.
///
/// Evaluates the 4x4x32 cell grid with identical floating-point accumulator arithmetic
/// and identical loop nesting order as Java bytecode.
pub fn set_blocks_in_chunk_parity(
    height_map: &[f64; 825],
    sea_level: i32,
    stone_id: u16,
    water_id: u16,
    primer: &mut [u16; CHUNK_PRIMER_SIZE],
) {
    for ix in 0..4usize {
        let px = ix * 5;
        let pxx = (ix + 1) * 5;

        for iz in 0..4usize {
            let pxxxx = (px + iz) * 33;
            let pxxxxx = (px + iz + 1) * 33;
            let pxxxxxx = (pxx + iz) * 33;
            let pxxxxxxx = (pxx + iz + 1) * 33;

            for iy in 0..32usize {
                let mut p_h00 = height_map[pxxxx + iy];
                let mut p_h01 = height_map[pxxxxx + iy];
                let mut p_h10 = height_map[pxxxxxx + iy];
                let mut p_h11 = height_map[pxxxxxxx + iy];

                let dh00 = (height_map[pxxxx + iy + 1] - p_h00) * 0.125;
                let dh01 = (height_map[pxxxxx + iy + 1] - p_h01) * 0.125;
                let dh10 = (height_map[pxxxxxx + iy + 1] - p_h10) * 0.125;
                let dh11 = (height_map[pxxxxxxx + iy + 1] - p_h11) * 0.125;

                for sub_y in 0..8usize {
                    let mut var_y0 = p_h00;
                    let mut var_y1 = p_h01;
                    let dx0 = (p_h10 - p_h00) * 0.25;
                    let dx1 = (p_h11 - p_h01) * 0.25;

                    for sub_x in 0..4usize {
                        let dz = (var_y1 - var_y0) * 0.25;
                        let mut density = var_y0 - dz;

                        let x = ix * 4 + sub_x;
                        let y = iy * 8 + sub_y;

                        for sub_z in 0..4usize {
                            density += dz;
                            let z = iz * 4 + sub_z;
                            let idx = chunk_primer_index(x, y, z);

                            if density > 0.0 {
                                primer[idx] = stone_id;
                            } else if (y as i32) < sea_level {
                                primer[idx] = water_id;
                            }
                        }

                        var_y0 += dx0;
                        var_y1 += dx1;
                    }

                    p_h00 += dh00;
                    p_h01 += dh01;
                    p_h10 += dh10;
                    p_h11 += dh11;
                }
            }
        }
    }
}

/// RUST_OPTIMIZED: Column-major sequential streaming kernel.
///
/// Within each 4x4 horizontal cell (ix, iz), the 16 vertical columns are
/// buffered or traversed so that writes to `primer` are contiguous in Y
/// (`base_idx + y` for y = 0..255).
///
/// ponytail: Pre-interpolates the 33 corner values along the cell's 4x4 columns,
/// then streams sequential linear interpolations down each 256-block column into L1/L2.
pub fn set_blocks_in_chunk_column_major(
    height_map: &[f64; 825],
    sea_level: i32,
    stone_id: u16,
    water_id: u16,
    primer: &mut [u16; CHUNK_PRIMER_SIZE],
) {
    // 16 columns of 33 vertical node values each (16 * 33 = 528 f64 = 4.2 KB in L1).
    let mut col_nodes = [0.0f64; 16 * 33];

    for ix in 0..4usize {
        let px = ix * 5;
        let pxx = (ix + 1) * 5;

        for iz in 0..4usize {
            let p00 = (px + iz) * 33;
            let p01 = (px + iz + 1) * 33;
            let p10 = (pxx + iz) * 33;
            let p11 = (pxx + iz + 1) * 33;

            // Phase 1: Precompute the 33 coarse node densities for all 16 columns in this cell.
            // Pure horizontal bilinear interpolation on the 4 corners at each iy.
            for iy in 0..33usize {
                let h00 = height_map[p00 + iy];
                let h01 = height_map[p01 + iy];
                let h10 = height_map[p10 + iy];
                let h11 = height_map[p11 + iy];

                let dx0 = (h10 - h00) * 0.25;
                let dx1 = (h11 - h01) * 0.25;

                let mut y0 = h00;
                let mut y1 = h01;

                for sub_x in 0..4usize {
                    let dz = (y1 - y0) * 0.25;
                    let mut d = y0 - dz;

                    for sub_z in 0..4usize {
                        d += dz;
                        col_nodes[(sub_x * 4 + sub_z) * 33 + iy] = d;
                    }

                    y0 += dx0;
                    y1 += dx1;
                }
            }

            // Phase 2: Stream contiguous Y-columns into ChunkPrimer.
            // For each column (x, z), memory index is `(x << 12) | (z << 8) | y`.
            // With y running 0..255, this is a strictly contiguous slice of 256 u16 elements!
            for sub_x in 0..4usize {
                let x = ix * 4 + sub_x;

                for sub_z in 0..4usize {
                    let z = iz * 4 + sub_z;
                    let col_idx = sub_x * 4 + sub_z;
                    let col_offset = col_idx * 33;
                    let base_mem_idx = chunk_primer_index(x, 0, z);

                    let col_slice = &mut primer[base_mem_idx..base_mem_idx + 256];

                    for iy in 0..32usize {
                        let d_bottom = col_nodes[col_offset + iy];
                        let d_top = col_nodes[col_offset + iy + 1];
                        let step_y = (d_top - d_bottom) * 0.125;
                        let mut density = d_bottom;

                        let y_base = iy * 8;
                        for sub_y in 0..8usize {
                            let y = y_base + sub_y;
                            if density > 0.0 {
                                col_slice[y] = stone_id;
                            } else if (y as i32) < sea_level {
                                col_slice[y] = water_id;
                            }
                            density += step_y;
                        }
                    }
                }
            }
        }
    }
}

/// Fused production kernel: complete density field evaluation + column-major
/// ChunkPrimer placement in a single call.
///
/// Avoids separate JNI transition, scratch allocation, and intermediate array copying.
pub fn generate_terrain_fused(
    init_field: &mut crate::field_complete::InitNoiseField,
    x4: i32,
    z4: i32,
    biomes: &[f32],
    sea_level: i32,
    stone_id: u16,
    water_id: u16,
    primer: &mut [u16; CHUNK_PRIMER_SIZE],
) {
    let mut density = [0.0f64; 825];
    init_field.complete(x4, z4, biomes, &mut density);
    set_blocks_in_chunk_column_major(&density, sea_level, stone_id, water_id, primer);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::JavaRandom;
    use std::collections::HashSet;

    #[test]
    fn test_chunk_primer_index_bijection() {
        assert_eq!(chunk_primer_index(0, 0, 0), 0);
        assert_eq!(chunk_primer_index(15, 255, 15), 65535);

        let mut seen = HashSet::with_capacity(CHUNK_PRIMER_SIZE);
        for x in 0..16usize {
            for z in 0..16usize {
                for y in 0..256usize {
                    let idx = chunk_primer_index(x, y, z);
                    assert!(idx < CHUNK_PRIMER_SIZE);
                    assert!(seen.insert(idx), "Duplicate index {} at ({}, {}, {})", idx, x, y, z);
                }
            }
        }
        assert_eq!(seen.len(), CHUNK_PRIMER_SIZE);
    }

    #[test]
    fn test_pure_air_terrain() {
        let height_map = [-10.0f64; 825];
        let mut primer = [STATE_AIR; CHUNK_PRIMER_SIZE];
        set_blocks_in_chunk_parity(&height_map, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer);

        // Below sea level should be water, above sea level should be air
        for x in 0..16usize {
            for z in 0..16usize {
                for y in 0..256usize {
                    let state = primer[chunk_primer_index(x, y, z)];
                    if (y as i32) < DEFAULT_SEA_LEVEL {
                        assert_eq!(state, STATE_WATER, "Expected water at y={}", y);
                    } else {
                        assert_eq!(state, STATE_AIR, "Expected air at y={}", y);
                    }
                }
            }
        }
    }

    #[test]
    fn test_pure_stone_terrain() {
        let height_map = [10.0f64; 825];
        let mut primer = [STATE_AIR; CHUNK_PRIMER_SIZE];
        set_blocks_in_chunk_parity(&height_map, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer);

        for x in 0..16usize {
            for z in 0..16usize {
                for y in 0..256usize {
                    let state = primer[chunk_primer_index(x, y, z)];
                    assert_eq!(state, STATE_STONE, "Expected stone at y={}", y);
                }
            }
        }
    }

    #[test]
    fn test_parity_matches_column_major_on_real_density() {
        use crate::field_complete::InitNoiseField;
        use crate::field::FieldSettings;

        let settings = FieldSettings {
            coordinate_scale: 684.412,
            height_scale: 684.412,
            depth_noise_scale_x: 200.0,
            depth_noise_scale_exp: 0.5,
            depth_noise_scale_z: 200.0,
            main_noise_scale_x: 80.0,
            main_noise_scale_y: 160.0,
            main_noise_scale_z: 80.0,
            upper_limit_scale: 8.5,
            lower_limit_scale: 12.0,
            depth_noise_scale: 512.0,
            main_depth_scale: 512.0,
            biome_depth_weight: 1.0,
            biome_depth_offset: 0.0,
            biome_scale_weight: 1.0,
            biome_scale_offset: 0.0,
            amplified: false,
        };

        let seed = 12345i64;
        let mut hnd = InitNoiseField::new(seed, seed + 1, seed + 2, seed + 3, settings);
        let biomes = vec![0.125f32; 200];
        let mut density_vec = vec![0.0f64; 825];
        hnd.complete(0, 0, &biomes, &mut density_vec);

        let mut density = [0.0f64; 825];
        density.copy_from_slice(&density_vec);

        let mut primer_parity = [STATE_AIR; CHUNK_PRIMER_SIZE];
        let mut primer_opt = [STATE_AIR; CHUNK_PRIMER_SIZE];

        set_blocks_in_chunk_parity(&density, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer_parity);
        set_blocks_in_chunk_column_major(&density, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer_opt);

        let mut mismatches = 0;
        for i in 0..CHUNK_PRIMER_SIZE {
            if primer_parity[i] != primer_opt[i] {
                mismatches += 1;
            }
        }

        // Check overall parity and count blocks
        let mut stone_count = 0;
        let mut water_count = 0;
        let mut air_count = 0;
        for &s in primer_parity.iter() {
            if s == STATE_STONE { stone_count += 1; }
            else if s == STATE_WATER { water_count += 1; }
            else if s == STATE_AIR { air_count += 1; }
        }

        println!(
            "Chunk terrain breakdown: stone={}, water={}, air={}, mismatches={}/{}",
            stone_count, water_count, air_count, mismatches, CHUNK_PRIMER_SIZE
        );
        assert_eq!(mismatches, 0, "Parity and column_major must be bit-exact");
        assert!(stone_count > 0, "Stone count must be positive: {}", stone_count);
        assert!(air_count > 0, "Air count must be positive: {}", air_count);
    }

    #[test]
    fn test_parity_matches_column_major_multi_chunk() {
        let mut r = JavaRandom::new(99999);
        let mut density = [0.0f64; 825];
        let mut primer_parity = [STATE_AIR; CHUNK_PRIMER_SIZE];
        let mut primer_opt = [STATE_AIR; CHUNK_PRIMER_SIZE];

        let mut total_blocks = 0;
        let mut total_mismatches = 0;

        for chunk_i in 0..50 {
            for v in density.iter_mut() {
                *v = (r.next_double() - 0.5) * 50.0;
            }
            primer_parity.fill(STATE_AIR);
            primer_opt.fill(STATE_AIR);

            set_blocks_in_chunk_parity(&density, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer_parity);
            set_blocks_in_chunk_column_major(&density, DEFAULT_SEA_LEVEL, STATE_STONE, STATE_WATER, &mut primer_opt);

            for i in 0..CHUNK_PRIMER_SIZE {
                total_blocks += 1;
                if primer_parity[i] != primer_opt[i] {
                    total_mismatches += 1;
                }
            }
        }
        println!("Multi-chunk test: tested {} blocks across 50 chunks, mismatches: {}", total_blocks, total_mismatches);
        assert_eq!(total_mismatches, 0, "Expected zero mismatches across 50 chunks");
    }


}
