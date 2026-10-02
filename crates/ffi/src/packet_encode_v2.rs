//! Versioned, single-operation chunk payload metadata. No packet authority.
//!
//! Success: bit 62 = 1; bits 46..16 = nonnegative Java int byte count;
//! bits 15..0 = emitted mask. Bits 63 and 61..47 must be zero. A zero
//! byte count requires a zero mask. Failure: exactly one of -1 through -7.
//! Every other encoding is reserved. See docs/research/issue1-jni-v2-contract.md.

use crate::native_chunk::get_registry;
use native_chunk::{
    ChunkHandle, ChunkKey, ChunkLifecycle, ChunkRegistry, NativeChunk, PacketEncodeResult,
};
use std::ffi::c_void;
use std::panic::{catch_unwind, UnwindSafe};

pub const PACKET_V2_SUCCESS_TAG: i64 = 1i64 << 62;
pub const PACKET_V2_MAX_BYTES: usize = i32::MAX as usize;

/// Observe only the result already returned by the one serialization operation.
pub(crate) fn observe_packed_result(call: &mut metrics::CallGuard<'_>, packed: i64) {
    use metrics::FallbackReason;
    call.bytes.output_bytes = Some(if packed > 0 {
        ((packed >> 16) & 0x7fff_ffff) as u64
    } else {
        0
    });
    call.fallback_reason = match packed {
        1.. => FallbackReason::None,
        -1 => FallbackReason::InvalidArgument,
        -2 | -3 => FallbackReason::MissingState,
        -4 => FallbackReason::CorruptInput,
        -5 => FallbackReason::Capacity,
        -7 => FallbackReason::Panic,
        _ => FallbackReason::BackendError,
    };
}

#[cfg(test)]
#[path = "../../../tools/testing/rust_property_support.rs"]
mod property_support;

#[cfg(test)]
mod properties {
    use super::*;
    use proptest::prelude::*;

    // Independent reference decoder used only for cross-checking the packer;
    // actual Java malformed decoding is exercised by PacketEncodeResultV2Test.
    fn decode(raw: i64) -> Option<PacketEncodeResult> {
        if raw < 0 || raw & PACKET_V2_SUCCESS_TAG == 0 || raw & !0x4000_7fff_ffff_ffff != 0 {
            return None;
        }
        let count = ((raw >> 16) & 0x7fff_ffff) as usize;
        let mask = (raw & 65535) as u16;
        if count == 0 && mask != 0 {
            return None;
        }
        Some(PacketEncodeResult {
            bytes_written: count,
            emitted_mask: mask,
        })
    }

    #[test]
    fn v2_roundtrip_and_corrupted_reserved_bits() {
        let strategy = (0u64..=i32::MAX as u64, any::<u16>(), 47u64..62)
            .prop_map(|(count, mask, bit)| vec![count, mask as u64, bit]);
        property_support::run("v2_roundtrip", strategy, |v| {
            prop_assert_eq!(v.len(), 3);
            let result = PacketEncodeResult {
                bytes_written: v[0] as usize,
                emitted_mask: if v[0] == 0 { 0 } else { v[1] as u16 },
            };
            let raw = pack_success(result).unwrap();
            prop_assert_eq!(decode(raw), Some(result));
            prop_assert_eq!(decode(raw | (1i64 << v[2])), None);
            prop_assert_eq!(decode(raw & !PACKET_V2_SUCCESS_TAG), None);
            prop_assert_eq!(decode(raw | i64::MIN), None);
            prop_assert_eq!(decode(PACKET_V2_SUCCESS_TAG | ((v[1] as i64) | 1)), None);
            Ok(())
        });
    }

    #[test]
    fn errors_have_no_success_fields_and_retry_is_independent() {
        let strategy = (1u64..=7, any::<u16>(), 1u64..=i32::MAX as u64)
            .prop_map(|(error, mask, count)| vec![error, mask as u64, count]);
        property_support::run("v2_failure_retry", strategy, |v| {
            prop_assert_eq!(v.len(), 3);
            let error = match v[0] {
                1 => PacketEncodeV2Error::InvalidArgument,
                2 => PacketEncodeV2Error::MissingHandle,
                3 => PacketEncodeV2Error::StaleGeneration,
                4 => PacketEncodeV2Error::MissingSelectedSection,
                5 => PacketEncodeV2Error::OutputCapacity,
                6 => PacketEncodeV2Error::EncodeFailure,
                _ => PacketEncodeV2Error::Panic,
            };
            prop_assert_eq!(result_boundary(|| Err(error)), -(v[0] as i64));
            prop_assert_eq!(decode(-(v[0] as i64)), None);
            let result = PacketEncodeResult {
                bytes_written: v[2] as usize,
                emitted_mask: v[1] as u16,
            };
            prop_assert_eq!(decode(result_boundary(|| Ok(result))), Some(result));
            prop_assert_eq!(
                result_boundary(|| Ok(PacketEncodeResult {
                    bytes_written: 0,
                    emitted_mask: (v[1] as u16) | 1
                })),
                -6
            );
            prop_assert_eq!(
                result_boundary(|| Ok(PacketEncodeResult {
                    bytes_written: PACKET_V2_MAX_BYTES + 1,
                    emitted_mask: v[1] as u16
                })),
                -6
            );
            Ok(())
        });
    }
}

