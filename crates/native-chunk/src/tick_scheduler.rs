//! M3-A: authoritative scheduled-tick scheduler state (per dimension).
//!
//! Mirrors vanilla `WorldServer` pending-block-tick scheduling semantics
//! (ground truth: notch bytecode of oo.a(Z)Z / oo.a(Let;Laow;II)V,
//! minecraft_server.1.12.2.jar):
//! - ordering = NextTickListEntry.compareTo = (scheduledTime, priority,
//!   tickEntryID); `seq` plays tickEntryID's monotonic role,
//! - admission dedup = hashSet.contains((pos, block)): an entry with the
//!   same (pos, block) already pending is skipped; scheduledTime =
//!   totalWorldTime + delay (deterministic, no jitter) — the Java side
//!   supplies the absolute time (the AIR-material quirk admits with
//!   time=0/prio=0, handled caller-side),
//! - eligibility = scheduledTime <= totalWorldTime (INCLUSIVE; the 65536
//!   constant in the cleaning loop caps the drain COUNT at
//!   min(queue, 65536), it is NOT a time horizon); runAllPending drops
//!   the time predicate but keeps the count cap,
//! - vanilla drains ALL eligible entries first, then executes, so
//!   same-call re-schedules never re-execute — the caller collects the
//!   whole batch before executing,
//! - execution-side: area-not-loaded -> scheduleUpdate(pos, block, 0)
//!   (REQUEUE, not drop); material==AIR or block-changed -> consume
//!   silently (no requeue). Those decisions live in the Java executor;
//!   this queue only owns admission/order/eligibility/removal.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Mutex;

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub struct TickKey {
    pub time: i64,
    pub priority: i32,
    pub seq: u64,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct TickEntry {
    pub key: TickKey,
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub block: i32,
}

impl TickEntry {
    /// packed JNI record: 7 x i64 (LE written by the FFI layer)
    pub fn to_record(self) -> [i64; 7] {
        [
            self.key.seq as i64,
            self.key.time,
            self.key.priority as i64,
            self.x as i64,
            self.y as i64,
            self.z as i64,
            self.block as i64,
        ]
    }
    pub fn from_record(r: &[i64; 7]) -> Self {
        TickEntry {
            key: TickKey {
                time: r[1],
                priority: r[2] as i32,
                seq: r[0] as u64,
            },
            x: r[3] as i32,
            y: r[4] as i32,
            z: r[5] as i32,
            block: r[6] as i32,
        }
    }
}

#[derive(Default)]
struct DimQueue {
    pending: BTreeMap<TickKey, TickEntry>,
    dedup: HashSet<(i32, i32, i32, i32)>, // (x, y, z, block)
    next_seq: u64,
}

fn queues() -> &'static Mutex<HashMap<i32, DimQueue>> {
    static Q: std::sync::OnceLock<Mutex<HashMap<i32, DimQueue>>> = std::sync::OnceLock::new();
    Q.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Admission decision. Returns the seq, or -1 when the (pos, block) is
/// already pending (vanilla hashSet.contains dedup). `time` is the
/// ABSOLUTE scheduledTime (vanilla setScheduledTime(totalTime + delay));
/// priority mirrors setPriority.
pub fn enqueue(dim: i32, x: i32, y: i32, z: i32, block: i32, time: i64, priority: i32) -> i64 {
    let mut qs = queues().lock().unwrap();
    let q = qs.entry(dim).or_default();
    if !q.dedup.insert((x, y, z, block)) {
        return -1; // dedup: skip (vanilla pending-check semantics)
    }
    let seq = q.next_seq;
    q.next_seq += 1;
    let key = TickKey {
        time,
        priority,
        seq,
    };
    q.pending.insert(
        key,
        TickEntry {
            key,
            x,
            y,
            z,
            block,
        },
    );
    seq as i64
}

/// Bulk adoption (mirror reconcile / load-side heal): insert entries whose
/// (pos, block) is not already pending. Returns the count inserted.
/// A negative seq re-sequences with a fresh monotonic id (the caller walks
/// the Java TreeSet in order, so ties keep the tree's relative order);
/// explicit seqs bump next_seq past them.
pub fn adopt(dim: i32, entries: &[TickEntry]) -> i32 {
    let mut qs = queues().lock().unwrap();
    let q = qs.entry(dim).or_default();
    let mut n = 0;
    for e in entries {
        if q.dedup.insert((e.x, e.y, e.z, e.block)) {
            let mut seq = if e.key.seq == u64::MAX {
                q.next_seq
            } else {
                e.key.seq
            };
            if seq == u64::MAX {
                seq = 0;
            }
            // collision guard: an explicit seq already in the map would
            // overwrite a live entry — bump to a fresh monotonic id
            while q.pending.contains_key(&TickKey {
                time: e.key.time,
                priority: e.key.priority,
                seq,
            }) {
                seq = q.next_seq;
                q.next_seq += 1;
            }
            if q.next_seq <= seq {
                q.next_seq = seq + 1;
            }
            let key = TickKey {
                time: e.key.time,
                priority: e.key.priority,
                seq,
            };
            q.pending.insert(
                key,
                TickEntry {
                    key,
                    x: e.x,
                    y: e.y,
                    z: e.z,
                    block: e.block,
                },
            );
            n += 1;
        }
    }
    n
}

/// Eligibility + ordering decision: remove and return up to `cap` entries
/// with time <= horizon (INCLUSIVE — vanilla drains while
/// scheduledTime <= totalWorldTime), in compareTo order.
pub fn drain(dim: i32, horizon: i64, cap: usize, out: &mut Vec<TickEntry>) -> i32 {
    let mut qs = queues().lock().unwrap();
    let Some(q) = qs.get_mut(&dim) else { return 0 };
    let mut n = 0;
    while n < cap {
        let Some((&first, _)) = q.pending.iter().next() else {
            break;
        };
        if first.time > horizon {
            break;
        }
        let (_, e) = q.pending.pop_first().unwrap();
        q.dedup.remove(&(e.x, e.y, e.z, e.block));
        out.push(e);
        n += 1;
    }
    n as i32
}

pub fn pending_count(dim: i32) -> i64 {
    let qs = queues().lock().unwrap();
    qs.get(&dim).map(|q| q.pending.len() as i64).unwrap_or(0)
}

/// clear one dimension (world unload); returns removed count.
pub fn clear(dim: i32) -> i64 {
    let mut qs = queues().lock().unwrap();
    qs.remove(&dim).map(|q| q.pending.len() as i64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(time: i64, pr: i32, seq: u64, x: i32, z: i32, b: i32) -> TickEntry {
        TickEntry {
            key: TickKey {
                time,
                priority: pr,
                seq,
            },
            x,
            y: 0,
            z,
            block: b,
        }
    }

    #[test]
    fn order_matches_java_compare_to() {
        // (time, priority, seq) lexicographic — the exact Java comparator
        let mut ks = vec![
            TickKey {
                time: 5,
                priority: 0,
                seq: 1,
            },
            TickKey {
                time: 3,
                priority: 9,
                seq: 2,
            },
            TickKey {
                time: 5,
                priority: -1,
                seq: 3,
            },
            TickKey {
                time: 3,
                priority: 9,
                seq: 0,
            },
        ];
        ks.sort();
        assert_eq!(
            ks,
            vec![
                TickKey {
                    time: 3,
                    priority: 9,
                    seq: 0
                },
                TickKey {
                    time: 3,
                    priority: 9,
                    seq: 2
                },
                TickKey {
                    time: 5,
                    priority: -1,
                    seq: 3
                },
                TickKey {
                    time: 5,
                    priority: 0,
                    seq: 1
                }
            ]
        );
    }

    #[test]
    fn admission_dedup_and_drain_horizon() {
        clear(9001);
        assert!(enqueue(9001, 1, 0, 1, 10, 105, 0) >= 0); // absolute t=105
        assert_eq!(enqueue(9001, 1, 0, 1, 10, 107, 3), -1); // dedup same (pos,block)
        assert!(enqueue(9001, 1, 0, 1, 11, 100, 0) >= 0); // different block: admitted, t=100
        assert!(enqueue(9001, 2, 0, 2, 10, 109, 0) >= 0); // t=109
        let mut out = Vec::new();
        // inclusive horizon: vanilla drains while scheduledTime <= totalTime
        assert_eq!(drain(9001, 104, 65536, &mut out), 1);
        assert_eq!(out[0].block, 11); // t=100
        let mut out2 = Vec::new();
        let n = drain(9001, 105, 65536, &mut out2); // t=105 INCLUDED
        assert_eq!(n, 1);
        assert_eq!(out2[0].block, 10);
        assert_eq!(pending_count(9001), 1); // t=109 remains
        let mut out3 = Vec::new();
        assert_eq!(drain(9001, 1000, 65536, &mut out3), 1);
        assert_eq!(out3[0].block, 10);
        assert_eq!(pending_count(9001), 0);
        clear(9001);
    }

    #[test]
    fn drain_respects_count_cap() {
        clear(9003);
        for i in 0..10 {
            assert!(enqueue(9003, i, 0, i, 5, 100, 0) >= 0);
        }
        let mut out = Vec::new();
        assert_eq!(drain(9003, 100, 3, &mut out), 3); // cap < eligible
        assert_eq!(pending_count(9003), 7);
        assert_eq!(drain(9003, 100, 1000, &mut Vec::new()), 7);
        assert_eq!(pending_count(9003), 0);
        clear(9003);
    }

    #[test]
    fn adopt_heals_without_duplicates() {
        clear(9002);
        assert!(enqueue(9002, 1, 0, 1, 5, 53, 0) >= 0);
        // seq = u64::MAX marks "re-sequence me" (Java passes -1 from tree
        // walks); fresh ids keep the walk order for (time, prio) ties
        let batch = [e(53, 0, u64::MAX, 1, 1, 5), e(60, 0, u64::MAX, 9, 2, 5)];
        assert_eq!(adopt(9002, &batch), 1); // (1,0,1,5) deduped; (9,..) inserted
        assert_eq!(pending_count(9002), 2);
        // explicit seq adopted: next enqueue must not collide
        let batch2 = [e(70, 0, 500, 8, 8, 5)];
        assert_eq!(adopt(9002, &batch2), 1);
        let s = enqueue(9002, 4, 0, 4, 5, 0, 0);
        assert!(s as u64 > 500);
        // explicit-seq collision guard: same (time,prio,seq) as a live entry
        // must NOT overwrite it — the adopter gets a bumped seq instead
        let batch3 = [e(70, 0, 500, 7, 7, 6)]; // same key, different (pos,block)
        assert_eq!(adopt(9002, &batch3), 1);
        assert_eq!(pending_count(9002), 5); // all five live, nothing lost
        clear(9002);
    }

    #[test]
    fn record_roundtrip() {
        let e1 = e(1234567890123456, -7, 42, 100, -3, 7777);
        let r = e1.to_record();
        let e2 = TickEntry::from_record(&r);
        assert_eq!(e1, e2);
    }
}
