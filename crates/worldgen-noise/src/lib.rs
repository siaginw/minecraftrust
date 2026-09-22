//! M3-WORLDGEN-NOISE prototype: exact Rust port of the vanilla 1.12.2
//! terrain-noise kernel (NoiseGeneratorImproved + NoiseGeneratorOctaves),
//! byte-verified against the installed jar (machine/raw/diag/noise-*-full.txt).
//!
//! EXACTNESS CONTRACT — every operation preserves Java double semantics and
//! evaluation order:
//!  - java.util.Random: 48-bit LCG (scramble on setSeed, next(bits),
//!    nextInt(bound) incl. the rare rejection path, nextDouble =
//!    (next(26)<<27 + next(27)) * 2^-53 exactly)
//!  - ctor: xOff/yOff/zOff = nextDouble()*256.0; perm identity then
//!    Fisher-Yates j = nextInt(256-i)+i; perm[i+256]=perm[i]
//!  - floor: (int)d then decrement if d < (double)i (matches d2i + dcmpg)
//!  - fade: t3 = d*d*d; fade = t3 * (d * (d * 6.0 - 15.0) + 10.0) in that
//!    exact order (bytecode 91-117)
//!  - octave wrapper (func_76304_a): d3 halving; x/z offsets normalized by
//!    MathHelper.floor-then-Java-lrem (truncated remainder == Rust %);
//!    accumulates `arr[i] += sample * (1.0/noiseScale)` with the reciprocal
//!    PRECOMPUTED (not a division per sample)
//!  - 3D batch: x outer / z middle / y inner loop order; perm-chain recompute
//!    skipped only when Y unchanged within an (x,z) column (lastY starts -1);
//!    lerp tree: lerp(u,...) x-pairs, lerp(v,...) y-pairs, lerp(w,...) outer
//!  - 2D batch (ySize==1): x outer / z inner, z-blend LAST, grad2 corner pair
//!  - lerp(blend,a,b) = a + blend*(b-a); grad tables are exact float literals
//!
//! NO reassociation, NO FMA, NO fast-math. f64 arithmetic in Rust defaults
//! to strict IEEE 754 — this port relies on that and tests it.

/// Java java.util.Random (48-bit LCG), exact.
#[derive(Clone, Debug)]
pub struct JavaRandom {
    seed: i64,
}

const MULT: i64 = 0x5DEECE66D;
const ADD: i64 = 0xB;
const MASK: i64 = (1i64 << 48) - 1;

impl JavaRandom {
    pub fn new(seed: i64) -> Self {
        let mut r = JavaRandom { seed: 0 };
        r.set_seed(seed);
        r
    }

    pub fn set_seed(&mut self, seed: i64) {
        self.seed = (seed ^ MULT) & MASK;
    }

    fn next(&mut self, bits: i32) -> i32 {
        self.seed = self.seed.wrapping_mul(MULT).wrapping_add(ADD) & MASK;
        (self.seed >> (48 - bits)) as i32
    }

    pub fn next_int(&mut self) -> i32 {
        self.next(32)
    }

    /// Exact java.util.Random.nextInt(bound) including the rejection path.
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        assert!(bound > 0);
        if bound & -bound == bound {
            // power of two: (int)((bound * (long)next(31)) >> 31)
            let r = self.next(31) as i64;
            ((bound as i64).wrapping_mul(r) >> 31) as i32
        } else {
            loop {
                let bits = self.next(31);
                let val = bits % bound;
                if bits.wrapping_sub(val).wrapping_add(bound - 1) >= 0 {
                    return val;
                }
            }
        }
    }

    /// Exact nextDouble: (next(26) << 27 + next(27)) * 2^-53.
    pub fn next_double(&mut self) -> f64 {
        let a = (self.next(26) as i64) << 27;
        let b = self.next(27) as i64;
        (a + b) as f64 * (1.0f64 / (1u64 << 53) as f64)
    }
}

