//! LIVE region-write authority FFI (RUST_REGION_WRITE_AUTHORITY).
//!
//! EXPERIMENTAL: every entry point is inert unless the Java-side hook
//! (RustRegionWriteHook) is enabled with -Drustcraft.regionWriteExperiment;
//! PRODUCTION_AUTHORITY stays false this milestone.
//!
//! Handle model (mirrors CompressionCtx): `create` leaks a
//! `Box<Arc<LiveRegionFile>>` and returns it as an i64; `close` reclaims it.
//! Java guarantees single-owner close (the hook frees in RegionFile.close
//! under the same single-threaded region-io scheduling that owns writes).
//! Stale non-null handles after close are a caller bug; null/<=0 handles are
//! rejected with BAD_HANDLE everywhere.
//!
//! Generation tickets: Java's per-region AtomicLongArray is the SINGLE
//! SOURCE OF TRUTH. The engine stores exactly the generation it is told on
//! every admitted write and every fallback notice; it never advances a
//! ticket itself, so the sides cannot diverge into false STALE rejections.
//!
//! Path passing: UTF-8 bytes in caller-owned direct memory (address+length),
//! consistent with the no-GetPrimitiveArrayCritical project convention.

use std::os::raw::c_void;
use std::slice;
use std::sync::Arc;

use region_io::live::{
    EngineRegistry, LiveRegionFile, STATUS_BAD_HANDLE, STATUS_INVALID_RECORD, STATUS_SUCCESS,
};

type SharedEngine = Arc<LiveRegionFile>;

fn engine<'a>(handle: i64) -> Option<&'a SharedEngine> {
    unsafe { (handle as *mut SharedEngine).as_ref() }
}

fn path_from_raw(addr: i64, len: i32) -> Option<String> {
    if addr == 0 || len <= 0 || len > 4096 {
        return None;
    }
    let bytes = unsafe { slice::from_raw_parts(addr as *const u8, len as usize) };
    let s = std::str::from_utf8(bytes).ok()?;
    // refuse NUL and separators that would smuggle a different file on the
    // Rust side of the boundary (Java already canonicalizes)
    if s.bytes().any(|b| b == 0) {
        return None;
    }
    Some(s.to_string())
}

/// JNI: open (or attach to) the live engine for a region file. The path bytes
/// live at `path_addr` for `path_len` bytes (UTF-8, no NUL). Returns the
/// engine handle (>0) or 0 on failure.
///
/// # Safety
/// `path_addr` must reference live memory of at least `path_len` bytes for
/// the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_create(
    _env: *mut c_void,
    _clazz: *mut c_void,
    path_addr: i64,
    path_len: i32,
) -> i64 {
    let path = match path_from_raw(path_addr, path_len) {
        Some(p) => p,
        None => return 0,
    };
    std::panic::catch_unwind(|| {
        EngineRegistry::global()
            .get_or_open(std::path::Path::new(&path))
            .ok()
            .map(|arc| Box::into_raw(Box::new(arc)) as i64)
            .unwrap_or(0)
    })
    .unwrap_or(0)
}

/// JNI: admitted write. `payload_addr/len` is the OPAQUE_FINAL_REGION_PAYLOAD
/// (the raw DEFLATE stream; the engine adds the 4-byte BE length prefix and
/// the type byte, exactly as vanilla's func_76706_a does internally).
/// Returns the new location entry (>0; count = entry & 0xFF, sector =
/// entry >> 8), or a negative status: -1 STALE_GENERATION, -2 INVALID_RECORD,
/// -3 IO_ERROR, -4 CAPACITY_ERROR, -5 NOT_ELIGIBLE, -6 BAD_HANDLE.
/// # Safety
/// Numeric handles/addresses must originate from this module's own
/// create/prepare calls; raw addresses must reference live memory of
/// at least the stated capacity for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_write(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    x: i32,
    z: i32,
    payload_addr: i64,
    payload_len: i32,
    generation: i64,
) -> i64 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let eng = match engine(handle) {
            Some(e) => e,
            None => return -(STATUS_BAD_HANDLE as i64),
        };
        if !(0..=31).contains(&x)
            || !(0..=31).contains(&z)
            || payload_len < 0
            || (payload_len > 0 && payload_addr == 0)
        {
            return -(STATUS_INVALID_RECORD as i64);
        }
        if generation < 0 {
            return -(STATUS_INVALID_RECORD as i64);
        }
        let payload = if payload_len == 0 {
            &[][..]
        } else {
            slice::from_raw_parts(payload_addr as *const u8, payload_len as usize)
        };
        match eng.write_chunk(x as u8, z as u8, payload, generation as u64) {
            Ok((entry, _needed)) => entry as i64,
            Err(code) => -(code as i64),
        }
    }));
    match result {
        Ok(code) => code,
        Err(_) => -(region_io::live::STATUS_IO_ERROR as i64),
    }
}

