//! OPT-LIGHT-003 bake-off: section-local world-access fast paths, one
//! frozen REAL corpus, every candidate through the IDENTICAL kernel
//! (run_job_with_access). Semantic equality = identical DIFF_DIGEST.
//! Locality profile (\u00a720/\u00a721) comes from the instrumented direct
//! candidate. Output lines feed tools/optimization/render_results.py.

mod corpus_common;
use corpus_common::*;
use native_chunk::{ChunkKey, NativeChunk};
use rustcraft_ffi::get_registry;
use rustcraft_ffi::zero_stage_job::{run_job_with_access, WorldAccess};
use std::cell::RefCell;
use std::sync::{Arc, RwLock};

// ---------------- instrumentation wrapper (locality profile) ----------

/// Direct access + per-call locality counters (\u00a720/\u00a721).
struct ProfiledDirect {
    last: RefCell<Option<(i32, i32, usize)>>, // (cx, cz, sy)
    same_section: std::cell::Cell<u64>,
    same_chunk: std::cell::Cell<u64>,
    other_chunk: std::cell::Cell<u64>,
    total: std::cell::Cell<u64>,
    seen: RefCell<std::collections::HashSet<(i32, i32)>>,
    unique_chunk_touch: std::cell::Cell<u64>,
}

impl ProfiledDirect {
    fn new() -> Self {
        Self {
            last: RefCell::new(None),
            same_section: std::cell::Cell::new(0),
            same_chunk: std::cell::Cell::new(0),
            other_chunk: std::cell::Cell::new(0),
            total: std::cell::Cell::new(0),
            seen: RefCell::new(std::collections::HashSet::new()),
            unique_chunk_touch: std::cell::Cell::new(0),
        }
    }
    fn note(&self, cx: i32, cz: i32, sy: usize) {
        self.total.set(self.total.get() + 1);
        let mut l = self.last.borrow_mut();
        if let Some((lx, lz, lsy)) = *l {
            if lx == cx && lz == cz {
                if lsy == sy {
                    self.same_section.set(self.same_section.get() + 1);
                } else {
                    self.same_chunk.set(self.same_chunk.get() + 1);
                }
            } else {
                self.other_chunk.set(self.other_chunk.get() + 1);
            }
        }
        *l = Some((cx, cz, sy));
        drop(l);
        if self.seen.borrow_mut().insert((cx, cz)) {
            self.unique_chunk_touch
                .set(self.unique_chunk_touch.get() + 1);
        }
    }
}