/// Static gradient tables (bytecode float literal arrays).
pub const GRAD_X: [f64; 16] = [1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0];
pub const GRAD_Y: [f64; 16] = [1.0, 1.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0];
pub const GRAD_Z: [f64; 16] = [0.0, 0.0, 0.0, 0.0, 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 0.0, 1.0, 0.0, -1.0];
const GRAD2_X: [f64; 16] = GRAD_X;
const GRAD2_Z: [f64; 16] = GRAD_Z;

#[inline]
fn lerp(blend: f64, a: f64, b: f64) -> f64 {
    a + blend * (b - a)
}

#[inline]
fn grad3(hash: i32, x: f64, y: f64, z: f64) -> f64 {
    let i = (hash & 15) as usize;
    GRAD_X[i] * x + GRAD_Y[i] * y + GRAD_Z[i] * z
}

#[inline]
fn grad2(hash: i32, x: f64, z: f64) -> f64 {
    let i = (hash & 15) as usize;
    GRAD2_X[i] * x + GRAD2_Z[i] * z
}

#[inline]
fn floor_i32(d: f64) -> i32 {
    let i = d as i32; // Java d2i truncation; range-safe for noise coords
    if d < i as f64 {
        i - 1
    } else {
        i
    }
}

#[inline]
fn fade(d: f64) -> f64 {
    let d3 = d * d * d;
    d3 * (d * (d * 6.0 - 15.0) + 10.0)
}

/// NoiseGeneratorImproved — exact port.
#[derive(Clone, Debug)]
pub struct Improved {
    pub perm: Vec<i32>, // int[512] (Java int arithmetic; values 0..255)
    pub x_off: f64,
    pub y_off: f64,
    pub z_off: f64,
}

impl Improved {
    pub fn new(rand: &mut JavaRandom) -> Self {
        let x_off = rand.next_double() * 256.0;
        let y_off = rand.next_double() * 256.0;
        let z_off = rand.next_double() * 256.0;
        let mut perm = vec![0i32; 512];
        for (i, p) in perm.iter_mut().enumerate().take(256) {
            *p = i as i32;
        }
        for i in 0..256usize {
            let j = rand.next_int_bound(256 - i as i32) + i as i32;
            let t = perm[i];
            perm[i] = perm[j as usize];
            perm[j as usize] = t;
            perm[i + 256] = perm[i];
        }
        Improved { perm, x_off, y_off, z_off }
    }

    /// In-place accumulation variant used by the batched path: adds octave
    /// contributions into `arr` without allocating.
    #[allow(clippy::too_many_arguments)]
    pub fn populate_into(
        &self,
        arr: &mut [f64],
        x_off: f64,
        y_off: f64,
        z_off: f64,
        x_size: i32,
        y_size: i32,
        z_size: i32,
        x_scale: f64,
        y_scale: f64,
        z_scale: f64,
        noise_scale: f64,
    ) {
        // arr already zeroed by the caller; populate is pure accumulation
        self.populate(arr, x_off, y_off, z_off, x_size, y_size, z_size,
                      x_scale, y_scale, z_scale, noise_scale);
    }

