//! OPT-ALG-001 differ: run ONE corpus job under carries-value vs
//! legacy-propagate on identically primed worlds and print the first
//! differing records. (Run under `--features legacy-propagate` for the
//! legacy side; the default build is the new side. The runner script
//! captures both.)

mod corpus_common;
use corpus_common::*;
use native_chunk::ChunkKey;
use rustcraft_ffi::get_registry;
use rustcraft_ffi::zero_stage_job::run_zero_stage_job;

#[test]
fn alg_differ_one_job() {
    let mca = corpus_mca();
    if !mca.is_file() {
        println!("[differ] SKIP");
        return;
    }
    let dim = 99;
    let center = (14i32, 14i32);
    let mut loaded = Vec::new();
    for dx in -2..=2i32 {
        for dz in -2..=2i32 {
            if let Some(lc) = load_chunk(&mca, center.0 + dx, center.1 + dz) {
                loaded.push(lc);
            }
        }
    }
    let mut solve_states: Vec<u32> = Vec::new();
    for lc in &loaded {
        for &st in &lc.primer {
            if st != 0 && !solve_states.contains(&st) {
                solve_states.push(st);
            }
        }
    }
    let solve_table = build_table(&solve_states);
    solve_baseline_light(&mut loaded, &solve_table);
    let mut states = Vec::new();
    for lc in &loaded {
        for &s in &lc.primer {
            if s != 0 && !states.contains(&s) {
                states.push(s);
            }
        }
    }
    let table = build_table(&states);

    // pick the first emissive origin in the center chunk (a real torch
    // cell from the campaign world) plus a darken variant
    let mut origins: Vec<(i32, i32, i32, bool)> = Vec::new();
    for lc in &loaded {
        let ring_ok = (lc.cx - center.0).abs() <= 1 && (lc.cz - center.1).abs() <= 1;
        if !ring_ok {
            continue;
        }
        for y in 0..256usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let pidx = (x << 12) | (z << 8) | y;
                    let s = lc.primer[pidx];
                    if s != 0 && light_props(s >> 4).1 > 0 {
                        origins.push((
                            lc.cx * 16 + x as i32,
                            y as i32,
                            lc.cz * 16 + z as i32,
                            false,
                        ));
                        origins.push((
                            lc.cx * 16 + x as i32,
                            y as i32,
                            lc.cz * 16 + z as i32,
                            true,
                        ));
                    }
                }
            }
        }
    }
    let mut out = vec![0i32; 1 << 18];
    for (wi, &(wx, wy, wz, darken)) in origins.iter().enumerate() {
        for lc in &loaded {
            prime_into_registry(lc, dim);
        }
        if darken {
            get_registry().mirror_block_state(
                ChunkKey::new(dim, wx >> 4, wz >> 4),
                (wx & 15) as usize,
                wy as usize,
                (wz & 15) as usize,
                0,
            );
        }
        let rc = run_zero_stage_job(dim, wx, wy, wz, &table, &mut out, 1 << 22, 64, false);
        // canonical per-job hash: value-histogram over world coords
        let mut recs: Vec<(i32, i32, i32, i32)> = (0..rc.max(0) as usize)
            .map(|i| (out[i * 4], out[i * 4 + 1], out[i * 4 + 2], out[i * 4 + 3]))
            .collect();
        recs.sort();
        let mut h: u64 = 0x9E3779B97F4A7C15;
        for r in &recs {
            h = (h ^ (r.0 as u64)).wrapping_mul(0x100000001b3);
            h = (h ^ (r.1 as u64)).wrapping_mul(0x100000001b3);
            h = (h ^ (r.2 as u64)).wrapping_mul(0x100000001b3);
            h = (h ^ (r.3 as u64)).wrapping_mul(0x100000001b3);
        }
        println!(
            "[differ] J {} ({},{},{}) dk={} rc={} h={:016x}",
            wi, wx, wy, wz, darken, rc, h
        );
        if wx == 224 && wy == 9 && wz == 246 && darken {
            for r in recs.iter().take(12) {
                println!("[differ]   R ({},{},{}) v={}", r.0, r.1, r.2, r.3);
            }
            if recs.len() > 12 {
                println!("[differ]   ... +{} more", recs.len() - 12);
            }
        }
    }
}