/// Canonical failure values. They carry no byte-count or emitted-mask fields.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i64)]
pub enum PacketEncodeV2Error {
    InvalidArgument = -1,
    MissingHandle = -2,
    StaleGeneration = -3,
    MissingSelectedSection = -4,
    OutputCapacity = -5,
    EncodeFailure = -6,
    Panic = -7,
}

fn pack_success(result: PacketEncodeResult) -> Result<i64, PacketEncodeV2Error> {
    if result.bytes_written > PACKET_V2_MAX_BYTES
        || (result.bytes_written == 0 && result.emitted_mask != 0)
    {
        return Err(PacketEncodeV2Error::EncodeFailure);
    }
    // Widen before shifting. The highest possible set bit is 62, so this
    // conversion cannot set Java's signed-long sign bit or truncate a field.
    Ok(PACKET_V2_SUCCESS_TAG
        | ((result.bytes_written as i64) << 16)
        | i64::from(result.emitted_mask))
}

pub(crate) fn result_boundary<F>(operation: F) -> i64
where
    F: FnOnce() -> Result<PacketEncodeResult, PacketEncodeV2Error> + UnwindSafe,
{
    match catch_unwind(operation) {
        Ok(Ok(result)) => pack_success(result).unwrap_or_else(|error| error as i64),
        Ok(Err(error)) => error as i64,
        Err(_) => PacketEncodeV2Error::Panic as i64,
    }
}

fn classify_encode_error(error: &'static str) -> PacketEncodeV2Error {
    // The accepted Rust encoder currently uses string errors. Unknown errors
    // remain failures; they are never converted into length/mask metadata.
    match error {
        "Packet mask selects a missing section" => PacketEncodeV2Error::MissingSelectedSection,
        "Output buffer overflow" | "Output buffer overflow writing biomes" => {
            PacketEncodeV2Error::OutputCapacity
        }
        _ => PacketEncodeV2Error::EncodeFailure,
    }
}

/// Shared lock/staleness gate for both the encode and the measure paths.
fn with_live_chunk<T>(
    registry: &ChunkRegistry,
    handle: ChunkHandle,
    operation: impl FnOnce(&mut NativeChunk) -> Result<T, &'static str>,
) -> Result<T, PacketEncodeV2Error> {
    // Hold native membership through the operation. This excludes native removal or
    // replacement during the operation, but does NOT synchronize Java writers
    // or guarantee publication after this function returns.
    let map = registry
        .chunks_map()
        .read()
        .map_err(|_| PacketEncodeV2Error::EncodeFailure)?;
    let entry = map
        .get(&handle.key)
        .ok_or(PacketEncodeV2Error::MissingHandle)?;
    let mut chunk = entry
        .write()
        .map_err(|_| PacketEncodeV2Error::EncodeFailure)?;
    if chunk.generation_id != handle.generation_id
        || matches!(
            chunk.lifecycle,
            ChunkLifecycle::Invalidated | ChunkLifecycle::Unloading | ChunkLifecycle::Freed
        )
    {
        return Err(PacketEncodeV2Error::StaleGeneration);
    }
    operation(&mut chunk).map_err(classify_encode_error)
}

fn encode_in_registry(
    registry: &ChunkRegistry,
    handle: ChunkHandle,
    skylight: bool,
    full_chunk: bool,
    output: &mut [u8],
) -> Result<PacketEncodeResult, PacketEncodeV2Error> {
    let mut offset = 0;
    // Exactly one encoder invocation; no independent mask lookup, recomputation,
    // second serialization, or retry. Both fields pass through this return.
    with_live_chunk(registry, handle, |chunk| {
        chunk.encode_packet_payload(skylight, full_chunk, output, &mut offset)
    })
}