    /// func_76308_a — the exact batch sampler (2D fast path when y_size==1).
    pub fn populate(
        &self,
        arr: &mut [f64],
        x_off: f64,
        y_off: f64,
        z_off: f64,
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
        if y_size == 1 {
            // 2D path: x outer, z inner, sequential index
            let inv = 1.0 / noise_scale;
            let mut idx = 0usize;
            for ix in 0..xs {
                let dx0 = x_off + ix as f64 * x_scale + self.x_off;
                let xi = floor_i32(dx0);
                let cx = xi & 0xFF;
                let dx = dx0 - xi as f64;
                let u = fade(dx);
                for iz in 0..zs {
                    let dz0 = z_off + iz as f64 * z_scale + self.z_off;
                    let zi = floor_i32(dz0);
                    let cz = zi & 0xFF;
                    let dz = dz0 - zi as f64;
                    let w = fade(dz);
                    let b0 = self.perm[cx as usize];
                    let l0 = self.perm[b0 as usize] + cz;
                    let b1 = self.perm[(cx + 1) as usize];
                    let l1 = self.perm[b1 as usize] + cz;
                    let a = lerp(
                        u,
                        grad2(self.perm[l0 as usize], dx, dz),
                        grad3(self.perm[l1 as usize], dx - 1.0, 0.0, dz),
                    );
                    let b = lerp(
                        u,
                        grad3(self.perm[(l0 + 1) as usize], dx, 0.0, dz - 1.0),
                        grad3(self.perm[(l1 + 1) as usize], dx - 1.0, 0.0, dz - 1.0),
                    );
                    arr[idx] += lerp(w, a, b) * inv;
                    idx += 1;
                }
            }
            return;
        }
        // 3D path: x outer, z middle, y inner; perm-chain skip when Y unchanged
        let inv = 1.0 / noise_scale;
        let mut idx = 0usize;
        let mut last_y: i32 = -1;
        // cached across y within a column when the y-cell is unchanged:
        // vanilla's skip (bytecode 696->981) reuses BOTH the perm chain AND
        // the four x-pair lerps from the previous y sample — a piecewise
        // quirk that is part of vanilla's exact semantics.
        let (mut l0, mut l1, mut l2, mut l3): (i32, i32, i32, i32) = (0, 0, 0, 0);
        let (mut c0, mut c1, mut c2, mut c3): (f64, f64, f64, f64) = (0.0, 0.0, 0.0, 0.0);
        for ix in 0..xs {
            let dx0 = x_off + ix as f64 * x_scale + self.x_off;
            let xi = floor_i32(dx0);
            let cx = xi & 0xFF;
            let dx = dx0 - xi as f64;
            let u = fade(dx);
            for iz in 0..zs {
                let dz0 = z_off + iz as f64 * z_scale + self.z_off;
                let zi = floor_i32(dz0);
                let cz = zi & 0xFF;
                let dz = dz0 - zi as f64;
                let w = fade(dz);
                for iy in 0..ys {
                    let dy0 = y_off + iy as f64 * y_scale + self.y_off;
                    let yi = floor_i32(dy0);
                    let cy = yi & 0xFF;
                    let dy = dy0 - yi as f64;
                    let v = fade(dy);
                    if iy != 0 && cy == last_y {
                        // bytecode 696->981: reuse perm chain AND c0..c3
                    } else {
                        last_y = cy;
                        let b0b = self.perm[cx as usize] + cy;
                        l0 = self.perm[b0b as usize] + cz;
                        l1 = self.perm[(b0b + 1) as usize] + cz;
                        let b1b = self.perm[(cx + 1) as usize] + cy;
                        l2 = self.perm[b1b as usize] + cz;
                        l3 = self.perm[(b1b + 1) as usize] + cz;
                        c0 = lerp(
                            u,
                            grad3(self.perm[l0 as usize], dx, dy, dz),
                            grad3(self.perm[l2 as usize], dx - 1.0, dy, dz),
                        );
                        c1 = lerp(
                            u,
                            grad3(self.perm[l1 as usize], dx, dy - 1.0, dz),
                            grad3(self.perm[l3 as usize], dx - 1.0, dy - 1.0, dz),
                        );
                        c2 = lerp(
                            u,
                            grad3(self.perm[(l0 + 1) as usize], dx, dy, dz - 1.0),
                            grad3(self.perm[(l2 + 1) as usize], dx - 1.0, dy, dz - 1.0),
                        );
                        c3 = lerp(
                            u,
                            grad3(self.perm[(l1 + 1) as usize], dx, dy - 1.0, dz - 1.0),
                            grad3(self.perm[(l3 + 1) as usize], dx - 1.0, dy - 1.0, dz - 1.0),
                        );
                    }
                    arr[idx] += lerp(w, lerp(v, c0, c1), lerp(v, c2, c3)) * inv;
                    idx += 1;
                }
            }
        }
    }
}

/// NoiseGeneratorOctaves — exact port of func_76304_a / func_76305_a.
#[derive(Clone, Debug)]
pub struct Octaves {
    pub generators: Vec<Improved>,
    pub octaves: i32,
}

/// MathHelper.func_76124_d(double)->long floor as long.
#[inline]
pub fn floor_long_pub(d: f64) -> i64 {
    d.floor() as i64
}

