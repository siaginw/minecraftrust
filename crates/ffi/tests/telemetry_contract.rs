//! Real native calls with independently checked payloads and guarded buffers.
//! No JVM/Forge; the separate Java smoke checks the actual JNI/critical path.
use metrics::{FallbackReason, MeasurementSummary, Operation, GLOBAL_FFI_METRICS};
use rustcraft_ffi::*;
use std::ptr::null_mut;
use std::sync::Mutex;

static SERIAL: Mutex<()> = Mutex::new(());
fn lock() -> std::sync::MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}
fn delta(after: MeasurementSummary, before: MeasurementSummary) -> u64 {
    assert_eq!(
        after.unknown_samples, before.unknown_samples,
        "unexpected unknown sample"
    );
    after.sum_known - before.sum_known
}
fn biomes() -> Vec<u8> {
    let mut input = vec![0, 1, 0, 0, 1, 0];
    input.extend(0..=255);
    input
}
unsafe fn encode(input: &[u8], out: &mut [u8]) -> i32 {
    Java_com_rustcraft_bridge_NativeChunkPacket_encodeSections(
        null_mut(),
        null_mut(),
        input.as_ptr() as i64,
        input.len() as i32,
        out.as_mut_ptr() as i64,
        out.len() as i32,
    )
}

#[test]
fn actual_serialized_bytes_differ_from_input_capacity_and_operation_id() {
    let _serial = lock();
    let mut input = vec![0, 1, 0, 1, 0, 1, 0, 4, 0, 2];
    input.extend_from_slice(&0u32.to_be_bytes());
    input.extend_from_slice(&300u32.to_be_bytes());
    input.extend_from_slice(&256u16.to_be_bytes());
    input.extend_from_slice(&[0x11; 2048]);
    input.extend_from_slice(&[0x7b; 2048]);
    // Independently specified wire header, storage and block-light bytes.
    let mut expected = vec![4, 2, 0, 0xac, 2, 0x80, 2];
    expected.extend_from_slice(&[0x11; 2048]);
    expected.extend_from_slice(&[0x7b; 2048]);
    let mut out = vec![0xa5u8; 9000];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    let written = unsafe { encode(&input, &mut out) };
    assert_eq!(written as usize, expected.len());
    assert_eq!(&out[..expected.len()], expected);
    assert!(out[expected.len()..].iter().all(|b| *b == 0xa5));
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(after.call_count - before.call_count, 1);
    assert_eq!(delta(after.input_bytes, before.input_bytes), 4116);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 4103);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 13_116);
    assert_eq!(delta(after.retained_bytes, before.retained_bytes), 0);
    assert_eq!(delta(after.allocation_bytes, before.allocation_bytes), 0);
    assert_eq!(
        after.copied_bytes.unknown_samples - before.copied_bytes.unknown_samples,
        1
    );
}

#[test]
fn rejected_capacity_has_no_admitted_input_or_successful_payload() {
    let _serial = lock();
    let input = biomes();
    let mut out = [0xa5u8; 255];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(unsafe { encode(&input, &mut out) }, -2);
    assert_eq!(out, [0xa5u8; 255]);
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(delta(after.input_bytes, before.input_bytes), 0);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 0);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 0);
    assert_eq!(
        after.fallback_reason[FallbackReason::Capacity as usize]
            - before.fallback_reason[FallbackReason::Capacity as usize],
        1
    );
}

#[test]
fn partial_scratch_write_is_not_success_and_valid_retry_is_independent() {
    let _serial = lock();
    let valid = biomes();
    let mut malformed = valid.clone();
    malformed.push(99);
    let mut out = [0xa5u8; 512];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(unsafe { encode(&malformed, &mut out) }, -6);
    // Existing encoder writes scratch before detecting trailing input.
    assert_eq!(&out[..256], &valid[6..]);
    let failed = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(delta(failed.output_bytes, before.output_bytes), 0);
    assert_eq!(delta(failed.input_bytes, before.input_bytes), 263);
    assert_eq!(unsafe { encode(&valid, &mut out) }, 256);
    let retried = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(delta(retried.output_bytes, failed.output_bytes), 256);
    assert_eq!(delta(retried.input_bytes, failed.input_bytes), 262);
    assert_eq!(retried.call_count - before.call_count, 2);
}

#[test]
fn contained_panic_is_classified_and_allocation_is_unknown() {
    let _serial = lock();
    let mut out = [0xa5u8; 256];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeChunkPacket_encodeSections(
                null_mut(),
                null_mut(),
                -999,
                262,
                out.as_mut_ptr() as i64,
                256,
            )
        },
        -5
    );
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::EncodeSections);
    assert_eq!(out, [0xa5u8; 256]);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 0);
    assert_eq!(
        after.allocation_bytes.unknown_samples - before.allocation_bytes.unknown_samples,
        1
    );
    assert_eq!(
        after.fallback_reason[FallbackReason::Panic as usize]
            - before.fallback_reason[FallbackReason::Panic as usize],
        1
    );
}