/// Measure path: same registry/staleness gate as the encode, but the chunk is
/// only scanned for its exact wire length — nothing is written anywhere.
fn measure_in_registry(
    registry: &ChunkRegistry,
    handle: ChunkHandle,
    skylight: bool,
    full_chunk: bool,
) -> Result<PacketEncodeResult, PacketEncodeV2Error> {
    with_live_chunk(registry, handle, |chunk| {
        chunk.measure_packet_payload(skylight, full_chunk)
    })
}

pub(crate) fn checked_output_address(
    address: i64,
    capacity: i32,
) -> Result<usize, PacketEncodeV2Error> {
    if address <= 0 || capacity <= 0 {
        return Err(PacketEncodeV2Error::InvalidArgument);
    }
    let start = usize::try_from(address).map_err(|_| PacketEncodeV2Error::InvalidArgument)?;
    let end = start
        .checked_add(capacity as usize)
        .ok_or(PacketEncodeV2Error::InvalidArgument)?;
    if end > isize::MAX as usize {
        return Err(PacketEncodeV2Error::InvalidArgument);
    }
    Ok(start)
}

/// One native encode -> one packed V2 result. Legacy length-only JNI is unchanged.
///
/// Invalid address/capacity/boolean arguments return -1. Nonpositive or stale
/// generation returns -3; absent coordinates return -2. Remaining error codes
/// are defined by `PacketEncodeV2Error`. On ANY failure, output is invalid scratch
/// and must be discarded. There is no valid count/mask to publish on that path.
///
/// # Safety
/// A nonzero address must designate a live writable allocation of at least
/// `output_buf_capacity` bytes, exclusively owned by this call. Numeric validation
/// cannot establish pointer validity, allocation size, or exclusive ownership.
/// The Java caller must retain that allocation through encoding and consumption.
/// `catch_unwind` contains Rust unwinding panics, not invalid-pointer access,
/// process aborts or allocator termination. Never pass fabricated nonzero pointers.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayloadV2(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    skylight: u8,
    full_chunk: u8,
    output_buf_address: i64,
    output_buf_capacity: i32,
) -> i64 {
    let mut call =
        metrics::GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkEncodePacketPayloadV2);
    call.bytes.input_bytes = Some(0);
    call.bytes.output_bytes = Some(0);
    call.bytes.borrowed_bytes = Some(0);
    call.bytes.retained_bytes = Some(0);
    let packed = result_boundary(std::panic::AssertUnwindSafe(|| {
        let address = checked_output_address(output_buf_address, output_buf_capacity)?;
        if skylight > 1 || full_chunk > 1 {
            return Err(PacketEncodeV2Error::InvalidArgument);
        }
        if generation_id <= 0 {
            return Err(PacketEncodeV2Error::StaleGeneration);
        }
        let output =
            std::slice::from_raw_parts_mut(address as *mut u8, output_buf_capacity as usize);
        call.bytes.borrowed_bytes = Some(output.len() as u64);
        encode_in_registry(
            get_registry(),
            ChunkHandle {
                key: ChunkKey::new(dim, cx, cz),
                generation_id: generation_id as u64,
            },
            skylight != 0,
            full_chunk != 0,
            output,
        )
    }));
    observe_packed_result(&mut call, packed);
    packed
}

