//! Dedicated JNI bridge crate.
//! Architectural invariant: this is the ONLY crate permitted to depend on JNI.

use metrics::GLOBAL_FFI_METRICS;
use std::ffi::c_void;

fn packet_fallback_reason(code: i32) -> metrics::FallbackReason {
    use metrics::FallbackReason;
    match code {
        0.. => FallbackReason::None,
        -1 => FallbackReason::InvalidArgument,
        -2 => FallbackReason::Capacity,
        -5 => FallbackReason::Panic,
        _ => FallbackReason::CorruptInput,
    }
}

mod spawn;
pub use spawn::*;
mod wnoise;
pub use wnoise::*;
mod native_chunk;
pub use native_chunk::*;
mod packet_encode_v2;
pub use packet_encode_v2::*;
mod owned_snapshot;
pub use owned_snapshot::*;
mod region_write;
pub use region_write::*;
mod region_read;
pub use region_read::*;

#[no_mangle]
pub extern "C" fn rust_runtime_ping() -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::RuntimePing);
    call.bytes = metrics::ByteMeasurements::ZERO;
    call.fallback_reason = metrics::FallbackReason::None;
    42
}

#[no_mangle]
pub extern "C" fn rust_runtime_get_ffi_calls() -> u64 {
    GLOBAL_FFI_METRICS
        .call_count
        .load(std::sync::atomic::Ordering::Relaxed)
}

/// JNI export for native Protocol 340 SPacketChunkData section payload encoding.
///
/// # Safety
/// `staging_buf_address` and `output_buf_address` must be valid direct off-heap addresses.
/// Wrapped in `std::panic::catch_unwind` to ensure panics never cross the JNI boundary.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_encodeSections(
    _env: *mut c_void,
    _clazz: *mut c_void,
    staging_buf_address: i64,
    staging_buf_len: i32,
    output_buf_address: i64,
    output_buf_capacity: i32,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::EncodeSections);

    call.bytes = metrics::ByteMeasurements::ZERO;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if staging_buf_address == -999 {
            panic!("Controlled test panic inside encodeSections");
        }
        if staging_buf_address == 0 || output_buf_address == 0 {
            return chunk_packet::ChunkPacketError::NullBufferPointer as i32;
        }
        if staging_buf_len <= 0 {
            return chunk_packet::ChunkPacketError::CorruptStagingData as i32;
        }
        if output_buf_capacity < 256 {
            return chunk_packet::ChunkPacketError::OutputBufferOverflow as i32;
        }

        // The raw encoder admits slices only for this validated size range.
        if (6..=chunk_packet::MAX_STAGING_CAPACITY).contains(&(staging_buf_len as usize)) {
            call.bytes.input_bytes = Some(staging_buf_len as u64);
            call.bytes.borrowed_bytes = Some(staging_buf_len as u64 + output_buf_capacity as u64);
            // Serialization includes copies not counted by the FFI wrapper.
            call.bytes.copied_bytes = None;
        }
        let staging_ptr = staging_buf_address as *const u8;
        let output_ptr = output_buf_address as *mut u8;

        match chunk_packet::encode_sections_raw(
            staging_ptr,
            staging_buf_len as usize,
            output_ptr,
            output_buf_capacity as usize,
        ) {
            Ok(bytes_written) => bytes_written as i32,
            Err(err) => err as i32,
        }
    }));

    let code = match result {
        Ok(code) => code,
        Err(_) => chunk_packet::ChunkPacketError::RustPanicCaught as i32,
    };
    call.bytes.output_bytes = Some(if code >= 0 { code as u64 } else { 0 });
    call.fallback_reason = packet_fallback_reason(code);
    if code == chunk_packet::ChunkPacketError::RustPanicCaught as i32 {
        call.bytes.allocation_bytes = None; // Panic machinery may allocate.
        call.bytes.copied_bytes = None;
    }
    code
}

/// M1.4-R2 §5: exact output-size prediction. Pure scan, zero writes.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_predictOutputLen(
    _env: *mut c_void,
    _clazz: *mut c_void,
    staging_buf_address: i64,
    staging_buf_len: i32,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::PredictOutputLen);
    call.bytes = metrics::ByteMeasurements::ZERO;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if staging_buf_address == 0 || staging_buf_len <= 0 {
            return chunk_packet::ChunkPacketError::CorruptStagingData as i32;
        }
        let staging =
            std::slice::from_raw_parts(staging_buf_address as *const u8, staging_buf_len as usize);
        call.bytes.input_bytes = Some(staging.len() as u64);
        call.bytes.borrowed_bytes = call.bytes.input_bytes;
        match chunk_packet::predict_output_len(staging) {
            Ok(len) => len as i32,
            Err(e) => e as i32,
        }
    }));
    let code = match result {
        Ok(code) => code,
        Err(_) => chunk_packet::ChunkPacketError::RustPanicCaught as i32,
    };
    call.fallback_reason = packet_fallback_reason(code);
    if code == chunk_packet::ChunkPacketError::RustPanicCaught as i32 {
        call.bytes.allocation_bytes = None;
    }
    code
}

