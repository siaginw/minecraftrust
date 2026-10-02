//! Structural regressions on synthetic immutable native state, not a live Java
//! capture or reproduction of the historical section-5 writer.
use native_chunk::{NativeChunk, PacketEncodeResult, BIOME_ARRAY_SIZE};

const BIOME: u8 = 42;
const BUFFER_SIZE: usize = 256 * 1024;

fn fixture(mask: u16) -> NativeChunk {
    let mut chunk = NativeChunk::new(0, 6, 4, 57);
    chunk.fill_biomes(BIOME);
    for y in 0..16 {
        if mask & (1u16 << y) != 0 {
            refresh_fixture_section(&mut chunk, y);
        }
    }
    chunk
}

fn refresh_fixture_section(chunk: &mut NativeChunk, y: u8) {
    // Distinct constant state and light markers identify each section on wire.
    chunk.refresh_section(
        y,
        &[u16::from(y) + 1; 4096],
        Some(&[y; 2048]),
        Some(&[255 - y; 2048]),
    );
}

fn take<'a>(input: &mut &'a [u8], count: usize) -> &'a [u8] {
    assert!(input.len() >= count, "truncated synthetic section payload");
    let (head, tail) = input.split_at(count);
    *input = tail;
    head
}

fn varint(input: &mut &[u8]) -> u32 {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = take(input, 1)[0];
        assert!(shift < 28 || byte <= 0x0f, "oversized VarInt");
        value |= u32::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return value;
        }
    }
    panic!("unterminated VarInt");
}

/// A small independent scanner for these deliberately uniform fixtures. It
/// consumes the entire payload WITHOUT using the returned mask as a loop bound
/// or calling the production section encoder/decoder to construct an expected
/// result. Thus a missing/extra section cannot hide behind matching mask logic.
fn scan_fixture_payload(payload: &[u8], skylight: bool, full_chunk: bool) -> Vec<u8> {
    let mut input = if full_chunk {
        assert!(payload.len() >= BIOME_ARRAY_SIZE);
        let (sections, biomes) = payload.split_at(payload.len() - BIOME_ARRAY_SIZE);
        assert_eq!(biomes, &[BIOME; BIOME_ARRAY_SIZE]);
        sections
    } else {
        payload
    };
    let mut sections = Vec::new();
    while !input.is_empty() {
        assert_eq!(take(&mut input, 1)[0], 4, "fixture uses four-bit palette");
        assert_eq!(varint(&mut input), 2, "air plus one constant state");
        assert_eq!(varint(&mut input), 0, "air palette entry");
        let state = varint(&mut input);
        assert!((1..=16).contains(&state));
        let y = (state - 1) as u8;
        assert_eq!(varint(&mut input), 256, "4096 four-bit entries");
        for _ in 0..256 {
            let word = u64::from_be_bytes(take(&mut input, 8).try_into().unwrap());
            assert_eq!(word, 0x1111_1111_1111_1111, "all blocks use palette[1]");
        }
        assert!(take(&mut input, 2048).iter().all(|&byte| byte == y));
        if skylight {
            assert!(take(&mut input, 2048).iter().all(|&byte| byte == 255 - y));
        }
        sections.push(y);
        assert!(sections.len() <= 16, "extra sections in payload");
    }
    sections
}

fn assert_encoded(
    chunk: &mut NativeChunk,
    selected_mask: u16,
    skylight: bool,
    full_chunk: bool,
) -> usize {
    let mut out = vec![0xa5; BUFFER_SIZE];
    let mut offset = 0;
    let result = chunk
        .encode_packet_payload(skylight, full_chunk, &mut out, &mut offset)
        .unwrap();
    assert_eq!(result.emitted_mask, selected_mask);
    assert_eq!(result.bytes_written, offset);
    let sections = scan_fixture_payload(&out[..result.bytes_written], skylight, full_chunk);
    let expected: Vec<u8> = (0..16)
        .filter(|y| selected_mask & (1u16 << y) != 0)
        .collect();
    assert_eq!(sections, expected, "exact selected sections, ascending Y");
    assert_eq!(sections.len(), result.emitted_mask.count_ones() as usize);
    assert!(out[offset..].iter().all(|&byte| byte == 0xa5));
    result.bytes_written
}

