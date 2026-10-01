//! Retained vs Ephemeral ChunkState Performance Benchmark Suite.
//!
//! Measures and compares:
//! 1. Ephemeral snapshot pipeline (transport deserialization -> encode)
//! 2. Retained chunk cold encode (first encode populating wire cache)
//! 3. Retained chunk static re-encode (fast path reusing cached wire payload)
//! 4. Retained chunk single-block mutation re-encode
//! 5. Retained chunk burst mutation re-encode (20 blocks across 4 sections)

use native_chunk::packet_snapshot::OwnedPacketSnapshot;
use native_chunk::NativeChunk;
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
    out.extend_from_slice(&0xffffu16.to_be_bytes()); // filter (all 16 sections)
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

    // Sections 0..7
    for y in 0..8u8 {
        out.push(y);
        out.push(0);
        out.extend_from_slice(&4096u16.to_be_bytes()); // refcount
        out.extend_from_slice(&4u16.to_be_bytes()); // palette len = 4
                                                    // Palette: stone (1), dirt (3), grass (2), cobblestone (4)
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

    // Biome array (256 bytes)
    out.extend_from_slice(&[4u8; 256]);
    out
}

#[allow(dead_code)]
struct Stats {
    min_us: f64,
    mean_us: f64,
    p50_us: f64,
    p95_us: f64,
    p99_us: f64,
    max_us: f64,
    throughput_kops: f64,
}

fn compute_stats(mut samples: Vec<f64>) -> Stats {
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = samples.len();
    let sum: f64 = samples.iter().sum();
    let mean = sum / n as f64;
    let min = samples[0];
    let max = samples[n - 1];
    let p50 = samples[n * 50 / 100];
    let p95 = samples[n * 95 / 100];
    let p99 = samples[n * 99 / 100];
    let throughput_kops = 1000.0 / mean;
    Stats {
        min_us: min,
        mean_us: mean,
        p50_us: p50,
        p95_us: p95,
        p99_us: p99,
        max_us: max,
        throughput_kops,
    }
}

