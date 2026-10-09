//! M3.0 prototype JNI surface for the spawn-query index.
//! Standalone prototype only — NOT wired into any live pipeline.

use crate::GLOBAL_FFI_METRICS;
use spawn_index::SpawnIndex;
use std::ffi::c_void;
use std::panic::catch_unwind;

/// Opaque handle -> Box<SpawnIndex>.
fn from_handle(h: *mut c_void) -> Option<&'static mut SpawnIndex> {
    if h.is_null() {
        return None;
    }
    Some(unsafe { &mut *(h as *mut SpawnIndex) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_create(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> *mut c_void {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnCreate);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    Box::into_raw(Box::new(SpawnIndex::new())) as *mut c_void
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_freeRaw(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
) {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnFree);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    if !h.is_null() {
        drop(Box::from_raw(h as *mut SpawnIndex));
    }
}

/// Insert/replace an entry. components is a flat int[] of 6-int boxes.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_insert(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    rank: i32,
    valid: jboolean_t,
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
    comps_addr: i64,
    n_comp_ints: i32,
) {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnInsert);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    call.fallback_reason = metrics::FallbackReason::MissingState;
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ix = match from_handle(h) {
            Some(ix) => ix,
            None => return,
        };
        let comps: &[i32] = if comps_addr == 0 || n_comp_ints <= 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(comps_addr as *const i32, n_comp_ints as usize)
        };
        call.bytes.input_bytes = Some(std::mem::size_of_val(comps) as u64);
        call.bytes.borrowed_bytes = call.bytes.input_bytes;
        call.bytes.copied_bytes = None;
        ix.insert(
            rank as u32,
            valid != 0,
            min_x,
            min_y,
            min_z,
            max_x,
            max_y,
            max_z,
            comps,
        );
        call.fallback_reason = metrics::FallbackReason::None;
    }));
    if r.is_err() {
        call.fallback_reason = metrics::FallbackReason::Panic;
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_updateBox(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    rank: i32,
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
) {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnUpdateBox);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let r = catch_unwind(|| {
        if let Some(ix) = from_handle(h) {
            ix.update_box(rank as u32, min_x, min_y, min_z, max_x, max_y, max_z);
        }
    });
    let _ = r;
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_remove(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    rank: i32,
) {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnRemove);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let r = catch_unwind(|| {
        if let Some(ix) = from_handle(h) {
            ix.remove(rank as u32);
        }
    });
    let _ = r;
}

/// Exact query: winning rank, or -1 for miss.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_query(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    x: i32,
    y: i32,
    z: i32,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnQuery);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(std::panic::AssertUnwindSafe(|| match from_handle(h) {
        Some(ix) => ix.query(x, y, z),
        None => -1,
    }))
    .unwrap_or(-1)
}

/// Stats: [queries, candidates_examined, hits, live_entries, region_count,
/// approx_memory_bytes] — read-only snapshot.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_SpawnIndexInterop_stats(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    out: *mut i64,
    _out_len: i32,
) {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::SpawnStats);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    call.fallback_reason = metrics::FallbackReason::MissingState;
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(ix) = from_handle(h) {
            let st = [
                ix.stats.queries as i64,
                ix.stats.candidates_examined as i64,
                ix.stats.hits as i64,
                ix.len() as i64,
                ix.region_count() as i64,
                ix.approx_memory_bytes() as i64,
            ];
            if !out.is_null() {
                let slice = std::slice::from_raw_parts_mut(out, 6);
                call.bytes.borrowed_bytes = Some(std::mem::size_of_val(slice) as u64);
                slice.copy_from_slice(&st);
                call.bytes.output_bytes = Some(std::mem::size_of_val(slice) as u64);
                call.bytes.copied_bytes = call.bytes.output_bytes;
                call.fallback_reason = metrics::FallbackReason::None;
            }
        }
    }));
    if r.is_err() {
        call.fallback_reason = metrics::FallbackReason::Panic;
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
}

#[allow(non_camel_case_types)]
pub type jboolean_t = u8;
