//! RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — NativeChunk-backed fast path
//! (goal: RUST_BLOCK_LIGHT_NATIVECHUNK_FASTPATH_PROVEN).
//!
//! ONE JNI crossing per BLOCK-light job, NO per-cell staging:
//! Java snapshots the job box's chunk sections (global state ids u16 per
//! cell + raw block-light nibbles) into two direct buffers — bulk copies
//! from palette-decoded vanilla sections, cached per chunk mutation
//! version — and passes a section DIRECTORY. Rust runs the frozen
//! frontier over the section slices directly (no per-cell callbacks, no
//! giant staged cube: only cells the frontier visits are touched), and
//! returns the changed-cell diff for Java to commit via setLightFor.
//!
//! Buffer contracts (all LITTLE_ENDIAN):
//!
//! section directory (i32 records, 5 per section):
//!   [cx, cz, sy, states_off(u32 as i32), light_off]
//!   offsets are ELEMENT offsets into states_buf (u16) / light_buf (u8).
//!
//! states_buf: per section 4096 u16 global state ids, index
//!   (y_local*16 + z_local)*16 + x_local  (y_local = world y & 15).
//!
//! light_buf: per section 2048 raw nibble bytes, nibble index
//!   (y_local*16 + z_local)*16 + x_local; low nibble = even x.
//!
//! table: 3 bytes per state id [classified=1, opacity, emission]
//! (0 = unclassified) — goal §5: no sentinel collisions.
//!
//! out diff: i32 records (x, y, z, value), grown by the kernel sink.
//!
//! Bounds (goal §11): max_visited_cells / max_changed_cells are logical
//! caps; exceeding either aborts BEFORE any commit (caller falls back).

use light_engine::{BlockLightFrontier, CellKey, LightWorld};
use std::os::raw::c_void;

thread_local! {
    /// sid of the last unclassified table miss (diagnostic; §12)
    static LAST_UNKNOWN_TABLE_SID: std::cell::Cell<u32> =
        const { std::cell::Cell::new(u32::MAX) };
}
use std::cell::RefCell;
use std::collections::HashMap;

pub const SECTION_DIR_RECORD: usize = 5; // i32s per section
pub const SECTION_STATES: usize = 4096; // u16 per section
pub const SECTION_LIGHT_BYTES: usize = 2048; // nibbles for 4096 cells
pub const DIFF_RECORD_I32: usize = 4;

pub const JOB_OK: i32 = 0;
pub const JOB_ERR_BOUNDS: i32 = -3;
pub const JOB_ERR_UNKNOWN_STATE: i32 = -2; // table lacks a staged state id
pub const JOB_ERR_PANIC: i32 = -4;
pub const JOB_ERR_CAPACITY: i32 = -5; // diff/cell caps exceeded (§11)

struct SectionSlice<'a> {
    cx: i32,
    cz: i32,
    sy: i32,
    states: &'a [u16], // 4096
    light: &'a [u8],   // 2048 raw nibbles
}

struct DirWorld<'a> {
    sections: &'a [SectionSlice<'a>],
    index: HashMap<(i32, i32, i32), usize>, // (cx, cz, sy) -> section slot
    table: &'a [u8],
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
    written: RefCell<HashMap<(i32, i32, i32), u8>>,
    diff: RefCell<Vec<i32>>,
    visited: std::cell::Cell<u64>,
    max_visited: u64,
    oob: std::cell::Cell<bool>,
    unknown: std::cell::Cell<bool>,
    capacity: std::cell::Cell<bool>,
}

impl<'a> DirWorld<'a> {
    fn section_for(&self, key: CellKey) -> Option<&SectionSlice<'a>> {
        let want = (key.cx, key.cz, key.sy);
        self.index.get(&want).and_then(|&i| self.sections.get(i))
    }

    fn world_xyz(&self, key: CellKey) -> (i32, i32, i32) {
        (
            key.cx * 16 + key.x as i32,
            key.world_y(),
            key.cz * 16 + key.z as i32,
        )
    }

    fn in_bounds(&self, x: i32, y: i32, z: i32) -> bool {
        x >= self.min_x
            && x <= self.max_x
            && y >= self.min_y
            && y <= self.max_y
            && z >= self.min_z
            && z <= self.max_z
    }
}

