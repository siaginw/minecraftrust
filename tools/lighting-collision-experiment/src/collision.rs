//! Bounded immutable geometry. Independent scalar math; actual runtime is oracle.
use std::collections::{BTreeMap, BTreeSet};
pub const MAX_SHAPES: usize = 4096;
pub const MAX_QUERIES: usize = 128;
const COORDINATE_LIMIT: f64 = 30_000_010.0;

#[derive(Clone, Copy, Debug)]
pub struct Aabb(pub [f64; 6]);
impl Aabb {
    pub fn parse(v: &[f64]) -> Option<Self> {
        if v.len() != 6
            || v.iter()
                .any(|n| !n.is_finite() || n.abs() > COORDINATE_LIMIT)
        {
            return None;
        }
        // Inputs are captured from constructed Java boxes. Do not normalize
        // signed zero or silently repair malformed/unknown capture data.
        if (0..3).any(|axis| v[axis] > v[axis + 3]) {
            return None;
        }
        Some(Self(v.try_into().ok()?))
    }
    pub fn intersects(self, other: Self) -> bool {
        (0..3).all(|a| self.0[a] < other.0[a + 3] && self.0[a + 3] > other.0[a])
    }
    pub fn clip(self, moving: Self, axis: usize, mut distance: f64) -> f64 {
        if (0..3)
            .filter(|a| *a != axis)
            .any(|a| moving.0[a + 3] <= self.0[a] || moving.0[a] >= self.0[a + 3])
        {
            return distance;
        }
        if distance > 0.0 && moving.0[axis + 3] <= self.0[axis] {
            let gap = self.0[axis] - moving.0[axis + 3];
            if gap < distance {
                distance = gap;
            }
        } else if distance < 0.0 && moving.0[axis] >= self.0[axis + 3] {
            let gap = self.0[axis + 3] - moving.0[axis];
            if gap > distance {
                distance = gap;
            }
        }
        distance
    }
}
#[derive(Default, Debug)]
pub struct Stats {
    pub exact_tests: u64,
    pub bucket_visits: u64,
    pub index_entries: usize,
}
type Cell = (i32, i32, i32);
fn cell(v: f64) -> i32 {
    (v / 4.0).floor() as i32
}
fn cells(b: Aabb) -> Option<Vec<Cell>> {
    let low = [cell(b.0[0]), cell(b.0[1]), cell(b.0[2])];
    let high = [cell(b.0[3]), cell(b.0[4]), cell(b.0[5])];
    let count = (0..3).try_fold(1usize, |n, a| {
        n.checked_mul((high[a] - low[a] + 1) as usize)
    })?;
    if count > 4096 {
        return None;
    }
    let mut result = Vec::with_capacity(count);
    for x in low[0]..=high[0] {
        for z in low[2]..=high[2] {
            for y in low[1]..=high[1] {
                result.push((x, y, z));
            }
        }
    }
    Some(result)
}
pub fn run(shapes: &[f64], queries: &[f64], indexed: bool) -> Option<(Vec<i64>, Stats)> {
    if !shapes.len().is_multiple_of(6)
        || !queries.len().is_multiple_of(9)
        || shapes.len() / 6 > MAX_SHAPES
        || queries.len() / 9 > MAX_QUERIES
    {
        return None;
    }
    let shapes: Vec<_> = shapes
        .as_chunks::<6>()
        .0
        .iter()
        .map(|v| Aabb::parse(v))
        .collect::<Option<_>>()?;
    let mut checked = Vec::with_capacity(queries.len() / 9);
    for q in queries.as_chunks::<9>().0 {
        let b = Aabb::parse(&q[..6])?;
        if q[6..].iter().any(|v| !v.is_finite() || v.abs() > 64.0) {
            return None;
        }
        checked.push((b, [q[6], q[7], q[8]]));
    }
    let mut stats = Stats::default();
    let mut index: BTreeMap<Cell, Vec<usize>> = BTreeMap::new();
    let mut oversized = Vec::new();
    if indexed {
        for (i, b) in shapes.iter().enumerate() {
            if let Some(cells) = cells(*b).filter(|c| c.len() <= 64) {
                for c in cells {
                    index.entry(c).or_default().push(i);
                    stats.index_entries += 1;
                }
            } else {
                oversized.push(i);
            }
        }
    }
    let mut output = Vec::with_capacity(1 + checked.len() * 4);
    output.push(checked.len() as i64);
    for (query, mut movement) in checked {
        let candidates: Vec<usize> = if indexed {
            let mut swept = query;
            for (a, d) in movement.iter().enumerate() {
                if *d < 0.0 {
                    swept.0[a] += d;
                } else {
                    swept.0[a + 3] += d;
                }
            }
            if let Some(cells) = cells(swept) {
                let mut found = BTreeSet::new();
                found.extend(oversized.iter().copied());
                for c in cells {
                    stats.bucket_visits += 1;
                    if let Some(items) = index.get(&c) {
                        found.extend(items.iter().copied());
                    }
                }
                found.into_iter().collect()
            } else {
                (0..shapes.len()).collect()
            }
        } else {
            (0..shapes.len()).collect()
        };
        let mut overlaps = Vec::new();
        for i in candidates {
            let b = shapes[i];
            stats.exact_tests += 1;
            if b.intersects(query) {
                overlaps.push(i as i64);
            }
            for (a, d) in movement.iter_mut().enumerate() {
                *d = b.clip(query, a, *d);
            }
        }
        output.extend(movement.map(|v| v.to_bits() as i64));
        output.push(overlaps.len() as i64);
        output.extend(overlaps);
    }
    Some((output, stats))
}
