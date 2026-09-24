//! Offline-only owned input adapter. No retained-cache handle or packet shell.
use crate::packet_encode_v2::{checked_output_address, result_boundary, PacketEncodeV2Error};
use native_chunk::packet_snapshot::MAX_SNAPSHOT_BYTES;
use native_chunk::{OwnedPacketSnapshot, SnapshotRejection};
use std::ffi::c_void;

fn classify(error: SnapshotRejection) -> PacketEncodeV2Error {
    match error {
        SnapshotRejection::ChunkReplaced => PacketEncodeV2Error::StaleGeneration,
        SnapshotRejection::MissingSection => PacketEncodeV2Error::MissingSelectedSection,
        SnapshotRejection::Capacity => PacketEncodeV2Error::OutputCapacity,
        _ => PacketEncodeV2Error::EncodeFailure,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ptr::null_mut;

    fn empty_transport() -> Vec<u8> {
        let mut input = vec![0; 130];
        input[..8].copy_from_slice(b"RCSNAP01");
        input[9] = 1;
        input[11] = 1;
        input[12] = 14;
        input[13] = 1;
        for offset in [35, 47, 55, 63, 87, 95] {
            input[offset] = 1;
        }
        input
    }

    unsafe fn invoke(input: &[u8], out: &mut [u8]) -> i64 {
        Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1(
            null_mut(),
            null_mut(),
            input.as_ptr() as i64,
            input.len() as i32,
            out.as_mut_ptr() as i64,
            out.len() as i32,
        )
    }

    #[test]
    fn owned_empty_v2_success_and_malformed_retry() {
        let valid = empty_transport();
        let mut input = valid.clone();
        let mut out = [0xa5; 4];
        input[0] = 0;
        assert_eq!(unsafe { invoke(&input, &mut out) }, -6);
        assert_eq!(out, [0xa5; 4]);
        assert_eq!(
            unsafe { invoke(&valid, &mut out) },
            crate::PACKET_V2_SUCCESS_TAG
        );
        assert_eq!(out, [0xa5; 4]);
    }

    #[test]
    fn owned_pointer_overlap_and_numeric_errors_fail_before_dereference() {
        let mut allocation = empty_transport();
        let address = allocation.as_mut_ptr() as i64;
        for (input, len, output, cap) in [
            (0, 130, address, 1),
            (address, -1, address, 1),
            (address, 130, 0, 1),
            (address, 130, address, 0),
            (address, 130, address + 1, 1),
            (address, 130, i64::MAX, 1),
            (address, (MAX_SNAPSHOT_BYTES + 1) as i32, address, 1),
        ] {
            assert_eq!(
                unsafe {
                    Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1(
                        null_mut(),
                        null_mut(),
                        input,
                        len,
                        output,
                        cap,
                    )
                },
                -1
            );
        }
    }

    #[test]
    fn owned_lifecycle_failure_carries_no_success_fields() {
        let mut input = empty_transport();
        input[95] = 2;
        assert_eq!(unsafe { invoke(&input, &mut [0; 4]) }, -3);
        input[95] = 1;
        input[36..40].copy_from_slice(&[0, 1, 0, 1]);
        assert_eq!(unsafe { invoke(&input, &mut [0; 4]) }, -4);
    }
}

/// Read one RCSNAP01 owned synthetic transport and serialize exactly once.
/// Success uses the existing V2 bytes/mask tuple; all failure output is scratch.
/// Rich admission reasons remain available in the Rust snapshot parser. This
/// export accepts synthetic and clean-Forge owned-oracle transports and grants no
/// production eligibility. The scope byte is not authentication or a proof that
/// an arbitrary caller performed coherent capture.
///
/// # Safety
/// The input must be a live readable allocation of input_len bytes, held stable
/// throughout this call. The output must be a live, exclusively writable
/// allocation of output_capacity bytes. Buffers must not overlap. Numeric bounds
/// checks cannot prove allocation validity. No input pointers are retained.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1(
    _env: *mut c_void,
    _class: *mut c_void,
    input_address: i64,
    input_len: i32,
    output_address: i64,
    output_capacity: i32,
) -> i64 {
    result_boundary(|| {
        if input_len <= 0 || input_len as usize > MAX_SNAPSHOT_BYTES {
            return Err(PacketEncodeV2Error::InvalidArgument);
        }
        let input_start = checked_output_address(input_address, input_len)?;
        let output_start = checked_output_address(output_address, output_capacity)?;
        if input_start < output_start + output_capacity as usize
            && output_start < input_start + input_len as usize
        {
            return Err(PacketEncodeV2Error::InvalidArgument);
        }
        // The parser owns every section, light and biome byte before output
        // serialization starts. No registry lookup or global-width setter.
        let snapshot = {
            let input = std::slice::from_raw_parts(input_start as *const u8, input_len as usize);
            OwnedPacketSnapshot::from_transport(input).map_err(classify)?
        };
        let output =
            std::slice::from_raw_parts_mut(output_start as *mut u8, output_capacity as usize);
        snapshot.encode(output).map_err(classify)
    })
}
