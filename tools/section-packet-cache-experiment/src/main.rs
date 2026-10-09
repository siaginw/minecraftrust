use native_state_vnext::section::NativeSection;
use rustcraft_core::DenseRuntimeStateId;
use section_packet_cache_experiment::*;
use std::collections::VecDeque;
use std::hint::black_box;
use std::io::Write;
use std::path::Path;
use std::time::Instant;

fn input(world: &World) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend(0x48385031u32.to_be_bytes());
    out.extend(world.key.protocol.to_be_bytes());
    out.extend(world.key.resource.x.to_be_bytes());
    out.extend(world.key.resource.z.to_be_bytes());
    out.extend(world.key.mask.to_be_bytes());
    out.extend([
        u8::from(world.key.skylight),
        u8::from(world.key.full),
        world.key.recipient_rules as u8,
    ]);
    out.extend((world.mapping.len() as u16).to_be_bytes());
    for (runtime, wire) in &world.mapping {
        out.extend(runtime.to_be_bytes());
        out.extend(wire.to_be_bytes());
    }
    for y in 0..16 {
        if let Some(section) = world.sections.get(&y) {
            out.push(1);
            for id in section.data.dense() {
                out.extend(id.0.to_be_bytes());
            }
            out.extend(section.block_light);
            out.extend(section.sky_light);
        } else {
            out.push(0);
        }
    }
    out.extend(world.biomes);
    out
}
fn fixtures(dir: &Path) {
    std::fs::create_dir_all(dir).unwrap();
    let mut manifest = std::fs::File::create(dir.join("manifest.tsv")).unwrap();
    for (name, cardinality, mask, dense, sky, full, recipient) in [
        ("uniform", 1, 1, false, true, true, 0),
        ("small", 8, 3, false, true, true, 0),
        ("large_sparse", 64, 0x21, false, true, true, 0),
        ("direct", 1024, 1, false, true, true, 0),
        ("dense_hot", 8, 1, true, true, true, 0),
        ("no_sky", 64, 2, false, false, true, 0),
        ("partial", 8, 1, false, true, false, 0),
        ("recipient", 64, 1, false, true, true, 1),
        ("empty", 1, 0, false, true, true, 0),
        ("all_sections", 1024, 0xffff, false, true, true, 0),
        ("mutated", 8, 1, false, true, true, 0),
        ("air", 1, 1, false, true, true, 0),
        ("local_15", 15, 1, false, true, true, 0),
        ("local_16", 16, 1, false, true, true, 0),
        ("local_32", 32, 1, false, true, true, 0),
        ("local_128", 128, 1, false, true, true, 0),
        ("local_255", 255, 1, false, true, true, 0),
        ("direct_256", 256, 1, false, true, true, 0),
    ] {
        let mut w = World::fixture(cardinality, mask, dense);
        w.key.skylight = sky;
        w.key.dimension_rules = u64::from(sky);
        w.key.full = full;
        w.key.recipient_rules = recipient;
        if name == "air" {
            w.mapping.insert(RUNTIME_BASE, 0);
        }
        if name == "mutated" {
            w.mutate(0);
            w.sections.get_mut(&0).unwrap().block_light[0] = 7;
            w.biomes[0] = 9;
            w.key.light += 1;
            w.key.biomes += 1;
        }
        let a = encode(&w, MAX_BODY).unwrap();
        let b = encode_legacy(&w, &mut w.legacy().unwrap(), MAX_BODY).unwrap();
        assert_eq!(a.bytes(), b.bytes(), "FIRST_DIVERGENCE fixture={name}");
        assert_eq!(a.emitted_mask(), mask);
        std::fs::write(dir.join(format!("{name}.input")), input(&w)).unwrap();
        std::fs::write(dir.join(format!("{name}.prototype.bin")), a.bytes()).unwrap();
        std::fs::write(dir.join(format!("{name}.legacy.bin")), b.bytes()).unwrap();
        writeln!(
            manifest,
            "{name}\t{mask}\t{}\t{}",
            a.bytes().len(),
            a.digest()
        )
        .unwrap();
    }
    println!("FIXTURES_PASS cases=18");
}

fn reads(count: usize, mut get: impl FnMut(usize) -> u64) -> (u128, u64) {
    let mut sum = 0;
    let now = Instant::now();
    for i in 0..count {
        sum += black_box(get((i * 37) % CELLS));
    }
    (now.elapsed().as_nanos(), sum)
}
fn writes(ops: &[(usize, usize)], mut set: impl FnMut(usize, usize)) {
    for (index, value) in ops {
        set(*index, *value);
    }
}

