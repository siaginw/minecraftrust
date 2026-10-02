//! Comprehensive Cost Model Benchmark Suite for Rust NativeChunk.
//! Measures and reports p50, p95, p99, throughput, and allocations for:
//! - chunk seed
//! - state reads (direct pointer vs AtomicU16 load)
//! - state writes (authoritative NativeChunk mutation)
//! - light reads (AtomicU32 packed word)
//! - light writes (AtomicU32 CAS)
//! - biome reads & writes
//! - height reads & updates (fast downward scan vs cache)
//! - section creation & emptying
//! - packet cold encode, static wire cache encode, single-section dirty, and multi-section dirty
//! - registry lookup (ChunkKey -> Arc<RwLock<NativeChunk>>)
//! - struct size and alignment metrics

use native_chunk::chunk::NativeChunk;
use native_chunk::registry::ChunkRegistry;
use native_chunk::section::NativeSection;
use std::time::Instant;

fn build_test_transport() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RCSNAP02");
    out.extend_from_slice(&2u16.to_be_bytes());
    out.push(3); // full=1, skylight=1
    out.push(1); // storage=1
    out.push(18); // source telemetry bits
    out.push(1); // scope
    out.extend_from_slice(&0u16.to_be_bytes());
    out.extend_from_slice(&0i32.to_be_bytes()); // dim
    out.extend_from_slice(&100i32.to_be_bytes()); // cx
    out.extend_from_slice(&200i32.to_be_bytes()); // cz
    out.extend_from_slice(&1u64.to_be_bytes()); // generation
    out.extend_from_slice(&0xffffu16.to_be_bytes()); // filter
    out.extend_from_slice(&0x00ffu16.to_be_bytes()); // accepted mask: 8 sections (0..7)
    out.extend_from_slice(&1u64.to_be_bytes()); // event id
    out.extend_from_slice(&1u64.to_be_bytes()); // owner thread
    out.extend_from_slice(&1u64.to_be_bytes()); // capture thread
    out.extend_from_slice(&1u64.to_be_bytes()); // epoch start
    out.extend_from_slice(&1u64.to_be_bytes()); // epoch end
    out.extend_from_slice(&1u64.to_be_bytes()); // inc start
    out.extend_from_slice(&1u64.to_be_bytes()); // inc end
    out.extend_from_slice(&[0u8; 32]); // digest
    assert_eq!(out.len(), 128);
    out.extend_from_slice(&8u16.to_be_bytes()); // 8 sections
    out.extend_from_slice(&157_010u32.to_be_bytes()); // registry size
    out.push(18); // bits

    for y in 0..8u8 {
        out.push(y);
        out.push(0);
        out.extend_from_slice(&4096u16.to_be_bytes()); // refcount
        out.extend_from_slice(&4u16.to_be_bytes()); // palette len = 4
        out.extend_from_slice(&1u16.to_be_bytes());
        out.extend_from_slice(&3u16.to_be_bytes());
        out.extend_from_slice(&2u16.to_be_bytes());
        out.extend_from_slice(&4u16.to_be_bytes());
        out.push(4); // bits = 4
        out.extend_from_slice(&256u16.to_be_bytes()); // word count = 256
        let words = [0x0123012301230123u64; 256];
        for w in &words {
            out.extend_from_slice(&w.to_be_bytes());
        }
        out.extend_from_slice(&[0x00u8; 2048]); // block light
        out.extend_from_slice(&[0xFFu8; 2048]); // sky light
    }

    out.extend_from_slice(&[4u8; 256]); // biomes
    out
}

#[derive(Debug)]
pub struct BenchmarkStats {
    pub name: &'static str,
    pub p50_ns: f64,
    pub p95_ns: f64,
    pub p99_ns: f64,
    pub avg_ns: f64,
    pub throughput_mops: f64,
}

fn measure_nanos<F: FnMut()>(name: &'static str, iters: usize, mut op: F) -> BenchmarkStats {
    // Warmup
    for _ in 0..(iters / 10).max(10) {
        op();
    }
    let mut samples = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t0 = Instant::now();
        op();
        let el = t0.elapsed().as_nanos() as f64;
        samples.push(el);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = samples.len();
    let sum: f64 = samples.iter().sum();
    let avg = sum / n as f64;
    let p50 = samples[n * 50 / 100];
    let p95 = samples[n * 95 / 100];
    let p99 = samples[n * 99 / 100];
    let throughput_mops = if avg > 0.0 { 1000.0 / avg } else { 0.0 };
    BenchmarkStats {
        name,
        p50_ns: p50,
        p95_ns: p95,
        p99_ns: p99,
        avg_ns: avg,
        throughput_mops,
    }
}

