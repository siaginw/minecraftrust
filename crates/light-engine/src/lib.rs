//! RUST_LIGHT_PROPAGATION_AUTHORITY: block-light changed-cell frontier.
//!
//! The kernel is the H13 experiment's fixed-point solver
//! (tools/lighting-collision-experiment/src/light.rs), proven bit-exact
//! against the real Java oracle (World.func_175638 checkLightFor /
//! func_180500 apply) on finite grids. This crate lifts it to live form:
//!
//! - WORLD-ADDRESSED: keys are (chunkX, y, chunkZ, packed xyz) via
//!   `CellKey`, not a bounded cube. No `side` limit.
//! - VANILLA-SEMANTIC: identical level rule — emission; attenuation =
//!   (opacity >= 15 && value > 0) ? 1 : max(opacity, 1); neighbor value =
//!   max(light[j] - attenuation); early exit at 14; BFS re-enqueue on
//!   improvement. Handles both removal (decrease frontier) and addition.
//! - OPACITY VIA CALLBACK: the engine holds no block-state opinion. The
//!   caller supplies `opacity(x,y,z) -> u8` — in production that reads
//!   NativeChunk state; in shadow it replays recorded vanilla opacity.
//! - ZERO SEMANTIC STATE: the engine mutates ONLY the light array the
//!   caller hands it (in production, the same CAS-backed NativeSection
//!   nibbles Java/Phosphor read). Java/Forge remains the semantic owner:
//!   Chunk callbacks/listeners stay in Java.
//!
//! Shadow mode: Java/Phosphor remains authoritative; this solver replays
//! the same mutation batch and the harness compares final grids cell-exact.

use std::collections::VecDeque;

/// One cell in world coordinates. Chunk-local packing matches Minecraft's
/// ExtendedBlockStorage order: index = ((y << 4) | z) << 4 | x with y in
/// 0..16, x/z in 0..16 (section-local), plus the section/chunk keys.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct CellKey {
    pub cx: i32,
    pub cz: i32,
    /// section Y within the world column (0 = y 0..16, 1 = 16..32, ...)
    pub sy: i32,
    pub x: u8,
    pub y: u8,
    pub z: u8,
}

impl CellKey {
    pub fn section_index(&self) -> usize {
        ((self.y as usize) << 8) | ((self.z as usize) << 4) | self.x as usize
    }

    pub fn world_y(&self) -> i32 {
        (self.sy * 16) + self.y as i32
    }

    pub fn neighbor(&self, axis: u8, dir: i8) -> Option<CellKey> {
        let mut k = *self;
        match axis {
            0 => {
                let nx = self.x as i32 + dir as i32;
                if (0..16).contains(&nx) {
                    k.x = nx as u8;
                } else {
                    // cross into the neighboring chunk
                    let (ncx, nx2) = if nx < 0 {
                        (self.cx - 1, nx + 16)
                    } else {
                        (self.cx + 1, nx - 16)
                    };
                    k.cx = ncx;
                    k.x = nx2 as u8;
                }
            }
            1 => {
                let ny = self.y as i32 + dir as i32;
                if (0..16).contains(&ny) {
                    k.y = ny as u8;
                } else {
                    let (nsy, ny2) = if ny < 0 {
                        (self.sy - 1, ny + 16)
                    } else {
                        (self.sy + 1, ny - 16)
                    };
                    k.sy = nsy;
                    k.y = ny2 as u8;
                }
            }
            2 => {
                let nz = self.z as i32 + dir as i32;
                if (0..16).contains(&nz) {
                    k.z = nz as u8;
                } else {
                    let (ncz, nz2) = if nz < 0 {
                        (self.cz - 1, nz + 16)
                    } else {
                        (self.cz + 1, nz - 16)
                    };
                    k.cz = ncz;
                    k.z = nz2 as u8;
                }
            }
            _ => return None,
        }
        Some(k)
    }

    pub fn neighbors(&self) -> [Option<CellKey>; 6] {
        [
            self.neighbor(0, -1),
            self.neighbor(0, 1),
            self.neighbor(1, -1),
            self.neighbor(1, 1),
            self.neighbor(2, -1),
            self.neighbor(2, 1),
        ]
    }
}

/// Trait the caller implements to supply world data WITHOUT the engine
/// holding semantic opinions. Production impl reads NativeChunk state;
/// the shadow harness replays recorded vanilla data.
pub trait LightWorld {
    fn block_light(&self, key: CellKey) -> u8;
    fn set_block_light(&self, key: CellKey, value: u8);
    /// 0..15; >=15 = fully opaque. Caller derives from NativeChunk state.
    fn opacity(&self, key: CellKey) -> u8;
    /// Emitted light at this cell (torch = 14 etc.). Usually 0.
    fn emission(&self, key: CellKey) -> u8 {
        let _ = key;
        0
    }
    /// World height limit (worlds are 0..height).
    fn height(&self) -> i32 {
        256
    }
}

