//! LIVE region READ authority FFI (RUST_REGION_READ_DECOMPRESSION_AUTHORITY).
//!
//! Handle model mirrors RegionWriteCtx: `create` leaks a Box<RegionReader>
//! as i64; `close` reclaims it; single-owner free guaranteed Java-side.
//!
//! read(handle, x, z, outAddr, outCap) returns:
//!   > 0  : SUCCESS, the decompressed length; `outAddr` holds exactly that
//!          many bytes (full record, validated)
//!   0    : MISSING (vanilla's func_76704_a returns null for this chunk)
//!   < 0  : negated status (-2 CORRUPT_ENTRY, -3 UNSUPPORTED_COMPRESSION,
//!          -4 OUTPUT_TOO_SMALL, -5 IO_ERROR) — the caller MUST fail closed
//!          to the vanilla body; no bytes were written to `outAddr`
//!
//! Coherency: disk-derived per call; when a live WRITE engine is registered
//! for the same path, the read is serialized against it via read_lock (the
//! offline counterpart of the RegionFile monitor that already serializes
//! the Java hooks).

use std::os::raw::c_void;
use std::slice;
use std::sync::Arc;

use region_io::live::{EngineRegistry, STATUS_BAD_HANDLE, STATUS_INVALID_RECORD};
use region_io::live_read::{
    RegionReader, READ_CORRUPT_ENTRY, READ_IO_ERROR, READ_MISSING, READ_SUCCESS,
    READ_UNSUPPORTED_COMPRESSION,
};

type SharedReader = Arc<RegionReader>;

fn reader<'a>(handle: i64) -> Option<&'a SharedReader> {
    unsafe { (handle as *mut SharedReader).as_ref() }
}

/// JNI: open a live region reader. Path bytes at `path_addr` for `path_len`
/// bytes (UTF-8, no NUL). Returns handle (>0) or 0 on failure.
///
/// # Safety
/// `path_addr` must reference live memory of at least `path_len` bytes for
/// the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionReadCtx_create(
    _env: *mut c_void,
    _clazz: *mut c_void,
    path_addr: i64,
    path_len: i32,
) -> i64 {
    if path_addr == 0 || path_len <= 0 || path_len > 4096 {
        return 0;
    }
    let bytes = slice::from_raw_parts(path_addr as *const u8, path_len as usize);
    let path = match std::str::from_utf8(bytes) {
        Ok(s) if !s.bytes().any(|b| b == 0) => s.to_string(),
        _ => return 0,
    };
    match std::panic::catch_unwind(|| {
        region_io::live_read::RegionReader::open(std::path::Path::new(&path))
            .ok()
            .map(|r| Box::into_raw(Box::new(Arc::new(r))) as i64)
            .unwrap_or(0)
    }) {
        Ok(h) => h,
        Err(_) => 0,
    }
}

/// JNI: read + validate + decompress one chunk. See the module contract for
/// the packed return value. `out_addr` must reference writable memory of at
/// least `out_cap` bytes; on success exactly the returned length is filled.
///
/// # Safety
/// Numeric handles/addresses must originate from this module's create call;
/// `out_addr` must reference live, exclusively writable memory of at least
/// `out_cap` bytes for the duration of the call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionReadCtx_read(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    x: i32,
    z: i32,
    out_addr: i64,
    out_cap: i32,
) -> i64 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let r = match reader(handle) {
            Some(r) => r,
            None => return -(STATUS_BAD_HANDLE as i64),
        };
        if !(0..=31).contains(&x) || !(0..=31).contains(&z) {
            return -(STATUS_INVALID_RECORD as i64);
        }
        if out_addr == 0 || out_cap < 0 {
            return -(region_io::live_read::READ_OUTPUT_TOO_SMALL as i64);
        }
        let path = r.path().to_path_buf();
        let mut out = Vec::new();
        let status = if let Some(engine) = EngineRegistry::global().try_get(&path) {
            engine
                .read_lock(|| r.read_chunk(x as u8, z as u8, &mut out))
                .unwrap_or(Err("engine lock poisoned".into()))
        } else {
            r.read_chunk(x as u8, z as u8, &mut out)
        };
        match status {
            Ok(READ_SUCCESS) => {
                if out.len() > out_cap as usize {
                    return -(region_io::live_read::READ_OUTPUT_TOO_SMALL as i64);
                }
                let dst = slice::from_raw_parts_mut(out_addr as *mut u8, out.len());
                dst.copy_from_slice(&out);
                out.len() as i64
            }
            Ok(READ_MISSING) => 0,
            Ok(code) => -(code as i64),
            Err(_) => -(READ_IO_ERROR as i64),
        }
    }));
    match result {
        Ok(v) => v,
        Err(_) => -(READ_IO_ERROR as i64),
    }
}

