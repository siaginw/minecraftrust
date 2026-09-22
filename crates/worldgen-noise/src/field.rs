//! M3W3: complete initNoiseField assembly port (biome weighting + density
//! formula) — exact transcription of the decompiled vanilla code (committed
//! noise-src/ChunkGeneratorOverworld.java), including all f32→f64 quirks.
//! Plus the n=1 x-lane SIMD kernel (lanes = x-positions, so a SINGLE chunk
//! fills SIMD lanes without cross-chunk batching).

use crate::simd::{is_avx2, FlatTables};

/// Settings consumed by the assembly (values passed from the LIVE Java
/// ChunkGeneratorSettings instance at integration time — no transcription
/// risk; the harness pulls them from the real class).
#[derive(Clone, Copy, Debug)]
pub struct FieldSettings {
    pub coordinate_scale: f32,      // field_177811_a
    pub height_scale: f32,          // field_177809_b
    pub depth_noise_scale_x: f32,   // field_177808_e
    pub depth_noise_scale_exp: f32, // field_177803_f (DROPPED by the 2D wrapper quirk)
    pub depth_noise_scale_z: f32,   // field_177804_g
    pub main_noise_scale_x: f32,    // field_177825_h
    pub main_noise_scale_y: f32,    // field_177827_i
    pub main_noise_scale_z: f32,    // field_177821_j
    pub upper_limit_scale: f32,     // field_177823_k
    pub lower_limit_scale: f32,     // field_177817_l
    pub depth_noise_scale: f32,     // field_177806_d (density: min/depthNoiseScale)
    pub main_depth_scale: f32,      // field_177810_c (density: max/mainDepthScale)
    pub biome_depth_weight: f32,    // field_177819_m
    pub biome_depth_offset: f32,    // field_177813_n
    pub biome_scale_weight: f32,    // field_177815_o
    pub biome_scale_offset: f32,    // field_177843_p
    pub amplified: bool,            // WorldType AMPLIFIED
}

/// biomeWeights[25]: 10.0F / (float)Math.sqrt((double)(i*i + j*j) + 0.2F)
pub fn biome_weights() -> [f32; 25] {
    let mut w = [0.0f32; 25];
    for i in -2..=2i32 {
        for j in -2..=2i32 {
            let f = (i * i + j * j) as f32 + 0.2f32;
            w[((i + 2) + (j + 2) * 5) as usize] = 10.0f32 / ((f as f64).sqrt() as f32);
        }
    }
    w
}

/// Biome inputs: per 10x10 grid cell, (min_height, height_variation) floats.
pub struct BiomeInputs {
    pub min_height: Vec<f32>,     // func_185355_j per cell
    pub variation: Vec<f32>,     // func_185360_m per cell
}

/// clampedLerp = MathHelper.func_151238_b (bytecode-exact).
#[inline]
fn clamped_lerp(a: f64, b: f64, t: f64) -> f64 {
    if t < 0.0 {
        a
    } else if t > 1.0 {
        b
    } else {
        a + (b - a) * t
    }
}

