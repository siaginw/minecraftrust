//! §49 frontier/visited bake-off (audit ONLY — no production change).
//! Records the access stream of a light-shaped BFS over a 29³ box
//! (opacity field mirroring real cascades) and replays the SAME stream
//! against overlay/visited variants + frontier variants:
//!
//!   overlay: std HashMap<(i32,i32,i3),u8> (SipHash — current kernel)
//!            vs FxHash-style multiply-xor hasher (zero new deps)
//!            vs section-dense [u64;64] bitset + value side-table
//!   frontier: VecDeque<CellKey> (current) vs Vec + monotonic head
//!
//! Whole-traversal times on identical work; printed as audit evidence.

use std::collections::{HashMap, VecDeque};

/// FxHasher-style state (rustc-hash algorithm, re-implemented to keep the
/// audit dependency-free). Not for untrusted input — audit candidate only.
#[derive(Default, Clone, Copy)]
struct FxHasher {
    hash: u64,
}
const FX_SEED: u64 = 0x51_7c_c1_b7_27_22_0a_95;
impl FxHasher {
    #[inline(always)]
    fn add(&mut self, w: u64) {
        self.hash = (self.hash.rotate_left(5) ^ w).wrapping_mul(FX_SEED);
    }
}
impl std::hash::Hasher for FxHasher {
    fn finish(&self) -> u64 {
        self.hash
    }
    fn write(&mut self, bytes: &[u8]) {
        for chunk in bytes.chunks(8) {
            let mut buf = [0u8; 8];
            buf[..chunk.len()].copy_from_slice(chunk);
            self.add(u64::from_le_bytes(buf));
        }
    }
}
type FxBuild = std::hash::BuildHasherDefault<FxHasher>;

fn lcg(seed: &mut u64) -> u64 {
    *seed = seed
        .wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407);
    *seed >> 33
}

/// One recorded BFS step: the cell being written and its value.
struct Rec {
    x: i32,
    y: i32,
    z: i32,
    v: u8,
}

/// Light-shaped BFS over a 29³ box, ~30% opaque cells, values decay from
/// 14 by 1 per step (min 1); mirrors the cascade shape of real torch jobs
/// (a few thousand changed cells). Returns the write stream.
fn record_stream() -> (Vec<Rec>, Vec<(i32, i32, i32)>, usize) {
    let r = 14i32;
    let mut seed = 0xBADC0DEu64;
    let mut opaque = HashMap::new();
    for x in -r..=r {
        for y in -r..=r {
            for z in -r..=r {
                if lcg(&mut seed) % 100 < 30 {
                    opaque.insert((x, y, z), true);
                }
            }
        }
    }
    let mut frontier: VecDeque<(i32, i32, i32, u8)> = VecDeque::new();
    let mut seen: HashMap<(i32, i32, i32), u8> = HashMap::new();
    frontier.push_back((0, 0, 0, 14));
    let mut stream = Vec::new();
    let mut pushes = 0usize;
    while let Some((x, y, z, v)) = frontier.pop_front() {
        if v < 1 {
            continue;
        }
        if opaque.get(&(x, y, z)).copied().unwrap_or(false) && v != 14 {
            continue;
        }
        if let Some(&prev) = seen.get(&(x, y, z)) {
            if prev >= v {
                continue;
            }
        }
        seen.insert((x, y, z), v);
        stream.push(Rec { x, y, z, v });
        if v > 1 {
            for (dx, dy, dz) in [
                (1, 0, 0),
                (-1, 0, 0),
                (0, 1, 0),
                (0, -1, 0),
                (0, 0, 1),
                (0, 0, -1),
            ] {
                let (nx, ny, nz) = (x + dx, y + dy, z + dz);
                if nx.abs() <= r && ny.abs() <= r && nz.abs() <= r {
                    frontier.push_back((nx, ny, nz, v - 1));
                    pushes += 1;
                }
            }
        }
    }
    (stream, opaque.keys().copied().collect(), pushes)
}

