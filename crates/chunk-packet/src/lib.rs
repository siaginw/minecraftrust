//! Protocol 340 SPacketChunkData vanilla section payload encoder.
//! Implements INPUT-A staging buffer decoding and direct wire formatting.

use std::fmt;

/// Authoritative schema version for INPUT-A staging buffer.
pub const M1_CHUNK_STAGING_V1: u16 = 1;

/// Strict maximum buffer capacities.
pub const MAX_STAGING_CAPACITY: usize = 262_144; // 256 KB
pub const MAX_OUTPUT_CAPACITY: usize = 262_144; // 256 KB

#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkPacketError {
    NullBufferPointer = -1,
    OutputBufferOverflow = -2,
    InvalidSectionBitmask = -3,
    UnsupportedPaletteWidth = -4,
    RustPanicCaught = -5,
    CorruptStagingData = -6,
    UnsupportedAbiVersion = -7,
    InvalidSectionIndex = -8,
    PaletteOverflow = -9,
    MissingLightArray = -10,
}

impl fmt::Display for ChunkPacketError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}(code={})", self, *self as i32)
    }
}

impl std::error::Error for ChunkPacketError {}

/// Fast VarInt writer for Minecraft Protocol 340.
#[inline(always)]
pub fn write_varint(
    mut val: i32,
    out: &mut [u8],
    offset: &mut usize,
) -> Result<(), ChunkPacketError> {
    loop {
        if *offset >= out.len() {
            return Err(ChunkPacketError::OutputBufferOverflow);
        }
        if (val & !0x7F) == 0 {
            out[*offset] = val as u8;
            *offset += 1;
            return Ok(());
        }
        out[*offset] = ((val & 0x7F) | 0x80) as u8;
        *offset += 1;
        val = ((val as u32) >> 7) as i32;
    }
}

