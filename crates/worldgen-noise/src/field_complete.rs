//! M3W4: redesigned single-chunk (n=1) production kernel.
//!
//! Design (per the operator directive + verified review findings):
//! - ONE coarse JNI operation per chunk: initFieldComplete(handle, x4, z4,
//!   biome floats, out 825) — Rust owns depth+main+min+max noise and the
//!   density assembly.
//! - Compact exact lookup state: perm as [u8; 512] (every vanilla perm value
//!   is 0..255; proven by construction and asserted) + the three tiny 16-wide
//!   gradient tables — ~600B per octave level vs FlatTables' 12KB+.
//! - Specialized scalar kernel for the fixed 5x33x5 shape: compile-time
//!   strides, no generic bounds checks, L1-resident scalar lookups (no
//!   gathers), register-resident intermediates, the vanilla y-skip cache
//!   amortization, contiguous accumulation into handle-owned scratch.
//! - Min/max lockstep fusion: the two 16-octave fields share identical
//!   lattice math (same offsets/scales/shape — only perm tables and gradient
//!   lookups differ); each field keeps its own FP accumulators and its own
//!   octave order, so per-field accumulation order (and thus bits) are
//!   unchanged. Main (8 octaves, different scales) runs its own pass.
//! - Zero hot-path allocation: all scratch owned by the handle; biomes
//!   borrowed as slices; the density result is written directly to the
//!   caller's buffer.

use crate::field::{assemble_field_into, FieldSettings};
use crate::{GRAD_X, GRAD_Y, GRAD_Z, JavaRandom, Octaves};

const XS: usize = 5;
const YS: usize = 33;
const ZS: usize = 5;
const LEN: usize = XS * YS * ZS; // 825

/// One octave level: compact perm + generator offsets (identical math to
/// NoiseGeneratorImproved, compact layout).
#[derive(Clone)]
pub struct CompactLevel {
    perm: Vec<u8>, // vanilla field_76312_d values (0..255, asserted)
    x_off: f64,
    y_off: f64,
    z_off: f64,
}

impl CompactLevel {
    fn from_improved(g: &crate::Improved) -> Self {
        assert!(g.perm.iter().all(|&v| (0..=255).contains(&v)),
            "perm value out of u8 range — compact layout invalid");
        CompactLevel {
            perm: g.perm.iter().map(|&v| v as u8).collect(),
            x_off: g.x_off,
            y_off: g.y_off,
            z_off: g.z_off,
        }
    }
}

/// Handle: owns the four generators (compact), settings, and scratch.
pub struct InitNoiseField {
    depth: Vec<CompactLevel>, // 16 octaves
    main: Vec<CompactLevel>,  // 8 octaves
    min: Vec<CompactLevel>,   // 16
    max: Vec<CompactLevel>,   // 16
    pub settings: FieldSettings,
    // handle-owned scratch (zero alloc in the hot path)
    h: Vec<f64>,           // 25
    e: Vec<f64>,           // 825 (main)
    f: Vec<f64>,           // 825 (min)
    g: Vec<f64>,           // 825 (max)
    weights: [f32; 25],
}

/// Java d2i-then-adjust floor, v2 via hardware floor (differential-tested).
#[inline(always)]
fn floor_i32(d: f64) -> i32 {
    d.floor() as i32
}

#[inline(always)]
fn fade(d: f64) -> f64 {
    let d3 = d * d * d;
    d3 * (d * (d * 6.0 - 15.0) + 10.0)
}

#[inline(always)]
fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}

#[inline(always)]
fn grad3(hash: u8, x: f64, y: f64, z: f64) -> f64 {
    let i = (hash & 15) as usize;
    GRAD_X[i] * x + GRAD_Y[i] * y + GRAD_Z[i] * z
}

/// Octave-wrapper coordinate normalization (func_76304_a), specialized.
#[inline(always)]
fn octave_offset(off: i32, d3: f64, scale: f64) -> f64 {
    let mut d = off as f64 * d3 * scale;
    let k = crate::floor_long_pub(d);
    d -= k as f64;
    d += (k % 16777216) as f64;
    d
}