fn bench_ns<F: FnMut()>(name: &str, iters: usize, mut f: F) {
    for _ in 0..iters / 10 + 1 {
        f();
    }
    let mut samples = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t = std::time::Instant::now();
        f();
        samples.push(t.elapsed().as_nanos() as f64 / 1000.0);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = samples.len();
    println!(
        "[bakeoff] {:<42} p50={:>8.1}us p95={:>8.1}us mean={:>8.1}us",
        name,
        samples[n / 2],
        samples[(n as f64 * 0.95) as usize],
        samples.iter().sum::<f64>() / n as f64
    );
}

#[test]
fn frontier_visited_bakeoff() {
    let (stream, _opaque, pushes) = record_stream();
    println!(
        "[bakeoff] stream: {} writes, {} pushes",
        stream.len(),
        pushes
    );

    // --- overlay variants (write+read-back per record, replay-identical) ---
    bench_ns("overlay std HashMap (SipHash, current)", 200, || {
        let mut m: HashMap<(i32, i32, i32), u8> = HashMap::with_capacity(4096);
        for rec in &stream {
            m.insert((rec.x, rec.y, rec.z), rec.v);
            let _ = m.get(&(rec.x, rec.y, rec.z));
        }
        std::hint::black_box(m.len());
    });
    bench_ns("overlay FxHash HashMap", 200, || {
        let mut m: HashMap<(i32, i32, i32), u8, FxBuild> =
            HashMap::with_capacity_and_hasher(4096, FxBuild::default());
        for rec in &stream {
            m.insert((rec.x, rec.y, rec.z), rec.v);
            let _ = m.get(&(rec.x, rec.y, rec.z));
        }
        std::hint::black_box(m.len());
    });
    // section-dense: [u64;64] presence bitset per section + flat value vec
    bench_ns("overlay section-dense bitset + values", 200, || {
        let mut secs: HashMap<(i32, i32), (Box<[u64; 64]>, Vec<(u16, u8)>)> = HashMap::new();
        for rec in &stream {
            let (cx, cz) = (rec.x.div_euclid(16), rec.z.div_euclid(16));
            let sec = secs
                .entry((cx, cz))
                .or_insert_with(|| (Box::new([0u64; 64]), Vec::with_capacity(64)));
            let idx = ((rec.y as usize & 15) << 8)
                | ((rec.z.rem_euclid(16) as usize) << 4)
                | (rec.x.rem_euclid(16) as usize);
            if sec.0[idx >> 6] & (1u64 << (idx & 63)) == 0 {
                sec.0[idx >> 6] |= 1u64 << (idx & 63);
                sec.1.push((idx as u16, rec.v));
            }
        }
        std::hint::black_box(secs.len());
    });

    // --- frontier variants: replay push order, then drain fully ---
    bench_ns("frontier VecDeque (current)", 200, || {
        let mut q: VecDeque<(i32, i32, i32, u8)> = VecDeque::with_capacity(1024);
        let mut v = 14u8;
        for rec in &stream {
            for _ in 0..6 {
                q.push_back((rec.x, rec.y, rec.z, v));
            }
            v = if v <= 1 { 14 } else { v - 1 };
        }
        while let Some(_e) = q.pop_front() {
            std::hint::black_box(_e);
        }
    });
    bench_ns("frontier Vec + monotonic head", 200, || {
        let mut q: Vec<(i32, i32, i32, u8)> = Vec::with_capacity(1024);
        let mut head = 0usize;
        let mut v = 14u8;
        for rec in &stream {
            for _ in 0..6 {
                q.push((rec.x, rec.y, rec.z, v));
            }
            v = if v <= 1 { 14 } else { v - 1 };
        }
        while head < q.len() {
            let _e = q[head];
            head += 1;
            std::hint::black_box(_e);
        }
    });
}
