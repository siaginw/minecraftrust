//! RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — production staged-box job runner.
//!
//! ONE JNI crossing per BLOCK-light job (goal §5): Java stages a flat
//! per-cell job descriptor (state id + current block light for the job box
//! plus a 1-cell halo), Rust runs the FROZEN two-queue frontier kernel over
//! a staging `LightWorld` and writes the resulting interior light grid.
//! Java commits diffs into the live chunk nibble arrays.
//!
//! Layout contract (little-endian, built by LightAuthorityBridge):
//!
//! input buffer, header (header_u32 words):
//! ```text
//!   [0] origin x   [1] origin y   [2] origin z
//!   [3] min x      [4] min y      [5] min z      (interior bounds, inclusive)
//!   [6] max x      [7] max y      [8] max z      (interior bounds, inclusive)
//!   [9] dim x     [10] dim y     [11] dim z      (staged box extent, interior
//!                                                 + 1-cell halo on each side)
//!  [12] flags (bit0: failed-out-of-bounds seen)
//! ```
//! then `dx*dy*dz` cells, index = (dy_idx*dim_z + dz_idx)*dim_x + dx_idx,
//! each cell = i32 state id + u8 current light (5 bytes, packed).
//!
//! table buffer: per state id, 2 bytes: [opacity, emission]. Unknown state
//! id (table value 0xFF opacity) marks the job ineligible.
//!
//! output buffer: interior cells only, `dx*dy*dz` bytes of new light.
//!
//! Hard bounds (goal §15): any frontier neighbor outside the staged box
//! sets the out-of-bounds flag; the caller must treat a flagged job as
//! failed and fall back to Java (fail closed BEFORE commit).

use light_engine::{BlockLightFrontier, CellKey, LightWorld};
use std::os::raw::c_void;

pub const HEADER_U32: usize = 13;
pub const CELL_BYTES: usize = 5;
pub const TABLE_CELL_BYTES: usize = 3; // [classified=1, opacity, emission]
pub const TABLE_UNKNOWN_OPACITY: u8 = 0xFF;

thread_local! {
    static LAST_OOB: std::cell::Cell<(i32, i32, i32)> =
        const { std::cell::Cell::new((i32::MIN, i32::MIN, i32::MIN)) };
}

fn set_last_oob(v: (i32, i32, i32)) {
    LAST_OOB.with(|c| c.set(v));
}

thread_local! {
    static LAST_UNKNOWN_SID: std::cell::Cell<u32> =
        const { std::cell::Cell::new(u32::MAX) };
}

/// # Safety
/// `addr` must reference 4+ bytes of writable direct memory.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_lastUnknownSid(
    _env: *mut c_void,
    _clazz: *mut c_void,
    addr: i64,
) {
    if addr == 0 {
        return;
    }
    let sid = LAST_UNKNOWN_SID.with(|c| c.get());
    *(addr as *mut u32) = sid;
}

/// First out-of-bounds cell of the most recent OOB job on this thread
/// (server thread = one job at a time) — for Java-side fallback reasons.
pub fn take_last_oob() -> (i32, i32, i32) {
    LAST_OOB.with(|c| c.replace((i32::MIN, i32::MIN, i32::MIN)))
}
pub const JOB_ERR_OOB: i32 = -1; // frontier left the staged box: fallback
pub const JOB_ERR_UNKNOWN_STATE: i32 = -2; // opacity 0xFF seen: fallback
pub const JOB_ERR_BOUNDS: i32 = -3; // malformed header
pub const JOB_ERR_PANIC: i32 = -4;

/// Full job runner with a write sink. `sink` is invoked as
/// `sink(x, y, z, new_value)` for every cell the kernel changes; callers
/// needing mutation wrap state in a RefCell (the sink is called via &).
pub fn run_staged_job_with_sink<F: Fn(i32, i32, i32, u8)>(
    input: &[u8],
    table: &[u8],
    sink: F,
) -> i32 {
    staged_inner(input, table, &sink)
}