/// One octave layer of ONE field into acc[LEN], specialized scalar kernel.
/// Exact transcription of NoiseGeneratorImproved's 3D path with fixed shape.
#[inline]
fn layer_field(acc: &mut [f64], lv: &CompactLevel,
               d6: f64, d7: f64, d8: f64,
               x_scale: f64, y_scale: f64, z_scale: f64, d3: f64) {
    let inv = 1.0 / d3;
    let perm = &lv.perm;
    let mut idx = 0usize;
    let mut last_y: i32 = -1;
    let (mut c0, mut c1, mut c2, mut c3) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    let (mut l0, mut l1, mut l2, mut l3) = (0i32, 0i32, 0i32, 0i32);
    for ix in 0..XS {
        let dx0 = d6 + ix as f64 * x_scale + lv.x_off;
        let xi = floor_i32(dx0);
        let cx = xi & 0xFF;
        let dx = dx0 - xi as f64;
        let u = fade(dx);
        for iz in 0..ZS {
            let dz0 = d8 + iz as f64 * z_scale + lv.z_off;
            let zi = floor_i32(dz0);
            let cz = zi & 0xFF;
            let dz = dz0 - zi as f64;
            let w = fade(dz);
            for iy in 0..YS {
                let dy0 = d7 + iy as f64 * y_scale + lv.y_off;
                let yi = floor_i32(dy0);
                let cy = yi & 0xFF;
                let dy = dy0 - yi as f64;
                let v = fade(dy);
                if !(iy != 0 && cy == last_y) {
                    last_y = cy;
                    let b0 = perm[cx as usize] as i32 + cy;
                    l0 = perm[b0 as usize] as i32 + cz;
                    l1 = perm[(b0 + 1) as usize] as i32 + cz;
                    let b1 = perm[(cx + 1) as usize] as i32 + cy;
                    l2 = perm[b1 as usize] as i32 + cz;
                    l3 = perm[(b1 + 1) as usize] as i32 + cz;
                    c0 = lerp(u, grad3(perm[l0 as usize], dx, dy, dz),
                                 grad3(perm[l2 as usize], dx - 1.0, dy, dz));
                    c1 = lerp(u, grad3(perm[l1 as usize], dx, dy - 1.0, dz),
                                 grad3(perm[l3 as usize], dx - 1.0, dy - 1.0, dz));
                    c2 = lerp(u, grad3(perm[(l0 + 1) as usize], dx, dy, dz - 1.0),
                                 grad3(perm[(l2 + 1) as usize], dx - 1.0, dy, dz - 1.0));
                    c3 = lerp(u, grad3(perm[(l1 + 1) as usize], dx, dy - 1.0, dz - 1.0),
                                 grad3(perm[(l3 + 1) as usize], dx - 1.0, dy - 1.0, dz - 1.0));
                }
                acc[idx] += lerp(w, lerp(v, c0, c1), lerp(v, c2, c3)) * inv;
                idx += 1;
            }
        }
    }
}