/// JNI crossing-overhead calibration export: does zero work so the measured
/// round-trip is pure JNI transition cost. Section 0B/0 of M1.4 requires this
/// to be measured, never assumed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_jniNoop(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    0
}

/// Test-only helper to verify JNI catch_unwind boundary without side effects.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_testForcedPanic(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    let result = std::panic::catch_unwind(|| {
        panic!("Controlled Rust test panic for JNI boundary verification");
    });
    match result {
        Ok(_) => 0,
        Err(_) => chunk_packet::ChunkPacketError::RustPanicCaught as i32,
    }
}

/// M1.4-R regression-test helper (Netty pooled-buffer addressing bug).
/// Fills `len` bytes at raw native address `addr` with `pattern`.
/// Used ONLY by NettyAddressRegressionTest to prove native writes stay inside
/// the intended pooled ByteBuf (see docs/engineering/netty-pooled-buffer-addressing.md).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_fillPattern(
    _env: *mut c_void,
    _clazz: *mut c_void,
    addr: u64,
    len: u64,
    pattern: u8,
) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dst = std::slice::from_raw_parts_mut(addr as *mut u8, len as usize);
        dst.fill(pattern);
    }));
    match result {
        Ok(_) => 0,
        Err(_) => chunk_packet::ChunkPacketError::RustPanicCaught as i32,
    }
}

// ---------------------------------------------------------------------------
// M1.4-R2 HANDOFF-A: direct encode into packet heap byte[] via
// GetPrimitiveArrayCritical. Raw JNI env access (no jni-crate dependency):
// JNIEnv function-table indices are fixed by the JNI specification.
//   GetArrayLength              = slot 171 (4 reserved void* precede fn slots)
//   GetPrimitiveArrayCritical   = slot 222
//   ReleasePrimitiveArrayCritical = slot 223
// Critical section contract (§4): Rust writes ONLY; no Java callbacks, no
// allocation, no blocking, no retention; pointer released before return.
// ---------------------------------------------------------------------------
// C-ABI JNIEnv: the pointer IS the JNINativeInterface_ functions table
// (jni.h typedef for C; no wrapper struct). Table layout: reserved[4] then
// function pointers, so table.add(idx) indexes struct members directly.

const JNI_GET_ARRAY_LENGTH: usize = 171;
const JNI_GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const JNI_RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_encodeSectionsIntoArray(
    env: *mut c_void,
    _clazz: *mut c_void,
    staging_buf_address: i64,
    staging_buf_len: i32,
    output_array: *mut c_void, // jbyteArray
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::EncodeSectionsIntoArray);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    // Never panic across the boundary; never hold the critical pointer on error paths.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(AssertUnwindSafeClosure(
        || {
            use std::panic::AssertUnwindSafe;
            let _ = AssertUnwindSafe(()); // placeholder to keep closure Send-agnostic
                                          // env arg IS JNIEnv* (= pointer to the table pointer). JNI functions
                                          // also take JNIEnv* — pass `env` itself through, never the table.
            let table_pp = env as *const *const *const c_void;
            if table_pp.is_null() || (*table_pp).is_null() {
                return chunk_packet::ChunkPacketError::NullBufferPointer as i32;
                // env bad
            }
            let table = *table_pp; // JNINativeInterface_ const* (function slots)
            let jenv = env; // functions receive JNIEnv* = env
            let get_critical: unsafe extern "system" fn(
                *mut c_void,
                *mut c_void,
                *mut c_void, // jboolean* isCopy (may be null)
            ) -> *mut c_void = std::mem::transmute(*table.add(JNI_GET_PRIMITIVE_ARRAY_CRITICAL));
            let release_critical: unsafe extern "system" fn(
                *mut c_void,
                *mut c_void,
                *mut c_void,
                i32, // mode: 0 = copy back + free (only legal value for critical)
            ) = std::mem::transmute(*table.add(JNI_RELEASE_PRIMITIVE_ARRAY_CRITICAL));

            if staging_buf_address == 0 || staging_buf_len <= 0 || output_array.is_null() {
                return chunk_packet::ChunkPacketError::CorruptStagingData as i32;
            }
            let staging = std::slice::from_raw_parts(
                staging_buf_address as *const u8,
                staging_buf_len as usize,
            );

            call.bytes.input_bytes = Some(staging.len() as u64);
            call.bytes.borrowed_bytes = call.bytes.input_bytes;
            // Predict exact size first (§5): array must be exactly payload-sized.
            let predicted = match chunk_packet::predict_output_len(staging) {
                Ok(n) => n,
                Err(e) => return e as i32,
            };

            let array_len: usize = {
                // GetArrayLength = index 171
                let get_len: unsafe extern "system" fn(*mut c_void, *mut c_void) -> i32 =
                    std::mem::transmute(*table.add(JNI_GET_ARRAY_LENGTH));
                get_len(jenv as *mut c_void, output_array) as usize
            };
            if array_len != predicted {
                return chunk_packet::ChunkPacketError::OutputBufferOverflow as i32;
            }

            // The VM may allocate/copy for critical access; no exact amount is inferred.
            call.bytes.copied_bytes = None;
            let mut is_copy: u8 = 0;
            let raw = get_critical(
                jenv as *mut c_void,
                output_array,
                &mut is_copy as *mut u8 as *mut c_void,
            );
            if raw.is_null() {
                return chunk_packet::ChunkPacketError::MissingLightArray as i32;
                // DEBUG: critical returned null
            }
            // CRITICAL SECTION: bounded, deterministic writes only.
            let out_slice = std::slice::from_raw_parts_mut(raw as *mut u8, array_len);
            call.bytes.borrowed_bytes = Some(staging.len() as u64 + out_slice.len() as u64);
            let encode_result = chunk_packet::encode_sections(staging, out_slice);
            // END CRITICAL SECTION — release immediately (mode 0).
            release_critical(jenv as *mut c_void, output_array, raw, 0);
            let _ = is_copy; // §4: caller reads pin-vs-copy via separate probe; kept for future telemetry

            match encode_result {
                Ok(written) => written as i32,
                Err(e) => e as i32,
            }
        },
    )));
    let code = match result {
        Ok(code) => code,
        Err(_) => chunk_packet::ChunkPacketError::RustPanicCaught as i32,
    };
    call.bytes.output_bytes = Some(if code >= 0 { code as u64 } else { 0 });
    call.fallback_reason = packet_fallback_reason(code);
    code
}

