use crate::{
    accounting,
    protocol::{hot_checksum, state_checksum},
    spatial::Spatial,
    storage::*,
};
use std::hint::black_box;
use std::time::Instant;
struct Phase {
    name: &'static str,
    nanos: u128,
    operations: usize,
    requested: u64,
    calls: u64,
    live_delta: i128,
    peak_extra: u64,
}
fn measured<T>(name: &'static str, operations: usize, run: impl FnOnce() -> T) -> (T, Phase) {
    accounting::reset_peak();
    let before = accounting::counters();
    let began = Instant::now();
    let result = black_box(run());
    let nanos = began.elapsed().as_nanos();
    let after = accounting::counters();
    (
        result,
        Phase {
            name,
            nanos,
            operations,
            requested: after.requested - before.requested,
            calls: after.calls - before.calls,
            live_delta: i128::from(after.live) - i128::from(before.live),
            peak_extra: after.peak.saturating_sub(before.live),
        },
    )
}
impl Phase {
    fn json(&self) -> String {
        format!("{{\"name\":\"{}\",\"ns\":{},\"operations\":{},\"requested_bytes\":{},\"allocation_calls\":{},\"live_delta_bytes\":{},\"peak_extra_bytes\":{}}}",self.name,self.nanos,self.operations,self.requested,self.calls,self.live_delta,self.peak_extra)
    }
}
pub fn run<S: Store>(backend: &str, n: usize, session: &str, challenge: &str) {
    assert!((256..=20000).contains(&n));
    let start = accounting::counters();
    let (mut r, init) = measured("initialize", 1, || Registry::<S>::new(9));
    let mut phases = vec![init];
    let (_, p) = measured("spawn_with_cold_payloads", n, || {
        for i in 0..n {
            r.spawn(i, i as u64).unwrap();
        }
    });
    phases.push(p);
    let populated_live = accounting::counters().live.saturating_sub(start.live);
    let (_, p) = measured("hot_iteration", 32 * n, || {
        for _ in 0..32 {
            r.store.tick()
        }
    });
    phases.push(p);
    let (lookup, p) = measured("logical_lookup", 4 * n, || {
        let mut sum = 0u64;
        for i in 0..4 * n {
            let k = r.key((i * 104729) % n).unwrap();
            let h = r.hot(k).unwrap();
            sum = sum.wrapping_add(h.position.0[0] as u64)
        }
        sum
    });
    phases.push(p);
    let (_, p) = measured("extension_add_remove_churn", 5 * n, || {
        for pass in 0..4 {
            for i in 0..n {
                let k = r.key(i).unwrap();
                r.extension(
                    k,
                    if (i + pass) % 2 == 0 {
                        Some(Extension((i + pass) as u64))
                    } else {
                        None
                    },
                )
                .unwrap();
            }
        }
        for i in 0..n {
            r.extension(r.key(i).unwrap(), None).unwrap()
        }
    });
    phases.push(p);
    let (_, p) = measured(
        "despawn_respawn_with_cold_payloads",
        2 * n.div_ceil(4),
        || {
            for i in (0..n).step_by(4) {
                r.despawn(r.key(i).unwrap()).unwrap();
                r.spawn(i, i as u64 + 100000).unwrap();
            }
        },
    );
    phases.push(p);
    let (scan, p) = measured("native_hot_scan_materialization", 8 * n, || {
        let mut rows = Vec::new();
        for _ in 0..8 {
            rows = black_box(r.store.scan())
        }
        rows
    });
    phases.push(p);
    let (ordered, p) = measured("restore_logical_order_with_copy", 8 * n, || {
        let mut last = Vec::new();
        for _ in 0..8 {
            let mut rows = scan.clone();
            rows.sort_unstable_by_key(|(k, _)| *k);
            last = black_box(rows)
        }
        last
    });
    phases.push(p);
    let (index, p) = measured("spatial_index_build_with_copy", 8 * n, || {
        let mut last = None;
        for _ in 0..8 {
            last = Some(black_box(Spatial::new(scan.clone())))
        }
        last.unwrap()
    });
    phases.push(p);
    let (queries, p) = measured("spatial_queries", 32, || {
        let mut sum = 0usize;
        for i in 0..32 {
            let x = i * 200;
            sum += black_box(index.query([x, 0, 0], [x + 512, 8192, 8192])).len()
        }
        sum
    });
    phases.push(p);
    let (pairs, p) = measured("collision_broadphase", 4, || {
        let mut last = Vec::new();
        for _ in 0..4 {
            last = black_box(index.pairs())
        }
        last.len()
    });
    phases.push(p);
    // Fixed semantic sequence, no built-in ECS scheduler or callbacks: move,
    // restore logical event order, rebuild spatial index, then bounded queries.
    let (pipeline, p) = measured("complete_pure_tick_pipeline", 8, || {
        let mut last = 0;
        for _ in 0..8 {
            r.store.tick();
            let mut hot = r.store.scan();
            hot.sort_unstable_by_key(|(k, _)| *k);
            last = hot_checksum(&hot);
            let spatial = Spatial::new(hot);
            last ^= spatial.pairs().len() as u64;
            for i in 0..4 {
                last ^= spatial
                    .query([i * 500, 0, 0], [i * 500 + 512, 8192, 8192])
                    .len() as u64;
            }
            black_box(last);
        }
        last
    });
    phases.push(p);
    let final_rows = r.snapshot();
    let checksum = state_checksum(&final_rows);
    let final_hot = hot_checksum(
        &final_rows
            .iter()
            .map(|v| (v.key, v.hot))
            .collect::<Vec<_>>(),
    );
    drop(final_rows);
    drop(index);
    drop(scan);
    let ordered_checksum = hot_checksum(&ordered);
    drop(ordered);
    let retained = accounting::counters().live.saturating_sub(start.live);
    drop(r);
    let residual = accounting::counters().live.saturating_sub(start.live);
    println!("{{\"schema\":\"ENTITY_STORAGE_SAMPLE_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"backend\":\"{}\",\"entities\":{},\"production_authority\":false,\"phases\":[{}],\"lookup_checksum\":{},\"ordered_checksum\":{},\"spatial_query_hits\":{},\"broadphase_pairs\":{},\"pipeline_checksum\":{},\"final_hot_checksum\":{},\"final_state_checksum\":{},\"populated_live_bytes_delta\":{},\"retained_live_bytes_delta\":{},\"residual_live_bytes_delta\":{},\"memory_is_rss\":false,\"allocator_instrumentation_enabled\":true}}",session,challenge,backend,n,phases.iter().map(Phase::json).collect::<Vec<_>>().join(","),lookup,ordered_checksum,queries,pairs,pipeline,final_hot,checksum,populated_live,retained,residual);
}
