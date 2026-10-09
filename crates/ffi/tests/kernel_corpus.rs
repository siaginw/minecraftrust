mod corpus_common;
use corpus_common::*;
use native_chunk::{ChunkKey, NativeChunk};
use rustcraft_ffi::get_registry;
use rustcraft_ffi::zero_stage_job::run_zero_stage_job;

#[test]
fn kernel_corpus_bench() {
    let mca = corpus_mca();
    if !mca.is_file() {
        println!("[corpus] SKIP: {} not found", mca.display());
        return;
    }
    // center on the zsa14 platform region (cx≈13-16, cz≈13-16 in r.0.0)
    let dim = 77;
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
    assert!(loaded.len() >= 9, "corpus: only {} chunks", loaded.len());
    // corpus hash over all primer bytes
    let mut corpus_hash: u64 = 0;
    for lc in &loaded {
        let mut bytes = Vec::with_capacity(lc.primer.len() * 4 + lc.light.len());
        for v in &lc.primer {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
        bytes.extend_from_slice(&lc.light);
        corpus_hash ^= fnv1a64(&bytes);
    }
    // table from present states
    let mut states = Vec::new();
    for lc in &loaded {
        for &s in &lc.primer {
            if s != 0 && !states.contains(&s) {
                states.push(s);
            }
        }
    }
    let table = build_table(&states);

    // job origins: every emissive cell + grid, with light variants
    let mut jobs: Vec<(i32, i32, i32, bool)> = Vec::new(); // (wx, wy, wz, darken)
    for lc in &loaded {
        // frontier can cross into neighbors: only origins whose whole
        // 3x3 chunk neighborhood is loaded (inner ring of the 5x5)
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
                        let wx = lc.cx * 16 + x as i32;
                        let wz = lc.cz * 16 + z as i32;
                        jobs.push((wx, y as i32, wz, false));
                        jobs.push((wx, y as i32, wz, true)); // removal variant
                    }
                }
            }
        }
    }
    // a few grid jobs for terrain-only regions
    for (gx, gz) in [(0, 0), (8, 8), (16, 16), (24, 24), (-16, -8)] {
        jobs.push((center.0 * 16 + gx, 66, center.1 * 16 + gz, false));
    }
    println!(
        "[corpus] mca={} chunks={} jobs={} states={} corpus_hash={:016x}",
        mca.display(),
        loaded.len(),
        jobs.len(),
        states.len(),
        corpus_hash
    );

    for lc in &loaded {
        prime_into_registry(lc, dim);
    }

    // run every job 2 whole passes (fresh world state per pass: re-prime)
    let passes = 6usize;
    let mut all_samples: Vec<(u64, u64)> = Vec::new(); // (ns, rc)
    let mut diff_hashes: Vec<u64> = Vec::new();
    let mut out = vec![0i32; 1 << 18];
    for p in 0..passes {
        for lc in &loaded {
            prime_into_registry(lc, dim);
        }
        for (wi, &(wx, wy, wz, darken)) in jobs.iter().enumerate() {
            if darken {
                // simulate source removal: set the cell to air before the job
                get_registry().mirror_block_state(
                    ChunkKey::new(dim, wx >> 4, wz >> 4),
                    (wx & 15) as usize,
                    wy as usize,
                    (wz & 15) as usize,
                    0,
                );
            }
            let t = std::time::Instant::now();
            let rc = run_zero_stage_job(dim, wx, wy, wz, &table, &mut out, 1 << 22, 64, false);
            let ns = t.elapsed().as_nanos() as u64;
            // fail-closed rc<0 (deep cascade reaching unloaded chunks) is a
            // LEGAL deterministic outcome — recorded, digested, timed
            let rc_u = (rc as i64) as u64;
            all_samples.push((ns, rc_u));
            // diff hash over rc records (sorted-independent: hash records as-is
            // is order-sensitive; kernel order is deterministic per variant —
            // cross-variant equality REQUIRES identical order too, which FIFO
            // preservation guarantees)
            let mut h: u64 = 0x9E3779B97F4A7C15;
            for i in 0..rc.max(0) as usize {
                for k in 0..4 {
                    h = (h ^ (out[i * 4 + k] as u64)).wrapping_mul(0x100000001b3);
                }
            }
            if p == 0 {
                diff_hashes.push(h ^ rc_u);
            } else if wi < diff_hashes.len() {
                // intra-variant determinism across passes
                assert_eq!(
                    h ^ rc_u,
                    diff_hashes[wi],
                    "job {} non-deterministic within variant (pass {})",
                    wi,
                    p
                );
            }
        }
    }

    // stats + size split
    let mut by_size: Vec<(u64, u64)> = all_samples.clone();
    by_size.sort_unstable();
    let n = by_size.len();
    let pct = |q: usize| by_size[(n as f64 * q as f64 / 100.0) as usize].0;
    let mean = by_size.iter().map(|s| s.0).sum::<u64>() / n as u64;
    let total_rc: u64 = by_size.iter().map(|s| s.1).sum();
    println!("[corpus] jobs/run={} passes={} mean_ns={} p50_ns={} p95_ns={} p99_ns={} max_ns={} total_rc={}",
             jobs.len(), passes, mean, pct(50), pct(95), pct(99),
             by_size[n - 1].0, total_rc);
    let mut sizes: Vec<&str> = vec!["small", "medium", "large"];
    let buckets: [(u64, u64, usize); 3] = [(0, 200, 0), (200, 2000, 1), (2000, u64::MAX, 2)];
    for (lo, hi, bi) in buckets {
        let sel: Vec<(u64, u64)> = by_size
            .iter()
            .filter(|s| s.1 >= lo && s.1 < hi && s.1 < (1 << 30))
            .copied()
            .collect();
        if sel.is_empty() {
            println!("[corpus] size={} jobs=0", sizes[bi]);
            continue;
        }
        let m = sel.iter().map(|s| s.0).sum::<u64>() / sel.len() as u64;
        let mut sorted: Vec<u64> = sel.iter().map(|s| s.0).collect();
        sorted.sort_unstable();
        println!(
            "[corpus] size={} jobs={} mean_ns={} p95_ns={}",
            sizes[bi],
            sel.len(),
            m,
            sorted[(sorted.len() as f64 * 0.95) as usize]
        );
    }
    // canonical diff-hash digest for cross-variant equality (§16)
    let mut dh = 0u64;
    for (i, h) in diff_hashes.iter().enumerate() {
        dh = (dh ^ h).wrapping_add((i as u64).wrapping_mul(0x9E3779B97F4A7C15));
    }
    println!(
        "[corpus] DIFF_DIGEST={:016x} jobs={}",
        dh,
        diff_hashes.len()
    );
    let (rm, ad, nr) = rustcraft_ffi::zero_stage_job::frontier_counters();
    println!(
        "[corpus] kernel-shares removal_pops={} addition_pops={} neighbor_reads={} removal_reads~{} addition_self_reads~{}",
        rm, ad, nr, rm * 6, nr.saturating_sub(rm * 6)
    );
    let _ = &mut sizes;
}
