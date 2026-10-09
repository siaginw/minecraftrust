//! §48 state-representation bake-off (audit prototype ONLY — production
//! stays direct-u32): current [u32; 4096] cells vs a section-local u16
//! palette-index + u32 global palette prototype, on realistic mixed
//! workloads (vanilla-low ids + Revelation-high ids like 76,916).
//! Measures read / scan / mutate / light-traversal-shape / packet-extract
//! shape / memory. Printed as evidence for RUST_ENGINE_OWNERSHIP_
//! PERFORMANCE_AUDIT; asserts nothing about winners.

use native_chunk::section::NativeSection;
use std::time::Instant;

/// Prototype B: section-local u16 palette index + u32 global palette.
/// Cells always hold palette indexes; air is palette slot 0.
struct PaletteSection {
    cells: Box<[u16; 4096]>,
    palette: Vec<u32>,
    // reverse lookup: for the tiny palettes real sections have, a linear
    // scan IS the credible simple design (a map would be slower at this n)
    non_air: u16,
}

impl PaletteSection {
    fn new() -> Self {
        Self {
            cells: Box::new([0u16; 4096]),
            palette: vec![0u32],
            non_air: 0,
        }
    }
    #[inline(always)]
    fn get(&self, idx: usize) -> u32 {
        self.palette[self.cells[idx] as usize]
    }
    fn set(&mut self, idx: usize, global: u32) -> bool {
        let mut slot = None;
        for (i, &g) in self.palette.iter().enumerate() {
            if g == global {
                slot = Some(i as u16);
                break;
            }
        }
        let slot = match slot {
            Some(s) => s,
            None => {
                if self.palette.len() >= 256 {
                    return false; // overflow: out of local-palette domain
                }
                self.palette.push(global);
                (self.palette.len() - 1) as u16
            }
        };
        let old = self.cells[idx];
        if old == slot {
            return false;
        }
        self.cells[idx] = slot;
        if old == 0 && slot != 0 {
            self.non_air += 1;
        } else if old != 0 && slot == 0 {
            self.non_air -= 1;
        }
        true
    }
    fn footprint(&self) -> usize {
        self.cells.len() * 2 + self.palette.len() * 4
    }
}

fn realistic_ids() -> Vec<u32> {
    // mixed: 60% stone/dirt/air variants (low), 25% mid modded,
    // 15% Revelation-high (incl. the known 76,916 / 108,517)
    let mut v: Vec<u32> = vec![0, 1, 16, 2, 3, 144];
    for m in 1..40 {
        v.push(4_000 + m * 37);
    }
    for h in 0..24 {
        v.push(65_600 + h * 521);
    }
    v.push(76_916);
    v.push(108_517);
    v
}

fn lcg(seed: &mut u64) -> u64 {
    *seed = seed
        .wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407);
    *seed >> 33
}

fn bench<F: FnMut()>(name: &str, iters: usize, mut f: F) -> (f64, f64, f64) {
    // warmup
    for _ in 0..iters / 10 + 1 {
        f();
    }
    let mut samples = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t = Instant::now();
        f();
        samples.push(t.elapsed().as_nanos() as f64);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let p50 = samples[samples.len() / 2] / 1000.0;
    let p95 = samples[(samples.len() as f64 * 0.95) as usize] / 1000.0;
    let mean = samples.iter().sum::<f64>() / samples.len() as f64 / 1000.0;
    println!(
        "[bakeoff] {:<34} p50={:>9.2}us p95={:>9.2}us mean={:>9.2}us",
        name, p50, p95, mean
    );
    (p50, p95, mean)
}