impl WorldAccess for ProfiledDirect {
    fn name(&self) -> &'static str {
        "Z-profiled-direct"
    }
    fn chunk_generation(&self, dim: i32, cx: i32, cz: i32) -> Option<u64> {
        get_registry().chunk_generation(ChunkKey::new(dim, cx, cz))
    }
    fn block_light(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u8> {
        self.note(cx, cz, y >> 4);
        get_registry().get_block_light(ChunkKey::new(dim, cx, cz), x, y, z)
    }
    fn block_state(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u32> {
        self.note(cx, cz, y >> 4);
        get_registry().get_block_state(ChunkKey::new(dim, cx, cz), x, y, z)
    }
    fn set_block_light(
        &self,
        dim: i32,
        cx: i32,
        cz: i32,
        x: usize,
        y: usize,
        z: usize,
        v: u8,
    ) -> bool {
        get_registry().set_block_light(ChunkKey::new(dim, cx, cz), x, y, z, v)
    }
    fn mark_section_mutation(&self, dim: i32, cx: i32, cz: i32, sy: u8) {
        get_registry().mark_section_mutation(ChunkKey::new(dim, cx, cz), sy);
    }
}

// ---------------- B/C/D: chunk-handle caches (\u00a722-\u00a726) -------------

/// N-entry linear-scan cache of (cx,cz) -> chunk Arc; round-robin fill.
/// Hit path: no registry map lock, no hashing \u2014 just the chunk RwLock.
/// \u00a723 generation safety: the Arc identity is checked against the
/// registry ONLY on fill; stale-by-reload reads are caught by the job-end
/// revalidation contract (\u00a711), same as today's path.
struct ChunkCache<const N: usize> {
    label: &'static str,
    slots: RefCell<[(i32, i32); N]>,
    arcs: RefCell<[Option<Arc<RwLock<NativeChunk>>>; N]>,
    next: std::cell::Cell<usize>,
    hits: std::cell::Cell<u64>,
    lookups: std::cell::Cell<u64>,
    prefetch: std::cell::Cell<bool>,
}

impl<const N: usize> ChunkCache<N> {
    fn new(label: &'static str) -> Self {
        Self {
            label,
            slots: RefCell::new([(i32::MIN, i32::MIN); N]),
            arcs: RefCell::new(std::array::from_fn(|_| None)),
            next: std::cell::Cell::new(0),
            hits: std::cell::Cell::new(0),
            lookups: std::cell::Cell::new(0),
            prefetch: std::cell::Cell::new(false),
        }
    }
    fn with_prefetch(label: &'static str) -> Self {
        let s = Self::new(label);
        s.prefetch.set(true);
        s
    }
    fn resolve(&self, dim: i32, cx: i32, cz: i32) -> Option<Arc<RwLock<NativeChunk>>> {
        self.lookups.set(self.lookups.get() + 1);
        {
            let slots = self.slots.borrow();
            for i in 0..N {
                if slots[i] == (cx, cz) {
                    if let Some(a) = self.arcs.borrow()[i].clone() {
                        self.hits.set(self.hits.get() + 1);
                        return Some(a);
                    }
                }
            }
        }
        let arc = get_registry().chunk_arc(ChunkKey::new(dim, cx, cz))?;
        let i = self.next.get() % N;
        self.next.set(self.next.get() + 1);
        self.slots.borrow_mut()[i] = (cx, cz);
        self.arcs.borrow_mut()[i] = Some(arc.clone());
        Some(arc)
    }
}

impl<const N: usize> WorldAccess for ChunkCache<N> {
    fn name(&self) -> &'static str {
        self.label
    }
    fn chunk_generation(&self, dim: i32, cx: i32, cz: i32) -> Option<u64> {
        get_registry().chunk_generation(ChunkKey::new(dim, cx, cz))
    }
    fn block_light(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u8> {
        let arc = self.resolve(dim, cx, cz)?;
        let c = arc.read().ok()?;
        Some(c.get_block_light(x, y, z))
    }
    fn block_state(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u32> {
        let arc = self.resolve(dim, cx, cz)?;
        let c = arc.read().ok()?;
        Some(c.get_block_state(x, y, z))
    }
    fn set_block_light(
        &self,
        dim: i32,
        cx: i32,
        cz: i32,
        x: usize,
        y: usize,
        z: usize,
        v: u8,
    ) -> bool {
        let arc = match self.resolve(dim, cx, cz) {
            Some(a) => a,
            None => return false,
        };
        let mut c = match arc.write() {
            Ok(g) => g,
            Err(_) => return false,
        };
        c.set_block_light(x, y, z, v);
        true
    }
    fn mark_section_mutation(&self, dim: i32, cx: i32, cz: i32, sy: u8) {
        get_registry().mark_section_mutation(ChunkKey::new(dim, cx, cz), sy);
    }
    fn reset_job(&self, origin_cx: i32, origin_cz: i32) {
        if self.prefetch.get() {
            for dx in -1..=1i32 {
                for dz in -1..=1i32 {
                    let _ = self.resolve(0, origin_cx + dx, origin_cz + dz);
                }
            }
        }
    }
    fn access_stats(&self) -> (u64, u64) {
        (self.hits.get(), self.lookups.get())
    }
}

fn run_candidate<A: WorldAccess>(
    access: &A,
    loaded: &[LoadedChunk],
    dim: i32,
    jobs: &[(i32, i32, i32, bool)],
    table: &[u8],
) -> (u64, f64, f64, f64, f64, u64, u64) {
    let mut out = vec![0i32; 1 << 18];
    let mut samples: Vec<(u64, u64)> = Vec::with_capacity(jobs.len());
    let mut digest: u64 = 0x9E3779B97F4A7C15;
    for lc in loaded {
        prime_into_registry(lc, dim);
    }
    for (wi, &(wx, wy, wz, darken)) in jobs.iter().enumerate() {
        if darken {
            get_registry().mirror_block_state(
                ChunkKey::new(dim, wx >> 4, wz >> 4),
                (wx & 15) as usize,
                wy as usize,
                (wz & 15) as usize,
                0,
            );
        }
        let t = std::time::Instant::now();
        let rc = run_job_with_access(access, dim, wx, wy, wz, table, &mut out, 1 << 22, 64, false);
        let ns = t.elapsed().as_nanos() as u64;
        let rc_u = (rc as i64) as u64;
        let mut h: u64 = 0x9E3779B97F4A7C15;
        for i in 0..rc.max(0) as usize {
            for k in 0..4 {
                h = (h ^ (out[i * 4 + k] as u64)).wrapping_mul(0x100000001b3);
            }
        }
        digest = (digest ^ (h ^ rc_u)).wrapping_add((wi as u64).wrapping_mul(0x9E3779B97F4A7C15));
        samples.push((ns, rc_u));
    }
    samples.sort_unstable();
    let n = samples.len();
    let pct = |q: usize| samples[(n as f64 * q as f64 / 100.0) as usize].0;
    let mean = samples.iter().map(|s| s.0).sum::<u64>() as f64 / n as f64;
    let (hits, lookups) = access.access_stats();
    println!(
        "[bakeoff] {:<16} mean_ns={:.0} p50_ns={} p95_ns={} p99_ns={} digest={:016x} hits={}/{} ({:.1}%)",
        access.name(),
        mean,
        pct(50),
        pct(95),
        pct(99),
        digest,
        hits,
        lookups,
        if lookups > 0 { hits as f64 / lookups as f64 * 100.0 } else { 0.0 },
    );
    (
        digest,
        mean,
        pct(50) as f64,
        pct(95) as f64,
        pct(99) as f64,
        hits,
        lookups,
    )
}

#[test]
fn world_access_bakeoff() {
    let mca = corpus_mca();
    if !mca.is_file() {
        println!("[bakeoff] SKIP: corpus mca missing");
        return;
    }
    let dim = 78;
    let center = (14i32, 14i32);
    let mut loaded = Vec::new();
    for dx in -2..=2i32 {
        for dz in -2..=2i32 {
            if let Some(lc) = load_chunk(&mca, center.0 + dx, center.1 + dz) {
                loaded.push(lc);
            }
        }
    }
    let mut solve_states: Vec<u32> = Vec::new();
    for lc in &loaded {
        for &st in &lc.primer {
            if st != 0 && !solve_states.contains(&st) {
                solve_states.push(st);
            }
        }
    }
    let solve_table = build_table(&solve_states);
    solve_baseline_light(&mut loaded, &solve_table);
    let mut states = Vec::new();
    for lc in &loaded {
        for &s in &lc.primer {
            if s != 0 && !states.contains(&s) {
                states.push(s);
            }
        }
    }
    let table = build_table(&states);
    let mut jobs: Vec<(i32, i32, i32, bool)> = Vec::new();
    for lc in &loaded {
        let ring_ok = (lc.cx - center.0).abs() <= 1 && (lc.cz - center.1).abs() <= 1;
        if !ring_ok {
            continue;
        }
        for y in 0..256usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let pidx = (x << 12) | (z << 8) | y;
                    let s = lc.primer[pidx];
                    if s != 0 && light_props(s >> 4).1 > 0 {
                        let wx = lc.cx * 16 + x as i32;
                        let wz = lc.cz * 16 + z as i32;
                        jobs.push((wx, y as i32, wz, false));
                        jobs.push((wx, y as i32, wz, true));
                    }
                }
            }
        }
    }
    for (gx, gz) in [(0, 0), (8, 8), (16, 16), (24, 24), (-16, -8)] {
        jobs.push((center.0 * 16 + gx, 66, center.1 * 16 + gz, false));
    }
    println!(
        "[bakeoff] corpus: chunks={} jobs={}",
        loaded.len(),
        jobs.len()
    );

    // locality profile first (\u00a720/\u00a721)
    let prof = ProfiledDirect::new();
    let (pdigest, _, _, _, _, _, _) = run_candidate(&prof, &loaded, dim, &jobs, &table);
    let total = prof.total.get();
    println!(
        "[bakeoff] locality: same-section={:.1}% same-chunk={:.1}% other-chunk={:.1}% unique-chunks/job-set={}",
        prof.same_section.get() as f64 / total as f64 * 100.0,
        prof.same_chunk.get() as f64 / total as f64 * 100.0,
        prof.other_chunk.get() as f64 / total as f64 * 100.0,
        prof.unique_chunk_touch.get(),
    );

    // candidates (\u00a722)
    let direct = rustcraft_ffi::zero_stage_job::DirectAccess;
    let (base_digest, _, _, _, _, _, _) = run_candidate(&direct, &loaded, dim, &jobs, &table);
    assert_eq!(
        base_digest, pdigest,
        "profiled wrapper must not alter semantics"
    );

    let b = ChunkCache::<1usize>::new("B-last-chunk");
    let (d, _, _, _, _, _, _) = run_candidate(&b, &loaded, dim, &jobs, &table);
    assert_eq!(d, base_digest, "B semantic mismatch");

    for (n, label) in [(2usize, "C2"), (4, "C4"), (8, "C8"), (16, "C16")] {
        match n {
            2 => {
                let c = ChunkCache::<2>::new("C2-assoc");
                let (d, _, _, _, _, _, _) = run_candidate(&c, &loaded, dim, &jobs, &table);
                assert_eq!(d, base_digest, "C2 semantic mismatch");
            }
            4 => {
                let c = ChunkCache::<4>::new("C4-assoc");
                let (d, _, _, _, _, _, _) = run_candidate(&c, &loaded, dim, &jobs, &table);
                assert_eq!(d, base_digest, "C4 semantic mismatch");
            }
            8 => {
                let c = ChunkCache::<8>::new("C8-assoc");
                let (d, _, _, _, _, _, _) = run_candidate(&c, &loaded, dim, &jobs, &table);
                assert_eq!(d, base_digest, "C8 semantic mismatch");
            }
            _ => {
                let c = ChunkCache::<16>::new("C16-assoc");
                let (d, _, _, _, _, _, _) = run_candidate(&c, &loaded, dim, &jobs, &table);
                assert_eq!(d, base_digest, "C16 semantic mismatch");
            }
        }
    }

    let nb = ChunkCache::<9usize>::with_prefetch("D-neighbor3x3");
    let (d, _, _, _, _, _, _) = run_candidate(&nb, &loaded, dim, &jobs, &table);
    assert_eq!(d, base_digest, "D semantic mismatch");
}