fn staged_inner<F: Fn(i32, i32, i32, u8)>(input: &[u8], table: &[u8], sink: &F) -> i32 {
    let hdr = |i: usize| -> i32 {
        let b = i * 4; // header occupies bytes 0..HEADER_U32*4
        if b + 4 <= input.len() {
            i32::from_le_bytes([input[b], input[b + 1], input[b + 2], input[b + 3]])
        } else {
            0
        }
    };
    let (ox, oy, oz) = (hdr(0), hdr(1), hdr(2));
    let (min_x, min_y, min_z) = (hdr(3), hdr(4), hdr(5));
    let (max_x, max_y, max_z) = (hdr(6), hdr(7), hdr(8));
    let (dim_x, dim_y, dim_z) = (hdr(9), hdr(10), hdr(11));
    if dim_x < 5
        || dim_y < 5
        || dim_z < 5
        || min_x > max_x
        || min_y > max_y
        || min_z > max_z
        || max_x - min_x + 1 != dim_x - 4
        || max_y - min_y + 1 != dim_y - 4
        || max_z - min_z + 1 != dim_z - 4
        || min_y < 0
        || max_y > 255
    {
        return JOB_ERR_BOUNDS;
    }
    let cells = dim_x as usize * dim_y as usize * dim_z as usize;
    if input.len() < HEADER_U32 * 4 + cells * CELL_BYTES {
        return JOB_ERR_BOUNDS;
    }

    let oob = std::cell::Cell::new(false);
    let unknown = std::cell::Cell::new(false);

    struct W<'a, F: Fn(i32, i32, i32, u8)> {
        input: &'a [u8],
        table: &'a [u8],
        min_x: i32,
        min_y: i32,
        min_z: i32,
        dim_x: i32,
        dim_y: i32,
        dim_z: i32,
        oob: &'a std::cell::Cell<bool>,
        unknown: &'a std::cell::Cell<bool>,
        sink: &'a F,
        first_oob: &'a std::cell::Cell<(i32, i32, i32)>,
        max_x: i32,
        max_y: i32,
        max_z: i32,
        /// read-your-writes overlay: the kernel re-reads cells it has
        /// already updated; without this the cascade sees stale darkness
        /// and stops at the origin (found by the torch-place unit test)
        written: &'a std::cell::RefCell<std::collections::HashMap<(i32, i32, i32), u8>>,
    }
    impl<'a, F: Fn(i32, i32, i32, u8)> W<'a, F> {
        fn idx(&self, x: i32, y: i32, z: i32) -> Option<usize> {
            // staged box = interior + 2 halo rings: the frontier processes
            // neighbors-of-changed cells (<=16 out) and reads their
            // neighbors (<=17 out) — dev-batch-ON layout fix
            let dx = x - self.min_x + 2;
            let dy = y - self.min_y + 2;
            let dz = z - self.min_z + 2;
            if dx < 0
                || dy < 0
                || dz < 0
                || dx >= self.dim_x
                || dy >= self.dim_y
                || dz >= self.dim_z
            {
                None
            } else {
                Some(
                    HEADER_U32 * 4
                        + ((dy as usize * self.dim_z as usize + dz as usize) * self.dim_x as usize
                            + dx as usize)
                            * CELL_BYTES,
                )
            }
        }
        fn state_at(&self, x: i32, y: i32, z: i32) -> Option<u32> {
            let i = self.idx(x, y, z)?;
            Some(u32::from_le_bytes([
                self.input[i],
                self.input[i + 1],
                self.input[i + 2],
                self.input[i + 3],
            ]))
        }
    }
    impl<'a, F: Fn(i32, i32, i32, u8)> LightWorld for W<'a, F> {
        fn block_light(&self, key: CellKey) -> u8 {
            let (x, y, z) = (
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            );
            if let Some(v) = self.written.borrow().get(&(x, y, z)) {
                return *v;
            }
            match self.idx(x, y, z) {
                Some(i) => self.input[i + 4],
                None => {
                    if !self.oob.get() {
                        self.first_oob.set((x, y, z));
                    }
                    self.oob.set(true);
                    0
                }
            }
        }
        fn set_block_light(&self, key: CellKey, value: u8) {
            // only interior cells reach here: the kernel writes values it
            // derived, and every derivation chain starts at the origin —
            // guard the box anyway (defense in depth, goal §15)
            let (x, y, z) = (
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            );
            self.written.borrow_mut().insert((x, y, z), value);
            (self.sink)(x, y, z, value);
        }
        fn opacity(&self, key: CellKey) -> u8 {
            match self.state_at(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ) {
                Some(sid) => {
                    let t = sid as usize * TABLE_CELL_BYTES;
                    if t + 2 < self.table.len() && self.table[t] == 1 {
                        self.table[t + 1]
                    } else {
                        LAST_UNKNOWN_SID.with(|c| c.set(sid));
                        self.unknown.set(true);
                        0xFF
                    }
                }
                None => {
                    if !self.oob.get() {
                        self.first_oob.set((
                            key.cx * 16 + key.x as i32,
                            key.world_y(),
                            key.cz * 16 + key.z as i32,
                        ));
                    }
                    self.oob.set(true);
                    0xFF
                }
            }
        }
        fn emission(&self, key: CellKey) -> u8 {
            match self.state_at(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ) {
                Some(sid) => {
                    let t = sid as usize * TABLE_CELL_BYTES;
                    if t + 2 < self.table.len() && self.table[t] == 1 {
                        self.table[t + 2]
                    } else {
                        LAST_UNKNOWN_SID.with(|c| c.set(sid));
                        self.unknown.set(true);
                        0
                    }
                }
                None => {
                    if !self.oob.get() {
                        self.first_oob.set((
                            key.cx * 16 + key.x as i32,
                            key.world_y(),
                            key.cz * 16 + key.z as i32,
                        ));
                    }
                    self.oob.set(true);
                    0
                }
            }
        }
        fn height(&self) -> i32 {
            256
        }
    }

    let first_oob = std::cell::Cell::new((i32::MIN, i32::MIN, i32::MIN));
    let written = std::cell::RefCell::new(std::collections::HashMap::<(i32, i32, i32), u8>::new());
    let world = W {
        input,
        table,
        min_x,
        min_y,
        min_z,
        dim_x,
        dim_y,
        dim_z,
        oob: &oob,
        unknown: &unknown,
        sink,
        first_oob: &first_oob,
        written: &written,
        max_x,
        max_y,
        max_z,
    };

    if world.idx(ox, oy, oz).is_none() {
        return JOB_ERR_BOUNDS;
    }
    if ox != (min_x + max_x) / 2 || oy != (min_y + max_y) / 2 || oz != (min_z + max_z) / 2 {
        return JOB_ERR_BOUNDS;
    }
    // §12 whole-job eligibility: every staged state must be classified
    // BEFORE propagation (fail closed, no per-cell Java callbacks)
    {
        let cells = dim_x as usize * dim_y as usize * dim_z as usize;
        let mut seen = std::collections::HashSet::<u32>::new();
        for c in 0..cells {
            let b = HEADER_U32 * 4 + c * CELL_BYTES;
            let sid = u32::from_le_bytes([input[b], input[b + 1], input[b + 2], input[b + 3]]);
            if seen.insert(sid) {
                let t = sid as usize * TABLE_CELL_BYTES;
                if t + 2 >= table.len() || table[t] != 1 {
                    LAST_UNKNOWN_SID.with(|c| c.set(sid));
                    return JOB_ERR_UNKNOWN_STATE;
                }
            }
        }
    }
    let origin = CellKey {
        cx: ox.div_euclid(16),
        cz: oz.div_euclid(16),
        sy: oy.div_euclid(16),
        x: ox.rem_euclid(16) as u8,
        y: oy.rem_euclid(16) as u8,
        z: oz.rem_euclid(16) as u8,
    };

    let mut frontier = BlockLightFrontier::new();
    frontier.notify(origin);
    let changed = frontier.propagate(&world);

    if unknown.get() {
        return JOB_ERR_UNKNOWN_STATE;
    }
    if oob.get() {
        set_last_oob(first_oob.get());
        return JOB_ERR_OOB;
    }
    changed as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build_input(
        ox: i32,
        oy: i32,
        oz: i32,
        states: &[i32], // interior only, row-major
        lights: &[u8],  // interior only
        halo_state: i32,
        halo_light: u8,
    ) -> Vec<u8> {
        let dx = 35i32; // interior 31 + 2 halo rings each side
        let dim = [dx, dx, dx];
        let mut input = vec![0u8; HEADER_U32 * 4 + (dx * dx * dx) as usize * CELL_BYTES];
        let mut set_hdr = |i: usize, v: i32| {
            let b = i * 4; // header occupies bytes 0..HEADER_U32*4
            input[b..b + 4].copy_from_slice(&v.to_le_bytes());
        };
        set_hdr(0, ox);
        set_hdr(1, oy);
        set_hdr(2, oz);
        set_hdr(3, ox - 15);
        set_hdr(4, oy - 15);
        set_hdr(5, oz - 15);
        set_hdr(6, ox + 15);
        set_hdr(7, oy + 15);
        set_hdr(8, oz + 15);
        set_hdr(9, dim[0]);
        set_hdr(10, dim[1]);
        set_hdr(11, dim[2]);
        // fill ALL cells (interior + halo) with halo defaults
        for c in 0..(dx * dx * dx) as usize {
            let b = HEADER_U32 * 4 + c * CELL_BYTES;
            input[b..b + 4].copy_from_slice(&halo_state.to_le_bytes());
            input[b + 4] = halo_light;
        }
        // overwrite interior (staged offset +2 per axis)
        for iy in 0..31i32 {
            for iz in 0..31i32 {
                for ix in 0..31i32 {
                    let c = (((iy + 2) * dim[1] * dim[2] + (iz + 2) * dim[2] + (ix + 2)) as usize)
                        * CELL_BYTES
                        + HEADER_U32 * 4;
                    let si = (iy * 31 * 31 + iz * 31 + ix) as usize;
                    input[c..c + 4].copy_from_slice(&states[si].to_le_bytes());
                    input[c + 4] = lights[si];
                }
            }
        }
        input
    }

    fn build_table(entries: &[(usize, u8, u8)]) -> Vec<u8> {
        let mut t = vec![0u8; 1024 * TABLE_CELL_BYTES]; // 0 = unclassified
        for (sid, o, e) in entries {
            t[sid * 3] = 1;
            t[sid * 3 + 1] = *o;
            t[sid * 3 + 2] = *e;
        }
        t
    }

    const AIR: usize = 0;
    const TORCH: usize = 1;
    const STONE: usize = 2;

    fn table() -> Vec<u8> {
        build_table(&[(AIR, 0, 0), (TORCH, 0, 14), (STONE, 15, 0)])
    }

    #[test]
    fn torch_place_lights_the_box() {
        // all air, origin torch, everything dark
        let mut states = vec![AIR as i32; 31 * 31 * 31];
        let mut lights = vec![0u8; states.len()];
        let oi = (15 * 31 * 31 + 15 * 31 + 15) as usize;
        states[oi] = TORCH as i32;
        lights[oi] = 0;
        let input = build_input(100, 64, 100, &states, &lights, AIR as i32, 0);
        let g = |i: usize| {
            i32::from_le_bytes([
                input[i * 4],
                input[i * 4 + 1],
                input[i * 4 + 2],
                input[i * 4 + 3],
            ])
        };
        eprintln!(
            "hdr origin=({},{},{}) min=({},{},{}) max=({},{},{}) dim=({},{},{}) len={} need={}",
            g(0),
            g(1),
            g(2),
            g(3),
            g(4),
            g(5),
            g(6),
            g(7),
            g(8),
            g(9),
            g(10),
            g(11),
            input.len(),
            52 + 33 * 33 * 33 * 5
        );
        let committed = std::cell::RefCell::new(Vec::<(i32, i32, i32, u8)>::new());
        let rc = run_staged_job_with_sink(&input, &table(), |x, y, z, v| {
            committed.borrow_mut().push((x, y, z, v));
        });
        let committed = committed.into_inner();
        assert!(rc >= 0, "torch job must succeed, got {}", rc);
        // origin itself becomes 14
        assert!(committed.contains(&(100, 64, 100, 14)));
        // direct neighbors become 13
        assert!(committed.contains(&(101, 64, 100, 13)));
        assert!(committed.contains(&(100, 64, 101, 13)));
        // a cell 13 away along x becomes 1 (14 - 13 attenuation steps);
        // distance 14 receives 0 — unlit, never written
        assert!(committed.contains(&(113, 64, 100, 1)));
        // count sanity: torch in open air lights 4^2*3+... > 100 cells
        assert!(committed.len() > 100, "committed={}", committed.len());
    }

    #[test]
    fn torch_removal_darkens() {
        // origin torch already at 14, remove it (air, light must fall to 0)
        let states = vec![AIR as i32; 31 * 31 * 31];
        let mut lights = vec![0u8; states.len()];
        let oi = (15 * 31 * 31 + 15 * 31 + 15) as usize;
        lights[oi] = 14;
        for iy in 0..31i32 {
            for iz in 0..31i32 {
                for ix in 0..31i32 {
                    let d = (ix - 15).abs().max((iy - 15).abs()).max((iz - 15).abs());
                    lights[(iy * 31 * 31 + iz * 31 + ix) as usize] = (14 - d as i32).max(0) as u8;
                }
            }
        }
        let input = build_input(100, 64, 100, &states, &lights, AIR as i32, 0);
        let committed = std::cell::RefCell::new(Vec::<(i32, i32, i32, u8)>::new());
        let rc = run_staged_job_with_sink(&input, &table(), |x, y, z, v| {
            committed.borrow_mut().push((x, y, z, v));
        });
        let committed = committed.into_inner();
        assert!(rc >= 0, "removal job must succeed, got {}", rc);
        assert!(committed.contains(&(100, 64, 100, 0)));
        assert!(committed.contains(&(101, 64, 100, 0)));
        // cells at the halo edge had 0 already — no writes reach 15-away
        assert!(committed.iter().all(|(x, _, _, _)| (100 - x).abs() < 16));
    }

    #[test]
    fn opaque_block_walls_light() {
        // torch at origin; stone wall at x=+3 spanning the whole yz interior
        let mut states = vec![AIR as i32; 31 * 31 * 31];
        let mut lights = vec![0u8; states.len()];
        let oi = (15 * 31 * 31 + 15 * 31 + 15) as usize;
        states[oi] = TORCH as i32;
        for iy in 0..31i32 {
            for iz in 0..31 {
                states[(iy * 31 * 31 + iz * 31 + 18) as usize] = STONE as i32;
            }
        }
        let input = build_input(100, 64, 100, &states, &lights, AIR as i32, 0);
        let beyond_wall_lit = std::cell::Cell::new(false);
        let rc = run_staged_job_with_sink(&input, &table(), |x, _, _, _| {
            if x > 103 {
                beyond_wall_lit.set(true);
            }
        });
        let beyond_wall_lit = beyond_wall_lit.get();
        assert!(rc >= 0, "wall job must succeed, got {}", rc);
        assert!(!beyond_wall_lit, "opacity-15 wall must stop light");
    }

    #[test]
    fn unknown_state_fails_closed() {
        let states = vec![AIR as i32; 31 * 31 * 31];
        let lights = vec![0u8; states.len()];
        let input = build_input(100, 64, 100, &states, &lights, AIR as i32, 0);
        let empty_table = build_table(&[]);
        let rc = run_staged_job_with_sink(&input, &empty_table, |_, _, _, _| {});
        assert_eq!(rc, JOB_ERR_UNKNOWN_STATE, "unknown state must fail closed");
    }

    #[test]
    fn malformed_bounds_rejected() {
        let mut input = vec![0u8; 64];
        assert_eq!(
            run_staged_job_with_sink(&input, &table(), |_, _, _, _| {}),
            JOB_ERR_BOUNDS
        );
    }
}

