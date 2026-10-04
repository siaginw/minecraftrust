//! Goal §20: differential fuzz — Rust frontier vs a straight re-computation
//! oracle over randomized worlds. The oracle recomputes every cell from
//! scratch (full fixed-point from zero) rather than incrementally; exact
//! agreement across 100k+ mutation scenarios is the admission criterion for
//! live shadow (mirrors the H13 proof that the kernel matches the REAL Java
//! oracle World.func_175638 on finite grids).

use light_engine::{BlockLightFrontier, CellKey, LightWorld};

use std::cell::RefCell;
use std::collections::HashMap;

/// PRNG (xorshift) for reproducibility without external deps.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn key(x: i32, y: i32, z: i32) -> CellKey {
    CellKey {
        cx: x.div_euclid(16),
        cz: z.div_euclid(16),
        sy: y.div_euclid(16),
        x: x.rem_euclid(16) as u8,
        y: y.rem_euclid(16) as u8,
        z: z.rem_euclid(16) as u8,
    }
}

#[derive(Default, Clone)]
struct Grid {
    light: HashMap<(i32, i32, i32), u8>,
    opacity: HashMap<(i32, i32, i32), u8>,
    emission: HashMap<(i32, i32, i32), u8>,
}

impl Grid {
    fn get(&self, x: i32, y: i32, z: i32) -> u8 {
        *self.light.get(&(x, y, z)).unwrap_or(&0)
    }
    fn set(&mut self, x: i32, y: i32, z: i32, v: u8) {
        self.light.insert((x, y, z), v);
    }
}

struct GridWorld {
    grid: *mut Grid,
}

impl LightWorld for GridWorld {
    fn block_light(&self, key: CellKey) -> u8 {
        let g = unsafe { &*self.grid };
        g.get(
            key.cx * 16 + key.x as i32,
            key.world_y(),
            key.cz * 16 + key.z as i32,
        )
    }
    fn set_block_light(&self, key: CellKey, value: u8) {
        // scenario phases run single-threaded; production writes go through
        // CAS-backed NativeSection (inherently shared-mutable)
        let g = unsafe { &mut *self.grid };
        g.set(
            key.cx * 16 + key.x as i32,
            key.world_y(),
            key.cz * 16 + key.z as i32,
            value,
        );
    }
    fn opacity(&self, key: CellKey) -> u8 {
        let g = unsafe { &*self.grid };
        *g.opacity
            .get(&(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ))
            .unwrap_or(&0)
    }
    fn emission(&self, key: CellKey) -> u8 {
        let g = unsafe { &*self.grid };
        *g.emission
            .get(&(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ))
            .unwrap_or(&0)
    }
}

/// Oracle: solve the fixed point from zero over a bounded region around all
/// interesting cells (the standard independent recomputation).
fn oracle_solve(grid: &Grid, bounds: (i32, i32, i32, i32, i32, i32)) -> Grid {
    let (x0, x1, y0, y1, z0, z1) = bounds;
    let mut solved = grid.clone();
    solved.light.clear();
    // iterate to fixed point (BFS from every emission, then relax)
    let mut changed = true;
    let mut rounds = 0u64;
    while changed {
        changed = false;
        rounds += 1;
        if rounds > 10_000 {
            panic!("oracle did not converge");
        }
        for x in x0..=x1 {
            for y in y0..=y1 {
                for z in z0..=z1 {
                    let emission = *grid.emission.get(&(x, y, z)).unwrap_or(&0);
                    let opacity = *grid.opacity.get(&(x, y, z)).unwrap_or(&0);
                    let mut target = emission;
                    let attenuation = if opacity >= 15 && target > 0 {
                        1
                    } else {
                        opacity.max(1)
                    };
                    if attenuation < 15 && target < 15 {
                        for (dx, dy, dz) in [
                            (-1, 0, 0),
                            (1, 0, 0),
                            (0, -1, 0),
                            (0, 1, 0),
                            (0, 0, -1),
                            (0, 0, 1),
                        ] {
                            let l = solved.get(x + dx, y + dy, z + dz);
                            let v = l.saturating_sub(attenuation);
                            if v > target {
                                target = v;
                            }
                        }
                    }
                    let cur = solved.get(x, y, z);
                    if target != cur {
                        solved.set(x, y, z, target);
                        changed = true;
                    }
                }
            }
        }
    }
    solved
}

fn bounds_for(grid: &Grid) -> (i32, i32, i32, i32, i32, i32) {
    // bounds cover every non-trivial cell; Y is CLAMPED to the world
    // (0..) — cells below 0 do not exist and must stay dark
    let mut x0 = 0;
    let mut x1 = 0;
    let mut y0 = 0;
    let mut y1 = 0;
    let mut z0 = 0;
    let mut z1 = 0;
    for (x, y, z) in grid
        .emission
        .keys()
        .chain(grid.opacity.keys())
        .chain(grid.light.keys())
    {
        x0 = x0.min(*x);
        x1 = x1.max(*x);
        y0 = y0.min(*y);
        y1 = y1.max(*y);
        z0 = z0.min(*z);
        z1 = z1.max(*z);
    }
    (x0 - 1, x1 + 1, y0.max(0), y1 + 1, z0 - 1, z1 + 1)
}