#[test]
fn selected_missing_section_is_rejected_before_writes() {
    for y in 0..16 {
        let mut chunk = fixture(0);
        chunk.primary_bit_mask = 1u16 << y;
        let mut out = [0xa5; 32];
        let mut offset = 3;
        assert_eq!(
            chunk.encode_packet_payload(true, true, &mut out, &mut offset),
            Err("Packet mask selects a missing section")
        );
        assert_eq!(offset, 3);
        assert_eq!(out, [0xa5; 32]);
    }
}

#[test]
fn historical_003f_shape_with_only_sections_zero_through_four_is_rejected() {
    let mut chunk = fixture(0x001f);
    chunk.primary_bit_mask = 0x003f;
    let mut out = vec![0xa5; BUFFER_SIZE];
    let mut offset = 7;
    assert_eq!(
        chunk.encode_packet_payload(true, true, &mut out, &mut offset),
        Err("Packet mask selects a missing section")
    );
    assert_eq!(offset, 7);
    assert!(out.iter().all(|&byte| byte == 0xa5));
}

#[test]
fn valid_001f_emits_exactly_five_sections() {
    assert_encoded(&mut fixture(0x001f), 0x001f, true, true);
}

#[test]
fn sparse_mask_excludes_unselected_resident_sections() {
    let mut chunk = fixture(0xffff);
    chunk.primary_bit_mask = 0x8421; // Y=0,5,10,15; all other sections still exist.
    assert_encoded(&mut chunk, 0x8421, true, true);
}

#[test]
fn every_active_bit_matches_one_serialized_section() {
    let masks = [0, 0x001f, 0x003f, 0x8001, 0x5555, 0xaaaa, 0xffff]
        .into_iter()
        .chain((0..16).map(|y| 1u16 << y));
    for mask in masks {
        for skylight in [false, true] {
            for full_chunk in [false, true] {
                assert_encoded(&mut fixture(mask), mask, skylight, full_chunk);
            }
        }
    }
}

#[test]
fn capacity_failure_has_no_success_result_or_published_offset() {
    let mut chunk = fixture(1);
    let mut out = [0xa5; 32];
    let mut offset = 5;
    assert!(chunk
        .encode_packet_payload(true, true, &mut out, &mut offset)
        .is_err());
    assert_eq!(offset, 5);
    assert_eq!(&out[..5], &[0xa5; 5]);
}

#[test]
fn late_biome_capacity_failure_does_not_publish_a_shorter_payload() {
    let mut chunk = fixture(1);
    let bytes_written = assert_encoded(&mut chunk, 1, true, true);
    let prefix = 9;
    let mut out = vec![0xa5; prefix + bytes_written - 1];
    let mut offset = prefix;
    assert_eq!(
        chunk.encode_packet_payload(true, true, &mut out, &mut offset),
        Err("Output buffer overflow writing biomes")
    );
    assert_eq!(offset, prefix);
    assert_eq!(&out[..prefix], &[0xa5; 9]);
    assert_eq!(
        out[prefix], 4,
        "section scratch was written before late error"
    );
}

#[test]
fn retry_after_capacity_failure_succeeds() {
    let mut chunk = fixture(0x8001);
    let mut small = [0u8; 1];
    let mut offset = 0;
    assert!(chunk
        .encode_packet_payload(true, true, &mut small, &mut offset)
        .is_err());
    assert_eq!(offset, 0);
    assert_encoded(&mut chunk, 0x8001, true, true);
}

#[test]
fn retry_after_missing_section_is_restored_succeeds() {
    let mut chunk = fixture(0x001f);
    chunk.primary_bit_mask = 0x003f;
    let mut out = vec![0; BUFFER_SIZE];
    let mut offset = 0;
    assert!(chunk
        .encode_packet_payload(true, true, &mut out, &mut offset)
        .is_err());
    refresh_fixture_section(&mut chunk, 5);
    assert_encoded(&mut chunk, 0x003f, true, true);
}