#[derive(Default, Debug, Clone)]
pub struct FrontierStats {
    pub neighbor_reads: u64,
    pub updates: u64,
    pub queue_peak: usize,
    pub removal_passes: u64,
    pub addition_passes: u64,
}

/// The frontier engine. Holds only the pending queue — never light data.
#[derive(Default)]
pub struct BlockLightFrontier {
    queue: VecDeque<CellKey>,
    stats: FrontierStats,
}

/// Neighbor iterator filtered to the world's Y range (vanilla: cells below
/// 0 and above the build height do not exist). Every neighbor scan in
/// `propagate` goes through this so Y bounds live in ONE place and come
/// from the world, not from hardcoded section arithmetic.
fn world_neighbors<'a>(
    world: &'a dyn LightWorld,
    key: CellKey,
) -> impl Iterator<Item = CellKey> + 'a {
    key.neighbors()
        .into_iter()
        .flatten()
        .filter(move |n| n.world_y() >= 0 && n.world_y() < world.height())
}

impl BlockLightFrontier {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn stats(&self) -> &FrontierStats {
        &self.stats
    }

    pub fn pending(&self) -> usize {
        self.queue.len()
    }

    /// Notify a mutation at `key` (place/break/light-source change at or
    /// adjacent to it). Schedules both the removal frontier (old light
    /// value must be re-verified downward) and the addition frontier.
    pub fn notify(&mut self, key: CellKey) {
        self.queue.push_back(key);
    }