/// Tiny helper so the closure above can take () and stay monomorphic.
#[inline]
fn AssertUnwindSafeClosure<F: FnOnce() -> i32>(f: F) -> impl FnOnce() -> i32 {
    move || {
        let _guard = std::panic::AssertUnwindSafe(());
        f()
    }
}

/// M1.4-R2 §4 debug: call GetVersion through candidate slots to find the
/// true table offset. GetVersion returns 0x00010008 (JNI 1.8).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkPacket_jniProbeVersion(
    env: *mut c_void,
    _clazz: *mut c_void,
    slot: i32,
) -> i32 {
    if env.is_null() {
        return -98;
    }
    let table_pp = env as *const *const *const c_void;
    let table = *table_pp;
    if table.is_null() {
        return -97;
    }
    let f: unsafe extern "system" fn(*mut c_void) -> i32 =
        std::mem::transmute(*table.add(slot as usize));
    f(table as *mut c_void)
}

// ---------------------------------------------------------------------------
// M2C: per-context native compressor ownership (replaces the M2.0 prototype
// global Mutex). One context per Java handler instance (per Netty channel).
// Explicit native handle lifecycle:
//   create  -> handle (Box<ZlibPacketCompressor> as *mut)
//   compress(handle, in, inLen, out, outCap) -> len | negative error
//   free(handle) -> drops; double-free guarded by null check (free(nullptr)
//                   is a no-op); stale non-null handles are a caller bug -
//   Java side guarantees single-owner free via AtomicLong + CAS.
// Invalid handle detection: null or -1 handle returns -4. No panic crosses
// JNI; deterministic context reset after BOTH success and error paths
// (compress_into resets internally; a capacity error leaves context clean
// and usable - unit-tested in crates/compression).
// ---------------------------------------------------------------------------

use compression::ZlibPacketCompressor;

/// JNI: create a compressor context. Returns handle (positive), 0 on failure.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_CompressionCtx_create(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i64 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::CompressionCreate);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    match std::panic::catch_unwind(
        || match ZlibPacketCompressor::new(compression::VANILLA_LEVEL) {
            Ok(c) => Box::into_raw(Box::new(c)) as i64,
            Err(_) => 0,
        },
    ) {
        Ok(h) if h != 0 => h,
        _ => 0,
    }
}

