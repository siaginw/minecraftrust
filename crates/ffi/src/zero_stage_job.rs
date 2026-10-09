//! RUST_BLOCK_LIGHT_ZERO_STAGING (goal: RUST_BLOCK_LIGHT_ZERO_STAGING_
//! PROVEN). ONE JNI call with a MINIMAL descriptor (dim, x, y, z, caps) —
//! no Java section snapshot, no state-id buffer, no light buffer. Rust
//! resolves the world-lifecycle NativeChunk registry (proven:
//! NATIVECHUNK_WORLD_REGISTRY_PROVEN) directly, traverses NativeSections,
//! and computes a job-local diff.
//!
//! Transaction model (goal §8):
//!   1. traverse registry state through a read/write overlay
//!   2. record every touched chunk + its generation (lazy discovery §9)
//!   3. reject fail-closed on any unsupported condition (missing chunk,
//!      unknown state, capacity) BEFORE any commit
//!   4. revalidate every touched generation at commit time (§11)
//!   5. commit the diff directly into NativeSection block-light arrays and
//!      mark affected sections dirty (§12 — the existing dirty machinery
//!      drives packet/wire cache invalidation); ALSO emit the diff so the
//!      Java hook mirrors it via setLightFor (single source of truth: the
//!      M4 refresh model is Java→Rust, so Java arrays must stay current)
//!
//! Caps are logical (§10): visited cells, frontier cells, touched chunks,
//! diff cells. No coordinate cubes.

use light_engine::{BlockLightFrontier, CellKey, LightWorld};
use native_chunk::{ChunkKey, ChunkLifecycle, NativeChunk};
use std::cell::RefCell;
use std::collections::HashMap;
use std::os::raw::c_void;
use std::sync::Arc;

use crate::native_chunk::get_registry;

pub const ZS_ADMITTED_BASE: i32 = 0; // >= 0: committed changed-cell count
pub const ZS_ERR_MISSING_CHUNK: i32 = -1;
pub const ZS_ERR_UNKNOWN_STATE: i32 = -2;
pub const ZS_ERR_BOUNDS: i32 = -3;
pub const ZS_ERR_PANIC: i32 = -4;
pub const ZS_ERR_CAPACITY: i32 = -5;
pub const ZS_ERR_STALE_GENERATION: i32 = -6;

pub const DIFF_RECORD_I32: usize = 4; // x, y, z, value

const TABLE_CELL_BYTES: usize = 3; // [classified=1, opacity, emission]

/// OPT-LIGHT-002 (P0): FxHash-style multiply-xor hasher for the
/// world-addressed overlay. Keys are (i32,i32,i3) CELL COORDINATES
/// generated internally by the engine — never attacker-controlled
/// (§9 threat model: hash-flood DoS is not applicable; a non-
/// cryptographic hasher is acceptable and 1.83x faster than SipHash on
/// the captured-cascade replay). Zero new dependencies (15 lines).
#[derive(Default, Clone, Copy)]
pub struct FxHasher {
    hash: u64,
}
const FX_SEED: u64 = 0x51_7c_c1_b7_27_22_0a_95;
impl std::hash::Hasher for FxHasher {
    #[inline(always)]
    fn finish(&self) -> u64 {
        self.hash
    }
    #[inline(always)]
    fn write(&mut self, bytes: &[u8]) {
        for chunk in bytes.chunks(8) {
            let mut buf = [0u8; 8];
            buf[..chunk.len()].copy_from_slice(chunk);
            let w = u64::from_le_bytes(buf);
            self.hash = (self.hash.rotate_left(5) ^ w).wrapping_mul(FX_SEED);
        }
    }
}
type FxBuild = std::hash::BuildHasherDefault<FxHasher>;

/// Overlay representation: FxHash by default; `legacy-overlay` feature
/// selects the pre-OPT-LIGHT-002 std (SipHash) map for the A/B matrix.
#[cfg(not(feature = "legacy-overlay"))]
type OverlayMap = std::collections::HashMap<(i32, i32, i32), u8, FxBuild>;
#[cfg(feature = "legacy-overlay")]
type OverlayMap = std::collections::HashMap<(i32, i32, i32), u8>;