/// The complete assembly: h=depth 25, e=main 825, f=min 825, g=max 825,
/// biomes 10x10 -> q 825 density output. Exact transcription of
/// func_185978_a's post-noise section (index order: (ix*5+iz)*33+iy).
pub fn assemble_field(
    s: &FieldSettings,
    weights: &[f32; 25],
    biomes: &BiomeInputs,
    h: &[f64],
    e: &[f64],
    f: &[f64],
    g: &[f64],
    q: &mut [f64],
) {
    let mut depth_idx = 0usize;  // walks h[25]
    let mut idx = 0usize;        // walks e/f/g/q[825]
    for ix in 0..5usize {
        for iz in 0..5usize {
            // ---- biome 5x5 weighting (all f32) ----
            let mut variation_sum = 0.0f32;
            let mut depth_sum = 0.0f32;
            let mut weight_sum = 0.0f32;
            let center = ix + 2 + (iz + 2) * 10;
            for di in -2i32..=2 {
                for dj in -2i32..=2 {
                    let cell = (ix as i32 + di + 2) as usize + ((iz as i32 + dj + 2) as usize) * 10;
                    let mut f7 = s.biome_depth_offset + biomes.min_height[cell] * s.biome_depth_weight;
                    let mut f8 = s.biome_scale_offset + biomes.variation[cell] * s.biome_scale_weight;
                    if s.amplified && f7 > 0.0f32 {
                        f7 = 1.0f32 + f7 * 2.0f32;
                        f8 = 1.0f32 + f8 * 4.0f32;
                    }
                    let mut f9 = weights[((di + 2) + (dj + 2) * 5) as usize] / (f7 + 2.0f32);
                    if biomes.min_height[cell] > biomes.min_height[center] {
                        f9 /= 2.0f32;
                    }
                    variation_sum += f8 * f9;
                    depth_sum += f7 * f9;
                    weight_sum += f9;
                }
            }
            variation_sum /= weight_sum;
            depth_sum /= weight_sum;
            variation_sum = variation_sum * 0.9f32 + 0.1f32;
            depth_sum = (depth_sum * 4.0f32 - 1.0f32) / 8.0f32;

            // ---- depth-noise conditioning ----
            let mut d0 = h[depth_idx] / 8000.0;
            if d0 < 0.0 {
                d0 = -d0 * 0.3;
            }
            d0 = d0 * 3.0 - 2.0;
            if d0 < 0.0 {
                d0 /= 2.0;
                if d0 < -1.0 {
                    d0 = -1.0;
                }
                d0 /= 1.4;
                d0 /= 2.0;
            } else {
                if d0 > 1.0 {
                    d0 = 1.0;
                }
                d0 /= 8.0;
            }
            depth_idx += 1;

            let mut d1 = depth_sum as f64;
            let d2 = variation_sum as f64;
            d1 += d0 * 0.2;
            d1 = d1 * (s.upper_limit_scale as f64) / 8.0;
            let d3 = (s.upper_limit_scale as f64) + d1 * 4.0;

            // ---- per-y density ----
            for y in 0..33usize {
                let mut d4 = (y as f64 - d3)
                    * (s.lower_limit_scale as f64)
                    * 128.0
                    / 256.0
                    / d2;
                if d4 < 0.0 {
                    d4 *= 4.0;
                }
                let d5 = f[idx] / (s.depth_noise_scale as f64);
                let d6 = g[idx] / (s.main_depth_scale as f64);
                let d7 = (e[idx] / 10.0 + 1.0) / 2.0;
                let mut density = clamped_lerp(d5, d6, d7) - d4;
                if y > 29 {
                    let d8 = ((y - 29) as f32 / 3.0f32) as f64;
                    density = density * (1.0 - d8) + -10.0 * d8;
                }
                q[idx] = density;
                idx += 1;
            }
        }
    }
}

/// n=1 x-lane SIMD kernel driver: generates ONE field (5x33x5) with lanes =
/// x positions. For each octave, lanes cover ix=0..4 (one 4-lane group +
/// 1 padded lane; the padded lane duplicates ix=0 and is not stored).
pub struct SingleFieldGen {
    pub batch: crate::simd::BatchOctaves,
}

impl SingleFieldGen {
    pub fn new(seed: i64, octaves: i32) -> Self {
        SingleFieldGen { batch: crate::simd::BatchOctaves::new(seed, octaves) }
    }

    /// out len must be 825. y_size must be 33 (assembly shape).
    #[allow(clippy::too_many_arguments)]
    pub fn generate3d_single(&self, out: &mut [f64], x_off: i32, y_off: i32, z_off: i32,
                             x_size: i32, y_size: i32, z_size: i32,
                             x_scale: f64, y_scale: f64, z_scale: f64) {
        let len = (x_size * y_size * z_size) as usize;
        #[cfg(target_arch = "x86_64")]
        {
            if crate::simd::is_avx2() {
                self.generate3d_single_simd(out, x_off, y_off, z_off, x_size, y_size, z_size,
                                            x_scale, y_scale, z_scale);
                return;
            }
        }
        out.copy_from_slice(&self.batch.octaves.generate3d(
            None, x_off, y_off, z_off, x_size, y_size, z_size, x_scale, y_scale, z_scale));
    }

