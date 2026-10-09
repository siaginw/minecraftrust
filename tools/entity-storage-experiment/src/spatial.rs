use crate::storage::{Hot, Key};

pub struct Spatial {
    rows: Vec<(Key, Hot)>,
}
fn lo(h: &Hot, a: usize) -> i128 {
    i128::from(h.position.0[a]) - i128::from(h.bounds.0[a])
}
fn hi(h: &Hot, a: usize) -> i128 {
    i128::from(h.position.0[a]) + i128::from(h.bounds.0[a])
}
impl Spatial {
    pub fn new(mut rows: Vec<(Key, Hot)>) -> Self {
        rows.sort_unstable_by_key(|(k, h)| (lo(h, 0), *k));
        Self { rows }
    }
    pub fn query(&self, min: [i64; 3], max: [i64; 3]) -> Vec<Key> {
        let mut out = Vec::new();
        for (k, h) in &self.rows {
            if lo(h, 0) > i128::from(max[0]) {
                break;
            }
            if (0..3).all(|a| hi(h, a) >= i128::from(min[a]) && lo(h, a) <= i128::from(max[a])) {
                out.push(*k)
            }
        }
        out.sort_unstable();
        out
    }
    pub fn pairs(&self) -> Vec<(Key, Key)> {
        let mut out = Vec::new();
        for (i, (a, h)) in self.rows.iter().enumerate() {
            for (b, j) in &self.rows[i + 1..] {
                if lo(j, 0) > hi(h, 0) {
                    break;
                }
                if (1..3).all(|axis| lo(h, axis) <= hi(j, axis) && lo(j, axis) <= hi(h, axis)) {
                    out.push(((*a).min(*b), (*a).max(*b)))
                }
            }
        }
        out.sort_unstable();
        out
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::fixture;
    #[test]
    fn touching_and_sparse() {
        let a = fixture(
            Key {
                world: 1,
                slot: 0,
                generation: 1,
            },
            0,
        );
        let mut b = fixture(Key { slot: 1, ..a.key }, 0);
        b.hot.position.0 = [2, 0, 0];
        let mut c = fixture(Key { slot: 2, ..a.key }, 0);
        c.hot.position.0 = [9, 0, 0];
        let s = Spatial::new(vec![(c.key, c.hot), (b.key, b.hot), (a.key, a.hot)]);
        assert_eq!(s.pairs(), vec![(a.key, b.key)]);
        assert_eq!(s.query([-1; 3], [0; 3]), vec![a.key]);
    }
    #[test]
    fn empty() {
        let s = Spatial::new(Vec::new());
        assert!(s.pairs().is_empty());
        assert!(s.query([0; 3], [1; 3]).is_empty());
    }
}