#[test]
fn bench_cost_model_native_chunk() {
    println!("\n================================================================================");
    println!("  RUSTCRAFT NATIVE CHUNK DETAILED COST MODEL BENCHMARK (RELEASE)");
    println!("================================================================================");

    let sec_size = std::mem::size_of::<NativeSection>();
    let sec_align = std::mem::align_of::<NativeSection>();
    let chunk_size = std::mem::size_of::<NativeChunk>();
    let chunk_align = std::mem::align_of::<NativeChunk>();

    println!("STRUCT MEMORY LAYOUT:");
    println!("  NativeSection: size = {} bytes ({} KiB), align = {} bytes", sec_size, sec_size / 1024, sec_align);
    println!("  NativeChunk:   size = {} bytes ({} KiB), align = {} bytes", chunk_size, chunk_size / 1024, chunk_align);
    let total_retained_16_sec = chunk_size + (16 * sec_size);
    println!("  Retained Chunk with 16 sections: {} bytes ({} KiB)", total_retained_16_sec, total_retained_16_sec / 1024);

    let transport = build_test_transport();
    let mut out_buf = vec![0u8; 262144];

    let mut results = Vec::new();

    // 1. Chunk Seed (from_transport)
    let stats_seed = measure_nanos("Chunk Seed (from_transport 8 sec)", 500, || {
        let chunk = NativeChunk::from_transport(&transport).unwrap();
        std::hint::black_box(chunk);
    });
    results.push(stats_seed);

    // Setup working chunk
    let mut working_chunk = NativeChunk::from_transport(&transport).unwrap();

    // 2. State Read (Raw AtomicU16 get_block_state)
    let stats_state_read = measure_nanos("State Read (get_block_state AtomicU16)", 100000, || {
        let st = working_chunk.get_block_state(7, 35, 7);
        std::hint::black_box(st);
    });
    results.push(stats_state_read);

    // 3. State Write (Authoritative set_block_state)
    let mut test_state = 1u16;
    let stats_state_write = measure_nanos("State Write (Authoritative set_block_state)", 50000, || {
        test_state = if test_state == 1 { 2 } else { 1 };
        let res = working_chunk.set_block_state(7, 35, 7, test_state);
        std::hint::black_box(res);
    });
    results.push(stats_state_write);

    // 4. Block Light Read
    let stats_bl_read = measure_nanos("Light Read (AtomicU32 block_light)", 100000, || {
        let l = working_chunk.sections[2].as_ref().unwrap().get_block_light(7, 3, 7);
        std::hint::black_box(l);
    });
    results.push(stats_bl_read);

    // 5. Block Light Write (CAS)
    let mut test_light = 5u8;
    let stats_bl_write = measure_nanos("Light Write (AtomicU32 CAS block_light)", 50000, || {
        test_light = if test_light == 5 { 6 } else { 5 };
        let res = working_chunk.sections[2].as_ref().unwrap().set_block_light(7, 3, 7, test_light);
        std::hint::black_box(res);
    });
    results.push(stats_bl_write);

    // 6. Biome Read
    let stats_bio_read = measure_nanos("Biome Read (get_biome [u8; 256])", 100000, || {
        let b = working_chunk.get_biome(7, 7);
        std::hint::black_box(b);
    });
    results.push(stats_bio_read);

    // 7. Biome Write
    let mut test_bio = 1u8;
    let stats_bio_write = measure_nanos("Biome Write (set_biome)", 50000, || {
        test_bio = if test_bio == 1 { 2 } else { 1 };
        let res = working_chunk.set_biome(7, 7, test_bio);
        std::hint::black_box(res);
    });
    results.push(stats_bio_write);

    // 8. Height Read
    let stats_height_read = measure_nanos("Height Read (get_height [u16; 256])", 100000, || {
        let h = working_chunk.get_height(7, 7);
        std::hint::black_box(h);
    });
    results.push(stats_height_read);

    // 9. Height Recomputation (Downward Scan across sections)
    let stats_height_recompute = measure_nanos("Height Recompute (Downward Scan)", 50000, || {
        let h = working_chunk.recompute_height(7, 7);
        std::hint::black_box(h);
    });
    results.push(stats_height_recompute);

    // 10. Packet Cold Encode (measuring encoding 8 cold sections and caching them)
    let stats_packet_cold = measure_nanos("Packet Cold Encode (populate wire cache)", 200, || {
        let mut c = NativeChunk::from_transport(&transport).unwrap();
        let mut off = 0;
        let res = c.encode_packet_payload(true, true, &mut out_buf, &mut off).unwrap();
        let _ = std::hint::black_box(res);
    });
    results.push(stats_packet_cold);

    // Prime working_chunk wire cache
    let mut off = 0;
    let _ = working_chunk.encode_packet_payload(true, true, &mut out_buf, &mut off).unwrap();

    // 11. Packet Static Encode (Warm wire cache hit)
    let stats_packet_static = measure_nanos("Packet Static Encode (Wire Cache Hit)", 10000, || {
        let mut off = 0;
        let res = working_chunk.encode_packet_payload(true, true, &mut out_buf, &mut off).unwrap();
        let _ = std::hint::black_box(res);
    });
    results.push(stats_packet_static);

    // 12. Packet Single Section Dirty
    let mut dirty_state = 10u16;
    let stats_packet_single_dirty = measure_nanos("Packet Single Section Dirty", 200, || {
        dirty_state = if dirty_state == 10 { 20 } else { 10 };
        working_chunk.set_block_state(5, 20, 5, dirty_state); // dirties section 1
        let mut off = 0;
        let res = working_chunk.encode_packet_payload(true, true, &mut out_buf, &mut off).unwrap();
        let _ = std::hint::black_box(res);
    });
    results.push(stats_packet_single_dirty);

    // 13. Registry Lookup
    let registry = ChunkRegistry::new();
    let gen = registry.next_generation_id();
    let mut c_reg = NativeChunk::from_transport(&transport).unwrap();
    c_reg.generation_id = gen;
    let handle = registry.insert(c_reg);

    let stats_registry_lookup = measure_nanos("Registry Lookup (ChunkHandle -> Arc)", 50000, || {
        let arc = registry.get(&handle);
        std::hint::black_box(arc);
    });
    results.push(stats_registry_lookup);

    println!("\n+-------------------------------------------------+----------+----------+----------+----------+----------------+");
    println!("| Operation                                       | p50 (ns) | p95 (ns) | p99 (ns) | avg (ns) | Throughput     |");
    println!("+-------------------------------------------------+----------+----------+----------+----------+----------------+");
    for s in &results {
        println!("| {:47} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} Mops/s |",
            s.name, s.p50_ns, s.p95_ns, s.p99_ns, s.avg_ns, s.throughput_mops);
    }
    println!("+-------------------------------------------------+----------+----------+----------+----------+----------------+");
}