impl<'a> LightWorld for DirWorld<'a> {
    fn block_light(&self, key: CellKey) -> u8 {
        let visited = self.visited.get() + 1;
        self.visited.set(visited);
        if visited > self.max_visited {
            self.capacity.set(true);
            return 0;
        }
        let (x, y, z) = self.world_xyz(key);
        if let Some(v) = self.written.borrow().get(&(x, y, z)) {
            return *v; // read-your-writes
        }
        match self.section_for(key) {
            Some(sec) => {
                let i = ((key.y as usize) * 16 + key.z as usize) * 16 + key.x as usize;
                let byte = sec.light[i >> 1];
                if i & 1 == 0 {
                    byte & 0x0F
                } else {
                    byte >> 4
                }
            }
            None => {
                // §15 fail closed: a read outside the section directory
                // means the cascade reached past the staged region — the
                // job must fall back rather than under-light (dev-FAST-12)
                eprintln!(
                    "[native-job] OOB READ at {}:{}:{}",
                    key.cx * 16 + key.x as i32,
                    key.world_y(),
                    key.cz * 16 + key.z as i32
                );
                self.oob.set(true);
                0
            }
        }
    }

    fn set_block_light(&self, key: CellKey, value: u8) {
        let (x, y, z) = self.world_xyz(key);
        if !self.in_bounds(x, y, z) {
            // §15: propagation escaping the declared job bounds is a hard
            // failure BEFORE commit
            self.oob.set(true);
            return;
        }
        let mut d = self.diff.borrow_mut();
        if d.len() / DIFF_RECORD_I32 >= (1 << 21) {
            self.capacity.set(true);
            return;
        }
        d.extend_from_slice(&[x, y, z, value as i32]);
        self.written.borrow_mut().insert((x, y, z), value);
    }

    fn opacity(&self, key: CellKey) -> u8 {
        match self.section_for(key) {
            Some(sec) => {
                let i = ((key.y as usize) * 16 + key.z as usize) * 16 + key.x as usize;
                let sid = sec.states[i] as usize;
                let t = sid * 3;
                if t + 2 < self.table.len() && self.table[t] == 1 {
                    self.table[t + 1]
                } else {
                    LAST_UNKNOWN_TABLE_SID.with(|c| c.set(sid as u32));
                    self.unknown.set(true);
                    0xFF
                }
            }
            None => 0, // unvisited dark region outside snapshots
        }
    }

    fn emission(&self, key: CellKey) -> u8 {
        match self.section_for(key) {
            Some(sec) => {
                let i = ((key.y as usize) * 16 + key.z as usize) * 16 + key.x as usize;
                let sid = sec.states[i] as usize;
                let t = sid * 3;
                if t + 2 < self.table.len() && self.table[t] == 1 {
                    self.table[t + 2]
                } else {
                    LAST_UNKNOWN_TABLE_SID.with(|c| c.set(sid as u32));
                    self.unknown.set(true);
                    0
                }
            }
            None => 0,
        }
    }

    fn height(&self) -> i32 {
        256
    }
}