#[test]
fn native_refresh_and_copies_account_optional_arrays_and_actual_payload() {
    let _serial = lock();
    let dim = -30101;
    let primer = vec![0u16; 65536];
    let biome = [42u8; 256];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkRegisterPrimer);
    let generation = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_registerPrimer(
            null_mut(),
            null_mut(),
            dim,
            1,
            2,
            primer.as_ptr() as i64,
            biome.as_ptr() as i64,
        )
    };
    assert!(generation > 0);
    let registered = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkRegisterPrimer);
    assert_eq!(delta(registered.input_bytes, before.input_bytes), 131_328);
    assert_eq!(delta(registered.output_bytes, before.output_bytes), 0); // Handle is not payload.
    assert_eq!(
        registered.allocation_bytes.unknown_samples - before.allocation_bytes.unknown_samples,
        1
    );
    let states = [1u16; 4096];
    let light = [0x5au8; 2048];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkRefreshSection);
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeChunkBridge_refreshSection(
                null_mut(),
                null_mut(),
                dim,
                1,
                2,
                0,
                states.as_ptr() as i64,
                light.as_ptr() as i64,
                0,
            )
        },
        0
    );
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkRefreshSection);
    assert_eq!(delta(after.input_bytes, before.input_bytes), 10_240);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 10_240);
    let mut out = [0xa5u8; 300];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkGetBiomes);
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeChunkBridge_getBiomes(
                null_mut(),
                null_mut(),
                dim,
                1,
                2,
                out.as_mut_ptr() as i64,
            )
        },
        1
    );
    assert_eq!(&out[..256], &biome);
    assert_eq!(&out[256..], &[0xa5u8; 44]);
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkGetBiomes);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 256);
    assert_eq!(delta(after.copied_bytes, before.copied_bytes), 256);
    unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_unloadChunk(null_mut(), null_mut(), dim, 1, 2);
    }
}

#[test]
fn coherent_result_count_comes_from_success_not_capacity_or_mask() {
    let _serial = lock();
    let reg = get_registry();
    let mut chunk = native_chunk::NativeChunk::new(-30102, 0, 0, reg.next_generation_id());
    chunk.fill_biomes(17);
    let handle = reg.insert(chunk);
    let mut out = [0xa5u8; 1024];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkEncodePacketPayloadV2);
    let packed = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayloadV2(
            null_mut(),
            null_mut(),
            -30102,
            0,
            0,
            handle.generation_id as i64,
            0,
            1,
            out.as_mut_ptr() as i64,
            out.len() as i32,
        )
    };
    assert_eq!(packed, PACKET_V2_SUCCESS_TAG | (256 << 16));
    assert_eq!(&out[..256], &[17; 256]);
    assert_eq!(&out[256..], &[0xa5u8; 768]);
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkEncodePacketPayloadV2);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 256);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 1024);
    reg.remove(handle.key);
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayloadV2(
                null_mut(),
                null_mut(),
                -30102,
                0,
                0,
                handle.generation_id as i64,
                0,
                1,
                out.as_mut_ptr() as i64,
                out.len() as i32,
            )
        },
        -2
    );
    let failed = GLOBAL_FFI_METRICS.snapshot(Operation::ChunkEncodePacketPayloadV2);
    assert_eq!(delta(failed.output_bytes, after.output_bytes), 0);
    assert_eq!(
        failed.fallback_reason[FallbackReason::MissingState as usize]
            - after.fallback_reason[FallbackReason::MissingState as usize],
        1
    );
}

#[test]
fn noise_output_is_generated_length_not_offered_capacity() {
    let _serial = lock();
    let handle =
        unsafe { Java_com_rustcraft_interop_WNoiseInterop_create(null_mut(), null_mut(), 42, 2) };
    assert!(!handle.is_null());
    let mut out = [f64::NAN; 850];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::NoiseGen3d);
    unsafe {
        Java_com_rustcraft_interop_WNoiseInterop_gen3d(
            null_mut(),
            null_mut(),
            handle,
            0,
            0,
            0,
            5,
            33,
            5,
            0.1,
            0.2,
            0.3,
            out.as_mut_ptr() as i64,
            out.len() as i32,
        );
    }
    let mut random = worldgen_noise::JavaRandom::new(42);
    let reference = worldgen_noise::Octaves::new(&mut random, 2);
    let expected = reference.generate3d(None, 0, 0, 0, 5, 33, 5, 0.1, 0.2, 0.3);
    assert_eq!(&out[..825], expected);
    assert!(out[825..].iter().all(|v| v.is_nan()));
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::NoiseGen3d);
    assert_eq!(delta(after.output_bytes, before.output_bytes), 6600);
    assert_eq!(delta(after.copied_bytes, before.copied_bytes), 6600);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 6800);
    assert_eq!(
        after.allocation_bytes.unknown_samples - before.allocation_bytes.unknown_samples,
        1
    );
    unsafe {
        Java_com_rustcraft_interop_WNoiseInterop_freeRaw(null_mut(), null_mut(), handle);
    }
}