/// Measure-only V2 twin: returns the exact byte count and emitted mask the
/// real encode would produce for the CURRENT chunk state, writing nothing.
///
/// Same packed-long protocol and failure codes as `encodePacketPayloadV2`
/// (minus the capacity codes, which cannot occur). This is the length oracle
/// that lets the Java single-copy path frame the complete pre-compression
/// packet header (including the payload-length VarInt) BEFORE the payload is
/// encoded straight into the final buffer — exactly one large payload memory
/// movement. The caller must still verify the real encode's byte count against
/// this value and fall back on any mismatch.
///
/// # Safety
/// Writes no memory; only registry locks and the chunk's own palette caches
/// are touched (idempotent, same discipline as the encode path).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayloadV2Measure(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    skylight: u8,
    full_chunk: u8,
) -> i64 {
    result_boundary(std::panic::AssertUnwindSafe(|| {
        if skylight > 1 || full_chunk > 1 {
            return Err(PacketEncodeV2Error::InvalidArgument);
        }
        if generation_id <= 0 {
            return Err(PacketEncodeV2Error::StaleGeneration);
        }
        measure_in_registry(
            get_registry(),
            ChunkHandle {
                key: ChunkKey::new(dim, cx, cz),
                generation_id: generation_id as u64,
            },
            skylight != 0,
            full_chunk != 0,
        )
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use native_chunk::NativeChunk;
    use std::cell::Cell;
    use std::panic::AssertUnwindSafe;

    #[test]
    fn success_layout_is_positive_and_lossless_at_boundaries() {
        for bytes in [0, 1, 256, 65_535, 65_536, PACKET_V2_MAX_BYTES] {
            for mask in [0, 1, 0x8001, 0xffff] {
                if bytes == 0 && mask != 0 {
                    continue;
                }
                let packed = pack_success(PacketEncodeResult {
                    bytes_written: bytes,
                    emitted_mask: mask,
                })
                .unwrap();
                assert!(packed > 0);
                assert_eq!(packed & PACKET_V2_SUCCESS_TAG, PACKET_V2_SUCCESS_TAG);
                assert_eq!((packed >> 16) & 0x7fff_ffff, bytes as i64);
                assert_eq!(packed & 0xffff, i64::from(mask));
                assert_eq!(packed & !0x4000_7fff_ffff_ffff, 0);
            }
        }
        assert_eq!(
            pack_success(PacketEncodeResult {
                bytes_written: PACKET_V2_MAX_BYTES,
                emitted_mask: 0xffff
            }),
            Ok(0x4000_7fff_ffff_ffff)
        );
    }

    #[test]
    fn unrepresentable_success_becomes_only_an_error() {
        for result in [
            PacketEncodeResult {
                bytes_written: PACKET_V2_MAX_BYTES + 1,
                emitted_mask: 0,
            },
            PacketEncodeResult {
                bytes_written: 0,
                emitted_mask: 1,
            },
        ] {
            assert_eq!(
                result_boundary(|| Ok(result)),
                PacketEncodeV2Error::EncodeFailure as i64
            );
        }
    }

    #[test]
    fn every_failure_is_canonical_and_has_no_success_tag_interpretation() {
        for error in [
            PacketEncodeV2Error::InvalidArgument,
            PacketEncodeV2Error::MissingHandle,
            PacketEncodeV2Error::StaleGeneration,
            PacketEncodeV2Error::MissingSelectedSection,
            PacketEncodeV2Error::OutputCapacity,
            PacketEncodeV2Error::EncodeFailure,
            PacketEncodeV2Error::Panic,
        ] {
            let packed = result_boundary(|| Err(error));
            // Check sign BEFORE examining any bit fields: negatives also have
            // bit 62 set in two's complement and must never be decoded as success.
            assert!(packed < 0);
            assert_eq!(packed, error as i64);
        }
    }

    #[test]
    fn boundary_invokes_operation_once_on_success_error_and_panic() {
        for mode in 0..3 {
            let calls = Cell::new(0);
            let packed = result_boundary(AssertUnwindSafe(|| {
                calls.set(calls.get() + 1);
                match mode {
                    0 => Ok(PacketEncodeResult {
                        bytes_written: 123,
                        emitted_mask: 3,
                    }),
                    1 => Err(PacketEncodeV2Error::EncodeFailure),
                    _ => panic!("test-only unwind inside the production result boundary"),
                }
            }));
            assert_eq!(calls.get(), 1);
            assert_eq!(
                packed,
                match mode {
                    0 => PACKET_V2_SUCCESS_TAG | (123 << 16) | 3,
                    1 => -6,
                    _ => -7,
                }
            );
        }
        // No sticky boundary state or invented metadata remains after a panic.
        assert_eq!(
            result_boundary(|| Ok(PacketEncodeResult {
                bytes_written: 0,
                emitted_mask: 0
            })),
            PACKET_V2_SUCCESS_TAG
        );
    }

    #[test]
    fn current_and_unknown_encoder_errors_remain_failures() {
        assert_eq!(
            classify_encode_error("Packet mask selects a missing section"),
            PacketEncodeV2Error::MissingSelectedSection
        );
        assert_eq!(
            classify_encode_error("Output buffer overflow"),
            PacketEncodeV2Error::OutputCapacity
        );
        assert_eq!(
            classify_encode_error("Output buffer overflow writing biomes"),
            PacketEncodeV2Error::OutputCapacity
        );
        for error in [
            "Snapshot stale: mutation during packet encode",
            "future encoder error",
        ] {
            assert_eq!(
                classify_encode_error(error),
                PacketEncodeV2Error::EncodeFailure
            );
        }
    }

    #[test]
    fn poisoned_native_state_is_rejected_without_success_metadata() {
        let registry = ChunkRegistry::new();
        let handle = registry.insert(NativeChunk::new(0, 0, 0, 1));
        let entry = registry.get(&handle).unwrap();
        let _ = catch_unwind(|| {
            let _guard = entry.write().unwrap();
            panic!("test-only poisoned native chunk");
        });
        assert_eq!(
            encode_in_registry(&registry, handle, false, false, &mut [0; 16]),
            Err(PacketEncodeV2Error::EncodeFailure)
        );
    }
}