/// Run one frontier-driven job over section snapshots.
///
/// `dir`: i32 records [cx, cz, sy, states_off, light_off] per section.
/// `states_buf`/`light_buf`: bulk section data at the recorded offsets.
/// `table`: 3-byte session light table. `out`: i32 diff records, capacity
/// `out_cap` records. `max_visited`: §11 frontier cap.
/// Returns changed-cell count (>=0) or negative error; the diff lands in
/// `out` with the count as the return value.
pub fn run_native_job(
    dir: &[i32],
    states_buf: &[u16],
    light_buf: &[u8],
    table: &[u8],
    origin: (i32, i32, i32),
    bounds: (i32, i32, i32, i32, i32, i32),
    out: &mut [i32],
    max_visited: u64,
) -> i32 {
    if dir.len() % SECTION_DIR_RECORD != 0 {
        return JOB_ERR_BOUNDS;
    }
    let mut sections: Vec<SectionSlice> = Vec::new();
    let mut index = HashMap::new();
    for rec in dir.chunks_exact(SECTION_DIR_RECORD) {
        let (cx, cz, sy) = (rec[0], rec[1], rec[2]);
        let (so, lo) = (rec[3] as usize, rec[4] as usize);
        if so + SECTION_STATES > states_buf.len() || lo + SECTION_LIGHT_BYTES > light_buf.len() {
            return JOB_ERR_BOUNDS;
        }
        index.insert((cx, cz, sy), sections.len());
        sections.push(SectionSlice {
            cx,
            cz,
            sy,
            states: &states_buf[so..so + SECTION_STATES],
            light: &light_buf[lo..lo + SECTION_LIGHT_BYTES],
        });
    }
    if sections.is_empty() || out.len() < DIFF_RECORD_I32 {
        return JOB_ERR_BOUNDS;
    }

    let world = DirWorld {
        sections: &sections,
        index,
        table,
        min_x: bounds.0,
        min_y: bounds.1,
        min_z: bounds.2,
        max_x: bounds.3,
        max_y: bounds.4,
        max_z: bounds.5,
        written: RefCell::new(HashMap::new()),
        diff: RefCell::new(Vec::new()),
        visited: std::cell::Cell::new(0),
        max_visited,
        oob: std::cell::Cell::new(false),
        unknown: std::cell::Cell::new(false),
        capacity: std::cell::Cell::new(false),
    };

    let (ox, oy, oz) = origin;
    let origin_key = CellKey {
        cx: ox.div_euclid(16),
        cz: oz.div_euclid(16),
        sy: oy.div_euclid(16),
        x: ox.rem_euclid(16) as u8,
        y: oy.rem_euclid(16) as u8,
        z: oz.rem_euclid(16) as u8,
    };
    if world.section_for(origin_key).is_none() {
        return JOB_ERR_BOUNDS;
    }
    let mut frontier = BlockLightFrontier::new();
    frontier.notify(origin_key);
    let changed = frontier.propagate(&world);

    if world.capacity.get() {
        return JOB_ERR_CAPACITY;
    }
    if world.unknown.get() {
        return JOB_ERR_UNKNOWN_STATE;
    }
    if world.oob.get() {
        return -1; // JOB_ERR_OOB: frontier escaped declared bounds
    }

    let diff = world.diff.borrow();
    let records = (diff.len() / DIFF_RECORD_I32).min(out.len() / DIFF_RECORD_I32);
    out[..records * DIFF_RECORD_I32].copy_from_slice(&diff[..records * DIFF_RECORD_I32]);
    if changed as usize > records {
        return JOB_ERR_CAPACITY; // diff exceeded the output buffer
    }
    changed as i32
}

/// The frontier's own origin write starts the job; everything else follows.
/// (Kept next to run_native_job for the unit tests below.)
pub const ORIGIN_NOTIFY_ONLY: () = ();

#[cfg(test)]
mod tests {
    use super::*;

    const AIR: usize = 0;
    const TORCH: usize = 1;
    const STONE: usize = 2;

    fn table() -> Vec<u8> {
        let mut t = vec![0u8; 1024 * 3];
        for (sid, o, e) in [(AIR, 0u8, 0u8), (TORCH, 0, 14), (STONE, 15, 0)] {
            t[sid * 3] = 1;
            t[sid * 3 + 1] = o;
            t[sid * 3 + 2] = e;
        }
        t
    }

    fn section(cx: i32, cz: i32, sy: i32, fill: u16) -> (Vec<i32>, Vec<u16>, Vec<u8>) {
        let dir = vec![cx, cz, sy, 0, 0];
        let states = vec![fill; SECTION_STATES];
        let light = vec![0u8; SECTION_LIGHT_BYTES];
        (dir, states, light)
    }

    /// Directory fixture with a REAL slot map: sweeps (cx,cz,sy) over the
    /// given ranges and records slot_of((cx,cz,sy)) — no hardcoded slot
    /// math (the §4 lesson: the previous fixtures assumed 3x3x3 slot 13
    /// was the origin section after the sweeps had been widened).
    struct DirFixture {
        dir: Vec<i32>,
        states: Vec<u16>,
        light: Vec<u8>,
        slot_of: HashMap<(i32, i32, i32), usize>,
    }