#[test]
fn empty_nonempty_refresh_transitions_keep_mask_and_payload_consistent() {
    let mut chunk = fixture(0x0021);
    assert_encoded(&mut chunk, 0x0021, true, true);
    chunk.mark_section_mutation(5);
    chunk.refresh_section(5, &[0; 4096], None, None);
    assert_eq!(chunk.sections[5].as_ref().map(|s| s.non_air_count).unwrap_or(0), 0);
    assert_eq!(chunk.primary_bit_mask, 1);
    assert_encoded(&mut chunk, 1, true, true);
    chunk.mark_section_mutation(5);
    refresh_fixture_section(&mut chunk, 5);
    assert!(chunk.sections[5].is_some());
    assert_eq!(chunk.primary_bit_mask, 0x0021);
    assert_encoded(&mut chunk, 0x0021, true, true);
    chunk.refresh_section(0, &[0; 4096], None, None);
    chunk.refresh_section(5, &[0; 4096], None, None);
    assert_eq!(chunk.primary_bit_mask, 0);
    assert_encoded(&mut chunk, 0, true, true);
}

#[test]
fn result_pairs_byte_count_and_emitted_mask_for_that_operation() {
    let mut chunk = fixture(0x0021);
    let mut out = vec![0xa5; BUFFER_SIZE];
    let start = 13;
    let mut offset = start;
    let result: PacketEncodeResult = chunk
        .encode_packet_payload(false, true, &mut out, &mut offset)
        .unwrap();
    assert_eq!(result.bytes_written, offset - start);
    assert_eq!(result.emitted_mask, 0x0021);
    assert_eq!(&out[..start], &[0xa5; 13]);

    // A later state change cannot change the metadata belonging to old bytes.
    chunk.refresh_section(5, &[0; 4096], None, None);
    assert_eq!(chunk.primary_bit_mask, 1);
    assert_eq!(result.emitted_mask, 0x0021);
    assert_eq!(
        scan_fixture_payload(&out[start..start + result.bytes_written], false, true),
        vec![0, 5]
    );
    assert_encoded(&mut chunk, 1, false, true);
}

#[test]
fn empty_mask_supports_zero_bytes_or_biomes_only() {
    let mut chunk = fixture(0);
    let mut offset = 0;
    assert_eq!(
        chunk.encode_packet_payload(false, false, &mut [], &mut offset),
        Ok(PacketEncodeResult {
            bytes_written: 0,
            emitted_mask: 0
        })
    );
    let mut biomes = [0u8; BIOME_ARRAY_SIZE];
    assert_eq!(
        chunk.encode_packet_payload(false, true, &mut biomes, &mut offset),
        Ok(PacketEncodeResult {
            bytes_written: BIOME_ARRAY_SIZE,
            emitted_mask: 0
        })
    );
    assert_eq!(offset, BIOME_ARRAY_SIZE);
    assert_eq!(biomes, [BIOME; BIOME_ARRAY_SIZE]);
}

#[test]
fn invalid_start_offset_is_rejected_without_panicking() {
    for invalid in [17, usize::MAX] {
        for mask in [0, 1] {
            let mut chunk = fixture(mask);
            let mut out = [0xa5; 16];
            let mut offset = invalid;
            assert_eq!(
                chunk.encode_packet_payload(true, true, &mut out, &mut offset),
                Err("Packet output offset out of bounds")
            );
            assert_eq!(offset, invalid);
            assert_eq!(out, [0xa5; 16]);
        }
    }
}

#[test]
fn failed_append_preserves_the_previous_successful_payload() {
    let mut chunk = fixture(1);
    let mut out = vec![0xa5; BUFFER_SIZE];
    let mut offset = 0;
    let first = chunk
        .encode_packet_payload(true, true, &mut out, &mut offset)
        .unwrap();
    let original = out.clone();
    chunk.primary_bit_mask |= 0x0020; // No section5: the next encode must fail.
    assert!(chunk
        .encode_packet_payload(true, true, &mut out, &mut offset)
        .is_err());
    assert_eq!(offset, first.bytes_written);
    assert_eq!(out, original);
    assert_eq!(scan_fixture_payload(&out[..offset], true, true), vec![0]);
}