#[test]
fn state_representation_bakeoff() {
    let ids = realistic_ids();
    let mut seed = 0xC0FFEEu64;

    // fill both representations with identical random contents
    let mut direct = NativeSection::new(4);
    let mut pal = PaletteSection::new();
    let mut contents = [0u32; 4096];
    for i in 0..4096usize {
        let id = ids[(lcg(&mut seed) % ids.len() as u64) as usize];
        contents[i] = id;
        direct.set_block_by_index(i, id);
        pal.set(i, id);
    }
    // sanity: same content
    for i in 0..4096 {
        assert_eq!(direct.get_block_by_index(i), pal.get(i));
    }
    println!(
        "[bakeoff] palette size after fill: {} entries",
        pal.palette.len()
    );
    println!(
        "[bakeoff] memory: direct={}B palette={}B (cells {}B + palette {}B)",
        4096 * 4,
        pal.footprint(),
        4096 * 2,
        pal.palette.len() * 4
    );

    let order: Vec<usize> = (0..4096)
        .map(|_| (lcg(&mut seed) % 4096) as usize)
        .collect();
    let mut acc = 0u64;

    // 1. random read (4096 reads)
    let o = order.clone();
    bench("direct-u32 random read x4096", 2000, || {
        let mut a = 0u64;
        for &i in &o {
            a += direct.get_block_by_index(i) as u64;
        }
        acc = acc.wrapping_add(a);
    });
    let o2 = order.clone();
    bench("palette   random read x4096", 2000, || {
        let mut a = 0u64;
        for &i in &o2 {
            a += pal.get(i) as u64;
        }
        acc = acc.wrapping_add(a);
    });

    // 2. sequential scan
    bench("direct-u32 sequential scan", 2000, || {
        let mut a = 0u64;
        for i in 0..4096 {
            a += direct.get_block_by_index(i) as u64;
        }
        acc = acc.wrapping_add(a);
    });
    bench("palette   sequential scan", 2000, || {
        let mut a = 0u64;
        let p = &pal.palette;
        let c = &pal.cells;
        for i in 0..4096 {
            a += p[c[i] as usize] as u64;
        }
        acc = acc.wrapping_add(a);
    });

    // 3. mutation burst (256 random in-domain writes — no palette growth)
    let mut widx = 0usize;
    bench("direct-u32 mutate x256", 2000, || {
        for k in 0..256 {
            let i = order[(widx + k) % 4096];
            direct.set_block_by_index(i, ids[(i + k) % ids.len()]);
        }
        widx += 7;
    });
    let mut widx2 = 0usize;
    bench("palette   mutate x256", 2000, || {
        for k in 0..256 {
            let i = order[(widx2 + k) % 4096];
            pal.set(i, ids[(i + k) % ids.len()]);
        }
        widx2 += 7;
    });

    // 4. light-traversal shape: for each cell, read self + 6 neighbors
    // (within-section approximation of the kernel's access pattern)
    bench("direct-u32 light-shape x4096", 300, || {
        let mut a = 0u64;
        for z in 1..15u32 {
            for y in 1..15u32 {
                for x in 1..15u32 {
                    let idx = ((y << 8) | (z << 4) | x) as usize;
                    a += direct.get_block_by_index(idx) as u64;
                    a += direct.get_block_by_index(idx + 1) as u64;
                    a += direct.get_block_by_index(idx - 1) as u64;
                    a += direct.get_block_by_index(idx + 16) as u64;
                    a += direct.get_block_by_index(idx - 16) as u64;
                    a += direct.get_block_by_index(idx + 256) as u64;
                    a += direct.get_block_by_index(idx - 256) as u64;
                }
            }
        }
        acc = acc.wrapping_add(a);
    });
    bench("palette   light-shape x4096", 300, || {
        let p = &pal.palette;
        let c = &pal.cells;
        let mut a = 0u64;
        for z in 1..15u32 {
            for y in 1..15u32 {
                for x in 1..15u32 {
                    let idx = ((y << 8) | (z << 4) | x) as usize;
                    a += p[c[idx] as usize] as u64;
                    a += p[c[idx + 1] as usize] as u64;
                    a += p[c[idx - 1] as usize] as u64;
                    a += p[c[idx + 16] as usize] as u64;
                    a += p[c[idx - 16] as usize] as u64;
                    a += p[c[idx + 256] as usize] as u64;
                    a += p[c[idx - 256] as usize] as u64;
                }
            }
        }
        acc = acc.wrapping_add(a);
    });

    // 5. packet-extract shape: count unique states (palette build core).
    // For the direct representation this is a scan+dedup (what
    // build_local_palette does); the palette representation already HAS it.
    bench("direct-u32 unique-count (palette build core)", 500, || {
        let mut uniq = [0u32; 256];
        let mut n = 0usize;
        'outer: for i in 0..4096 {
            let id = direct.get_block_by_index(i);
            for u in uniq.iter().take(n) {
                if *u == id {
                    continue 'outer;
                }
            }
            uniq[n] = id;
            n += 1;
        }
        acc = acc.wrapping_add(n as u64);
    });
    bench("palette   unique-count (O(1))", 5000, || {
        acc = acc.wrapping_add(pal.palette.len() as u64);
    });

    println!("[bakeoff] sink={}", acc);
}
