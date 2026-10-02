//! Corpus compression bench: reads a captured [u32 BE len][bytes] corpus and
//! benchmarks candidate compressors per size/entropy bucket with REAL
//! per-packet latency distributions (not big-buffer throughput).
//!
//! Backends:
//!   zlib-rs      via ZlibPacketCompressor (the production type), levels 1/6/9
//!   miniz_oxide  direct one-shot compress_to_slice, levels 1/6 (dev-dep)
//!
//! Usage: cargo run -p compression --release --example corpus_bench -- <corpus.bin> [warmup=200] [passes=3]

use compression::{max_output_len, ZlibPacketCompressor};
use std::time::Instant;

enum Backend {
    ZlibRs { level: u32 },
    Miniz { level: u8 },
}

impl Backend {
    fn name(&self) -> String {
        match self {
            Backend::ZlibRs { level } => format!("zlib-rs L{level}"),
            Backend::Miniz { level } => format!("miniz_oxide L{level}"),
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: corpus_bench <corpus.bin> [warmup=200] [passes=3]");
        std::process::exit(2);
    }
    let corpus = std::fs::read(&args[1]).expect("corpus file");
    let warmup: usize = args.get(2).and_then(|v| v.parse().ok()).unwrap_or(200);
    let passes: usize = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(3);

    let mut bodies: Vec<&[u8]> = Vec::new();
    let mut off = 0usize;
    while off + 4 <= corpus.len() {
        let len = u32::from_be_bytes([
            corpus[off],
            corpus[off + 1],
            corpus[off + 2],
            corpus[off + 3],
        ]) as usize;
        off += 4;
        if off + len > corpus.len() {
            break;
        }
        bodies.push(&corpus[off..off + len]);
        off += len;
    }
    println!(
        "corpus: {} packets, {} bytes total",
        bodies.len(),
        corpus.len()
    );

    // ---- buckets -----------------------------------------------------------
    let idx_by_size = |bodies: &[&[u8]], lo: usize, hi: usize| -> Vec<usize> {
        bodies
            .iter()
            .enumerate()
            .filter(|(_, b)| b.len() >= lo && b.len() < hi)
            .map(|(i, _)| i)
            .collect()
    };
    let mut buckets: Vec<(&str, Vec<usize>)> = Vec::new();
    buckets.push(("t0: 256-1023", idx_by_size(&bodies, 256, 1024)));
    buckets.push(("t1: 1k-4k", idx_by_size(&bodies, 1024, 4096)));
    buckets.push(("t2: 4k-16k", idx_by_size(&bodies, 4096, 16384)));
    buckets.push(("t3: 16k-64k", idx_by_size(&bodies, 16384, 65536)));
    buckets.push(("t4: 64k+", idx_by_size(&bodies, 65536, usize::MAX)));
    // entropy buckets over >= 256 bodies, classified by the zlib-rs level-6 ratio
    let mut entropy: Vec<(&str, Vec<usize>)> = vec![
        ("e0: ratio<0.30", vec![]),
        ("e1: 0.30-0.80", vec![]),
        ("e2: >0.80", vec![]),
    ];
    {
        let mut probe = ZlibPacketCompressor::new(6).expect("compressor");
        for (i, b) in bodies.iter().enumerate() {
            if b.len() < 256 {
                continue;
            }
            let mut out = vec![0u8; max_output_len(b.len())];
            let n = probe.compress_into(b, &mut out).expect("probe compress");
            let ratio = n as f64 / b.len() as f64;
            let slot = if ratio < 0.30 {
                0
            } else if ratio <= 0.80 {
                1
            } else {
                2
            };
            entropy[slot].1.push(i);
        }
    }

    let backends = [
        Backend::ZlibRs { level: 1 },
        Backend::ZlibRs { level: 6 },
        Backend::ZlibRs { level: 9 },
        Backend::Miniz { level: 1 },
        Backend::Miniz { level: 6 },
    ];

    let all_sets: [(&str, &Vec<(&str, Vec<usize>)>); 2] =
        [("size", &buckets), ("entropy", &entropy)];

    for (set_name, set) in all_sets {
        println!("\n== {set_name} buckets ==");
        for (bucket_name, indices) in set.iter() {
            if indices.is_empty() {
                continue;
            }
            for backend in &backends {
                let stats = bench_backend(backend, &bodies, indices, warmup, passes);
                println!(
                    "{:<12} {:<16} n={:<6} in={:<10} out={:<10} ratio={:.4} mean={:<9.0} p50={:<9.0} p95={:<9.0} p99={:<9.0} MB/s={:.1}",
                    bucket_name,
                    backend.name(),
                    stats.n,
                    stats.in_bytes,
                    stats.out_bytes,
                    stats.ratio,
                    stats.mean_ns,
                    stats.p50_ns,
                    stats.p95_ns,
                    stats.p99_ns,
                    stats.mibs,
                );
            }
        }
    }
}

struct Stats {
    n: usize,
    in_bytes: u64,
    out_bytes: u64,
    ratio: f64,
    mean_ns: f64,
    p50_ns: f64,
    p95_ns: f64,
    p99_ns: f64,
    mibs: f64,
}

#[allow(clippy::cast_precision_loss)]
fn bench_backend(
    backend: &Backend,
    bodies: &[&[u8]],
    indices: &[usize],
    warmup: usize,
    passes: usize,
) -> Stats {
    // reusable output buffers per index (bounded by max_output_len)
    let mut outs: Vec<Vec<u8>> = indices
        .iter()
        .map(|&i| vec![0u8; max_output_len(bodies[i].len())])
        .collect();

    let mut run = |pass_indices: &[usize]| -> (Vec<u64>, u64, u64) {
        let mut times = Vec::with_capacity(pass_indices.len());
        let mut in_total = 0u64;
        let mut out_total = 0u64;
        match backend {
            Backend::ZlibRs { level } => {
                let mut c = ZlibPacketCompressor::new(*level).expect("compressor");
                for (k, &i) in pass_indices.iter().enumerate() {
                    let body = bodies[i];
                    let t0 = Instant::now();
                    let n = c.compress_into(body, &mut outs[k]).expect("compress");
                    let dt = t0.elapsed().as_nanos() as u64;
                    times.push(dt);
                    in_total += body.len() as u64;
                    out_total += n as u64;
                }
            }
            Backend::Miniz { level } => {
                // Vec-based zlib one-shot (alloc included) — a CONSERVATIVE
                // upper bound for miniz timing; raw-core slice mode rejects
                // reused finished state in 0.8.9 (BadParam).
                for (_k, &i) in pass_indices.iter().enumerate() {
                    let body = bodies[i];
                    let t0 = Instant::now();
                    let compressed = miniz_oxide::deflate::compress_to_vec_zlib(body, *level);
                    let dt = t0.elapsed().as_nanos() as u64;
                    times.push(dt);
                    in_total += body.len() as u64;
                    out_total += compressed.len() as u64;
                }
            }
        }
        (times, in_total, out_total)
    };

    for _ in 0..warmup.max(1) {
        let _ = run(indices);
    }
    // passes; keep the fastest (least-interference) pass percentiles
    let mut best: Option<(Vec<u64>, u64, u64)> = None;
    for _ in 0..passes.max(1) {
        let r = run(indices);
        let mean = r.0.iter().sum::<u64>() as f64 / r.0.len() as f64;
        let best_mean = best
            .as_ref()
            .map(|(t, _, _)| t.iter().sum::<u64>() as f64 / t.len() as f64)
            .unwrap_or(f64::MAX);
        if mean < best_mean {
            best = Some(r);
        }
    }
    let (times, in_total, out_total) = best.expect("at least one pass");
    let mut times = times;
    times.sort_unstable();
    let n = times.len();
    let mean = times.iter().sum::<u64>() as f64 / n as f64;
    let in_bytes = in_total / passes.max(1) as u64;
    let out_bytes = out_total / passes.max(1) as u64;
    Stats {
        n,
        in_bytes,
        out_bytes,
        ratio: out_bytes as f64 / in_bytes.max(1) as f64,
        mean_ns: mean,
        p50_ns: times[n / 2] as f64,
        p95_ns: times[(n as f64 * 0.95) as usize % n] as f64,
        p99_ns: times[(n as f64 * 0.99) as usize % n] as f64,
        mibs: if mean > 0.0 {
            (in_bytes as f64 / mean) * 1000.0
        } else {
            0.0
        },
    }
}
