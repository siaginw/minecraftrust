fn main() {
    let mut r = worldgen_noise::JavaRandom::new(4096);
    let oct = worldgen_noise::Octaves::new(&mut r, 16);
    let g = &oct.generators[0];
    println!("gen0 x={:x?} y={:x?} z={:x?}", g.x_off.to_bits(), g.y_off.to_bits(), g.z_off.to_bits());
    println!("perm: {:?}", &g.perm[0..16]);
    let res = oct.generate3d(None, 0, 0, 0, 1, 4, 1, 684.412, 684.412, 684.412);
    for (i, v) in res.iter().enumerate() { println!("s{}={:x}", i, v.to_bits()); }
    let mut res2 = vec![0.0f64; 4];
    g.populate(&mut res2, 0.0, 0.0, 0.0, 1, 4, 1, 684.412, 684.412, 684.412, 1.0);
    for (i, v) in res2.iter().enumerate() { println!("g0_s{}={:x}", i, v.to_bits()); }
}