/// Min/max LOCKSTEP layer: shares ONLY the traversal structure, the octave
/// d3/inv, and the (identical, integer-derived) wrapper offsets — NEVER the
/// generators' own Random-derived x/y/z offsets or perm lookups (they differ
/// per field; sharing them was the review's exact warning and broke parity).
/// Per-field coordinate math (dx0/dy0/dz0, floors, fades) is computed
/// separately for each field; per-field FP accumulation order unchanged.
#[inline]
fn layer_min_max(acc_f: &mut [f64], acc_g: &mut [f64],
                 lv_f: &CompactLevel, lv_g: &CompactLevel,
                 d6: f64, d7: f64, d8: f64,
                 x_scale: f64, y_scale: f64, z_scale: f64, d3: f64) {
    let inv = 1.0 / d3;
    let pf = &lv_f.perm;
    let pg = &lv_g.perm;
    let mut idx = 0usize;
    let mut last_yf: i32 = -1;
    let mut last_yg: i32 = -1;
    let (mut c0f, mut c1f, mut c2f, mut c3f) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    let (mut c0g, mut c1g, mut c2g, mut c3g) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for ix in 0..XS {
        let dx0f = d6 + ix as f64 * x_scale + lv_f.x_off;
        let xif = floor_i32(dx0f);
        let cxf = xif & 0xFF;
        let dxf = dx0f - xif as f64;
        let uf = fade(dxf);
        let dx0g = d6 + ix as f64 * x_scale + lv_g.x_off;
        let xig = floor_i32(dx0g);
        let cxg = xig & 0xFF;
        let dxg = dx0g - xig as f64;
        let ug = fade(dxg);
        for iz in 0..ZS {
            let dz0f = d8 + iz as f64 * z_scale + lv_f.z_off;
            let zif = floor_i32(dz0f);
            let czf = zif & 0xFF;
            let dzf = dz0f - zif as f64;
            let wf = fade(dzf);
            let dz0g = d8 + iz as f64 * z_scale + lv_g.z_off;
            let zig = floor_i32(dz0g);
            let czg = zig & 0xFF;
            let dzg = dz0g - zig as f64;
            let wg = fade(dzg);
            for iy in 0..YS {
                let dy0f = d7 + iy as f64 * y_scale + lv_f.y_off;
                let yif = floor_i32(dy0f);
                let cyf = yif & 0xFF;
                let dyf = dy0f - yif as f64;
                let vf = fade(dyf);
                if !(iy != 0 && cyf == last_yf) {
                    last_yf = cyf;
                    let b0 = pf[cxf as usize] as i32 + cyf;
                    let l0 = pf[b0 as usize] as i32 + czf;
                    let l1 = pf[(b0 + 1) as usize] as i32 + czf;
                    let b1 = pf[(cxf + 1) as usize] as i32 + cyf;
                    let l2 = pf[b1 as usize] as i32 + czf;
                    let l3 = pf[(b1 + 1) as usize] as i32 + czf;
                    c0f = lerp(uf, grad3(pf[l0 as usize], dxf, dyf, dzf),
                                  grad3(pf[l2 as usize], dxf - 1.0, dyf, dzf));
                    c1f = lerp(uf, grad3(pf[l1 as usize], dxf, dyf - 1.0, dzf),
                                  grad3(pf[l3 as usize], dxf - 1.0, dyf - 1.0, dzf));
                    c2f = lerp(uf, grad3(pf[(l0 + 1) as usize], dxf, dyf, dzf - 1.0),
                                  grad3(pf[(l2 + 1) as usize], dxf - 1.0, dyf, dzf - 1.0));
                    c3f = lerp(uf, grad3(pf[(l1 + 1) as usize], dxf, dyf - 1.0, dzf - 1.0),
                                  grad3(pf[(l3 + 1) as usize], dxf - 1.0, dyf - 1.0, dzf - 1.0));
                }
                let dy0g = d7 + iy as f64 * y_scale + lv_g.y_off;
                let yig = floor_i32(dy0g);
                let cyg = yig & 0xFF;
                let dyg = dy0g - yig as f64;
                let vg = fade(dyg);
                if !(iy != 0 && cyg == last_yg) {
                    last_yg = cyg;
                    let b0 = pg[cxg as usize] as i32 + cyg;
                    let l0 = pg[b0 as usize] as i32 + czg;
                    let l1 = pg[(b0 + 1) as usize] as i32 + czg;
                    let b1 = pg[(cxg + 1) as usize] as i32 + cyg;
                    let l2 = pg[b1 as usize] as i32 + czg;
                    let l3 = pg[(b1 + 1) as usize] as i32 + czg;
                    c0g = lerp(ug, grad3(pg[l0 as usize], dxg, dyg, dzg),
                                  grad3(pg[l2 as usize], dxg - 1.0, dyg, dzg));
                    c1g = lerp(ug, grad3(pg[l1 as usize], dxg, dyg - 1.0, dzg),
                                  grad3(pg[l3 as usize], dxg - 1.0, dyg - 1.0, dzg));
                    c2g = lerp(ug, grad3(pg[(l0 + 1) as usize], dxg, dyg, dzg - 1.0),
                                  grad3(pg[(l2 + 1) as usize], dxg - 1.0, dyg, dzg - 1.0));
                    c3g = lerp(ug, grad3(pg[(l1 + 1) as usize], dxg, dyg - 1.0, dzg - 1.0),
                                  grad3(pg[(l3 + 1) as usize], dxg - 1.0, dyg - 1.0, dzg - 1.0));
                }
                acc_f[idx] += lerp(wf, lerp(vf, c0f, c1f), lerp(vf, c2f, c3f)) * inv;
                acc_g[idx] += lerp(wg, lerp(vg, c0g, c1g), lerp(vg, c2g, c3g)) * inv;
                idx += 1;
            }
        }
    }
}

