//! RUST_OPTIMIZED batched SIMD kernel for the vanilla terrain-noise port
//! (M3W2). Techniques per docs/research/m3w2-simd-research.md: lanes =
//! independent CHUNKS (T1), register-resident lerp tree (T2), gathers on
//! flattened perm→grad tables (T5/T6), runtime AVX2 dispatch (T7), batch
//! accumulation in an interleaved slab (T9/T10). REJECTED as
//! PARITY_BREAKING: arithmetic hashing (T3), f32/FMA (T4).
//!
//! Exactness: every vector op maps 1:1 to the scalar expression tree per
//! lane, in the same order, with no FMA contraction. The vanilla y-skip
//! caching quirk is replicated per lane by computing the recomputed value
//! on ALL lanes and blending in the cached value where the per-lane mask
//! says the scalar path would have kept it.

#![allow(clippy::too_many_arguments)]

use crate::{Improved, JavaRandom, Octaves};

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

/// Flattened perm→grad tables: PGX[i] = GRAD_X[perm[i] & 15] (EXACT_SAFE T6).
pub struct FlatTables {
    pgx: Vec<f64>,
    pgy: Vec<f64>,
    pgz: Vec<f64>,
    perm: Vec<i32>,
    gx: f64,
    gy: f64,
    gz: f64, // generator xCoord/yCoord/zCoord
}

impl FlatTables {
    pub fn from_improved(g: &Improved) -> Self {
        let mut pgx = vec![0.0f64; 512];
        let mut pgy = vec![0.0f64; 512];
        let mut pgz = vec![0.0f64; 512];
        for i in 0..512 {
            let h = (g.perm[i] & 15) as usize;
            pgx[i] = crate::GRAD_X[h];
            pgy[i] = crate::GRAD_Y[h];
            pgz[i] = crate::GRAD_Z[h];
        }
        FlatTables { pgx, pgy, pgz, perm: g.perm.clone(), gx: g.x_off, gy: g.y_off, gz: g.z_off }
    }
}

/// Batched octave generator: SIMD lanes = chunks (4 lanes per AVX2 f64
/// group; batches larger than 4 iterate in groups).
pub struct BatchOctaves {
    pub octaves: Octaves,
    pub tables: Vec<FlatTables>,
}

impl BatchOctaves {
    pub fn new(seed: i64, octaves: i32) -> Self {
        let mut rand = JavaRandom::new(seed);
        let oct = Octaves::new(&mut rand, octaves);
        let tables = oct.generators.iter().map(FlatTables::from_improved).collect();
        BatchOctaves { octaves: oct, tables }
    }

