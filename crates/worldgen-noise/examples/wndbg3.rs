fn main() {
    let mut r = worldgen_noise::JavaRandom::new(4096);
    let oct = worldgen_noise::Octaves::new(&mut r, 16);
    let d3 = 2f64.powi(-10);
    let xsc = 684.412 * d3;
    let ysc = 684.412 * d3;
    let zsc = 684.412 * d3;
    let mut tmp = vec![0.0f64; 5 * 33 * 5];
    oct.generators[10].populate(&mut tmp, 0.0, 0.0, 0.0, 5, 33, 5, xsc, ysc, zsc, d3);
    println!("rust g10 s1={:x} (java c07531df94e0c8f7)", tmp[1].to_bits());
    // trace: y cells for column (0,0)
    for iy in 0..6 {
        let dy0 = 0.0 + iy as f64 * ysc + oct.generators[10].y_off;
        let yi = (dy0 as i32) - if dy0 < (dy0 as i32) as f64 { 1 } else { 0 };
        println!("y{} dy0={:?} cell={}", iy, dy0, yi & 0xFF);
    }
}