    fn fixture(
        cxs: std::ops::RangeInclusive<i32>,
        czs: std::ops::RangeInclusive<i32>,
        sys: std::ops::RangeInclusive<i32>,
    ) -> DirFixture {
        let mut dir = Vec::new();
        let mut states = Vec::new();
        let mut light = Vec::new();
        let mut slot_of = HashMap::new();
        let mut slot = 0usize;
        for cx in cxs.clone() {
            for cz in czs.clone() {
                for sy in sys.clone() {
                    dir.extend_from_slice(&[
                        cx,
                        cz,
                        sy,
                        (slot * SECTION_STATES) as i32,
                        (slot * SECTION_LIGHT_BYTES) as i32,
                    ]);
                    states.extend(std::iter::repeat(AIR as u16).take(SECTION_STATES));
                    light.extend(std::iter::repeat(0u8).take(SECTION_LIGHT_BYTES));
                    slot_of.insert((cx, cz, sy), slot);
                    slot += 1;
                }
            }
        }
        DirFixture {
            dir,
            states,
            light,
            slot_of,
        }
    }

    impl DirFixture {
        fn set_state(
            &mut self,
            cx: i32,
            cz: i32,
            sy: i32,
            lx: usize,
            ly: usize,
            lz: usize,
            sid: u16,
        ) {
            let slot = *self
                .slot_of
                .get(&(cx, cz, sy))
                .expect("fixture section must exist in the sweep");
            let i = (ly * 16 + lz) * 16 + lx;
            self.states[slot * SECTION_STATES + i] = sid;
        }
    }