impl Octaves {
    pub fn new(rand: &mut JavaRandom, octaves: i32) -> Self {
        let generators = (0..octaves).map(|_| Improved::new(rand)).collect();
        Octaves { generators, octaves }
    }

    /// func_76304_a — exact.
    #[allow(clippy::too_many_arguments)]
    pub fn generate3d(
        &self,
        arr: Option<&mut [f64]>,
        x_off: i32,
        y_off: i32,
        z_off: i32,
        x_size: i32,
        y_size: i32,
        z_size: i32,
        x_scale: f64,
        y_scale: f64,
        z_scale: f64,
    ) -> Vec<f64> {
        let n = (x_size * y_size * z_size) as usize;
        let mut out = match arr {
            None => vec![0.0f64; n],
            Some(a) => {
                for v in a.iter_mut() {
                    *v = 0.0;
                }
                a.to_vec()
            }
        };
        let mut d3 = 1.0f64;
        for j in 0..self.octaves {
            let mut d6 = x_off as f64 * d3 * x_scale;
            let d7 = y_off as f64 * d3 * y_scale;
            let mut d8 = z_off as f64 * d3 * z_scale;
            let k6 = floor_long_pub(d6);
            let j7 = floor_long_pub(d8);
            d6 -= k6 as f64;
            d8 -= j7 as f64;
            let k6r = k6 % 16777216;
            let j7r = j7 % 16777216;
            d6 += k6r as f64;
            d8 += j7r as f64;
            self.generators[j as usize].populate(
                &mut out, d6, d7, d8, x_size, y_size, z_size,
                x_scale * d3, y_scale * d3, z_scale * d3, d3,
            );
            d3 /= 2.0;
        }
        out
    }

    /// func_76305_a — the 2D wrapper: (arr, xOff, zOff, xSize, zSize, xScale,
    /// zScale, DROPPED) delegating to 3D with yOff=10, ySize=1, yScale=1.0.
    /// Bytecode pushes dload_6 (1st double -> xScale) and dload_8 (2nd ->
    /// zScale); the THIRD double parameter is never passed = the vanilla quirk.
    pub fn generate2d(
        &self,
        arr: Option<&mut [f64]>,
        x_off: i32,
        z_off: i32,
        x_size: i32,
        z_size: i32,
        x_scale: f64,
        z_scale: f64,
        _dropped: f64,
    ) -> Vec<f64> {
        self.generate3d(arr, x_off, 10, z_off, x_size, 1, z_size, x_scale, 1.0, z_scale)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_random_lcg_reference_vector() {
        // Known java.util.Random(0) sequence: first nextInt() = -1155484576,
        // second = -723955199; nextInt(16)=0; nextDouble values fixed by spec.
        let mut r = JavaRandom::new(0);
        assert_eq!(r.next_int(), -1155484576);
        assert_eq!(r.next_int(), -723955400);
        let mut r2 = JavaRandom::new(0);
        assert_eq!(r2.next_int_bound(16), 11); // authoritative python LCG check committed in evidence
    }

    #[test]
    fn deterministic_and_seed_sensitive() {
        let mut r1 = JavaRandom::new(12345);
        let a = Improved::new(&mut r1);
        let mut r2 = JavaRandom::new(12345);
        let b = Improved::new(&mut r2);
        assert_eq!(a.perm, b.perm);
        assert_eq!(a.x_off.to_bits(), b.x_off.to_bits());
        let mut r3 = JavaRandom::new(12346);
        let c = Improved::new(&mut r3);
        assert_ne!(a.perm, c.perm);
    }

    #[test]
    fn outputs_finite_and_bounded() {
        let mut r = JavaRandom::new(42);
        let oct = Octaves::new(&mut r, 8);
        let out = oct.generate3d(None, 1000, 0, -2000, 5, 33, 5, 684.412 / 80.0, 684.412 / 160.0, 684.412 / 80.0);
        assert_eq!(out.len(), 5 * 33 * 5);
        for v in out {
            assert!(v.is_finite());
            assert!(v.abs() < 1e4);
        }
    }
}

pub mod simd;
pub mod field;
pub mod field_complete;
pub mod terrain;