fn layout(name: &str, cardinality: usize, dense: bool, repeat: i32) {
    let mut w = World::fixture(cardinality, 1, dense);
    let mut legacy = w.legacy().unwrap();
    let expected_layout = w.sections[&0].data.layout();
    let count = 100_000usize;
    let old_section = legacy.sections[0].as_ref().unwrap();
    let new_section = &w.sections[&0].data;
    let old_read_run = || reads(count, |i| u64::from(old_section.get_block_by_index(i)));
    let new_read_run = || reads(count, |i| u64::from(new_section.get(i).unwrap().0));
    let ((old_read, old_sum), (new_read, new_sum)) = if repeat % 2 == 0 {
        let b = new_read_run();
        (old_read_run(), b)
    } else {
        let a = old_read_run();
        (a, new_read_run())
    };
    assert_eq!(
        new_sum,
        old_sum + count as u64 * u64::from(RUNTIME_BASE - 1)
    );
    let write_count = 10_000;
    let operations: Vec<_> = (0..write_count)
        .map(|i| ((i * 37) % CELLS, (i * 17 + 1) % cardinality))
        .collect();
    let old_section = legacy.sections[0].as_mut().unwrap();
    let new_section = &mut w.sections.get_mut(&0).unwrap().data;
    let mut old_write_run = || {
        let now = Instant::now();
        writes(&operations, |i, v| {
            black_box(old_section.set_block_by_index(i, (v + 1) as u16));
        });
        now.elapsed().as_nanos()
    };
    let mut new_write_run = || {
        let now = Instant::now();
        writes(&operations, |i, v| {
            black_box(
                new_section
                    .set(i, DenseRuntimeStateId(RUNTIME_BASE + v as u32))
                    .unwrap(),
            );
        });
        now.elapsed().as_nanos()
    };
    let (old_write, new_write) = if repeat % 2 == 0 {
        let b = new_write_run();
        (old_write_run(), b)
    } else {
        let a = old_write_run();
        (a, new_write_run())
    };
    for i in 0..CELLS {
        assert_eq!(
            legacy.sections[0].as_ref().unwrap().get_block_by_index(i),
            w.wire(w.sections[&0].data.get(i).unwrap()).unwrap()
        );
    }
    let prototype = encode(&w, MAX_BODY).unwrap();
    let reference = encode_legacy(&w, &mut legacy, MAX_BODY).unwrap();
    assert_eq!(prototype.bytes(), reference.bytes());
    let rounds = 16;
    let mut old_pack = || {
        let now = Instant::now();
        for _ in 0..rounds {
            black_box(encode_legacy(&w, &mut legacy, MAX_BODY).unwrap());
        }
        now.elapsed().as_nanos()
    };
    let new_pack = || {
        let now = Instant::now();
        for _ in 0..rounds {
            black_box(encode(&w, MAX_BODY).unwrap());
        }
        now.elapsed().as_nanos()
    };
    let (warm, packed) = if repeat % 2 == 0 {
        let b = new_pack();
        (old_pack(), b)
    } else {
        let a = old_pack();
        (a, new_pack())
    };
    let mut cold = 0;
    for _ in 0..rounds {
        let section = legacy.sections[0].as_mut().unwrap();
        let values = section.states;
        section.replace_states(&values, None, None);
        let now = Instant::now();
        black_box(encode_legacy(&w, &mut legacy, MAX_BODY).unwrap());
        cold += now.elapsed().as_nanos();
    }
    let p = w.sections[&0].data.footprint();
    let stats = w.sections[&0].data.stats();
    let vector_payload =
        if w.sections[&0].data.layout() == native_state_vnext::section::Layout::Uniform {
            0 // H6 footprint's four uniform state bytes are already inline.
        } else {
            p.state_bytes + p.index_bytes + p.count_bytes
        };
    println!("layout\t{name}\t{repeat}\t{expected_layout:?}\t{count}\t{write_count}\t{rounds}\t{old_read}\t{new_read}\t{old_write}\t{new_write}\t{warm}\t{cold}\t{packed}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",std::mem::size_of::<native_chunk::section::NativeSection>(),std::mem::size_of::<Section>(),vector_payload,p.lookup_capacity_entries,prototype.bytes().len(),stats.linear_lookups,stats.hashed_lookups,prototype.digest());
}
fn lookup_comparison(cardinality: usize, repeat: i32) {
    let values: Vec<_> = (0..CELLS)
        .map(|i| DenseRuntimeStateId(RUNTIME_BASE + (i % cardinality) as u32))
        .collect();
    let mut linear_policy = policy(false);
    linear_policy.linear_lookup_max = 256;
    let mut hashed_policy = policy(false);
    hashed_policy.linear_lookup_max = 1;
    let mut linear = NativeSection::from_dense(&values, linear_policy).unwrap();
    let mut hashed = NativeSection::from_dense(&values, hashed_policy).unwrap();
    // Every cell changes on every pass; no-op setters would hide lookup cost.
    let operations: Vec<_> = (0..8)
        .flat_map(|pass| (0..CELLS).map(move |i| (i, (i % cardinality + pass + 1) % cardinality)))
        .collect();
    let apply = |section: &mut NativeSection| {
        let now = Instant::now();
        for (index, value) in &operations {
            assert!(black_box(
                section
                    .set(*index, DenseRuntimeStateId(RUNTIME_BASE + *value as u32))
                    .unwrap()
            ));
        }
        now.elapsed().as_nanos()
    };
    let (linear_ns, hashed_ns) = if repeat % 2 == 0 {
        let h = apply(&mut hashed);
        (apply(&mut linear), h)
    } else {
        let l = apply(&mut linear);
        (l, apply(&mut hashed))
    };
    assert_eq!(linear.dense(), hashed.dense());
    let l = linear.stats();
    let h = hashed.stats();
    assert_eq!(l.linear_lookups, operations.len() as u64);
    assert_eq!(h.hashed_lookups, operations.len() as u64);
    println!(
        "lookup\t{repeat}\t{cardinality}\t{}\t{linear_ns}\t{hashed_ns}\t{}\t{}\t{}",
        operations.len(),
        l.linear_lookups,
        h.hashed_lookups,
        hashed.footprint().lookup_capacity_entries
    );
}
fn transitions(repeat: i32) {
    let mut p = policy(false);
    p.hot_write_threshold = 64;
    let mut section = NativeSection::uniform(DenseRuntimeStateId(RUNTIME_BASE), p).unwrap();
    let now = Instant::now();
    for i in 0..64 {
        section
            .set(i, DenseRuntimeStateId(RUNTIME_BASE + 1))
            .unwrap();
    }
    assert_eq!(
        section.layout(),
        native_state_vnext::section::Layout::DenseHot
    );
    let promotion = now.elapsed().as_nanos();
    let now = Instant::now();
    for i in 0..CELLS {
        section.set(i, DenseRuntimeStateId(RUNTIME_BASE)).unwrap();
    }
    for _ in 0..3 {
        section.maintenance_sweep();
    }
    let demotion = now.elapsed().as_nanos();
    assert_eq!(
        section.layout(),
        native_state_vnext::section::Layout::Uniform
    );
    let stats = section.stats();
    println!(
        "transition\t{repeat}\t{promotion}\t{demotion}\t{}\t{}",
        stats.promotions, stats.demotions
    );
}
struct Outcome {
    ns: u128,
    checksum: u64,
    delivered: usize,
    hits: usize,
    misses: usize,
    invalidated: usize,
    invalidate_ns: u128,
    peak_live: usize,
    peak_resident: usize,
    peak_external: usize,
    evictions: usize,
    pressure: usize,
}
fn workload(
    players: usize,
    radius: i32,
    mutation: bool,
    slow: bool,
    cached: bool,
    budget: usize,
) -> Outcome {
    let mut worlds = Vec::new();
    for x in -radius..=radius {
        for z in -radius..=radius {
            let mut w = World::fixture(8, 1, false);
            w.key.resource.x = x;
            w.key.resource.z = z;
            worlds.push(w);
        }
    }
    let mut baselines: Vec<_> = worlds
        .iter()
        .map(|w| {
            let first = w.legacy().unwrap();
            let mut second = w.clone();
            second.key.recipient_rules = 1;
            [first, second.legacy().unwrap()]
        })
        .collect();
    let live_budget = budget + budget / 2;
    let mut cache = Cache::new(budget, live_budget);
    let accounting = cache.accounting();
    let mut held = VecDeque::new();
    let mut baseline_held = VecDeque::<Box<[u8]>>::new();
    let mut baseline_live = 0;
    let mut result = Outcome {
        ns: 0,
        checksum: 0,
        delivered: 0,
        hits: 0,
        misses: 0,
        invalidated: 0,
        invalidate_ns: 0,
        peak_live: 0,
        peak_resident: 0,
        peak_external: 0,
        evictions: 0,
        pressure: 0,
    };
    let now = Instant::now();
    for round in 0..2 {
        for (index, w) in worlds.iter_mut().enumerate() {
            if mutation {
                if cached {
                    w.mutate(index + round);
                    let t = Instant::now();
                    result.invalidated += cache.invalidate(w.key.resource);
                    result.invalidate_ns += t.elapsed().as_nanos();
                } else {
                    w.key.states += 1;
                    let cell = (index + round) % CELLS;
                    let wire = ((cell % 8 + 1) % 8 + 1) as u16;
                    for (recipient, baseline) in baselines[index].iter_mut().enumerate() {
                        baseline.sections[0]
                            .as_mut()
                            .unwrap()
                            .set_block_by_index(cell, if recipient == 0 { wire } else { 1 });
                    }
                }
            }
            for player in 0..players {
                w.key.recipient_rules = (player % 2) as u64;
                if cached {
                    let packet = match cache.lookup(w.key).unwrap() {
                        Some(p) => {
                            result.hits += 1;
                            p
                        }
                        None => {
                            result.misses += 1;
                            let mut encoded = encode(w, MAX_BODY).unwrap();
                            loop {
                                match cache.publish(encoded, w.key) {
                                    Ok(p) => break p,
                                    Err((Error::RetentionPressure, e)) => {
                                        encoded = *e;
                                        result.pressure += 1;
                                        assert!(
                                            held.pop_front().is_some(),
                                            "retention pressure must drain a pending slow consumer"
                                        );
                                    }
                                    Err((e, _)) => panic!("cache admission {e:?}"),
                                }
                            }
                        }
                    };
                    result.checksum = result.checksum.wrapping_add(packet.digest());
                    result.delivered += packet.bytes().len();
                    if slow && player == 0 {
                        held.push_back(packet);
                        if held.len() > 64 {
                            held.pop_front();
                        }
                    }
                    result.peak_live = result.peak_live.max(accounting.live());
                    result.peak_resident = result.peak_resident.max(cache.resident());
                    result.peak_external = result
                        .peak_external
                        .max(accounting.live() - cache.resident());
                } else {
                    let packet =
                        encode_legacy(w, &mut baselines[index][player % 2], MAX_BODY).unwrap();
                    result.checksum = result.checksum.wrapping_add(packet.digest());
                    result.delivered += packet.bytes().len();
                    result.misses += 1;
                    if slow && player == 0 {
                        let bytes = packet.into_bytes();
                        while baseline_live + bytes.len() > live_budget || baseline_held.len() >= 64
                        {
                            baseline_live -= baseline_held.pop_front().unwrap().len();
                            result.pressure += 1;
                        }
                        baseline_live += bytes.len();
                        baseline_held.push_back(bytes);
                        result.peak_live = result.peak_live.max(baseline_live);
                        result.peak_external = result.peak_live;
                    }
                }
            }
        }
    }
    result.ns = now.elapsed().as_nanos();
    result.evictions = cache.evictions;
    assert!(cache.resident() <= budget && accounting.live() <= live_budget);
    if cached {
        result.peak_live = accounting.peak();
    }
    drop(held);
    drop(cache);
    assert_eq!(accounting.live(), 0);
    result
}
fn benchmark() {
    for repeat in -1..5 {
        for (name, card, dense) in [
            ("uniform", 1, false),
            ("small_palette", 8, false),
            ("large_palette", 64, false),
            ("dense_hot", 8, true),
            ("direct_1024", 1024, false),
        ] {
            layout(name, card, dense, repeat);
        }
        transitions(repeat);
        for cardinality in [8, 64] {
            lookup_comparison(cardinality, repeat);
        }
        for budget in [64 * 1024, 512 * 1024] {
            for players in [1, 16] {
                for radius in [2, 8] {
                    for mutation in [false, true] {
                        for slow in [false, true] {
                            let (a, b) = if repeat % 2 == 0 {
                                let b = workload(players, radius, mutation, slow, true, budget);
                                (workload(players, radius, mutation, slow, false, budget), b)
                            } else {
                                let a = workload(players, radius, mutation, slow, false, budget);
                                (a, workload(players, radius, mutation, slow, true, budget))
                            };
                            assert_eq!(
                                (a.checksum, a.delivered),
                                (b.checksum, b.delivered),
                                "FIRST_DIVERGENCE workload"
                            );
                            println!("cache\t{repeat}\t{players}\t{radius}\t{budget}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",u8::from(mutation),u8::from(slow),a.ns,b.ns,b.hits,b.misses,b.invalidated,b.invalidate_ns,a.peak_live,b.peak_live,b.peak_resident,b.peak_external,b.evictions,b.pressure,a.delivered,a.checksum);
                        }
                    }
                }
            }
        }
    }
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("fixtures") => fixtures(Path::new(args.get(2).expect("fixture output"))),
        Some("bench") => benchmark(),
        _ => panic!("use fixtures DIR or bench"),
    }
}