impl InitNoiseField {
    /// Construct from the four generator seeds (same construction order and
    /// Random consumption as ChunkGeneratorOverworld: depth=16, main=8,
    /// min=16, max=16 — each from its OWN Random(seed)).
    pub fn new(seed_depth: i64, seed_main: i64, seed_min: i64, seed_max: i64,
               settings: FieldSettings) -> Self {
        let mk = |seed: i64, oct: i32| -> Vec<CompactLevel> {
            let mut r = JavaRandom::new(seed);
            let o = Octaves::new(&mut r, oct);
            o.generators.iter().map(CompactLevel::from_improved).collect()
        };
        InitNoiseField {
            depth: mk(seed_depth, 16),
            main: mk(seed_main, 8),
            min: mk(seed_min, 16),
            max: mk(seed_max, 16),
            settings,
            h: vec![0.0; 25],
            e: vec![0.0; LEN],
            f: vec![0.0; LEN],
            g: vec![0.0; LEN],
            weights: crate::field::biome_weights(),
        }
    }

    /// The COMPLETE single-chunk operation: one call, zero allocation.
    /// biome floats = 100 min-height then 100 variation; out = 825 doubles.
    pub fn complete(&mut self, x4: i32, z4: i32, biomes: &[f32], out: &mut [f64]) {
        let s = self.settings;
        // ---- depth 2D field (vanilla wrapper quirk: zScale = exponent param) ----
        for v in self.h.iter_mut() {
            *v = 0.0;
        }
        {
            let mut d3 = 1.0f64;
            let arr = &mut self.h;
            for j in 0..self.depth.len() {
                let lv = &self.depth[j];
                let d6 = octave_offset(x4, d3, s.depth_noise_scale_x as f64);
                let d7 = 10.0f64 * d3 * 1.0; // wrapper: yOffset=10, yScale=1.0
                let d8 = octave_offset(z4, d3, s.depth_noise_scale_exp as f64); // QUIRK: exponent -> zScale
                layer_2d(arr, lv, d6, d7, d8, s.depth_noise_scale_x as f64 * d3,
                         s.depth_noise_scale_exp as f64 * d3, d3);
                d3 /= 2.0;
            }
        }
        // ---- main (8 octaves, f32-division scales) ----
        for v in self.e.iter_mut() {
            *v = 0.0;
        }
        {
            let mnx = (s.coordinate_scale / s.main_noise_scale_x) as f64;
            let mny = (s.height_scale / s.main_noise_scale_y) as f64;
            let mnz = (s.coordinate_scale / s.main_noise_scale_z) as f64;
            let mut d3 = 1.0f64;
            for j in 0..self.main.len() {
                let lv = &self.main[j];
                let d6 = octave_offset(x4, d3, mnx);
                let d7 = octave_offset(0, d3, mny); // y offset 0*... = 0 via same math
                let d8 = octave_offset(z4, d3, mnz);
                layer_field(&mut self.e, lv, d6, d7, d8, mnx * d3, mny * d3, mnz * d3, d3);
                d3 /= 2.0;
            }
        }
        // ---- min/max lockstep (16 octaves, raw a/b/a scales) ----
        for v in self.f.iter_mut() {
            *v = 0.0;
        }
        for v in self.g.iter_mut() {
            *v = 0.0;
        }
        {
            let a = s.coordinate_scale as f64;
            let b = s.height_scale as f64;
            let mut d3 = 1.0f64;
            for j in 0..self.min.len() {
                let d6 = octave_offset(x4, d3, a);
                let d8 = octave_offset(z4, d3, a);
                // NOTE: y offset for min/max = 0*... normalized = 0.0 exactly
                layer_min_max(&mut self.f, &mut self.g, &self.min[j], &self.max[j],
                              d6, 0.0, d8, a * d3, b * d3, a * d3, d3);
                d3 /= 2.0;
            }
        }
        // ---- assembly, borrowed biomes, direct out ----
        let bi = crate::field::BiomeInputsBorrowed {
            min_height: &biomes[..100],
            variation: &biomes[100..],
        };
        assemble_field_into(&self.settings, &self.weights, &bi,
                            &self.h, &self.e, &self.f, &self.g, out);
    }
}