/// JNI: close the reader handle. Idempotent (<=0 is a no-op).
///
/// # Safety
/// `handle` must be a value returned by create that has not been closed
/// (single-owner contract, enforced Java-side).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_RegionReadCtx_closeRaw(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i32 {
    let result = std::panic::catch_unwind(|| {
        if handle <= 0 {
            return 0;
        }
        drop(Box::from_raw(handle as *mut SharedReader));
        0
    });
    match result {
        Ok(c) => c,
        Err(_) => -1,
    }
}

/// JNI: diagnostic counters are Java-side (RustRegionReadHook); this export
/// exists for parity with RegionWriteCtx.statsSnapshot and reports the
/// static corruption/unsupported constants for the Java side to assert
/// against (status schema check). Returns the schema version.
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_bridge_RegionReadCtx_schemaVersion(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    1
}

// status constants re-exported for tests
pub use region_io::live_read::{
    READ_CORRUPT_ENTRY as FFI_READ_CORRUPT_ENTRY, READ_IO_ERROR as FFI_READ_IO_ERROR,
    READ_UNSUPPORTED_COMPRESSION as FFI_READ_UNSUPPORTED,
};

#[cfg(test)]
mod tests {
    use super::*;

    fn write_region(path: &std::path::Path, slot: usize, stream: &[u8]) {
        use std::io::{Seek, SeekFrom, Write};
        let mut f = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(path)
            .unwrap();
        let end = (4096 * 8).max(8192);
        let cur = f.metadata().unwrap().len() as usize;
        if cur < end {
            f.seek(SeekFrom::End(0)).unwrap();
            f.write_all(&vec![0u8; end - cur]).unwrap();
        }
        f.seek(SeekFrom::Start((2 * 4096) as u64)).unwrap();
        let total = (stream.len() + 1) as u32;
        f.write_all(&total.to_be_bytes()).unwrap();
        f.write_all(&[2u8]).unwrap();
        f.write_all(stream).unwrap();
        let entry = (2u32 << 8) | 1;
        f.seek(SeekFrom::Start((slot * 4) as u64)).unwrap();
        f.write_all(&entry.to_be_bytes()).unwrap();
        f.flush().unwrap();
    }

    #[test]
    fn ffi_read_roundtrip_and_missing() {
        let dir = std::env::temp_dir().join("ffi-region-read-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ffi-r.0.0.mca");
        let _ = std::fs::remove_file(&path);
        let payload = vec![9u8; 3000];
        let stream = {
            use std::io::Write as IoWrite;
            let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
            z.write_all(&payload).unwrap();
            z.finish().unwrap()
        };
        write_region(&path, 0, &stream);

        let pbytes = path.to_str().unwrap().as_bytes().to_vec();
        let h = unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_create(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                pbytes.as_ptr() as i64,
                pbytes.len() as i32,
            )
        };
        assert!(h > 0, "create failed");

        let mut out = vec![0u8; 65536];
        let n = unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_read(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                0,
                0,
                out.as_mut_ptr() as i64,
                out.len() as i32,
            )
        };
        assert_eq!(n, payload.len() as i64, "decompressed length");
        assert_eq!(&out[..n as usize], &payload[..]);

        // empty slot -> 0 (MISSING; vanilla null)
        let n = unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_read(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                5,
                5,
                out.as_mut_ptr() as i64,
                out.len() as i32,
            )
        };
        assert_eq!(n, 0, "MISSING maps to 0");

        // output too small -> negative -4, no bytes written
        let n = unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_read(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
                0,
                0,
                out.as_mut_ptr() as i64,
                8,
            )
        };
        assert_eq!(n, -(region_io::live_read::READ_OUTPUT_TOO_SMALL as i64));

        // bad handle
        let n = unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_read(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                0,
                0,
                0,
                out.as_mut_ptr() as i64,
                100,
            )
        };
        assert_eq!(n, -(STATUS_BAD_HANDLE as i64));

        unsafe {
            Java_com_rustcraft_bridge_RegionReadCtx_closeRaw(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                h,
            );
        }
    }
}