#[test]
fn bench_retained_vs_ephemeral_chunkstate() {
    let transport = build_test_transport();
    let iters = 2000;
    let mut out = vec![0u8; 262144];

    println!("\n================================================================================");
    println!("  RUSTCRAFT ENGINE-OWNERSHIP BENCHMARK: RETAINED CHUNKSTATE VS EPHEMERAL SNAPSHOT");
    println!("================================================================================");
    println!(
        "Transport payload: {} bytes | Sections: 8 | Samples per arm: {}",
        transport.len(),
        iters
    );

    // Warmup
    for _ in 0..200 {
        let snap = OwnedPacketSnapshot::from_transport(&transport).unwrap();
        let _ = snap.encode(&mut out);
    }

    // 1. Arm A: Ephemeral Snapshot (deserialization + wire encode on every call)
    let mut samples_ephemeral = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t0 = Instant::now();
        let snap = OwnedPacketSnapshot::from_transport(&transport).unwrap();
        let res = snap.encode(&mut out).unwrap();
        std::hint::black_box(&out[..res.bytes_written]);
        samples_ephemeral.push(t0.elapsed().as_nanos() as f64 / 1000.0);
    }
    let stats_ephemeral = compute_stats(samples_ephemeral);

    // 2. Arm B: Retained Chunk Cold Encode (seeding + first encode populating wire cache)
    let mut samples_retained_cold = Vec::with_capacity(iters);
    for _ in 0..iters {
        let mut chunk = NativeChunk::from_transport(&transport).unwrap();
        let t0 = Instant::now();
        let mut off = 0;
        let res = chunk
            .encode_packet_payload(true, true, &mut out, &mut off)
            .unwrap();
        std::hint::black_box(&out[..res.bytes_written]);
        samples_retained_cold.push(t0.elapsed().as_nanos() as f64 / 1000.0);
    }
    let stats_retained_cold = compute_stats(samples_retained_cold);

    // 3. Arm C: Retained Chunk Static Re-Encode (fast path reusing wire cache)
    let mut retained_chunk = NativeChunk::from_transport(&transport).unwrap();
    let mut off = 0;
    let _ = retained_chunk
        .encode_packet_payload(true, true, &mut out, &mut off)
        .unwrap(); // warm cache

    let mut samples_retained_static = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t0 = Instant::now();
        let mut off = 0;
        let res = retained_chunk
            .encode_packet_payload(true, true, &mut out, &mut off)
            .unwrap();
        std::hint::black_box(&out[..res.bytes_written]);
        samples_retained_static.push(t0.elapsed().as_nanos() as f64 / 1000.0);
    }
    let stats_retained_static = compute_stats(samples_retained_static);

    // 4. Arm D: Retained Chunk with Single-Block Mutation
    let mut samples_retained_single_mut = Vec::with_capacity(iters);
    for i in 0..iters {
        retained_chunk.sections[0].as_mut().unwrap().set_block(
            i % 16,
            (i / 16) % 16,
            0,
            (i % 20 + 1) as u16,
        );
        let t0 = Instant::now();
        let mut off = 0;
        let res = retained_chunk
            .encode_packet_payload(true, true, &mut out, &mut off)
            .unwrap();
        std::hint::black_box(&out[..res.bytes_written]);
        samples_retained_single_mut.push(t0.elapsed().as_nanos() as f64 / 1000.0);
    }
    let stats_retained_single_mut = compute_stats(samples_retained_single_mut);

    // 5. Arm E: Retained Chunk with Burst Mutation (20 blocks across 4 sections)
    let mut samples_retained_burst = Vec::with_capacity(iters);
    for i in 0..iters {
        for s in 0..4 {
            for b in 0..5 {
                retained_chunk.sections[s].as_mut().unwrap().set_block(
                    b,
                    b,
                    0,
                    ((i + s + b) % 50 + 1) as u16,
                );
            }
        }
        let t0 = Instant::now();
        let mut off = 0;
        let res = retained_chunk
            .encode_packet_payload(true, true, &mut out, &mut off)
            .unwrap();
        std::hint::black_box(&out[..res.bytes_written]);
        samples_retained_burst.push(t0.elapsed().as_nanos() as f64 / 1000.0);
    }
    let stats_retained_burst = compute_stats(samples_retained_burst);

    println!("\n+---------------------------------------+----------+----------+----------+----------+----------+----------------+");
    println!("| Scenario                              | p50 (us) | p95 (us) | p99 (us) | min (us) | max (us) | Throughput     |");
    println!("+---------------------------------------+----------+----------+----------+----------+----------+----------------+");
    println!("| Ephemeral Snapshot (Baseline)         | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.1} kops/s |",
        stats_ephemeral.p50_us, stats_ephemeral.p95_us, stats_ephemeral.p99_us, stats_ephemeral.min_us, stats_ephemeral.max_us, stats_ephemeral.throughput_kops);
    println!("| Retained Chunk (Cold Encode)          | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.1} kops/s |",
        stats_retained_cold.p50_us, stats_retained_cold.p95_us, stats_retained_cold.p99_us, stats_retained_cold.min_us, stats_retained_cold.max_us, stats_retained_cold.throughput_kops);
    println!("| Retained Chunk (Static Wire Cache)    | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.1} kops/s |",
        stats_retained_static.p50_us, stats_retained_static.p95_us, stats_retained_static.p99_us, stats_retained_static.min_us, stats_retained_static.max_us, stats_retained_static.throughput_kops);
    println!("| Retained Chunk (Single-Block Mut)     | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.1} kops/s |",
        stats_retained_single_mut.p50_us, stats_retained_single_mut.p95_us, stats_retained_single_mut.p99_us, stats_retained_single_mut.min_us, stats_retained_single_mut.max_us, stats_retained_single_mut.throughput_kops);
    println!("| Retained Chunk (Burst Mutation 20 blk)| {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.2} | {:8.1} kops/s |",
        stats_retained_burst.p50_us, stats_retained_burst.p95_us, stats_retained_burst.p99_us, stats_retained_burst.min_us, stats_retained_burst.max_us, stats_retained_burst.throughput_kops);
    println!("+---------------------------------------+----------+----------+----------+----------+----------+----------------+");

    let speedup_static = stats_ephemeral.p50_us / stats_retained_static.p50_us;
    let speedup_single = stats_ephemeral.p50_us / stats_retained_single_mut.p50_us;
    let speedup_burst = stats_ephemeral.p50_us / stats_retained_burst.p50_us;

    println!("\nSpeedup Factors vs Ephemeral Snapshot Baseline:");
    println!(
        "  Static Wire Cache Speedup:        {:.2}x faster",
        speedup_static
    );
    println!(
        "  Single-Block Mutation Speedup:    {:.2}x faster",
        speedup_single
    );
    println!(
        "  Burst Mutation (4 secs) Speedup:  {:.2}x faster",
        speedup_burst
    );

    assert!(
        speedup_static >= 2.0,
        "Static wire cache speedup must be at least 2.0x, got {:.2}x",
        speedup_static
    );
}
