use worldgen_noise::simd::BatchOctaves;

fn main() {
    // reproduce: seed=1 oct=16 shape (5,33,5) n=4 c=2 i=1
    let seed = 1i64;
    let b = BatchOctaves::new(seed, 16);
    let n = 4usize;
    let xs = 5i32;
    let ys = 33;
    let zs = 5;
    let len = (xs * ys * zs) as usize;
    let mut rng = seed;
    let mut xo = vec![0i32; n];
    let mut zo = vec![0i32; n];
    for c in 0..n {
        rng = rng.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        xo[c] = ((rng >> 33) as i32) % 400_000;
        zo[c] = ((rng >> 13) as i32) % -400_000;
    }
    println!("offsets x={:?} z={:?}", xo, zo);
    let (xsc, ysc, zsc) = (684.412f64, 684.412f64, 684.412f64);
    let mut batch = vec![0.0f64; n * len];
    b.generate3d_batch(&mut batch, n, &xo, 0, &zo, xs, ys, zs, xsc, ysc, zsc);
    // per-lane scalar references for the FIRST 12 octaves, first-diff per octave
    for oct in 0..16 {
        // isolate octave oct by comparing full-run then per-octave via direct populate
        // easier: compute scalar full and compare sample i=0..4
        let scalar = b.octaves.generate3d(None, xo[2], 0, zo[2], xs, ys, zs, xsc, ysc, zsc);
        let mut diffs = 0;
        for i in 0..8 {
            if batch[2 * len + i].to_bits() != scalar[i].to_bits() {
                if diffs < 2 {
                    println!("oct-run diff i={} simd={:x} scalar={:x}", i,
                        batch[2 * len + i].to_bits(), scalar[i].to_bits());
                }
                diffs += 1;
            }
        }
        break; // only full-run compare; per-octave below
    }
    // per-octave: single-octave batch via a 1-octave BatchOctaves fed same perm? Instead:
    // directly run the batched kernel for ONE octave by constructing BatchOctaves with
    // oct count = (oct+1) and only comparing the marginal contribution is complex.
    // Instead: bisect octave count.
    for octn in [10i32, 11, 12, 13, 14, 15, 16] {
        let bb = BatchOctaves::new(seed, octn);
        let mut bt = vec![0.0f64; n * len];
        bb.generate3d_batch(&mut bt, n, &xo, 0, &zo, xs, ys, zs, xsc, ysc, zsc);
        let sc = bb.octaves.generate3d(None, xo[2], 0, zo[2], xs, ys, zs, xsc, ysc, zsc);
        let d = (0..8).filter(|&i| bt[2 * len + i].to_bits() != sc[i].to_bits()).count();
        println!("oct_count={} first8 diffs={}", octn, d);
    }
}