/// JNI: compress with an owned context. Returns compressed length or
/// negative code (-1 capacity, -2 backend/panic, -3 bad args, -4 bad handle).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_CompressionCtx_compress(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    in_addr: i64,
    in_len: i32,
    out_addr: i64,
    out_cap: i32,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::CompressionCompress);
    call.bytes.input_bytes = Some(0);
    call.bytes.output_bytes = Some(0);
    call.bytes.borrowed_bytes = Some(0);
    call.bytes.retained_bytes = Some(0);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle <= 0
            || in_len < 0
            || out_cap < 0
            || (in_len > 0 && in_addr == 0)
            || (out_cap > 0 && out_addr == 0)
        {
            return -3;
        }
        let ctx: &mut ZlibPacketCompressor = match (handle as *mut ZlibPacketCompressor).as_mut() {
            Some(c) => c,
            None => return -4,
        };
        let input = std::slice::from_raw_parts(in_addr as *const u8, in_len as usize);
        let output = std::slice::from_raw_parts_mut(out_addr as *mut u8, out_cap as usize);
        call.bytes.input_bytes = Some(input.len() as u64);
        call.bytes.borrowed_bytes = Some(input.len() as u64 + output.len() as u64);
        match ctx.compress_into(input, output) {
            Ok(n) => n as i32,
            Err(e) => e.as_code(),
        }
    }));
    let code = match result {
        Ok(code) => code,
        Err(_) => -2,
    };
    call.bytes.output_bytes = Some(if code >= 0 { code as u64 } else { 0 });
    call.fallback_reason = match code {
        0.. => metrics::FallbackReason::None,
        -1 => metrics::FallbackReason::Capacity,
        -3 => metrics::FallbackReason::InvalidArgument,
        -4 => metrics::FallbackReason::MissingState,
        _ if result.is_err() => metrics::FallbackReason::Panic,
        _ => metrics::FallbackReason::BackendError,
    };
    code
}

/// JNI: free an owned context. Double-free/null safe (no-op on 0/-1).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_CompressionCtx_freeRaw(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::CompressionFree);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let result = std::panic::catch_unwind(|| {
        if handle <= 0 {
            return 0;
        }
        drop(Box::from_raw(handle as *mut ZlibPacketCompressor));
        0
    });
    match result {
        Ok(c) => c,
        Err(_) => -1,
    }
}

/// Fixed little-endian diagnostic layout: schema, operation ID, completed calls;
/// then seven (sum_known, known_samples, unknown_samples, overflowed) groups in
/// input/output/copied/borrowed/retained/allocation/elapsed order; eight fallback
/// counters follow. Reads are observational during concurrent work and do not
/// themselves increment measured operation counts. No existing JNI ABI changes.
pub const FFI_METRICS_V2_SNAPSHOT_BYTES: usize = (3 + 7 * 4 + 8) * 8;

#[no_mangle]
pub extern "system" fn Java_com_rustcraft_bridge_NativeFfiTelemetry_schemaVersion(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i32 {
    metrics::FFI_METRICS_SCHEMA_VERSION as i32
}

/// # Safety
/// A positive output address must refer to a live, exclusively writable region
/// of output_capacity bytes. Numeric checks cannot establish pointer provenance.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeFfiTelemetry_readOperationV2(
    _env: *mut c_void,
    _class: *mut c_void,
    operation_id: i32,
    output_address: i64,
    output_capacity: i32,
) -> i32 {
    let operation = match metrics::Operation::ALL
        .iter()
        .copied()
        .find(|op| *op as i32 == operation_id)
    {
        Some(operation) => operation,
        None => return -1,
    };
    if output_address <= 0 || output_capacity < FFI_METRICS_V2_SNAPSHOT_BYTES as i32 {
        return -1;
    }
    let start = match usize::try_from(output_address) {
        Ok(start) => start,
        Err(_) => return -1,
    };
    if start
        .checked_add(FFI_METRICS_V2_SNAPSHOT_BYTES)
        .is_none_or(|end| end > isize::MAX as usize)
    {
        return -1;
    }
    std::panic::catch_unwind(|| {
        let sample = GLOBAL_FFI_METRICS.snapshot(operation);
        let mut words = [0u64; FFI_METRICS_V2_SNAPSHOT_BYTES / 8];
        words[0] = sample.schema_version as u64;
        words[1] = sample.operation_id as u64;
        words[2] = sample.call_count;
        let fields = [
            sample.input_bytes,
            sample.output_bytes,
            sample.copied_bytes,
            sample.borrowed_bytes,
            sample.retained_bytes,
            sample.allocation_bytes,
            sample.elapsed_ns,
        ];
        for (index, field) in fields.iter().enumerate() {
            let offset = 3 + index * 4;
            words[offset..offset + 4].copy_from_slice(&[
                field.sum_known,
                field.known_samples,
                field.unknown_samples,
                u64::from(field.overflowed),
            ]);
        }
        words[31..].copy_from_slice(&sample.fallback_reason);
        let output =
            std::slice::from_raw_parts_mut(start as *mut u8, FFI_METRICS_V2_SNAPSHOT_BYTES);
        for (index, word) in words.iter().enumerate() {
            output[index * 8..index * 8 + 8].copy_from_slice(&word.to_le_bytes());
        }
        FFI_METRICS_V2_SNAPSHOT_BYTES as i32
    })
    .unwrap_or(-2)
}