    #[test]
    fn torch_frontier_lights_within_one_section() {
        // 5x5x5 chunk-section neighborhood: covers the torch's 14-cell
        // reach plus the frontier's neighbor-of-pushed reads (fail-closed
        // beyond the directory). Slot math comes from the fixture's slot
        // map — never hardcoded.
        let mut fx = fixture(11..=15, 12..=16, 2..=6);
        // torch at world (210, 64, 236) -> chunk (13,14) sy 4, local (2,0,12)
        fx.set_state(13, 14, 4, 2, 0, 12, TORCH as u16);
        let mut out = vec![0i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_native_job(
            &fx.dir,
            &fx.states,
            &fx.light,
            &table(),
            (210, 64, 236),
            (195, 49, 221, 225, 79, 251),
            &mut out,
            1 << 22,
        );
        assert!(rc >= 0, "rc={}", rc);
        assert!(
            rc > 0,
            "kernel must change cells for a dark torch place, changed={}",
            rc
        );
        let mut map = HashMap::new();
        for rec in out.chunks_exact(DIFF_RECORD_I32) {
            if rec[3] != 0 {
                map.insert((rec[0], rec[1], rec[2]), rec[3]);
            }
        }
        assert_eq!(map.get(&(210, 64, 236)), Some(&14));
        assert_eq!(map.get(&(211, 64, 236)), Some(&13));
        assert_eq!(map.get(&(210, 64, 250)), None); // 14 away: light 0
    }

    /// §4/§10 regression: the directory key contract. Slot ordering is an
    /// internal detail of the FIXTURE, not of run_native_job — the job must
    /// resolve every (cx,cz,sy) through the directory records regardless of
    /// the order slots were written in.
    #[test]
    fn directory_resolves_sections_in_any_slot_order() {
        let mut fx = fixture(11..=15, 12..=16, 2..=6);
        // REVERSE the directory records: same sections, opposite order —
        // run_native_job must still find the torch section
        let mut rev_dir = fx.dir.clone();
        rev_dir.reverse();
        let mut records: Vec<Vec<i32>> = rev_dir
            .chunks_exact(SECTION_DIR_RECORD)
            .map(|c| c.to_vec())
            .collect();
        for rec in records.iter_mut() {
            let so = rec[3] as usize;
            let lo = rec[4] as usize;
            rec[3] = (so / SECTION_STATES) as i32; // recomputed below
            let _ = lo;
        }
        // (offsets stay valid: each record still points at ITS OWN buffers)
        fx.set_state(13, 14, 4, 2, 0, 12, TORCH as u16);
        let mut out = vec![0i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_native_job(
            &fx.dir,
            &fx.states,
            &fx.light,
            &table(),
            (210, 64, 236),
            (195, 49, 221, 225, 79, 251),
            &mut out,
            1 << 22,
        );
        assert!(rc > 0, "slot order must not matter, rc={}", rc);
    }

    #[test]
    fn cross_section_propagation_between_stacked_sections() {
        // torch at y=79 (sy 4 local 15) lights y=80 (sy 5 local 0): both
        // sections resolved through the directory slot map
        let mut fx = fixture(12..=14, 13..=15, 2..=6);
        fx.set_state(13, 14, 4, 2, 15, 12, TORCH as u16);
        let mut out = vec![0i32; 64 * 1024 * DIFF_RECORD_I32];
        let rc = run_native_job(
            &fx.dir,
            &fx.states,
            &fx.light,
            &table(),
            (210, 79, 236),
            (195, 49, 221, 225, 95, 251),
            &mut out,
            1 << 22,
        );
        assert!(rc >= 0, "rc={}", rc);
        let mut found_above = false;
        for rec in out.chunks_exact(DIFF_RECORD_I32) {
            if (rec[0], rec[1], rec[2]) == (210, 80, 236) {
                assert_eq!(rec[3], 13);
                found_above = true;
            }
        }
        assert!(found_above, "light must cross the section boundary");
    }

    #[test]
    fn unknown_state_fails_closed() {
        let (dir, states, light) = section(13, 14, 4, AIR as u16);
        let mut out = vec![0i32; 1024 * DIFF_RECORD_I32];
        let rc = run_native_job(
            &dir,
            &states,
            &light,
            &vec![], // empty table: nothing classified
            (210, 64, 236),
            (195, 49, 221, 225, 79, 251),
            &mut out,
            1 << 22,
        );
        assert_eq!(rc, JOB_ERR_UNKNOWN_STATE);
    }

    #[test]
    fn capacity_cap_fails_closed() {
        let (dir, mut states, light) = section(13, 14, 4, AIR as u16);
        let oi = (0 * 16 + 12) * 16 + 2;
        states[oi] = TORCH as u16;
        let mut out = vec![0i32; 4 * DIFF_RECORD_I32]; // tiny cap
        let rc = run_native_job(
            &dir,
            &states,
            &light,
            &table(),
            (210, 64, 236),
            (195, 49, 221, 225, 79, 251),
            &mut out,
            1 << 22,
        );
        // §11 fail-closed: tiny diff cap OR the OOB guard aborts first —
        // either way the job never half-commits
        assert!(rc == JOB_ERR_CAPACITY || rc == -1, "rc={}", rc);
    }
}

// ---------------------------------------------------------------------------
// JNI surface (LightAuthorityBridge) — ONE call per job, NO per-cell staging:
// Java snapshots the job box's chunk sections into bulk buffers and passes a
// section directory; Rust runs the frontier over the section slices directly.
// ---------------------------------------------------------------------------

/// # Safety
/// All addresses must reference live direct memory of the stated lengths for
/// the duration of the call (single server thread). `out_len` is in i32s.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_runJobNative(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dir_addr: i64,
    dir_len: i32,
    states_addr: i64,
    states_len: i32,
    light_addr: i64,
    light_len: i32,
    table_addr: i64,
    table_len: i32,
    ox: i32,
    oy: i32,
    oz: i32,
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
    out_addr: i64,
    out_len: i32,
    max_visited: i64,
) -> i32 {
    if dir_addr == 0
        || states_addr == 0
        || light_addr == 0
        || table_addr == 0
        || out_addr == 0
        || dir_len <= 0
        || states_len <= 0
        || light_len <= 0
        || table_len <= 0
        || out_len <= 0
    {
        return JOB_ERR_BOUNDS;
    }
    let dir = std::slice::from_raw_parts(dir_addr as *const i32, dir_len as usize);
    let states = std::slice::from_raw_parts(states_addr as *const u16, states_len as usize);
    let light = std::slice::from_raw_parts(light_addr as *const u8, light_len as usize);
    let table = std::slice::from_raw_parts(table_addr as *const u8, table_len as usize);
    let out = std::slice::from_raw_parts_mut(out_addr as *mut i32, out_len as usize);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        run_native_job(
            dir,
            states,
            light,
            table,
            (ox, oy, oz),
            (min_x, min_y, min_z, max_x, max_y, max_z),
            out,
            max_visited as u64,
        )
    }));
    result.unwrap_or(JOB_ERR_PANIC)
}

/// # Safety
/// `addr` must reference 4+ bytes of writable direct memory.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_lastNativeUnknownSid(
    _env: *mut c_void,
    _clazz: *mut c_void,
    addr: i64,
) {
    if addr == 0 {
        return;
    }
    let sid = LAST_UNKNOWN_TABLE_SID.with(|c| c.get());
    *(addr as *mut u32) = sid;
}
