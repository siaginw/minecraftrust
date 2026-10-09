//! Synthetic single-thread layout probe, not a server/packet performance claim.
use native_state_vnext::section::{LayoutPolicy, NativeSection, SECTION_CELLS};
use rustcraft_core::DenseRuntimeStateId as Id;
use std::hint::black_box;
use std::time::Instant;

const SAMPLES: usize = 5;
const READS: usize = 200_000;
const WRITES: usize = 50_000;
const CANONICALIZATIONS: usize = 32;

fn policy(promote: usize, linear: usize, hot: u64) -> LayoutPolicy {
    LayoutPolicy {
        promote_unique: promote,
        demote_unique: promote / 4,
        linear_lookup_max: linear,
        cold_sweeps: 2,
        cold_write_budget: 8,
        hot_write_threshold: hot,
    }
}

fn indices() -> Vec<usize> {
    let mut seed = 0x7465_7374_6866_u64;
    (0..READS)
        .map(|_| {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed as usize) % SECTION_CELLS
        })
        .collect()
}

fn checksum(values: &[Id]) -> u64 {
    values.iter().enumerate().fold(0, |sum, (index, value)| {
        sum.wrapping_add((index as u64 + 1).wrapping_mul(u64::from(value.0)))
    })
}

fn median(values: &[u128]) -> u128 {
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    sorted[sorted.len() / 2]
}

