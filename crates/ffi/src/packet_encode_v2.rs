//! Versioned, single-operation chunk payload metadata. No packet authority.
//!
//! Success: bit 62 = 1; bits 46..16 = nonnegative Java int byte count;
//! bits 15..0 = emitted mask. Bits 63 and 61..47 must be zero. A zero
//! byte count requires a zero mask. Failure: exactly one of -1 through -7.
//! Every other encoding is reserved. See docs/research/issue1-jni-v2-contract.md.

use crate::native_chunk::get_registry;
use native_chunk::{ChunkHandle, ChunkKey, ChunkLifecycle, ChunkRegistry, PacketEncodeResult};
use std::ffi::c_void;
use std::panic::{catch_unwind, UnwindSafe};

pub const PACKET_V2_SUCCESS_TAG: i64 = 1i64 << 62;
pub const PACKET_V2_MAX_BYTES: usize = i32::MAX as usize;

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

fn result_boundary<F>(operation: F) -> i64
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

fn encode_in_registry(
    registry: &ChunkRegistry,
    handle: ChunkHandle,
    skylight: bool,
    full_chunk: bool,
    output: &mut [u8],
) -> Result<PacketEncodeResult, PacketEncodeV2Error> {
    // Hold native membership through encoding. This excludes native removal or
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
    let mut offset = 0;
    // Exactly one encoder invocation; no independent mask lookup, recomputation,
    // second serialization, or retry. Both fields pass through this return.
    chunk
        .encode_packet_payload(skylight, full_chunk, output, &mut offset)
        .map_err(classify_encode_error)
}

fn checked_output_address(address: i64, capacity: i32) -> Result<usize, PacketEncodeV2Error> {
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
    result_boundary(|| {
        let address = checked_output_address(output_buf_address, output_buf_capacity)?;
        if skylight > 1 || full_chunk > 1 {
            return Err(PacketEncodeV2Error::InvalidArgument);
        }
        if generation_id <= 0 {
            return Err(PacketEncodeV2Error::StaleGeneration);
        }
        let output =
            std::slice::from_raw_parts_mut(address as *mut u8, output_buf_capacity as usize);
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
    })
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
