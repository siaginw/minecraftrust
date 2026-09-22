//! M3.0 prototype: native spawn-query structure index.
//!
//! Replicates the EXACT vanilla 1.12.2 semantics of
//! `MapGenStructure.func_175797_c(BlockPos)` (verified by disassembly):
//!
//! ```text
//! for start in structure_map.values() {          // fastutil iteration order
//!     if !start.is_valid() { continue; }
//!     if !start.bounding_box.contains(pos) { continue; }
//!     for component in start.components() {
//!         if component.bounding_box.contains(pos) { return start; }  // first match
//!     }
//! }
//! return none
//! ```
//!
//! The index assigns each start a RANK equal to its position in the map's
//! iteration order (assigned by the caller at load/build time) and pre-filters
//! by start bounding boxes via the shared `spatial_index::LongKeyBuckets`
//! region grid. Queries return the winning rank applying the full predicate
//! (valid + start-box + component-box containment) — identical results to the
//! linear scan, including first-match ordering.
//!
//! Mutation model (mirrors the verified lifecycle): insert (generation
//! append), replace (same rank), update_box (bounding-box recalculation),
//! tombstone (none in vanilla; provided for completeness). No removal
//! reorder: ranks are stable identifiers.

use spatial_index::{pack_key, LongKeyBuckets};

/// Region cell size in blocks (structures are chunk-scale; 512 blocks = 32 chunks).
const REGION_SHIFT: i32 = 9;
const REGION_SIZE: i32 = 1 << REGION_SHIFT;

#[derive(Debug, Clone)]
pub struct StructureEntry {
    pub rank: u32,
    pub valid: bool,
    pub min_x: i32,
    pub min_y: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_y: i32,
    pub max_z: i32,
    /// Flattened component boxes: 6 ints each.
    pub components: Vec<i32>,
}

impl StructureEntry {
    fn box_contains(&self, x: i32, y: i32, z: i32) -> bool {
        x >= self.min_x && x <= self.max_x && y >= self.min_y && y <= self.max_y && z >= self.min_z && z <= self.max_z
    }

    /// Exact vanilla predicate after the start-box prefilter.
    fn component_contains(&self, x: i32, y: i32, z: i32) -> bool {
        let mut i = 0;
        while i + 5 < self.components.len() {
            if x >= self.components[i]
                && x <= self.components[i + 1]
                && y >= self.components[i + 2]
                && y <= self.components[i + 3]
                && z >= self.components[i + 4]
                && z <= self.components[i + 5]
            {
                return true;
            }
            i += 6;
        }
        false
    }

    fn region_range(&self) -> Vec<i64> {
        let rx0 = self.min_x >> REGION_SHIFT;
        let rx1 = self.max_x >> REGION_SHIFT;
        let rz0 = self.min_z >> REGION_SHIFT;
        let rz1 = self.max_z >> REGION_SHIFT;
        let mut keys = Vec::with_capacity(((rx1 - rx0 + 1) * (rz1 - rz0 + 1)) as usize);
        for rx in rx0..=rx1 {
            for rz in rz0..=rz1 {
                keys.push(pack_key(rx, rz));
            }
        }
        keys
    }
}

/// Observability counters for effectiveness measurement.
#[derive(Debug, Default, Clone, Copy)]
pub struct IndexStats {
    pub queries: u64,
    pub candidates_examined: u64,
    pub hits: u64,
}

#[derive(Debug, Default)]
pub struct SpawnIndex {
    /// rank -> entry; tombstoned ranks keep a `valid=false, empty` entry.
    entries: Vec<StructureEntry>,
    /// rank -> (generation, entry-is-live)
    live: Vec<bool>,
    /// region key -> ranks in ASCENDING rank order (first-match parity).
    regions: LongKeyBuckets,
    pub stats: IndexStats,
}