fn main() {
    let indices = indices();
    let mut rows = Vec::new();
    for promote in [64, 128, 512] {
        for linear in [8, 32] {
            for cardinality in [1, 4, 16, 64, 128, 512, 2048] {
                let initial: Vec<Id> = (0..SECTION_CELLS)
                    .map(|index| Id(70_000 + (index % cardinality) as u32))
                    .collect();
                // The same prepared values/indices drive both implementations.
                let writes: Vec<_> = (0..WRITES)
                    .map(|index| {
                        (
                            indices[index],
                            Id(70_000 + ((index * 31 + 1) % cardinality) as u32),
                        )
                    })
                    .collect();
                let mut times = [Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new()];
                let mut final_checksum = 0;
                let mut final_section =
                    NativeSection::from_dense(&initial, policy(promote, linear, u64::MAX)).unwrap();
                // One discarded warm-up, then five measured samples. Alternate
                // baseline order to reduce a simple first-run/cache-order bias.
                for sample in 0..=SAMPLES {
                    let mut dense = initial.clone();
                    let mut section =
                        NativeSection::from_dense(&initial, policy(promote, linear, u64::MAX))
                            .unwrap();
                    let mut sample_times = [0_u128; 5];
                    let mut sums = [0_u64; 2];
                    for mode in if sample % 2 == 0 { [0, 1] } else { [1, 0] } {
                        let start = Instant::now();
                        for &index in &indices {
                            let value = if mode == 0 {
                                black_box(&dense)[black_box(index)]
                            } else {
                                black_box(&section).get(black_box(index)).unwrap()
                            };
                            sums[mode] = sums[mode].wrapping_add(u64::from(black_box(value).0));
                        }
                        sample_times[mode] = start.elapsed().as_nanos();
                        let start = Instant::now();
                        for &(index, value) in &writes {
                            if mode == 0 {
                                black_box(&mut dense)[black_box(index)] = black_box(value);
                            } else {
                                black_box(&mut section)
                                    .set(black_box(index), black_box(value))
                                    .unwrap();
                            }
                        }
                        sample_times[mode + 2] = start.elapsed().as_nanos();
                    }
                    assert_eq!(sums[0], sums[1]);
                    assert_eq!(dense, section.dense());
                    final_checksum = checksum(&dense);
                    let start = Instant::now();
                    for _ in 0..CANONICALIZATIONS {
                        black_box(section.canonical_palette());
                    }
                    sample_times[4] = start.elapsed().as_nanos();
                    let (palette, local) = section.canonical_palette();
                    assert_eq!(
                        local
                            .iter()
                            .map(|&i| palette[usize::from(i)])
                            .collect::<Vec<_>>(),
                        dense
                    );
                    if sample > 0 {
                        for (times, measured) in times.iter_mut().zip(sample_times) {
                            times.push(measured);
                        }
                    }
                    final_section = section;
                }
                let footprint = final_section.footprint();
                let stats = final_section.stats();
                rows.push(format!(
                    concat!(
                    "{{\"promote_unique\":{},\"linear_lookup_max\":{},\"cardinality\":{},",
                    "\"layout\":\"{:?}\",\"dense_read_ns\":{:?},\"layout_read_ns\":{:?},",
                    "\"dense_write_ns\":{:?},\"layout_write_ns\":{:?},\"canonical_index_ns\":{:?},",
                    "\"median_dense_read_ns\":{},\"median_layout_read_ns\":{},",
                    "\"median_dense_write_ns\":{},\"median_layout_write_ns\":{},",
                    "\"median_canonical_index_ns\":{},\"state_bytes\":{},\"index_bytes\":{},",
                    "\"count_bytes\":{},\"lookup_capacity_entries\":{},\"linear_lookups\":{},",
                    "\"hashed_lookups\":{},\"final_checksum\":{},\"dense_match\":true}}"
                ),
                    promote,
                    linear,
                    cardinality,
                    final_section.layout(),
                    times[0],
                    times[1],
                    times[2],
                    times[3],
                    times[4],
                    median(&times[0]),
                    median(&times[1]),
                    median(&times[2]),
                    median(&times[3]),
                    median(&times[4]),
                    footprint.state_bytes,
                    footprint.index_bytes,
                    footprint.count_bytes,
                    footprint.lookup_capacity_entries,
                    stats.linear_lookups,
                    stats.hashed_lookups,
                    final_checksum
                ));
            }
        }
    }
    let mut transitions = Vec::new();
    for threshold in [64, 512, 2048] {
        let mut section = NativeSection::uniform(Id(70_000), policy(128, 16, threshold)).unwrap();
        let start = Instant::now();
        for index in 0..threshold {
            section
                .set(index as usize % SECTION_CELLS, Id(70_001))
                .unwrap();
        }
        let promotion_ns = start.elapsed().as_nanos();
        assert_eq!(
            section.layout(),
            native_state_vnext::section::Layout::DenseHot
        );
        let start = Instant::now();
        section.maintenance_sweep(); // hot window resets cold streak
        section.maintenance_sweep(); // first cold window
        assert_eq!(
            section.layout(),
            native_state_vnext::section::Layout::DenseHot
        );
        section.maintenance_sweep(); // second cold window permits demotion
        let demotion_ns = start.elapsed().as_nanos();
        assert_eq!(
            section.layout(),
            native_state_vnext::section::Layout::LocalPalette
        );
        let stats = section.stats();
        assert_eq!((stats.promotions, stats.demotions), (1, 1));
        transitions.push(format!(
            concat!(
                "{{\"hot_write_threshold\":{},\"promotion_ns\":{},",
                "\"three_sweeps_demotion_ns\":{},\"promotions\":1,\"demotions\":1}}"
            ),
            threshold, promotion_ns, demotion_ns
        ));
    }
    println!(
        concat!(
            "{{\"schema\":\"H6_LAYOUT_PROBE_V1\",\"scope\":\"SYNTHETIC_SINGLE_THREAD\",",
            "\"production_authority\":false,\"samples\":{},\"warmup_samples\":1,",
            "\"reads_per_sample\":{},\"writes_per_sample\":{},\"canonicalizations_per_sample\":{},",
            "\"baseline\":\"independent_u32_Vec_not_legacy_native_chunk\",",
            "\"memory_scope\":\"vector_payload_excludes_allocator_and_hash_buckets\",",
            "\"packing_scope\":\"canonical_local_indices_only_no_wire_packet\",",
            "\"rows\":[{}],\"transitions\":[{}]}}"
        ),
        SAMPLES,
        READS,
        WRITES,
        CANONICALIZATIONS,
        rows.join(","),
        transitions.join(",")
    );
}
