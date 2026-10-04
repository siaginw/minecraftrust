//! LIVE block-light propagation batch FFI (RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY).
//!
//! One FFI context = one propagation JOB (one Phosphor
//! `processLightUpdatesForType(BLOCK)` drain). Java captures the scheduled
//! positions + pre-drain light grid / opacity / emission over the affected
//! bounds, feeds them here, Rust runs the proven frontier kernel, Java reads
//! the resulting cell values back.
//!
//! Packing: cells are passed as three i32 world coordinates (x, y, z) plus
//! u8 values — Java marshals into direct buffers in bulk (no per-cell JNI).
//!
//! Contract:
//!   create(cap_cells)                    -> handle (>0) | 0
//!   reserve(h, n_cells)                  -> 0 | <0 error
//!   add_initial(h, x, y, z, light)       -> 0 | <0
//!   add_opacity(h, x, y, z, opacity)     -> 0 | <0
//!   add_emission(h, x, y, z, emission)   -> 0 | <0
//!   add_notify(h, x, y, z)               -> 0 | <0
//!   propagate(h)                         -> changed count | <0
//!   read_result(h, x, y, z)              -> light 0..15 | <0
//!   close(h)                             -> 0
//!
//! All entry points catch panics; no unwinding crosses the boundary.

use std::cell::RefCell;
use std::collections::HashMap;
use std::os::raw::c_void;

use light_engine::{BlockLightFrontier, CellKey, LightWorld};

/// World over sparse Java-provided maps (pre-drain light, opacity, emission).
/// The engine writes results into `result`, leaving the initial maps intact
/// for deterministic re-runs.
struct BatchWorld {
    opacity: HashMap<(i32, i32, i32), u8>,
    emission: HashMap<(i32, i32, i32), u8>,
    initial: HashMap<(i32, i32, i32), u8>,
    result: RefCell<HashMap<(i32, i32, i32), u8>>,
    /// cells outside Java's captured region: treated as dark + fully
    /// transparent, matching Phosphor's behavior at unloaded borders for
    /// block light (no light sources there in an admitted job).
    outside_light: u8,
}

impl BatchWorld {
    fn k(x: i32, y: i32, z: i32) -> Option<CellKey> {
        if y < 0 {
            return None;
        }
        Some(CellKey {
            cx: x.div_euclid(16),
            cz: z.div_euclid(16),
            sy: y.div_euclid(16),
            x: x.rem_euclid(16) as u8,
            y: y.rem_euclid(16) as u8,
            z: z.rem_euclid(16) as u8,
        })
    }
}

impl LightWorld for BatchWorld {
    fn block_light(&self, key: CellKey) -> u8 {
        let c = (
            key.cx * 16 + key.x as i32,
            key.world_y(),
            key.cz * 16 + key.z as i32,
        );
        self.result
            .borrow()
            .get(&c)
            .or_else(|| self.initial.get(&c))
            .copied()
            .unwrap_or(self.outside_light)
    }

    fn set_block_light(&self, key: CellKey, value: u8) {
        self.result.borrow_mut().insert(
            (
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ),
            value,
        );
    }

    fn opacity(&self, key: CellKey) -> u8 {
        *self
            .opacity
            .get(&(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ))
            .unwrap_or(&0)
    }

    fn emission(&self, key: CellKey) -> u8 {
        *self
            .emission
            .get(&(
                key.cx * 16 + key.x as i32,
                key.world_y(),
                key.cz * 16 + key.z as i32,
            ))
            .unwrap_or(&0)
    }
}

struct BatchCtx {
    world: BatchWorld,
    frontier: BlockLightFrontier,
    propagated: bool,
}

fn ctx<'a>(handle: i64) -> Option<&'a mut BatchCtx> {
    unsafe { (handle as *mut BatchCtx).as_mut() }
}

fn k_of(x: i32, y: i32, z: i32) -> Option<CellKey> {
    BatchWorld::k(x, y, z)
}

/// JNI: create a batch context. Returns handle (>0) or 0.
///
/// # Safety
/// `outside_light` is a plain value; no pointer arguments. Must only be
/// paired with close() on the returned handle by the single owner.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightBatchCtx_create(
    _env: *mut c_void,
    _clazz: *mut c_void,
    outside_light: i32,
) -> i64 {
    let world = BatchWorld {
        opacity: HashMap::new(),
        emission: HashMap::new(),
        initial: HashMap::new(),
        result: RefCell::new(HashMap::new()),
        outside_light: outside_light.clamp(0, 15) as u8,
    };
    let c = BatchCtx {
        world,
        frontier: BlockLightFrontier::new(),
        propagated: false,
    };
    std::panic::catch_unwind(move || Box::into_raw(Box::new(c)) as i64).unwrap_or(0)
}