/// 2D layer (y_size==1 special path), specialized 5x5.
fn layer_2d(arr: &mut [f64], lv: &CompactLevel, d6: f64, _d7: f64, d8: f64,
            x_scale: f64, z_scale: f64, d3: f64) {
    let inv = 1.0 / d3;
    let perm = &lv.perm;
    let mut idx = 0usize;
    for ix in 0..XS {
        let dx0 = d6 + ix as f64 * x_scale + lv.x_off;
        let xi = floor_i32(dx0);
        let cx = xi & 0xFF;
        let dx = dx0 - xi as f64;
        let u = fade(dx);
        for iz in 0..ZS {
            let dz0 = d8 + iz as f64 * z_scale + lv.z_off;
            let zi = floor_i32(dz0);
            let cz = zi & 0xFF;
            let dz = dz0 - zi as f64;
            let w = fade(dz);
            let b0 = perm[cx as usize] as i32;
            let l0 = perm[b0 as usize] as i32 + cz;
            let b1 = perm[(cx + 1) as usize] as i32;
            let l1 = perm[b1 as usize] as i32 + cz;
            let g2 = |hash: u8, x: f64, z: f64| -> f64 {
                let i = (hash & 15) as usize;
                GRAD_X[i] * x + GRAD_Z[i] * z
            };
            let l = lerp(u, g2(perm[l0 as usize], dx, dz),
                            grad3(perm[l1 as usize], dx - 1.0, 0.0, dz));
            let m = lerp(u, grad3(perm[(l0 + 1) as usize], dx, 0.0, dz - 1.0),
                            grad3(perm[(l1 + 1) as usize], dx - 1.0, 0.0, dz - 1.0));
            arr[idx] += lerp(w, l, m) * inv;
            idx += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn default_settings() -> FieldSettings {
        FieldSettings {
            coordinate_scale: 684.412, height_scale: 684.412,
            depth_noise_scale_x: 200.0, depth_noise_scale_exp: 0.5, depth_noise_scale_z: 200.0,
            main_noise_scale_x: 80.0, main_noise_scale_y: 160.0, main_noise_scale_z: 80.0,
            upper_limit_scale: 8.5, lower_limit_scale: 12.0,
            depth_noise_scale: 512.0, main_depth_scale: 512.0,
            biome_depth_weight: 1.0, biome_depth_offset: 0.0,
            biome_scale_weight: 1.0, biome_scale_offset: 0.0,
            amplified: false,
        }
    }

    /// Differential: complete() vs the scalar Octaves pipeline + assembly.
    #[test]
    fn complete_matches_scalar_pipeline_bit_exact() {
        let s = default_settings();
        for seed in [1i64, 4096, 777] {
            let mut hnd = InitNoiseField::new(seed, seed + 1, seed + 2, seed + 3, s);
            let mut out = vec![0.0f64; LEN];
            // reference: scalar Octaves (proven exact) + assembly
            let mut r_depth = JavaRandom::new(seed);
            let depth = Octaves::new(&mut r_depth, 16);
            let mut r_main = JavaRandom::new(seed + 1);
            let main = Octaves::new(&mut r_main, 8);
            let mut r_min = JavaRandom::new(seed + 2);
            let min = Octaves::new(&mut r_min, 16);
            let mut r_max = JavaRandom::new(seed + 3);
            let max = Octaves::new(&mut r_max, 16);
            let biomes = vec![0.125f32; 200];
            let mut xx = seed;
            for trial in 0..3 {
                xx = xx.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                let x4 = ((xx >> 33) as i32) % 300_000;
                let z4 = ((xx >> 13) as i32) % -300_000;
                hnd.complete(x4, z4, &biomes, &mut out);
                // reference pipeline
                let mnx = (s.coordinate_scale / s.main_noise_scale_x) as f64;
                let mny = (s.height_scale / s.main_noise_scale_y) as f64;
                let mnz = (s.coordinate_scale / s.main_noise_scale_z) as f64;
                let a = s.coordinate_scale as f64;
                let b = s.height_scale as f64;
                // depth 2D with the drop quirk: zScale = exponent (f_)
                let h = depth.generate2d(None, x4, z4, 5, 5,
                        s.depth_noise_scale_x as f64,
                        s.depth_noise_scale_exp as f64,
                        s.depth_noise_scale_z as f64);
                let e = main.generate3d(None, x4, 0, z4, 5, 33, 5, mnx, mny, mnz);
                let f = min.generate3d(None, x4, 0, z4, 5, 33, 5, a, b, a);
                let g = max.generate3d(None, x4, 0, z4, 5, 33, 5, a, b, a);
                let bi = crate::field::BiomeInputs {
                    min_height: biomes[..100].to_vec(),
                    variation: biomes[100..].to_vec(),
                };
                let mut q = vec![0.0f64; LEN];
                crate::field::assemble_field(&s, &crate::field::biome_weights(), &bi,
                                             &h, &e, &f, &g, &mut q);
                // component isolation first
                let heq = h.iter().zip(hnd.h.iter()).take(25)
                    .filter(|(a, b)| a.to_bits() != b.to_bits()).count();
                let eeq = e.iter().zip(hnd.e.iter()).take(LEN)
                    .filter(|(a, b)| a.to_bits() != b.to_bits()).count();
                let feq = f.iter().zip(hnd.f.iter()).take(LEN)
                    .filter(|(a, b)| a.to_bits() != b.to_bits()).count();
                let geq = g.iter().zip(hnd.g.iter()).take(LEN)
                    .filter(|(a, b)| a.to_bits() != b.to_bits()).count();
                println!("seed={} trial={} hdiffs={} ediffs={} fdiffs={} gdiffs={}", seed, trial, heq, eeq, feq, geq);
                for i in 0..LEN {
                    assert_eq!(out[i].to_bits(), q[i].to_bits(),
                        "seed={} trial={} i={} new={:x} ref={:x}", seed, trial, i,
                        out[i].to_bits(), q[i].to_bits());
                }
                let _ = trial;
            }
        }
    }
}

impl InitNoiseField {
    /// LIVE-SHADOW state transplantation: build from the ACTUAL Java
    /// generator states (per-level perm[512] + x/y/z offsets, packed per
    /// field in order depth/main/min/max) — exact by construction, no seed
    /// replay. Fails (returns None) if any perm value is out of u8 range.
    pub fn from_state(oct_depth: usize, oct_main: usize, oct_min: usize, oct_max: usize,
                      perms: &[u8], offsets: &[f64],
                      settings: FieldSettings) -> Option<Self> {
        let mut off_i = 0usize;
        let mut perm_i = 0usize;
        let mut take = |n_oct: usize, perms: &[u8], offsets: &[f64],
                        perm_i: &mut usize, off_i: &mut usize| -> Option<Vec<CompactLevel>> {
            let mut levels = Vec::with_capacity(n_oct);
            for _ in 0..n_oct {
                if *perm_i + 512 > perms.len() || *off_i + 3 > offsets.len() {
                    return None;
                }
                let mut perm = Vec::with_capacity(512);
                for k in 0..512 {
                    perm.push(perms[*perm_i + k]);
                }
                *perm_i += 512;
                let x_off = offsets[*off_i];
                let y_off = offsets[*off_i + 1];
                let z_off = offsets[*off_i + 2];
                *off_i += 3;
                levels.push(CompactLevel { perm, x_off, y_off, z_off });
            }
            Some(levels)
        };
        let depth = take(oct_depth, perms, offsets, &mut perm_i, &mut off_i)?;
        let main = take(oct_main, perms, offsets, &mut perm_i, &mut off_i)?;
        let min = take(oct_min, perms, offsets, &mut perm_i, &mut off_i)?;
        let max = take(oct_max, perms, offsets, &mut perm_i, &mut off_i)?;
        Some(InitNoiseField {
            depth, main, min, max,
            settings,
            h: vec![0.0; 25],
            e: vec![0.0; LEN],
            f: vec![0.0; LEN],
            g: vec![0.0; LEN],
            weights: crate::field::biome_weights(),
        })
    }
}