impl SpawnIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Insert an entry with the given rank. Reinserting a live rank is a
    /// replace (mirrors a put() over the same map key).
    pub fn insert(
        &mut self,
        rank: u32,
        valid: bool,
        min_x: i32,
        min_y: i32,
        min_z: i32,
        max_x: i32,
        max_y: i32,
        max_z: i32,
        components: &[i32],
    ) {
        let entry = StructureEntry {
            rank,
            valid,
            min_x,
            min_y,
            min_z,
            max_x,
            max_y,
            max_z,
            components: components.to_vec(),
        };
        self.set_entry(rank, entry);
    }

    /// Bounding-box recalculation event (vanilla func_175787_b class).
    pub fn update_box(
        &mut self,
        rank: u32,
        min_x: i32,
        min_y: i32,
        min_z: i32,
        max_x: i32,
        max_y: i32,
        max_z: i32,
    ) {
        if let Some(e) = self.entries.get_mut(rank as usize) {
            if !self.live.get(rank as usize).copied().unwrap_or(false) {
                return;
            }
            let old_keys = e.region_range();
            e.min_x = min_x;
            e.min_y = min_y;
            e.min_z = min_z;
            e.max_x = max_x;
            e.max_y = max_y;
            e.max_z = max_z;
            let new_keys = e.region_range();
            for k in old_keys {
                self.regions.remove(k, rank);
            }
            for k in new_keys {
                self.regions.insert(k, rank);
            }
        }
    }

    /// Tombstone (not present in vanilla; completeness only).
    pub fn remove(&mut self, rank: u32) {
        if let Some(i) = self.live.get_mut(rank as usize) {
            if *i {
                *i = false;
                if let Some(e) = self.entries.get_mut(rank as usize) {
                    let keys = e.region_range();
                    for k in keys {
                        self.regions.remove(k, rank);
                    }
                    e.components.clear();
                }
            }
        }
    }

    /// Exact query: winning rank or -1. First-match-in-rank-order parity.
    pub fn query(&mut self, x: i32, y: i32, z: i32) -> i32 {
        let rk = x >> REGION_SHIFT;
        let rz = z >> REGION_SHIFT;
        let mut bucket: Vec<u32> = self.regions.get(pack_key(rk, rz)).to_vec();
        // ascending rank order == vanilla map iteration order; buckets are
        // small so this sort is cheap, and it keeps update_box re-adds exact.
        bucket.sort_unstable();
        self.stats.queries += 1;
        let mut examined: u64 = 0;
        for &rank in &bucket {
            examined += 1;
            if let (Some(e), Some(true)) = (
                self.entries.get(rank as usize),
                self.live.get(rank as usize).copied(),
            ) {
                if e.valid && e.box_contains(x, y, z) && e.component_contains(x, y, z) {
                    self.stats.candidates_examined += examined;
                    self.stats.hits += 1;
                    return rank as i32;
                }
            }
        }
        self.stats.candidates_examined += examined;
        -1
    }

    pub fn len(&self) -> usize {
        self.live.iter().filter(|&&l| l).count()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn region_count(&self) -> usize {
        self.regions.bucket_count()
    }

    pub fn approx_memory_bytes(&self) -> usize {
        let mut b = self.regions.approx_memory_bytes();
        b += self.entries.capacity() * std::mem::size_of::<StructureEntry>();
        for e in &self.entries {
            b += e.components.capacity() * std::mem::size_of::<i32>();
        }
        b += self.live.capacity() * std::mem::size_of::<bool>();
        b
    }

    fn set_entry(&mut self, rank: u32, entry: StructureEntry) {
        let idx = rank as usize;
        if self.entries.len() <= idx {
            self.entries.resize(idx + 1, StructureEntry {
                rank: 0,
                valid: false,
                min_x: 0,
                min_y: 0,
                min_z: 0,
                max_x: 0,
                max_y: 0,
                max_z: 0,
                components: Vec::new(),
            });
            self.live.resize(idx + 1, false);
        }
        let old_keys = if self.live.get(idx).copied().unwrap_or(false) {
            self.entries[idx].region_range()
        } else {
            Vec::new()
        };
        for k in old_keys {
            self.regions.remove(k, rank);
        }
        let new_keys = entry.region_range();
        for k in new_keys {
            self.regions.insert(k, rank);
        }
        self.entries[idx] = entry;
        self.live[idx] = true;
    }


}

#[cfg(test)]
mod tests {
    use super::*;

    fn mk(rank: u32, x0: i32, z0: i32, w: i32, comps: &[(i32, i32, i32, i32, i32, i32)]) -> SpawnIndex {
        let mut ix = SpawnIndex::new();
        let mut flat = Vec::new();
        for c in comps {
            flat.extend_from_slice(&[c.0, c.1, c.2, c.3, c.4, c.5]);
        }
        ix.insert(rank, true, x0, 0, z0, x0 + w, 255, z0 + w, &flat);
        ix
    }