    /// Batched func_76304_a for `n` chunks into `out` (n * len, chunk-major).
    /// Per-chunk x/z offsets; shared y offset/sizes/scales. Bit-exact vs
    /// per-chunk scalar generate3d (proven by the differential harness).
    pub fn generate3d_batch(
        &self,
        out: &mut [f64],
        n: usize,
        x_offs: &[i32],
        y_off: i32,
        z_offs: &[i32],
        x_size: i32,
        y_size: i32,
        z_size: i32,
        x_scale: f64,
        y_scale: f64,
        z_scale: f64,
    ) {
        let len = (x_size * y_size * z_size) as usize;
        for v in out.iter_mut().take(n * len) {
            *v = 0.0;
        }
        let mut d3 = 1.0f64;
        for j in 0..self.octaves.octaves as usize {
            let t = &self.tables[j];
            let d7 = y_off as f64 * d3 * y_scale;
            #[cfg(target_arch = "x86_64")]
            {
                if crate::simd::is_avx2() && n > 0 && y_size > 1 { // ySize==1 = vanilla 2D special path: scalar only
                    let mut g0 = 0usize;
                    while g0 < n {
                        let lanes = (n - g0).min(4);
                        let mut d6 = [0.0f64; 4];
                        let mut d8 = [0.0f64; 4];
                        for lane in 0..4usize {
                            let src = g0 + lane.min(lanes - 1);
                            let mut dx = x_offs[src] as f64 * d3 * x_scale;
                            let mut dz = z_offs[src] as f64 * d3 * z_scale;
                            let k6 = crate::floor_long_pub(dx);
                            let j7 = crate::floor_long_pub(dz);
                            dx -= k6 as f64;
                            dz -= j7 as f64;
                            dx += (k6 % 16777216) as f64;
                            dz += (j7 % 16777216) as f64;
                            d6[lane] = dx;
                            d8[lane] = dz;
                        }
                        unsafe {
                            populate_batch_avx4(
                                out, g0, lanes, len, t, &d6, d7, &d8,
                                x_size, y_size, z_size,
                                x_scale * d3, y_scale * d3, z_scale * d3, d3,
                            );
                        }
                        g0 += 4;
                    }
                    d3 /= 2.0;
                    continue;
                }
            }
            for lane in 0..n {
                let g = &self.octaves.generators[j];
                let mut dx = x_offs[lane] as f64 * d3 * x_scale;
                let mut dz = z_offs[lane] as f64 * d3 * z_scale;
                let k6 = crate::floor_long_pub(dx);
                let j7 = crate::floor_long_pub(dz);
                dx -= k6 as f64;
                dz -= j7 as f64;
                dx += (k6 % 16777216) as f64;
                dz += (j7 % 16777216) as f64;
                let base = lane * len;
                g.populate_into(
                    &mut out[base..base + len],
                    dx, d7, dz,
                    x_size, y_size, z_size,
                    x_scale * d3, y_scale * d3, z_scale * d3, d3,
                );
            }
            d3 /= 2.0;
        }
    }
}

#[cfg(target_arch = "x86_64")]
pub fn is_avx2() -> bool {
    use std::sync::atomic::{AtomicU8, Ordering};
    static AVX2: AtomicU8 = AtomicU8::new(2);
    let v = AVX2.load(Ordering::Relaxed);
    if v == 2 {
        let ok = is_x86_feature_detected!("avx2");
        AVX2.store(u8::from(ok), Ordering::Relaxed);
        ok
    } else {
        v == 1
    }
}