    #[cfg(target_arch = "x86_64")]
    #[allow(clippy::too_many_arguments)]
    fn generate3d_single_simd(&self, out: &mut [f64], x_off: i32, y_off: i32, z_off: i32,
                              x_size: i32, y_size: i32, z_size: i32,
                              x_scale: f64, y_scale: f64, z_scale: f64) {
        let len = (x_size * y_size * z_size) as usize;
        for v in out.iter_mut().take(len) {
            *v = 0.0;
        }
        let mut d3 = 1.0f64;
        for j in 0..self.batch.octaves.octaves as usize {
            let t: &FlatTables = &self.batch.tables[j];
            let d7 = y_off as f64 * d3 * y_scale;
            let mut dx = x_off as f64 * d3 * x_scale;
            let mut dz = z_off as f64 * d3 * z_scale;
            let k6 = crate::floor_long_pub(dx);
            let j7 = crate::floor_long_pub(dz);
            dx -= k6 as f64;
            dz -= j7 as f64;
            dx += (k6 % 16777216) as f64;
            dz += (j7 % 16777216) as f64;
            let xsc = x_scale * d3;
            // lanes = ix: d6[lane] = dx + lane*xsc (the batch kernel adds
            // ix*xsc itself, so we PRE-subtract by invoking with per-lane
            // offsets and ix-loop collapsed). We instead reuse the batched
            // kernel with lanes as "chunks" whose offsets step by xsc*d3...
            // that does not fit the integer-offset API, so a dedicated loop
            // below performs the x-lane population directly.
            #[cfg(target_arch = "x86_64")]
            {
                if is_avx2() {
                    unsafe {
                        crate::simd::populate_xlanes_avx4(
                            out, len, t, dx, xsc, d7, dz,
                            x_size, y_size, z_size, y_scale * d3, z_scale * d3, d3,
                        );
                    }
                    d3 /= 2.0;
                    continue;
                }
            }
            let _ = (dx, xsc);
            d3 /= 2.0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// x-lane single-chunk kernel vs scalar generate3d: bit-exact.
    #[test]
    fn xlane_single_matches_scalar_bit_exact() {
        if !crate::simd::is_avx2() {
            eprintln!("no AVX2; skipping");
            return;
        }
        for (seed, oct) in [(1i64, 16i32), (4096i64, 8i32), (555i64, 10i32)] {
            let g = SingleFieldGen::new(seed, oct);
            let mut out = vec![0.0f64; 825];
            let mut xo = seed;
            xo = xo.wrapping_mul(6364136223846793005).wrapping_add(1);
            let x = ((xo >> 33) as i32) % 400_000;
            let z = ((xo >> 13) as i32) % -400_000;
            g.generate3d_single(&mut out, x, 0, z, 5, 33, 5, 684.412, 684.412, 684.412);
            let scalar = g.batch.octaves.generate3d(None, x, 0, z, 5, 33, 5, 684.412, 684.412, 684.412);
            for i in 0..825 {
                assert_eq!(out[i].to_bits(), scalar[i].to_bits(),
                    "seed={} oct={} i={} xlane={:x} scalar={:x}", seed, oct, i,
                    out[i].to_bits(), scalar[i].to_bits());
            }
        }
    }

    /// assembly: self-consistency + q length + finite outputs.
    #[test]
    fn assembly_runs_and_biome_weights_match_reference() {
        let w = biome_weights();
        // vanilla reference: center = 10/sqrt(0.2), corners = 10/sqrt(8.2)
        assert_eq!(w[12].to_bits(), (10.0f32 / ((0.2f64).sqrt() as f32)).to_bits());
        assert_eq!(w[0].to_bits(), (10.0f32 / ((8.2f64).sqrt() as f32)).to_bits());
        let s = FieldSettings {
            coordinate_scale: 684.412, height_scale: 684.412,
            depth_noise_scale_x: 200.0, depth_noise_scale_exp: 0.5, depth_noise_scale_z: 200.0,
            main_noise_scale_x: 80.0, main_noise_scale_y: 160.0, main_noise_scale_z: 80.0,
            upper_limit_scale: 512.0, lower_limit_scale: 512.0,
            depth_noise_scale: 200.0 / 400.0, main_depth_scale: 400.0 / 400.0,
            biome_depth_weight: 1.0, biome_depth_offset: 0.0,
            biome_scale_weight: 1.0, biome_scale_offset: 0.0,
            amplified: false,
        };
        let biomes = BiomeInputs {
            min_height: vec![0.125f32; 100],  // plains-like
            variation: vec![0.05f32; 100],
        };
        let h = vec![0.5f64; 25];
        let e = vec![0.1f64; 825];
        let f = vec![0.2f64; 825];
        let g = vec![0.15f64; 825];
        let mut q = vec![0.0f64; 825];
        assemble_field(&s, &w, &biomes, &h, &e, &f, &g, &mut q);
        assert!(q.iter().all(|v| v.is_finite()));
    }
}


/// Borrowed-biome variant of the assembly (zero allocation).
pub struct BiomeInputsBorrowed<'a> {
    pub min_height: &'a [f32],
    pub variation: &'a [f32],
}

/// Same math as assemble_field, borrowed inputs, direct out write.
pub fn assemble_field_into(
    s: &FieldSettings,
    weights: &[f32; 25],
    biomes: &BiomeInputsBorrowed,
    h: &[f64], e: &[f64], f: &[f64], g: &[f64],
    q: &mut [f64],
) {
    let mut depth_idx = 0usize;
    let mut idx = 0usize;
    for ix in 0..5usize {
        for iz in 0..5usize {
            let mut variation_sum = 0.0f32;
            let mut depth_sum = 0.0f32;
            let mut weight_sum = 0.0f32;
            let center = ix + 2 + (iz + 2) * 10;
            for di in -2i32..=2 {
                for dj in -2i32..=2 {
                    let cell = (ix as i32 + di + 2) as usize + ((iz as i32 + dj + 2) as usize) * 10;
                    let mut f7 = s.biome_depth_offset + biomes.min_height[cell] * s.biome_depth_weight;
                    let mut f8 = s.biome_scale_offset + biomes.variation[cell] * s.biome_scale_weight;
                    if s.amplified && f7 > 0.0f32 {
                        f7 = 1.0f32 + f7 * 2.0f32;
                        f8 = 1.0f32 + f8 * 4.0f32;
                    }
                    let mut f9 = weights[((di + 2) + (dj + 2) * 5) as usize] / (f7 + 2.0f32);
                    if biomes.min_height[cell] > biomes.min_height[center] {
                        f9 /= 2.0f32;
                    }
                    variation_sum += f8 * f9;
                    depth_sum += f7 * f9;
                    weight_sum += f9;
                }
            }
            variation_sum /= weight_sum;
            depth_sum /= weight_sum;
            variation_sum = variation_sum * 0.9f32 + 0.1f32;
            depth_sum = (depth_sum * 4.0f32 - 1.0f32) / 8.0f32;
            let mut d0 = h[depth_idx] / 8000.0;
            if d0 < 0.0 { d0 = -d0 * 0.3; }
            d0 = d0 * 3.0 - 2.0;
            if d0 < 0.0 {
                d0 /= 2.0;
                if d0 < -1.0 { d0 = -1.0; }
                d0 /= 1.4;
                d0 /= 2.0;
            } else {
                if d0 > 1.0 { d0 = 1.0; }
                d0 /= 8.0;
            }
            depth_idx += 1;
            let mut d1 = depth_sum as f64;
            let d2 = variation_sum as f64;
            d1 += d0 * 0.2;
            d1 = d1 * (s.upper_limit_scale as f64) / 8.0;
            let d3 = (s.upper_limit_scale as f64) + d1 * 4.0;
            for y in 0..33usize {
                let mut d4 = (y as f64 - d3)
                    * (s.lower_limit_scale as f64)
                    * 128.0 / 256.0 / d2;
                if d4 < 0.0 { d4 *= 4.0; }
                let d5 = f[idx] / (s.depth_noise_scale as f64);
                let d6 = g[idx] / (s.main_depth_scale as f64);
                let d7 = (e[idx] / 10.0 + 1.0) / 2.0;
                let mut density = clamped_lerp(d5, d6, d7) - d4;
                if y > 29 {
                    let d8 = ((y - 29) as f32 / 3.0f32) as f64;
                    density = density * (1.0 - d8) + -10.0 * d8;
                }
                q[idx] = density;
                idx += 1;
            }
        }
    }
}