/// One scenario: build a random world, apply a random mutation batch
/// through the frontier engine, compare the ENTIRE bounded grid against
/// the oracle's independent recomputation.
fn scenario(seed: u64) -> Result<(), String> {
    let mut rng = Rng(seed | 1);
    let mut grid = Grid::default();

    // random emitters and opacity
    let n_emit = 1 + rng.below(4);
    for _ in 0..n_emit {
        let (x, y, z) = (
            (rng.below(12) as i32) - 6,
            (rng.below(12) as i32),
            (rng.below(12) as i32) - 6,
        );
        grid.emission.insert((x, y, z), 8 + (rng.below(7) as u8));
        // sometimes the emitter sits inside a solid block
        if rng.below(4) == 0 {
            grid.opacity.insert((x, y, z), 15);
        }
    }
    let n_solid = rng.below(10);
    for _ in 0..n_solid {
        let (x, y, z) = (
            (rng.below(12) as i32) - 6,
            (rng.below(12) as i32),
            (rng.below(12) as i32) - 6,
        );
        grid.opacity.insert((x, y, z), 15);
    }

    // initial solve via the frontier from all emitters
    {
        let world = GridWorld {
            grid: &grid as *const Grid as *mut Grid,
        };
        let mut f = BlockLightFrontier::new();
        for (x, y, z) in grid.emission.keys().copied().collect::<Vec<_>>() {
            f.notify(key(x, y, z));
        }
        for (x, y, z) in grid.opacity.keys().copied().collect::<Vec<_>>() {
            f.notify(key(x, y, z));
        }
        f.propagate(&world);
    }

    // random mutation batch: remove/add emitters and solids
    let mut notifications: Vec<CellKey> = Vec::new();
    let n_mut = 1 + rng.below(5);
    for _ in 0..n_mut {
        let (x, y, z) = (
            (rng.below(12) as i32) - 6,
            (rng.below(12) as i32),
            (rng.below(12) as i32) - 6,
        );
        match rng.below(3) {
            0 => {
                // place a solid (maybe removing an emitter)
                grid.opacity.insert((x, y, z), 15);
                grid.emission.remove(&(x, y, z));
            }
            1 => {
                // break a block
                grid.opacity.remove(&(x, y, z));
            }
            _ => {
                // add/move a light source
                grid.emission.insert((x, y, z), 8 + (rng.below(7) as u8));
                grid.opacity.remove(&(x, y, z));
            }
        }
        notifications.push(key(x, y, z));
    }

    // apply through the frontier: ONE DRAIN PER MUTATION — vanilla applies
    // each block change through its own checkLightFor before the next block
    // changes; batching heterogeneous mutations into one drain is NOT a
    // vanilla behavior and breaks the monotonic tear-down assumption.
    {
        let world = GridWorld {
            grid: &grid as *const Grid as *mut Grid,
        };
        for k in notifications {
            let mut f = BlockLightFrontier::new();
            f.notify(k);
            for n in k.neighbors().into_iter().flatten() {
                f.notify(n);
            }
            f.propagate(&world);
        }
    }

    // oracle: independent recomputation over the bounds
    let bounds = bounds_for(&grid);
    let solved = oracle_solve(&grid, bounds);
    let (x0, x1, y0, y1, z0, z1) = bounds;
    let mut mismatches = 0;
    for x in x0..=x1 {
        for y in y0..=y1 {
            for z in z0..=z1 {
                let got = grid.get(x, y, z);
                let want = solved.get(x, y, z);
                if got != want {
                    mismatches += 1;
                    if mismatches <= 3 {
                        eprintln!(
                            "seed {seed}: mismatch at ({x},{y},{z}): frontier={got} oracle={want}"
                        );
                    }
                }
            }
        }
    }
    if mismatches > 0 {
        return Err(format!("seed {seed}: {mismatches} cell mismatches"));
    }
    Ok(())
}

#[test]
fn differential_fuzz_100k_scenarios() {
    // 100k scenarios across worker threads (each worker owns a disjoint
    // seed range; failures collected per worker)
    const SCENARIOS: u64 = 100_000;
    const WORKERS: u64 = 8;
    let per_worker = SCENARIOS / WORKERS;
    let mut handles = Vec::new();
    for w in 0..WORKERS {
        handles.push(std::thread::spawn(move || {
            let lo = w * per_worker + 1;
            let hi = if w == WORKERS - 1 { SCENARIOS } else { lo + per_worker - 1 };
            let mut failures: Vec<String> = Vec::new();
            for seed in lo..=hi {
                if let Err(e) = scenario(seed) {
                    failures.push(e);
                    if failures.len() >= 3 {
                        break;
                    }
                }
            }
            failures
        }));
    }
    let mut all_failures = Vec::new();
    for h in handles {
        all_failures.extend(h.join().unwrap());
    }
    for f in all_failures.iter().take(5) {
        eprintln!("{f}");
    }
    assert!(all_failures.is_empty(), "fuzz found divergences");
}

#[test]
fn differential_fuzz_smoke() {
    for seed in 1..=2000u64 {
        scenario(seed).unwrap_or_else(|e| panic!("{e}"));
    }
}