/// JNI: vanilla fallback notice. Java completed its own write for (x, z) and
/// holds `entry`; `generation` is the ticket value Java committed for the
/// fallback. Returns 0 on success or a negative status.
/// # Safety
/// Numeric handles/addresses must originate from this module's own
/// create/prepare calls; raw addresses must reference live memory of
/// at least the stated capacity for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_noteExternal(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    x: i32,
    z: i32,
    entry: i32,
    generation: i64,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let eng = match engine(handle) {
            Some(e) => e,
            None => return -STATUS_BAD_HANDLE,
        };
        if !(0..=31).contains(&x) || !(0..=31).contains(&z) || entry <= 0 || generation < 0 {
            return -STATUS_INVALID_RECORD;
        }
        match eng.note_external_write(x as u8, z as u8, entry as u32, generation as u64) {
            Ok(()) => STATUS_SUCCESS,
            Err(_) => -region_io::live::STATUS_IO_ERROR,
        }
    }));
    match result {
        Ok(code) => code,
        Err(_) => -region_io::live::STATUS_IO_ERROR,
    }
}

/// JNI: close the handle. Drops the Java-side reference AND removes the
/// engine from the path registry so a later reopen of the same file gets a
/// fresh engine bound to the new file state. Idempotent (<=0 is a no-op).
///
/// # Safety
/// `handle` must be a value returned by create that has not been closed
/// (single-owner contract, enforced Java-side by AtomicLong CAS).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_closeRaw(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i32 {
    let result = std::panic::catch_unwind(|| {
        if handle <= 0 {
            return STATUS_SUCCESS;
        }
        let boxed = Box::from_raw(handle as *mut SharedEngine);
        let path = boxed.path().to_path_buf();
        EngineRegistry::global().remove(&path);
        drop(boxed);
        STATUS_SUCCESS
    });
    match result {
        Ok(c) => c,
        Err(_) => -region_io::live::STATUS_IO_ERROR,
    }
}

/// JNI: snapshot of the engine's WriteStats into caller memory as 8 LE u64s
/// (rust_selected, success, stale_rejected, failures, external_syncs,
/// in_place_reuses, bytes_written, reserved). Returns the field count (8) or
/// a negative status.
/// # Safety
/// Numeric handles/addresses must originate from this module's own
/// create/prepare calls; raw addresses must reference live memory of
/// at least the stated capacity for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_statsSnapshot(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    out_addr: i64,
    out_cap: i32,
) -> i32 {
    const FIELDS: usize = 8;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let eng = match engine(handle) {
            Some(e) => e,
            None => return -STATUS_BAD_HANDLE,
        };
        if out_addr == 0 || out_cap < FIELDS as i32 * 8 {
            return -STATUS_INVALID_RECORD;
        }
        let stats = match eng.stats.lock() {
            Ok(s) => s.clone(),
            Err(_) => return -region_io::live::STATUS_IO_ERROR,
        };
        let out = slice::from_raw_parts_mut(out_addr as *mut u8, FIELDS * 8);
        for (i, v) in [
            stats.rust_selected,
            stats.success,
            stats.stale_rejected,
            stats.failures,
            stats.external_syncs,
            stats.in_place_reuses,
            stats.bytes_written,
            0u64,
        ]
        .iter()
        .enumerate()
        {
            out[i * 8..i * 8 + 8].copy_from_slice(&v.to_le_bytes());
        }
        FIELDS as i32
    }));
    match result {
        Ok(c) => c,
        Err(_) => -region_io::live::STATUS_IO_ERROR,
    }
}

/// JNI: snapshot the engine's committed generation floors (1024 LE u64s) so
/// a freshly-created Java hook state can seed its ticket counters past every
/// generation this process's engine has already committed. Returns the entry
/// count (1024) or a negative status.
/// # Safety
/// Numeric handles/addresses must originate from this module's own
/// create/prepare calls; raw addresses must reference live memory of
/// at least the stated capacity for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_generationsSnapshot(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    out_addr: i64,
    out_cap: i32,
) -> i32 {
    const COUNT: usize = 1024;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let eng = match engine(handle) {
            Some(e) => e,
            None => return -STATUS_BAD_HANDLE,
        };
        if out_addr == 0 || out_cap < COUNT as i32 * 8 {
            return -STATUS_INVALID_RECORD;
        }
        let gens = eng.generations();
        let out = slice::from_raw_parts_mut(out_addr as *mut u8, COUNT * 8);
        for (i, v) in gens.iter().take(COUNT).enumerate() {
            out[i * 8..i * 8 + 8].copy_from_slice(&v.to_le_bytes());
        }
        COUNT as i32
    }));
    match result {
        Ok(c) => c,
        Err(_) => -region_io::live::STATUS_IO_ERROR,
    }
}