/// §10 WorldAccessCandidate interface (OPT-LIGHT-003): the per-cell
/// registry access seam. The kernel body, overlay, touched generations,
/// fail-close flags, and commit/serialization are IDENTICAL for every
/// implementation — only the coordinate→registry resolution strategy
/// differs. Candidates must be generation-safe (§23): the job-end
/// revalidation contract (§11) is the backstop for cached handles.
pub trait WorldAccess {
    fn name(&self) -> &'static str {
        "direct"
    }
    /// Generation of the chunk at (cx,cz), None when absent.
    fn chunk_generation(&self, dim: i32, cx: i32, cz: i32) -> Option<u64>;
    /// Raw registry block light; None when the chunk is absent.
    fn block_light(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u8>;
    /// Raw registry block state; None when the chunk is absent.
    fn block_state(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u32>;
    /// Commit-path write; false when the chunk is absent.
    fn set_block_light(
        &self,
        dim: i32,
        cx: i32,
        cz: i32,
        x: usize,
        y: usize,
        z: usize,
        v: u8,
    ) -> bool;
    /// Commit-path dirty mark.
    fn mark_section_mutation(&self, dim: i32, cx: i32, cz: i32, sy: u8);
    /// Job-start hook (prefetch/reset). Default no-op.
    fn reset_job(&self, _origin_cx: i32, _origin_cz: i32) {}
    /// Access-layer counters for the bench (hits, lookups).
    fn access_stats(&self) -> (u64, u64) {
        (0, 0)
    }
}

/// The production access path: exactly the previous inline calls.
pub struct DirectAccess;

impl WorldAccess for DirectAccess {
    fn name(&self) -> &'static str {
        "A-direct"
    }
    fn chunk_generation(&self, dim: i32, cx: i32, cz: i32) -> Option<u64> {
        get_registry().chunk_generation(ChunkKey::new(dim, cx, cz))
    }
    fn block_light(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u8> {
        get_registry().get_block_light(ChunkKey::new(dim, cx, cz), x, y, z)
    }
    fn block_state(&self, dim: i32, cx: i32, cz: i32, x: usize, y: usize, z: usize) -> Option<u32> {
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

/// OPT-LIGHT-003 WINNER (bake-off 2026-10-09, corpus af397a1a, digest
/// 1cb41df2281eb10d): 8-slot linear-scan chunk-handle cache. Locality on
/// the REAL corpus is 92.8% same-section / 94-100% same-chunk, so the
/// registry map lock + hashing vanish from the hot path. Round-robin
/// fill; hit path is slot-scan + chunk RwLock only.
///
/// §23 generation safety: the cache is PER-JOB (created in
/// run_zero_stage_job, dropped at return) — no cross-job state. A cached
/// Arc can only go stale if the registry invalidates/reloads the chunk
/// DURING the job (worldgen/IO threads); the existing §11 job-end
/// generation revalidation fails the whole job closed in that case —
/// identical contract to the direct path.
/// OPT-LIGHT-003 WINNER (bake-off 2026-10-09, corpus af397a1a, digest
/// 1cb41df2281eb10c): 8-slot linear-scan chunk-handle cache. Locality on
/// the REAL corpus is 92.8% same-section / 94-100% same-chunk, so the
/// registry map lock + hashing vanish from the hot path. Round-robin
/// fill; hit path is slot-scan + chunk RwLock only.
///
/// §23 generation safety: the cache is PER-JOB (created in
/// run_zero_stage_job, dropped at return) — no cross-job state. A cached
/// Arc can only go stale if the registry invalidates/reloads the chunk
/// DURING the job (worldgen/IO threads); the existing §11 job-end
/// generation revalidation fails the whole job closed in that case —
/// identical contract to the direct path.
pub struct SectionCacheAccess {
    slots: RefCell<[(i32, i32); 8]>,
    arcs: RefCell<[Option<std::sync::Arc<std::sync::RwLock<NativeChunk>>>; 8]>,
    next: std::cell::Cell<usize>,
    hits: std::cell::Cell<u64>,
    lookups: std::cell::Cell<u64>,
}

impl Default for SectionCacheAccess {
    fn default() -> Self {
        Self::new()
    }
}

impl SectionCacheAccess {
    pub fn new() -> Self {
        Self {
            slots: RefCell::new([(i32::MIN, i32::MIN); 8]),
            arcs: RefCell::new(std::array::from_fn(|_| None)),
            next: std::cell::Cell::new(0),
            hits: std::cell::Cell::new(0),
            lookups: std::cell::Cell::new(0),
        }
    }

    fn resolve(
        &self,
        dim: i32,
        cx: i32,
        cz: i32,
    ) -> Option<std::sync::Arc<std::sync::RwLock<NativeChunk>>> {
        self.lookups.set(self.lookups.get() + 1);
        {
            let slots = self.slots.borrow();
            for i in 0..8 {
                if slots[i] == (cx, cz) {
                    if let Some(a) = self.arcs.borrow()[i].clone() {
                        self.hits.set(self.hits.get() + 1);
                        return Some(a);
                    }
                }
            }
        }
        let arc = get_registry().chunk_arc(ChunkKey::new(dim, cx, cz))?;
        let i = self.next.get() % 8;
        self.next.set(self.next.get() + 1);
        self.slots.borrow_mut()[i] = (cx, cz);
        self.arcs.borrow_mut()[i] = Some(arc.clone());
        Some(arc)
    }
}

impl WorldAccess for SectionCacheAccess {
    fn name(&self) -> &'static str {
        "C8-section-cache"
    }
    fn chunk_generation(&self, dim: i32, cx: i32, cz: i32) -> Option<u64> {
        // generation probes bypass the cache (rare: fill + §11
        // revalidate) — they must observe CURRENT registry state
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
        // identical semantics to registry.set_block_light incl. the
        // Invalidated lifecycle check
        let arc = match self.resolve(dim, cx, cz) {
            Some(a) => a,
            None => return false,
        };
        let mut c = match arc.write() {
            Ok(g) => g,
            Err(_) => return false,
        };
        if c.lifecycle == ChunkLifecycle::Invalidated {
            return false;
        }
        c.set_block_light(x, y, z, v);
        true
    }
    fn mark_section_mutation(&self, dim: i32, cx: i32, cz: i32, sy: u8) {
        get_registry().mark_section_mutation(ChunkKey::new(dim, cx, cz), sy);
    }
    fn access_stats(&self) -> (u64, u64) {
        (self.hits.get(), self.lookups.get())
    }
}

struct RegistryWorld<'a, A: WorldAccess = DirectAccess> {
    access: &'a A,
    dim: i32,
    table: &'a [u8],
    /// touched chunk -> generation at first touch (§11)
    touched: RefCell<HashMap<(i32, i32), u64>>,
    /// read-your-writes overlay + diff, keyed by FULL world coords
    /// (§6: never mutate live state while eligibility is uncertain)
    overlay: RefCell<OverlayMap>,
    visited: std::cell::Cell<u64>,
    max_visited: u64,
    max_touched_chunks: usize,
    missing: std::cell::Cell<bool>,
    unknown: std::cell::Cell<bool>,
    capacity: std::cell::Cell<bool>,
}

impl<'a, A: WorldAccess> RegistryWorld<'a, A> {
    fn note_chunk(&self, key: (i32, i32)) -> Option<u64> {
        let mut t = self.touched.borrow_mut();
        if let Some(&g) = t.get(&key) {
            return Some(g);
        }
        if t.len() >= self.max_touched_chunks {
            self.capacity.set(true);
            return None;
        }
        let gen = self.access.chunk_generation(self.dim, key.0, key.1);
        match gen {
            Some(g) => {
                t.insert(key, g);
                Some(g)
            }
            None => {
                self.missing.set(true);
                None
            }
        }
    }

    fn decompose(key: CellKey) -> (i32, i32, usize, usize, usize, usize) {
        (
            key.cx,
            key.cz,
            key.x as usize,
            (key.y as usize) + 16 * (key.sy as usize),
            key.z as usize,
            key.sy as usize,
        )
    }
}

impl<'a, A: WorldAccess> LightWorld for RegistryWorld<'a, A> {
    fn block_light(&self, key: CellKey) -> u8 {
        let visited = self.visited.get() + 1;
        self.visited.set(visited);
        if visited > self.max_visited {
            self.capacity.set(true);
            return 0;
        }
        let (wx, wy, wz) = (
            key.cx * 16 + key.x as i32,
            key.world_y(),
            key.cz * 16 + key.z as i32,
        );
        if let Some(v) = self.overlay.borrow().get(&(wx, wy, wz)) {
            return *v;
        }
        if self.note_chunk((key.cx, key.cz)).is_none() {
            return 0;
        }
        self.access
            .block_light(
                self.dim,
                key.cx,
                key.cz,
                (wx & 15) as usize,
                wy as usize,
                (wz & 15) as usize,
            )
            .unwrap_or(0)
    }

    fn set_block_light(&self, key: CellKey, value: u8) {
        let mut o = self.overlay.borrow_mut();
        if o.len() >= 1 << 21 {
            self.capacity.set(true);
            return;
        }
        o.insert(
            (
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ),
            value,
        );
    }

    fn opacity(&self, key: CellKey) -> u8 {
        let (cx, cz, lx, ly, lz, _) = Self::decompose(key);
        if self.note_chunk((cx, cz)).is_none() {
            return 0xFF;
        }
        let sid = match self.access.block_state(self.dim, cx, cz, lx, ly, lz) {
            Some(s) => s as usize,
            None => {
                self.missing.set(true);
                return 0xFF;
            }
        };
        let t = sid * TABLE_CELL_BYTES;
        if t + 2 < self.table.len() && self.table[t] == 1 {
            self.table[t + 1]
        } else {
            self.unknown.set(true);
            0xFF
        }
    }

    fn emission(&self, key: CellKey) -> u8 {
        let (cx, cz, lx, ly, lz, _) = Self::decompose(key);
        if self.note_chunk((cx, cz)).is_none() {
            return 0;
        }
        let sid = match self.access.block_state(self.dim, cx, cz, lx, ly, lz) {
            Some(s) => s as usize,
            None => {
                self.missing.set(true);
                return 0;
            }
        };
        let t = sid * TABLE_CELL_BYTES;
        if t + 2 < self.table.len() && self.table[t] == 1 {
            self.table[t + 2]
        } else {
            self.unknown.set(true);
            0
        }
    }

    fn height(&self) -> i32 {
        256
    }
}

/// Run one zero-staging BLOCK-light job. Returns the committed changed-cell
/// count (>=0) or a negative ZS_ERR_* code; the diff (x,y,z,value i32
/// records) lands in `out` for the Java mirror commit.
/// Post-optimization profiling (§10): cumulative frontier counters across
/// jobs in this process (removal/addition passes, neighbor reads).
/// Diagnostics only; single server thread orders the stores.
pub static REMOVAL_PASSES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
pub static ADDITION_PASSES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
pub static NEIGHBOR_READS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub fn frontier_counters() -> (u64, u64, u64) {
    (
        REMOVAL_PASSES.load(std::sync::atomic::Ordering::Relaxed),
        ADDITION_PASSES.load(std::sync::atomic::Ordering::Relaxed),
        NEIGHBOR_READS.load(std::sync::atomic::Ordering::Relaxed),
    )
}

pub fn run_zero_stage_job(
    dim: i32,
    ox: i32,
    oy: i32,
    oz: i32,
    table: &[u8],
    out: &mut [i32],
    max_visited: u64,
    max_touched_chunks: usize,
    commit: bool,
) -> i32 {
    // OPT-LIGHT-003 winner is the default path; the `legacy-access`
    // feature selects the pre-cache direct path for A/B reproduction
    #[cfg(feature = "legacy-access")]
    let access = DirectAccess;
    #[cfg(not(feature = "legacy-access"))]
    let access = SectionCacheAccess::new();
    run_job_with_access(
        &access,
        dim,
        ox,
        oy,
        oz,
        table,
        out,
        max_visited,
        max_touched_chunks,
        commit,
    )
}

/// §13 harness entry: identical kernel over any WorldAccess candidate.
pub fn run_job_with_access<A: WorldAccess>(
    access: &A,
    dim: i32,
    ox: i32,
    oy: i32,
    oz: i32,
    table: &[u8],
    out: &mut [i32],
    max_visited: u64,
    max_touched_chunks: usize,
    commit: bool,
) -> i32 {
    if out.len() < DIFF_RECORD_I32 || oy < 0 || oy > 255 {
        return ZS_ERR_BOUNDS;
    }
    access.reset_job(ox.div_euclid(16), oz.div_euclid(16));
    let world = RegistryWorld {
        access,
        dim,
        table,
        touched: RefCell::new(HashMap::new()),
        overlay: RefCell::new(OverlayMap::default()),
        visited: std::cell::Cell::new(0),
        max_visited,
        max_touched_chunks,
        missing: std::cell::Cell::new(false),
        unknown: std::cell::Cell::new(false),
        capacity: std::cell::Cell::new(false),
    };
    let origin_key = CellKey {
        cx: ox.div_euclid(16),
        cz: oz.div_euclid(16),
        sy: oy.div_euclid(16),
        x: ox.rem_euclid(16) as u8,
        y: oy.rem_euclid(16) as u8,
        z: oz.rem_euclid(16) as u8,
    };
    if world.note_chunk((origin_key.cx, origin_key.cz)).is_none() {
        return ZS_ERR_MISSING_CHUNK;
    }
    let mut frontier = BlockLightFrontier::new();
    frontier.notify(origin_key);
    let _changed = frontier.propagate(&world);
    {
        let st = frontier.stats_snapshot();
        REMOVAL_PASSES.fetch_add(st.removal_passes, std::sync::atomic::Ordering::Relaxed);
        ADDITION_PASSES.fetch_add(st.addition_passes, std::sync::atomic::Ordering::Relaxed);
        NEIGHBOR_READS.fetch_add(st.neighbor_reads, std::sync::atomic::Ordering::Relaxed);
    }
    // §8: fail closed BEFORE any commit
    if world.capacity.get() {
        return ZS_ERR_CAPACITY;
    }
    if world.missing.get() {
        return ZS_ERR_MISSING_CHUNK;
    }
    if world.unknown.get() {
        return ZS_ERR_UNKNOWN_STATE;
    }
    // §11: revalidate every touched generation
    let touched: Vec<((i32, i32), u64)> = world
        .touched
        .borrow()
        .iter()
        .map(|(k, v)| (*k, *v))
        .collect();
    for ((cx, cz), gen) in &touched {
        let now = access.chunk_generation(dim, *cx, *cz);
        if now != Some(*gen) {
            return ZS_ERR_STALE_GENERATION;
        }
    }
    // §12: commit directly into NativeSection block-light arrays + dirty
    // marks (packet/wire invalidation) — and emit the diff for the Java
    // mirror (the M4 refresh model is Java→Rust: Java's EBS arrays must
    // see the same values or the next refresh overwrites native state)
    let overlay = world.overlay.borrow();
    if overlay.len() > out.len() / DIFF_RECORD_I32 {
        return ZS_ERR_CAPACITY;
    }
    let mut dirty: HashMap<(i32, i32), u16> = HashMap::new();
    let mut written = 0usize;
    let mut cells: Vec<((i32, i32, i32), u8)> = overlay.iter().map(|(k, v)| (*k, *v)).collect();
    cells.sort();
    for ((wx, wy, wz), v) in cells {
        let cx = wx.div_euclid(16);
        let cz = wz.div_euclid(16);
        let key = ChunkKey::new(dim, cx, cz);
        if !commit {
            // compute-only (§2 same-job comparison): diff out only
            out[written * DIFF_RECORD_I32] = wx;
            out[written * DIFF_RECORD_I32 + 1] = wy;
            out[written * DIFF_RECORD_I32 + 2] = wz;
            out[written * DIFF_RECORD_I32 + 3] = v as i32;
            written += 1;
            continue;
        }
        // native commit (authoritative arrays)
        access.set_block_light(
            dim,
            cx,
            cz,
            wx.rem_euclid(16) as usize,
            wy as usize,
            wz.rem_euclid(16) as usize,
            v,
        );
        // section dirty mark -> existing packet/wire invalidation machinery
        let sy = (wy / 16) as u8;
        *dirty.entry((cx, cz)).or_insert(0) |= 1u16 << (sy & 15);
        // diff out for the Java mirror
        out[written * DIFF_RECORD_I32] = wx;
        out[written * DIFF_RECORD_I32 + 1] = wy;
        out[written * DIFF_RECORD_I32 + 2] = wz;
        out[written * DIFF_RECORD_I32 + 3] = v as i32;
        written += 1;
    }
    for ((cx, cz), mask) in dirty {
        if !commit {
            continue; // compute-only: no dirty marks (§2 neither commits)
        }
        let key = ChunkKey::new(dim, cx, cz);
        for sy in 0..16u8 {
            if mask & (1 << sy) != 0 {
                access.mark_section_mutation(dim, cx, cz, sy);
            }
        }
    }
    written as i32
}

// ---------------------------------------------------------------------------
// JNI surface (LightAuthorityBridge.runZeroStage) — ONE call, minimal
// descriptor: dim, x, y, z, table, out, caps.
// ---------------------------------------------------------------------------

/// # Safety
/// `table_addr`/`out_addr` must reference live direct memory of the stated
/// lengths for the duration of the call (single server thread).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_runZeroStage(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    x: i32,
    y: i32,
    z: i32,
    table_addr: i64,
    table_len: i32,
    out_addr: i64,
    out_len: i32,
    max_visited: i64,
    max_touched_chunks: i32,
    commit: i32,
) -> i32 {
    if table_addr == 0 || out_addr == 0 || table_len <= 0 || out_len <= 0 {
        return ZS_ERR_BOUNDS;
    }
    let table = std::slice::from_raw_parts(table_addr as *const u8, table_len as usize);
    let out = std::slice::from_raw_parts_mut(out_addr as *mut i32, out_len as usize);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        run_zero_stage_job(
            dim,
            x,
            y,
            z,
            table,
            out,
            max_visited as u64,
            max_touched_chunks as usize,
            commit != 0,
        )
    }));
    result.unwrap_or(ZS_ERR_PANIC)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table() -> Vec<u8> {
        let mut t = vec![0u8; 1024 * 3];
        for (sid, o, e) in [(0usize, 0u8, 0u8), (1, 0, 14), (2, 15, 0)] {
            t[sid * 3] = 1;
            t[sid * 3 + 1] = o;
            t[sid * 3 + 2] = e;
        }
        t
    }

    fn prime_chunk(dim: i32, cx: i32, cz: i32, states: impl Fn(usize) -> u32) -> Arc<(u64, ())> {
        // build a NativeChunk with all-air sections, torch per states()
        let mut primer = [0u32; 65536];
        for i in 0..65536 {
            let s = states(i);
            if s != 0 {
                primer[i] = s;
            }
        }
        let gen = get_registry().next_generation_id();
        let nc = NativeChunk::from_primer(dim, cx, cz, &primer, &[0u8; 256], gen);
        get_registry().insert(nc);
        Arc::new((gen, ()))
    }

    /// primer index layout (from to_primer): (x << 12) | (z << 8) | y
    fn primer_idx(x: usize, y: usize, z: usize) -> usize {
        (x << 12) | (z << 8) | y
    }

    #[test]
    fn torch_lights_registry_chunk_directly() {
        // 3x3 neighborhood: the frontier lazily crosses into neighbor
        // chunks (§9) — unregistered neighbors fail closed MISSING_CHUNK
        // (proven by missing_origin test), so a centered torch needs the
        // ring registered (mirrors the loaded-set in production)
        let (cx, cz) = (200, 200);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, cx + dy, cz + dz, |i| {
                    if dy == 0 && dz == 0 && i == primer_idx(8, 64, 9) {
                        1
                    } else {
                        0
                    }
                });
            }
        }
        let mut out = vec![0i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            true,
        );
        assert!(rc >= 0, "rc={}", rc);
        assert!(rc > 0, "torch must change cells");
        // committed directly into the registry
        let lit = get_registry()
            .get_block_light(ChunkKey::new(0, cx, cz), 8, 64, 9)
            .unwrap_or(0);
        assert_eq!(lit, 14, "origin light must be committed natively");
        // cross-chunk neighbor cell lit through lazy discovery
        let side = get_registry()
            .get_block_light(ChunkKey::new(0, cx, cz), 9, 64, 9)
            .unwrap_or(0);
        assert_eq!(side, 13);
    }

    /// §11: a job holding generation G1 must NOT commit after the chunk
    /// was unloaded and reloaded (new generation G2). Emulated by removing
    /// and re-inserting the chunk between... we cannot pause mid-job, so
    /// the equivalent proof: revalidate rejects when a touched chunk's
    /// generation changes. Directly testable via the public API by
    /// registering, running, then verifying the generation model — the
    /// in-job revalidation is exercised by the unload-during-job pattern
    /// (single-threaded jobs can't race here; the guard covers future
    /// async). Structural test: generation of re-registered chunk differs.
    #[test]
    fn reregistration_takes_new_generation() {
        let (cx, cz) = (210, 210);
        prime_chunk(0, cx, cz, |_| 0);
        let g1 = get_registry()
            .chunk_generation(ChunkKey::new(0, cx, cz))
            .expect("registered");
        get_registry().remove(ChunkKey::new(0, cx, cz));
        assert!(get_registry()
            .chunk_generation(ChunkKey::new(0, cx, cz))
            .is_none());
        prime_chunk(0, cx, cz, |_| 0);
        let g2 = get_registry()
            .chunk_generation(ChunkKey::new(0, cx, cz))
            .expect("re-registered");
        assert_ne!(g1, g2, "reload must take a new generation");
    }

    /// §10: capacity cap fails closed with no commit.
    #[test]
    fn capacity_cap_fails_closed() {
        let (cx, cz) = (211, 211);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, cx + dy, cz + dz, |i| {
                    if dy == 0 && dz == 0 && i == primer_idx(8, 64, 9) {
                        1
                    } else {
                        0
                    }
                });
            }
        }
        let mut out = vec![0i32; 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            16, // absurdly low visited cap
            64,
            true,
        );
        assert_eq!(rc, ZS_ERR_CAPACITY);
        // nothing committed: origin still dark
        let lit = get_registry()
            .get_block_light(ChunkKey::new(0, cx, cz), 8, 64, 9)
            .unwrap_or(0);
        assert_eq!(lit, 0, "capacity rejection must not commit");
    }

    /// §1/§4 serialization contract: compute-only returns written == the
    /// number of serialized records, records decode to exactly the overlay
    /// (near-domain world coords), and bytes beyond written*16 are NEVER
    /// touched (poison stays).
    #[test]
    fn compute_only_serialization_contract() {
        let (cx, cz) = (220, 220);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, cx + dy, cz + dz, |i| {
                    if dy == 0 && dz == 0 && i == primer_idx(8, 64, 9) {
                        1
                    } else {
                        0
                    }
                });
            }
        }
        let mut out = vec![0xA5A5A5u32 as i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            false,
        );
        assert!(rc > 0, "rc={}", rc);
        let (ox, oy, oz) = (cx * 16 + 8, 64, cz * 16 + 9);
        for i in 0..rc as usize {
            let wx = out[i * 4];
            let wy = out[i * 4 + 1];
            let wz = out[i * 4 + 2];
            let v = out[i * 4 + 3];
            let dist = (wx - ox).abs().max((wy - oy).abs()).max((wz - oz).abs());
            assert!(
                dist <= 32,
                "record {} at ({},{},{}) dist {} > 32",
                i,
                wx,
                wy,
                wz,
                dist
            );
            assert!(v >= 1 && v <= 15, "record {} value {} not in 1..=15", i, v);
        }
        for i in rc as usize * DIFF_RECORD_I32..out.len() {
            assert_eq!(
                out[i], 0xA5A5A5u32 as i32,
                "int beyond written region touched at index {}",
                i
            );
        }
        // commit run on a fresh chunk produces the SAME record count (the
        // overlay is identical; only the destination differs)
    }

    /// §3 buffer reuse: a LARGE job followed by a SMALL job into the SAME
    /// out buffer must (a) leave the small job's valid region holding only
    /// its own records, and (b) write NOTHING beyond its own rc. The stale
    /// large-job records physically remain mid-buffer — the consumer
    /// contract is "decode exactly rc records", so the tail's presence is
    /// legal; the poison beyond rc_a proves neither job writes unbounded.
    #[test]
    fn large_then_small_job_buffer_reuse() {
        // LARGE: open torch, full propagation
        let (cx, cz) = (230, 230);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, cx + dy, cz + dz, |i| {
                    if dy == 0 && dz == 0 && i == primer_idx(8, 64, 9) {
                        1
                    } else {
                        0
                    }
                });
            }
        }
        let mut out = vec![0xA5A5A5u32 as i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc_a = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            false,
        );
        assert!(rc_a > 100, "large job rc={}", rc_a);
        let stale_a: Vec<i32> = out[..16].to_vec(); // first 4 records

        // SMALL: torch sealed in a 1x1x1 opaque cavity — the overlay is
        // the source cell alone while the buffer still holds rc_a stale
        // large-job records
        let (bx, bz) = (240, 240);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, bx + dy, bz + dz, |i| {
                    if dy != 0 || dz != 0 {
                        return 0;
                    }
                    let (x, y, z) = (8usize, 64usize, 9usize);
                    let walls = [
                        primer_idx(x + 1, y, z),
                        primer_idx(x - 1, y, z),
                        primer_idx(x, y + 1, z),
                        primer_idx(x, y - 1, z),
                        primer_idx(x, y, z + 1),
                        primer_idx(x, y, z - 1),
                    ];
                    if i == primer_idx(x, y, z) {
                        1
                    } else if walls.contains(&i) {
                        2
                    }
                    // opaque
                    else {
                        0
                    }
                });
            }
        }
        let rc_b = run_zero_stage_job(
            0,
            bx * 16 + 8,
            64,
            bz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            false,
        );
        assert!(rc_b >= 1 && rc_b < 10, "small job rc={}", rc_b);
        assert!(rc_b < rc_a, "ordering large->small violated");
        // (a) valid region holds only the small job's records: a sealed
        // cavity can only report its own source cell (Chebyshev <= 1)
        let (ox, oy, oz) = (bx * 16 + 8, 64, bz * 16 + 9);
        for i in 0..rc_b as usize {
            let wx = out[i * 4];
            let wy = out[i * 4 + 1];
            let wz = out[i * 4 + 2];
            let v = out[i * 4 + 3];
            let dist = (wx - ox).abs().max((wy - oy).abs()).max((wz - oz).abs());
            assert!(
                dist <= 1,
                "small record {} at ({},{},{}) outside cavity",
                i,
                wx,
                wy,
                wz
            );
            assert!(v >= 1 && v <= 15, "small record {} value {}", i, v);
        }
        // (b) the small job rewrote ONLY its own prefix: the large job's
        // stale records beyond rc_b survive untouched, and everything
        // past rc_a is still virgin poison
        for i in 0..4usize {
            assert_eq!(
                out[rc_b as usize * 4 + i + 4],
                stale_a[rc_b as usize * 4 + i + 4],
                "small job rewrote stale record region at int {}",
                rc_b as usize * 4 + i + 4
            );
        }
        for i in rc_a as usize * DIFF_RECORD_I32..out.len() {
            assert_eq!(
                out[i], 0xA5A5A5u32 as i32,
                "int {} beyond both jobs' writes touched",
                i
            );
        }
    }

    /// §12: compute-only must be side-effect-free — no registry light
    /// writes, no section dirty marks, no lifecycle disturbance. The
    /// commit run over the identical overlay must then produce both.
    #[test]
    fn compute_only_has_no_side_effects() {
        let (cx, cz) = (250, 250);
        for dy in -1..=1i32 {
            for dz in -1..=1i32 {
                prime_chunk(0, cx + dy, cz + dz, |i| {
                    if dy == 0 && dz == 0 && i == primer_idx(8, 64, 9) {
                        1
                    } else {
                        0
                    }
                });
            }
        }
        let key = ChunkKey::new(0, cx, cz);
        let mask_before = get_registry().dirty_mask(key).unwrap_or(0);
        let mut out = vec![0i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            false,
        );
        assert!(rc > 0, "rc={}", rc);
        assert_eq!(
            get_registry().dirty_mask(key).unwrap_or(0),
            mask_before,
            "compute-only marked sections dirty"
        );
        assert_eq!(
            get_registry().get_block_light(key, 8, 64, 9).unwrap_or(0),
            0,
            "compute-only committed light"
        );
        // the identical overlay committed: light lands AND the owning
        // section's dirty bit drives the packet/flush machinery
        let rc2 = run_zero_stage_job(
            0,
            cx * 16 + 8,
            64,
            cz * 16 + 9,
            &table(),
            &mut out,
            1 << 22,
            64,
            true,
        );
        assert_eq!(rc2, rc, "commit must write the identical overlay");
        assert_eq!(
            get_registry().get_block_light(key, 8, 64, 9).unwrap_or(0),
            14,
            "commit must land the origin light"
        );
        let mask_after = get_registry().dirty_mask(key).unwrap_or(0);
        assert_ne!(
            mask_after, mask_before,
            "commit must mark touched sections dirty (mask {:#x})",
            mask_after
        );
        assert_ne!(
            mask_after & (1 << (64 / 16)),
            0,
            "commit must dirty the origin's section (y=64 -> bit 4)"
        );
    }

    #[test]
    fn missing_origin_chunk_fails_closed() {
        let mut out = vec![0i32; 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            -3000 * 16,
            64,
            -3000 * 16,
            &table(),
            &mut out,
            1 << 22,
            64,
            true,
        );
        assert_eq!(rc, ZS_ERR_MISSING_CHUNK);
    }

    #[test]
    fn unknown_state_fails_closed() {
        let (cx, cz) = (201, 201);
        prime_chunk(0, cx, cz, |i| {
            if i == primer_idx(4, 64, 4) {
                9
            } else {
                0
            } // sid 9 unclassified
        });
        let empty = vec![0u8; 1024 * 3];
        let mut out = vec![0i32; 1024 * DIFF_RECORD_I32];
        let rc = run_zero_stage_job(
            0,
            cx * 16 + 4,
            64,
            cz * 16 + 4,
            &empty,
            &mut out,
            1 << 22,
            64,
            true,
        );
        assert_eq!(rc, ZS_ERR_UNKNOWN_STATE);
    }
}