/// Encode section payload from INPUT-A staging buffer into output wire buffer.
pub fn encode_sections(
    staging_buf: &[u8],
    output_buf: &mut [u8],
) -> Result<usize, ChunkPacketError> {
    if staging_buf.len() < 6 {
        return Err(ChunkPacketError::CorruptStagingData);
    }

    // 1. Header validation
    let schema_version = u16::from_be_bytes([staging_buf[0], staging_buf[1]]);
    if schema_version != M1_CHUNK_STAGING_V1 {
        return Err(ChunkPacketError::UnsupportedAbiVersion);
    }

    let primary_bit_mask = u16::from_be_bytes([staging_buf[2], staging_buf[3]]);
    let flags = staging_buf[4];
    let section_count = staging_buf[5] as usize;

    let full_chunk = (flags & 0x01) != 0;
    let skylight_present = (flags & 0x02) != 0;

    // Check mask vs section count consistency
    if section_count > 16 || section_count != primary_bit_mask.count_ones() as usize {
        return Err(ChunkPacketError::InvalidSectionBitmask);
    }

    let mut in_pos = 6usize;
    let mut out_pos = 0usize;
    let mut observed_mask = 0u16;

    for _ in 0..section_count {
        if in_pos + 4 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }

        let section_idx = staging_buf[in_pos];
        if section_idx >= 16 {
            return Err(ChunkPacketError::InvalidSectionIndex);
        }
        let section_bit = 1u16 << section_idx;
        if (primary_bit_mask & section_bit) == 0 || (observed_mask & section_bit) != 0 {
            return Err(ChunkPacketError::InvalidSectionIndex);
        }
        observed_mask |= section_bit;

        let bits_per_block = staging_buf[in_pos + 1];
        if bits_per_block < 4 || bits_per_block > 16 {
            return Err(ChunkPacketError::UnsupportedPaletteWidth);
        }

        let palette_count =
            u16::from_be_bytes([staging_buf[in_pos + 2], staging_buf[in_pos + 3]]) as usize;
        in_pos += 4;

        if out_pos >= output_buf.len() {
            return Err(ChunkPacketError::OutputBufferOverflow);
        }
        output_buf[out_pos] = bits_per_block;
        out_pos += 1;

        // Palette serialization (In Minecraft 1.12.2, all palettes including BlockStatePaletteRegistry
        // write the palette length VarInt; for global palette, palette_count is 0).
        if palette_count > 256 && bits_per_block < 9 {
            return Err(ChunkPacketError::PaletteOverflow);
        }
        write_varint(palette_count as i32, output_buf, &mut out_pos)?;

        if bits_per_block < 9 {
            let palette_bytes = palette_count * 4;
            if in_pos + palette_bytes > staging_buf.len() {
                return Err(ChunkPacketError::CorruptStagingData);
            }
            for _ in 0..palette_count {
                let state_id = u32::from_be_bytes([
                    staging_buf[in_pos],
                    staging_buf[in_pos + 1],
                    staging_buf[in_pos + 2],
                    staging_buf[in_pos + 3],
                ]) as i32;
                in_pos += 4;
                write_varint(state_id, output_buf, &mut out_pos)?;
            }
        } else {
            // Global palette: staging buffer has palette_count=0 (or skipped)
            let palette_bytes = palette_count * 4;
            if in_pos + palette_bytes > staging_buf.len() {
                return Err(ChunkPacketError::CorruptStagingData);
            }
            in_pos += palette_bytes;
        }

        // BitArray storage words
        if in_pos + 2 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        let storage_words_count =
            u16::from_be_bytes([staging_buf[in_pos], staging_buf[in_pos + 1]]) as usize;
        in_pos += 2;

        if storage_words_count > 1024 {
            return Err(ChunkPacketError::CorruptStagingData);
        }

        write_varint(storage_words_count as i32, output_buf, &mut out_pos)?;

        let storage_bytes = storage_words_count * 8;
        if in_pos + storage_bytes > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        if out_pos + storage_bytes > output_buf.len() {
            return Err(ChunkPacketError::OutputBufferOverflow);
        }

        output_buf[out_pos..out_pos + storage_bytes]
            .copy_from_slice(&staging_buf[in_pos..in_pos + storage_bytes]);
        in_pos += storage_bytes;
        out_pos += storage_bytes;

        // Block light (2048 bytes)
        if in_pos + 2048 > staging_buf.len() {
            return Err(ChunkPacketError::MissingLightArray);
        }
        if out_pos + 2048 > output_buf.len() {
            return Err(ChunkPacketError::OutputBufferOverflow);
        }
        output_buf[out_pos..out_pos + 2048].copy_from_slice(&staging_buf[in_pos..in_pos + 2048]);
        in_pos += 2048;
        out_pos += 2048;

        // Sky light (2048 bytes, if present)
        if skylight_present {
            if in_pos + 2048 > staging_buf.len() {
                return Err(ChunkPacketError::MissingLightArray);
            }
            if out_pos + 2048 > output_buf.len() {
                return Err(ChunkPacketError::OutputBufferOverflow);
            }
            output_buf[out_pos..out_pos + 2048]
                .copy_from_slice(&staging_buf[in_pos..in_pos + 2048]);
            in_pos += 2048;
            out_pos += 2048;
        }
    }

    // Biomes (256 bytes, if full chunk)
    if full_chunk {
        if in_pos + 256 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        if out_pos + 256 > output_buf.len() {
            return Err(ChunkPacketError::OutputBufferOverflow);
        }
        output_buf[out_pos..out_pos + 256].copy_from_slice(&staging_buf[in_pos..in_pos + 256]);
        in_pos += 256;
        out_pos += 256;
    }

    // Ensure zero unaccounted trailing bytes
    if in_pos != staging_buf.len() {
        return Err(ChunkPacketError::CorruptStagingData);
    }

    Ok(out_pos)
}

/// Unsafe FFI wrapper verifying non-null pointers and bounds.
///
/// # Safety
/// Caller must ensure `staging_ptr` and `output_ptr` are valid, mapped, and aligned for their declared lengths.
pub unsafe fn encode_sections_raw(
    staging_ptr: *const u8,
    staging_len: usize,
    output_ptr: *mut u8,
    output_cap: usize,
) -> Result<usize, ChunkPacketError> {
    if staging_ptr.is_null() || output_ptr.is_null() {
        return Err(ChunkPacketError::NullBufferPointer);
    }
    if staging_len < 6 {
        return Err(ChunkPacketError::CorruptStagingData);
    }
    if staging_len > MAX_STAGING_CAPACITY {
        return Err(ChunkPacketError::CorruptStagingData);
    }
    if output_cap < 256 {
        return Err(ChunkPacketError::OutputBufferOverflow);
    }

    let staging_slice = std::slice::from_raw_parts(staging_ptr, staging_len);
    let output_slice = std::slice::from_raw_parts_mut(output_ptr, output_cap);

    encode_sections(staging_slice, output_slice)
}