    /// Drain the frontier to the fixed point. Returns the number of cells
    /// whose stored value the engine actually changed. `world` reads and
    /// writes the authoritative light arrays (NativeSection nibbles in
    /// production).
    ///
    /// TWO-QUEUE VANILLA ORDERING (matches checkLightFor's removal-then-
    /// addition structure): every notified cell first goes through the
    /// REMOVAL queue to completion (values that are now too high are torn
    /// down and their dependents scheduled), THEN the ADDITION queue runs
    /// to completion (values that are now too low are raised). A mixed
    /// queue lets a still-too-high neighbor justify a premature raise that
    /// never gets re-checked after the tear-down — the exact divergence the
    /// differential fuzz caught.
    pub fn propagate(&mut self, world: &dyn LightWorld) -> u64 {
        let mut changed = 0u64;
        let mut removal: VecDeque<CellKey> = std::mem::take(&mut self.queue);
        let mut addition: VecDeque<CellKey> = VecDeque::new();

        // ---- removal pass ----
        let mut guard = 0u64;
        while let Some(key) = removal.pop_front() {
            guard += 1;
            if guard > 1 << 26 {
                break;
            }
            let current = world.block_light(key);
            let opacity = world.opacity(key);
            let emission = world.emission(key);
            let mut justified = emission;
            let attenuation = if opacity >= 15 && justified > 0 {
                1
            } else {
                opacity.max(1)
            };
            if attenuation < 15 {
                for n in world_neighbors(world, key) {
                    self.stats.neighbor_reads += 1;
                    let v = world.block_light(n).saturating_sub(attenuation);
                    if v > justified {
                        justified = v;
                    }
                }
            }
            #[cfg(feature = "debug-prop")]
            eprintln!("removal-visit {:?}", key);
            if justified < current {
                // still too high: tear down, schedule dependents. Neighbors
                // go into BOTH queues — removal (if still lit, they may need
                // tearing too) and addition (unconditionally: a neighbor may
                // be re-raisable later via a detour path once the tear-down
                // settles; without this the addition pass never revisits it
                // and the differential fuzz catches under-lighting).
                world.set_block_light(key, justified);
                changed += 1;
                self.stats.updates += 1;
                self.stats.removal_passes += 1;
                for n in world_neighbors(world, key) {
                    if world.block_light(n) > 0 {
                        removal.push_back(n);
                    }
                    addition.push_back(n);
                }
            }
            // any cell whose value is LOWER than justified goes to addition
            // (this is how pure-addition notifications — a new emitter in
            // open air — enter the addition queue)
            let current = world.block_light(key);
            if current < justified {
                addition.push_back(key);
            }
        }

        // ---- addition pass ----
        let mut guard = 0u64;
        while let Some(key) = addition.pop_front() {
            guard += 1;
            if guard > 1 << 26 {
                break;
            }
            let current = world.block_light(key);
            let opacity = world.opacity(key);
            let emission = world.emission(key);
            let mut target = emission;
            let attenuation = if opacity >= 15 && target > 0 {
                1
            } else {
                opacity.max(1)
            };
            if attenuation < 15 && target < 15 {
                for n in world_neighbors(world, key) {
                    self.stats.neighbor_reads += 1;
                    let v = world.block_light(n).saturating_sub(attenuation);
                    if v > target {
                        target = v;
                    }
                }
                if target > 15 {
                    target = 15;
                }
            }
            if target > current {
                world.set_block_light(key, target);
                changed += 1;
                self.stats.updates += 1;
                self.stats.addition_passes += 1;
                for n in world_neighbors(world, key) {
                    if world.block_light(n) < 15 {
                        addition.push_back(n);
                    }
                }
            }
        }
        changed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::HashMap;

    /// Test world with interior mutability on the light map (mirrors the
    /// production CAS-backed NativeSection: set through a shared reference).
    struct TestWorld {
        light: RefCell<HashMap<CellKey, u8>>,
        opacity: HashMap<CellKey, u8>,
        emission: HashMap<CellKey, u8>,
    }

    impl LightWorld for TestWorld {
        fn block_light(&self, key: CellKey) -> u8 {
            *self.light.borrow().get(&key).unwrap_or(&0)
        }
        fn set_block_light(&self, key: CellKey, value: u8) {
            self.light.borrow_mut().insert(key, value);
        }
        fn opacity(&self, key: CellKey) -> u8 {
            *self.opacity.get(&key).unwrap_or(&0)
        }
        fn emission(&self, key: CellKey) -> u8 {
            *self.emission.get(&key).unwrap_or(&0)
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

    #[test]
    fn point_source_floods_expected_radius() {
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        w.emission.insert(key(0, 0, 0), 14);
        let mut f = BlockLightFrontier::new();
        f.notify(key(0, 0, 0));
        let changed = f.propagate(&w);
        assert_eq!(w.block_light(key(0, 0, 0)), 14);
        assert_eq!(w.block_light(key(5, 0, 0)), 9);
        assert_eq!(w.block_light(key(14, 0, 0)), 0);
        assert!(changed > 0);
    }

    #[test]
    fn opaque_wall_blocks_propagation() {
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        w.emission.insert(key(0, 0, 0), 14);
        // full opaque slab at x=3 (all y/z in range) — light cannot leak
        for y in -16..=16 {
            for z in -16..=16 {
                w.opacity.insert(key(3, y, z), 15);
            }
        }
        let mut f = BlockLightFrontier::new();
        f.notify(key(0, 0, 0));
        f.propagate(&w);
        assert_eq!(w.block_light(key(2, 0, 0)), 12);
        assert_eq!(w.block_light(key(4, 0, 0)), 0);
    }

    #[test]
    fn removal_deflates_correctly() {
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        w.emission.insert(key(0, 0, 0), 14);
        let mut f = BlockLightFrontier::new();
        f.notify(key(0, 0, 0));
        f.propagate(&w);
        // source removed / solid placed
        w.emission.remove(&key(0, 0, 0));
        w.opacity.insert(key(0, 0, 0), 15);
        let mut f2 = BlockLightFrontier::new();
        f2.notify(key(0, 0, 0));
        f2.propagate(&w);
        assert_eq!(w.block_light(key(0, 0, 0)), 0);
        assert_eq!(w.block_light(key(7, 0, 0)), 0);
    }

    #[test]
    fn chunk_boundary_crossing_stays_consistent() {
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        let src = key(15, 0, 0);
        w.emission.insert(src, 14);
        let mut f = BlockLightFrontier::new();
        f.notify(src);
        f.propagate(&w);
        assert_eq!(w.block_light(key(15, 0, 0)), 14);
        assert_eq!(w.block_light(key(16, 0, 0)), 13);
        assert_eq!(w.block_light(key(20, 0, 0)), 9);
    }

    #[test]
    fn section_crossing_upward() {
        // emitter at y=15 (top of section 0): light must cross into section 1
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        w.emission.insert(key(0, 15, 0), 14);
        let mut f = BlockLightFrontier::new();
        f.notify(key(0, 15, 0));
        f.propagate(&w);
        assert_eq!(w.block_light(key(0, 15, 0)), 14);
        assert_eq!(w.block_light(key(0, 16, 0)), 13, "must cross sy=0 -> sy=1");
        assert_eq!(w.block_light(key(0, 17, 0)), 12);
        // below-world cells stay dark (vanilla: y<0 does not exist)
        assert_eq!(w.block_light(key(0, -1, 0)), 0);
    }

    #[test]
    fn torch_beside_full_opaque_matches_vanilla_rule() {
        let mut w = TestWorld {
            light: RefCell::new(HashMap::new()),
            opacity: HashMap::new(),
            emission: HashMap::new(),
        };
        let src = key(0, 0, 0);
        w.emission.insert(src, 14);
        w.opacity.insert(src, 15);
        let mut f = BlockLightFrontier::new();
        f.notify(src);
        f.propagate(&w);
        assert_eq!(w.block_light(src), 14);
        assert_eq!(w.block_light(key(1, 0, 0)), 13);
    }
}