    #[test]
    fn empty_map_query_misses() {
        let mut ix = SpawnIndex::new();
        assert_eq!(ix.query(10, 64, 10), -1);
        assert_eq!(ix.stats.queries, 1);
    }

    #[test]
    fn exact_hit_component_and_first_match() {
        // two overlapping starts; rank 1 must win (vanilla map order)
        let mut ix = SpawnIndex::new();
        ix.insert(1, true, 0, 0, 0, 100, 255, 100, &[10, 50, 0, 100, 10, 50]);
        ix.insert(2, true, 0, 0, 0, 100, 255, 100, &[5, 60, 0, 100, 5, 60]);
        assert_eq!(ix.query(20, 64, 20), 1); // both match, first rank wins
        assert_eq!(ix.query(55, 64, 55), 2); // only rank 2's component holds
        assert_eq!(ix.query(90, 64, 90), -1); // start box holds, no component
    }

    #[test]
    fn invalid_start_never_matches() {
        let mut ix = SpawnIndex::new();
        ix.insert(0, false, 0, 0, 0, 100, 255, 100, &[0, 100, 0, 255, 0, 100]);
        assert_eq!(ix.query(50, 64, 50), -1);
    }

    #[test]
    fn negative_and_huge_coordinates() {
        let mut ix = SpawnIndex::new();
        ix.insert(0, true, -100_000, 0, -100_000, -99_000, 255, -99_000, &[-100_000, -99_000, 0, 255, -100_000, -99_000]);
        assert_eq!(ix.query(-99_500, 64, -99_500), 0);
        ix.insert(1, true, 1_000_000, 0, 1_000_000, 1_001_000, 255, 1_001_000, &[1_000_000, 1_001_000, 0, 255, 1_000_000, 1_001_000]);
        assert_eq!(ix.query(1_000_500, 64, 1_000_500), 1);
        assert_eq!(ix.query(0, 64, 0), -1);
    }

    #[test]
    fn update_box_moves_membership() {
        let mut ix = mk(0, 0, 0, 50, &[(0, 50, 0, 255, 0, 50)]);
        assert_eq!(ix.query(25, 64, 25), 0);
        ix.update_box(0, 5000, 0, 5000, 5050, 255, 5050);
        assert_eq!(ix.query(25, 64, 25), -1); // old region no longer hits
        assert_eq!(ix.query(5025, 64, 5025), -1); // box moved but component didn't
    }

    #[test]
    fn tombstone_removes_visibility() {
        let mut ix = mk(0, 0, 0, 50, &[(0, 50, 0, 255, 0, 50)]);
        assert_eq!(ix.query(25, 64, 25), 0);
        ix.remove(0);
        assert_eq!(ix.query(25, 64, 25), -1);
        assert!(ix.is_empty());
    }

    #[test]
    fn parity_against_linear_reference() {
        // randomized differential: linear scan vs index over many ops/queries
        let mut rng: u64 = 0x1234_5678_9abc_def0;
        let mut next = move || {
            rng ^= rng << 13;
            rng ^= rng >> 7;
            rng ^= rng << 17;
            rng
        };
        let mut ix = SpawnIndex::new();
        let mut starts: Vec<StructureEntry> = Vec::new();
        for rank in 0..300u32 {
            let r = next();
            let x0 = ((r % 4000) as i32) - 2000;
            let z0 = (((r >> 20) % 4000) as i32) - 2000;
            let w = 8 + (r % 64) as i32;
            let comps = vec![x0, x0 + w, 0, 255, z0, z0 + w];
            ix.insert(rank, true, x0, 0, z0, x0 + w, 255, z0 + w, &comps);
            starts.push(StructureEntry {
                rank,
                valid: true,
                min_x: x0,
                min_y: 0,
                min_z: z0,
                max_x: x0 + w,
                max_y: 255,
                max_z: z0 + w,
                components: comps,
            });
        }
        for q in 0..20000u64 {
            let x = ((next() % 8000) as i32) - 4000;
            let y = ((next() % 256) as i32) - 0;
            let z = ((next() % 8000) as i32) - 4000;
            let _ = q;
            // linear reference (first match in rank order)
            let mut lin = -1i32;
            for e in &starts {
                if e.box_contains(x, y, z) && e.component_contains(x, y, z) {
                    lin = e.rank as i32;
                    break;
                }
            }
            assert_eq!(ix.query(x, y, z), lin, "query {x},{y},{z}");
        }
    }
}