/// Exact output-size prediction (M1.4-R2 §5): pure scan mirroring
/// encode_sections byte accounting, zero writes. Caller allocates the final
/// heap byte[] with this length BEFORE encoding.
pub fn predict_output_len(staging_buf: &[u8]) -> Result<usize, ChunkPacketError> {
    if staging_buf.len() < 6 {
        return Err(ChunkPacketError::CorruptStagingData);
    }
    let schema_version = u16::from_be_bytes([staging_buf[0], staging_buf[1]]);
    if schema_version != M1_CHUNK_STAGING_V1 {
        return Err(ChunkPacketError::UnsupportedAbiVersion);
    }
    let primary_bit_mask = u16::from_be_bytes([staging_buf[2], staging_buf[3]]);
    let flags = staging_buf[4];
    let section_count = staging_buf[5] as usize;
    let full_chunk = (flags & 0x01) != 0;
    let skylight_present = (flags & 0x02) != 0;
    if section_count > 16 || section_count != primary_bit_mask.count_ones() as usize {
        return Err(ChunkPacketError::InvalidSectionBitmask);
    }

    let mut in_pos = 6usize;
    let mut out_len = 0usize;
    let mut observed_mask = 0u16;

    for _ in 0..section_count {
        if in_pos + 4 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        let section_idx = staging_buf[in_pos];
        if section_idx >= 16 {
            return Err(ChunkPacketError::InvalidSectionIndex);
        }
        let section_bit = 1u16 << section_idx;
        if (primary_bit_mask & section_bit) == 0 || (observed_mask & section_bit) != 0 {
            return Err(ChunkPacketError::InvalidSectionIndex);
        }
        observed_mask |= section_bit;

        let bits_per_block = staging_buf[in_pos + 1];
        if bits_per_block < 4 || bits_per_block > 16 {
            return Err(ChunkPacketError::UnsupportedPaletteWidth);
        }
        let palette_count =
            u16::from_be_bytes([staging_buf[in_pos + 2], staging_buf[in_pos + 3]]) as usize;
        if palette_count > 256 && bits_per_block < 9 {
            return Err(ChunkPacketError::PaletteOverflow);
        }
        in_pos += 4;

        out_len += 1; // bits per block byte
        out_len += varint_len(palette_count as i32); // palette length varint
        if bits_per_block < 9 {
            let palette_bytes = palette_count * 4;
            if in_pos + palette_bytes > staging_buf.len() {
                return Err(ChunkPacketError::CorruptStagingData);
            }
            for _ in 0..palette_count {
                let state_id = u32::from_be_bytes([
                    staging_buf[in_pos],
                    staging_buf[in_pos + 1],
                    staging_buf[in_pos + 2],
                    staging_buf[in_pos + 3],
                ]) as i32;
                in_pos += 4;
                out_len += varint_len(state_id);
            }
        } else {
            let palette_bytes = palette_count * 4;
            if in_pos + palette_bytes > staging_buf.len() {
                return Err(ChunkPacketError::CorruptStagingData);
            }
            in_pos += palette_bytes;
        }

        if in_pos + 2 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        let storage_words_count =
            u16::from_be_bytes([staging_buf[in_pos], staging_buf[in_pos + 1]]) as usize;
        in_pos += 2;
        if storage_words_count > 1024 {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        out_len += varint_len(storage_words_count as i32);
        let storage_bytes = storage_words_count * 8;
        if in_pos + storage_bytes > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        in_pos += storage_bytes;
        out_len += storage_bytes;

        // block light + optional sky light
        let lights = 2048 + if skylight_present { 2048 } else { 0 };
        if in_pos + lights > staging_buf.len() {
            return Err(ChunkPacketError::MissingLightArray);
        }
        in_pos += lights;
        out_len += lights;
    }

    if full_chunk {
        if in_pos + 256 > staging_buf.len() {
            return Err(ChunkPacketError::CorruptStagingData);
        }
        in_pos += 256;
        out_len += 256;
    }

    if in_pos != staging_buf.len() {
        return Err(ChunkPacketError::CorruptStagingData);
    }
    Ok(out_len)
}

/// Byte length a value occupies as a Protocol 340 VarInt (non-negative).
#[inline]
pub fn varint_len(val: i32) -> usize {
    let mut v = val as u32; // counts/state ids here are always non-negative
    let mut len = 1;
    loop {
        v >>= 7;
        if v == 0 {
            return len;
        }
        len += 1;
        if len > 5 {
            return 5;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_valid_staging_header(mask: u16, flags: u8, count: u8) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(&M1_CHUNK_STAGING_V1.to_be_bytes());
        buf.extend_from_slice(&mask.to_be_bytes());
        buf.push(flags);
        buf.push(count);
        buf
    }

    #[test]
    fn test_abi_version_rejection() {
        let mut staging = vec![0u8; 10];
        staging[0] = 0;
        staging[1] = 99; // Unknown version 99
        let mut output = vec![0u8; 1024];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::UnsupportedAbiVersion));
    }

    #[test]
    fn test_predict_output_len_matches_encode() {
        // M1.4-R2 §5: predictor must equal actual encoded length exactly.
        let mut staging = create_valid_staging_header(1, 0x03, 1);
        staging.push(0); // section index
        staging.push(5); // bits per block
        staging.extend_from_slice(&3u16.to_be_bytes()); // 3 palette entries
        staging.extend_from_slice(&0u32.to_be_bytes());
        staging.extend_from_slice(&300u32.to_be_bytes()); // 2-byte varint id
        staging.extend_from_slice(&17000u32.to_be_bytes()); // 3-byte varint id
        staging.extend_from_slice(&256u16.to_be_bytes()); // 256 storage words
        staging.extend_from_slice(&[7u8; 256 * 8]);
        staging.extend_from_slice(&[1u8; 2048]); // block light
        staging.extend_from_slice(&[2u8; 2048]); // sky light
        staging.extend_from_slice(&[9u8; 256]); // biomes (full_chunk)

        let mut output = vec![0u8; 262144];
        let written = encode_sections(&staging, &mut output).expect("encode");
        let predicted = predict_output_len(&staging).expect("predict");
        assert_eq!(predicted, written, "predictor must match encoder");
    }

    #[test]
    fn test_empty_chunk_biomes_only() {
        let mut staging = create_valid_staging_header(0, 0x01, 0);
        staging.extend_from_slice(&[42u8; 256]);

        let mut output = vec![0u8; 1024];
        let written = encode_sections(&staging, &mut output).expect("encode failed");
        assert_eq!(written, 256);
        assert_eq!(&output[0..256], &[42u8; 256]);
    }

    #[test]
    fn test_single_section_with_skylight() {
        let mut staging = create_valid_staging_header(1, 0x03, 1);

        // Section 0
        staging.push(0); // section_index
        staging.push(4); // bits_per_block
        staging.extend_from_slice(&2u16.to_be_bytes()); // 2 palette entries
        staging.extend_from_slice(&0u32.to_be_bytes()); // air
        staging.extend_from_slice(&1u32.to_be_bytes()); // stone

        // 2 storage words
        staging.extend_from_slice(&2u16.to_be_bytes());
        staging.extend_from_slice(&0x0123456789ABCDEFu64.to_be_bytes());
        staging.extend_from_slice(&0xFEDCBA9876543210u64.to_be_bytes());

        // 2048 block light
        staging.extend_from_slice(&[0x11u8; 2048]);
        // 2048 sky light
        staging.extend_from_slice(&[0xFFu8; 2048]);

        // 256 biomes
        staging.extend_from_slice(&[7u8; 256]);

        let mut output = vec![0u8; 65536];
        let written = encode_sections(&staging, &mut output).expect("encode failed");
        assert!(written > 4096);
    }

    #[test]
    fn test_global_palette_wire_omission() {
        // Section with global palette (bits=13)
        let mut staging = create_valid_staging_header(1, 0x00, 1);
        staging.push(0); // index 0
        staging.push(13); // bits=13 (global)
        staging.extend_from_slice(&0u16.to_be_bytes()); // palette_count=0
        staging.extend_from_slice(&1u16.to_be_bytes()); // 1 storage word
        staging.extend_from_slice(&0x1111222233334444u64.to_be_bytes());
        staging.extend_from_slice(&[0x55u8; 2048]); // block light

        let mut output = vec![0u8; 4096];
        let written = encode_sections(&staging, &mut output).expect("encode failed");
        assert_eq!(output[0], 13);
        assert_eq!(output[1], 0); // palette_count VarInt 0 (BlockStatePaletteRegistry in 1.12.2)
        assert_eq!(output[2], 1); // storage_words_count VarInt 1
        assert_eq!(written, 1 + 1 + 1 + 8 + 2048);
    }

    #[test]
    fn test_malformed_truncated_staging() {
        let staging = vec![0, 1, 0, 1, 3, 1]; // Truncated header, missing section data
        let mut output = vec![0u8; 1024];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::CorruptStagingData));
    }

    #[test]
    fn test_capacity_boundary_conditions() {
        let mut staging = create_valid_staging_header(0, 0x01, 0);
        staging.extend_from_slice(&[7u8; 256]); // 256 bytes biome

        // Exact capacity required: 256
        let mut exact_buf = vec![0u8; 256];
        let written =
            encode_sections(&staging, &mut exact_buf).expect("exact capacity should succeed");
        assert_eq!(written, 256);

        // Required - 1: 255 -> must fail with OutputBufferOverflow
        let mut undersized_buf = vec![0u8; 255];
        let res = encode_sections(&staging, &mut undersized_buf);
        assert_eq!(res, Err(ChunkPacketError::OutputBufferOverflow));

        // Large safe capacity
        let mut large_buf = vec![0u8; 65536];
        let written_large =
            encode_sections(&staging, &mut large_buf).expect("large buf should succeed");
        assert_eq!(written_large, 256);
    }

    #[test]
    fn test_malformed_section_mask_mismatch() {
        // Mask specifies 2 bits set (0x03), but count is 1
        let staging = create_valid_staging_header(0x03, 0x00, 1);
        let mut output = vec![0u8; 1024];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::InvalidSectionBitmask));
    }

    #[test]
    fn test_malformed_duplicate_section_index() {
        // Mask 0x01, count 1, but section claims index 1 (not in mask)
        let mut staging = create_valid_staging_header(0x01, 0x00, 1);
        staging.push(1); // index 1 not in mask 0x01
        staging.push(4); // bits
        staging.extend_from_slice(&0u16.to_be_bytes()); // palette
        staging.extend_from_slice(&0u16.to_be_bytes()); // words
        staging.extend_from_slice(&[0u8; 2048]); // light
        let mut output = vec![0u8; 4096];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::InvalidSectionIndex));
    }

    #[test]
    fn test_unsupported_palette_width() {
        let mut staging = create_valid_staging_header(0x01, 0x00, 1);
        staging.push(0);
        staging.push(3); // bits=3 is illegal in Minecraft 1.12.2 (< 4)
        staging.extend_from_slice(&0u16.to_be_bytes());
        staging.extend_from_slice(&0u16.to_be_bytes());
        staging.extend_from_slice(&[0u8; 2048]);
        let mut output = vec![0u8; 4096];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::UnsupportedPaletteWidth));
    }

    #[test]
    fn test_palette_overflow() {
        let mut staging = create_valid_staging_header(0x01, 0x00, 1);
        staging.push(0);
        staging.push(4); // bits=4 (indirect palette)
        staging.extend_from_slice(&257u16.to_be_bytes()); // 257 entries > 256!
        let mut output = vec![0u8; 4096];
        let res = encode_sections(&staging, &mut output);
        assert_eq!(res, Err(ChunkPacketError::PaletteOverflow));
    }
}