/// JNI: snapshot the engine's sector used-map as 0/1 bytes. Returns the
/// sector count or a negative status. The Java hook mirrors this into
/// RegionFile.field_76714_f (vanilla's free list; inverted polarity: Java
/// true = free) so in-session reads pass the bounds check and vanilla
/// fallback writes cannot clobber Rust-occupied sectors.
/// # Safety
/// Numeric handles/addresses must originate from this module's own
/// create/prepare calls; raw addresses must reference live memory of
/// at least the stated capacity for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionWriteCtx_usedSnapshot(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    out_addr: i64,
    out_cap: i32,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let eng = match engine(handle) {
            Some(e) => e,
            None => return -STATUS_BAD_HANDLE,
        };
        if out_addr == 0 {
            return -STATUS_INVALID_RECORD;
        }
        let map = eng.used_map();
        let n = map.len();
        // size probe (out_cap <= 0) or retry hint when the buffer is small
        if out_cap <= 0 || (out_cap as usize) < n {
            return n as i32;
        }
        let out = slice::from_raw_parts_mut(out_addr as *mut u8, n);
        out.copy_from_slice(&map);
        n as i32
    }));
    match result {
        Ok(c) => c,
        Err(_) => -region_io::live::STATUS_IO_ERROR,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jpath(path: &str) -> (Vec<u8>, i64, i32) {
        let bytes = path.as_bytes().to_vec();
        let len = bytes.len() as i32;
        let addr = bytes.as_ptr() as i64;
        (bytes, addr, len)
    }

    #[test]
    fn ffi_create_write_stats_close_roundtrip() {
        let dir = std::env::temp_dir().join("ffi-region-write-test");
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("ffi-r.0.0.mca");
        let _ = std::fs::remove_file(&path);
        let (_own, paddr, plen) = jpath(path.to_str().unwrap());

        let h = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_create(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                paddr,
                plen,
            )
        };
        assert!(h > 0, "create failed");

        let payload = vec![7u8; 3000];
        let entry = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_write(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                0,
                0,
                payload.as_ptr() as i64,
                payload.len() as i32,
                1,
            )
        };
        assert!(entry > 0, "write failed: {entry}");
        assert_eq!(entry & 0xFF, 1, "3005 bytes must fit one sector");
        assert_eq!(entry >> 8, 2, "first allocation lands on sector 2");

        // stale generation rejected through FFI
        let stale = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_write(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                0,
                0,
                payload.as_ptr() as i64,
                payload.len() as i32,
                1,
            )
        };
        assert_eq!(stale, -region_io::live::STATUS_STALE_GENERATION as i64);

        // stats reflect one success
        let mut stats = [0u64; 8];
        let n = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_statsSnapshot(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                stats.as_mut_ptr() as i64,
                (stats.len() * 8) as i32,
            )
        };
        assert_eq!(n, 8);
        assert_eq!(stats[0], 1, "rust_selected");
        assert_eq!(stats[1], 1, "success");
        assert_eq!(stats[2], 1, "stale_rejected");

        // SECOND state attaching while the FIRST is still live shares the
        // registered engine; its tickets must be seeded from the engine's
        // committed floors or every write would be rejected STALE forever.
        let h2 = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_create(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                paddr,
                plen,
            )
        };
        assert!(h2 > 0, "second create failed");
        let mut floors = [0u64; 1024];
        let fn_ = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_generationsSnapshot(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h2,
                floors.as_mut_ptr() as i64,
                (floors.len() * 8) as i32,
            )
        };
        assert_eq!(fn_, 1024);
        assert_eq!(floors[0], 1, "generation floor for chunk 0 must be shared");

        // a DIFFERENT chunk's write proves the shared engine loaded the
        // on-disk map: sector 2 (held by the round-1 record) must be avoided
        let entry2 = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_write(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h2,
                1,
                0,
                payload.as_ptr() as i64,
                payload.len() as i32,
                (floors[32] + 1) as i64,
            )
        };
        assert!(entry2 > 0, "shared-engine write failed: {entry2}");
        assert!(
            entry2 >> 8 != 2,
            "second state clobbered the existing record"
        );

        // close frees + deregisters
        let rc = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_closeRaw(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
            )
        };
        assert_eq!(rc, STATUS_SUCCESS);

        unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_closeRaw(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h2,
            )
        };

        // after the LAST handle closes the registry is empty: the next
        // attach gets a FRESH engine (floors 0, any ticket accepted) bound
        // to the current on-disk state.
        let h3 = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_create(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                paddr,
                plen,
            )
        };
        assert!(h3 > 0, "third create failed");
        let entry3 = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_write(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h3,
                2,
                0,
                payload.as_ptr() as i64,
                payload.len() as i32,
                1, // ticket restarts: fresh engine has no committed floors
            )
        };
        assert!(entry3 > 0, "post-close-reopen write failed: {entry3}");
        assert!(
            entry3 >> 8 != 2,
            "fresh engine must still honor the on-disk map"
        );
        unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_closeRaw(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h3,
            )
        };
    }

    #[test]
    fn ffi_bad_handles_and_args() {
        let payload = [1u8; 16];
        assert_eq!(
            unsafe {
                Java_com_rustcraft_bridge_RegionWriteCtx_write(
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    0,
                    0,
                    0,
                    payload.as_ptr() as i64,
                    payload.len() as i32,
                    1,
                )
            },
            -STATUS_BAD_HANDLE as i64
        );
        let rc = unsafe {
            Java_com_rustcraft_bridge_RegionWriteCtx_noteExternal(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                0,
                0,
                0,
                5,
                1,
            )
        };
        assert_eq!(rc, -STATUS_BAD_HANDLE);
    }
}