#[test]
fn compression_counts_actual_length_and_keeps_native_allocation_unknown() {
    let _serial = lock();
    let handle = unsafe { Java_com_rustcraft_bridge_CompressionCtx_create(null_mut(), null_mut()) };
    assert!(handle > 0);
    let input = [0x31u8; 2048];
    let mut out = [0xa5u8; 4096];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::CompressionCompress);
    let written = unsafe {
        Java_com_rustcraft_bridge_CompressionCtx_compress(
            null_mut(),
            null_mut(),
            handle,
            input.as_ptr() as i64,
            input.len() as i32,
            out.as_mut_ptr() as i64,
            out.len() as i32,
        )
    };
    assert!(written > 0 && written < input.len() as i32);
    assert_eq!(out[0], 0x78); // zlib stream header; backend is unchanged.
    assert!(out[written as usize..].iter().all(|v| *v == 0xa5));
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::CompressionCompress);
    assert_eq!(delta(after.input_bytes, before.input_bytes), 2048);
    assert_eq!(
        delta(after.output_bytes, before.output_bytes),
        written as u64
    );
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 6144);
    assert_eq!(
        after.allocation_bytes.unknown_samples - before.allocation_bytes.unknown_samples,
        1
    );
    unsafe {
        Java_com_rustcraft_bridge_CompressionCtx_freeRaw(null_mut(), null_mut(), handle);
    }
}

#[test]
fn owned_transport_rejection_reports_only_the_input_actually_admitted() {
    let _serial = lock();
    let malformed = [0u8; 130];
    let mut out = [0xa5u8; 512];
    let before = GLOBAL_FFI_METRICS.snapshot(Operation::OwnedSnapshotEncodeV1);
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1(
                null_mut(),
                null_mut(),
                malformed.as_ptr() as i64,
                130,
                out.as_mut_ptr() as i64,
                512,
            )
        },
        -6
    );
    assert_eq!(out, [0xa5u8; 512]);
    let after = GLOBAL_FFI_METRICS.snapshot(Operation::OwnedSnapshotEncodeV1);
    assert_eq!(delta(after.input_bytes, before.input_bytes), 130);
    assert_eq!(delta(after.borrowed_bytes, before.borrowed_bytes), 130); // output not exposed yet
    assert_eq!(delta(after.output_bytes, before.output_bytes), 0);
}

#[test]
fn diagnostic_snapshot_is_versioned_bounded_and_does_not_observe_itself() {
    let _serial = lock();
    assert_eq!(
        Java_com_rustcraft_bridge_NativeFfiTelemetry_schemaVersion(null_mut(), null_mut()),
        2
    );
    assert_eq!(rust_runtime_ping(), 42);
    let calls = rust_runtime_get_ffi_calls();
    let mut out = [0xa5u8; FFI_METRICS_V2_SNAPSHOT_BYTES + 16];
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeFfiTelemetry_readOperationV2(
                null_mut(),
                null_mut(),
                0,
                out.as_mut_ptr() as i64,
                (FFI_METRICS_V2_SNAPSHOT_BYTES - 1) as i32,
            )
        },
        -1
    );
    assert!(out.iter().all(|v| *v == 0xa5));
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeFfiTelemetry_readOperationV2(
                null_mut(),
                null_mut(),
                511,
                out.as_mut_ptr() as i64,
                out.len() as i32,
            )
        },
        -1
    );
    assert!(out.iter().all(|v| *v == 0xa5));
    assert_eq!(
        unsafe {
            Java_com_rustcraft_bridge_NativeFfiTelemetry_readOperationV2(
                null_mut(),
                null_mut(),
                0,
                out.as_mut_ptr() as i64,
                out.len() as i32,
            )
        },
        FFI_METRICS_V2_SNAPSHOT_BYTES as i32
    );
    let word = |index: usize| u64::from_le_bytes(out[index * 8..index * 8 + 8].try_into().unwrap());
    assert_eq!((word(0), word(1)), (2, 0));
    assert!(word(2) >= 1);
    assert_eq!(word(3), 0); // input sum
    assert!(word(4) >= 1); // known zero samples
    assert_eq!(word(5), 0); // no unknown input samples
    assert_eq!(rust_runtime_get_ffi_calls(), calls);
    assert!(out[FFI_METRICS_V2_SNAPSHOT_BYTES..]
        .iter()
        .all(|v| *v == 0xa5));
}