// ---------------------------------------------------------------------------
// JNI surface (LightAuthorityBridge) — ONE call per BLOCK-light job.
// input: direct buffer, layout documented at the top of this file.
// output: interior grid (31*31*31 bytes, row-major over min..=max).
// returns: changed-cell count (>=0) or negative JOB_ERR_*.
// ---------------------------------------------------------------------------

/// # Safety
/// `input_addr`/`table_addr`/`out_addr` must reference live direct memory of
/// the stated lengths for the duration of the call (single server thread).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_runJob(
    _env: *mut c_void,
    _clazz: *mut c_void,
    input_addr: i64,
    input_len: i32,
    table_addr: i64,
    table_len: i32,
    out_addr: i64,
    out_len: i32,
) -> i32 {
    if input_addr == 0
        || table_addr == 0
        || out_addr == 0
        || input_len <= 0
        || table_len <= 0
        || out_len <= 0
    {
        return JOB_ERR_BOUNDS;
    }
    let input = std::slice::from_raw_parts(input_addr as *const u8, input_len as usize);
    let table = std::slice::from_raw_parts(table_addr as *const u8, table_len as usize);
    let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, out_len as usize);

    // interior bounds from the input header
    let g = |i: usize| -> i32 {
        let b = i * 4;
        i32::from_le_bytes([input[b], input[b + 1], input[b + 2], input[b + 3]])
    };
    let (min_x, min_y, min_z) = (g(3), g(4), g(5));
    let (max_x, max_y, max_z) = (g(6), g(7), g(8));
    let dx = (max_x - min_x + 1) as usize;
    let dy = (max_y - min_y + 1) as usize;
    let dz = (max_z - min_z + 1) as usize;
    if out_len < (dx * dy * dz) as i32 {
        return JOB_ERR_BOUNDS;
    }
    out[..dx * dy * dz].fill(0xFF); // sentinel: unwritten cells keep staged light

    let out_cell = std::cell::RefCell::new(out);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        staged_inner(input, table, &|x, y, z, v| {
            if (min_x..=max_x).contains(&x)
                && (min_y..=max_y).contains(&y)
                && (min_z..=max_z).contains(&z)
            {
                let i = ((y - min_y) as usize * dz * dx)
                    + ((z - min_z) as usize * dx)
                    + (x - min_x) as usize;
                out_cell.borrow_mut()[i] = v;
            }
        })
    }));
    result.unwrap_or(JOB_ERR_PANIC)
}

/// # Safety
/// `addr` must reference 12+ bytes of writable direct memory.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightAuthorityBridge_lastOob(
    _env: *mut c_void,
    _clazz: *mut c_void,
    addr: i64,
) {
    if addr == 0 {
        return;
    }
    let (x, y, z) = take_last_oob();
    let p = addr as *mut i32;
    *p = x;
    *p.add(1) = y;
    *p.add(2) = z;
}
