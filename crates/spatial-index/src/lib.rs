//! Shared spatial-utility primitive: long-key bucket index.
//!
//! Minimal, Minecraft-policy-free building block shared by M3 (structure
//! spawn-query index) and potential later consumers (e.g. collision section
//! occupancy). Maps an arbitrary i64 key (e.g. a packed region/chunk
//! coordinate, negative and huge values supported) to a compact bucket of
//! u32 entry ids, with explicit insert/remove point-query APIs and bounded
//! per-bucket memory (buckets shrink on removal).
//!
//! Deliberately NOT a general spatial engine: no AABB logic, no ordering
//! policy, no invalidation semantics. Those belong to consumers.

use std::collections::HashMap;

/// Long-key -> bucket-of-ids index.
///
/// Invariants:
/// * an id may appear in many buckets (fan-out is the caller's policy);
/// * a bucket never contains duplicate ids (insert is idempotent per bucket);
/// * buckets are freed when they become empty (bounded memory);
/// * point queries are deterministic for a given content state.
#[derive(Debug, Default, Clone)]
pub struct LongKeyBuckets {
    map: HashMap<i64, Vec<u32>>,
}

impl LongKeyBuckets {
    pub fn new() -> Self {
        Self { map: HashMap::new() }
    }

    /// Prime capacity for `n` distinct keys (bulk-load path).
    pub fn with_capacity(n: usize) -> Self {
        Self { map: HashMap::with_capacity(n) }
    }

    /// Add `id` to the bucket at `key`. Idempotent per (key, id).
    pub fn insert(&mut self, key: i64, id: u32) {
        let b = self.map.entry(key).or_default();
        if !b.contains(&id) {
            b.push(id);
        }
    }

    /// Remove `id` from the bucket at `key`. Returns true if it was present.
    pub fn remove(&mut self, key: i64, id: u32) -> bool {
        if let Some(b) = self.map.get_mut(&key) {
            if let Some(pos) = b.iter().position(|&x| x == id) {
                b.swap_remove(pos);
                if b.is_empty() {
                    self.map.remove(&key);
                }
                return true;
            }
        }
        false
    }

    /// Point lookup: the bucket contents at `key` (empty slice if absent).
    /// The Vec order is insertion order into that bucket — deterministic
    /// for a given sequence of operations.
    pub fn get(&self, key: i64) -> &[u32] {
        self.map.get(&key).map(|v| v.as_slice()).unwrap_or(&[])
    }

    pub fn bucket_count(&self) -> usize {
        self.map.len()
    }

    pub fn total_ids(&self) -> usize {
        self.map.values().map(|v| v.len()).sum()
    }

    /// Approximate heap footprint in bytes (Vec overhead + payload).
    pub fn approx_memory_bytes(&self) -> usize {
        let mut bytes = std::mem::size_of::<HashMap<i64, Vec<u32>>>();
        for v in self.map.values() {
            bytes += std::mem::size_of::<Vec<u32>>() + v.capacity() * std::mem::size_of::<u32>();
        }
        bytes
    }
}

/// Packs two signed 32-bit cell coordinates into one i64 key.
/// Negative coordinates are supported and round-trip exactly.
#[inline]
pub fn pack_key(x: i32, z: i32) -> i64 {
    ((x as i64) << 32) | ((z as i64) & 0xFFFF_FFFF)
}

/// Inverse of [`pack_key`].
#[inline]
pub fn unpack_key(key: i64) -> (i32, i32) {
    let x = (key >> 32) as i32;
    let z = key as i32;
    (x, z)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn negative_and_huge_coordinates_round_trip() {
        for &(x, z) in &[
            (0i32, 0i32),
            (-1, -1),
            (i32::MIN, i32::MAX),
            (i32::MAX, i32::MIN),
            (-500_000, 900_000),
        ] {
            assert_eq!(unpack_key(pack_key(x, z)), (x, z));
        }
    }

    #[test]
    fn insert_remove_and_buckets_free() {
        let mut b = LongKeyBuckets::new();
        b.insert(pack_key(3, 4), 7);
        b.insert(pack_key(3, 4), 7); // idempotent
        b.insert(pack_key(3, 4), 9);
        assert_eq!(b.get(pack_key(3, 4)), &[7, 9]);
        assert_eq!(b.bucket_count(), 1);
        assert!(b.remove(pack_key(3, 4), 7));
        assert!(!b.remove(pack_key(3, 4), 7));
        assert_eq!(b.get(pack_key(3, 4)), &[9]);
        assert!(b.remove(pack_key(3, 4), 9));
        assert!(b.get(pack_key(3, 4)).is_empty());
        assert_eq!(b.bucket_count(), 0); // empty bucket freed
    }

    #[test]
    fn fan_out_and_memory_bounds() {
        let mut b = LongKeyBuckets::new();
        for k in 0..1000i64 {
            b.insert(k, (k % 17) as u32);
        }
        assert_eq!(b.bucket_count(), 1000);
        assert_eq!(b.total_ids(), 1000);
        let m0 = b.approx_memory_bytes();
        assert!(m0 > 0);
        for k in 0..1000i64 {
            b.remove(k, (k % 17) as u32);
        }
        assert_eq!(b.total_ids(), 0);
        assert!(b.approx_memory_bytes() < m0);
    }

    #[test]
    fn deterministic_point_queries() {
        let mut a = LongKeyBuckets::new();
        let mut b = LongKeyBuckets::new();
        for i in 0..50i32 {
            a.insert(pack_key(i, -i), i as u32);
        }
        for i in 0..50i32 {
            b.insert(pack_key(i, -i), i as u32);
        }
        for i in 0..50i32 {
            assert_eq!(a.get(pack_key(i, -i)), b.get(pack_key(i, -i)));
        }
    }
}