/// JNI: free the context. Idempotent (<=0 is a no-op).
/// # Safety
/// `handle` must be a live create()-returned handle; array addresses
/// must reference live direct memory of the stated length.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightBatchCtx_close(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i32 {
    if handle <= 0 {
        return 0;
    }
    drop(Box::from_raw(handle as *mut BatchCtx));
    0
}

/// JNI: bulk-add entries from three parallel arrays (coords packed
/// interleaved xyz in a direct i32 buffer, values in a direct u8 buffer).
/// kind: 0 = initial light, 1 = opacity, 2 = emission, 3 = notify.
/// Returns entries added or negative on error.
/// # Safety
/// `handle` must be a live create()-returned handle; array addresses
/// must reference live direct memory of the stated length.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightBatchCtx_addBatch(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    coords_addr: i64,
    values_addr: i64,
    n: i32,
    kind: i32,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let c = match ctx(handle) {
            Some(c) => c,
            None => return -6,
        };
        if n < 0 || coords_addr == 0 || values_addr == 0 {
            return -2;
        }
        let coords = std::slice::from_raw_parts(coords_addr as *const i32, (n * 3) as usize);
        let values = std::slice::from_raw_parts(values_addr as *const u8, n as usize);
        let mut added = 0i32;
        for i in 0..n as usize {
            let (x, y, z) = (coords[i * 3], coords[i * 3 + 1], coords[i * 3 + 2]);
            match kind {
                0 => {
                    if y >= 0 {
                        c.world.initial.insert((x, y, z), values[i]);
                        c.world
                            .result
                            .borrow_mut()
                            .entry((x, y, z))
                            .or_insert(values[i]);
                        added += 1;
                    }
                }
                1 => {
                    c.world.opacity.insert((x, y, z), values[i]);
                    added += 1;
                }
                2 => {
                    c.world.emission.insert((x, y, z), values[i]);
                    added += 1;
                }
                3 => {
                    if let Some(k) = k_of(x, y, z) {
                        c.frontier.notify(k);
                        let _ = k;
                        added += 1;
                    }
                }
                _ => return -2,
            }
        }
        added
    }));
    result.unwrap_or(-5)
}

/// JNI: run the frontier to the fixed point. Returns changed cells or
/// negative on error. Only valid once per context.
/// # Safety
/// `handle` must be a live create()-returned handle; array addresses
/// must reference live direct memory of the stated length.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightBatchCtx_propagate(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i64 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let c = match ctx(handle) {
            Some(c) => c,
            None => return -6i64,
        };
        if c.propagated {
            return -2i64;
        }
        c.propagated = true;
        c.frontier.propagate(&c.world) as i64
    }));
    result.unwrap_or(-5i64)
}

/// JNI: read one resulting light value (0..15) or negative on error.
/// # Safety
/// `handle` must be a live create()-returned handle; array addresses
/// must reference live direct memory of the stated length.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_LightBatchCtx_readResult(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    x: i32,
    y: i32,
    z: i32,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let c = match ctx(handle) {
            Some(c) => c,
            None => return -6,
        };
        if y < 0 {
            return 0; // below world: dark (vanilla)
        }
        let k = match k_of(x, y, z) {
            Some(k) => k,
            None => return -2,
        };
        c.world.block_light(k) as i32
    }));
    result.unwrap_or(-5)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn batch_torch_place_and_remove() {
        let h = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_create(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                0,
            )
        };
        assert!(h > 0);

        // initial: dark grid cell at origin (light 0), torch emits 14 there
        let coords: [i32; 3] = [0, 64, 0];
        let light0: [u8; 1] = [0];
        let n = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_addBatch(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                coords.as_ptr() as i64,
                light0.as_ptr() as i64,
                1,
                0, // initial light
            )
        };
        assert_eq!(n, 1);

        let emission: [u8; 1] = [14];
        let n = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_addBatch(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                coords.as_ptr() as i64,
                emission.as_ptr() as i64,
                1,
                2, // emission
            )
        };
        assert_eq!(n, 1);

        // notify origin + one neighbor
        let coords2: [i32; 6] = [0, 64, 0, 1, 64, 0];
        let dummy: [u8; 2] = [0, 0];
        let n = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_addBatch(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                coords2.as_ptr() as i64,
                dummy.as_ptr() as i64,
                2,
                3, // notify
            )
        };
        assert_eq!(n, 2);

        let changed = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_propagate(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
            )
        };
        assert!(changed >= 2, "changed={changed}");

        // torch cell = 14; neighbor = 13
        let r0 = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_readResult(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                0,
                64,
                0,
            )
        };
        assert_eq!(r0, 14);
        let r1 = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_readResult(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                1,
                64,
                0,
            )
        };
        assert_eq!(r1, 13);

        // double propagate rejected (one job per context)
        let again = unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_propagate(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
            )
        };
        assert_eq!(again, -2);

        unsafe {
            Java_com_rustcraft_bridge_LightBatchCtx_close(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
            );
        }
    }
}