/// Java d2i + decrement-if-less floor, vectorized (exact for |d| < 2^31).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn v_floor_i32(d: __m256d) -> (__m128i, __m256d) {
    let ti = _mm256_cvttpd_epi32(d);
    let ti_f = _mm256_cvtepi32_pd(ti);
    let less = _mm256_cmp_pd(d, ti_f, _CMP_LT_OQ);
    let one_or_zero_f = _mm256_blendv_pd(_mm256_setzero_pd(), _mm256_set1_pd(1.0), less);
    let ti_f2 = _mm256_sub_pd(ti_f, one_or_zero_f);
    (_mm256_cvttpd_epi32(ti_f2), ti_f2)
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn v_fade(d: __m256d) -> __m256d {
    let d3 = _mm256_mul_pd(_mm256_mul_pd(d, d), d);
    let t1 = _mm256_mul_pd(d, _mm256_set1_pd(6.0));
    let t2 = _mm256_sub_pd(t1, _mm256_set1_pd(15.0));
    let t3 = _mm256_mul_pd(d, t2);
    let t4 = _mm256_add_pd(t3, _mm256_set1_pd(10.0));
    _mm256_mul_pd(d3, t4)
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn v_lerp(blend: __m256d, a: __m256d, b: __m256d) -> __m256d {
    let ba = _mm256_sub_pd(b, a);
    let m = _mm256_mul_pd(blend, ba);
    _mm256_add_pd(a, m)
}

/// Corner grad dot: (gx*x + gy*y) + gz*z — left-assoc, matching scalar.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn v_dot3(gx: __m256d, gy: __m256d, gz: __m256d, x: __m256d, y: __m256d, z: __m256d) -> __m256d {
    let a = _mm256_mul_pd(gx, x);
    let b = _mm256_mul_pd(gy, y);
    let ab = _mm256_add_pd(a, b);
    let c = _mm256_mul_pd(gz, z);
    _mm256_add_pd(ab, c)
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn populate_batch_avx4(
    out: &mut [f64],
    g0: usize,
    lanes: usize,
    len: usize,
    t: &FlatTables,
    d6: &[f64; 4],
    d7: f64,
    d8: &[f64; 4],
    x_size: i32,
    y_size: i32,
    z_size: i32,
    x_scale: f64,
    y_scale: f64,
    z_scale: f64,
    noise_scale: f64,
) {
    let xs = x_size as usize;
    let ys = y_size as usize;
    let zs = z_size as usize;
    let inv = _mm256_set1_pd(1.0 / noise_scale);
    let d6v = _mm256_loadu_pd(d6.as_ptr());
    let d8v = _mm256_loadu_pd(d8.as_ptr());
    let d7s = _mm256_set1_pd(d7);
    let xsc = _mm256_set1_pd(x_scale);
    let ysc = _mm256_set1_pd(y_scale);
    let zsc = _mm256_set1_pd(z_scale);
    let gx_s = _mm256_set1_pd(t.gx);
    let gy_s = _mm256_set1_pd(t.gy);
    let gz_s = _mm256_set1_pd(t.gz);
    let grad_x = t.pgx.as_ptr();
    let grad_y = t.pgy.as_ptr();
    let grad_z = t.pgz.as_ptr();
    let perm = t.perm.as_ptr();
    let ff_255 = _mm_set1_epi32(0xFF);
    let one_i = _mm_set1_epi32(1);
    let zero_i = _mm_setzero_si128();

    let mut slab = vec![0.0f64; len * 4];
    let mut last_y = _mm_set1_epi32(-1);
    let (mut c0c, mut c1c, mut c2c, mut c3c) = (
        _mm256_setzero_pd(), _mm256_setzero_pd(), _mm256_setzero_pd(), _mm256_setzero_pd());

    let mut idx = 0usize;
    for ix in 0..xs {
        let dx0 = _mm256_add_pd(
            _mm256_add_pd(d6v, _mm256_mul_pd(_mm256_set1_pd(ix as f64), xsc)),
            gx_s,
        );
        let (xi, xi_f) = v_floor_i32(dx0);
        let cx = _mm_and_si128(xi, ff_255);
        let dx = _mm256_sub_pd(dx0, xi_f);
        let u = v_fade(dx);
        for iz in 0..zs {
            let dz0 = _mm256_add_pd(
                _mm256_add_pd(d8v, _mm256_mul_pd(_mm256_set1_pd(iz as f64), zsc)),
                gz_s,
            );
            let (zi, zi_f) = v_floor_i32(dz0);
            let cz = _mm_and_si128(zi, ff_255);
            let dz = _mm256_sub_pd(dz0, zi_f);
            let w = v_fade(dz);
            for iy in 0..ys {
                let dy0 = _mm256_add_pd(
                    _mm256_add_pd(d7s, _mm256_mul_pd(_mm256_set1_pd(iy as f64), ysc)),
                    gy_s,
                );
                let (yi, yi_f) = v_floor_i32(dy0);
                let cy = _mm_and_si128(yi, ff_255);
                let dy = _mm256_sub_pd(dy0, yi_f);
                let v = v_fade(dy);

                let skip = if iy == 0 { zero_i } else { _mm_cmpeq_epi32(cy, last_y) };
                // replicate each 32-bit mask lane into its 64-bit pair so the f64 sign
                // bit equals that lane's flag (castps_pd reads only the upper half!)
                let lo = _mm_unpacklo_epi32(skip, skip);
                let hi = _mm_unpackhi_epi32(skip, skip);
                let skip_pd = _mm256_castsi256_pd(_mm256_insertf128_si256::<1>(_mm256_castsi128_si256(lo), hi));

                // Fast path: when EVERY lane would keep its cache (the common
                // case in high octaves where yScale*d3 < 1), the expensive
                // perm/gather/grad work is skipped entirely — the scalar
                // algorithm's own amortization, restored under SIMD. Lanes are
                // unaffected: c0..c3 remain the cached values for all lanes.
                if _mm_movemask_epi8(skip) == 0xFFFF && iy != 0 {
                    let inner = v_lerp(v, c0c, c1c);
                    let inner2 = v_lerp(v, c2c, c3c);
                    let val = _mm256_mul_pd(v_lerp(w, inner, inner2), inv);
                    let p = slab.as_mut_ptr().add(idx * 4);
                    let cur = _mm256_loadu_pd(p);
                    _mm256_storeu_pd(p, _mm256_add_pd(cur, val));
                    idx += 1;
                    continue;
                }

                let b0 = _mm_add_epi32(_mm_i32gather_epi32(perm, cx, 4), cy);
                let l0 = _mm_add_epi32(_mm_i32gather_epi32(perm, b0, 4), cz);
                let b0p1 = _mm_add_epi32(b0, one_i);
                let l1 = _mm_add_epi32(_mm_i32gather_epi32(perm, b0p1, 4), cz);
                let cxp1 = _mm_add_epi32(cx, one_i);
                let b1 = _mm_add_epi32(_mm_i32gather_epi32(perm, cxp1, 4), cy);
                let l2 = _mm_add_epi32(_mm_i32gather_epi32(perm, b1, 4), cz);
                let b1p1 = _mm_add_epi32(b1, one_i);
                let l3 = _mm_add_epi32(_mm_i32gather_epi32(perm, b1p1, 4), cz);

                let d1 = _mm256_set1_pd(1.0);
                let dxm = _mm256_sub_pd(dx, d1);
                let dym = _mm256_sub_pd(dy, d1);
                let dzm = _mm256_sub_pd(dz, d1);

                let g0x = _mm256_i32gather_pd(grad_x, l0, 8);
                let g0y = _mm256_i32gather_pd(grad_y, l0, 8);
                let g0z = _mm256_i32gather_pd(grad_z, l0, 8);
                let d0 = v_dot3(g0x, g0y, g0z, dx, dy, dz);
                let g2x = _mm256_i32gather_pd(grad_x, l2, 8);
                let g2y = _mm256_i32gather_pd(grad_y, l2, 8);
                let g2z = _mm256_i32gather_pd(grad_z, l2, 8);
                let d2 = v_dot3(g2x, g2y, g2z, dxm, dy, dz);
                let c0n = v_lerp(u, d0, d2);

                let g1x = _mm256_i32gather_pd(grad_x, l1, 8);
                let g1y = _mm256_i32gather_pd(grad_y, l1, 8);
                let g1z = _mm256_i32gather_pd(grad_z, l1, 8);
                let d1v = v_dot3(g1x, g1y, g1z, dx, dym, dz);
                let g3x = _mm256_i32gather_pd(grad_x, l3, 8);
                let g3y = _mm256_i32gather_pd(grad_y, l3, 8);
                let g3z = _mm256_i32gather_pd(grad_z, l3, 8);
                let d3v = v_dot3(g3x, g3y, g3z, dxm, dym, dz);
                let c1n = v_lerp(u, d1v, d3v);

                let l0p1 = _mm_add_epi32(l0, one_i);
                let g4x = _mm256_i32gather_pd(grad_x, l0p1, 8);
                let g4y = _mm256_i32gather_pd(grad_y, l0p1, 8);
                let g4z = _mm256_i32gather_pd(grad_z, l0p1, 8);
                let d4v = v_dot3(g4x, g4y, g4z, dx, dy, dzm);
                let l2p1 = _mm_add_epi32(l2, one_i);
                let g5x = _mm256_i32gather_pd(grad_x, l2p1, 8);
                let g5y = _mm256_i32gather_pd(grad_y, l2p1, 8);
                let g5z = _mm256_i32gather_pd(grad_z, l2p1, 8);
                let d5v = v_dot3(g5x, g5y, g5z, dxm, dy, dzm);
                let c2n = v_lerp(u, d4v, d5v);

                let l1p1 = _mm_add_epi32(l1, one_i);
                let g6x = _mm256_i32gather_pd(grad_x, l1p1, 8);
                let g6y = _mm256_i32gather_pd(grad_y, l1p1, 8);
                let g6z = _mm256_i32gather_pd(grad_z, l1p1, 8);
                let d6v2 = v_dot3(g6x, g6y, g6z, dx, dym, dzm);
                let l3p1 = _mm_add_epi32(l3, one_i);
                let g7x = _mm256_i32gather_pd(grad_x, l3p1, 8);
                let g7y = _mm256_i32gather_pd(grad_y, l3p1, 8);
                let g7z = _mm256_i32gather_pd(grad_z, l3p1, 8);
                let d7v2 = v_dot3(g7x, g7y, g7z, dxm, dym, dzm);
                let c3n = v_lerp(u, d6v2, d7v2);

                c0c = _mm256_blendv_pd(c0n, c0c, skip_pd);
                c1c = _mm256_blendv_pd(c1n, c1c, skip_pd);
                c2c = _mm256_blendv_pd(c2n, c2c, skip_pd);
                c3c = _mm256_blendv_pd(c3n, c3c, skip_pd);
                last_y = cy;

                let inner = v_lerp(v, c0c, c1c);
                let inner2 = v_lerp(v, c2c, c3c);
                let val = _mm256_mul_pd(v_lerp(w, inner, inner2), inv);
                let p = slab.as_mut_ptr().add(idx * 4);
                let cur = _mm256_loadu_pd(p);
                _mm256_storeu_pd(p, _mm256_add_pd(cur, val));
                idx += 1;
            }
        }
    }
    for lane in 0..lanes {
        let base = (g0 + lane) * len;
        for i in 0..len {
            out[base + i] += slab[i * 4 + lane];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Differential: batched SIMD path vs per-chunk scalar path, bit-exact.
    /// This is the cheap first gate; the Java-oracle gate runs in the harness.
    #[test]
    fn batched_simd_matches_scalar_bit_exact() {
        if !is_avx2() {
            eprintln!("no AVX2; skipping");
            return;
        }
        for (seed, oct) in [(1i64, 16i32), (4096i64, 8i32), (987654321i64, 10i32)] {
            let b = BatchOctaves::new(seed, oct);
            let shapes: &[(i32, i32, i32)] = &[(5, 33, 5), (5, 1, 5), (4, 17, 4), (1, 4, 1), (10, 8, 10)];
            for (xs, ys, zs) in shapes {
                for scales in [
                    (684.412, 684.412, 684.412),
                    (684.412 / 80.0, 684.412 / 160.0, 684.412 / 80.0),
                ] {
                    for n in [1usize, 4, 8, 16] {
                        let len = (xs * ys * zs) as usize;
                        let mut xo = vec![0i32; n];
                        let mut zo = vec![0i32; n];
                        let mut rng = seed;
                        for c in 0..n {
                            rng = rng.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                            xo[c] = ((rng >> 33) as i32) % 400_000;
                            zo[c] = ((rng >> 13) as i32) % -400_000;
                        }
                        let mut batch_out = vec![0.0f64; n * len];
                        b.generate3d_batch(&mut batch_out, n, &xo, 0, &zo, *xs, *ys, *zs,
                            scales.0, scales.1, scales.2);
                        for c in 0..n {
                            let scalar = b.octaves.generate3d(None, xo[c], 0, zo[c], *xs, *ys, *zs,
                                scales.0, scales.1, scales.2);
                            for i in 0..len {
                                assert_eq!(
                                    batch_out[c * len + i].to_bits(),
                                    scalar[i].to_bits(),
                                    "seed={} oct={} shape={:?} n={} c={} i={}",
                                    seed, oct, (xs, ys, zs), n, c, i
                                );
                            }
                        }
                    }
                }
            }
        }
    }
}

/// n=1 x-lane kernel (M3W3): lanes = x positions of a single field; z/y
/// iterate scalar (shared dy/dz/cy across lanes — the y-cell change branch is
/// uniform, so the scalar cache-amortization fast path applies directly).
/// Layout matches vanilla: out[(ix*zs + iz)*ys + iy].
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
pub unsafe fn populate_xlanes_avx4(
    out: &mut [f64],
    _len: usize,
    t: &FlatTables,
    dx_base: f64,
    x_scale: f64,
    d7: f64,
    dz_base: f64,
    x_size: i32,
    y_size: i32,
    z_size: i32,
    y_scale: f64,
    z_scale: f64,
    noise_scale: f64,
) {
    let xs = x_size as usize;
    let ys = y_size as usize;
    let zs = z_size as usize;
    let inv = _mm256_set1_pd(1.0 / noise_scale);
    let d7s = _mm256_set1_pd(d7);
    let ysc = _mm256_set1_pd(y_scale);
    let zsc = _mm256_set1_pd(z_scale);
    let gy_s = _mm256_set1_pd(t.gy);
    let grad_x = t.pgx.as_ptr();
    let grad_y = t.pgy.as_ptr();
    let grad_z = t.pgz.as_ptr();
    let perm = t.perm.as_ptr();
    let ff_255 = _mm_set1_epi32(0xFF);
    let one_i = _mm_set1_epi32(1);

    // lane dx/cx/u for the whole field (per octave): lanes = ix
    let mut lane_group = 0usize;
    while lane_group < xs {
        let lanes = (xs - lane_group).min(4);
        let mut d6 = [0.0f64; 4];
        for lane in 0..4usize {
            let ix = lane_group + lane.min(lanes - 1);
            d6[lane] = dx_base + ix as f64 * x_scale;
        }
        let dx0 = _mm256_add_pd(_mm256_loadu_pd(d6.as_ptr()), _mm256_set1_pd(t.gx));
        let (xi, xi_f) = v_floor_i32(dx0);
        let cx = _mm_and_si128(xi, ff_255);
        let dxv = _mm256_sub_pd(dx0, xi_f);
        let u = v_fade(dxv);
        let d8v = _mm256_add_pd(_mm256_set1_pd(dz_base), _mm256_set1_pd(t.gz));

        let mut last_y: i32 = -1;
        let (mut c0c, mut c1c, mut c2c, mut c3c) = (
            _mm256_setzero_pd(), _mm256_setzero_pd(), _mm256_setzero_pd(), _mm256_setzero_pd());

        for iz in 0..zs {
            let dz0 = _mm256_add_pd(d8v, _mm256_mul_pd(_mm256_set1_pd(iz as f64), zsc));
            let (zi, zi_f) = v_floor_i32(dz0);
            let cz = _mm_and_si128(zi, ff_255);
            let dzv = _mm256_sub_pd(dz0, zi_f);
            let w = v_fade(dzv);
            for iy in 0..ys {
                let dy0 = _mm256_add_pd(
                    _mm256_add_pd(d7s, _mm256_mul_pd(_mm256_set1_pd(iy as f64), ysc)),
                    gy_s,
                );
                let (yi, yi_f) = v_floor_i32(dy0);
                // dy is lane-uniform -> yi/cy uniform; extract lane 0 once
                let cy_scalar = _mm_cvtsi128_si32(yi) & 0xFF;
                let cy = _mm_set1_epi32(cy_scalar);
                let dyv = _mm256_sub_pd(dy0, yi_f);
                let v = v_fade(dyv);

                let skip = iy != 0 && cy_scalar == last_y;
                if !skip {
                    last_y = cy_scalar;
                    let b0 = _mm_add_epi32(_mm_i32gather_epi32(perm, cx, 4), cy);
                    let l0 = _mm_add_epi32(_mm_i32gather_epi32(perm, b0, 4), cz);
                    let b0p1 = _mm_add_epi32(b0, one_i);
                    let l1 = _mm_add_epi32(_mm_i32gather_epi32(perm, b0p1, 4), cz);
                    let cxp1 = _mm_add_epi32(cx, one_i);
                    let b1 = _mm_add_epi32(_mm_i32gather_epi32(perm, cxp1, 4), cy);
                    let l2 = _mm_add_epi32(_mm_i32gather_epi32(perm, b1, 4), cz);
                    let b1p1 = _mm_add_epi32(b1, one_i);
                    let l3 = _mm_add_epi32(_mm_i32gather_epi32(perm, b1p1, 4), cz);

                    let d1 = _mm256_set1_pd(1.0);
                    let dxm = _mm256_sub_pd(dxv, d1);
                    let dym = _mm256_sub_pd(dyv, d1);
                    let dzm = _mm256_sub_pd(dzv, d1);

                    let g0x = _mm256_i32gather_pd(grad_x, l0, 8);
                    let g0y = _mm256_i32gather_pd(grad_y, l0, 8);
                    let g0z = _mm256_i32gather_pd(grad_z, l0, 8);
                    let d0 = v_dot3(g0x, g0y, g0z, dxv, dyv, dzv);
                    let g2x = _mm256_i32gather_pd(grad_x, l2, 8);
                    let g2y = _mm256_i32gather_pd(grad_y, l2, 8);
                    let g2z = _mm256_i32gather_pd(grad_z, l2, 8);
                    let d2 = v_dot3(g2x, g2y, g2z, dxm, dyv, dzv);
                    c0c = v_lerp(u, d0, d2);

                    let g1x = _mm256_i32gather_pd(grad_x, l1, 8);
                    let g1y = _mm256_i32gather_pd(grad_y, l1, 8);
                    let g1z = _mm256_i32gather_pd(grad_z, l1, 8);
                    let d1v = v_dot3(g1x, g1y, g1z, dxv, dym, dzv);
                    let g3x = _mm256_i32gather_pd(grad_x, l3, 8);
                    let g3y = _mm256_i32gather_pd(grad_y, l3, 8);
                    let g3z = _mm256_i32gather_pd(grad_z, l3, 8);
                    let d3v = v_dot3(g3x, g3y, g3z, dxm, dym, dzv);
                    c1c = v_lerp(u, d1v, d3v);

                    let l0p1 = _mm_add_epi32(l0, one_i);
                    let g4x = _mm256_i32gather_pd(grad_x, l0p1, 8);
                    let g4y = _mm256_i32gather_pd(grad_y, l0p1, 8);
                    let g4z = _mm256_i32gather_pd(grad_z, l0p1, 8);
                    let d4v = v_dot3(g4x, g4y, g4z, dxv, dyv, dzm);
                    let l2p1 = _mm_add_epi32(l2, one_i);
                    let g5x = _mm256_i32gather_pd(grad_x, l2p1, 8);
                    let g5y = _mm256_i32gather_pd(grad_y, l2p1, 8);
                    let g5z = _mm256_i32gather_pd(grad_z, l2p1, 8);
                    let d5v = v_dot3(g5x, g5y, g5z, dxm, dyv, dzm);
                    c2c = v_lerp(u, d4v, d5v);

                    let l1p1 = _mm_add_epi32(l1, one_i);
                    let g6x = _mm256_i32gather_pd(grad_x, l1p1, 8);
                    let g6y = _mm256_i32gather_pd(grad_y, l1p1, 8);
                    let g6z = _mm256_i32gather_pd(grad_z, l1p1, 8);
                    let d6v2 = v_dot3(g6x, g6y, g6z, dxv, dym, dzm);
                    let l3p1 = _mm_add_epi32(l3, one_i);
                    let g7x = _mm256_i32gather_pd(grad_x, l3p1, 8);
                    let g7y = _mm256_i32gather_pd(grad_y, l3p1, 8);
                    let g7z = _mm256_i32gather_pd(grad_z, l3p1, 8);
                    let d7v2 = v_dot3(g7x, g7y, g7z, dxm, dym, dzm);
                    c3c = v_lerp(u, d6v2, d7v2);
                }

                let val = _mm256_mul_pd(v_lerp(w, v_lerp(v, c0c, c1c), v_lerp(v, c2c, c3c)), inv);
                let mut arr = [0.0f64; 4];
                _mm256_storeu_pd(arr.as_mut_ptr(), val);
                for lane in 0..lanes {
                    let ix = lane_group + lane;
                    let oi = (ix * zs + iz) * ys + iy;
                    out[oi] += arr[lane];
                }
            }
        }
        lane_group += 4;
    }
}
